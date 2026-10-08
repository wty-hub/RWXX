"""Run the pan/zoom bench N times and report the median (interaction smoothness gate).

The static benchmark (`repeat_static_benchmark.py`) covers a still camera, which is how the original is
matched without taking over its mouse and keyboard. This driver covers the interaction case the user
actually feels: a triangular camera pan over `--camera-period-seconds` seconds, optionally with a zoom
cycle. It runs the same `desktop/tools/map_pan_replay.py` command per repetition and prints per-window
`newFps`, the interval percentiles and the >33/>50/>100 ms counts.
"""
import argparse
import json
import os
import statistics
import subprocess
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]

REPLAY = 'replays/2080年欧洲回归🌎结盟15p城夺4.0(15p) [v1.15] (4 Oct 2026 23.06.30).replay'
JAVA = r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot\bin\java.exe'

WINDOW_KEYS = ('newFps', 'freshSnapshotIntervalP95Ms', 'freshSnapshotIntervalP99Ms',
               'freshSnapshotIntervalMaxMs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--runs', type=int, default=4)
    parser.add_argument('--camera-mode', default='pan-zoom', choices=('static', 'pan', 'zoom', 'pan-zoom'))
    parser.add_argument('--camera-period-seconds', type=int, default=20)
    parser.add_argument('--zoom-period-seconds', type=int, default=4)
    parser.add_argument('--zoom-min', type=float, default=.35)
    parser.add_argument('--zoom-max', type=float, default=1.5)
    parser.add_argument('--fog', default='on', choices=('on', 'off'))
    parser.add_argument('--zoom-mode', default=None, choices=('cycle', 'none'))
    parser.add_argument('--map-cell-min-render-scale', type=float,
                        help='Experimental cache scale floor; changes both resolution and world coverage')
    parser.add_argument('--jar', type=Path, default=PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar')
    parser.add_argument('--skip-windows', type=int, default=0,
                        help='Leading per-run windows excluded from the aggregate as map-load/warm-up')
    parser.add_argument('--extra', nargs=argparse.REMAINDER, default=[])
    args = parser.parse_args()
    results = []
    for index in range(1, args.runs + 1):
        output = PROJECT / 'build' / 'rwx-benchmark' / f'{args.tag}-{index}'
        if output.exists():
            parser.error(f'output already exists: {output}; use a fresh --tag')
        else:
            zoom_mode = args.zoom_mode or ('cycle' if args.camera_mode in ('zoom', 'pan-zoom') else 'none')
            command = [sys.executable, str(PROJECT / 'desktop/tools/map_pan_replay.py'),
                       '--jar', str(args.jar), '--replay', REPLAY, '--java', JAVA,
                       '--output', str(output), '--camera-mode', args.camera_mode,
                       '--camera-period-seconds', str(args.camera_period_seconds),
                       '--zoom-period-seconds', str(args.zoom_period_seconds),
                       '--zoom-mode', zoom_mode,
                       '--zoom-min', str(args.zoom_min), '--zoom-max', str(args.zoom_max),
                       '--gpu-map-cell-cache', '--fog', args.fog, '--window-width', '1280',
                       '--window-height', '720',
                       '--no-perf-window-log', *args.extra]
            if args.map_cell_min_render_scale is not None:
                command += ['--map-cell-min-render-scale', str(args.map_cell_min_render_scale)]
            print(f'RUN {index}: {output.name}', flush=True)
            subprocess.run(command, check=False, env=dict(os.environ))
        summary_path = output / 'diagnostic-summary.json'
        def failed(error):
            # A run that hits map_pan_replay's 155 s watchdog writes no usable summary. That is a
            # measurement failure, not a result: record it and keep going instead of aborting the batch,
            # because otherwise a single slow run silently truncates the median. Every aggregated key must
            # still be present, or the median pass raises.
            print(f'  FAILED run {index}: {error}', flush=True)
            record = {'run': index, 'valid': False, 'error': str(error)}
            for key in WINDOW_KEYS:
                record[key] = []
            results.append(record)
        try:
            summary = json.loads(summary_path.read_text(encoding='utf-8'))
        except (OSError, ValueError) as error:
            failed(error)
            continue
        if 'run' not in summary:
            failed('summary has no run section')
            continue
        windows = summary['run']['windows']
        result = {'run': index, 'valid': summary['run']['validMeasurement'],
                  'diagnosticOnly': summary.get('diagnosticOnly', False),
                  'reasons': summary['run']['invalidReasons'],
                  'cameraMovementConfirmed': [w.get('cameraMovementConfirmed') for w in windows],
                  'zoomModeConfirmed': summary['run'].get('zoomModeConfirmed')}
        for key in WINDOW_KEYS:
            result[key] = [w.get(key) for w in windows]
        results.append(result)
        print(f'  valid={result["valid"]} reasons={result["reasons"]}'
              f' camera={result["cameraMovementConfirmed"]} zoom={result["zoomModeConfirmed"]}'
              f' newFps={[round(v, 1) for v in result["newFps"]]}'
              f' p99={[round(v, 1) for v in result["freshSnapshotIntervalP99Ms"]]}', flush=True)
    summary = {'tag': args.tag, 'cameraMode': args.camera_mode,
               'cameraPeriodSeconds': args.camera_period_seconds,
               'fog': args.fog, 'zoomMode': args.zoom_mode,
               'zoomMinimum': args.zoom_min, 'zoomMaximum': args.zoom_max,
               'zoomPeriodSeconds': args.zoom_period_seconds, 'runs': results}
    # The runtime starts windows after its explicit warmup. Exclude none by default; retain any
    # caller-requested exclusions in the report rather than silently treating long frames as loading.
    # An explicitly requested window exclusion is diagnostic, never formal acceptance evidence.
    def steady(values):
        if values is None:
            return []
        return [value for index, value in enumerate(values) if index >= args.skip_windows and value is not None]

    for key in WINDOW_KEYS:
        every = [value for result in results if result['valid'] for value in steady(result[key])]
        summary['median' + key[0].upper() + key[1:]] = statistics.median(every) if every else None
        summary['min' + key[0].upper() + key[1:]] = min(every) if every else None
        summary['max' + key[0].upper() + key[1:]] = max(every) if every else None
    warm = [value for result in results for value in (result['newFps'] or [])[:args.skip_windows]
            if value is not None]
    summary['warmupMedianNewFps'] = statistics.median(warm) if warm else None
    summary['skippedWarmupWindowsPerRun'] = args.skip_windows
    summary['diagnosticOnly'] = args.skip_windows > 0 or any(result.get('diagnosticOnly') for result in results)
    summary['failedRuns'] = [result['run'] for result in results if not result.get('valid')]
    summary['measuredWindows'] = sum(len(result.get('newFps') or []) for result in results)
    summary['steadyWindows'] = sum(
        max(0, len(result.get('newFps') or []) - args.skip_windows) for result in results)
    (PROJECT / 'build' / 'rwx-benchmark' / f'{args.tag}-summary.json').write_text(
        json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
    print('MEDIAN ' + json.dumps({key: round(value, 2) for key, value in summary.items()
                                  if key.startswith('median') and value is not None}), flush=True)
    print(f'WINDOWS {summary["measuredWindows"]} FAILED {summary["failedRuns"]}', flush=True)
    return 1 if summary['failedRuns'] or not summary['steadyWindows'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
