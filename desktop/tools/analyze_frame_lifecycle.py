"""Join explicit picture/submission/input identities; temporal overlap is never a causal edge.

The trace marks CPU production before mailbox publication, SceneHost's chosen picture,
successful frame submission, and accepted present. None is GPU completion or scan-out.
"""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path


def picture(row):
    return int(row['generation']), int(row['sequence'])


def percentiles(values):
    values = sorted(values)
    return {'events': len(values), **{
        name: (values[int((len(values) - 1) * quantile)] / 1e6 if values else None)
        for name, quantile in (('p50Ms', .5), ('p99Ms', .99), ('p999Ms', .999), ('maxMs', 1))}}


def analyze(rows, input_rows=None):
    reasons = []
    markers = [row for row in rows if row['event'] == 'trace-complete']
    events = [row for row in rows if row['event'] != 'trace-complete']
    if len(markers) != 1 or not rows or rows[-1]['event'] != 'trace-complete':
        reasons.append('missing or ambiguous final trace-complete marker; partial capture')
    dropped = None
    if len(markers) == 1:
        marker = markers[0]
        dropped = int(marker['droppedEvents'])
        recorded, capacity = int(marker['recordedEvents']), int(marker['capacity'])
        if recorded != len(events) or not 0 <= recorded <= capacity:
            reasons.append('recorded-event count or capacity does not match the trace')
        if dropped:
            reasons.append(f'bounded lifecycle storage dropped {dropped} events')
    native = any(row['event'] == 'submitted' for row in events) or any(
        row.get('nativeBackend') == 'true' for row in markers)
    produced, submissions, accepted = {}, {}, {}
    presented_submissions = set()
    choice = None
    previous_time = None
    previous_picture = None
    paths = []
    counts = Counter()
    for row in events:
        now, event = int(row['eventNanos']), row['event']
        key = picture(row)
        counts[event] += 1
        if previous_time is not None and now < previous_time:
            reasons.append(f'non-monotonic lifecycle event at {now}')
        previous_time = now
        if min(key) < 0:
            # Menu/native frames may submit before any engine envelope has been chosen.
            # They remain in the evidence, but cannot be credited with an engine picture.
            if event == 'submitted':
                continue
            reasons.append(f'{event} has no engine picture identity at {now}')
            continue
        if event == 'produced':
            if key in produced:
                reasons.append(f'duplicate produced identity {key}')
            else:
                produced[key] = now
        elif event == 'chosen':
            choice = {'key': key, 'at': now}
            if key not in produced or produced[key] > now:
                reasons.append(f'chosen picture {key} has no preceding production')
        elif event == 'submitted':
            ordinal = int(row['submission'])
            if ordinal < 0 or ordinal in submissions:
                reasons.append(f'invalid or reused submission identity {ordinal}')
            if choice is None or choice['key'] != key:
                reasons.append(f'submission {ordinal} has no matching chosen picture {key}')
                continue
            submissions[ordinal] = {'key': key, 'at': now, 'choice': choice.copy()}
        elif event == 'presented':
            ordinal = int(row['submission'])
            if native:
                submission = submissions.get(ordinal)
                if submission is None or submission['key'] != key:
                    reasons.append(f'present {key} at {now} has no exact submission edge {ordinal}')
                    continue
                if ordinal in presented_submissions:
                    reasons.append(f'native submission {ordinal} was presented more than once')
                    continue
                presented_submissions.add(ordinal)
                selected = submission['choice']
                submitted = submission['at']
            else:
                selected, submitted = choice, None
                if ordinal != -1:
                    reasons.append(f'present {key} names submission {ordinal} without a submission trace')
                    continue
            if selected is None or selected['key'] != key or key not in produced:
                reasons.append(f'present {key} at {now} has an incomplete produced/chosen path')
                continue
            boundary = [produced[key], selected['at'], *([submitted] if submitted is not None else []), now]
            if boundary != sorted(boundary):
                reasons.append(f'picture {key} at {now} has inverted causal boundaries')
                continue
            segments = {'producedToChosen': selected['at'] - produced[key]}
            if submitted is None:
                segments['chosenToAcceptedPresent'] = now - selected['at']
            else:
                segments['chosenToSubmitted'] = submitted - selected['at']
                segments['submittedToAcceptedPresent'] = now - submitted
            path = {'generation': key[0], 'sequence': key[1], 'submission': ordinal,
                    'producedNanos': produced[key], 'chosenNanos': selected['at'],
                    'submittedNanos': submitted, 'acceptedPresentNanos': now,
                    'segmentsNanos': segments, 'pictureAgeAtPresentNanos': now - produced[key]}
            if (key, now) in accepted:
                reasons.append(f'duplicate accepted picture event {key} at {now}')
            accepted[key, now] = path
            if key != previous_picture:
                paths.append(path)
            previous_picture = key
        else:
            reasons.append(f'unknown lifecycle event {event!r}')
    if not accepted:
        reasons.append('no accepted engine picture with a complete lifecycle')

    inputs = []
    input_status = 'not-requested'
    if input_rows is not None:
        input_markers = [row for row in input_rows if row['stage'] == 'trace-complete']
        if len(input_markers) != 1 or not input_rows or input_rows[-1]['stage'] != 'trace-complete':
            reasons.append('input trace has no unique final trace-complete marker; legacy or partial capture')
            input_status = 'legacy-or-partial'
        else:
            retained = [row for row in input_rows if row['stage'] not in ('trace-complete', 'trace-overflow')]
            if int(input_markers[0]['value']) != len(retained):
                reasons.append('input trace-complete count does not match retained rows')
                input_status = 'count-mismatch'
            else:
                input_status = 'complete'
        if any('overflow' in row['stage'] for row in input_rows):
            reasons.append('input trace overflowed; input causal coverage is incomplete')
            input_status = 'overflowed'
        sampled = {row['id']: row for row in input_rows if row['stage'] == 'sampled'}
        if not sampled:
            reasons.append('input capture contains no sampled inputs')
            input_status = 'empty'
        applied = {row['id']: row for row in input_rows if row['stage'] == 'applied'}
        responses = [row for row in input_rows if row['stage'] in ('presented', 'camera-effect')]
        response_ids = {row['id'] for row in responses if row['stage'] == 'presented'}
        if sampled.keys() - response_ids:
            reasons.append('some sampled inputs have no accepted-picture acknowledgment')
        for row in responses:
            key, now = picture(row), int(row['acceptedPresentNanos'])
            path = accepted.get((key, now))
            if row['id'] not in sampled or row['id'] not in applied or path is None:
                reasons.append(f'input {row["id"]}/{row["stage"]} has no exact input/picture edge')
                continue
            start, adoption = int(row['sampledNanos']), int(row['appliedNanos'])
            if [start, adoption, path['producedNanos']] != sorted([start, adoption, path['producedNanos']]):
                reasons.append(f'input {row["id"]} has inverted sample/adoption/production boundaries')
                continue
            if start != int(sampled[row['id']]['sampledNanos']) or adoption != int(applied[row['id']]['appliedNanos']):
                reasons.append(f'input {row["id"]} changes its original timestamps')
                continue
            segments = {'sampledToAdopted': adoption - start,
                        'adoptedToProduced': path['producedNanos'] - adoption,
                        **path['segmentsNanos']}
            inputs.append({'id': row['id'], 'kind': row['kind'], 'stage': row['stage'],
                           'generation': key[0], 'sequence': key[1], 'submission': path['submission'],
                           'sampledNanos': start, 'acceptedPresentNanos': now,
                           'totalNanos': now - start, 'segmentsNanos': segments,
                           'inputPictureTimestampOffsetNanos': int(row['producedNanos']) - path['producedNanos']})

    summaries = {}
    for group, records in (('freshPictures', paths), ('inputAcknowledgments', [row for row in inputs if row['stage'] == 'presented']),
                           ('inputCameraEffects', [row for row in inputs if row['stage'] == 'camera-effect'])):
        names = sorted({name for row in records for name in row['segmentsNanos']})
        summaries[group] = {name: percentiles([row['segmentsNanos'][name] for row in records if name in row['segmentsNanos']])
                            for name in names}
    return {'schemaVersion': 1, 'diagnosticOnly': True, 'validTrace': not reasons,
            'invalidReasons': list(dict.fromkeys(reasons)), 'droppedEvents': dropped,
            'eventCounts': dict(counts), 'submissionBoundaryVerified': native,
            'acceptedPictureEvents': len(accepted), 'freshPictureEvents': len(paths),
            'producedButNotPresentedPictures': len(produced.keys() - {key for key, _ in accepted}),
            'inputTraceProvided': input_rows is not None, 'inputCaptureStatus': input_status,
            'supportedBoundaryStages': ['produced', 'chosen', *(['submitted'] if native else []), 'accepted-present'],
            'segmentSummaries': summaries, 'framePaths': paths, 'inputPaths': inputs,
            'measurementBoundary': 'CPU production marker before mailbox publication to accepted queue present; optional normal-session API input sampling',
            'limitations': ['Accepted present is not GPU completion, OS input sampling, or physical scan-out.',
                            'Chosen-to-submit includes CPU work and any waits; these boundary durations do not identify CPU/GPU causes.',
                            'Input camera-effect semantics are supplied by the existing input trace; use its own response validation too.',
                            'No missing edge is reconstructed from temporal overlap or a neighboring submission.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace', type=Path)
    parser.add_argument('--input-response', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    def read(path):
        with path.open(encoding='utf-8') as stream:
            return list(csv.DictReader(stream))
    result = analyze(read(args.trace), read(args.input_response) if args.input_response else None)
    # CREATE_NEW: a later analysis must never overwrite the evidence from an earlier one.
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2)
    print(json.dumps({key: result[key] for key in ('validTrace', 'invalidReasons', 'eventCounts',
                                                'submissionBoundaryVerified', 'acceptedPictureEvents', 'segmentSummaries')}, indent=2))
    return 0 if result['validTrace'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
