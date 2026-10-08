"""Locate full render allocation stacks inside fresh gaps; samples never prove call duration.

Use after (never during) formal measurement. Writes a new file and preserves its input evidence.
"""
import argparse
import collections
import csv
import json
from pathlib import Path

from analyze_clocked_live_gaps import ClockMap


def allocation_role(stack):
    if any('VulkanUploadState.allocate' in frame for frame in stack):
        return 'persistent-upload-slot'
    if any('BindGroupDataVk$UboBinding' in frame for frame in stack):
        return 'uniform-binding-buffer'
    if any('GrowingBufferVk' in frame for frame in stack):
        return 'geometry-growing-buffer'
    if any('MemoryManager' in frame for frame in stack):
        return 'other-native-buffer'
    return 'mapped-memory-copy-or-other'


def analyze(root):
    profile = root / 'compact-profile.ndjson'
    clocks, samples = [], []
    with profile.open(encoding='utf-8') as stream:
        for line in stream:
            if 'rwx.ClockSync' not in line and 'vmaCreateBuffer' not in line and 'setMemory' not in line:
                continue
            event = json.loads(line)
            if event['type'] == 'rwx.ClockSync':
                clocks.append(event)
            elif event['type'] in ('jdk.ExecutionSample', 'jdk.NativeMethodSample'):
                samples.append(event)
    clock = ClockMap(clocks)
    report = json.loads((root / 'diagnostic-summary.json').read_text(encoding='utf-8'))
    windows = report['run']['windows']
    gaps = []
    for window in windows:
        for gap in window.get('freshSnapshotLongFrames', []):
            end = gap['acceptedPresentNanos']
            gaps.append({**gap, 'startNanos': end - round(gap['intervalMs'] * 1e6), 'samples': []})
    stages = []
    path = root / 'vulkan-stages.csv'
    if path.exists():
        with path.open(encoding='utf-8') as stream:
            for row in csv.DictReader(stream):
                stages.append({**row, 'startNanos': int(row['startNanos']), 'endNanos': int(row['endNanos'])})
    counts = collections.Counter()
    for sample in samples:
        mapped = clock.event(sample)
        timestamp = mapped['startNanos']
        role = allocation_role(sample.get('stack', []))
        inside = any(window['sampleStartNanos'] <= timestamp <= window['sampleEndNanos'] for window in windows)
        counts[('measurement' if inside else 'outside-measurement', sample.get('thread'), role)] += 1
        for gap in gaps:
            if not gap['startNanos'] <= timestamp <= gap['acceptedPresentNanos']:
                continue
            covering = [row for row in stages if row['startNanos'] <= timestamp <= row['endNanos']]
            mapped['role'] = role
            mapped['coveringStages'] = [{**row, 'durationMs': (row['endNanos'] - row['startNanos']) / 1e6}
                for row in covering]
            gap['samples'].append(mapped.copy())
    gaps.sort(key=lambda row: row['intervalMs'], reverse=True)
    return {'diagnosticOnly': True, 'sourceDirectory': str(root.resolve()),
        'interpretation': 'A sample proves the thread was in this stack at one instant. Surrounding stage time is not allocation duration. Zero-duration samples cannot be called blocking events.',
        'clockCalibration': clock.diagnostics(),
        'sampleCounts': [{'window': window, 'thread': thread, 'role': role, 'samples': count}
                         for (window, thread, role), count in sorted(counts.items(), key=lambda pair: str(pair[0]))],
        'freshGaps': gaps}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.directory)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'sampleCounts': result['sampleCounts'],
        'gapsWithSamples': [{'intervalMs': row['intervalMs'], 'roles': [s['role'] for s in row['samples']]}
                           for row in result['freshGaps'] if row['samples']]}, indent=2))


if __name__ == '__main__':
    main()
