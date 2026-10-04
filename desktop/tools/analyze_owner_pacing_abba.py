"""Review completed owner-pacing ABBA data; never launches a JVM, game or build."""
import argparse
import bisect
import csv
import json
import math
import re
import statistics
from pathlib import Path

from analyze_native_bgra_abba import (ORDER, RESOURCE_KEYS, THRESHOLDS, dist,
                                     fresh_intervals, inside, merge_windows, overlap_ns)

OWNER_STAGES = ("workNanos", "updateNanos", "drawNanos", "layerRedrawNanos", "snapshotNanos")
COMPARISONS = ("legacy-short", "guard", "text-reuse")
TEXT_COUNTERS = ("created", "exactHits", "reused", "pruned", "scanCandidates")
TEXT_GAUGES = ("live", "peak")


def read_owner(path):
    rows = []
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for raw in csv.DictReader(stream):
            row = {key: int(raw[key]) for key in ("frameStartNanos", "frameEndNanos", "epochMillisAtEnd",
                "tick", "ownerCpuNanos", "visiblePendingRedraws", *OWNER_STAGES)}
            if row["frameEndNanos"] < row["frameStartNanos"] or (rows and row["frameStartNanos"] < rows[-1]["frameEndNanos"]):
                raise ValueError(f"Unordered or overlapping owner rows: {path}")
            rows.append(row)
    if not rows:
        raise ValueError(f"Missing owner rows: {path}")
    return rows


def row_detail(row):
    return {"startNanos": row["frameStartNanos"], "endNanos": row["frameEndNanos"],
        "frameSpanMs": (row["frameEndNanos"] - row["frameStartNanos"]) / 1e6,
        "tick": row["tick"], "ownerCpuMs": row["ownerCpuNanos"] / 1e6 if row["ownerCpuNanos"] >= 0 else None,
        "visiblePendingRedraws": row["visiblePendingRedraws"],
        **{key.removesuffix("Nanos") + "Ms": row[key] / 1e6 for key in OWNER_STAGES}}


def owner_gap(gap, rows, starts, ends, windows):
    start, end = gap["startNanos"], gap["endNanos"]
    first, last = bisect.bisect_right(ends, start), bisect.bisect_left(starts, end)
    selected = rows[first:last]
    between, cadence = [], []
    for i in range(max(0, first - 1), min(len(rows) - 1, last)):
        a, b = rows[i], rows[i + 1]
        left, right = a["frameEndNanos"], b["frameStartNanos"]
        clipped = max(0, min(right, end) - max(left, start))
        if clipped:
            between.append({"startNanos": left, "endNanos": right,
                "fullMs": (right - left) / 1e6, "overlapMs": clipped / 1e6})
        if a["frameStartNanos"] < end and b["frameStartNanos"] > start:
            cadence.append((b["frameStartNanos"] - a["frameStartNanos"]) / 1e6)
    details = sorted((row_detail(row) for row in selected), key=lambda row: row["frameSpanMs"], reverse=True)
    recorded_ns = sum(max(0, min(row["frameEndNanos"], end) - max(row["frameStartNanos"], start)) for row in selected)
    unlogged_ns = sum(round(item["overlapMs"] * 1e6) for item in between)
    max_row = max((row["frameSpanMs"] for row in details), default=0)
    max_blank = max((row["overlapMs"] for row in between), default=0)
    if recorded_ns + unlogged_ns < end - start:
        classification = "incomplete-owner-trace-coverage"
    elif max_row > 33.333 and max_blank > 33.333:
        classification = "long-owner-row-and-unlogged-between-row-gap"
    elif max_row > 33.333:
        classification = "long-owner-row"
    elif max_blank > 33.333:
        classification = "unlogged-between-owner-rows"
    else:
        classification = "recorded-owner-rows-and-spacing-short"
    return {**gap, "classification": classification,
        "freshGapInsideMeasurementMs": overlap_ns(start, end, windows) / 1e6,
        "ownerRowsOverlappingGap": len(selected), "maximumOwnerFrameSpanMs": max_row,
        "maximumOwnerStartToStartMs": max(cadence, default=None),
        "maximumOwnerEndToNextStartFullMs": max((r["fullMs"] for r in between), default=None),
        "maximumOwnerEndToNextStartOverlapMs": max_blank,
        "ownerRecordedSpanOverlapMs": recorded_ns / 1e6,
        "ownerUnloggedBetweenRowsOverlapMs": unlogged_ns / 1e6,
        "uncoveredTraceTimeMs": max(0, end - start - recorded_ns - unlogged_ns) / 1e6,
        "maximumStagesMs": {key.removesuffix("Nanos") + "Ms": max((row[key] for row in selected), default=0) / 1e6 for key in OWNER_STAGES},
        "largestOwnerRows": details[:3],
        "largestBetweenRowIntervals": sorted(between, key=lambda row: row["overlapMs"], reverse=True)[:3]}


def owner_stats(rows, windows):
    measured = [row for row in rows if inside(row["frameEndNanos"], windows)]
    spacing = [(b["frameStartNanos"] - a["frameEndNanos"]) / 1e6 for a, b in zip(rows, rows[1:])
               if inside(b["frameStartNanos"], windows)]
    cadence = [(b["frameStartNanos"] - a["frameStartNanos"]) / 1e6 for a, b in zip(rows, rows[1:])
               if inside(b["frameStartNanos"], windows)]
    cpu = [row["ownerCpuNanos"] for row in measured if row["ownerCpuNanos"] >= 0]
    positive = [value for value in cpu if value > 0]
    return {"frameSpanMs": dist([(r["frameEndNanos"] - r["frameStartNanos"]) / 1e6 for r in measured]),
        **{key.removesuffix("Nanos") + "Ms": dist([row[key] / 1e6 for row in measured]) for key in OWNER_STAGES},
        "endToNextStartMs": dist(spacing), "startToNextStartMs": dist(cadence),
        "ownerRecordedThreadCpuMs": dist([value / 1e6 for value in cpu]),
        "ownerRecordedCpuZeroCount": sum(value == 0 for value in cpu),
        "ownerRecordedCpuPositiveDeltaGcdNanos": math.gcd(*positive) if positive else None,
        "visiblePendingRedrawsMaximum": max((row["visiblePendingRedraws"] for row in measured), default=None)}


def measured_resources(samples, rows, windows):
    # Python monotonic_ns and HotSpot nanoTime use the same Windows QPC timebase.
    # Prefer that exact timestamp; old samples without QPC fall back to epoch-ms alignment.
    epoch_offset = statistics.median(row["epochMillisAtEnd"] * 1_000_000 - row["frameEndNanos"] for row in rows)
    selected = []
    for sample in samples:
        timestamp = sample.get("pythonMonotonicNanos")
        basis = "windows-QPC" if timestamp is not None else "epoch-ms-fallback"
        if timestamp is None:
            timestamp = sample["epochMillis"] * 1_000_000 - epoch_offset
        if inside(timestamp, windows):
            selected.append({**sample, "measurementTimestampNanos": timestamp, "timestampBasis": basis})
    return selected


def resource_stats(samples):
    return {"count": len(samples),
        **{key: dist([row[key] for row in samples if row.get(key) is not None]) for key in RESOURCE_KEYS},
        "minimumAvailablePhysicalMemoryMiB": min((r["availablePhysicalMemoryMiB"] for r in samples if r.get("availablePhysicalMemoryMiB") is not None), default=None),
        "peakJavaWorkingSetMiB": max((r["javaWorkingSetMiB"] for r in samples if r.get("javaWorkingSetMiB") is not None), default=None),
        "peakJavaPrivateMiB": max((r["javaPrivateMiB"] for r in samples if r.get("javaPrivateMiB") is not None), default=None)}


def text_mesh_metrics(path, windows):
    """Use cumulative-counter endpoints, never apportion a boundary-crossing sample."""
    if not path.exists():
        raise ValueError(f"Missing text mesh metrics: {path}")
    points, skipped = [], 0
    for line_number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        cache = row.get("textMeshCache")
        if cache is None:
            skipped += 1
            continue
        timestamp = row.get("sampleNanos")
        if not isinstance(timestamp, int) or isinstance(timestamp, bool) or timestamp < 0 or not isinstance(cache, dict) or any(
                not isinstance(cache.get(key), int) or isinstance(cache.get(key), bool) or cache[key] < 0
                for key in (*TEXT_COUNTERS, *TEXT_GAUGES)):
            raise ValueError(f"Invalid text mesh counter sample at {path}:{line_number}")
        point = {"sampleNanos": timestamp, "textMeshCache": {key: cache[key] for key in (*TEXT_COUNTERS, *TEXT_GAUGES)}}
        if points and (timestamp <= points[-1]["sampleNanos"] or any(
                cache[key] < points[-1]["textMeshCache"][key] for key in (*TEXT_COUNTERS, "peak"))):
            raise ValueError(f"Non-monotonic text mesh counter or timestamp at {path}:{line_number}")
        if cache["peak"] < cache["live"]:
            raise ValueError(f"Text mesh peak below live count at {path}:{line_number}")
        points.append(point)
    if not points:
        raise ValueError(f"No timestamped text mesh counters: {path}")
    pairs, excluded = [], []
    for first, last in zip(points, points[1:]):
        start, end = first["sampleNanos"], last["sampleNanos"]
        window_index = next((index for index, window in enumerate(windows)
                            if window["sampleStartNanos"] <= start < end <= window["sampleEndNanos"]), None)
        if window_index is None:
            excluded.append({"startNanos": start, "endNanos": end,
                "reason": "Both endpoints are not inside the same measured union interval"})
            continue
        pairs.append({"startNanos": start, "endNanos": end, "measurementUnionIntervalIndex": window_index,
            "coverageSeconds": (end - start) / 1e9,
            "counterDeltas": {key: last["textMeshCache"][key] - first["textMeshCache"][key] for key in TEXT_COUNTERS},
            "gaugesAtStart": {key: first["textMeshCache"][key] for key in TEXT_GAUGES},
            "gaugesAtEnd": {key: last["textMeshCache"][key] for key in TEXT_GAUGES}})
    if not pairs:
        raise ValueError(f"No text mesh delta pair has both endpoints inside a measured union interval: {path}")
    measured_ns = sum(window["sampleEndNanos"] - window["sampleStartNanos"] for window in windows)
    covered_ns = sum(pair["endNanos"] - pair["startNanos"] for pair in pairs)
    return {"pointCount": len(points), "skippedRecordsWithoutCache": skipped,
        "wholeProcessLast": points[-1],
        "measured": {"counterDeltas": {key: sum(pair["counterDeltas"][key] for pair in pairs) for key in TEXT_COUNTERS},
            "deltaPairs": pairs, "pairCount": len(pairs), "measurementUnionSeconds": measured_ns / 1e9,
            "coveredSeconds": covered_ns / 1e9, "coverageFraction": covered_ns / measured_ns,
            "excludedAdjacentPairs": excluded}}


def analyze(directory, comparison="legacy-short"):
    if comparison not in COMPARISONS:
        raise ValueError(f"Unknown comparison {comparison!r}; expected one of {COMPARISONS}")
    guard_comparison = comparison == "guard"
    text_comparison = comparison == "text-reuse"
    modern_comparison = guard_comparison or text_comparison
    report = json.loads((directory / "summary.json").read_text(encoding="utf-8-sig"))
    runs = report.get("runs", [])
    if tuple(run.get("variant") for run in runs) != ORDER or not report.get("comparison", {}).get("validComparison"):
        raise ValueError("Require completed valid baseline/candidate/candidate/baseline measurement")
    expected_viewport = (1600, 900)
    if modern_comparison:
        comparison_evidence = report["comparison"]
        viewports = comparison_evidence.get("measuredViewports", [])
        if comparison_evidence.get("matchingMeasuredViewports") is not True or len(viewports) != 1 or len(viewports[0]) != 2 or any(
                not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value <= 0 or int(value) != value
                for value in viewports[0]):
            raise ValueError(f"{comparison} comparison requires one matching positive integer measured viewport")
        expected_viewport = tuple(int(value) for value in viewports[0])
    errors, output_runs = [], []
    grouped = {variant: {"fresh": [], "resources": [], "ownerRows": [], "ownerSpacing": [], "ownerCadence": [], "longGaps": [], "textMesh": []} for variant in set(ORDER)}
    for index, run in enumerate(runs, 1):
        variant = run["variant"]
        target = directory / f"{index:02d}-{variant}"
        if run.get("exitCode") != 0 or not run.get("validMeasurement") or run.get("timedOut"):
            raise ValueError(f"Incomplete/invalid run: {target.name}")
        diagnostic = json.loads((target / "diagnostic-summary.json").read_text(encoding="utf-8-sig"))
        protocol = diagnostic.get("protocol", {})
        expected_protocol = {"mode": "moving", "teams": 15, "fog": "off", "units": 500,
            "cameraMode": "pan", "cameraPeriodSeconds": 1, "warmupSeconds": 20, "sampleSeconds": 20, "repetitions": 2,
            "recordReplay": False, "perfWindowLogRequested": False, "nativeBgraUploadsRequested": False,
            "hybridOwnerPacingRequested": modern_comparison or variant == "candidate"}
        if guard_comparison:
            expected_protocol.update({"legacyShortOwnerParkRequested": variant == "baseline",
                "shortParkGuardRequested": variant == "candidate"})
        elif text_comparison:
            expected_protocol.update({"legacyShortOwnerParkRequested": True, "shortParkGuardRequested": False,
                "textMeshReuseRequested": variant == "candidate"})
        else:
            # Jar13 lacks guard metadata. Newer jars must explicitly preserve the old
            # short-park policy when reproducing that comparison, rather than silently
            # changing both hybrid pacing and its coarse-wait guard.
            for key, expected in (("legacyShortOwnerParkRequested", True), ("shortParkGuardRequested", False)):
                if key in protocol:
                    expected_protocol[key] = expected
        for key, expected in expected_protocol.items():
            if protocol.get(key) != expected:
                errors.append(f"{target.name}: protocol {key}={protocol.get(key)!r}, expected {expected!r}")
        if diagnostic.get("diagnosticOnly") is not False or diagnostic.get("diagnosticMode") != "none" or diagnostic.get("jfrExport") is not None or (target / "profile.jfr").exists() or any("StartFlightRecording" in arg for arg in run.get("argv", [])):
            errors.append(f"{target.name}: primary run must have no JFR/diagnostic mode")
        log = (target / f"{run['name']}.log").read_text(encoding="utf-8", errors="replace")
        pacing = re.findall(r"RWXOwnerPacing\s+hybrid=(true|false)\s+spinBudgetNanos=(\d+)\s+maxHybridPeriodNanos=(\d+)", log)
        expected_pacing = ("true" if modern_comparison or variant == "candidate" else "false", "1500000", "3333333")
        if not pacing or any(tuple(row) != expected_pacing for row in pacing):
            errors.append(f"{target.name}: actual owner pacing {pacing} != {expected_pacing}")
        pacing_lines = re.findall(r"RWXOwnerPacing[^\r\n]*", log)
        if len(pacing) != len(pacing_lines):
            errors.append(f"{target.name}: unparseable actual owner pacing marker")
        guard_states = [re.search(r"\bshortParkGuard=(true|false)\b", line) for line in pacing_lines]
        coarse_limits = [re.search(r"\bminCoarseParkNanos=(\d+)\b", line) for line in pacing_lines]
        expected_guard = "true" if guard_comparison and variant == "candidate" else "false"
        if modern_comparison or any(state is not None or limit is not None for state, limit in zip(guard_states, coarse_limits)):
            if not pacing_lines or any(state is None or state.group(1) != expected_guard for state in guard_states):
                errors.append(f"{target.name}: actual shortParkGuard must be {expected_guard} for {comparison}")
            if not pacing_lines or any(limit is None or limit.group(1) != "1500000" for limit in coarse_limits):
                errors.append(f"{target.name}: actual minCoarseParkNanos must be 1500000 for {comparison}")
        text_states = re.findall(r"RWXTextMeshReuse\s+enabled=(true|false)\b", log) if text_comparison else []
        text_lines = re.findall(r"RWXTextMeshReuse[^\r\n]*", log) if text_comparison else []
        if text_comparison and (not text_states or len(text_states) != len(text_lines) or any(state != ("true" if variant == "candidate" else "false") for state in text_states)):
            errors.append(f"{target.name}: actual text mesh reuse must be {variant == 'candidate'}")
        vk_lines = re.findall(r"RWXVulkanConfiguration[^\r\n]*", log)
        states = [re.search(r"\bnativeBgraUploads=(true|false)\b", line) for line in vk_lines]
        sizes = [re.search(r"\bframebuffer=(\d+)x(\d+)\b", line) for line in vk_lines]
        if not vk_lines or any(state is None or state.group(1) != "false" for state in states):
            errors.append(f"{target.name}: actual Vulkan nativeBgraUploads must be false")
        if not vk_lines or any(size is None or tuple(int(value) for value in size.groups()) != expected_viewport for size in sizes):
            errors.append(f"{target.name}: actual Vulkan framebuffer must be {expected_viewport[0]}x{expected_viewport[1]}")
        scenario = [json.loads(line) for line in (target / "live-pan-scenario.ndjson").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        setups = [row for row in scenario if row.get("kind") == "scenario"]
        if len(setups) != 1 or any(setups[0].get(key) != expected for key, expected in {"localMapFixture": True, "fogDisplayEnabled": False, "mapFogEnabled": False, "cameraMode": "pan", "cameraPeriodSeconds": 1, "viewportWidth": expected_viewport[0], "viewportHeight": expected_viewport[1]}.items()):
            errors.append(f"{target.name}: actual no-fog local pan scenario or viewport mismatch")
        engine_windows = [row for row in scenario if row.get("kind") == "engine-window"]
        if len(engine_windows) != 2 or any(row.get("minimumLivingUnits") != 500 or row.get("maximumLivingUnits") != 500 or row.get("framesWithMovingUnits", 0) <= 0 for row in engine_windows):
            errors.append(f"{target.name}: require two actual moving 500-unit engine windows")
        original = sorted(run["windows"], key=lambda row: row["sampleStartNanos"])
        if len(original) != 2 or any(row["sampleEndNanos"] <= row["sampleStartNanos"] or abs((row["sampleEndNanos"] - row["sampleStartNanos"]) / 1e9 - 20) > 1 for row in original):
            raise ValueError(f"Require two positive approximately 20-second windows: {target.name}")
        windows = merge_windows(original)
        raw_ns = sum(row["sampleEndNanos"] - row["sampleStartNanos"] for row in original)
        union_ns = sum(row["sampleEndNanos"] - row["sampleStartNanos"] for row in windows)
        fresh = fresh_intervals(target / "live-pan-trace.csv", windows)
        rows = read_owner(target / "engine.csv")
        starts, ends = [row["frameStartNanos"] for row in rows], [row["frameEndNanos"] for row in rows]
        long_gaps = [owner_gap(gap, rows, starts, ends, windows) for gap in fresh if gap["durationMs"] > 33.333]
        resources = measured_resources(run.get("resourceSamples", []), rows, windows)
        if not resources or not any(row.get("javaCpuPercent") is not None and row.get("systemCpuPercent") is not None for row in resources):
            errors.append(f"{target.name}: no measured-window Java/system CPU samples")
        digest = diagnostic.get("runtimeSha256")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            errors.append(f"{target.name}: invalid recorded runtime SHA-256")
        actual_pacing = [{"hybrid": row[0] == "true", "spinBudgetNanos": int(row[1]), "maxHybridPeriodNanos": int(row[2])} for row in pacing]
        if modern_comparison or any(state is not None or limit is not None for state, limit in zip(guard_states, coarse_limits)):
            for actual, state, limit in zip(actual_pacing, guard_states, coarse_limits):
                actual.update({"shortParkGuard": state.group(1) == "true" if state else None,
                    "minCoarseParkNanos": int(limit.group(1)) if limit else None})
        output_runs.append({"run": target.name, "variant": variant, "runtimeSha256": digest,
            "actualOwnerPacing": actual_pacing,
            "actualVulkanNativeBgraStates": [state.group(1) if state else None for state in states],
            "measurementSeconds": union_ns / 1e9, "originalWindowRanges": [{key: row.get(key) for key in ("repetition", "sampleStartNanos", "sampleEndNanos")} for row in original],
            "mergedMeasurementIntervals": windows, "rawWindowOverlapMs": (raw_ns - union_ns) / 1e6,
            "thresholdCounts": {str(t): sum(gap["durationMs"] > t for gap in fresh) for t in THRESHOLDS},
            "pooledFreshIntervalMs": dist([gap["durationMs"] for gap in fresh]),
            "measuredResources": resource_stats(resources), "selectedResourceSamples": resources,
            "owner": owner_stats(rows, windows), "freshGapsAbove33Ms": long_gaps})
        if text_comparison:
            output_runs[-1]["actualTextMeshReuse"] = [state == "true" for state in text_states]
            try:
                text_metrics = text_mesh_metrics(target / f"{run['name']}-frame.jsonl", windows)
                output_runs[-1]["textMeshCache"] = text_metrics
                grouped[variant]["textMesh"].append({"run": target.name, **text_metrics})
            except ValueError as error:
                errors.append(f"{target.name}: {error}")
        group = grouped[variant]
        group["fresh"].extend(fresh)
        group["resources"].extend(resources)
        group["ownerRows"].extend(row for row in rows if inside(row["frameEndNanos"], windows))
        group["ownerSpacing"].extend((b["frameStartNanos"] - a["frameEndNanos"]) / 1e6 for a, b in zip(rows, rows[1:]) if inside(b["frameStartNanos"], windows))
        group["ownerCadence"].extend((b["frameStartNanos"] - a["frameStartNanos"]) / 1e6 for a, b in zip(rows, rows[1:]) if inside(b["frameStartNanos"], windows))
        group["longGaps"].extend({"run": target.name, **gap} for gap in long_gaps)
    same_jar = len({run["runtimeSha256"] for run in output_runs}) == 1
    if not same_jar:
        errors.append("Recorded runtime SHA-256 values differ")
    variants = {}
    for variant, group in grouped.items():
        owner = owner_stats(group["ownerRows"], [{"sampleStartNanos": 0, "sampleEndNanos": 2**63 - 1}])
        # Never create a synthetic interval between separate JVM runs.
        owner["endToNextStartMs"] = dist(group["ownerSpacing"])
        owner["startToNextStartMs"] = dist(group["ownerCadence"])
        variants[variant] = {"measurementSeconds": sum(run["measurementSeconds"] for run in output_runs if run["variant"] == variant),
            "thresholdCounts": {str(t): sum(gap["durationMs"] > t for gap in group["fresh"]) for t in THRESHOLDS},
            "pooledFreshIntervalMs": dist([gap["durationMs"] for gap in group["fresh"]]),
            "measuredResources": resource_stats(group["resources"]), "owner": owner,
            "freshGapsAbove33ClassificationCounts": {kind: sum(gap["classification"] == kind for gap in group["longGaps"])
                for kind in sorted({gap["classification"] for gap in group["longGaps"]})},
            "freshGapsAbove50Ms": sorted((gap for gap in group["longGaps"] if gap["durationMs"] > 50), key=lambda gap: gap["durationMs"], reverse=True)}
        if text_comparison:
            measured = [entry["measured"] for entry in group["textMesh"]]
            total_seconds = sum(entry["measurementUnionSeconds"] for entry in measured)
            covered_seconds = sum(entry["coveredSeconds"] for entry in measured)
            variants[variant]["textMeshCache"] = {"processCount": len(group["textMesh"]),
                "wholeProcessLastByRun": [{"run": entry["run"], **entry["wholeProcessLast"]} for entry in group["textMesh"]],
                "measured": {"counterDeltas": {key: sum(entry["counterDeltas"][key] for entry in measured) for key in TEXT_COUNTERS},
                    "pairCount": sum(entry["pairCount"] for entry in measured), "measurementUnionSeconds": total_seconds,
                    "coveredSeconds": covered_seconds, "coverageFraction": covered_seconds / total_seconds if total_seconds else None}}
    return {"source": str(directory), "comparisonMode": comparison,
        "validation": {"validControlledComparison": not errors, "sameRecordedRuntimeSha256": same_jar, "errors": errors},
        "primaryRunnerMedianWindowComparison": report["comparison"], "runs": output_runs, "variants": variants,
        "limits": [
            "Freshness is accepted generation/sequence change, not physical scanout or physical mouse input latency.",
            "Ending intervals count once in the merged measured-window union, including left-boundary crossings and excluding unfinished right tails; original windows and overlap are retained.",
            "Primary runner statistics are medians of the individual window values; pooled P95/P99 here use linearly interpolated percentiles and are a separate aggregation.",
            "Resource samples count only when their endpoint timestamp is inside the merged windows. CPU percentages span the preceding approximately five seconds across all logical CPUs; boundary samples may include earlier work. Memory min/peak are observed samples, not continuous extrema.",
            "Resource timestamps use Windows Python/HotSpot QPC when available; epoch-ms fallback has rounding uncertainty. No subframe cause can be inferred from these CPU samples.",
            "Owner ThreadMXBean CPU is quantized: zero means unresolved CPU time. Positive-delta GCD is descriptive, not a calibrated timer resolution. Recorded owner CPU excludes the waiting/spin tail, which is assessed with process CPU samples.",
            "Owner end-to-next-start includes unlogged postpublish offscreen work, UI publication, queued tasks, benchmark work and pacing; a blank interval is not proof of an OS park.",
            "Long owner update/draw/snapshot wall durations or coincident spacing describe paths, not CPU/GPU/GC causes. No JFR or native wait trace is present. Short owner rows during a fresh gap do not establish GPU causation.",
            "Owner gap details use complete boundary-crossing gaps and report measured overlap separately; stage durations are complete owner-row values, not clipped phase CPU times.",
            "No pack workload equality gate is applied: this primary comparison does not trace per-texture packing. Same-Jar validation compares recorded diagnostic digests without rereading large jars.",
            "Text mesh cumulative totals end at the last observed sample, not at JVM shutdown. Measured deltas include only adjacent counter samples with both endpoints inside the same merged measurement interval; excluded boundary chunks are not apportioned. Live and peak are gauges, not summed allocation counters.",
        ]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--comparison", choices=COMPARISONS, default="legacy-short",
        help="legacy-short: original outer park vs hybrid; guard: minimum coarse-park guard; text-reuse: reusable text meshes with the same hybrid short-park policy")
    args = parser.parse_args()
    directory = args.directory.resolve()
    result = analyze(directory, comparison=args.comparison)
    output = directory / "owner-pacing-abba-review.json"
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output), "validation": result["validation"], "variants": result["variants"]}, indent=2))
    raise SystemExit(0 if result["validation"]["validControlledComparison"] else 2)
