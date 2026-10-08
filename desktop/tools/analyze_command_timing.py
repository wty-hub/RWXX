import csv
import os
import sys

ROOT = r'build/rwx-benchmark'


def stage_totals(tag):
    path = os.path.join(ROOT, tag, 'canvas-stages.csv')
    totals = {}
    passes = 0
    if not os.path.exists(path):
        return totals, passes
    for row in csv.reader(open(path, newline='')):
        if len(row) < 4 or row[0] == 'startNanos':
            continue
        try:
            elapsed = int(row[1]) - int(row[0])
        except ValueError:
            continue
        totals[row[2]] = totals.get(row[2], 0) + elapsed
        if row[2] == 'canvas-commands':
            passes += 1
    return totals, passes


def timing(tag):
    path = os.path.join(ROOT, tag, 'canvas-command-timing.csv')
    if not os.path.exists(path):
        return {}
    return {r[0]: (int(r[1]), int(r[2])) for r in csv.reader(open(path)) if r and r[0] != 'kind'}


print('%-14s %10s %10s %8s %8s %7s' % ('run', 'cmdLoop_ms', 'text_ms', 'text_us', 'passes', 'text/pass'))
for tag in sys.argv[1:]:
    totals, passes = stage_totals(tag)
    rows = timing(tag)
    text = rows.get('drawText', (0, 0))
    loop = totals.get('canvas-commands', 0)
    print('%-14s %10.0f %10.0f %8.2f %8d %7.0f' % (
        tag, loop / 1e6, text[1], text[1] * 1000 / max(text[0], 1), passes,
        text[0] / max(passes, 1)))
