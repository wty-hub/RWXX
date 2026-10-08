import csv
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r'build/rwx-benchmark/prof-r1/canvas-command-profile.csv'
rows = list(csv.reader(open(path)))
kinds = [r for r in rows[1:] if r[0] == 'kind']
tot = sum(int(r[2]) for r in kinds)
runs = sum(int(r[3]) for r in kinds)
print('commands', tot, 'runs', runs, 'avg run', round(tot / runs, 2))
print('kind         commands       runs  cmds/run  run1  2-4  5-16  17-64  65-256  257-1k  1k+')
for r in kinds:
    c = int(r[2]); n = int(r[3]); b = [int(x) for x in r[4:11]]
    print('%-12s %10d %10d %9.2f  %s' % (r[1], c, n, c / n, '  '.join(str(x) for x in b)))
