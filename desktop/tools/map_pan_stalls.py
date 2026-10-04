#!/usr/bin/env python3
"""Find isolated fresh-frame gaps that average FPS and p99 can hide.

Pure analysis of an already completed ABBA directory; never launches the game.
Counts are reported both for gaps ending inside each measurement window and for
gaps intersecting it. Time totals are clipped to the window, including known
left/right crossings. A map-reload atNanos is an end marker observed by the engine
after TileMap changed, not a measured reload start or full reload duration.
"""

import argparse
import bisect
import json
from pathlib import Path

from analyze_vulkan_matrix import read_json_lines
from map_pan_comparison import ORDER, read_trace

THRESHOLDS_MS = (16.667, 33.333, 50.0, 100.0)


def gap_event(trace, left_index, right_index, started_at, window, reloads):
    left, right = trace[left_index], trace[right_index]
    start, end = left[0], right[0]
    ws, we = window["sampleStartNanos"], window["sampleEndNanos"]
    overlap_start, overlap_end = max(ws, start), min(we, end)
    repeated = trace[left_index + 1:right_index]
    repeat_window = sum(ws <= row[0] <= we for row in repeated)
    markers = [row for row in reloads if start <= row["atNanos"] <= end]
    nearest = min(reloads, key=lambda row: abs(end - row["atNanos"])) if reloads else None
    seconds = (end - start) / 1e9
    return {
        "startNanos": start, "endNanos": end, "durationMs": (end - start) / 1e6,
        "startSecondsFromScenario": (start - started_at) / 1e9,
        "endSecondsFromScenario": (end - started_at) / 1e9,
        "generationBefore": left[1], "sequenceBefore": left[2],
        "generationAfter": right[1], "sequenceAfter": right[2],
        "windowOverlapMs": max(0, overlap_end - overlap_start) / 1e6,
        "crossesLeftWindowBoundary": start < ws, "crossesRightWindowBoundary": end > we,
        "repeatPresentCountDuringGap": len(repeated),
        "repeatPresentCountInsideWindow": repeat_window,
        "repeatPresentFpsDuringGap": len(repeated) / seconds if seconds else None,
        "mapReloadEndMarkersInsideGap": [
            {**row, "secondsFromScenario": (row["atNanos"] - started_at) / 1e9,
             "reloadEndToFreshAcceptanceMs": (end - row["atNanos"]) / 1e6} for row in markers],
        "nearestMapReloadEnd": ({**nearest,
            "secondsFromScenario": (nearest["atNanos"] - started_at) / 1e9,
            "reloadEndToFreshAcceptanceMs": (end - nearest["atNanos"]) / 1e6,
            "insideGap": start <= nearest["atNanos"] <= end} if nearest else None),
    }


def analyze_window(trace, window, started_at, reloads):
    fresh_indices = [i for i, row in enumerate(trace) if i == 0 or row[1:] != trace[i - 1][1:]]
    ws, we = window["sampleStartNanos"], window["sampleEndNanos"]
    times = [row[0] for row in trace]
    first, last = bisect.bisect_left(times, ws), bisect.bisect_right(times, we)
    fresh_count = sum(ws <= trace[index][0] <= we for index in fresh_indices)
    events = []
    for left_index, right_index in zip(fresh_indices, fresh_indices[1:]):
        if trace[right_index][0] >= ws and trace[left_index][0] < we:
            events.append(gap_event(trace, left_index, right_index, started_at, window, reloads))
    thresholds = {}
    for threshold in THRESHOLDS_MS:
        over = [event for event in events if event["durationMs"] > threshold]
        ending = [event for event in over if ws <= event["endNanos"] <= we]
        # Count actual window time after the tolerated interval has elapsed.
        stalled_ns = sum(max(0, min(we, event["endNanos"]) - max(ws, event["startNanos"] + threshold * 1e6)) for event in over)
        thresholds[f"{threshold:g}"] = {
            "endingIntervalCount": len(ending), "intersectingIntervalCount": sum(event["windowOverlapMs"] > 0 for event in over),
            "stalledTimeMs": stalled_ns / 1e6,
            "stalledTimePercentOfWindow": stalled_ns / (we - ws) * 100,
            "gapOverlapTimeMs": sum(event["windowOverlapMs"] for event in over),
            "repeatPresentCountInOverThresholdGaps": sum(event["repeatPresentCountInsideWindow"] for event in over),
            "overThresholdGapsWithReloadEndMarker": sum(bool(event["mapReloadEndMarkersInsideGap"]) for event in over),
        }
    return {
        "repetition": window["repetition"], "sampleStartNanos": ws, "sampleEndNanos": we,
        "sampleStartSecondsFromScenario": (ws - started_at) / 1e9,
        "sampleEndSecondsFromScenario": (we - started_at) / 1e9,
        "sampleStartTick": window.get("sampleStartTick"), "sampleEndTick": window.get("sampleEndTick"),
        "acceptedPresentCount": last - first, "freshAcceptedCount": fresh_count,
        "repeatPresentCount": last - first - fresh_count,
        "thresholdsMs": thresholds,
        "largestFiveFreshGaps": sorted(events, key=lambda event: event["durationMs"], reverse=True)[:5],
        "freshGapsOver100Ms": [event for event in events if event["durationMs"] > 100],
    }


def analyze_directory(directory):
    summary = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
    runs = summary["runs"]
    if tuple(run["variant"] for run in runs) != ORDER or not all(run.get("exitCode") == 0 for run in runs):
        raise ValueError("All four ABBA processes must have completed normally before stall analysis")
    analyzed = []
    for run in runs:
        name = run["name"]
        trace = read_trace(directory / f"{name}-trace.csv")
        scenario = read_json_lines(directory / f"{name}-scenario.ndjson")
        setup = next(row for row in scenario if row["kind"] == "scenario")
        reloads = [row for row in scenario if row["kind"] == "map-reload"]
        windows = [row for row in scenario if row["kind"] == "engine-window"]
        analyzed.append({"name": name, "variant": run["variant"], "startedAtNanos": setup["startedAtNanos"],
                         "mapReloadEndMarkers": [{**row, "secondsFromScenario": (row["atNanos"] - setup["startedAtNanos"]) / 1e9} for row in reloads],
                         "windows": [analyze_window(trace, window, setup["startedAtNanos"], reloads) for window in windows]})
    aggregates = {}
    for variant in ("baseline", "candidate"):
        windows = [window for run in analyzed if run["variant"] == variant for window in run["windows"]]
        aggregates[variant] = {"windowCount": len(windows), "thresholdsMs": {}}
        aggregates[variant]["timeTotalScope"] = "sum of measured-window times; frame-end overshoot may cause slight overlap between consecutive windows"
        for threshold in THRESHOLDS_MS:
            key = f"{threshold:g}"
            fields = ("endingIntervalCount", "intersectingIntervalCount", "stalledTimeMs", "gapOverlapTimeMs",
                      "repeatPresentCountInOverThresholdGaps", "overThresholdGapsWithReloadEndMarker")
            aggregates[variant]["thresholdsMs"][key] = {field: sum(window["thresholdsMs"][key][field] for window in windows) for field in fields}
        all_events = [event for window in windows for event in window["largestFiveFreshGaps"]]
        aggregates[variant]["maxFreshGapMs"] = max((event["durationMs"] for event in all_events), default=None)
    return {"sourceSummary": str(directory / "summary.json"), "validComparison": summary.get("comparison", {}).get("validComparison"),
            "definitions": {
                "freshGap": "time between consecutive accepted presentations whose generation/sequence changes",
                "counts": "endingIntervalCount matches intervals ending inside inclusive sample bounds; intersectingIntervalCount includes known boundary-crossing gaps",
                "stalledTimeMs": "window-clipped time after the threshold has elapsed since the previous fresh acceptance; this is excess time, not the entire interval",
                "gapOverlapTimeMs": "entire over-threshold gap duration clipped to sample bounds",
                "repeats": "successful accepted presentations between the two fresh endpoints; the old snapshot is repeated",
                "reloadEvidence": "map-reload atNanos is the first engine callback observing a changed TileMap, approximately reload end; only temporal overlap is established",
                "windowTotals": "nominal adjacent windows can overlap by engine-frame overshoot; window-specific counts and summed totals retain those recorded bounds",
                "limitations": "no reload-start marker, GPU profiling, input-latency measurement or physical scanout; overlap cannot prove causal attribution; wall-clock windows can cover different replay ticks"},
            "runs": analyzed, "aggregates": aggregates}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    report = analyze_directory(args.directory.resolve())
    output = args.directory.resolve() / "stalls.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for run in report["runs"]:
        for window in run["windows"]:
            print(json.dumps({"run": run["name"], "window": window["repetition"],
                              "thresholdsMs": window["thresholdsMs"],
                              "largestFreshGap": window["largestFiveFreshGaps"][0] if window["largestFiveFreshGaps"] else None}, ensure_ascii=False))
    print("AGGREGATES " + json.dumps(report["aggregates"], ensure_ascii=False))
    print(f"STALLS {output}")


if __name__ == "__main__":
    main()
