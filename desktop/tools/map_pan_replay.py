"""Run a frozen real replay with rapid camera motion in the actual Vulkan game."""
import argparse
import hashlib
import math
from pathlib import Path
import shutil

from map_pan_comparison import (PROJECT, REPLAY_ALIAS, analyze_run, copy_replay_alias,
                                isolated_seed, run_environment, write_json)
from map_pan_builtin_comparison import launch, confirm_experimental_modes
from map_pan_diagnostic import export_jfr
from vulkan_native_matrix import java_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--replay', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--camera-period-seconds', type=int, default=1)
    parser.add_argument('--camera-mode', choices=('pan', 'jump', 'pan-zoom'), default='pan',
                        help='pan-zoom is an explicit alias for pan with a continuous zoom cycle')
    parser.add_argument('--zoom-mode', choices=('none', 'cycle'), default=None)
    parser.add_argument('--zoom-period-seconds', type=int, default=4)
    parser.add_argument('--zoom-min', type=float, default=.35, help='Requested target zoom; ordinary UI bounds still apply')
    parser.add_argument('--zoom-max', type=float, default=1.5)
    parser.add_argument('--camera-trace', action='store_true',
                        help='Buffer per-owner camera observations to camera.csv; also enabled by --diagnostic for zoom cycles')
    parser.add_argument('--fog', choices=('off', 'on'), default='off')
    parser.add_argument('--window-width', type=int, default=1920)
    parser.add_argument('--window-height', type=int, default=1080)
    parser.add_argument('--diagnostic', action='store_true', help='Record JFR; timing is diagnostic only')
    parser.add_argument('--vk-trace', action='store_true')
    parser.add_argument('--engine-section-trace', action='store_true')
    parser.add_argument('--legacy-owner-pacing', action='store_true')
    parser.add_argument('--legacy-short-owner-park', action='store_true')
    parser.add_argument('--guard-short-owner-park', action='store_true')
    parser.add_argument('--disable-text-mesh-reuse', action='store_true')
    parser.add_argument('--disable-texture-metadata-reuse', action='store_true')
    parser.add_argument('--time-based-map-zoom-cache', action='store_true', help='Opt-in display-cache cadence experiment')
    parser.add_argument('--parallel-cell-raster', action='store_true', help='Opt-in whole-cell CPU stripe experiment')
    parser.add_argument('--adaptive-cell-raster', action='store_true', help='Select costly texture cells for stripes; requires --parallel-cell-raster')
    parser.add_argument('--gpu-map-cell-cache', action='store_true', help='Enable opt-in Vulkan map cell rendering; require measured GPU cache activity')
    parser.add_argument('--disable-gpu-map-cell-pass-reuse', action='store_true',
                        help='Disable post-fence GPU cell pass reuse for a controlled GPU-only comparison; requires --gpu-map-cell-cache')
    parser.add_argument('--primitive-text-metrics', action='store_true', help='Opt-in primitive glyph metrics experiment')
    parser.add_argument('--cpu-target-profile', action='store_true', help='Log CPU raster details; diagnostic timing only')
    parser.add_argument('--raster-threads', type=int, choices=range(1, 9), help='Override CPU raster workers for controlled tests')
    parser.add_argument('--no-perf-window-log', action='store_true')
    parser.add_argument('--canvas-stage-trace', action='store_true')
    parser.add_argument('--disable-native-bgra-upload', action='store_true', help='Controlled Vulkan RGBA baseline')
    parser.add_argument('--native-bgra-upload', action='store_true', help='Enable the experimental Vulkan BGRA path')
    parser.add_argument('--force-legacy-reload-gc', action='store_true')
    args = parser.parse_args()
    if args.disable_gpu_map_cell_pass_reuse and not args.gpu_map_cell_cache:
        parser.error('--disable-gpu-map-cell-pass-reuse requires --gpu-map-cell-cache')
    if args.adaptive_cell_raster and not args.parallel_cell_raster:
        parser.error('--adaptive-cell-raster requires --parallel-cell-raster')
    if args.camera_period_seconds < 1 or args.zoom_period_seconds < 1:
        parser.error('camera and zoom periods must be >= 1 second')
    if not (math.isfinite(args.zoom_min) and math.isfinite(args.zoom_max) and 0 < args.zoom_min < args.zoom_max):
        parser.error('zoom limits must be finite and 0 < zoom-min < zoom-max')
    camera_mode = 'pan' if args.camera_mode == 'pan-zoom' else args.camera_mode
    zoom_mode = args.zoom_mode or ('cycle' if args.camera_mode == 'pan-zoom' else 'none')
    if args.camera_mode == 'pan-zoom' and zoom_mode != 'cycle':
        parser.error('pan-zoom requires cycle zoom')
    camera_trace = args.camera_trace or (args.diagnostic and zoom_mode == 'cycle')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sandbox = output / 'sandbox'
    sandbox.mkdir()
    jar = output / 'runtime.jar'
    shutil.copy2(args.jar, jar)
    digest = hashlib.sha256(args.replay.read_bytes()).hexdigest()
    alias = copy_replay_alias(args.replay.resolve(), sandbox, digest)
    (sandbox / 'preferences.toml').write_text(isolated_seed(), encoding='utf-8')
    command = java_command(args.java, jar, PROJECT / 'desktop/build/slick-natives')
    command = [arg for arg in command if arg not in ('--screen=battleroom', '--auto-start-battleroom')]
    command.append(f'--replay={REPLAY_ALIAS}')
    command[1:1] = [f'-Dlaunch.dir={sandbox}', f'-Drwx.assetsDir={PROJECT / "assets"}',
                    '-Drwx.kool.backend=vulkan', '-Xms512m', '-Xmx2g']
    if args.diagnostic:
        command[1:1] = ['-XX:FlightRecorderOptions=stackdepth=64',
                       f'-XX:StartFlightRecording=filename={output / "profile.jfr"},settings=profile,dumponexit=true']
    if args.cpu_target_profile:
        command[1:1] = ['-Drwx.kool.cpuTargetProfile=true']
    if args.raster_threads is not None:
        command[1:1] = [f'-Drwx.koolRasterThreads={args.raster_threads}']
    env = run_environment(output, sandbox, 'replay-pan')
    if args.legacy_owner_pacing:
        env['RWX_LEGACY_OWNER_PACING'] = '1'
    if args.legacy_short_owner_park:
        env['RWX_LEGACY_SHORT_OWNER_PARK'] = '1'
    if args.guard_short_owner_park:
        env['RWX_GUARD_SHORT_OWNER_PARK'] = '1'
    if args.disable_text_mesh_reuse:
        env['RWX_DISABLE_TEXT_MESH_REUSE'] = '1'
    if args.disable_texture_metadata_reuse:
        env['RWX_DISABLE_TEXTURE_METADATA_REUSE'] = '1'
    if args.time_based_map_zoom_cache:
        env['RWX_TIME_BASED_MAP_ZOOM_CACHE'] = '1'
    if args.parallel_cell_raster:
        env['RWX_PARALLEL_CELL_RASTER'] = '1'
    if args.adaptive_cell_raster:
        env['RWX_ADAPTIVE_CELL_RASTER'] = '1'
    if args.gpu_map_cell_cache:
        env['RWX_GPU_MAP_CELL_CACHE'] = '1'
    if args.disable_gpu_map_cell_pass_reuse:
        env['RWX_DISABLE_GPU_MAP_CELL_PASS_REUSE'] = '1'
    if args.primitive_text_metrics:
        env['RWX_PRIMITIVE_TEXT_METRICS'] = '1'
    if args.vk_trace:
        env['RWX_VK_TRACE'] = str(output / 'vulkan-stages.csv')
    if args.engine_section_trace:
        env['RWX_ENGINE_SECTION_TRACE'] = str(output / 'engine-sections.csv')
    if args.no_perf_window_log:
        env.pop('RWX_PERF_LOG', None)
    if args.canvas_stage_trace:
        env['RWX_CANVAS_TRACE'] = str(output / 'canvas-stages.csv')
    if args.disable_native_bgra_upload:
        env['RWX_DISABLE_NATIVE_BGRA_UPLOAD'] = '1'
    if args.native_bgra_upload:
        env['RWX_NATIVE_BGRA_UPLOAD'] = '1'
    env.update({'RWX_ENGINE_FRAME_TRACE': str(output / 'engine.csv'),
                'RWX_MAP_CACHE_TRACE': str(output / 'map-cache.csv'),
                'RWX_REPLAY_PAN_PERIOD_SECONDS': str(args.camera_period_seconds),
                'RWX_REPLAY_PAN_CAMERA_MODE': camera_mode,
                'RWX_REPLAY_PAN_ZOOM_MODE': zoom_mode,
                'RWX_REPLAY_PAN_ZOOM_PERIOD_SECONDS': str(args.zoom_period_seconds),
                'RWX_REPLAY_PAN_ZOOM_MIN': str(args.zoom_min),
                'RWX_REPLAY_PAN_ZOOM_MAX': str(args.zoom_max),
                'RWX_REPLAY_PAN_FOG': args.fog,
                'RWX_CANVAS_PIXEL_POOL_METRICS': '1',
                'RWX_WINDOW_WIDTH': str(args.window_width), 'RWX_WINDOW_HEIGHT': str(args.window_height)})
    if camera_trace:
        env['RWX_REPLAY_PAN_CAMERA_TRACE'] = str(output / 'camera.csv')
    if args.force_legacy_reload_gc:
        env['RWX_FORCE_LEGACY_RELOAD_GC'] = '1'
    report = {'runtimeSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'replaySha256': digest, 'replaySource': str(args.replay.resolve()),
              'diagnosticOnly': args.diagnostic or args.vk_trace or args.engine_section_trace or camera_trace or args.cpu_target_profile,
              'protocol': {'fog': args.fog, 'cameraMode': camera_mode, 'cameraModeRequested': args.camera_mode,
                           'cameraPeriodSeconds': args.camera_period_seconds,
                           'zoomMode': zoom_mode, 'zoomPeriodSeconds': args.zoom_period_seconds,
                           'zoomMinimumRequested': args.zoom_min, 'zoomMaximumRequested': args.zoom_max,
                           'cameraTraceRequested': camera_trace,
                           'logicalWindowWidth': args.window_width, 'logicalWindowHeight': args.window_height,
                           'perfWindowLogRequested': not args.no_perf_window_log,
                           'canvasStageTraceRequested': args.canvas_stage_trace,
                           'nativeBgraUploadsRequested': args.native_bgra_upload and not args.disable_native_bgra_upload,
                           'hybridOwnerPacingRequested': not args.legacy_owner_pacing,
                           'legacyShortOwnerParkRequested': args.legacy_short_owner_park or not args.guard_short_owner_park,
                           'shortParkGuardRequested': not args.legacy_owner_pacing and args.guard_short_owner_park and not args.legacy_short_owner_park,
                           'textMeshReuseRequested': not args.disable_text_mesh_reuse,
                           'textureMetadataReuseRequested': not args.disable_texture_metadata_reuse,
                           'timeBasedMapZoomCacheRequested': args.time_based_map_zoom_cache,
                           'parallelCellRasterRequested': args.parallel_cell_raster,
                           'adaptiveCellRasterRequested': args.adaptive_cell_raster,
                           'gpuMapCellCacheRequested': args.gpu_map_cell_cache,
                           'gpuMapCellPassReuseDisabled': args.disable_gpu_map_cell_pass_reuse,
                           'primitiveTextMetricsRequested': args.primitive_text_metrics,
                           'cpuTargetProfileRequested': args.cpu_target_profile,
                           'rasterThreadsRequested': args.raster_threads,
                           'vulkanStageTraceRequested': args.vk_trace,
                           'engineSectionTraceRequested': args.engine_section_trace,
                           'forceLegacyReloadGc': args.force_legacy_reload_gc},
              'purpose': 'Actual replay timing and correctness confirmation; one process, not an A/B comparison.'}
    write_json(output / 'diagnostic-summary.json', report)
    run = launch(command, env, output, 'replay-pan', 155)
    run.update({'fogDisplayExpected': args.fog == 'on', 'cameraModeExpected': camera_mode,
                'replayName': REPLAY_ALIAS, 'replayAliasPath': str(alias),
                'replayAliasVerified': hashlib.sha256(alias.read_bytes()).hexdigest() == digest,
                'replayAliasSha256': digest})
    report['run'] = analyze_run(output, run)
    actual_log = (output / 'replay-pan.log').read_text(encoding='utf-8', errors='replace')
    confirm_experimental_modes(report['run'], actual_log, args.parallel_cell_raster,
                               args.primitive_text_metrics, args.adaptive_cell_raster, args.gpu_map_cell_cache)
    # The shared pan analyzer deliberately retains its existing pan/jump contract.
    # Add zoom confirmation here so a runtime that silently ignores new flags fails.
    zoom_confirmed = report['run']['setup'] is not None and report['run']['setup'].get('zoomMode') == zoom_mode
    if zoom_mode == 'cycle':
        setup = report['run']['setup'] or {}
        zoom_confirmed = zoom_confirmed and setup.get('zoomPeriodSeconds') == args.zoom_period_seconds and all(
            isinstance(setup.get(key), (int, float)) and math.isclose(setup[key], requested, abs_tol=1e-6)
            for key, requested in (('zoomRequestedMinimum', args.zoom_min), ('zoomRequestedMaximum', args.zoom_max))) and all(
            window.get('zoomMode') == 'cycle'
            and isinstance(window.get('actualZoomMinimum'), (int, float))
            and isinstance(window.get('actualZoomMaximum'), (int, float))
            and window['actualZoomMaximum'] - window['actualZoomMinimum'] > .01
            for window in report['run']['windows'])
    report['run']['zoomModeConfirmed'] = zoom_confirmed
    if zoom_mode == 'cycle' and not zoom_confirmed:
        report['run']['invalidReasons'].append('continuous zoom request was not confirmed by actual engine zoom changes')
        report['run']['validMeasurement'] = False
    write_json(output / 'diagnostic-summary.json', report)
    print('DONE ' + str({'valid': report['run']['validMeasurement'],
                         'reasons': report['run']['invalidReasons'],
                         'windows': [{key: window.get(key) for key in ('newFps', 'renderFps',
                             'freshSnapshotIntervalP99Ms')} for window in report['run']['windows']]}), flush=True)
    if args.diagnostic:
        report['jfrExport'] = export_jfr(args.java, output / 'profile.jfr', output, compact=True)
        write_json(output / 'diagnostic-summary.json', report)
    return 0 if report['run']['validMeasurement'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
