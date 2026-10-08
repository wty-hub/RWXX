"""Summarize an engine-section stage trace (startNanos,endNanos,stage,value0,value1,value2)."""
import collections
import csv
import statistics
import sys


def main():
    for path in sys.argv[1:]:
        totals = collections.defaultdict(list)
        with open(path, newline='') as stream:
            reader = csv.reader(stream)
            next(reader)
            for row in reader:
                if len(row) < 3:
                    continue
                try:
                    totals[row[2]].append(int(row[1]) - int(row[0]))
                except ValueError:
                    continue
        print('==', path)
        for stage in sorted(totals, key=lambda key: -sum(totals[key])):
            values = sorted(totals[stage])
            if len(values) < 5:
                continue
            print('  %-24s n=%6d mean=%9.1fus p50=%9.1f p95=%10.1f max=%9.1fms total=%7.1fs' % (
                stage, len(values), statistics.mean(values) / 1000, values[len(values) // 2] / 1000,
                values[int(len(values) * .95)] / 1000, values[-1] / 1e6, sum(values) / 1e9))


if __name__ == '__main__':
    main()
