"""Record a dynamic 500-unit built-in map and compare it with the supplied replay.

Both measurement cases use actual replay playback and the same pan harness.
Unit composition and alliances differ: this tests map exclusivity, not texture causality.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from map_pan_comparison import (PROJECT, NO_CONSOLE, REPLAY_ALIAS, isolated_seed,
    run_environment, copy_replay_alias, analyze_run, write_json)
from vulkan_native_matrix import java_command
from analyze_vulkan_matrix import read_json_lines


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
    parser.add_argument('--map',default='maps/skirmish/[p8]Interlocked Large (8p).tmx')
    args=parser.parse_args()
    output=args.output.resolve(); output.mkdir(parents=True,exist_ok=False)
    digest=hashlib.sha256(args.jar.read_bytes()).hexdigest()
    jar=output/f'runtime-{digest[:16]}.jar'; shutil.copy2(args.jar,jar)
    original=output/'europe.replay'; shutil.copy2(args.replay,original)
    report={'runtimeSha256':digest,'mode':args.mode,'builtInMap':args.map,'recording':None,'runs':[],
            'protocol':{'units':500,'teams':15,'msaa':4,'targetFps':300,'vsync':False,
                        'warmupSeconds':20,'sampleSeconds':20,'windowsPerProcess':2,
                        'order':['europe','builtin','builtin','europe'],
                        'limitation':'Unit types, alliances, terrain dimensions, fog and replay scripts differ; not a texture-only comparison'}}
    write_json(output/'summary.json',report)
    name='record-builtin'; sandbox=output/name; sandbox.mkdir()
    (sandbox/'preferences.toml').write_text(isolated_seed(),encoding='utf-8')
    command=java_command(args.java,jar,PROJECT/'desktop/build/slick-natives')
    command[1:1]=[f'-Dlaunch.dir={sandbox}',f'-Drwx.assetsDir={PROJECT / "assets"}',
                   '-Drwx.kool.backend=vulkan',f'-Drwx.benchmark.map={args.map}','-Xms512m','-Xmx2g']
    env=run_environment(output,sandbox,name)
    env.pop('RWX_REPLAY_PAN_OUTPUT')
    env.update({'RWX_MAP_PAN_OUTPUT':str(output/f'{name}-scenario.ndjson'),
                'RWX_BENCHMARK_UNITS':'500','RWX_BENCHMARK_MIX':'land-air',
                'RWX_BENCHMARK_PALETTE':'builtin:tank,builtin:hoverTank,builtin:heavyTank,builtin:helicopter,builtin:artillery,builtin:megaTank',
                'RWX_BENCHMARK_MODE':args.mode,'RWX_BENCHMARK_SELECTED':'0','RWX_BENCHMARK_TEAMS':'15',
                'RWX_BENCHMARK_RECORD_REPLAY':'builtin-500.replay',
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
                       '-Drwx.kool.backend=vulkan','-Xms512m','-Xmx2g']
        run=launch(command,run_environment(output,sandbox,name),output,name,135)
        run.update({'variant':variant,'replayName':REPLAY_ALIAS,'replayAliasPath':str(alias),
                    'replayAliasSha256':replay_digest,'replayAliasVerified':True})
        result=analyze_run(output,run); report['runs'].append(result)
        write_json(output/'summary.json',report)
        print('DONE '+json.dumps({'variant':variant,'valid':result['validMeasurement'],
              'windows':[{k:w.get(k) for k in ('minimumLivingUnits','minimumVisibleUnits','maximumMovingUnits',
                         'framesWithProjectiles','newFps','renderFps','freshSnapshotIntervalP99Ms')} for w in result['windows']]}),flush=True)
        if not result['validMeasurement']: print('INVALID '+str(result['invalidReasons']),flush=True)
    return 0


if __name__=='__main__': raise SystemExit(main())
