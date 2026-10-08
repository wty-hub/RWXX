"""Run an actual local dynamic game, with optional diagnostic tracing."""
import argparse
import hashlib
import json
import os
import re
import shutil
from pathlib import Path
from map_pan_comparison import PROJECT, isolated_seed, run_environment, analyze_run, write_json
from map_pan_builtin_comparison import (launch, add_camera_arguments, camera_protocol,
    camera_environment, confirm_camera_zoom, confirm_experimental_modes, confirm_dynamic_activity)
from map_pan_diagnostic import export_jfr
from analyze_vulkan_matrix import read_json_lines
from vulkan_native_matrix import java_command
from frozen_benchmark_tooling import fingerprint


CANDIDATES = {
    'frozen-reuse': 'RWX_FROZEN_CONTENT_REUSE', 'map-texture-batches': 'RWX_MAP_TEXTURE_BATCHES',
    'map-fog-batches': 'RWX_MAP_FOG_BATCHES', 'map-zoom-generation': 'RWX_MAP_ZOOM_GENERATION',
    'single-map-cell-render': 'RWX_SINGLE_MAP_CELL_RENDER', 'incremental-sprite-atlas': 'RWX_INCREMENTAL_SPRITE_ATLAS',
    'projected-sprite-atlas': 'RWX_PROJECTED_SPRITE_ATLAS', 'instanced-text-labels': 'RWX_INSTANCED_TEXT_LABELS',
    'text-shader-reuse': 'RWX_TEXT_SHADER_REUSE', 'guard-short-render-park': 'RWX_GUARD_SHORT_RENDER_PARK',
    'stable-frame-deadlines': 'RWX_STABLE_FRAME_DEADLINES', 'wait-for-fresh-frame': 'RWX_WAIT_FOR_FRESH_FRAME',
    'share-map-texture-materials': 'RWX_SHARE_MAP_TEXTURE_MATERIALS',
    'reuse-frozen-texture-references': 'RWX_REUSE_FROZEN_TEXTURE_REFERENCES',
    'reuse-map-mesh-slots': 'RWX_REUSE_MAP_MESH_SLOTS',
    'direct-target-textures': 'RWX_DIRECT_TARGET_TEXTURES',
    'avoid-texture-paint-copies': 'RWX_AVOID_TEXTURE_PAINT_COPIES',
    'reuse-text-mesh-keys': 'RWX_REUSE_TEXT_MESH_KEYS',
    'prepare-canvas-text': 'RWX_PREPARE_CANVAS_TEXT',
    'cache-canvas-shader-templates': 'RWX_CACHE_CANVAS_SHADER_TEMPLATES',
}


def freeze_map(source, output):
    """Use an isolated asset copy for the embedded Europe map; leave project assets untouched."""
    builtin = 'maps/skirmish/[p8]Interlocked Large (8p).tmx'
    if source is None:
        return PROJECT / 'assets', builtin, None
    frozen = output / 'frozen-map.tmx'
    shutil.copy2(source, frozen)
    assets = output / 'assets'
    shutil.copytree(PROJECT / 'assets', assets)
    asset_path = 'maps/skirmish/[p15]Frozen Benchmark Map.tmx'
    shutil.copy2(frozen, assets / asset_path)
    files = {p.relative_to(assets).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(assets.rglob('*')) if p.is_file()}
    inventory = hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    write_json(output / 'asset-manifest.json', files)
    return assets, asset_path, {'source': str(source.resolve()), 'frozen': str(frozen),
        'sha256': hashlib.sha256(frozen.read_bytes()).hexdigest(), 'assetInventorySha256': inventory}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--fog', choices=('off', 'on'), default='off')
    p.add_argument('--mode', choices=('moving', 'combat'), default='moving')
    p.add_argument('--units', type=int, choices=(500, 661, 1000, 2000), default=500)
    p.add_argument('--teams', type=int, default=15)
    p.add_argument('--map-file', type=Path, help='Use this TMX through an isolated copy of the ordinary map catalog and assets')
    p.add_argument('--player-death-seconds', type=int, help='Diagnostic: trigger native defeat of the local player and verify fog changes')
    p.add_argument('--retain-map-units', action='store_true', help='Run the map ordinary game and AI; omit the injected unit fixture')
    p.add_argument('--real-scene-cell-oracle', action='store_true', help='Diagnostic: independently redraw real live map cells and compare final compositions')
    for flag in CANDIDATES:
        p.add_argument('--' + flag, action='store_true', help='Explicit canvas candidate; default remains unchanged')
    diagnostics = p.add_mutually_exclusive_group()
    diagnostics.add_argument('--diagnostic', action='store_true')
    diagnostics.add_argument('--diagnostic-light', action='store_true', help='JFR and engine trace without per-canvas profiling')
    p.add_argument('--vk-trace', action='store_true', help='Record native acquire/fence/submit/present durations; diagnostic only')
    p.add_argument('--engine-trace', action='store_true')
    p.add_argument('--engine-section-trace', action='store_true', help='Record engine sub-sections exceeding 2 ms; diagnostic only')
    p.add_argument('--no-perf-window-log', action='store_true', help='Disable benchmark-only synchronous summary logging')
    p.add_argument('--run-timeout-seconds', type=int, default=155,
                   help='Wall-clock budget for warm-up plus sample windows; fog-enabled runs advance engine '
                        'time slower than real time and need a larger value')
    p.add_argument('--map-cache-trace', action='store_true', help='Record map cell redraws and camera scroll boundaries')
    add_camera_arguments(p)
    p.add_argument('--camera-trace', action='store_true', help='Record actual and requested camera zoom; diagnostic only')
    p.add_argument('--parallel-cell-raster', action='store_true', help='Enable opt-in whole-cell raster stripes')
    p.add_argument('--adaptive-cell-raster', action='store_true', help='Select costly texture cells for stripes; requires --parallel-cell-raster')
    p.add_argument('--gpu-map-cell-cache', action='store_true', help='Deprecated: GPU map targets are already enabled by default')
    p.add_argument('--cpu-cell-raster', action='store_true', help='Explicit CPU map-cell control arm')
    p.add_argument('--heap-mb', type=int, default=1000, help='Maximum heap in MiB, matching the pinned original probe')
    p.add_argument('--disable-gpu-map-cell-pass-reuse', action='store_true',
                   help='Disable post-fence GPU cell pass reuse; incompatible with --cpu-cell-raster')
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
    if args.player_death_seconds is not None and (args.player_death_seconds < 1 or not args.retain_map_units or args.fog != 'on' or not args.real_scene_cell_oracle):
        p.error('player death requires a positive time, ordinary map units, fog on and the real-scene oracle')
    if args.heap_mb < 256:
        p.error('--heap-mb must be at least 256')
    if args.map_file is not None and not args.map_file.is_file():
        p.error('--map-file must identify an existing TMX file')
    if args.disable_gpu_map_cell_pass_reuse and args.cpu_cell_raster:
        p.error('--disable-gpu-map-cell-pass-reuse is incompatible with --cpu-cell-raster')
    if args.adaptive_cell_raster and not args.parallel_cell_raster:
        p.error('--adaptive-cell-raster requires --parallel-cell-raster')
    try:
        camera = camera_protocol(args)
    except ValueError as error:
        p.error(str(error))
    camera_trace = args.camera_trace or ((args.diagnostic or args.diagnostic_light) and camera['zoomMode'] == 'cycle')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    assets, map_path, map_evidence = freeze_map(args.map_file, out)
    jar = out / 'runtime.jar'; shutil.copy2(args.jar, jar)
    name = 'live-pan'; sandbox = out / name; sandbox.mkdir()
    (sandbox / 'preferences.toml').write_text(isolated_seed(), encoding='utf-8')
    cmd = java_command(args.java, jar, PROJECT / 'desktop/build/slick-natives')
    cmd[1:1] = [f'-Dlaunch.dir={sandbox}', f'-Drwx.assetsDir={assets}',
                '-Drwx.kool.backend=vulkan', f'-Drwx.benchmark.map={map_path}', '-Dsun.java2d.uiScale=1',
                '-Xms512m', f'-Xmx{args.heap_mb}M']
    if args.cpu_target_profile:
        cmd.insert(1, '-Drwx.kool.cpuTargetProfile=true')
    if args.raster_threads is not None:
        cmd.insert(1, f'-Drwx.koolRasterThreads={args.raster_threads}')
    if args.layer_buffer_pixels is not None:
        cmd.insert(1, f'-Drwx.koolLayerBufferPixels={args.layer_buffer_pixels}')
    env = run_environment(out, sandbox, name); env.pop('RWX_REPLAY_PAN_OUTPUT')
    for flag, variable in CANDIDATES.items():
        if getattr(args, flag.replace('-', '_')):
            env[variable] = '1'
    if args.real_scene_cell_oracle:
        env['RWX_REAL_SCENE_ORACLE'] = str(out / 'real-scene-oracle')
        env['RWX_REAL_SCENE_CELL_ORACLE'] = '1'
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
    if args.retain_map_units:
        for variable in ('RWX_BENCHMARK_UNITS', 'RWX_BENCHMARK_MIX', 'RWX_BENCHMARK_PALETTE',
                         'RWX_BENCHMARK_MODE', 'RWX_BENCHMARK_TEAMS', 'RWX_BENCHMARK_OUTPUT'):
            env.pop(variable)
    if camera_trace:
        env['RWX_REPLAY_PAN_CAMERA_TRACE'] = str(out / 'camera.csv')
    if args.parallel_cell_raster:
        env['RWX_PARALLEL_CELL_RASTER'] = '1'
    if args.adaptive_cell_raster:
        env['RWX_ADAPTIVE_CELL_RASTER'] = '1'
    # Match the production default. Only an explicit CPU control disables GPU targets.
    env['RWX_GPU_MAP_CELL_TARGETS'] = '0' if args.cpu_cell_raster else '1'
    if not args.cpu_cell_raster:
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
    if args.player_death_seconds is not None:
        env['RWX_BENCHMARK_PLAYER_DEATH_SECONDS'] = str(args.player_death_seconds)
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
    tooling_digest, _ = fingerprint(Path(__file__).resolve().parent)
    if os.environ.get('RWX_BENCHMARK_TOOLING_SHA256') not in (None, tooling_digest):
        raise RuntimeError('frozen live measurement tooling fingerprint changed before launch')
    configuration = {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()
                     if key not in ('output', 'jar')}
    report = {'runtimeSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'toolingSha256': tooling_digest,
              'configuration': configuration, 'configurationSha256': hashlib.sha256(json.dumps(configuration,
                  sort_keys=True, separators=(',', ':')).encode()).hexdigest(), 'mapEvidence': map_evidence,
              'diagnosticOnly': args.diagnostic or args.diagnostic_light or args.vk_trace or camera_trace or args.real_scene_cell_oracle or args.camera_mode == 'edge-jump',
              'diagnosticMode': 'full' if args.diagnostic else 'light' if args.diagnostic_light else 'none',
              'protocol': {'mode': 'ordinary-map' if args.retain_map_units else args.mode, 'teams': None if args.retain_map_units else args.teams, 'fog': args.fog, 'recordReplay': False,
                           'units': None if args.retain_map_units else args.units, 'injectedUnitFixture': not args.retain_map_units,
                           'mapAssetPath': map_path, 'warmupSeconds': 20, 'sampleSeconds': 20, 'repetitions': 2,
                           **camera, 'cameraTraceRequested': camera_trace,
                           'parallelCellRasterRequested': args.parallel_cell_raster,
                           'adaptiveCellRasterRequested': args.adaptive_cell_raster,
                           'gpuMapCellCacheRequested': not args.cpu_cell_raster,
                           'heapMiB': args.heap_mb,
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
    run = launch(cmd, env, out, name, args.run_timeout_seconds)
    run.update({'localMapExpected': True, 'mapFogExpected': args.fog == 'on', 'cameraModeExpected': camera['cameraMode']})
    report['run'] = analyze_run(out, run)
    confirm_camera_zoom(report['run'], camera)
    confirm_dynamic_activity(report['run'])
    if args.player_death_seconds is not None:
        rows = read_json_lines(out / f'{name}-scenario.ndjson')
        requests = [r for r in rows if r.get('kind') == 'player-death-request']
        observations = [r for r in rows if r.get('kind') == 'player-visibility']
        before = any(r.get('livingPlayerUnits', 0) > 0 and not r.get('playerWipedOut') and r.get('hiddenFogCells', 0) > 0 for r in observations)
        after = any(r.get('livingPlayerUnits') == 0 and r.get('playerWipedOut') and r.get('hiddenFogCells') == 0 for r in observations)
        report['playerDeathEvidence'] = {'requests': requests, 'observations': observations, 'nativeTransitionConfirmed': before and after}
        if len(requests) != 1 or requests[0].get('markedUnits', 0) < 1 or not before or not after:
            report['run']['invalidReasons'].append('native player death and fog visibility transition was not observed')
            report['run']['validMeasurement'] = False
    if args.camera_mode == 'edge-jump' and any(w.get('sampledEdgeMask') != 15 for w in report['run']['windows']):
        report['run']['invalidReasons'].append('the ordinary camera did not reach all four map corners in each window')
        report['run']['validMeasurement'] = False
    fixture_path = out / 'fixture.ndjson'
    fixture_rows = [] if args.retain_map_units and not fixture_path.exists() else read_json_lines(fixture_path)
    fixtures = [row for row in fixture_rows if row.get('kind') == 'scenario']
    report['fixture'] = fixtures[0] if len(fixtures) == 1 else None
    fixture_matches = report['fixture'] and all(report['fixture'].get(key) == expected for key, expected in (
        ('requestedUnits', args.units), ('mode', args.mode), ('teams', args.teams), ('fogEnabled', args.fog == 'on')))
    if not args.retain_map_units and not fixture_matches:
        report['run']['invalidReasons'].append('local dynamic fixture setup differs from requested unit count, mode, teams or map fog')
        report['run']['validMeasurement'] = False
    if args.retain_map_units and fixtures:
        report['run']['invalidReasons'].append('injected units were observed in an ordinary-map control')
        report['run']['validMeasurement'] = False
    confirm_experimental_modes(report['run'], (out / f'{name}.log').read_text(encoding='utf-8', errors='replace'),
                               args.parallel_cell_raster, args.primitive_text_metrics, args.adaptive_cell_raster, not args.cpu_cell_raster)
    if args.real_scene_cell_oracle:
        oracle_path = out / 'real-scene-oracle/summary.json'
        evidence = json.loads(oracle_path.read_text(encoding='utf-8')) if oracle_path.exists() else None
        report['realSceneCorrectnessEvidence'] = evidence
        if not evidence or not evidence.get('passed'):
            report['run']['invalidReasons'].append('live map pixel oracle is incomplete or found incorrect pixels')
            report['run']['validMeasurement'] = False
    if args.reuse_frozen_texture_references:
        log = (out / f'{name}.log').read_text(encoding='utf-8', errors='replace')
        if 'RWXFrozenTextureReferences reuseConfirmed=true' not in log:
            report['run']['invalidReasons'].append('frozen texture reference reuse was not confirmed by actual source versions')
            report['run']['validMeasurement'] = False
    if args.reuse_map_mesh_slots:
        log = (out / f'{name}.log').read_text(encoding='utf-8', errors='replace')
        if 'RWXMapMeshSlots reuseConfirmed=true' not in log:
            report['run']['invalidReasons'].append('map mesh slot reuse did not confirm a compatible unused mesh')
            report['run']['validMeasurement'] = False
    if args.direct_target_textures:
        log = (out / f'{name}.log').read_text(encoding='utf-8', errors='replace')
        if 'directTargetTextures=true' not in log:
            report['run']['invalidReasons'].append('direct offscreen source texture sampling was not confirmed by the runtime')
            report['run']['validMeasurement'] = False
    if args.avoid_texture_paint_copies:
        log = (out / f'{name}.log').read_text(encoding='utf-8', errors='replace')
        if 'RWXTexturePaintCopies reuseConfirmed=true' not in log:
            report['run']['invalidReasons'].append('immutable texture paint reuse did not confirm actual unchanged effects')
            report['run']['validMeasurement'] = False
    if args.reuse_text_mesh_keys:
        log = (out / f'{name}.log').read_text(encoding='utf-8', errors='replace')
        if 'RWXTextMeshKeys reuseConfirmed=true' not in log:
            report['run']['invalidReasons'].append('text mesh identity reuse did not confirm actual cache hits')
            report['run']['validMeasurement'] = False
    if args.prepare_canvas_text:
        log = (out / f'{name}.log').read_text(encoding='utf-8', errors='replace')
        if 'RWXPreparedCanvasText reuseConfirmed=true' not in log:
            report['run']['invalidReasons'].append('prepared canvas text did not confirm actual private font reuse')
            report['run']['validMeasurement'] = False
    if args.cache_canvas_shader_templates:
        log = (out / f'{name}.log').read_text(encoding='utf-8', errors='replace')
        totals = re.findall(r'RWXCanvasShaderTemplates totals created=(\d+) hits=(\d+)', log)
        report['shaderTemplateEvidence'] = {'totals': [{'created': int(created), 'hits': int(hits)} for created, hits in totals]}
        if 'RWXCanvasShaderTemplates enabled=true' not in log or len(totals) != 1 or int(totals[0][1]) == 0:
            report['run']['invalidReasons'].append('CPU shader template reuse is not confirmed by runtime activity')
            report['run']['validMeasurement'] = False
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
