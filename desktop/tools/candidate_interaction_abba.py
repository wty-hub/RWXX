"""Screen one Vulkan candidate in A/B/B/A order with a frozen runtime and fresh outputs.

This retains all events and is candidate screening, never an original comparison or final acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys

from map_pan_comparison import PROJECT, machine_state
from pinned_interaction_comparison import JAVA, REPLAY, normalize_rwx, write
from frozen_benchmark_tooling import freeze_tooling, frozen_environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--jar', type=Path, default=PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar')
    parser.add_argument('--replay', type=Path, default=PROJECT / REPLAY)
    parser.add_argument('--java', default=JAVA)
    parser.add_argument('--mode', choices=('static', 'pan', 'zoom', 'pan-zoom'), default='pan-zoom')
    parser.add_argument('--fog', choices=('off', 'on'), default='off')
    parser.add_argument('--sample-seconds', type=int, default=40)
    parser.add_argument('--warmup-seconds', type=int, default=20)
    parser.add_argument('--base-flag', action='append', default=[], help='Common runner flag, e.g. --base-flag=--map-texture-batches')
    parser.add_argument('--candidate-flag', action='append', required=True, help='Candidate runner flag, e.g. --candidate-flag=--single-map-cell-render')
    args = parser.parse_args()
    if min(args.sample_seconds, args.warmup_seconds) < 1:
        parser.error('durations must be positive')
    output = PROJECT / 'build/rwx-benchmark' / args.tag
    output.mkdir(parents=True, exist_ok=False)
    tooling, tooling_digest = freeze_tooling(PROJECT, output)
    environment = frozen_environment(PROJECT, tooling, tooling_digest, os.environ)
    runtime = output / 'runtime.jar'
    replay = output / 'frozen.replay'
    shutil.copy2(args.jar, runtime)
    shutil.copy2(args.replay, replay)
    report = {'candidateScreeningOnly': True, 'overallAcceptance': False, 'machine': machine_state(),
              'runtimeSha256': hashlib.sha256(runtime.read_bytes()).hexdigest(),
              'replaySha256': hashlib.sha256(replay.read_bytes()).hexdigest(),
              'toolingSha256': tooling_digest,
              'baseFlags': args.base_flag, 'candidateFlags': args.candidate_flag, 'records': []}
    write(output / 'report.json', report)
    for index, arm in enumerate(('control', 'candidate', 'candidate', 'control'), 1):
        destination = output / f'{index:02d}-{arm}'
        command = [sys.executable, str(tooling / 'map_pan_replay.py'),
                   '--jar', str(runtime), '--replay', str(replay), '--java', args.java,
                   '--output', str(destination), '--repetitions', '1', '--camera-mode', args.mode,
                   '--fog', args.fog, '--window-width', '1280', '--window-height', '720',
                   '--warmup-seconds', str(args.warmup_seconds), '--sample-seconds', str(args.sample_seconds),
                   '--no-perf-window-log', *args.base_flag,
                   *(args.candidate_flag if arm == 'candidate' else [])]
        print(f'RUN {index} {arm}', flush=True)
        result = subprocess.run(command, cwd=PROJECT, env=environment, check=False)
        record = {'arm': arm, 'output': str(destination), 'exitCode': result.returncode, 'valid': False}
        try:
            summary = json.loads((destination / 'diagnostic-summary.json').read_text(encoding='utf-8'))
            metrics = normalize_rwx(summary)
            if metrics['runtimeSha256'] != report['runtimeSha256'] or metrics['replaySha256'] != report['replaySha256']:
                raise ValueError('runtime or replay fingerprint changed')
            if metrics['toolingSha256'] != tooling_digest:
                raise ValueError('measurement tooling fingerprint changed')
            record.update(valid=True, metrics=metrics)
        except Exception as failure:
            record['failure'] = str(failure)
        report['records'].append(record)
        write(output / 'report.json', report)
    report['completeScreening'] = all(r['valid'] for r in report['records'])
    if report['completeScreening']:
        keys = ('newFps', 'p99Ms', 'p999Ms', 'over16_667PerSecond')
        report['medianWindowMetrics'] = {arm: {key: statistics.median(r['metrics'][key]
            for r in report['records'] if r['arm'] == arm) for key in keys} for arm in ('control', 'candidate')}
        report['maximumIntervalMs'] = {arm: max(r['metrics']['maxMs'] for r in report['records']
            if r['arm'] == arm) for arm in ('control', 'candidate')}
    write(output / 'report.json', report)
    print(json.dumps({key: value for key, value in report.items() if key in
                     ('completeScreening', 'medianWindowMetrics', 'maximumIntervalMs')}, indent=2), flush=True)
    return 0 if report['completeScreening'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
