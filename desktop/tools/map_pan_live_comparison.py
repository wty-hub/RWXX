"""ABBA comparison of frozen runtimes in an actual moving local game."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from map_pan_comparison import PROJECT, ORDER, summarize_comparison, write_json
from map_pan_builtin_comparison import add_camera_arguments, camera_protocol


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--fog', choices=('off', 'on'), default='off')
    p.add_argument('--units', type=int, choices=(500, 661, 1000, 2000), default=500)
    add_camera_arguments(p)
    p.add_argument('--window-width', type=int, default=1920)
    p.add_argument('--window-height', type=int, default=1080)
    p.add_argument('--baseline-disable-frozen-pixel-mesh-reuse', action='store_true')
    p.add_argument('--baseline-disable-pixel-pool', action='store_true')
    p.add_argument('--baseline-disable-upload-buffer-pool', action='store_true')
    p.add_argument('--baseline-disable-immutable-pixel-snapshot-reuse', action='store_true')
    p.add_argument('--baseline-disable-raster-scalar-mapping', action='store_true')
    p.add_argument('--baseline-disable-opaque-source-over-fast-path', action='store_true')
    p.add_argument('--baseline-disable-normal-fog-display-diff', action='store_true')
    p.add_argument('--baseline-disable-bulk-argb-pack', action='store_true')
    p.add_argument('--baseline-disable-native-bgra-upload', action='store_true')
    p.add_argument('--disable-native-bgra-upload', action='store_true', help='Use RGBA in both variants')
    p.add_argument('--baseline-legacy-owner-pacing', action='store_true')
    p.add_argument('--baseline-legacy-short-owner-park', action='store_true')
    p.add_argument('--legacy-short-owner-park', action='store_true', help='Use the version 13 hybrid pacing in both variants')
    p.add_argument('--guard-short-owner-park', action='store_true')
    p.add_argument('--baseline-disable-text-mesh-reuse', action='store_true')
    p.add_argument('--no-perf-window-log', action='store_true')
    p.add_argument('--canvas-stage-trace', action='store_true')
    p.add_argument('--parallel-cell-raster', action='store_true', help='Enable whole-cell raster stripes in both variants')
    p.add_argument('--candidate-parallel-cell-raster', action='store_true', help='Enable whole-cell raster stripes in candidate only')
    p.add_argument('--adaptive-cell-raster', action='store_true', help='Select costly texture cells in both variants; requires common parallel stripes')
    p.add_argument('--candidate-adaptive-cell-raster', action='store_true', help='Select costly texture cells in candidate only; requires candidate parallel stripes')
    p.add_argument('--gpu-map-cell-cache', action='store_true', help='Deprecated: GPU map targets are already the default in both variants')
    p.add_argument('--cpu-cell-raster', action='store_true', help='Explicit CPU control in both variants, or baseline only with --candidate-gpu-map-cell-cache')
    p.add_argument('--run-timeout-seconds', type=int, default=None,
                   help='Per-process wall-clock budget; fog-enabled runs are slower than real time and need more than the default')
    p.add_argument('--candidate-gpu-map-cell-cache', action='store_true', help='Enable Vulkan map cell rendering in candidate only')
    p.add_argument('--primitive-text-metrics', action='store_true', help='Enable primitive text metrics in both variants')
    p.add_argument('--candidate-primitive-text-metrics', action='store_true', help='Enable primitive text metrics in candidate only')
    p.add_argument('--baseline-raster-threads', type=int, choices=range(1, 9))
    p.add_argument('--candidate-raster-threads', type=int, choices=range(1, 9))
    p.add_argument('--baseline-layer-buffer-pixels', type=int, choices=(256, 384, 512))
    p.add_argument('--candidate-layer-buffer-pixels', type=int, choices=(256, 384, 512))
    args = p.parse_args()
    if args.cpu_cell_raster and args.gpu_map_cell_cache:
        p.error('--cpu-cell-raster is incompatible with common --gpu-map-cell-cache')
    if args.adaptive_cell_raster and not args.parallel_cell_raster:
        p.error('--adaptive-cell-raster requires --parallel-cell-raster in both variants')
    if args.candidate_adaptive_cell_raster and not (args.parallel_cell_raster or args.candidate_parallel_cell_raster):
        p.error('--candidate-adaptive-cell-raster requires common or candidate --parallel-cell-raster')
    try:
        camera = camera_protocol(args)
    except ValueError as error:
        p.error(str(error))
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    protocol = {**camera, 'units': args.units, 'mode': 'moving', 'teams': 15, 'fog': args.fog,
                'logicalWindowWidth': args.window_width, 'logicalWindowHeight': args.window_height,
                'order': ORDER, 'warmupSeconds': 20, 'sampleSeconds': 20, 'windowsPerProcess': 2,
                'rasterThreadsRequested': {'baseline': args.baseline_raster_threads, 'candidate': args.candidate_raster_threads},
                'parallelCellRasterRequested': {'baseline': args.parallel_cell_raster,
                    'candidate': args.parallel_cell_raster or args.candidate_parallel_cell_raster},
                'adaptiveCellRasterRequested': {'baseline': args.adaptive_cell_raster,
                    'candidate': args.adaptive_cell_raster or args.candidate_adaptive_cell_raster},
                'gpuMapCellCacheRequested': {
                    'baseline': args.gpu_map_cell_cache or (not args.cpu_cell_raster and not args.candidate_gpu_map_cell_cache),
                    'candidate': args.gpu_map_cell_cache or args.candidate_gpu_map_cell_cache or not args.cpu_cell_raster},
                'primitiveTextMetricsRequested': {'baseline': args.primitive_text_metrics,
                    'candidate': args.primitive_text_metrics or args.candidate_primitive_text_metrics}}
    runs = []
    for i, variant in enumerate(ORDER, 1):
        target = out / f'{i:02d}-{variant}'
        command = [sys.executable, str(PROJECT / 'desktop/tools/map_pan_live.py'), '--jar',
                        str(getattr(args, variant).resolve()), '--output', str(target), '--java', args.java,
                        '--fog', args.fog, '--units', str(args.units),
                        '--camera-period-seconds', str(args.camera_period_seconds),
                        '--camera-mode', args.camera_mode, '--zoom-mode', camera['zoomMode'],
                        '--zoom-period-seconds', str(args.zoom_period_seconds),
                        '--zoom-min', str(args.zoom_min), '--zoom-max', str(args.zoom_max), '--engine-trace',
                        '--window-width', str(args.window_width), '--window-height', str(args.window_height)]
        if variant == 'baseline' and args.baseline_disable_frozen_pixel_mesh_reuse:
            command.append('--disable-frozen-pixel-mesh-reuse')
        if variant == 'baseline' and args.baseline_disable_pixel_pool:
            command.append('--disable-pixel-pool')
        if variant == 'baseline' and args.baseline_disable_upload_buffer_pool:
            command.append('--disable-upload-buffer-pool')
        if variant == 'baseline' and args.baseline_disable_immutable_pixel_snapshot_reuse:
            command.append('--disable-immutable-pixel-snapshot-reuse')
        if variant == 'baseline' and args.baseline_disable_raster_scalar_mapping:
            command.append('--disable-raster-scalar-mapping')
        if variant == 'baseline' and args.baseline_disable_opaque_source_over_fast_path:
            command.append('--disable-opaque-source-over-fast-path')
        if variant == 'baseline' and args.baseline_disable_normal_fog_display_diff:
            command.append('--disable-normal-fog-display-diff')
        if variant == 'baseline' and args.baseline_disable_bulk_argb_pack:
            command.append('--disable-bulk-argb-pack')
        if args.disable_native_bgra_upload or (variant == 'baseline' and args.baseline_disable_native_bgra_upload):
            command.append('--disable-native-bgra-upload')
        if variant == 'baseline' and args.baseline_legacy_owner_pacing:
            command.append('--legacy-owner-pacing')
        if args.legacy_short_owner_park or (variant == 'baseline' and args.baseline_legacy_short_owner_park):
            command.append('--legacy-short-owner-park')
        if args.guard_short_owner_park:
            command.append('--guard-short-owner-park')
        if variant == 'baseline' and args.baseline_disable_text_mesh_reuse:
            command.append('--disable-text-mesh-reuse')
        if args.run_timeout_seconds is not None:
            command.extend(['--run-timeout-seconds', str(args.run_timeout_seconds)])
        if args.no_perf_window_log:
            command.append('--no-perf-window-log')
        if args.canvas_stage_trace:
            command.append('--canvas-stage-trace')
        if protocol['parallelCellRasterRequested'][variant]:
            command.append('--parallel-cell-raster')
        if protocol['adaptiveCellRasterRequested'][variant]:
            command.append('--adaptive-cell-raster')
        if protocol['gpuMapCellCacheRequested'][variant]:
            command.append('--gpu-map-cell-cache')
        else:
            command.append('--cpu-cell-raster')
        if protocol['primitiveTextMetricsRequested'][variant]:
            command.append('--primitive-text-metrics')
        raster_threads = getattr(args, variant + '_raster_threads')
        if raster_threads is not None:
            command.extend(['--raster-threads', str(raster_threads)])
        layer_buffer_pixels = getattr(args, variant + '_layer_buffer_pixels')
        if layer_buffer_pixels is not None:
            command.extend(['--layer-buffer-pixels', str(layer_buffer_pixels)])
        subprocess.run(command, check=True)
        run = json.loads((target / 'diagnostic-summary.json').read_text(encoding='utf-8'))['run']
        run['variant'] = variant; runs.append(run)
        write_json(out / 'summary.json', {'protocol': protocol, 'runs': runs})
    report = {'protocol': protocol, 'runs': runs, 'comparison': summarize_comparison(runs)}
    write_json(out / 'summary.json', report)
    print('COMPARISON ' + json.dumps(report['comparison']), flush=True)


if __name__ == '__main__':
    main()
