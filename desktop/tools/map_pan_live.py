"""Run an actual local dynamic game, with optional diagnostic tracing."""
import argparse
import hashlib
import shutil
from pathlib import Path
from map_pan_comparison import PROJECT, isolated_seed, run_environment, analyze_run, write_json
from map_pan_builtin_comparison import (launch, add_camera_arguments, camera_protocol,
    camera_environment, confirm_camera_zoom, confirm_experimental_modes, confirm_dynamic_activity)
from map_pan_diagnostic import export_jfr
from analyze_vulkan_matrix import read_json_lines
from vulkan_native_matrix import java_command


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--fog', choices=('off', 'on'), default='off')
    p.add_argument('--mode', choices=('moving', 'combat'), default='moving')
    p.add_argument('--units', type=int, choices=(500, 661, 1000, 2000), default=500)
    p.add_argument('--teams', type=int, default=15)
    diagnostics = p.add_mutually_exclusive_group()
    diagnostics.add_argument('--diagnostic', action='store_true')
    diagnostics.add_argument('--diagnostic-light', action='store_true', help='JFR and engine trace without per-canvas profiling')
    p.add_argument('--vk-trace', action='store_true', help='Record native acquire/fence/submit/present durations; diagnostic only')
    p.add_argument('--engine-trace', action='store_true')
    p.add_argument('--engine-section-trace', action='store_true', help='Record engine sub-sections exceeding 2 ms; diagnostic only')
    p.add_argument('--no-perf-window-log', action='store_true', help='Disable benchmark-only synchronous summary logging')
    p.add_argument('--map-cache-trace', action='store_true', help='Record map cell redraws and camera scroll boundaries')
    add_camera_arguments(p)
    p.add_argument('--camera-trace', action='store_true', help='Record actual and requested camera zoom; diagnostic only')
    p.add_argument('--parallel-cell-raster', action='store_true', help='Enable opt-in whole-cell raster stripes')
    p.add_argument('--adaptive-cell-raster', action='store_true', help='Select costly texture cells for stripes; requires --parallel-cell-raster')
    p.add_argument('--gpu-map-cell-cache', action='store_true', help='Enable opt-in Vulkan map cell rendering; require measured GPU cache activity')
    p.add_argument('--disable-gpu-map-cell-pass-reuse', action='store_true',
                   help='Disable post-fence GPU cell pass reuse for a controlled GPU-only comparison; requires --gpu-map-cell-cache')
    p.add_argument('--primitive-text-metrics', action='store_true', help='Enable opt-in primitive text metrics')
    p.add_argument('--window-width', type=int, default=1920, help='AWT logical window width, before DPI scaling')
    p.add_argument('--window-height', type=int, default=1080, help='AWT logical window height, before DPI scaling')
    p.add_argument('--disable-frozen-pixel-mesh-reuse', action='store_true', help='Controlled baseline for Vulkan mesh reuse')
    p.add_argument('--disable-pixel-pool', action='store_true', help='Controlled baseline for pooled CPU map pixels')
    p.add_argument('--disable-upload-buffer-pool', action='store_true')
    p.add_argument('--disable-immutable-pixel-snapshot-reuse', action='store_true')
    p.add_argument('--disable-raster-scalar-mapping', action='store_true')
    p.add_argument('--disable-opaque-source-over-fast-path', action='store_true')
    p.add_argument('--disable-normal-fog-display-diff', action='store_true',
                   help='Reproduce the retired fog-diff experiment with frozen runtime 7')
    p.add_argument('--raster-threads', type=int, choices=range(1, 9), help='Controlled CPU raster worker count')
    p.add_argument('--layer-buffer-pixels', type=int, choices=(256, 384, 512), help='Controlled map cache target size')
    p.add_argument('--legacy-pointer-forwarding', action='store_true', help='Reproduce previous dual pointer forwarding')
    p.add_argument('--legacy-owner-pacing', action='store_true', help='Controlled baseline for owner frame waits')
    p.add_argument('--legacy-short-owner-park', action='store_true', help='Controlled baseline for tiny coarse parks in hybrid owner pacing')
    p.add_argument('--guard-short-owner-park', action='store_true', help='Enable the experimental minimum coarse-park guard')
    p.add_argument('--disable-text-mesh-reuse', action='store_true')
    p.add_argument('--disable-bulk-argb-pack', action='store_true', help='Reproduce the retired bulk conversion experiment with frozen runtime 10')
    p.add_argument('--disable-native-bgra-upload', action='store_true', help='Controlled Vulkan RGBA baseline')
    p.add_argument('--canvas-stage-trace', action='store_true', help='Record canvas stages without canvas statistics logging')
    p.add_argument('--cpu-target-profile', action='store_true', help="Log CPU map/minimap raster work; diagnostic only")
    args = p.parse_args()
    if args.disable_gpu_map_cell_pass_reuse and not args.gpu_map_cell_cache:
        p.error('--disable-gpu-map-cell-pass-reuse requires --gpu-map-cell-cache')
    if args.adaptive_cell_raster and not args.parallel_cell_raster:
        p.error('--adaptive-cell-raster requires --parallel-cell-raster')
    try:
        camera = camera_protocol(args)
    except ValueError as error:
        p.error(str(error))
    camera_trace = args.camera_trace or ((args.diagnostic or args.diagnostic_light) and camera['zoomMode'] == 'cycle')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    jar = out / 'runtime.jar'; shutil.copy2(args.jar, jar)
    name = 'live-pan'; sandbox = out / name; sandbox.mkdir()
    (sandbox / 'preferences.toml').write_text(isolated_seed(), encoding='utf-8')
    cmd = java_command(args.java, jar, PROJECT / 'desktop/build/slick-natives')
    cmd[1:1] = [f'-Dlaunch.dir={sandbox}', f'-Drwx.assetsDir={PROJECT / "assets"}',
                '-Drwx.kool.backend=vulkan', '-Drwx.benchmark.map=maps/skirmish/[p8]Interlocked Large (8p).tmx',
                '-Xms512m', '-Xmx2g']
    if args.cpu_target_profile:
        cmd.insert(1, '-Drwx.kool.cpuTargetProfile=true')
    if args.raster_threads is not None:
        cmd.insert(1, f'-Drwx.koolRasterThreads={args.raster_threads}')
    if args.layer_buffer_pixels is not None:
        cmd.insert(1, f'-Drwx.koolLayerBufferPixels={args.layer_buffer_pixels}')
    env = run_environment(out, sandbox, name); env.pop('RWX_REPLAY_PAN_OUTPUT')
    if args.no_perf_window_log:
        env.pop('RWX_PERF_LOG', None)
    env.update({'RWX_WINDOW_WIDTH': str(args.window_width), 'RWX_WINDOW_HEIGHT': str(args.window_height)})
    env['RWX_CANVAS_PIXEL_POOL_METRICS'] = '1'
    env.update(camera_environment(camera))
    env.update({'RWX_MAP_PAN_OUTPUT': str(out / f'{name}-scenario.ndjson'),
                'RWX_BENCHMARK_UNITS': str(args.units), 'RWX_BENCHMARK_MIX': 'land-air',
                'RWX_BENCHMARK_PALETTE': 'builtin:tank,builtin:hoverTank,builtin:heavyTank,builtin:helicopter,builtin:artillery,builtin:megaTank',
                'RWX_BENCHMARK_MODE': args.mode, 'RWX_BENCHMARK_TEAMS': str(args.teams),
                'RWX_BENCHMARK_FOG': args.fog, 'RWX_BENCHMARK_OUTPUT': str(out / 'fixture.ndjson')})
    if camera_trace:
        env['RWX_REPLAY_PAN_CAMERA_TRACE'] = str(out / 'camera.csv')
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
    if args.map_cache_trace:
        env['RWX_MAP_CACHE_TRACE'] = str(out / 'map-cache.csv')
    if args.engine_section_trace:
        env['RWX_ENGINE_SECTION_TRACE'] = str(out / 'engine-sections.csv')
        env['RWX_ENGINE_FRAME_TRACE'] = str(out / 'engine.csv')
    if args.disable_frozen_pixel_mesh_reuse:
        env['RWX_DISABLE_FROZEN_PIXEL_MESH_REUSE'] = '1'
    if args.disable_pixel_pool:
        env['RWX_CANVAS_PIXEL_POOL'] = '0'
    if args.disable_upload_buffer_pool:
        env['RWX_CANVAS_UPLOAD_BUFFER_POOL'] = '0'
    if args.disable_immutable_pixel_snapshot_reuse:
        env['RWX_DISABLE_IMMUTABLE_PIXEL_SNAPSHOT_REUSE'] = '1'
    if args.disable_raster_scalar_mapping:
        env['RWX_DISABLE_RASTER_SCALAR_MAPPING'] = '1'
    if args.disable_opaque_source_over_fast_path:
        env['RWX_DISABLE_OPAQUE_SOURCE_OVER_FAST_PATH'] = '1'
    if args.disable_normal_fog_display_diff:
        env['RWX_DISABLE_NORMAL_FOG_DISPLAY_DIFF'] = '1'
    if args.legacy_pointer_forwarding:
        env['RWX_LEGACY_POINTER_FORWARDING'] = '1'
    if args.legacy_owner_pacing:
        env['RWX_LEGACY_OWNER_PACING'] = '1'
    if args.legacy_short_owner_park:
        env['RWX_LEGACY_SHORT_OWNER_PARK'] = '1'
    if args.guard_short_owner_park:
        env['RWX_GUARD_SHORT_OWNER_PARK'] = '1'
    if args.disable_text_mesh_reuse:
        env['RWX_DISABLE_TEXT_MESH_REUSE'] = '1'
    if args.disable_bulk_argb_pack:
        env['RWX_DISABLE_BULK_ARGB_PACK'] = '1'
    if args.disable_native_bgra_upload:
        env['RWX_DISABLE_NATIVE_BGRA_UPLOAD'] = '1'
    else:
        env['RWX_NATIVE_BGRA_UPLOAD'] = '1'
    if args.canvas_stage_trace:
        env['RWX_CANVAS_TRACE'] = str(out / 'canvas-stages.csv')
    if args.diagnostic or args.diagnostic_light:
        cmd[1:1] = ['-XX:FlightRecorderOptions=stackdepth=64',
                    f'-XX:StartFlightRecording=filename={out / "profile.jfr"},settings=profile,dumponexit=true']
        env['RWX_ENGINE_FRAME_TRACE'] = str(out / 'engine.csv')
    elif args.engine_trace:
        env['RWX_ENGINE_FRAME_TRACE'] = str(out / 'engine.csv')
    if args.diagnostic:
        env.update({'RWX_CANVAS_PERF': '1', 'RWX_CANVAS_TRACE': str(out / 'canvas-stages.csv')})
    if args.diagnostic or args.vk_trace:
        env['RWX_VK_TRACE'] = str(out / 'vulkan-stages.csv')
    report = {'runtimeSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'diagnosticOnly': args.diagnostic or args.diagnostic_light or args.vk_trace or camera_trace,
              'diagnosticMode': 'full' if args.diagnostic else 'light' if args.diagnostic_light else 'none',
              'protocol': {'mode': args.mode, 'teams': args.teams, 'fog': args.fog, 'recordReplay': False,
                           'units': args.units, 'warmupSeconds': 20, 'sampleSeconds': 20, 'repetitions': 2,
                           **camera, 'cameraTraceRequested': camera_trace,
                           'parallelCellRasterRequested': args.parallel_cell_raster,
                           'adaptiveCellRasterRequested': args.adaptive_cell_raster,
                           'gpuMapCellCacheRequested': args.gpu_map_cell_cache,
                           'gpuMapCellPassReuseDisabled': args.disable_gpu_map_cell_pass_reuse,
                           'primitiveTextMetricsRequested': args.primitive_text_metrics,
                           'logicalWindowWidth': args.window_width, 'logicalWindowHeight': args.window_height,
                           'frozenPixelMeshReuseRequested': not args.disable_frozen_pixel_mesh_reuse,
                           'pixelPoolRequested': not args.disable_pixel_pool,
                           'uploadBufferPoolRequested': not args.disable_upload_buffer_pool,
                           'immutablePixelSnapshotReuseRequested': not args.disable_immutable_pixel_snapshot_reuse,
                           'rasterScalarMappingRequested': not args.disable_raster_scalar_mapping,
                           'opaqueSourceOverFastPathRequested': not args.disable_opaque_source_over_fast_path,
                           'normalFogDisplayDiffRequested': not args.disable_normal_fog_display_diff,
                           'rasterThreadsRequested': args.raster_threads,
                           'layerBufferPixelsRequested': args.layer_buffer_pixels,
                           'legacyPointerForwardingRequested': args.legacy_pointer_forwarding}}
    report['protocol']['perfWindowLogRequested'] = not args.no_perf_window_log
    report['protocol']['engineSectionTraceRequested'] = args.engine_section_trace
    report['protocol']['bulkArgbPackingRequested'] = not args.disable_bulk_argb_pack
    report['protocol']['nativeBgraUploadsRequested'] = not args.disable_native_bgra_upload
    report['protocol']['canvasStageTraceRequested'] = args.canvas_stage_trace
    report['protocol']['hybridOwnerPacingRequested'] = not args.legacy_owner_pacing
    report['protocol']['legacyShortOwnerParkRequested'] = args.legacy_short_owner_park or not args.guard_short_owner_park
    report['protocol']['shortParkGuardRequested'] = not args.legacy_owner_pacing and args.guard_short_owner_park and not args.legacy_short_owner_park
    report['protocol']['textMeshReuseRequested'] = not args.disable_text_mesh_reuse
    write_json(out / 'diagnostic-summary.json', report)
    run = launch(cmd, env, out, name, 155)
    run.update({'localMapExpected': True, 'mapFogExpected': args.fog == 'on', 'cameraModeExpected': camera['cameraMode']})
    report['run'] = analyze_run(out, run)
    confirm_camera_zoom(report['run'], camera)
    confirm_dynamic_activity(report['run'])
    fixtures = [row for row in read_json_lines(out / 'fixture.ndjson') if row.get('kind') == 'scenario']
    report['fixture'] = fixtures[0] if len(fixtures) == 1 else None
    if not report['fixture'] or any(report['fixture'].get(key) != expected for key, expected in (
            ('requestedUnits', args.units), ('mode', args.mode), ('teams', args.teams), ('fogEnabled', args.fog == 'on'))):
        report['run']['invalidReasons'].append('local dynamic fixture setup differs from requested unit count, mode, teams or map fog')
        report['run']['validMeasurement'] = False
    confirm_experimental_modes(report['run'], (out / f'{name}.log').read_text(encoding='utf-8', errors='replace'),
                               args.parallel_cell_raster, args.primitive_text_metrics, args.adaptive_cell_raster, args.gpu_map_cell_cache)
    write_json(out / 'diagnostic-summary.json', report)
    print('DONE ' + str({'valid': report['run']['validMeasurement'], 'reasons': report['run']['invalidReasons'],
                        'windows': [{k: w.get(k) for k in ('newFps', 'renderFps', 'freshSnapshotIntervalP99Ms',
                                     'minimumLivingUnits', 'maximumMovingUnits', 'framesWithProjectiles',
                                     'actualZoomMinimum', 'actualZoomMaximum')}
                                    for w in report['run']['windows']]}), flush=True)
    if args.diagnostic or args.diagnostic_light:
        report['jfrExport'] = export_jfr(args.java, out / 'profile.jfr', out, compact=True)
        write_json(out / 'diagnostic-summary.json', report)
    return 0 if report['run']['validMeasurement'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
