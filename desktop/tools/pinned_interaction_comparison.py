"""Interleave the pinned original and RWX at 1280x720, retaining every measurement window.

This gates measured performance only. Correctness, ten-minute continuity and normal-input
response require separate evidence; passing this report never declares the overall task done.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys

from map_pan_comparison import PROJECT, machine_state
from frozen_benchmark_tooling import freeze_tooling, frozen_environment

MODES = ('static', 'pan', 'zoom', 'pan-zoom')
JAVA = r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot\bin\java.exe'
REPLAY = 'replays/2080年欧洲回归🌎结盟15p城夺4.0(15p) [v1.15] (4 Oct 2026 23.06.30).replay'


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def normalize_original(summary):
    if summary.get('diagnosticOnly'):
        raise ValueError('diagnostic original run cannot enter a formal comparison')
    seconds = summary['sampleSeconds']
    return {'newFps': summary['newFps'],
            'p99Ms': summary['freshSnapshotIntervalP99Ms'],
            'p999Ms': summary['freshSnapshotIntervalP999Ms'],
            'maxMs': summary['freshSnapshotIntervalMaxMs'],
            'over16_667PerSecond': summary['intervalsOver16.667Ms'] / seconds,
            'longFrames': summary['longFrames'], 'sampleSeconds': seconds,
            'runtimeSha256': summary['runtimeSha256'], 'replaySha256': summary['replaySha256'],
            'configurationSha256': summary['configurationSha256'], 'toolingSha256': summary.get('toolingSha256'), 'fogDisplayEnabled': summary['fogDisplayEnabled'],
            'simulationEvidence': summary['simulationEvidence']}


def normalize_rwx(summary):
    run = summary['run']
    if summary.get('diagnosticOnly') or not run['validMeasurement']:
        raise ValueError('diagnostic or invalid RWX run cannot enter a formal comparison')
    windows = run['windows']
    if len(windows) != 1:
        raise ValueError('the paired protocol requires one continuous window per process')
    window = windows[0]
    seconds = summary['measurementProtocol']['sampleSeconds']
    return {'newFps': window['newFps'], 'p99Ms': window['freshSnapshotIntervalP99Ms'],
            'p999Ms': window['freshSnapshotIntervalP999Ms'], 'maxMs': window['freshSnapshotIntervalMaxMs'],
            'over16_667PerSecond': window['freshSnapshotIntervalsOver16_667Ms'] / seconds,
            'longFrames': window['freshSnapshotLongFrames'], 'sampleSeconds': seconds,
            'runtimeSha256': summary['runtimeSha256'], 'replaySha256': summary['replaySha256'],
            'configurationSha256': summary['configurationSha256'], 'toolingSha256': summary.get('toolingSha256'), 'fogDisplayEnabled': run['setup']['fogDisplayEnabled'],
            'simulationEvidence': {'initialTick': run['setup']['initialTick'],
                'initialGameTimeMillis': run['setup']['initialGameTimeMillis'],
                **{key: window['sample' + key[0].upper() + key[1:]]
                    for key in ('startTick', 'endTick', 'startGameTimeMillis', 'endGameTimeMillis')}}}


def assess(records, runs, modes):
    comparisons = {}
    for mode in modes:
        arms = {arm: [r['metrics'] for r in records if r.get('valid') and r['mode'] == mode and r['arm'] == arm]
                for arm in ('original', 'rwx')}
        if any(len(values) != runs for values in arms.values()):
            comparisons[mode] = {'validComparison': False, 'reason': 'incomplete paired runs'}
            continue
        hashes = {r['replaySha256'] for values in arms.values() for r in values}
        if len(hashes) != 1 or any(len({r['runtimeSha256'] for r in values}) != 1 for values in arms.values()):
            comparisons[mode] = {'validComparison': False, 'reason': 'replay or runtime fingerprints differ'}
            continue
        tooling = {r.get('toolingSha256') for values in arms.values() for r in values}
        if None in tooling or len(tooling) != 1:
            comparisons[mode] = {'validComparison': False, 'reason': 'measurement tooling fingerprint missing or changed'}
            continue
        if len({r.get('fogDisplayEnabled') for values in arms.values() for r in values}) != 1:
            comparisons[mode] = {'validComparison': False, 'reason': 'fog display states differ'}
            continue
        skews = []
        for original, rwx in zip(arms['original'], arms['rwx']):
            left, right = original.get('simulationEvidence'), rwx.get('simulationEvidence')
            if left is None or right is None:
                skews.append(None)
                continue
            skews.append({key: abs(left[key] - right[key]) for key in
                ('initialGameTimeMillis', 'startGameTimeMillis', 'endGameTimeMillis')})
        if any(value is None or max(value.values()) > 1000 for value in skews):
            comparisons[mode] = {'validComparison': False, 'reason': 'simulation intervals missing or differ by more than one second',
                'simulationSkewMillis': skews}
            continue
        medians = {arm: {key: statistics.median(r[key] for r in values)
                         for key in ('newFps', 'p99Ms', 'p999Ms', 'over16_667PerSecond')}
                   for arm, values in arms.items()}
        original, rwx = medians['original'], medians['rwx']
        comparisons[mode] = {'validComparison': True, 'pairs': runs, 'simulationSkewMillis': skews, 'medianWindowMetrics': medians,
            'maximumIntervalMs': {arm: max(r['maxMs'] for r in values) for arm, values in arms.items()},
            'fpsRatio': rwx['newFps'] / original['newFps'],
            'measuredPerformancePassed': rwx['newFps'] >= original['newFps'] and rwx['p99Ms'] <= original['p99Ms']
                and rwx['over16_667PerSecond'] <= original['over16_667PerSecond'],
            'continuousWindowPassed': all(r['sampleSeconds'] >= 600 and not r['longFrames'] for r in arms['rwx'])}
    return comparisons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--runs', type=int, default=7)
    parser.add_argument('--modes', nargs='+', choices=MODES, default=list(MODES))
    parser.add_argument('--sample-seconds', type=int, default=40)
    parser.add_argument('--warmup-seconds', type=int, default=20)
    parser.add_argument('--original', action='store_true', help='Measure only original (no comparison or acceptance claim)')
    parser.add_argument('--fog', choices=('on', 'off'), default='off', help='Reject comparison if original fog display differs')
    parser.add_argument('--jar', type=Path, default=PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar')
    parser.add_argument('--replay', type=Path, default=PROJECT / REPLAY)
    parser.add_argument('--extra', nargs=argparse.REMAINDER, default=[])
    args = parser.parse_args()
    if min(args.runs, args.sample_seconds, args.warmup_seconds) < 1:
        parser.error('runs and durations must be positive')
    output = PROJECT / 'build/rwx-benchmark' / args.tag
    output.mkdir(parents=True, exist_ok=False)
    tooling, tooling_digest = freeze_tooling(PROJECT, output)
    environment = frozen_environment(PROJECT, tooling, tooling_digest, os.environ)
    frozen = output / 'rwx-runtime.jar'
    if not args.original:
        import shutil
        shutil.copy2(args.jar, frozen)
    report = {'machine': machine_state(), 'toolingSha256': tooling_digest, 'runsRequested': args.runs, 'sampleSeconds': args.sample_seconds,
              'records': [], 'overallAcceptance': False,
              'remainingGates': ['real map correctness', 'normal input response', 'each interaction mode 600 seconds with zero >16.667 ms intervals']}
    write(output / 'report.json', report)
    for pair in range(1, args.runs + 1):
        for mode in args.modes:
            order = ('original',) if args.original else (('original', 'rwx') if pair % 2 else ('rwx', 'original'))
            for arm in order:
                destination = output / f'{pair:02d}-{mode}-{arm}'
                command = [sys.executable, str(tooling /
                    ('original_interaction_benchmark.py' if arm == 'original' else 'map_pan_replay.py')),
                    '--output', str(destination), '--replay', str(args.replay), '--camera-mode', mode,
                    '--warmup-seconds', str(args.warmup_seconds), '--sample-seconds', str(args.sample_seconds)]
                if arm == 'rwx':
                    command += ['--jar', str(frozen), '--java', JAVA, '--repetitions', '1',
                        '--camera-period-seconds', '20', '--zoom-period-seconds', '4', '--zoom-min', '.35', '--zoom-max', '1.5',
                        '--gpu-map-cell-cache', '--fog', args.fog, '--window-width', '1280', '--window-height', '720',
                        '--no-perf-window-log', *args.extra]
                print(f'PAIR {pair} {mode} {arm}', flush=True)
                record = {'pair': pair, 'mode': mode, 'arm': arm, 'output': str(destination), 'valid': False}
                try:
                    subprocess.run(command, cwd=PROJECT, env=environment, check=True)
                    raw = json.loads((destination / ('summary.json' if arm == 'original' else 'diagnostic-summary.json')).read_text(encoding='utf-8'))
                    metrics = normalize_original(raw) if arm == 'original' else normalize_rwx(raw)
                    if arm == 'rwx' and metrics['runtimeSha256'] != hashlib.sha256(frozen.read_bytes()).hexdigest():
                        raise ValueError('RWX runtime fingerprint changed')
                    record.update(valid=True, metrics=metrics)
                except (subprocess.CalledProcessError, ValueError, OSError, KeyError) as error:
                    record['error'] = str(error)
                report['records'].append(record)
                report['comparisons'] = assess(report['records'], args.runs, args.modes)
                report['sevenPairPerformanceGatePassed'] = args.runs >= 7 and set(args.modes) == set(MODES) and all(
                    value.get('measuredPerformancePassed', False) for value in report['comparisons'].values())
                write(output / 'report.json', report)
    print(json.dumps(report['comparisons'], ensure_ascii=True, indent=2), flush=True)
    return 0 if all(record['valid'] for record in report['records']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
