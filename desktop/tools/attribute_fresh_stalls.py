"""Attribute every fresh-frame gap to overlapping owner work and map-reload end markers."""
import argparse
import bisect
import csv
import json
from collections import Counter
from pathlib import Path
from analyze_clocked_live_gaps import ClockMap


def jfr_clock_map(events):
    """Calibrate both clocks across the run; a single epoch offset can drift on this machine."""
    try:
        clock = ClockMap(events)
    except ValueError:
        return None
    def convert(value):
        return clock.point(value)[0]
    convert.point = clock.point
    convert.diagnostics = clock.diagnostics()
    return convert


def render_thread_names(events):
    # Contexts can rename the backend thread; relying only on "main" loses its blocking events.
    names = {'main', 'kool-main-backend-thread'}
    for event in events:
        if event['type'] in ('jdk.ExecutionSample', 'jdk.NativeMethodSample') and any(
                marker in frame for frame in event.get('stack', []) for marker in
                ('Lwjgl3Context.renderFrame', 'RenderBackendVk.renderFrame', 'KoolCanvasFrameRenderer.render')):
            if event.get('thread'):
                names.add(event['thread'])
    return names


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    root = args.directory
    report = json.loads((root / 'diagnostic-summary.json').read_text(encoding='utf-8'))
    with (root / 'engine.csv').open(encoding='utf-8') as stream:
        owners = list(csv.DictReader(stream))
    ends = [int(row['frameEndNanos']) for row in owners]
    scenario = [json.loads(line) for line in (root / 'replay-pan-scenario.ndjson').read_text(encoding='utf-8').splitlines()]
    reloads = [row for row in scenario if row['kind'] == 'map-reload']
    stages = []
    for filename in ('canvas-stages.csv', 'vulkan-stages.csv'):
        path = root / filename
        if path.exists():
            with path.open(encoding='utf-8') as stream:
                for row in csv.DictReader(stream):
                    stages.append({**row, 'start': int(row['startNanos']), 'end': int(row['endNanos']),
                                   'source': filename})
    profile = root / 'compact-profile.ndjson'
    events = [json.loads(line) for line in profile.read_text(encoding='utf-8').splitlines()] if profile.exists() else []
    convert_clock = jfr_clock_map(events)
    if convert_clock is not None:
        events = [{**row, 'start': convert_clock(row['start']),
                   'duration': convert_clock(row['start'] + row['duration']) - convert_clock(row['start']),
                   'clockExtrapolated': convert_clock.point(row['start'])[1] or
                       convert_clock.point(row['start'] + row['duration'])[1]}
                  for row in events]
    render_threads = render_thread_names(events)
    submissions = {}
    current_submission = None
    for row in sorted((r for r in stages if r['source'] == 'vulkan-stages.csv'), key=lambda r: r['start']):
        if row['stage'] == 'submission-work':
            current_submission = int(row['value0'])
            submissions[current_submission] = {'ordinal': current_submission, 'atNanos': row['start'],
                'drawCommands': int(row['value1']), 'pipelineCount': int(row['value2'])}
        elif current_submission in submissions and row['stage'] == 'submission-summary':
            submissions[current_submission].update(bufferUploadBytes=int(row['value0']),
                textureUploadBytes=int(row['value1']), uploadCpuNanos=int(row['value2']))
    output = []
    for window in report['run']['windows']:
        for gap in window.get('freshSnapshotLongFrames', []):
            end = gap['acceptedPresentNanos']
            start = end - round(gap['intervalMs'] * 1e6)
            left = max(0, bisect.bisect_left(ends, start) - 1)
            right = min(len(owners), bisect.bisect_right(ends, end) + 1)
            rows = [row for row in owners[left:right] if int(row['frameStartNanos']) <= end and int(row['frameEndNanos']) >= start]
            fields = ('workNanos', 'updateNanos', 'drawNanos', 'layerRedrawNanos', 'snapshotNanos', 'replayNanos', 'callbackEntryGapNanos')
            costs = {field: max((int(row.get(field, 0)) for row in rows), default=0) / 1e6 for field in fields}
            overlapping = [row for row in stages if row['end'] >= start and row['start'] <= end]
            slowest = sorted(overlapping, key=lambda row: row['end'] - row['start'], reverse=True)[:12]
            sampled = [] if convert_clock is None else [row for row in events
                if row['start'] + row['duration'] >= start and row['start'] <= end]
            render_leaves = Counter(row['stack'][0] if row['stack'] else '?' for row in sampled
                if row['type'] in ('jdk.ExecutionSample', 'jdk.NativeMethodSample') and row['thread'] in render_threads)
            blocking = sorted([row for row in sampled if row['thread'] in render_threads or row['type'] == 'jdk.GCPhasePause'],
                              key=lambda row: row['duration'], reverse=True)[:6]
            output.append({**gap, 'startNanos': start, 'ownerFramesOverlapping': len(rows),
                           'maximumOwnerIntervalMs': max((int(row['intervalNanos']) for row in rows), default=0) / 1e6,
                           'maximumOwnerWorkMs': costs,
                           'reloadEndMarkersInside': [row for row in reloads if start <= row['atNanos'] <= end],
                           'slowestOverlappingStages': [{'stage': row['stage'], 'source': row['source'],
                               'durationMs': (row['end'] - row['start']) / 1e6,
                               **{key: row.get(key) for key in ('value0', 'value1', 'value2')},
                               'waitedSubmission': submissions.get(int(row.get('value2') or -1))
                                   if row['stage'] == 'fence' else None} for row in slowest],
                           'renderThreadSamples': render_leaves.most_common(8),
                           'renderThreadNames': sorted(render_threads),
                           'blockingEvents': [{**row, 'stack': row['stack'][:8]} for row in blocking if row['duration'] > 0],
                           'jfrClockSyncAvailable': convert_clock is not None,
                           'jfrClockCalibration': convert_clock.diagnostics if convert_clock is not None else None})
    output.sort(key=lambda row: row['intervalMs'], reverse=True)
    (root / 'fresh-stall-attribution.json').write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(output[:12], ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
