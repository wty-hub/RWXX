"""ABBA comparison of frozen runtimes in an actual moving 500-unit local game."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from map_pan_comparison import PROJECT, ORDER, summarize_comparison, write_json


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--fog', choices=('off', 'on'), default='off')
    p.add_argument('--camera-period-seconds', type=int, default=20)
    p.add_argument('--camera-mode', choices=('pan', 'jump'), default='pan')
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
    p.add_argument('--baseline-raster-threads', type=int, choices=(1, 2, 4))
    p.add_argument('--candidate-raster-threads', type=int, choices=(1, 2, 4))
    p.add_argument('--baseline-layer-buffer-pixels', type=int, choices=(256, 384, 512))
    p.add_argument('--candidate-layer-buffer-pixels', type=int, choices=(256, 384, 512))
    args = p.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    runs = []
    for i, variant in enumerate(ORDER, 1):
        target = out / f'{i:02d}-{variant}'
        command = [sys.executable, str(PROJECT / 'desktop/tools/map_pan_live.py'), '--jar',
                        str(getattr(args, variant).resolve()), '--output', str(target), '--java', args.java,
                        '--fog', args.fog, '--camera-period-seconds', str(args.camera_period_seconds),
                        '--camera-mode', args.camera_mode, '--engine-trace',
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
        if args.no_perf_window_log:
            command.append('--no-perf-window-log')
        if args.canvas_stage_trace:
            command.append('--canvas-stage-trace')
        raster_threads = getattr(args, variant + '_raster_threads')
        if raster_threads is not None:
            command.extend(['--raster-threads', str(raster_threads)])
        layer_buffer_pixels = getattr(args, variant + '_layer_buffer_pixels')
        if layer_buffer_pixels is not None:
            command.extend(['--layer-buffer-pixels', str(layer_buffer_pixels)])
        subprocess.run(command, check=True)
        run = json.loads((target / 'diagnostic-summary.json').read_text(encoding='utf-8'))['run']
        run['variant'] = variant; runs.append(run)
        write_json(out / 'summary.json', {'runs': runs})
    report = {'runs': runs, 'comparison': summarize_comparison(runs)}
    write_json(out / 'summary.json', report)
    print('COMPARISON ' + json.dumps(report['comparison']), flush=True)


if __name__ == '__main__':
    main()
