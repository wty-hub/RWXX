"""Exclude measured replay reload epochs from an existing clock-calibrated gap report."""
import argparse
import csv
import json
from pathlib import Path

from analyze_clocked_live_gaps import ClockMap, union
from analyze_native_bgra_abba import dist, inside, merge_windows, overlap_ns


def analyze(directory):
    report = json.loads((directory / "diagnostic-summary.json").read_text(encoding="utf-8-sig"))
    attribution = json.loads((directory / "clocked-gap-attribution.json").read_text(encoding="utf-8"))
    run = report["run"]
    windows = merge_windows(run["windows"])
    name = run["name"]
    scenario = [json.loads(line) for line in (directory / (name + "-scenario.ndjson")).read_text(encoding="utf-8").splitlines()]
    reloads = [row for row in scenario if row.get("kind") == "map-reload"]
    with (directory / "engine.csv").open(encoding="utf-8-sig", newline="") as stream:
        frames = [{key: int(row[key]) for key in ("frameStartNanos", "frameEndNanos", "tick")} for row in csv.DictReader(stream)]
    with (directory / "engine-sections.csv").open(encoding="utf-8-sig", newline="") as stream:
        loads = [{**row, "startNanos": int(row["startNanos"]), "endNanos": int(row["endNanos"])}
                 for row in csv.DictReader(stream) if row["stage"] == "load_map"]
    with (directory / (name + "-trace.csv")).open(encoding="utf-8-sig", newline="") as stream:
        trace = [{key: int(value) for key, value in row.items()} for row in csv.DictReader(stream)]
    fresh = [row for i, row in enumerate(trace) if i == 0 or (row["generation"], row["sequence"]) != (trace[i - 1]["generation"], trace[i - 1]["sequence"])]
    epochs = []
    for load in loads:
        frame = next((row for row in frames if row["frameStartNanos"] <= load["startNanos"] <= load["endNanos"] <= row["frameEndNanos"]), None)
        if frame is None:
            continue
        reload = next((row for row in reloads if load["endNanos"] <= row["atNanos"] <= load["endNanos"] + 2_000_000_000), None)
        if reload is None:
            raise ValueError("Owner load_map has no nearby actual map-reload completion event")
        first_post = next((row for row in fresh if row["acceptedPresentNanos"] >= reload["atNanos"]), None)
        if first_post is None:
            raise ValueError("Reload epoch has no subsequent accepted fresh presentation")
        epochs.append({"startNanos": frame["frameStartNanos"], "endNanos": first_post["acceptedPresentNanos"],
            "tick": frame["tick"], "ownerFrameEndNanos": frame["frameEndNanos"], "loadMapStartNanos": load["startNanos"],
            "loadMapEndNanos": load["endNanos"], "actualMapReloadAtNanos": reload["atNanos"],
            "firstPostReloadGeneration": first_post["generation"], "firstPostReloadSequence": first_post["sequence"]})
    is_reload_gap = lambda gap: any(gap["startNanos"] < epoch["endNanos"] and gap["endNanos"] > epoch["startNanos"] for epoch in epochs)
    intervals = [{"startNanos": a["acceptedPresentNanos"], "endNanos": b["acceptedPresentNanos"],
                  "durationMs": (b["acceptedPresentNanos"] - a["acceptedPresentNanos"]) / 1e6}
                 for a, b in zip(fresh, fresh[1:]) if inside(b["acceptedPresentNanos"], windows)]
    normal = [gap for gap in intervals if not is_reload_gap(gap)]
    excluded = [gap for gap in attribution["gaps"] if gap["measuredWindow"] and is_reload_gap(gap)]
    normal_gaps = [gap for gap in attribution["gaps"] if gap["measuredWindow"] and not is_reload_gap(gap)]
    with (directory / "vulkan-stages.csv").open(encoding="utf-8-sig", newline="") as stream:
        native = [{key: row[key] if key == "stage" else int(row[key]) for key in row} for row in csv.DictReader(stream)]
    for gap in normal_gaps:
        selected = [row for row in native if row["startNanos"] < gap["endNanos"] and row["endNanos"] > gap["startNanos"]]
        gap["longestNativeOrUploadCalls"] = [{**row, "durationMs": (row["endNanos"] - row["startNanos"]) / 1e6,
            "overlapMs": max(0, min(row["endNanos"], gap["endNanos"]) - max(row["startNanos"], gap["startNanos"])) / 1e6}
            for row in sorted(selected, key=lambda row: row["endNanos"] - row["startNanos"], reverse=True)[:4]]
    raw_jfr = [json.loads(line) for line in (directory / "compact-profile.ndjson").open(encoding="utf-8")]
    clock = ClockMap(raw_jfr)
    detail_samples = []
    for event in raw_jfr:
        if event.get("type") not in ("jdk.ExecutionSample", "jdk.NativeMethodSample") or event.get("thread") not in ("main", "RWX-engine-owner"):
            continue
        mapped = clock.event(event)
        for gap in normal_gaps:
            if gap["startNanos"] <= mapped["startNanos"] < gap["endNanos"]:
                detail_samples.append({"freshGapMs": gap["durationMs"], "freshGapStartNanos": gap["startNanos"],
                    "thread": event["thread"], "startNanos": mapped["startNanos"],
                    "clockExtrapolated": mapped["clockExtrapolated"], "type": event["type"], "stack": event.get("stack", [])})
    clipped_epochs = [(max(epoch["startNanos"], w["sampleStartNanos"]), min(epoch["endNanos"], w["sampleEndNanos"]))
        for epoch in epochs for w in windows if epoch["startNanos"] < w["sampleEndNanos"] and epoch["endNanos"] > w["sampleStartNanos"]]
    threshold_counts = lambda rows: {str(t): sum(row["durationMs"] > t for row in rows) for t in (33.333333, 50, 100)}
    return {"source": str(directory), "runtimeSha256": report["runtimeSha256"], "validMeasurement": run["validMeasurement"],
        "clockCalibration": clock.diagnostics(), "originalMeasurementWindows": run["windows"], "mergedMeasurementIntervals": windows,
        "measurementSeconds": sum(w["sampleEndNanos"] - w["sampleStartNanos"] for w in windows) / 1e9,
        "reloadEpochs": epochs, "measuredReloadEpochSeconds": union(clipped_epochs) / 1e9,
        "normalMeasuredSeconds": (sum(w["sampleEndNanos"] - w["sampleStartNanos"] for w in windows) - union(clipped_epochs)) / 1e9,
        "rawFreshThresholdCounts": threshold_counts(intervals), "normalFreshThresholdCounts": threshold_counts(normal),
        "normalPooledFreshIntervalMs": dist([row["durationMs"] for row in normal]),
        "excludedReloadGaps": excluded, "normalGaps": normal_gaps, "normalGapFullExecutionSampleStacks": detail_samples,
        "presentationEpochChangedDuringRun": any(b["generation"] != a["generation"] or b["sequence"] < a["sequence"] for a, b in zip(trace, trace[1:])),
        "limits": ["Reload exclusion starts at the owning load_map frame and ends at the first fresh acceptance after the actual map-reload completion; excludes any crossing fresh interval, never truncates it into an artificial shorter normal interval.",
            "Presentation generation/sequence may remain monotonic through replay resync; actual owner loader and scenario reload markers define reload epochs here.",
            "This is one diagnostic process, not an A/B performance comparison. Full JFR stacks are sample points, not continuous execution time or proof that the sampled method caused the entire gap.",
            "Clock interpolation uses both JFR/mono marker endpoints; residuals and extrapolation flags are retained. Native and owner CSV spans already share QPC.",
            "Owner phase and Vulkan overlaps are non-exclusive wall-time evidence; ThreadMXBean CPU is quantized, and unlogged between-row time includes tasks, cache work, UI and pacing."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    result = analyze(directory)
    output = directory / "normal-replay-gap-attribution.json"
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"output": str(output), **{key: result[key] for key in ("measurementSeconds", "measuredReloadEpochSeconds", "normalMeasuredSeconds", "rawFreshThresholdCounts", "normalFreshThresholdCounts", "normalPooledFreshIntervalMs", "presentationEpochChangedDuringRun")}}, indent=2))
    for gap in result["normalGaps"][:4]:
        print(json.dumps({"freshMs": gap["durationMs"], "startNanos": gap["startNanos"], "endNanos": gap["endNanos"],
            "ownerFrames": gap["longestOwnerFrames"], "ownerBetween": gap["longestBetweenOwnerRows"],
            "rendererLeafSamples": gap["rendererSampleLeafCounts"], "native": gap["longestNativeCalls"], "cache": gap["cache"]["count"],
            "gcPauseMs": gap["gcPauseOverlapUnionMs"], "ownerWaits": gap["ownerWaits"]}, ensure_ascii=False))
