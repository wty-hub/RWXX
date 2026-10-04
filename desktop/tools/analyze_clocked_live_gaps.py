"""Attribute actual-game gaps using ClockSync markers and same-clock native/engine spans."""
import argparse
import bisect
import collections
import csv
import json
import statistics
from pathlib import Path


class ClockMap:
    def __init__(self, events):
        self.rejected = []
        self.markers = []
        for event in events:
            if event.get('type') != 'rwx.ClockSync':
                continue
            try:
                x0, x1 = event['start'], event['start'] + event['duration']
                y0, y1 = event['monoStart'], event['monoEnd']
                reason = None
                if x1 - x0 < 100 or y1 <= y0:
                    reason = 'zero, negative or extremely small marker clock interval'
                elif x1 - x0 >= 100_000 and (y1 - y0) * 20 < x1 - x0:
                    reason = 'long JFR bracket with tiny payload interval; preemption outside payload suspected'
                if reason:
                    self.rejected.append({'event': event, 'reason': reason})
                    continue
                self.markers.append({'x0': x0, 'x1': x1, 'y0': y0, 'y1': y1,
                                     'epochMillis': event.get('epochMillis'), 'thread': event.get('thread')})
            except KeyError as error:
                self.rejected.append({'event': event, 'reason': 'missing field ' + str(error)})
        self.markers.sort(key=lambda event: event['x0'])
        self.anchors = []
        accepted = []
        for marker in self.markers:
            if self.anchors and (marker['x0'] <= self.anchors[-1][0] or marker['y0'] <= self.anchors[-1][1]):
                self.rejected.append({'event': marker, 'reason': 'non-monotonic or overlapping marker anchors'})
                continue
            self.anchors.extend(((marker['x0'], marker['y0']), (marker['x1'], marker['y1'])))
            accepted.append(marker)
        self.markers = accepted
        if len(self.markers) < 2:
            raise ValueError('Need at least two valid ClockSync markers; no raw epoch-offset fallback is allowed.')
        self.xs = [anchor[0] for anchor in self.anchors]
        self.long_segments = [(index, (b[1] - a[1]) / (b[0] - a[0]))
                              for index, (a, b) in enumerate(zip(self.anchors, self.anchors[1:]))
                              if b[0] - a[0] >= 1_000_000]
        if not self.long_segments:
            raise ValueError('No stable between-marker clock segment.')
        self.residuals = []
        mids = [((marker['x0'] + marker['x1']) // 2, (marker['y0'] + marker['y1']) // 2) for marker in self.markers]
        for index in range(1, len(mids) - 1):
            a, point, b = mids[index - 1], mids[index], mids[index + 1]
            predicted = a[1] + (point[0] - a[0]) * (b[1] - a[1]) // (b[0] - a[0])
            self.residuals.append(point[1] - predicted)

    def point(self, timestamp):
        extrapolated = timestamp < self.xs[0] or timestamp > self.xs[-1]
        if timestamp < self.xs[0]:
            index, _ = self.long_segments[0]
            anchor = self.anchors[0]
        elif timestamp > self.xs[-1]:
            index, _ = self.long_segments[-1]
            anchor = self.anchors[-1]
        else:
            index = min(bisect.bisect_right(self.xs, timestamp) - 1, len(self.anchors) - 2)
            anchor = self.anchors[index]
        a, b = self.anchors[index], self.anchors[index + 1]
        # Relative integer arithmetic avoids loss of precision at epoch timestamps near 2e18.
        mapped = anchor[1] + (timestamp - anchor[0]) * (b[1] - a[1]) // (b[0] - a[0])
        return mapped, extrapolated

    def event(self, event):
        start, extrap_start = self.point(event['start'])
        end, extrap_end = self.point(event['start'] + event.get('duration', 0))
        return {**event, 'startNanos': start, 'endNanos': end,
                'mappedDurationNanos': end - start, 'clockExtrapolated': extrap_start or extrap_end}

    def diagnostics(self):
        slopes = [slope for _, slope in self.long_segments]
        return {'validMarkerCount': len(self.markers), 'rejectedMarkerCount': len(self.rejected),
                'rejectedMarkers': self.rejected, 'markerThreads': sorted(set(str(event['thread']) for event in self.markers)),
                'markerPayloadRangeNanos': [self.anchors[0][1], self.anchors[-1][1]],
                'betweenMarkerSlopeMin': min(slopes), 'betweenMarkerSlopeMedian': statistics.median(slopes),
                'betweenMarkerSlopeMax': max(slopes),
                'leaveOneMarkerOutResidualMedianAbsMs': statistics.median(map(abs, self.residuals)) / 1e6 if self.residuals else None,
                'leaveOneMarkerOutResidualMaxAbsMs': max(map(abs, self.residuals), default=0) / 1e6,
                'method': 'Pair marker JFR start/end with monoStart/monoEnd, piecewise linear integer interpolation. Map event start and end independently; label edge extrapolation.'}


def csv_rows(path, strings=(), floats=()):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return [{key: value if key in strings else float(value) if key in floats else int(value)
                 for key, value in row.items()} for row in csv.DictReader(stream)]


def overlap(row, start, end):
    return max(0, min(end, row['endNanos']) - max(start, row['startNanos']))


def union(intervals):
    total, previous_end = 0, None
    for start, end in sorted(intervals):
        if previous_end is None or start > previous_end:
            total += end - start
            previous_end = end
        elif end > previous_end:
            total += end - previous_end
            previous_end = end
    return total


def compact_event(row, start, end):
    return {'startNanos': row['startNanos'], 'endNanos': row['endNanos'],
            'durationMs': (row['endNanos'] - row['startNanos']) / 1e6, 'overlapMs': overlap(row, start, end) / 1e6,
            **{key: row[key] for key in ('type', 'thread', 'stack', 'stage', 'name', 'cause', 'clockExtrapolated') if key in row}}


def compact_frame(row):
    return {'startNanos': row['frameStartNanos'], 'endNanos': row['frameEndNanos'], 'tick': row['tick'],
            'wallMs': (row['frameEndNanos'] - row['frameStartNanos']) / 1e6, 'cpuMs': row['ownerCpuNanos'] / 1e6,
            **{key + 'Ms': row[key + 'Nanos'] / 1e6 for key in ('work', 'update', 'draw', 'layerRedraw', 'snapshot')}}


def analyze(directory, include_supplementary=False):
    report = json.loads((directory / 'diagnostic-summary.json').read_text(encoding='utf-8'))
    run = report['run']
    if not run['validMeasurement']:
        raise ValueError('Invalid game measurement: ' + str(run.get('invalidReasons')))
    setup, windows, name = run['setup'], run['windows'], run['name']
    origin = setup['startedAtNanos']
    inside = lambda timestamp: any(window['sampleStartNanos'] <= timestamp <= window['sampleEndNanos'] for window in windows)
    frames = csv_rows(directory / 'engine.csv', floats=('cameraX', 'cameraY', 'zoom'))
    frame_ends = [row['frameEndNanos'] for row in frames]
    offsets = sorted(row['epochMillisAtEnd'] * 1_000_000 - row['frameEndNanos'] for row in frames)
    epoch_offset = (offsets[(len(offsets) - 1) // 2] + offsets[len(offsets) // 2]) // 2
    raw_jfr = [json.loads(line) for line in (directory / 'compact-profile.ndjson').open(encoding='utf-8')]
    clock = ClockMap(raw_jfr)
    jfr = [clock.event(event) for event in raw_jfr if event['type'] != 'rwx.ClockSync']
    cache = csv_rows(directory / 'map-cache.csv', strings=('event', 'axis'), floats=('cameraX', 'cameraY', 'scale', 'visibleWorldWidth', 'visibleWorldHeight'))
    vk = csv_rows(directory / 'vulkan-stages.csv', strings=('stage',))
    sections_path = directory / 'engine-sections.csv'
    sections = csv_rows(sections_path, strings=('stage',)) if sections_path.exists() else []
    trace = csv_rows(directory / (name + '-trace.csv'))
    fresh = [row for index, row in enumerate(trace) if index == 0 or (row['generation'], row['sequence']) != (trace[index - 1]['generation'], trace[index - 1]['sequence'])]
    for cell in cache:
        if cell['event'] != 'cell':
            continue
        index = bisect.bisect_left(frame_ends, cell['endNanos'])
        cell['placement'] = 'withinOwnerRow' if index < len(frames) and frames[index]['frameStartNanos'] <= cell['startNanos'] else 'outsideOwnerRow'
    between = [{'startNanos': a['frameEndNanos'], 'endNanos': b['frameStartNanos'], 'previousTick': a['tick'], 'nextTick': b['tick']}
               for a, b in zip(frames, frames[1:])]
    # Outer owner pacing should happen between rows. Check it independently of a gap's apparent cause.
    pacing_checks = []
    for event in jfr:
        if event['type'] != 'jdk.ThreadPark' or event.get('thread') != 'RWX-engine-owner' or 'io.github.rwx.kool.EngineOwnerLoop.run' not in event.get('stack', [])[:4] or not frames[0]['frameStartNanos'] <= event['startNanos'] <= event['endNanos'] <= frames[-1]['frameEndNanos']:
            continue
        start, end = event['startNanos'], event['endNanos']
        rows = [row for row in frames if row['frameStartNanos'] < end and row['frameEndNanos'] > start]
        in_rows = union([(max(start, row['frameStartNanos']), min(end, row['frameEndNanos'])) for row in rows]) / 1e6
        best_between = max((row for row in between if overlap(row, start, end)), key=lambda row: overlap(row, start, end), default=None)
        pacing_checks.append({**compact_event(event, start, end), 'measuredWindow': inside(end), 'ownerRowOverlapMs': in_rows,
                              'bestBetweenRows': {**best_between, 'durationMs': (best_between['endNanos'] - best_between['startNanos']) / 1e6,
                                                  'overlapMs': overlap(best_between, start, end) / 1e6,
                                                  'startEdgeResidualUs': (start - best_between['startNanos']) / 1e3,
                                                  'endEdgeResidualUs': (best_between['endNanos'] - end) / 1e3} if best_between else None})
    gaps = []
    for first, last in zip(fresh, fresh[1:]):
        start, end = first['acceptedPresentNanos'], last['acceptedPresentNanos']
        if end - start <= 33_333_333 or not (inside(end) or include_supplementary and 5_000_000_000 < end - origin <= 60_000_000_000):
            continue
        owner = [row for row in frames if row['frameStartNanos'] < end and row['frameEndNanos'] > start]
        row_gaps = [row for row in between if overlap(row, start, end)]
        cells = [row for row in cache if row['event'] == 'cell' and overlap(row, start, end)]
        selected = [row for row in jfr if overlap(row, start, end)]
        pauses = [row for row in selected if row['type'] == 'jdk.GCPhasePause']
        waits = [row for row in selected if row['type'] in ('jdk.ThreadPark', 'jdk.ThreadSleep', 'jdk.JavaMonitorWait', 'jdk.JavaMonitorEnter')]
        sample_rows = [row for row in jfr if row['type'] in ('jdk.ExecutionSample', 'jdk.NativeMethodSample') and start <= row['startNanos'] < end]
        owner_samples = [row for row in sample_rows if row.get('thread') == 'RWX-engine-owner']
        renderer_samples = sorted((row for row in sample_rows if row.get('thread') == 'main'), key=lambda row: row['startNanos'])
        outside_samples = [row for row in owner_samples if not any(frame['frameStartNanos'] <= row['startNanos'] <= frame['frameEndNanos'] for frame in owner)]
        vk_events = [row for row in vk if overlap(row, start, end)]
        section_events = [row for row in sections if overlap(row, start, end)]
        resources = sorted(run.get('resourceSamples', []), key=lambda row: abs(row['epochMillis'] * 1_000_000 - end - epoch_offset))[:2]
        select_methods = lambda rows: dict(collections.Counter(method for row in rows for method in set(row.get('stack', [])) if any(key.lower() in method.lower() for key in ('UiState', 'renderOffscreen', 'renderCell', 'sort', 'logger', 'publishUiState', 'issueMoves', 'ReplayPanBenchmark'))))
        gaps.append({'measuredWindow': inside(end), 'scenarioEndSeconds': (end - origin) / 1e9,
            'startNanos': start, 'endNanos': end, 'durationMs': (end - start) / 1e6, 'sequenceBefore': first['sequence'], 'sequenceAfter': last['sequence'],
            'overlappingOwnerRowCount': len(owner), 'ownerTickRange': [min((row['tick'] for row in owner), default=0), max((row['tick'] for row in owner), default=0)],
            'longestOwnerFrames': sorted([compact_frame(row) for row in owner], key=lambda row: row['wallMs'], reverse=True)[:3],
            'longestBetweenOwnerRows': sorted([{**row, 'durationMs': (row['endNanos'] - row['startNanos']) / 1e6} for row in row_gaps], key=lambda row: row['durationMs'], reverse=True)[:3],
            'longestProfilerSections': sorted([compact_event(row, start, end) for row in section_events], key=lambda row: row['durationMs'], reverse=True)[:8],
            'cache': {'count': len(cells), 'placementCounts': dict(collections.Counter(row['placement'] for row in cells)),
                      'overlapUnionMs': union([(max(start, row['startNanos']), min(end, row['endNanos'])) for row in cells]) / 1e6,
                      'fullEventCpuSumMs': sum(max(0, row['cpuNanos']) for row in cells) / 1e6,
                      'longestCells': sorted([{'startNanos': row['startNanos'], 'endNanos': row['endNanos'], 'wallMs': (row['endNanos'] - row['startNanos']) / 1e6, 'cpuMs': row['cpuNanos'] / 1e6, 'placement': row['placement'], 'tick': row['tick'], 'visible': row['visibleIntersection']} for row in cells], key=lambda row: row['wallMs'], reverse=True)[:4]},
            'gcPauseOverlapUnionMs': union([(max(start, row['startNanos']), min(end, row['endNanos'])) for row in pauses]) / 1e6,
            'gcPauses': [compact_event(row, start, end) for row in pauses],
            'safepoints': [compact_event(row, start, end) for row in jfr if row['type'] == 'jdk.SafepointBegin' and (overlap(row, start, end) or start <= row['startNanos'] < end)],
            'ownerWaits': [compact_event(row, start, end) for row in waits if row.get('thread') == 'RWX-engine-owner'],
            'rendererWaits': [compact_event(row, start, end) for row in waits if row.get('thread') == 'main'],
            'fileIo': [compact_event(row, start, end) for row in selected if row['type'] in ('jdk.FileRead', 'jdk.FileWrite')],
            'longestNativeCalls': sorted([compact_event(row, start, end) for row in vk_events if row['stage'] in ('fence', 'acquire', 'present', 'submit')], key=lambda row: row['durationMs'], reverse=True)[:4],
            'executionSamplesByThread': dict(collections.Counter(row.get('thread') for row in sample_rows)),
            'rendererSampleLeafCounts': collections.Counter(row['stack'][0] for row in renderer_samples if row.get('stack')).most_common(8),
            'rendererSampleFirstToLastSpanMs': (renderer_samples[-1]['startNanos'] - renderer_samples[0]['startNanos']) / 1e6 if len(renderer_samples) > 1 else 0,
            'rendererSamples': [{'startNanos': row['startNanos'], 'offsetMs': (row['startNanos'] - start) / 1e6,
                                 'type': row['type'], 'clockExtrapolated': row['clockExtrapolated'], 'stack': row.get('stack', [])[:12]} for row in renderer_samples],
            'ownerSampleLeafCounts': collections.Counter(row['stack'][0] for row in owner_samples if row.get('stack')).most_common(6),
            'ownerSelectedInclusiveSamples': select_methods(owner_samples), 'outsideOwnerRowSelectedInclusiveSamples': select_methods(outside_samples),
            'jfrExtrapolatedOwnerRendererOrGcCount': sum(row['clockExtrapolated'] for row in selected + sample_rows if row.get('thread') in ('main', 'RWX-engine-owner') or row['type'] in ('jdk.GCPhasePause', 'jdk.GarbageCollection', 'jdk.SafepointBegin')),
            'jfrExtrapolatedAllThreadsEventOrSampleCount': sum(row['clockExtrapolated'] for row in selected + sample_rows),
            'nearbyEpochAlignedNativeResources': resources})
    measured = [row for row in gaps if row['measuredWindow']]
    summary = {'thresholdsMs': {str(threshold): {'count': sum(row['durationMs'] > threshold for row in measured),
        'withGcPause': sum(row['durationMs'] > threshold and bool(row['gcPauses']) for row in measured),
        'withOwnerWait': sum(row['durationMs'] > threshold and bool(row['ownerWaits']) for row in measured),
        'withNativeCallOver33ms': sum(row['durationMs'] > threshold and any(call['durationMs'] > 33.333333 for call in row['longestNativeCalls']) for row in measured)} for threshold in (33.333333, 50, 100)},
        'maximumMeasuredFreshGapMs': max((row['durationMs'] for row in measured), default=0),
        'supplementaryGapCount': sum(not row['measuredWindow'] for row in gaps)}
    return {'source': str(directory), 'runtimeSha256': report['runtimeSha256'], 'actualSetup': setup,
        'clockCalibration': {**clock.diagnostics(), 'engineEpochMinusMonoNanosForResourcesOnly': epoch_offset,
            'ownerOuterParkChecks': pacing_checks, 'ownerOuterParkChecksOverlappingOwnerRowsOver1ms': sum(row['ownerRowOverlapMs'] > 1 for row in pacing_checks)},
        'summary': summary, 'gaps': sorted(gaps, key=lambda row: row['durationMs'], reverse=True),
        'limitations': ['Overlap is non-exclusive timing evidence.', 'CPU clocks are quantized and exclude other worker threads.',
            'Sections are nested and recorded only above2ms; do not sum nested spans as independent work.',
            'Resource CPU values are five-second averages despite epoch alignment.', 'Extrapolated JFR edges are explicitly marked.',
            'Between-row time includes cache/UI/task/benchmark/pacing work.', 'Vulkan wall duration alone does not identify driver/GPU/OS root cause.',
            'ExecutionSample points establish sampled stacks, not continuous call duration or exact CPU utilization; scheduling may contribute between samples.',
            'Absence of recorded waits or IO excludes events meeting the recording threshold, not every subthreshold operation.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--include-supplementary', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    result = analyze(directory, args.include_supplementary)
    output = args.output or directory / 'clocked-gap-attribution.json'
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    clock = result['clockCalibration']
    print(json.dumps({'output': str(output), 'summary': result['summary'],
        'clock': {key: clock[key] for key in ('validMarkerCount', 'rejectedMarkerCount', 'betweenMarkerSlopeMedian', 'leaveOneMarkerOutResidualMaxAbsMs', 'ownerOuterParkChecksOverlappingOwnerRowsOver1ms')}}, indent=2))
    for gap in result['gaps']:
        print(json.dumps({'gapMs': gap['durationMs'], 'scenarioSeconds': gap['scenarioEndSeconds'],
            'ownerFrame': gap['longestOwnerFrames'][:1], 'sections': gap['longestProfilerSections'][:3],
            'nativeCalls': gap['longestNativeCalls'][:1], 'ownerWaits': gap['ownerWaits'], 'gcMs': gap['gcPauseOverlapUnionMs'],
            'outsideSamples': gap['outsideOwnerRowSelectedInclusiveSamples']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
