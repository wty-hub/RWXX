"""Check normal-session input adoption against the corresponding accepted camera pictures."""
import argparse
import csv
import json
from pathlib import Path

from map_pan_comparison import percentile


def analyze(rows, windows):
    accepted = [row for row in rows if row['stage'] == 'presented' and
                any(window['sampleStartNanos'] <= int(row['sampledNanos']) <= window['sampleEndNanos'] for window in windows)]
    applied = {row['id']: row for row in rows if row['stage'] == 'applied'}
    sampled = [row for row in rows if row['stage'] == 'sampled' and
               any(window['sampleStartNanos'] <= int(row['sampledNanos']) <= window['sampleEndNanos'] for window in windows)]
    accepted_ids = {row['id'] for row in accepted}
    reasons = []
    if not accepted:
        reasons.append('no input sampled in the measurement windows reached an accepted picture')
    missing = [row for row in sampled if row['id'] not in accepted_ids]
    if missing:
        reasons.append(f'{len(missing)} sampled events never reached an accepted picture')
    latencies = [(int(row['acceptedPresentNanos']) - int(row['sampledNanos'])) / 1e6 for row in accepted]
    owner = [(int(row['appliedNanos']) - int(row['sampledNanos'])) / 1e6 for row in accepted]
    tracks_effects = all('expectsCamera' in row for row in rows)
    if not tracks_effects:
        reasons.append('trace only tracks first acknowledgment, not the later camera response')
    candidates = [row for row in rows if row['stage'] == 'camera-effect' and
                  any(window['sampleStartNanos'] <= int(row['sampledNanos']) <= window['sampleEndNanos'] for window in windows)]
    changed = [row for row in candidates if row['id'] in applied and any(
        abs(float(row[field]) - float(applied[row['id']][field])) > 1e-5 for field in
        (('zoom',) if row['kind'] == 'wheel' else ('cameraX', 'cameraY')))]
    effect_ids = {row['id'] for row in changed}
    missing_effects = [row for row in sampled if row.get('expectsCamera') == 'true' and row['id'] not in effect_ids]
    if missing_effects:
        reasons.append(f'{len(missing_effects)} camera-driving inputs never produced an observed camera response')
    activation = [row for row in accepted if row['kind'] == 'pointer/activation']
    if any(row['id'] not in applied or any(abs(float(row[field]) - float(applied[row['id']][field])) > 1e-5
           for field in ('cameraX', 'cameraY')) for row in activation):
        reasons.append('a movement classified as drag activation moved the camera; its response cannot be exempted')
    kinds = {row['kind'].split('/')[0] for row in accepted}
    if not {'pointer', 'key', 'wheel'} <= kinds:
        reasons.append('normal drag, camera keys and wheel were not all exercised')
    if not changed:
        reasons.append('accepted pictures show no camera response after input adoption')
    changed_kinds = {row['kind'].split('/')[0] for row in changed}
    if not {'pointer', 'key', 'wheel'} <= changed_kinds:
        reasons.append('drag, camera keys and wheel must each produce an observed camera response')
    effect_latencies = [(int(row['acceptedPresentNanos']) - int(row['sampledNanos'])) / 1e6 for row in changed]
    by_kind = {}
    for kind in ('pointer', 'key', 'wheel'):
        events = [row for row in changed if row['kind'].split('/')[0] == kind]
        times = [(int(row['acceptedPresentNanos']) - int(row['sampledNanos'])) / 1e6 for row in events]
        by_kind[kind] = {'events': len(events), 'p99Ms': percentile(times, .99),
                         'p999Ms': percentile(times, .999), 'maxMs': max(times, default=None)}
    if any(int(row['sampledNanos']) > int(row['appliedNanos']) or
           int(row['appliedNanos']) > int(row['producedNanos']) or
           int(row['producedNanos']) > int(row['acceptedPresentNanos']) for row in accepted + changed):
        reasons.append('input, owner, publication and presentation timestamps are out of order')
    return {'validTrace': not reasons, 'invalidReasons': reasons, 'sampledEvents': len(sampled),
            'presentedEvents': len(accepted), 'cameraChangedEvents': len(changed), 'missingEvents': missing,
            'cameraEffectTracking': tracks_effects, 'missingCameraEffects': missing_effects,
            'dragActivationEvents': len(activation),
            'inputToAcceptedP99Ms': percentile(latencies, .99), 'inputToAcceptedP999Ms': percentile(latencies, .999),
            'inputToAcceptedMaxMs': max(latencies, default=None), 'inputToOwnerP99Ms': percentile(owner, .99),
            'cameraChangedKinds': sorted(changed_kinds),
            'cameraResponsesByKind': by_kind,
            'inputToCameraEffectP99Ms': percentile(effect_latencies, .99),
            'inputToCameraEffectP999Ms': percentile(effect_latencies, .999),
            'inputToCameraEffectMaxMs': max(effect_latencies, default=None),
            'eventsOver16_667Ms': [row for row, latency in zip(accepted, latencies) if latency > 16.667],
            'measurementBoundary': 'normal desktop session API sampling to accepted queue present; OS event delivery before API sampling is not included'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    root = args.directory
    report = json.loads((root / 'diagnostic-summary.json').read_text(encoding='utf-8'))
    with (root / 'input-response.csv').open(encoding='utf-8') as stream:
        result = analyze(list(csv.DictReader(stream)), report['run']['windows'])
    (root / 'input-response-summary.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if key not in ('missingEvents', 'eventsOver16_667Ms')}, indent=2))
    return 0 if result['validTrace'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
