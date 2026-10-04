#!/usr/bin/env python3
"""Stream exported map-pan allocation/GC events, normalized by engine frames.

Inputs per directory: engine.csv, *-scenario.ndjson, allocations.ndjson (the
AllocationJfr compact export), and compact-profile.ndjson (CompactJfr export).
This tool never starts Java, exports JFR, or loads an NDJSON file into memory.

Example, after the diagnostic processes have stopped and exports are complete:
  python desktop/tools/analyze_map_pan_gc.py OLD_DIR NEW_DIR --output comparison.json

ObjectAllocationSample.weight estimates allocation bytes; neither weights nor
sample counts are exact allocation totals/counts. This is attribution evidence,
not an uninstrumented throughput comparison or proof of exclusive stall cause.
"""

import argparse
from collections import Counter
import csv
import json
from pathlib import Path


NANOS_PER_SECOND = 1_000_000_000
MIB = 1024 * 1024
FREEZE_METHOD = "io.github.rwx.render.canvas.KoolGraphicsEngine.freezeTextureForOffscreenDraw"
PIXEL_COPY_METHOD = "com.corrodinggames.rts.gameFramework.graphics.Texture.getArgbPixelsCopy"
REGISTER_ARGB_METHOD = "io.github.rwx.render.canvas.KoolCanvasCpuTextureStore.registerArgb"
ARRAY_COPY_METHOD = "java.util.Arrays.copyOf"
ALLOCATION_GROUPS = ("all", "intArray", "offscreenGetArgbPixelsCopy", "offscreenRegisterArgbCopy")
DIRECT_FIELDS = ("count", "totalCapacity", "memoryUsed", "maxCapacity")


def json_lines(path):
    with path.open(encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"Malformed JSON at {path}:{line_number}: {error.msg}") from error
            if not isinstance(row, dict):
                raise ValueError(f"Expected an event object at {path}:{line_number}")
            yield row


def integer(row, key):
    value = row[key]
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError(f"Expected integer {key}, got {value!r}")
    return value


def merged_intervals(intervals):
    merged = []
    for start, end in sorted(intervals):
        if end <= start:
            raise ValueError("A window/interval end must follow its start")
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def safe_divide(numerator, denominator):
    return numerator / denominator if denominator else None


def has_jvm_method(stack, qualified_name):
    # Kotlin value-class parameters mangle JVM names, e.g. registerArgb-Uzkpg2g.
    # Keep the complete class/method boundary; a generic copyOf elsewhere does
    # not satisfy the CPU-store/offscreen caller requirements.
    return any(method == qualified_name or method.startswith(qualified_name + "-") or
               method == qualified_name + "$default" for method in stack)


class SystemGc:
    def __init__(self):
        self.count = 0
        self.duration_nanos = 0
        self.max_nanos = 0
        self.stacks = Counter()

    def add(self, row):
        duration = integer(row, "duration")
        self.count += 1
        self.duration_nanos += duration
        self.max_nanos = max(self.max_nanos, duration)
        self.stacks[(row.get("thread"), tuple(row.get("stack", [])))] += 1

    def report(self):
        return {"count": self.count, "eventDurationSumMs": self.duration_nanos / 1e6,
                "maxEventDurationMs": self.max_nanos / 1e6,
                "stacks": [{"thread": thread, "stack": list(stack), "count": count}
                           for (thread, stack), count in self.stacks.most_common()]}


class Window:
    def __init__(self, name, intervals, scenario_window=None):
        self.name = name
        self.intervals = merged_intervals(intervals)
        self.duration_nanos = sum(end - start for start, end in self.intervals)
        self.scenario_window = scenario_window
        self.frames = 0
        self.allocation_samples = 0
        self.allocation_weights = Counter()
        self.pauses = 0
        self.pause_full_duration_nanos = 0
        self.pause_overlap_nanos = 0
        self.pause_max_full_nanos = 0
        self.pause_max_overlap_nanos = 0
        self.pause_intervals = []
        self.gc_started = Counter()
        self.gc_overlapping = Counter()
        self.system_gc = SystemGc()
        self.direct_samples = 0
        self.direct_ranges = {}
        self.direct_first = None
        self.direct_last = None

    def contains(self, nanos):
        return any(start <= nanos <= end for start, end in self.intervals)

    def overlaps(self, start, end):
        return [(max(start, left), min(end, right)) for left, right in self.intervals
                if max(start, left) < min(end, right)]

    def add_allocation(self, row):
        weight = integer(row, "weight")
        if weight < 0:
            raise ValueError("Negative ObjectAllocationSample weight")
        self.allocation_samples += 1
        self.allocation_weights["all"] += weight
        if row.get("class") not in ("[I", "int[]"):
            return
        self.allocation_weights["intArray"] += weight
        stack = row.get("stack", [])
        # Require the offscreen caller: other Arrays.copyOf/ARGB registrations
        # (assets, uploads, general Texture edits) must not enter these groups.
        if not has_jvm_method(stack, FREEZE_METHOD):
            return
        if has_jvm_method(stack, PIXEL_COPY_METHOD):
            self.allocation_weights["offscreenGetArgbPixelsCopy"] += weight
        if has_jvm_method(stack, REGISTER_ARGB_METHOD) and ARRAY_COPY_METHOD in stack:
            self.allocation_weights["offscreenRegisterArgbCopy"] += weight

    def add_profile(self, row, start):
        event_type = row["type"]
        if event_type in ("jdk.GCPhasePause", "jdk.GarbageCollection"):
            duration = integer(row, "duration")
            if duration < 0:
                raise ValueError(f"Negative duration for {event_type}")
            overlaps = self.overlaps(start, start + duration)
            if event_type == "jdk.GCPhasePause" and (overlaps or (duration == 0 and self.contains(start))):
                overlap = sum(end - begin for begin, end in overlaps)
                self.pauses += 1
                self.pause_full_duration_nanos += duration
                self.pause_overlap_nanos += overlap
                self.pause_max_full_nanos = max(self.pause_max_full_nanos, duration)
                self.pause_max_overlap_nanos = max(self.pause_max_overlap_nanos, overlap)
                self.pause_intervals.extend(overlaps)
            elif event_type == "jdk.GarbageCollection":
                key = (row.get("name"), row.get("cause"))
                if self.contains(start):
                    self.gc_started[key] += 1
                if overlaps or (duration == 0 and self.contains(start)):
                    self.gc_overlapping[key] += 1
        elif event_type == "jdk.SystemGC" and self.contains(start):
            self.system_gc.add(row)
        elif event_type == "jdk.DirectBufferStatistics" and self.contains(start):
            sample = {"atNanos": start, **{field: integer(row, field) for field in DIRECT_FIELDS}}
            self.direct_samples += 1
            for field in DIRECT_FIELDS:
                value = sample[field]
                low, high = self.direct_ranges.get(field, (value, value))
                self.direct_ranges[field] = (min(low, value), max(high, value))
            if self.direct_first is None or start < self.direct_first["atNanos"]:
                self.direct_first = sample
            if self.direct_last is None or start > self.direct_last["atNanos"]:
                self.direct_last = sample

    def report(self):
        seconds = self.duration_nanos / NANOS_PER_SECOND
        pause_union_nanos = sum(end - start for start, end in merged_intervals(self.pause_intervals))
        counts = lambda counter: [{"name": name, "cause": cause, "count": count}
                                  for (name, cause), count in counter.most_common()]
        result = {
            "name": self.name,
            "intervals": [{"startNanos": start, "endNanos": end} for start, end in self.intervals],
            "durationSeconds": seconds, "engineFrames": self.frames,
            "engineFramesPerSecond": safe_divide(self.frames, seconds),
            "allocationSamples": self.allocation_samples,
            "allocationEstimates": {
                group: {"weightedBytes": self.allocation_weights[group],
                        "estimatedMiBPerSecond": self.allocation_weights[group] / MIB / seconds,
                        "estimatedMiBPerEngineFrame": safe_divide(self.allocation_weights[group] / MIB, self.frames)}
                for group in ALLOCATION_GROUPS},
            "gcPhasePause": {
                "overlappingEventCount": self.pauses,
                "fullEventDurationSumMs": self.pause_full_duration_nanos / 1e6,
                "windowOverlapDurationSumMs": self.pause_overlap_nanos / 1e6,
                "windowOverlapUnionMs": pause_union_nanos / 1e6,
                "maxFullEventDurationMs": self.pause_max_full_nanos / 1e6,
                "maxWindowOverlapDurationMs": self.pause_max_overlap_nanos / 1e6,
                "windowPauseFraction": pause_union_nanos / self.duration_nanos,
                "windowPauseMsPerEngineFrame": safe_divide(pause_union_nanos / 1e6, self.frames)},
            "garbageCollection": {"startedEventCount": sum(self.gc_started.values()),
                                  "startedNameCauseCounts": counts(self.gc_started),
                                  "overlappingEventCount": sum(self.gc_overlapping.values()),
                                  "overlappingNameCauseCounts": counts(self.gc_overlapping)},
            "systemGC": self.system_gc.report(),
            "directBuffers": {"sampleCount": self.direct_samples,
                              "ranges": {field: {"minimum": low, "maximum": high}
                                         for field, (low, high) in self.direct_ranges.items()},
                              "firstSample": self.direct_first, "lastSample": self.direct_last},
        }
        if self.scenario_window is not None:
            result["scenarioFreshEngineFrames"] = self.scenario_window.get("freshEngineFrames")
            result["frameCountMatchesScenario"] = self.frames == self.scenario_window.get("freshEngineFrames")
        return result


def scenario_metadata(directory, summary):
    name = summary.get("run", {}).get("name")
    preferred = directory / f"{name}-scenario.ndjson" if name else None
    candidates = [preferred] if preferred and preferred.is_file() else list(directory.glob("*-scenario.ndjson"))
    if len(candidates) != 1:
        raise ValueError(f"Expected one scenario file in {directory}, found {len(candidates)}")
    setups, windows = [], []
    for row in json_lines(candidates[0]):
        if row.get("kind") == "scenario":
            setups.append(row)
        elif row.get("kind") == "engine-window":
            windows.append(row)
    if len(setups) != 1 or len(windows) != 2:
        raise ValueError(f"Expected one scenario and two engine windows in {candidates[0]}")
    windows.sort(key=lambda row: integer(row, "repetition"))
    if [row["repetition"] for row in windows] != [1, 2]:
        raise ValueError("Expected scenario repetitions 1 and 2")
    return candidates[0], setups[0], windows


def engine_frames_and_offset(path, windows):
    offsets = []
    previous_end = None
    duplicate_ends = 0
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            end = int(row["frameEndNanos"])
            if previous_end is not None and end < previous_end:
                raise ValueError(f"Engine frameEndNanos is not ordered in {path}")
            duplicate_ends += int(end == previous_end)
            previous_end = end
            offsets.append(int(row["epochMillisAtEnd"]) * 1_000_000 - end)
            for window in windows:
                if window.contains(end):
                    window.frames += 1
    if not offsets:
        raise ValueError(f"No engine frames in {path}")
    offsets.sort()
    # An integer median avoids loss of epoch nanoseconds through float conversion.
    middle = len(offsets) // 2
    median = offsets[middle] if len(offsets) % 2 else (offsets[middle - 1] + offsets[middle]) // 2
    return {"epochOffsetNs": median, "minimumOffsetNs": offsets[0], "maximumOffsetNs": offsets[-1],
            "offsetSpreadNs": offsets[-1] - offsets[0], "engineRows": len(offsets),
            "duplicateConsecutiveFrameEnds": duplicate_ends,
            "mapping": "monotonicNanos = eventEpochNanos - median(epochMillisAtEnd * 1e6 - frameEndNanos)",
            "uncertainty": "Epoch milliseconds are quantized and sampled after frame end; JFR boundary assignment is approximate. Engine frame counts use original monotonic timestamps."}


def analyze(directory):
    directory = directory.resolve()
    summary_path = directory / "diagnostic-summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8-sig")) if summary_path.is_file() else {}
    scenario_path, setup, scenario_windows = scenario_metadata(directory, summary)
    intervals = [(integer(row, "sampleStartNanos"), integer(row, "sampleEndNanos")) for row in scenario_windows]
    per_window = [Window(f"repetition-{row['repetition']}", [interval], row)
                  for row, interval in zip(scenario_windows, intervals)]
    measurement = Window("measurement-union", intervals)
    windows = per_window + [measurement]
    alignment = engine_frames_and_offset(directory / "engine.csv", windows)
    offset = alignment["epochOffsetNs"]
    allocation_path = directory / "allocations.ndjson"
    profile_path = directory / "compact-profile.ndjson"
    allocation_types = Counter()
    for row in json_lines(allocation_path):
        allocation_types[row.get("type")] += 1
        if row.get("type") != "jdk.ObjectAllocationSample":
            continue
        start = integer(row, "start") - offset
        for window in windows:
            if window.contains(start):
                window.add_allocation(row)
    profile_types = Counter()
    system_gc_phases = {phase: SystemGc() for phase in
                        ("initializationBeforeScenario", "warmup", "measurement", "betweenWindows", "afterMeasurement")}
    scenario_start = integer(setup, "startedAtNanos")
    measurement_start = measurement.intervals[0][0]
    measurement_end = measurement.intervals[-1][1]
    for row in json_lines(profile_path):
        event_type = row.get("type")
        profile_types[event_type] += 1
        if event_type not in ("jdk.GCPhasePause", "jdk.GarbageCollection", "jdk.SystemGC", "jdk.DirectBufferStatistics"):
            continue
        start = integer(row, "start") - offset
        for window in windows:
            window.add_profile(row, start)
        if event_type == "jdk.SystemGC":
            if measurement.contains(start):
                phase = "measurement"
            elif start < scenario_start:
                phase = "initializationBeforeScenario"
            elif start < measurement_start:
                phase = "warmup"
            elif start <= measurement_end:
                phase = "betweenWindows"
            else:
                phase = "afterMeasurement"
            system_gc_phases[phase].add(row)
    reports = [window.report() for window in per_window]
    warnings = []
    for report in reports:
        if report["scenarioFreshEngineFrames"] is not None and not report["frameCountMatchesScenario"]:
            warnings.append(f"{report['name']}: engine.csv frame count differs from scenario freshEngineFrames")
    if alignment["duplicateConsecutiveFrameEnds"]:
        warnings.append("engine.csv has consecutive duplicate frame ends; engineFrames counts recorded rows")
    if not measurement.allocation_samples:
        warnings.append("No ObjectAllocationSample events in the measurement; zero weights do not prove zero allocation")
    protocol_fog = summary.get("protocol", {}).get("fog")
    if protocol_fog in ("on", "off") and setup.get("fogDisplayEnabled") != (protocol_fog == "on"):
        warnings.append("Requested protocol fog disagrees with scenario fogDisplayEnabled; inspect scenario/production state before labeling this run")
    overlap_nanos = sum(end - start for start, end in intervals) - measurement.duration_nanos
    return {"directory": str(directory), "sources": {"scenario": str(scenario_path),
            "engine": str(directory / "engine.csv"), "allocation": str(allocation_path), "profile": str(profile_path)},
            "scenario": setup, "protocol": summary.get("protocol", {}),
            "runtimeSha256": summary.get("runtimeSha256", summary.get("runtime", {}).get("sha256")),
            "epochAlignment": alignment, "individualWindowOverlapNanos": overlap_nanos,
            "individualWindows": reports, "measurement": measurement.report(),
            "systemGCByPhase": {phase: stats.report() for phase, stats in system_gc_phases.items()},
            "recordingEventCounts": {"allocationExport": dict(allocation_types), "compactProfile": dict(profile_types)},
            "warnings": warnings}


def comparison(baseline, candidate):
    old, new = baseline["measurement"], candidate["measurement"]
    allocation = {}
    for group in ALLOCATION_GROUPS:
        allocation[group] = {}
        for metric in ("estimatedMiBPerSecond", "estimatedMiBPerEngineFrame"):
            left, right = old["allocationEstimates"][group][metric], new["allocationEstimates"][group][metric]
            ratio = safe_divide(right, left) if left is not None and right is not None else None
            allocation[group][metric] = {"baseline": left, "candidate": right,
                                         "candidateToBaselineRatio": ratio,
                                         "changePercent": (ratio - 1) * 100 if ratio is not None else None}
    mismatches = {}
    for field in ("map", "fogDisplayEnabled", "mapFogEnabled", "mapFogPeriodicMaintenanceEnabled",
                  "viewportWidth", "viewportHeight", "zoom", "cameraMode", "cameraPeriodSeconds",
                  "cameraRequestedAmplitude", "localMapFixture", "replayPath", "replaySpeed", "replayRate"):
        left, right = baseline["scenario"].get(field), candidate["scenario"].get(field)
        if left != right:
            mismatches[field] = {"baseline": left, "candidate": right}
    return {"baseline": baseline["directory"], "candidate": candidate["directory"],
            "allocationEstimateChanges": allocation, "scenarioMismatches": mismatches,
            "gcPhasePause": {"baseline": old["gcPhasePause"], "candidate": new["gcPhasePause"]},
            "engineFrames": {"baseline": old["engineFrames"], "candidate": new["engineFrames"]},
            "durationSeconds": {"baseline": old["durationSeconds"], "candidate": new["durationSeconds"]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("directories", type=Path, nargs="+", help="Completed diagnostic export directories; first is baseline")
    parser.add_argument("--output", type=Path, help="Write the full JSON report here; otherwise print it")
    args = parser.parse_args()
    try:
        runs = [analyze(directory) for directory in args.directories]
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Analysis failed: {error}\n")
    report = {
        "definitions": {
            "allocation": "JFR ObjectAllocationSample.weight estimates allocated bytes. Sample count is not an allocation count; weights are not exact ThreadAllocationStatistics deltas.",
            "offscreenCopies": "Only IntArray samples with freezeTextureForOffscreenDraw plus Texture.getArgbPixelsCopy, or CPUTextureStore.registerArgb plus Arrays.copyOf, enter the corresponding copy family. Categories are subsets of all/intArray and must not be summed together.",
            "frames": "Count engine.csv rows with frameEndNanos in each inclusive scenario window. Combined measurement is the union, so shared/overlapping window boundaries do not double-count.",
            "pauses": "GCPhasePause counts events overlapping a window. Full durations and clipped sums are separate; union excludes overlapping intervals. GarbageCollection (especially G1Old) may be concurrent; its duration is not a stop-the-world pause. Cause/name counts are provided by event start and overlap without inferring a pause-to-cause join.",
            "systemGC": "Phase uses event start: before scenario initialization, warmup before first sample, union measurement, gaps between samples, or after measurement. Event duration is not exclusive GC pause time.",
            "directBuffers": "Ranges and first/last are sampled DirectBufferStatistics inside the window; sparse periodic samples can miss transient peaks. Capacities/memoryUsed are bytes; count is buffers.",
            "comparison": "Compare identical scenario/viewport and instrumentation. Report both per second and per engine frame; engine frames are not fresh presentations. These diagnostics alone do not establish exclusive causality or an uninstrumented FPS improvement."},
        "runs": runs,
        "comparisons": [comparison(runs[0], candidate) for candidate in runs[1:]],
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
        print(json.dumps({"output": str(args.output.resolve()), "runs": [
            {"directory": run["directory"], "engineFrames": run["measurement"]["engineFrames"],
             "allocationEstimates": run["measurement"]["allocationEstimates"],
             "gcPhasePause": run["measurement"]["gcPhasePause"], "warnings": run["warnings"]}
            for run in runs]}, ensure_ascii=False))
    else:
        print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
