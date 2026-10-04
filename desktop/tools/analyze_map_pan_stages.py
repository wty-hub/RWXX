#!/usr/bin/env python3
"""Attribute fresh-presentation gaps to recorded wall-clock stages (not CPU time).

Parent and child stages overlap. Their durations must not be added together.
The engine operates in parallel and has no snapshot sequence in engine.csv.
"""
import argparse
import bisect
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path

from analyze_vulkan_matrix import percentile, read_json_lines
from map_pan_comparison import read_trace


def stages(path):
    with path.open(encoding="utf-8") as stream:
        rows = [{k: v if k == "stage" else int(v) for k, v in r.items()}
                for r in csv.DictReader(stream)]
    if any(r["endNanos"] < r["startNanos"] for r in rows):
        raise ValueError(f"Negative stage duration in {path}")
    return rows


def union_nanos(intervals):
    total = 0
    end = None
    for a, b in sorted(intervals):
        if end is None or a > end:
            total += b - a
            end = b
        elif b > end:
            total += b - end
            end = b
    return total


def intersects(a, b, c, d):
    return max(0, min(b, d) - max(a, c))


def analyze(directory):
    summary = json.loads((directory / "diagnostic-summary.json").read_text(encoding="utf-8"))
    run = summary["run"]
    if not run["validMeasurement"]:
        raise ValueError("Replay-pan diagnostic is not valid")
    windows = run["windows"]
    trace = read_trace(directory / f"{run['name']}-trace.csv")
    times = [t[0] for t in trace]
    fresh = [t for i, t in enumerate(trace) if i == 0 or t[1:] != trace[i - 1][1:]]
    scenario = read_json_lines(directory / f"{run['name']}-scenario.ndjson")
    reloads = [r["atNanos"] for r in scenario if r["kind"] == "map-reload"]
    canvas = stages(directory / "canvas-stages.csv")
    native = stages(directory / "vulkan-stages.csv")
    with (directory / "engine.csv").open(encoding="utf-8") as stream:
        engine = [{k: float(v) if k in ("cameraX", "cameraY", "zoom") else int(v)
                   for k, v in r.items()} for r in csv.DictReader(stream)]
    all_stages = canvas + native
    categories = {
        "native": {"fence", "acquire", "submit", "present"},
        "canvas": {"envelope-install", "canvas-new", "canvas-repeat"},
        "uploads": {"texture-load", "buffer-upload"},
    }
    gaps = []
    for left, right in zip(fresh, fresh[1:]):
        a, b = left[0], right[0]
        if b - a < 50_000_000 or not any(w["sampleStartNanos"] <= b <= w["sampleEndNanos"] for w in windows):
            continue
        found = [r for r in all_stages if intersects(a, b, r["startNanos"], r["endNanos"]) > 0]
        main = [{**r, "durationMs": (r["endNanos"] - r["startNanos"]) / 1e6,
                 "overlapMs": intersects(a, b, r["startNanos"], r["endNanos"]) / 1e6} for r in found]
        coverage = {name: union_nanos([(max(a, r["startNanos"]), min(b, r["endNanos"]))
                                      for r in found if r["stage"] in names]) / (b - a)
                    for name, names in categories.items()}
        covered = union_nanos([(max(a, r["startNanos"]), min(b, r["endNanos"]))
                               for r in found if any(r["stage"] in names for names in categories.values())])
        dominant = max(coverage, key=coverage.get)
        if coverage[dominant] < .5:
            dominant = "mixed-or-unrecorded"
        work = [r for r in engine if intersects(a, b, r["frameStartNanos"], r["frameEndNanos"]) > 0]
        longest = max(work, key=lambda r: r["workNanos"], default=None)
        completed = sum(a < r["frameEndNanos"] <= b for r in work)
        gaps.append({"startNanos": a, "endNanos": b, "durationMs": (b - a) / 1e6,
                     "generationAfter": right[1], "sequenceAfter": right[2],
                     "reloadEndInside": any(a <= t <= b for t in reloads),
                     "within500msAfterReloadEnd": any(0 <= a - t <= 500_000_000 for t in reloads),
                     "acceptedPresentCount": bisect.bisect_right(times, b) - bisect.bisect_right(times, a),
                     "knownMainCoverageFraction": covered / (b - a),
                     "categoryCoverageFractions": coverage, "dominantMain": dominant,
                     "engineFramesCompleted": completed, "longestEngineFrame": longest,
                     "largestStages": sorted(main, key=lambda r: r["overlapMs"], reverse=True)[:12]})
    ordinary = [g for g in gaps if not g["reloadEndInside"] and not g["within500msAfterReloadEnd"]]
    distributions = {}
    grouped = defaultdict(list)
    for r in all_stages:
        if any(w["sampleStartNanos"] <= r["endNanos"] <= w["sampleEndNanos"] for w in windows):
            grouped[r["stage"]].append(r)
    for name, rows in grouped.items():
        values = [(r["endNanos"] - r["startNanos"]) / 1e6 for r in rows]
        distributions[name] = {"count": len(values), "p99Ms": percentile(values, .99),
                               "maxMs": max(values), "over33ms": sum(v > 33.333 for v in values),
                               "over50ms": sum(v > 50 for v in values)}
        if name in categories["native"]:
            distributions[name]["resultCounts"] = dict(Counter(r["value0"] for r in rows))
    report = {"source": str(directory / "diagnostic-summary.json"),
              "summary": {"freshGapsAtLeast50ms": len(gaps), "ordinaryGaps": len(ordinary),
                          "ordinaryDominantMainCounts": dict(Counter(g["dominantMain"] for g in ordinary)),
                          "ordinaryWithOnePresent": sum(g["acceptedPresentCount"] == 1 for g in ordinary)},
              "stageDistributions": distributions, "gaps": gaps,
              "definitions": {"ordinary": "Exclude gaps containing a reload-end marker or beginning within 500ms after it",
                              "coverage": "Union of recorded wall-clock intervals intersecting a fresh gap; nested categories overlap",
                              "dominant": "Largest recorded main category covering at least half the gap; not proof of CPU/GPU root cause",
                              "limitations": "Instrumented run; not a throughput comparison. Engine phases are duration totals, not exact subphase boundaries. Unrecorded frontend/backend work and OS scheduling remain possible."}}
    (directory / "stage-attribution.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    report = analyze(parser.parse_args().directory.resolve())
    print(json.dumps({"summary": report["summary"], "stages": report["stageDistributions"]}, ensure_ascii=False))
