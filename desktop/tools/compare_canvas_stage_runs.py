"""Compare the same stage across two canvas trace runs, including per-command frozen volume."""
import collections
import csv
import statistics
import sys


def summarize(path):
    totals = collections.defaultdict(list)
    details = collections.defaultdict(list)
    with open(path, newline='') as stream:
        reader = csv.reader(stream)
        next(reader)
        for row in reader:
            if len(row) < 4:
                continue
            try:
                elapsed = int(row[1]) - int(row[0])
            except ValueError:
                continue
            totals[row[2]].append(elapsed)
            details[row[2]].append([int(value) if value.lstrip('-').isdigit() else 0 for value in row[3:]])
    return totals, details


def main():
    for path in sys.argv[1:]:
        totals, details = summarize(path)
        span = max(max(values) for values in totals.values()) / 1e9 if totals else 0
        print('==', path)
        for stage in sorted(totals, key=lambda key: -sum(totals[key])):
            values = totals[stage]
            if len(values) < 5:
                continue
            print('  %-16s n=%7d total=%7.1fs mean=%8.1fus' % (
                stage, len(values), sum(values) / 1e9, statistics.mean(values) / 1000))
        for stage in ('freeze-detail', 'freeze-content', 'freeze-repeat'):
            if stage not in details:
                continue
            rows = details[stage]
            sums = [sum(row[index] for row in rows) for index in range(len(rows[0]))]
            print('  %-16s column sums: %s' % (stage, sums))
        print()


if __name__ == '__main__':
    main()
