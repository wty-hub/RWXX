"""Aggregate the compact JFR sample stream into per-thread CPU hot stacks.

`jdk.ExecutionSample` is emitted on a fixed interval for threads that are running, so its counts track
CPU time instead of wall clock. That makes it the usable comparison on a desktop whose wall clock swung
between 66 and 231 presents per second for the identical jar.
"""
import argparse
import collections
import json
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]


def load(path, warmup_seconds=0.0):
    rows = []
    first = None
    for line in path.open(encoding='utf-8'):
        event = json.loads(line)
        if event['type'] != 'jdk.ExecutionSample' and event['type'] != 'jdk.NativeMethodSample':
            continue
        if first is None:
            first = event['start']
        event['seconds'] = (event['start'] - first) / 1e9
        if event['seconds'] < warmup_seconds:
            continue
        rows.append(event)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', type=Path)
    parser.add_argument('--warmup-seconds', type=float, default=30.0)
    parser.add_argument('--top', type=int, default=25)
    parser.add_argument('--thread', default=None, help='Only this thread name (substring)')
    args = parser.parse_args()
    rows = load(args.path, args.warmup_seconds)
    by_thread = collections.Counter(row['thread'] or '?' for row in rows)
    print('samples after warmup:', len(rows))
    print('per thread:')
    for thread, count in by_thread.most_common(12):
        print('  %-34s %6d' % (thread[:34], count))
    selected = [row for row in rows
                if args.thread is None or args.thread in (row['thread'] or '')]
    if not selected:
        return
    print('\nhot leaf frames (%d samples%s):' % (
        len(selected), '' if args.thread is None else ', thread=' + args.thread))
    leaves = collections.Counter(row['stack'][0] if row['stack'] else '?' for row in selected)
    for name, count in leaves.most_common(args.top):
        print('  %6d %5.2f%%  %s' % (count, 100 * count / len(selected), name))
    print('\nhot own frames in io.github.rwx / com.corrodinggames:')
    own = collections.Counter()
    for row in selected:
        for frame in row['stack']:
            if frame.startswith('io.github.rwx') or frame.startswith('com.corrodinggames'):
                own[frame] += 1
                break
    for name, count in own.most_common(args.top):
        print('  %6d %5.2f%%  %s' % (count, 100 * count / len(selected), name))


if __name__ == '__main__':
    main()
