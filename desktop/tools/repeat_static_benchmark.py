"""Run the static radar benchmark N times and report the median, matching the §6.1 acceptance gate.

One run of the static europe-15p replay is bimodal on this machine (measured 125/s and 217/s from the
identical jar), so a single run cannot gate anything. This driver launches the same command
`desktop/tools/map_pan_replay.py` for each repetition, and prints
the median of the per-window `newFps`/`freshSnapshotIntervalP99Ms` values together with every run.
"""
import argparse
import json
import os
import statistics
import subprocess
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / 'desktop' / 'tools'))

REPLAY = 'replays/2080年欧洲回归🌎结盟15p城夺4.0(15p) [v1.15] (4 Oct 2026 23.06.30).replay'
JAVA = r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot\bin\java.exe'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--runs', type=int, default=4)
    parser.add_argument('--jar', type=Path, default=PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar')
    parser.add_argument('--extra', nargs=argparse.REMAINDER, default=[])
    parser.add_argument('--env', nargs='*', default=[], help='Extra KEY=VALUE environment entries for each run')
    parser.add_argument('--legacy-text-vertex-allocation', action='store_true',
                        help='Controlled A/B: restore the pre-optimisation per-vertex text allocation')
    args = parser.parse_args()
    results = []
    for index in range(1, args.runs + 1):
        output = PROJECT / 'build' / 'rwx-benchmark' / f'{args.tag}-{index}'
        if output.exists():
            parser.error(f'output already exists: {output}; use a fresh --tag')
        command = [sys.executable, str(PROJECT / 'desktop/tools/map_pan_replay.py'),
                   '--jar', str(args.jar), '--replay', REPLAY, '--java', JAVA,
                   '--output', str(output), '--camera-mode', 'static', '--gpu-map-cell-cache',
                   '--fog', 'on', '--window-width', '1280', '--window-height', '720',
                   '--no-perf-window-log', *args.extra]
        if args.legacy_text_vertex_allocation:
            command.append('--legacy-text-vertex-allocation')
        print(f'RUN {index}: {output.name}', flush=True)
        environment = dict(os.environ)
        for entry in args.env:
            key, _, value = entry.partition('=')
            environment[key] = value
        subprocess.run(command, check=False, env=environment)
        summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
        windows = summary['run']['windows']
        fps = [window['newFps'] for window in windows]
        p99 = [window['freshSnapshotIntervalP99Ms'] for window in windows]
        p95 = [window['freshSnapshotIntervalP95Ms'] for window in windows]
        results.append({'run': index, 'valid': summary['run']['validMeasurement'],
                        'diagnosticOnly': summary.get('diagnosticOnly', False),
                        'reasons': summary['run']['invalidReasons'],
                        'newFps': fps, 'p99Ms': p99, 'p95Ms': p95})
        print(f'  valid={results[-1]["valid"]} newFps={[round(v, 1) for v in fps]} '
              f'p99={[round(v, 2) for v in p99]}', flush=True)
    if not results:
        print('NO RUNS', flush=True)
        return 1
    usable = [result for result in results if result['valid']]
    if not usable:
        print('NO VALID RUNS', flush=True)
        return 1
    every_fps = [value for result in usable for value in result['newFps']]
    every_p99 = [value for result in usable for value in result['p99Ms']]
    every_p95 = [value for result in usable for value in result['p95Ms']]
    summary = {'tag': args.tag, 'runs': results,
               'medianNewFps': statistics.median(every_fps),
               'medianP99Ms': statistics.median(every_p99),
               'medianP95Ms': statistics.median(every_p95),
               'minNewFps': min(every_fps), 'maxNewFps': max(every_fps)}
    (PROJECT / 'build' / 'rwx-benchmark' / f'{args.tag}-summary.json').write_text(
        json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
    print('MEDIAN ' + json.dumps({'medianNewFps': round(summary['medianNewFps'], 1),
                                  'medianP99Ms': round(summary['medianP99Ms'], 2),
                                  'medianP95Ms': round(summary['medianP95Ms'], 2),
                                  'min': round(summary['minNewFps'], 1),
                                  'max': round(summary['maxNewFps'], 1)}), flush=True)
    return 0 if len(usable) == args.runs else 1


if __name__ == '__main__':
    raise SystemExit(main())
