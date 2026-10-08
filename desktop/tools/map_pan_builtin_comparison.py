"""Record a dynamic built-in map and compare it with the supplied replay.

Both measurement cases use actual replay playback and the same pan harness.
Unit composition and alliances differ: this tests map exclusivity, not texture causality.
"""
import argparse
from collections import Counter
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import re
import time

from map_pan_comparison import (PROJECT, NO_CONSOLE, REPLAY_ALIAS, isolated_seed,
    run_environment, copy_replay_alias, analyze_run, write_json)
from vulkan_native_matrix import java_command
from analyze_vulkan_matrix import read_json_lines


def add_camera_arguments(parser, period_default=20):
    parser.add_argument('--camera-period-seconds', type=int, default=period_default)
    parser.add_argument('--camera-mode', choices=('pan', 'jump', 'edge-jump', 'pan-zoom'), default='pan')
    parser.add_argument('--zoom-mode', choices=('none', 'cycle'), default=None)
    parser.add_argument('--zoom-period-seconds', type=int, default=4)
    parser.add_argument('--zoom-min', type=float, default=.35)
    parser.add_argument('--zoom-max', type=float, default=1.5)


def camera_protocol(args):
    if args.camera_period_seconds < 1 or args.zoom_period_seconds < 1:
        raise ValueError('camera and zoom periods must be >= 1 second')
    if not (math.isfinite(args.zoom_min) and math.isfinite(args.zoom_max) and 0 < args.zoom_min < args.zoom_max):
        raise ValueError('zoom limits must be finite and 0 < zoom-min < zoom-max')
    zoom_mode = args.zoom_mode or ('cycle' if args.camera_mode == 'pan-zoom' else 'none')
    if args.camera_mode == 'pan-zoom' and zoom_mode != 'cycle':
        raise ValueError('pan-zoom requires cycle zoom')
    return {'cameraMode': 'pan' if args.camera_mode == 'pan-zoom' else args.camera_mode,
            'cameraModeRequested': args.camera_mode, 'cameraPeriodSeconds': args.camera_period_seconds,
            'zoomMode': zoom_mode, 'zoomPeriodSeconds': args.zoom_period_seconds,
            'zoomMinimumRequested': args.zoom_min, 'zoomMaximumRequested': args.zoom_max}


def camera_environment(protocol):
    return {'RWX_REPLAY_PAN_CAMERA_MODE': protocol['cameraMode'],
            'RWX_REPLAY_PAN_PERIOD_SECONDS': str(protocol['cameraPeriodSeconds']),
            'RWX_REPLAY_PAN_ZOOM_MODE': protocol['zoomMode'],
            'RWX_REPLAY_PAN_ZOOM_PERIOD_SECONDS': str(protocol['zoomPeriodSeconds']),
            'RWX_REPLAY_PAN_ZOOM_MIN': str(protocol['zoomMinimumRequested']),
            'RWX_REPLAY_PAN_ZOOM_MAX': str(protocol['zoomMaximumRequested'])}


def confirm_camera_zoom(run, protocol):
    setup = run.get('setup') or {}
    confirmed = setup.get('zoomMode') == protocol['zoomMode']
    if protocol['zoomMode'] == 'cycle':
        confirmed = confirmed and setup.get('zoomPeriodSeconds') == protocol['zoomPeriodSeconds'] and all(
            isinstance(setup.get(key), (int, float)) and math.isclose(setup[key], requested, abs_tol=1e-6)
            for key, requested in (('zoomRequestedMinimum', protocol['zoomMinimumRequested']),
                                   ('zoomRequestedMaximum', protocol['zoomMaximumRequested']))) and bool(run.get('windows')) and all(
            window.get('zoomMode') == 'cycle' and isinstance(window.get('actualZoomMinimum'), (int, float))
            and isinstance(window.get('actualZoomMaximum'), (int, float))
            and math.isfinite(window['actualZoomMinimum']) and math.isfinite(window['actualZoomMaximum'])
            and window['actualZoomMaximum'] - window['actualZoomMinimum'] > .01 for window in run['windows'])
        if not confirmed:
            run['invalidReasons'].append('continuous zoom request was not confirmed by actual engine zoom changes')
            run['validMeasurement'] = False
    run['zoomModeConfirmed'] = confirmed


def confirm_experimental_modes(run, log, parallel_cell_raster=False, primitive_text_metrics=False, adaptive_cell_raster=False,
                               gpu_map_cell_cache=False):
    observed = {'parallelCellRaster': re.findall(r'\[RWX canvas\] parallelCellRaster=(true|false)', log),
                'primitiveTextMetrics': re.findall(r'RWXPrimitiveTextMetrics enabled=(true|false)', log),
                'adaptiveCellRaster': re.findall(r'\[RWX canvas\] adaptiveCellRaster=(true|false)', log)}
    run['actualExperimentalModes'] = observed
    for mode, requested in (('parallelCellRaster', parallel_cell_raster), ('primitiveTextMetrics', primitive_text_metrics),
                            ('adaptiveCellRaster', adaptive_cell_raster)):
        values = observed[mode]
        if (requested and not values) or (values and any(value != str(requested).lower() for value in values)):
            run['invalidReasons'].append(f'{mode} effective runtime mode differs from the requested case')
            run['validMeasurement'] = False
    confirm_gpu_map_cell_cache(run, log, gpu_map_cell_cache)


def confirm_gpu_map_cell_cache(run, log, requested=False):
    supported = re.findall(r'RWXVulkanConfiguration[^\r\n]*\bgpuMapCellSupported=(true|false)\b', log)
    fallback = re.findall(r'RWXGpuMapCellFallback\s+reason=(\S+)\s+cpu=(true|false)', log)
    ranges = []
    for window in sorted((w for w in run.get('windows', []) if isinstance(w.get('sampleStartNanos'), int)
                          and isinstance(w.get('sampleEndNanos'), int)), key=lambda w: w['sampleStartNanos']):
        start, end = window['sampleStartNanos'], window['sampleEndNanos']
        if end <= start:
            continue
        if ranges and start <= ranges[-1][1]:
            ranges[-1][1] = max(ranges[-1][1], end)
        else:
            ranges.append([start, end])
    samples = []
    for frame in run.get('frameWindows', []):
        counters = frame.get('gpuMapCellCache')
        if isinstance(frame.get('sampleNanos'), int) and isinstance(counters, dict) and all(
                isinstance(counters.get(key), (int, float)) and math.isfinite(counters[key]) and counters[key] >= 0
                for key in ('created', 'hits')):
            samples.append(frame)
    samples.sort(key=lambda frame: frame['sampleNanos'])
    measured = []
    totals = {'created': 0, 'hits': 0}
    monotonic = True
    measured_reuse = False
    for start, end in ranges:
        selected = [sample for sample in samples if start <= sample['sampleNanos'] <= end]
        growth = {key: selected[-1]['gpuMapCellCache'][key] - selected[0]['gpuMapCellCache'][key] if len(selected) > 1 else 0
                  for key in totals}
        monotonic = monotonic and all(b['gpuMapCellCache'][key] >= a['gpuMapCellCache'][key]
                                     for a, b in zip(selected, selected[1:]) for key in totals)
        for key in totals:
            totals[key] += growth[key]
        measured_reuse = measured_reuse or (growth['hits'] > 0 and
            selected[-1]['gpuMapCellCache']['created'] > 0)
        measured.append({'startNanos': start, 'endNanos': end, 'counterGrowth': growth, 'samples': selected})
    active = monotonic and measured_reuse
    confirmed = bool(supported) and all(value == 'true' for value in supported) and active
    run['gpuMapCellCacheEvidence'] = {'requested': requested, 'capabilityStates': supported,
        'actualActivityConfirmed': confirmed, 'measuredCounterGrowth': totals, 'countersMonotonic': monotonic,
        'measurementRanges': measured, 'processFallbackReasonCounts': dict(Counter(reason for reason, _ in fallback)),
        'processFallbackCpuStates': dict(Counter(cpu for _, cpu in fallback)),
        'notes': ['created/hits are lifetime counters; only growth between samples inside each measured interval counts.',
                  'Measured hit growth can confirm reuse of cells created during warmup; creation need not grow when all requested cells are already cached.',
                  'The initial constructor enabled state precedes Vulkan capability installation and does not determine activation.',
                  'Fallback logs lack monotonic timestamps; counts cover the complete process, including warmup.']}
    if requested and not confirmed:
        run['invalidReasons'].append('GPU map cell cache requires actual Vulkan support and growing hit counters for created cells inside measured windows')
        run['validMeasurement'] = False
    elif not requested and active:
        run['invalidReasons'].append('GPU map cell cache activity was observed in an OFF control case')
        run['validMeasurement'] = False


def confirm_dynamic_activity(run):
    def active(window):
        return any(isinstance(window.get(key), (int, float)) and window[key] > 0
                   for key in ('maximumMovingUnits', 'framesWithProjectiles'))
    confirmed = bool(run.get('windows')) and all(active(window) for window in run['windows'])
    run['dynamicActivityConfirmed'] = confirmed
    if not confirmed:
        run['invalidReasons'].append('dynamic scenario requires measured moving units or projectiles in every window')
        run['validMeasurement'] = False


def available_memory_mib():
    """Cheap native snapshot, avoiding WMI or a monitoring process during GPU tests."""
    if os.name != 'nt':
        return None
    class MemoryStatus(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ulong), ('load', ctypes.c_ulong)] + [
            (field, ctypes.c_ulonglong) for field in ('totalPhysical', 'availablePhysical', 'totalPageFile',
                'availablePageFile', 'totalVirtual', 'availableVirtual', 'availableExtendedVirtual')]
    status = MemoryStatus()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        return None
    return round(status.availablePhysical / 1048576, 1)


class ResourceSampler:
    """Native five-second samples; CPU percentages are across all logical CPUs."""
    def __init__(self, process, started):
        self.process = process
        self.started = started
        self.previous = None
        self.enabled = os.name == 'nt'
        if not self.enabled:
            return
        from ctypes import wintypes
        class FileTime(ctypes.Structure):
            _fields_ = [('low', wintypes.DWORD), ('high', wintypes.DWORD)]
            def ticks(self):
                return (self.high << 32) | self.low
        class ProcessMemory(ctypes.Structure):
            _fields_ = [('cb', wintypes.DWORD), ('pageFaultCount', wintypes.DWORD)] + [
                (field, ctypes.c_size_t) for field in ('peakWorkingSetSize', 'workingSetSize',
                    'quotaPeakPagedPoolUsage', 'quotaPagedPoolUsage', 'quotaPeakNonPagedPoolUsage',
                    'quotaNonPagedPoolUsage', 'pagefileUsage', 'peakPagefileUsage', 'privateUsage')]
        self.FileTime = FileTime
        self.ProcessMemory = ProcessMemory
        self.handle = wintypes.HANDLE(int(process._handle))
        self.system_times = ctypes.windll.kernel32.GetSystemTimes
        self.system_times.argtypes = [ctypes.POINTER(FileTime)] * 3
        self.system_times.restype = wintypes.BOOL
        self.process_times = ctypes.windll.kernel32.GetProcessTimes
        self.process_times.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(FileTime)] * 4
        self.process_times.restype = wintypes.BOOL
        self.process_memory = ctypes.windll.psapi.GetProcessMemoryInfo
        self.process_memory.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessMemory), wintypes.DWORD]
        self.process_memory.restype = wintypes.BOOL

    def sample(self):
        sample = {'elapsedSeconds': round(time.monotonic() - self.started, 3),
                  'epochMillis': time.time_ns() // 1_000_000,
                  'pythonMonotonicNanos': time.monotonic_ns(),
                  'availablePhysicalMemoryMiB': available_memory_mib()}
        if not self.enabled:
            return sample
        idle, kernel, user = (self.FileTime() for _ in range(3))
        created, exited, process_kernel, process_user = (self.FileTime() for _ in range(4))
        if self.system_times(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)) and self.process_times(
                self.handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(process_kernel), ctypes.byref(process_user)):
            current = (kernel.ticks() + user.ticks(), idle.ticks(), process_kernel.ticks() + process_user.ticks())
            if self.previous:
                total, idle_delta, process_delta = (a - b for a, b in zip(current, self.previous))
                if total > 0:
                    sample.update({'systemCpuPercent': round(100 * (total - idle_delta) / total, 2),
                                   'javaCpuPercent': round(100 * process_delta / total, 2)})
            self.previous = current
        memory = self.ProcessMemory()
        memory.cb = ctypes.sizeof(memory)
        if self.process_memory(self.handle, ctypes.byref(memory), memory.cb):
            sample.update({'javaWorkingSetMiB': round(memory.workingSetSize / 1048576, 1),
                           'javaPrivateMiB': round(memory.privateUsage / 1048576, 1),
                           'javaPageFaultCount': memory.pageFaultCount})
        return sample


def launch(command, env, output, name, timeout):
    print('START '+name, flush=True)
    start=time.monotonic()
    memory_before = available_memory_mib()
    with (output/f'{name}.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen(command,cwd=PROJECT,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=NO_CONSOLE)
        print('PID '+str(process.pid),flush=True)
        sampler = ResourceSampler(process, start)
        resource_samples = [sampler.sample()]
        minimum_memory = memory_before
        while True:
            remaining = timeout - (time.monotonic() - start)
            if remaining <= 0:
                process.kill(); process.wait()
                raise RuntimeError(f'{name} timed out')
            try:
                code = process.wait(timeout=min(5, remaining))
                break
            except subprocess.TimeoutExpired:
                sample = sampler.sample()
                resource_samples.append(sample)
                memory = sample['availablePhysicalMemoryMiB']
                if memory is not None:
                    minimum_memory = min(memory, minimum_memory) if minimum_memory is not None else memory
    if code: raise RuntimeError(f'{name} exited {code}; inspect saved log')
    return {'name':name,'pid':process.pid,'exitCode':code,'timedOut':False,'elapsedSeconds':time.monotonic()-start,
            'argv':command, 'availablePhysicalMemoryMiBBefore': memory_before,
            'minimumAvailablePhysicalMemoryMiB': minimum_memory,
            'availablePhysicalMemoryMiBAfter': available_memory_mib(), 'resourceSamples': resource_samples,
            'resourceSampleNotes': 'CPU percentages use all logical CPUs; page faults include soft faults and do not establish paging.'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--replay',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--java',required=True)
    parser.add_argument('--mode',choices=('moving','combat'),default='moving')
    parser.add_argument('--units', type=int, choices=(500, 661, 1000, 2000), default=500)
    parser.add_argument('--map',default='maps/skirmish/[p8]Interlocked Large (8p).tmx')
    parser.add_argument('--fog', choices=('off', 'on'), default=None, help='Default preserves the original replay-display protocol')
    add_camera_arguments(parser)
    parser.add_argument('--window-width', type=int, default=1920)
    parser.add_argument('--window-height', type=int, default=1080)
    parser.add_argument('--parallel-cell-raster', action='store_true')
    parser.add_argument('--primitive-text-metrics', action='store_true')
    parser.add_argument('--heap-mb', type=int, default=1000, help='Maximum heap in MiB, matching the pinned original probe')
    parser.add_argument('--disable-native-bgra-upload', action='store_true')
    parser.add_argument('--no-perf-window-log', action='store_true')
    args=parser.parse_args()
    if args.heap_mb < 256: parser.error('--heap-mb must be at least 256')
    try:
        camera = camera_protocol(args)
    except ValueError as error:
        parser.error(str(error))
    output=args.output.resolve(); output.mkdir(parents=True,exist_ok=False)
    digest=hashlib.sha256(args.jar.read_bytes()).hexdigest()
    jar=output/f'runtime-{digest[:16]}.jar'; shutil.copy2(args.jar,jar)
    original=output/'europe.replay'; shutil.copy2(args.replay,original)
    report={'runtimeSha256':digest,'mode':args.mode,'builtInMap':args.map,'recording':None,'runs':[],
            'protocol':{'units':args.units,'teams':15,'msaa':4,'targetFps':300,'vsync':False, **camera,
                        'fog':args.fog, 'heapMiB':args.heap_mb, 'parallelCellRasterRequested':args.parallel_cell_raster,
                        'primitiveTextMetricsRequested':args.primitive_text_metrics,
                        'logicalWindowWidth':args.window_width,'logicalWindowHeight':args.window_height,
                        'warmupSeconds':20,'sampleSeconds':20,'windowsPerProcess':2,
                        'order':['europe','builtin','builtin','europe'],
                        'limitation':'Unit types, alliances, terrain dimensions, fog and replay scripts differ; not a texture-only comparison'}}
    write_json(output/'summary.json',report)
    name='record-builtin'; sandbox=output/name; sandbox.mkdir()
    (sandbox/'preferences.toml').write_text(isolated_seed(),encoding='utf-8')
    command=java_command(args.java,jar,PROJECT/'desktop/build/slick-natives')
    command[1:1]=[f'-Dlaunch.dir={sandbox}',f'-Drwx.assetsDir={PROJECT / "assets"}',
                   '-Drwx.kool.backend=vulkan',f'-Drwx.benchmark.map={args.map}','-Xms512m',f'-Xmx{args.heap_mb}M']
    env=run_environment(output,sandbox,name)
    env.pop('RWX_REPLAY_PAN_OUTPUT')
    env.update(camera_environment(camera))
    env.update({'RWX_WINDOW_WIDTH':str(args.window_width),'RWX_WINDOW_HEIGHT':str(args.window_height)})
    if args.fog is not None: env['RWX_BENCHMARK_FOG'] = args.fog
    if args.parallel_cell_raster: env['RWX_PARALLEL_CELL_RASTER'] = '1'
    if args.primitive_text_metrics: env['RWX_PRIMITIVE_TEXT_METRICS'] = '1'
    if args.disable_native_bgra_upload: env['RWX_DISABLE_NATIVE_BGRA_UPLOAD'] = '1'
    if args.no_perf_window_log: env.pop('RWX_PERF_LOG', None)
    env.update({'RWX_MAP_PAN_OUTPUT':str(output/f'{name}-scenario.ndjson'),
                'RWX_BENCHMARK_UNITS':str(args.units),'RWX_BENCHMARK_MIX':'land-air',
                'RWX_BENCHMARK_PALETTE':'builtin:tank,builtin:hoverTank,builtin:heavyTank,builtin:helicopter,builtin:artillery,builtin:megaTank',
                'RWX_BENCHMARK_MODE':args.mode,'RWX_BENCHMARK_SELECTED':'0','RWX_BENCHMARK_TEAMS':'15',
                'RWX_BENCHMARK_RECORD_REPLAY':f'builtin-{args.units}.replay',
                'RWX_BENCHMARK_OUTPUT':str(output/'fixture.ndjson'),'RWX_DEBUG_AUTO_EXIT_SECONDS':'145'})
    report['recording']=launch(command,env,output,name,185)
    fixture=read_json_lines(output/'fixture.ndjson')
    finished=[r for r in fixture if r['kind']=='recording-finished']
    if len(finished)!=1: raise RuntimeError('Recording did not finish cleanly')
    recorded=Path(finished[0]['path'])
    builtin=output/'builtin.replay'; shutil.copy2(recorded,builtin)
    report['recording'].update({'fixture':fixture[0],'completion':finished[0],
                                'replaySha256':hashlib.sha256(builtin.read_bytes()).hexdigest()})
    write_json(output/'summary.json',report)
    for i,variant in enumerate(report['protocol']['order'],1):
        name=f'{i:02d}-{variant}'; sandbox=output/name; sandbox.mkdir()
        replay=original if variant=='europe' else builtin
        replay_digest=hashlib.sha256(replay.read_bytes()).hexdigest()
        alias=copy_replay_alias(replay,sandbox,replay_digest)
        (sandbox/'preferences.toml').write_text(isolated_seed(),encoding='utf-8')
        command=java_command(args.java,jar,PROJECT/'desktop/build/slick-natives')
        command=[a for a in command if a not in ('--screen=battleroom','--auto-start-battleroom')]
        command.append(f'--replay={REPLAY_ALIAS}')
        command[1:1]=[f'-Dlaunch.dir={sandbox}',f'-Drwx.assetsDir={PROJECT / "assets"}',
                       '-Drwx.kool.backend=vulkan','-Xms512m',f'-Xmx{args.heap_mb}M']
        env = run_environment(output,sandbox,name)
        env.update(camera_environment(camera))
        env.update({'RWX_WINDOW_WIDTH':str(args.window_width),'RWX_WINDOW_HEIGHT':str(args.window_height)})
        if args.fog is not None: env['RWX_REPLAY_PAN_FOG'] = args.fog
        if args.parallel_cell_raster: env['RWX_PARALLEL_CELL_RASTER'] = '1'
        if args.primitive_text_metrics: env['RWX_PRIMITIVE_TEXT_METRICS'] = '1'
        if args.disable_native_bgra_upload: env['RWX_DISABLE_NATIVE_BGRA_UPLOAD'] = '1'
        if args.no_perf_window_log: env.pop('RWX_PERF_LOG', None)
        run=launch(command,env,output,name,135)
        run.update({'variant':variant,'replayName':REPLAY_ALIAS,'replayAliasPath':str(alias),
                    'replayAliasSha256':replay_digest,'replayAliasVerified':True,'cameraModeExpected':camera['cameraMode']})
        if args.fog is not None: run['fogDisplayExpected'] = args.fog == 'on'
        result=analyze_run(output,run)
        confirm_camera_zoom(result,camera)
        confirm_dynamic_activity(result)
        confirm_experimental_modes(result,(output/f'{name}.log').read_text(encoding='utf-8',errors='replace'),
                                   args.parallel_cell_raster,args.primitive_text_metrics,gpu_map_cell_cache=True)
        report['runs'].append(result)
        write_json(output/'summary.json',report)
        print('DONE '+json.dumps({'variant':variant,'valid':result['validMeasurement'],
              'windows':[{k:w.get(k) for k in ('minimumLivingUnits','minimumVisibleUnits','maximumMovingUnits',
                         'framesWithProjectiles','newFps','renderFps','freshSnapshotIntervalP99Ms')} for w in result['windows']]}),flush=True)
        if not result['validMeasurement']: print('INVALID '+str(result['invalidReasons']),flush=True)
    return 0 if all(run['validMeasurement'] for run in report['runs']) else 1


if __name__=='__main__': raise SystemExit(main())
