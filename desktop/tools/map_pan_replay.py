"""Run a frozen real replay with rapid camera motion in the actual Vulkan game."""
import argparse
import hashlib
import math
import json
import os
import re
from pathlib import Path
import shutil

from map_pan_comparison import (PROJECT, REPLAY_ALIAS, analyze_run, copy_replay_alias,
                                isolated_seed, run_environment, write_json)
from map_pan_builtin_comparison import launch, confirm_experimental_modes
from map_pan_diagnostic import export_jfr
from vulkan_native_matrix import java_command
from frozen_benchmark_tooling import fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--replay', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--warmup-seconds', type=int, default=20)
    parser.add_argument('--sample-seconds', type=int, default=20)
    parser.add_argument('--repetitions', type=int, default=2)
    parser.add_argument('--camera-period-seconds', type=int, default=1)
    parser.add_argument('--camera-mode', choices=('pan', 'jump', 'pan-zoom', 'zoom', 'static'), default='pan',
                        help='pan-zoom pans and zooms; zoom cycles zoom at a fixed camera center')
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
    parser.add_argument('--export-replay-map', action='store_true', help='Diagnostic: copy the embedded map into this new output directory for normal-game correctness checks')
    parser.add_argument('--vk-trace', action='store_true')
    parser.add_argument('--map-tile-run-trace', action='store_true',
                        help='Report map tile draw/merge counters into the canvas stage trace')
    parser.add_argument('--offscreen-tile-run-merge-off', action='store_true',
                        help='Disable the map tile horizontal run merge (control arm)')
    parser.add_argument('--loop-split', action='store_true',
                        help='Attribute command-loop time to its steps, into the canvas stage trace')
    parser.add_argument('--map-backend-diag', action='store_true',
                        help='Log the layer-buffer resource backend and its GPU-render-target capability')
    parser.add_argument('--reuse-trace', action='store_true',
                        help='Report whole-frame frozen-content reuse outcomes to map-cache.csv.reuse')
    parser.add_argument('--zoom-enlarge-step', type=float,
                        help='RWX_MAP_CELL_ZOOM_ENLARGE_STEP: how far renderScale must rise before the terrain '
                             'grid is re-laid. Vanilla uses 0.1 on PC, which measured as 63%% of interaction '
                             'resets; a larger step trades cache sharpness for fewer re-rasterisations.')
    parser.add_argument('--cell-pixels', type=int, choices=(256, 384, 512),
                        help='rwx.koolLayerBufferPixels: offscreen map cell size. Physical pixel count is the '
                             'one lever this project has repeatedly confirmed, and the cell size is the part '
                             'of it the renderer owns, so it needs to be measurable.')
    parser.add_argument('--frame-based-zoom-cache', action='store_true',
                        help='RWX_TIME_BASED_MAP_ZOOM_CACHE=0: control arm for the terrain zoom-cache '
                             'cadence. The time-based one is the default because frame counting made the '
                             'refresh interval scale with the engine refresh rate.')
    parser.add_argument('--cpu-cell-raster', action='store_true',
                        help='RWX_GPU_MAP_CELL_TARGETS=0: map cells are CPU-rasterised instead of being GPU '
                             'offscreen passes. This is the real control arm - --gpu-map-cell-cache is a '
                             'no-op because the GPU path is already the default.')
    parser.add_argument('--sync-scene-update', action='store_true',
                        help='Kept for the recorded experiment only: Kool async scene update is hardcoded off '
                             'because enabling it made pan+zoom runs fail to complete and races game state')
    parser.add_argument('--frozen-reuse', action='store_true',
                        help='RWX_FROZEN_CONTENT_REUSE=1: reuse unchanged frozen cell content instead of '
                             're-freezing it every frame; each hit restores its validated transitive resources')
    parser.add_argument('--tile-run', action='store_true',
                        help='RWX_MAP_TILE_RUN_TILE=1: re-enable the terrain row merge. It is OFF by default '
                             'because it leaves black holes across the map; --tile-run is for fixing it.')
    parser.add_argument('--map-texture-batches', action='store_true',
                        help='Compact ordered GPU map-cell texture batches; experimental candidate')
    parser.add_argument('--map-fog-batches', action='store_true',
                        help='Literal-order mixed fog mask batches; experimental candidate')
    parser.add_argument('--map-zoom-generation', action='store_true',
                        help='One previous world-coordinate cache generation; experimental candidate')
    parser.add_argument('--text-shader-reuse', action='store_true', help='Share MSDF pipelines by immutable font material')
    parser.add_argument('--disable-map-rect-batches', action='store_true',
                        help='Control arm: retain texture batches but record fog rectangles individually')
    parser.add_argument('--offscreen-atlas-off', action='store_true',
                        help='RWX_OFFSCREEN_TEXTURE_ATLAS=0: disable the offscreen texture atlas (control arm)')
    parser.add_argument('--msaa', type=int,
                        help='RWX_KOOL_MSAA_SAMPLES. The runs so far used 4; a high sample count is a '
                             'renderer-side cost that has nothing to do with command count.')
    parser.add_argument('--target-fps', type=int,
                        help='RWX_DESKTOP_TARGET_FPS. The desktop loop is software-paced: without this it '
                             'falls back to the settings maxFrameRate, and 120 when that is unset, so a '
                             'measurement can be reporting the cap rather than the workload.')
    parser.add_argument('--heap-mb', type=int, default=1000, help='Maximum Java heap in MiB; default matches the pinned original probe')
    parser.add_argument('--g1-region-mb', type=int,
                        help='-XX:G1HeapRegionSize in MB. G1 treats any object of at least half a region as '
                             'humongous, and this project allocates >=512KB rasters (1MB cell pixel buffers, '
                             'a 16MB sprite atlas page), which G1 then has to collect specially.')
    parser.add_argument('--engine-section-trace', action='store_true')
    parser.add_argument('--input-response-trace', action='store_true', help='Correctness: normal session input adoption and accepted picture latency')
    parser.add_argument('--real-scene-oracle', action='store_true', help='Correctness: compare live leased game snapshots through control and candidate rendering')
    parser.add_argument('--real-scene-cell-oracle', action='store_true', help='Correctness: additionally compare actual live map attachments with independent replays of the same frozen cells')
    parser.add_argument('--reuse-paint-snapshots', action='store_true', help='Candidate: reuse unchanged immutable paints after checking all normalized legacy getter values')
    parser.add_argument('--share-immutable-commands', action='store_true', help='Candidate: share immutable non-texture commands when freezing leaves their paint unchanged')
    parser.add_argument('--single-map-cell-render', action='store_true', help='Candidate: render each GPU map content version exactly once after fixing draw-queue group retention')
    parser.add_argument('--reuse-typeface-keys', action='store_true', help='Candidate: reuse the key of each immutable legacy typeface')
    parser.add_argument('--compact-quad-storage', action='store_true', help='Candidate: allocate fixed quad geometry for eight entries instead of 1024')
    parser.add_argument('--reuse-instanced-texture-shaders', action='store_true', help='Candidate: share immutable texture materials across instanced meshes with independent transforms and draw order')
    parser.add_argument('--normal-input-probe', action='store_true', help='Correctness: exercise drag, arrow keys and wheel through normal session input')
    parser.add_argument('--legacy-owner-pacing', action='store_true')
    parser.add_argument('--legacy-short-owner-park', action='store_true')
    parser.add_argument('--guard-short-owner-park', action='store_true')
    parser.add_argument('--guard-short-render-park', action='store_true', help='Candidate: avoid coarse Windows waits near render deadlines')
    parser.add_argument('--stable-frame-deadlines', action='store_true', help='Candidate: compensate small wake-up drift without changing simulation delta or catching up missed intervals')
    parser.add_argument('--wait-for-fresh-frame', action='store_true', help='Candidate: wait up to 1.5 ms for a new publication before collecting a repeated picture; input-response gate remains mandatory')
    parser.add_argument('--post-input-frame-wait', action='store_true', help='Candidate: move bounded publication wait after normal input forwarding; require that publication includes adopted input')
    parser.add_argument('--share-map-texture-materials', action='store_true', help='Candidate: share immutable instanced materials between map target slots, retaining independent mesh/view data and existing GPU fences')
    parser.add_argument('--reuse-frozen-texture-references', action='store_true', help='Candidate: cache CPU-only immutable versioned references after restoring each full resource closure')
    parser.add_argument('--reuse-map-mesh-slots', action='store_true', help='Candidate: re-key compatible unused map meshes while retaining their buffers and existing target frame fences')
    parser.add_argument('--direct-target-textures', action='store_true', help='Candidate: map/offscreen targets sample leased source textures without creating a per-slot sprite atlas')
    parser.add_argument('--avoid-texture-paint-copies', action='store_true', help='Candidate: retain an immutable texture paint when the resolved team effect is unchanged')
    parser.add_argument('--reuse-text-mesh-keys', action='store_true', help='Candidate: allocation-free lookups while retaining immutable canonical text mesh identities')
    parser.add_argument('--prepare-canvas-text', action='store_true', help='Candidate: renderer-private derived fonts and one immutable text preparation per label')
    parser.add_argument('--cache-canvas-shader-templates', action='store_true', help='Candidate: reuse immutable compiled CPU shader descriptions, preserving independent per-shader binding and pipeline lifetime')
    parser.add_argument('--disable-text-mesh-reuse', action='store_true')
    parser.add_argument('--text-geometry-templates', action='store_true',
                        help='Candidate: reuse font-local text geometry across moving labels')
    parser.add_argument('--instanced-text-glyphs', action='store_true', help='Candidate: one GPU instance per shaped glyph')
    parser.add_argument('--instanced-text-labels', action='store_true', help='Candidate: one GPU instance per complete label with immutable geometry generations')
    parser.add_argument('--incremental-sprite-atlas', action='store_true', help='Candidate: append only new sprite texels through fence-scoped Vulkan uploads')
    parser.add_argument('--projected-sprite-atlas', action='store_true', help='Candidate: batch immutable world sprites in nested canvases without changing draw order')
    parser.add_argument('--disable-texture-metadata-reuse', action='store_true')
    parser.add_argument('--time-based-map-zoom-cache', action='store_true', help='Opt-in display-cache cadence experiment')
    parser.add_argument('--parallel-cell-raster', action='store_true', help='Opt-in whole-cell CPU stripe experiment')
    parser.add_argument('--adaptive-cell-raster', action='store_true', help='Select costly texture cells for stripes; requires --parallel-cell-raster')
    parser.add_argument('--gpu-map-cell-cache', action='store_true',
                        help='DEPRECATED / NO-OP: GPU offscreen map cells are already the default '
                             '(KoolGraphicsEngine.gpuRenderTargetsEnabled returns true by default), so this '
                             'adds nothing and must not be used as an on/off control arm. Use '
                             '--cpu-cell-raster for the CPU-rasterised arm.')
    parser.add_argument('--disable-gpu-map-cell-pass-reuse', action='store_true',
                        help='Disable post-fence GPU cell pass reuse; incompatible with --cpu-cell-raster')
    parser.add_argument('--primitive-text-metrics', action='store_true', help='Opt-in primitive glyph metrics experiment')
    parser.add_argument('--cpu-target-profile', action='store_true', help='Log CPU raster details; diagnostic timing only')
    parser.add_argument('--raster-threads', type=int, choices=range(1, 9), help='Override CPU raster workers for controlled tests')
    parser.add_argument('--no-perf-window-log', action='store_true')
    parser.add_argument('--canvas-stage-trace', action='store_true')
    parser.add_argument('--command-profile', action='store_true',
                        help='Diagnostic: dump per-pass canvas command distribution and run lengths; timing is diagnostic only')
    parser.add_argument('--command-timing', action='store_true',
                        help='Diagnostic: dump render-thread wall clock per canvas command kind; timing is diagnostic only')
    parser.add_argument('--legacy-text-vertex-allocation', action='store_true',
                        help='Controlled A/B: restore the pre-optimisation per-vertex text allocation')
    parser.add_argument('--gc-log', action='store_true',
                        help='Diagnostic: write unified JVM GC logging to gc.log; used to attribute long command stalls')
    parser.add_argument('--map-cell-min-render-scale', type=float,
                        help='Floor for the map cell rasterisation scale (vanilla uses the zoom, i.e. 1/zoom over-sampling)')
    parser.add_argument('--legacy-source-versioning', action='store_true',
                        help='Controlled A/B: restore unconditional source version bumps on re-commit')
    parser.add_argument('--disable-native-bgra-upload', action='store_true', help='Controlled Vulkan RGBA baseline')
    parser.add_argument('--native-bgra-upload', action='store_true', help='Enable the experimental Vulkan BGRA path')
    parser.add_argument('--force-legacy-reload-gc', action='store_true')
    args = parser.parse_args()
    if args.warmup_seconds < 1 or args.sample_seconds < 1 or args.repetitions < 1:
        parser.error('warmup, sample duration and repetitions must be positive')
    if args.heap_mb < 256:
        parser.error('--heap-mb must be at least 256')
    if args.disable_gpu_map_cell_pass_reuse and args.cpu_cell_raster:
        parser.error('--disable-gpu-map-cell-pass-reuse is incompatible with --cpu-cell-raster')
    if args.adaptive_cell_raster and not args.parallel_cell_raster:
        parser.error('--adaptive-cell-raster requires --parallel-cell-raster')
    if args.camera_period_seconds < 1 or args.zoom_period_seconds < 1:
        parser.error('camera and zoom periods must be >= 1 second')
    if not (math.isfinite(args.zoom_min) and math.isfinite(args.zoom_max) and 0 < args.zoom_min < args.zoom_max):
        parser.error('zoom limits must be finite and 0 < zoom-min < zoom-max')
    camera_mode = {'pan-zoom': 'pan', 'zoom': 'static'}.get(args.camera_mode, args.camera_mode)
    zoom_mode = args.zoom_mode or ('cycle' if args.camera_mode in ('pan-zoom', 'zoom') else 'none')
    if args.normal_input_probe and (camera_mode != 'static' or zoom_mode != 'none' or not args.input_response_trace):
        parser.error('--normal-input-probe requires --camera-mode static and --input-response-trace')
    if args.camera_mode in ('pan-zoom', 'zoom') and zoom_mode != 'cycle':
        parser.error('pan-zoom and zoom require cycle zoom')
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
                    '-Drwx.kool.backend=vulkan', '-Dsun.java2d.uiScale=1',
                    f'-Xms{min(512, args.heap_mb)}m', f'-Xmx{args.heap_mb}M']
    if args.g1_region_mb is not None:
        command[1:1] = [f'-XX:G1HeapRegionSize={args.g1_region_mb}m']
    if args.gc_log:
        command[1:1] = [f'-Xlog:gc*:file={output / "gc.log"}:time,uptime,level,tags:filecount=1,filesize=64m']
    if args.diagnostic:
        command[1:1] = ['-XX:FlightRecorderOptions=stackdepth=64',
                       f'-XX:StartFlightRecording=filename={output / "profile.jfr"},settings=profile,dumponexit=true']
    if args.cpu_target_profile:
        command[1:1] = ['-Drwx.kool.cpuTargetProfile=true']
    if args.raster_threads is not None:
        command[1:1] = [f'-Drwx.koolRasterThreads={args.raster_threads}']
    env = run_environment(output, sandbox, 'replay-pan')
    if args.reuse_frozen_texture_references:
        env['RWX_REUSE_FROZEN_TEXTURE_REFERENCES'] = '1'
    if args.reuse_map_mesh_slots:
        env['RWX_REUSE_MAP_MESH_SLOTS'] = '1'
    if args.direct_target_textures:
        env['RWX_DIRECT_TARGET_TEXTURES'] = '1'
    if args.avoid_texture_paint_copies:
        env['RWX_AVOID_TEXTURE_PAINT_COPIES'] = '1'
    if args.reuse_text_mesh_keys:
        env['RWX_REUSE_TEXT_MESH_KEYS'] = '1'
    if args.prepare_canvas_text:
        env['RWX_PREPARE_CANVAS_TEXT'] = '1'
    if args.cache_canvas_shader_templates:
        env['RWX_CACHE_CANVAS_SHADER_TEMPLATES'] = '1'
    if args.export_replay_map:
        env['RWX_EXPORT_REPLAY_MAP'] = str(output / 'embedded-map.tmx')
    if args.single_map_cell_render:
        env['RWX_SINGLE_MAP_CELL_RENDER'] = '1'
    if args.reuse_typeface_keys:
        env['RWX_REUSE_TYPEFACE_KEYS'] = '1'
    if args.compact_quad_storage:
        env['RWX_COMPACT_QUAD_STORAGE'] = '1'
    if args.reuse_instanced_texture_shaders:
        env['RWX_REUSE_INSTANCED_TEXTURE_SHADERS'] = '1'
    measurement_protocol = {'warmupSeconds': args.warmup_seconds,
                            'sampleSeconds': args.sample_seconds, 'repetitions': args.repetitions}
    env.update({'RWX_REPLAY_PAN_WARMUP_SECONDS': str(args.warmup_seconds),
                'RWX_REPLAY_PAN_SAMPLE_SECONDS': str(args.sample_seconds),
                'RWX_REPLAY_PAN_REPETITIONS': str(args.repetitions),
                'RWX_DEBUG_AUTO_EXIT_SECONDS': str(max(140, args.warmup_seconds + args.sample_seconds * args.repetitions + 85))})
    if args.disable_map_rect_batches:
        env['RWX_MAP_RECT_BATCHES'] = '0'
    if args.msaa is not None:
        env['RWX_KOOL_MSAA_SAMPLES'] = str(args.msaa)
    if args.target_fps is not None:
        env['RWX_DESKTOP_TARGET_FPS'] = str(args.target_fps)
    if args.legacy_owner_pacing:
        env['RWX_LEGACY_OWNER_PACING'] = '1'
    if args.legacy_short_owner_park:
        env['RWX_LEGACY_SHORT_OWNER_PARK'] = '1'
    if args.guard_short_owner_park:
        env['RWX_GUARD_SHORT_OWNER_PARK'] = '1'
    if args.guard_short_render_park:
        env['RWX_GUARD_SHORT_RENDER_PARK'] = '1'
    if args.stable_frame_deadlines:
        env['RWX_STABLE_FRAME_DEADLINES'] = '1'
    if args.wait_for_fresh_frame:
        env['RWX_WAIT_FOR_FRESH_FRAME'] = '1'
    if args.post_input_frame_wait:
        env['RWX_POST_INPUT_FRAME_WAIT'] = '1'
    if args.share_map_texture_materials:
        env['RWX_SHARE_MAP_TEXTURE_MATERIALS'] = '1'
    if args.disable_text_mesh_reuse:
        env['RWX_DISABLE_TEXT_MESH_REUSE'] = '1'
    if args.text_geometry_templates:
        env['RWX_TEXT_GEOMETRY_TEMPLATES'] = '1'
    if args.instanced_text_glyphs:
        env['RWX_INSTANCED_TEXT_GLYPHS'] = '1'
    if args.instanced_text_labels:
        env['RWX_INSTANCED_TEXT_LABELS'] = '1'
    if args.incremental_sprite_atlas:
        env['RWX_INCREMENTAL_SPRITE_ATLAS'] = '1'
    if args.real_scene_oracle or args.real_scene_cell_oracle:
        env['RWX_REAL_SCENE_ORACLE'] = str(output / 'real-scene-oracle')
    if args.real_scene_cell_oracle:
        env['RWX_REAL_SCENE_CELL_ORACLE'] = '1'
    if args.reuse_paint_snapshots:
        env['RWX_REUSE_PAINT_SNAPSHOTS'] = '1'
    if args.share_immutable_commands:
        env['RWX_SHARE_IMMUTABLE_COMMANDS'] = '1'
    if args.projected_sprite_atlas:
        env['RWX_PROJECTED_SPRITE_ATLAS'] = '1'
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
        env['RWX_VK_METRICS'] = str(output / 'vulkan-metrics.jsonl')
    if args.engine_section_trace:
        env['RWX_ENGINE_SECTION_TRACE'] = str(output / 'engine-sections.csv')
    if args.no_perf_window_log:
        env.pop('RWX_PERF_LOG', None)
    if args.canvas_stage_trace:
        env['RWX_CANVAS_TRACE'] = str(output / 'canvas-stages.csv')
    if args.command_profile:
        env['RWX_CANVAS_COMMAND_PROFILE'] = str(output / 'canvas-command-profile.csv')
    if args.command_timing:
        env['RWX_COMMAND_TIMING'] = str(output / 'canvas-command-timing.csv')
    if args.legacy_text_vertex_allocation:
        env['RWX_LEGACY_TEXT_VERTEX_ALLOCATION'] = '1'
    if args.map_cell_min_render_scale is not None:
        env['RWX_MAP_CELL_MIN_RENDER_SCALE'] = str(args.map_cell_min_render_scale)
    # The harness passes only an allow-list of RWX_* variables, so a flag set in the parent shell never
    # reaches the game. Diagnostics that need an env switch must be exposed as an argument here.
    if args.map_tile_run_trace:
        env['RWX_MAP_TILE_RUN_TRACE'] = '1'
    if args.loop_split:
        env['RWX_LOOP_SPLIT'] = '1'
    if args.map_backend_diag:
        env['RWX_MAP_BACKEND_DIAG'] = '1'
    if args.reuse_trace:
        env['RWX_REUSE_TRACE'] = '1'
    if args.offscreen_atlas_off:
        env['RWX_OFFSCREEN_TEXTURE_ATLAS'] = '0'
    if args.frozen_reuse:
        env['RWX_FROZEN_CONTENT_REUSE'] = '1'
    if args.sync_scene_update:
        env['RWX_ASYNC_SCENE_UPDATE'] = '0'
    if args.cpu_cell_raster:
        env['RWX_GPU_MAP_CELL_TARGETS'] = '0'
    if args.frame_based_zoom_cache:
        env['RWX_TIME_BASED_MAP_ZOOM_CACHE'] = '0'
    if args.cell_pixels is not None:
        command[1:1] = [f'-Drwx.koolLayerBufferPixels={args.cell_pixels}']
    if args.zoom_enlarge_step is not None:
        env['RWX_MAP_CELL_ZOOM_ENLARGE_STEP'] = str(args.zoom_enlarge_step)
    if args.native_bgra_upload:
        env['RWX_NATIVE_BGRA_UPLOAD'] = '1'
    if args.tile_run:
        env['RWX_MAP_TILE_RUN_TILE'] = '1'
    if args.map_texture_batches:
        env['RWX_MAP_TEXTURE_BATCHES'] = '1'
    if args.map_fog_batches:
        env['RWX_MAP_FOG_BATCHES'] = '1'
    if args.map_zoom_generation:
        env['RWX_MAP_ZOOM_GENERATION'] = '1'
    if args.text_shader_reuse:
        env['RWX_TEXT_SHADER_REUSE'] = '1'
    if args.legacy_source_versioning:
        env['RWX_LEGACY_SOURCE_VERSIONING'] = '1'
    if args.disable_native_bgra_upload:
        env['RWX_DISABLE_NATIVE_BGRA_UPLOAD'] = '1'
    if args.native_bgra_upload:
        env['RWX_NATIVE_BGRA_UPLOAD'] = '1'
    if args.diagnostic or args.canvas_stage_trace or args.vk_trace or args.engine_section_trace:
        env.update({'RWX_ENGINE_FRAME_TRACE': str(output / 'engine.csv'),
                    'RWX_MAP_CACHE_TRACE': str(output / 'map-cache.csv'),
                    'RWX_CANVAS_MEMORY_DIAGNOSTICS': '1'})
    if args.input_response_trace:
        env['RWX_INPUT_RESPONSE_TRACE'] = str(output / 'input-response.csv')
    if args.normal_input_probe:
        env['RWX_NORMAL_INPUT_PROBE'] = '1'
    env.update({'RWX_REPLAY_PAN_PERIOD_SECONDS': str(args.camera_period_seconds),
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
    tooling_digest, _ = fingerprint(Path(__file__).resolve().parent)
    expected_tooling = os.environ.get('RWX_BENCHMARK_TOOLING_SHA256')
    if expected_tooling and tooling_digest != expected_tooling:
        raise RuntimeError('frozen benchmark tooling fingerprint changed before launch')
    report = {'runtimeSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'toolingSha256': tooling_digest,
              'preferencesSha256': hashlib.sha256((sandbox / 'preferences.toml').read_bytes()).hexdigest(),
              'configuration': {key: str(value) if isinstance(value, Path) else value
                                for key, value in vars(args).items() if key not in ('output', 'jar', 'replay')},
              'effectiveNativeBgraUploads': not args.disable_native_bgra_upload,
              'mapFogBatchesRequested': args.map_fog_batches,
              'replaySha256': digest, 'replaySource': str(args.replay.resolve()),
              'diagnosticOnly': args.diagnostic or args.vk_trace or args.engine_section_trace or camera_trace or args.cpu_target_profile
                                or args.canvas_stage_trace or args.command_profile or args.command_timing or args.gc_log or args.input_response_trace or args.real_scene_oracle or args.real_scene_cell_oracle or args.export_replay_map,
              'measurementProtocol': measurement_protocol,
              'protocol': {'fog': args.fog, 'cameraMode': camera_mode, 'cameraModeRequested': args.camera_mode,
                           'cameraPeriodSeconds': args.camera_period_seconds,
                           'zoomMode': zoom_mode, 'zoomPeriodSeconds': args.zoom_period_seconds,
                           'zoomMinimumRequested': args.zoom_min, 'zoomMaximumRequested': args.zoom_max,
                           'cameraTraceRequested': camera_trace,
                           'logicalWindowWidth': args.window_width, 'logicalWindowHeight': args.window_height,
                           'perfWindowLogRequested': not args.no_perf_window_log,
                           'canvasStageTraceRequested': args.canvas_stage_trace,
                           'nativeBgraUploadsRequested': not args.disable_native_bgra_upload,
                           'hybridOwnerPacingRequested': not args.legacy_owner_pacing,
                           'legacyShortOwnerParkRequested': args.legacy_short_owner_park or not args.guard_short_owner_park,
                           'shortParkGuardRequested': not args.legacy_owner_pacing and args.guard_short_owner_park and not args.legacy_short_owner_park,
                           'textMeshReuseRequested': not args.disable_text_mesh_reuse,
                           'textureMetadataReuseRequested': not args.disable_texture_metadata_reuse,
                           'timeBasedMapZoomCacheRequested': args.time_based_map_zoom_cache,
                           'parallelCellRasterRequested': args.parallel_cell_raster,
                           'adaptiveCellRasterRequested': args.adaptive_cell_raster,
                           'gpuMapCellCacheRequested': not args.cpu_cell_raster,
                           'gpuMapCellPassReuseDisabled': args.disable_gpu_map_cell_pass_reuse,
                           'primitiveTextMetricsRequested': args.primitive_text_metrics,
                           'preparedCanvasTextRequested': args.prepare_canvas_text,
                           'cpuTargetProfileRequested': args.cpu_target_profile,
                           'rasterThreadsRequested': args.raster_threads,
                           'vulkanStageTraceRequested': args.vk_trace,
                           'engineSectionTraceRequested': args.engine_section_trace,
                           'forceLegacyReloadGc': args.force_legacy_reload_gc},
              'purpose': 'Actual replay timing and correctness confirmation; one process, not an A/B comparison.'}
    report['configurationSha256'] = hashlib.sha256(json.dumps(report['configuration'], sort_keys=True,
        ensure_ascii=True, separators=(',', ':')).encode('utf-8')).hexdigest()
    write_json(output / 'diagnostic-summary.json', report)
    run = launch(command, env, output, 'replay-pan',
                 max(155, args.warmup_seconds + args.sample_seconds * args.repetitions + 95))
    run['measurementProtocol'] = measurement_protocol
    run.update({'fogDisplayExpected': args.fog == 'on', 'cameraModeExpected': camera_mode,
                'replayName': REPLAY_ALIAS, 'replayAliasPath': str(alias),
                'replayAliasVerified': hashlib.sha256(alias.read_bytes()).hexdigest() == digest,
                'replayAliasSha256': digest})
    report['run'] = analyze_run(output, run)
    if args.export_replay_map:
        rows = [json.loads(line) for line in (output / 'replay-pan-scenario.ndjson').read_text(encoding='utf-8').splitlines()]
        exports = [row for row in rows if row.get('kind') == 'map-export']
        exported = output / 'embedded-map.tmx'
        evidence = exports[0] if len(exports) == 1 else None
        confirmed = bool(evidence) and exported.is_file() and evidence.get('path') == str(exported) and \
            evidence.get('bytes') == exported.stat().st_size and \
            evidence.get('sha256') == hashlib.sha256(exported.read_bytes()).hexdigest()
        report['embeddedMapExport'] = {'confirmed': confirmed, 'evidence': evidence}
        if not confirmed:
            report['run']['invalidReasons'].append('embedded replay map export is missing or its fingerprint differs')
            report['run']['validMeasurement'] = False
    actual_log = (output / 'replay-pan.log').read_text(encoding='utf-8', errors='replace')
    if args.frozen_reuse and 'frozenContentReuse=true' not in actual_log:
        report['run']['invalidReasons'].append('frozen resource-closure reuse candidate is not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.stable_frame_deadlines and 'stableFrameDeadlines=true' not in actual_log:
        report['run']['invalidReasons'].append('stable frame deadlines candidate is not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.wait_for_fresh_frame and not args.post_input_frame_wait and 'RWXFreshFrameWait active=true' not in actual_log:
        report['run']['invalidReasons'].append('fresh-frame wait candidate did not become active in this run')
        report['run']['validMeasurement'] = False
    if args.post_input_frame_wait and 'RWXPostInputFrameWait active=true' not in actual_log:
        report['run']['invalidReasons'].append('post-input publication wait did not become active in this run')
        report['run']['validMeasurement'] = False
    if args.share_map_texture_materials and 'shareMapTextureMaterials=true' not in actual_log:
        report['run']['invalidReasons'].append('shared map texture materials are not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.reuse_frozen_texture_references and 'RWXFrozenTextureReferences reuseConfirmed=true' not in actual_log:
        report['run']['invalidReasons'].append('frozen texture reference reuse was not confirmed by actual source versions')
        report['run']['validMeasurement'] = False
    if args.reuse_map_mesh_slots and 'RWXMapMeshSlots reuseConfirmed=true' not in actual_log:
        report['run']['invalidReasons'].append('map mesh slot reuse did not confirm a compatible unused mesh')
        report['run']['validMeasurement'] = False
    if args.direct_target_textures and 'directTargetTextures=true' not in actual_log:
        report['run']['invalidReasons'].append('direct offscreen source texture sampling was not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.avoid_texture_paint_copies and 'RWXTexturePaintCopies reuseConfirmed=true' not in actual_log:
        report['run']['invalidReasons'].append('immutable texture paint reuse did not confirm actual unchanged effects')
        report['run']['validMeasurement'] = False
    if args.reuse_text_mesh_keys and 'RWXTextMeshKeys reuseConfirmed=true' not in actual_log:
        report['run']['invalidReasons'].append('text mesh identity reuse did not confirm actual cache hits')
        report['run']['validMeasurement'] = False
    if args.prepare_canvas_text and 'RWXPreparedCanvasText reuseConfirmed=true' not in actual_log:
        report['run']['invalidReasons'].append('prepared canvas text did not confirm actual private font reuse')
        report['run']['validMeasurement'] = False
    if args.cache_canvas_shader_templates and 'RWXCanvasShaderTemplates enabled=true' not in actual_log:
        report['run']['invalidReasons'].append('immutable CPU shader templates are not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.cache_canvas_shader_templates:
        totals = re.findall(r'RWXCanvasShaderTemplates totals created=(\d+) hits=(\d+)', actual_log)
        report['shaderTemplateEvidence'] = {'totals': [{'created': int(created), 'hits': int(hits)} for created, hits in totals]}
        if len(totals) != 1 or int(totals[0][1]) == 0:
            report['run']['invalidReasons'].append('immutable CPU shader templates did not report actual reuse')
            report['run']['validMeasurement'] = False
    if args.single_map_cell_render and 'singleMapCellRender=true' not in actual_log:
        report['run']['invalidReasons'].append('single map-cell render candidate is not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.reuse_typeface_keys and 'reuseTypefaceKeys=true' not in actual_log:
        report['run']['invalidReasons'].append('typeface key reuse candidate is not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.compact_quad_storage and 'quadStorageVertices=8' not in actual_log:
        report['run']['invalidReasons'].append('compact quad storage candidate is not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    if args.reuse_instanced_texture_shaders and 'reuseInstancedTextureShaders=true' not in actual_log:
        report['run']['invalidReasons'].append('instanced texture shader reuse candidate is not confirmed by the runtime')
        report['run']['validMeasurement'] = False
    confirm_experimental_modes(report['run'], actual_log, args.parallel_cell_raster,
                               args.primitive_text_metrics, args.adaptive_cell_raster, not args.cpu_cell_raster)
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
    if args.real_scene_oracle or args.real_scene_cell_oracle:
        oracle_path = output / 'real-scene-oracle' / 'summary.json'
        evidence = json.loads(oracle_path.read_text(encoding='utf-8')) if oracle_path.exists() else None
        report['realSceneCorrectnessEvidence'] = evidence
        if not evidence or not evidence.get('passed'):
            report['run']['invalidReasons'].append('live scene pixel oracle is incomplete or found incorrect pixels')
            report['run']['validMeasurement'] = False
    if args.normal_input_probe:
        import csv
        from analyze_input_response import analyze as analyze_input
        with (output / 'input-response.csv').open(encoding='utf-8') as stream:
            input_evidence = analyze_input(list(csv.DictReader(stream)), report['run']['windows'])
        write_json(output / 'input-response-summary.json', input_evidence)
        report['inputResponseEvidence'] = input_evidence
        if not input_evidence['validTrace']:
            report['run']['invalidReasons'].extend(input_evidence['invalidReasons'])
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
