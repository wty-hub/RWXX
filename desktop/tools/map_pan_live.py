"""Run an actual local 500-unit dynamic game, with optional diagnostic tracing."""
import argparse
import hashlib
import shutil
from pathlib import Path
from map_pan_comparison import PROJECT, isolated_seed, run_environment, analyze_run, write_json
from map_pan_builtin_comparison import launch
from map_pan_diagnostic import export_jfr
from vulkan_native_matrix import java_command


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--fog', choices=('off', 'on'), default='off')
    p.add_argument('--mode', choices=('moving', 'combat'), default='moving')
    p.add_argument('--teams', type=int, default=15)
    diagnostics = p.add_mutually_exclusive_group()
    diagnostics.add_argument('--diagnostic', action='store_true')
    diagnostics.add_argument('--diagnostic-light', action='store_true', help='JFR and engine trace without per-canvas profiling')
    p.add_argument('--vk-trace', action='store_true', help='Record native acquire/fence/submit/present durations; diagnostic only')
    p.add_argument('--engine-trace', action='store_true')
    p.add_argument('--engine-section-trace', action='store_true', help='Record engine sub-sections exceeding 2 ms; diagnostic only')
    p.add_argument('--no-perf-window-log', action='store_true', help='Disable benchmark-only synchronous summary logging')
    p.add_argument('--map-cache-trace', action='store_true', help='Record map cell redraws and camera scroll boundaries')
    p.add_argument('--camera-period-seconds', type=int, default=20)
    p.add_argument('--camera-mode', choices=('pan', 'jump'), default='pan')
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
    p.add_argument('--raster-threads', type=int, choices=(1, 2, 4), help='Controlled CPU raster worker count')
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
    env.update({'RWX_MAP_PAN_OUTPUT': str(out / f'{name}-scenario.ndjson'),
                'RWX_BENCHMARK_UNITS': '500', 'RWX_BENCHMARK_MIX': 'land-air',
                'RWX_BENCHMARK_PALETTE': 'builtin:tank,builtin:hoverTank,builtin:heavyTank,builtin:helicopter,builtin:artillery,builtin:megaTank',
                'RWX_BENCHMARK_MODE': args.mode, 'RWX_BENCHMARK_TEAMS': str(args.teams),
                'RWX_BENCHMARK_FOG': args.fog, 'RWX_BENCHMARK_OUTPUT': str(out / 'fixture.ndjson'),
                'RWX_REPLAY_PAN_PERIOD_SECONDS': str(args.camera_period_seconds),
                'RWX_REPLAY_PAN_CAMERA_MODE': args.camera_mode})
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
              'diagnosticOnly': args.diagnostic or args.diagnostic_light or args.vk_trace,
              'diagnosticMode': 'full' if args.diagnostic else 'light' if args.diagnostic_light else 'none',
              'protocol': {'mode': args.mode, 'teams': args.teams, 'fog': args.fog, 'recordReplay': False,
                           'units': 500, 'warmupSeconds': 20, 'sampleSeconds': 20, 'repetitions': 2,
                           'cameraPeriodSeconds': args.camera_period_seconds, 'cameraMode': args.camera_mode,
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
    run.update({'localMapExpected': True, 'mapFogExpected': args.fog == 'on', 'cameraModeExpected': args.camera_mode})
    report['run'] = analyze_run(out, run)
    write_json(out / 'diagnostic-summary.json', report)
    print('DONE ' + str({'valid': report['run']['validMeasurement'], 'reasons': report['run']['invalidReasons'],
                        'windows': [{k: w.get(k) for k in ('newFps', 'renderFps', 'freshSnapshotIntervalP99Ms',
                                     'minimumLivingUnits', 'maximumMovingUnits', 'framesWithProjectiles')}
                                    for w in report['run']['windows']]}), flush=True)
    if args.diagnostic or args.diagnostic_light:
        report['jfrExport'] = export_jfr(args.java, out / 'profile.jfr', out, compact=True)
        write_json(out / 'diagnostic-summary.json', report)
    return 0 if report['run']['validMeasurement'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
