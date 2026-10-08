"""Freeze and compare normal callback-to-picture input response separately from performance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from compare_input_responses import compare
from frozen_benchmark_tooling import freeze_tooling, frozen_environment
from map_pan_comparison import PROJECT, machine_state
from pinned_interaction_comparison import JAVA, REPLAY, write


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--jar', type=Path, default=PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar')
    parser.add_argument('--replay', type=Path, default=PROJECT / REPLAY)
    parser.add_argument('--warmup-seconds', type=int, default=20)
    parser.add_argument('--sample-seconds', type=int, default=40)
    parser.add_argument('--extra', nargs=argparse.REMAINDER, default=[])
    args = parser.parse_args()
    if min(args.warmup_seconds, args.sample_seconds) < 1:
        parser.error('durations must be positive')
    output = PROJECT / 'build/rwx-benchmark' / args.tag
    output.mkdir(parents=True, exist_ok=False)
    tooling, digest = freeze_tooling(PROJECT, output)
    environment = frozen_environment(PROJECT, tooling, digest, os.environ)
    runtime, replay = output / 'runtime.jar', output / 'frozen.replay'
    shutil.copy2(args.jar, runtime)
    shutil.copy2(args.replay, replay)
    report = {'overallAcceptance': False, 'diagnosticOnly': True, 'machine': machine_state(),
              'toolingSha256': digest, 'runtimeSha256': hashlib.sha256(runtime.read_bytes()).hexdigest(),
              'replaySha256': hashlib.sha256(replay.read_bytes()).hexdigest(), 'records': []}
    write(output / 'report.json', report)
    responses = {}
    for arm in ('original', 'rwx'):
        destination = output / arm
        command = [sys.executable, str(tooling / ('original_interaction_benchmark.py' if arm == 'original' else 'map_pan_replay.py')),
                   '--output', str(destination), '--replay', str(replay), '--camera-mode', 'static',
                   '--warmup-seconds', str(args.warmup_seconds), '--sample-seconds', str(args.sample_seconds), '--normal-input-probe']
        if arm == 'rwx':
            command += ['--jar', str(runtime), '--java', JAVA, '--repetitions', '1', '--fog', 'off',
                        '--window-width', '1280', '--window-height', '720', '--no-perf-window-log',
                        '--input-response-trace', *args.extra]
        print(f'INPUT {arm}', flush=True)
        result = subprocess.run(command, cwd=PROJECT, env=environment, check=False)
        record = {'arm': arm, 'output': str(destination), 'exitCode': result.returncode, 'valid': False}
        try:
            summary = json.loads((destination / ('summary.json' if arm == 'original' else 'diagnostic-summary.json')).read_text(encoding='utf-8'))
            if summary['toolingSha256'] != digest or summary['replaySha256'] != report['replaySha256']:
                raise ValueError('measurement tooling or replay fingerprint changed')
            if arm == 'rwx' and summary['runtimeSha256'] != report['runtimeSha256']:
                raise ValueError('RWX runtime fingerprint changed')
            if result.returncode or (arm == 'rwx' and not summary['run']['validMeasurement']):
                raise ValueError(f'run failed validation: {summary.get("run", {}).get("invalidReasons", [])}')
            response = json.loads((destination / 'input-response-summary.json').read_text(encoding='utf-8'))
            responses[arm] = response
            record.update(valid=response['validTrace'], response=response)
        except (ValueError, OSError, KeyError) as failure:
            record['failure'] = str(failure)
        report['records'].append(record)
        write(output / 'report.json', report)
    report['completeComparison'] = all(record['valid'] for record in report['records'])
    if report['completeComparison']:
        report['comparison'] = compare(responses['original'], responses['rwx'])
    write(output / 'report.json', report)
    print(json.dumps({key: value for key, value in report.items() if key in ('completeComparison', 'comparison')}, indent=2), flush=True)
    return 0 if report['completeComparison'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
