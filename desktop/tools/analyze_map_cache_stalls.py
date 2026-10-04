"""Align completed map-cache events with engine frames and fresh presentation gaps."""
import argparse
import bisect
import csv
import json
from pathlib import Path
from map_pan_comparison import read_trace


def analyze(directory):
    report = json.loads((directory / 'diagnostic-summary.json').read_text(encoding='utf-8'))
    run = report['run']
    if not run['validMeasurement']:
        raise ValueError('Invalid measurement')
    windows = run['windows']
    setup = next(json.loads(line) for line in (directory / f"{run['name']}-scenario.ndjson").open()
                 if json.loads(line).get('kind') == 'scenario')
    with (directory / 'engine.csv').open() as stream:
        frames = [{k: float(v) if k in ('cameraX', 'cameraY', 'zoom') else int(v)
                   for k, v in r.items()} for r in csv.DictReader(stream)]
    with (directory / 'map-cache.csv').open() as stream:
        events = [{k: v if k in ('event', 'axis') else float(v) if k in
                   ('cameraX', 'cameraY', 'scale', 'visibleWorldWidth', 'visibleWorldHeight') else int(v)
                   for k, v in r.items()} for r in csv.DictReader(stream)]
    ends = [f['frameEndNanos'] for f in frames]
    if ends != sorted(ends):
        raise ValueError('Engine frames out of order')
    per_frame = [[] for _ in frames]
    unmatched = []
    for event in events:
        i = bisect.bisect_left(ends, event['endNanos'])
        if i < len(frames) and frames[i]['frameStartNanos'] <= event['startNanos']:
            per_frame[i].append(event)
        else:
            unmatched.append(event)
    def inside(t):
        return any(w['sampleStartNanos'] <= t <= w['sampleEndNanos'] for w in windows)
    def frame_summary(i):
        f, ev = frames[i], per_frame[i]
        cells = [e for e in ev if e['event'] == 'cell']
        return {**f, 'secondsFromScenario': (f['frameEndNanos'] - setup['startedAtNanos']) / 1e9,
                'cacheCellCount': len(cells), 'cacheWallMs': sum(e['endNanos'] - e['startNanos'] for e in cells) / 1e6,
                'offscreenCacheCellCount': sum(e['visibleIntersection'] == 0 for e in cells),
                'cacheCpuMs': sum(e['cpuNanos'] for e in cells if e['cpuNanos'] >= 0) / 1e6,
                'cacheCpuAvailable': all(e['cpuNanos'] >= 0 for e in cells),
                'gridEvents': [e for e in ev if e['event'] != 'cell'],
                'cells': cells}
    measured = [i for i, f in enumerate(frames) if inside(f['frameEndNanos'])]
    long = [frame_summary(i) for i in measured if frames[i]['drawNanos'] >= 33_333_333]
    trace = read_trace(directory / f"{run['name']}-trace.csv")
    fresh = [r for i, r in enumerate(trace) if i == 0 or r[1:] != trace[i - 1][1:]]
    gaps = []
    for a, b in zip(fresh, fresh[1:]):
        if b[0] - a[0] <= 50_000_000 or not inside(b[0]):
            continue
        first, last = bisect.bisect_right(ends, a[0]), bisect.bisect_right(ends, b[0])
        # Include a frame still in progress at the gap's end.
        if last < len(frames) and frames[last]['frameStartNanos'] < b[0]:
            last += 1
        work = [frame_summary(i) for i in range(first, last)]
        gaps.append({'startNanos': a[0], 'endNanos': b[0], 'durationMs': (b[0] - a[0]) / 1e6,
                     'secondsFromScenario': (b[0] - setup['startedAtNanos']) / 1e9,
                     'overlappingCacheCells': [e for e in events if e['event'] == 'cell'
                                               and e['startNanos'] < b[0] and e['endNanos'] > a[0]],
                     'longestEngineFrame': max(work, key=lambda f: f['workNanos'], default=None)})
    cells = [e for e in events if e['event'] == 'cell' and inside(e['endNanos'])]
    post_publish = [e for e in unmatched if e['event'] == 'cell' and inside(e['endNanos'])]
    return {'source': str(directory), 'summary': {'measuredEngineFrames': len(measured),
            'cacheCells': len(cells), 'offscreenCacheCells': sum(e['visibleIntersection'] == 0 for e in cells),
            'ownerDrawFramesOver33ms': len(long), 'freshGapsOver50ms': len(gaps),
            'ownerDrawFramesOver33msWithCacheRedraw': sum(f['cacheCellCount'] > 0 for f in long),
            'unmatchedEvents': len(unmatched), 'postPublishCacheCells': len(post_publish)},
            'longEngineFrames': long, 'freshGaps': gaps, 'postPublishCacheCells': post_publish,
            'definitions': {'offscreen': 'No integer rectangle intersection with the viewport at cell entry.',
                            'cpuTime': 'Thread CPU time; Windows may quantize it to about 15.625ms.',
                            'cacheWall': 'Cell rendering includes CPU rasterization and target copies; excludes trace writing.',
                            'postPublish': 'Prefetch after publication lies outside the engine.csv row, but is included in cache totals and gap overlap.',
                            'correlation': 'Overlapping work is evidence of timing, not proof of exclusive causation.'}}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    a = p.parse_args()
    result = analyze(a.directory.resolve())
    (a.directory / 'map-cache-attribution.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result['summary']))


if __name__ == '__main__':
    main()
