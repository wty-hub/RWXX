"""Compare normal input camera-response tails, keeping both diagnostic boundaries explicit."""
import argparse
import json
from pathlib import Path


def compare(original, rwx):
    reasons = []
    if not original['validTrace'] or not rwx['validTrace']:
        reasons.append('one or both traces failed their camera-response validation')
    by_kind = {}
    for kind in ('pointer', 'key', 'wheel'):
        left = original.get('cameraResponsesByKind', {}).get(kind)
        right = rwx.get('cameraResponsesByKind', {}).get(kind)
        passed = bool(left and right and left['events'] > 0 and right['events'] > 0 and
                      right['p99Ms'] <= left['p99Ms'] and right['p999Ms'] <= left['p999Ms'])
        by_kind[kind] = {'original': left, 'rwx': right, 'tailNotIncreased': passed}
        if not passed:
            reasons.append(f'{kind} response tail is missing or exceeds the original')
    return {'apiBoundaryResponsePass': not reasons, 'reasons': reasons, 'byKind': by_kind,
            'originalBoundary': original['measurementBoundary'], 'rwxBoundary': rwx['measurementBoundary'],
            'physicalOsInputResponseVerified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original', type=Path)
    parser.add_argument('rwx', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('output already exists; retain the previous comparison')
    read = lambda root: json.loads((root / 'input-response-summary.json').read_text(encoding='utf-8'))
    result = compare(read(args.original), read(args.rwx))
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
    return 0 if result['apiBoundaryResponsePass'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
