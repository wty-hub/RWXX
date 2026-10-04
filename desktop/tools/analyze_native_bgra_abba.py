"""Analyze a completed native-BGRA ABBA directory; never launches Java or a build."""
import argparse
import collections
import csv
import json
import math
import re
import statistics
from pathlib import Path

ORDER = ("baseline", "candidate", "candidate", "baseline")
THRESHOLDS = (16.667, 33.333, 50.0, 100.0)
RESOURCE_KEYS = ("systemCpuPercent", "javaCpuPercent", "availablePhysicalMemoryMiB",
                 "javaWorkingSetMiB", "javaPrivateMiB")


def dist(values):
    values = sorted(values)
    def p(q):
        if not values:
            return None
        index = (len(values) - 1) * q
        low = int(index)
        return values[low] + (values[min(low + 1, len(values) - 1)] - values[low]) * (index - low)
    return {"count": len(values), "min": min(values, default=None), "median": p(.5),
            "p95": p(.95), "p99": p(.99), "max": max(values, default=None)}


def fresh_intervals(path, windows):
    fresh = []
    previous = None
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            point = tuple(int(row[k]) for k in ("acceptedPresentNanos", "generation", "sequence"))
            if previous and point[0] < previous[0]:
                raise ValueError(f"Unordered presentation trace: {path}")
            if previous is None or point[1:] != previous[1:]:
                fresh.append(point)
            previous = point
    # Use a union of inclusive windows: an acceptance at a shared endpoint is counted once.
    return [{"startNanos": a[0], "endNanos": b[0], "durationMs": (b[0] - a[0]) / 1e6,
             "generationBefore": a[1], "generationAfter": b[1],
             "sequenceBefore": a[2], "sequenceAfter": b[2],
             "crossesLeftWindowBoundary": any(a[0] < w["sampleStartNanos"] <= b[0] <= w["sampleEndNanos"] for w in windows)}
            for a, b in zip(fresh, fresh[1:]) if inside(b[0], windows)]


def inside(t, windows):
    return any(w["sampleStartNanos"] <= t <= w["sampleEndNanos"] for w in windows)


def merge_windows(windows):
    merged = []
    for window in sorted(windows, key=lambda w: w["sampleStartNanos"]):
        start, end = window["sampleStartNanos"], window["sampleEndNanos"]
        if end <= start:
            raise ValueError("Require positive measurement windows")
        if merged and start <= merged[-1]["sampleEndNanos"]:
            merged[-1]["sampleEndNanos"] = max(merged[-1]["sampleEndNanos"], end)
        else:
            merged.append({"sampleStartNanos": start, "sampleEndNanos": end})
    return merged


def overlap_ns(start, end, windows):
    return sum(max(0, min(end, w["sampleEndNanos"]) - max(start, w["sampleStartNanos"])) for w in windows)


def read_pack_events(path):
    packs, work = [], {}
    seen_packs = set()
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            if row["stage"] not in ("argb-pack", "argb-pack-work"):
                continue
            event = {k: int(v) for k, v in row.items() if k != "stage"}
            start = event["startNanos"]
            if event["endNanos"] < start:
                raise ValueError(f"Negative pack event: {path}")
            if row["stage"] == "argb-pack":
                if start in seen_packs:
                    raise ValueError(f"Ambiguous pack start key: {start}")
                seen_packs.add(start)
                packs.append(event)
            else:
                if start in work:
                    raise ValueError(f"Ambiguous pack CPU start key: {start}")
                work[start] = event
    return packs, work


def measured_resources(path, resources, windows):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        offsets = [int(row["epochMillisAtEnd"]) * 1_000_000 - int(row["frameEndNanos"])
                   for row in csv.DictReader(stream)]
    if not offsets:
        return []
    offset = statistics.median(offsets)
    return [r for r in resources if inside(r["epochMillis"] * 1_000_000 - offset, windows)]


def pack_stats(packs, work, process_keys=False):
    def key(p):
        return (p["runKey"], p["startNanos"]) if process_keys else p["startNanos"]
    paired = [work[key(p)] for p in packs if key(p) in work]
    cpu_ns = [w["value1"] for w in paired]
    byte_count = sum(p["value0"] for p in packs)
    wall_ns = [p["endNanos"] - p["startNanos"] for p in packs]
    sizes = collections.Counter((p["value1"], p["value2"], p["value0"]) for p in packs)
    positive = [n for n in cpu_ns if n > 0]
    return {
        "packCount": len(packs), "packBytes": byte_count,
        "packByteSizes": [{"width": k[0], "height": k[1], "bytes": k[2], "calls": n}
                          for k, n in sorted(sizes.items())],
        "pairedWorkCount": len(paired), "packWorkLayoutFlags": dict(collections.Counter(w["value2"] for w in paired)),
        "packWallMs": dist([n / 1e6 for n in wall_ns]), "packThreadCpuMs": dist([n / 1e6 for n in cpu_ns]),
        "packWallTotalMs": sum(wall_ns) / 1e6, "packThreadCpuTotalMs": sum(cpu_ns) / 1e6,
        "packWallMsPerGiB": sum(wall_ns) / 1e6 / (byte_count / 2**30) if byte_count else None,
        "packThreadCpuMsPerGiB": sum(cpu_ns) / 1e6 / (byte_count / 2**30) if byte_count else None,
        "packWallAbove33MsCount": sum(n > 33_333_000 for n in wall_ns),
        "packCpuZeroCount": sum(n == 0 for n in cpu_ns), "packCpuNonzeroCount": len(positive),
        "packCpuPositiveDeltaGcdNanos": math.gcd(*positive) if positive else None,
    }


def attribute_gap(gap, packs, work, windows):
    events = [p for p in packs if p["startNanos"] < gap["endNanos"] and p["endNanos"] > gap["startNanos"]]
    def detail(p):
        cpu = work.get(p["startNanos"])
        return {"startNanos": p["startNanos"], "endNanos": p["endNanos"],
                "wallMs": (p["endNanos"] - p["startNanos"]) / 1e6,
                "bytes": p["value0"], "width": p["value1"], "height": p["value2"],
                "threadCpuFullEventMs": cpu["value1"] / 1e6 if cpu else None,
                "layoutFlag": cpu["value2"] if cpu else None}
    return {**gap, "argbPackCallCountOverlappingFullGap": len(events),
            "argbPackBytesFullOverlappingEvents": sum(p["value0"] for p in events),
            "argbPackThreadCpuFullOverlappingEventsMs": sum(work[p["startNanos"]]["value1"] for p in events if p["startNanos"] in work) / 1e6,
            "argbPackWallOverlapFullGapMs": sum(max(0, min(p["endNanos"], gap["endNanos"]) - max(p["startNanos"], gap["startNanos"])) for p in events) / 1e6,
            "argbPackWallOverlapInsideWindowsMs": sum(overlap_ns(max(p["startNanos"], gap["startNanos"]), min(p["endNanos"], gap["endNanos"]), windows) for p in events) / 1e6,
            "longestPacks": sorted((detail(p) for p in events), key=lambda p: p["wallMs"], reverse=True)[:3]}


def resource_stats(resources):
    return {"count": len(resources),
            **{k: dist([r[k] for r in resources if r.get(k) is not None]) for k in RESOURCE_KEYS}}


def analyze(directory):
    report = json.loads((directory / "summary.json").read_text(encoding="utf-8-sig"))
    runs = report.get("runs", [])
    if tuple(r.get("variant") for r in runs) != ORDER or not report.get("comparison", {}).get("validComparison"):
        raise ValueError("Require completed, valid baseline/candidate/candidate/baseline report")
    validation_errors, output_runs = [], []
    aggregate = {v: {"packs": [], "work": {}, "resources": [], "intervals": []} for v in set(ORDER)}
    for index, run in enumerate(runs, 1):
        variant = run["variant"]
        target = directory / f"{index:02d}-{variant}"
        if not run.get("validMeasurement") or run.get("exitCode") != 0 or run.get("timedOut"):
            raise ValueError(f"Invalid or unfinished measurement: {target.name}")
        diagnostic = json.loads((target / "diagnostic-summary.json").read_text(encoding="utf-8-sig"))
        source_windows = sorted(run["windows"], key=lambda w: w["sampleStartNanos"])
        if len(source_windows) != 2 or any(w["sampleEndNanos"] <= w["sampleStartNanos"] for w in source_windows):
            raise ValueError(f"Require two positive measured windows: {target.name}")
        # The harness schedules the next start but records the preceding window's actual
        # observation end, so small overlaps are legitimate. Every pooled statistic uses the union.
        windows = merge_windows(source_windows)
        raw_window_ns = sum(w["sampleEndNanos"] - w["sampleStartNanos"] for w in source_windows)
        union_window_ns = sum(w["sampleEndNanos"] - w["sampleStartNanos"] for w in windows)
        protocol = diagnostic.get("protocol", {})
        if (protocol.get("fog"), protocol.get("cameraMode"), protocol.get("cameraPeriodSeconds"), protocol.get("units"), protocol.get("teams")) != ("off", "pan", 1, 500, 15):
            validation_errors.append(f"{target.name}: expected moving 500/15/no-fog/period1 pan protocol")
        if protocol.get("mode") != "moving" or protocol.get("perfWindowLogRequested") is not False:
            validation_errors.append(f"{target.name}: expected moving fixture with performance-window logging disabled")
        log = (target / f"{run['name']}.log").read_text(encoding="utf-8", errors="replace")
        states = re.findall(r"RWXVulkanConfiguration[^\r\n]*\bnativeBgraUploads=(true|false)\b", log)
        expected_state = "true" if variant == "candidate" else "false"
        if not states or any(state != expected_state for state in states):
            validation_errors.append(f"{target.name}: actual Vulkan nativeBgraUploads state {states} != {expected_state}")
        packs_all, work_all = read_pack_events(target / "canvas-stages.csv")
        packs = [p for p in packs_all if inside(p["endNanos"], windows)]
        # Select by pack end; match CPU on shared start, rather than independently filtering its later trace end.
        work = {p["startNanos"]: work_all[p["startNanos"]] for p in packs if p["startNanos"] in work_all}
        stats = pack_stats(packs, work)
        expected_flag = 2 if variant == "candidate" else 0
        if not packs or stats["pairedWorkCount"] != len(packs) or stats["packWorkLayoutFlags"] != {expected_flag: len(packs)}:
            validation_errors.append(f"{target.name}: missing/ambiguous work pair or actual layout flags {stats['packWorkLayoutFlags']} != {expected_flag}")
        for p in packs:
            cpu = work.get(p["startNanos"])
            if p["value1"] <= 0 or p["value2"] <= 0 or p["value0"] != p["value1"] * p["value2"] * 4 or (cpu and (cpu["value0"] * 4 != p["value0"] or cpu["value1"] < 0)):
                validation_errors.append(f"{target.name}: invalid dimensions/bytes/CPU for pack {p['startNanos']}")
                break
        intervals = fresh_intervals(target / "live-pan-trace.csv", windows)
        resources = measured_resources(target / "engine.csv", run.get("resourceSamples", []), windows)
        gaps = [attribute_gap(g, packs_all, work_all, windows) for g in intervals if g["durationMs"] > 33.333]
        digest = diagnostic.get("runtimeSha256")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            validation_errors.append(f"{target.name}: missing valid recorded runtime SHA-256")
        output_runs.append({"run": target.name, "variant": variant, "runtimeSha256": digest,
            "actualVulkanNativeBgraStates": states, "measurementSeconds": union_window_ns / 1e9,
            "originalWindowRanges": [{k: w.get(k) for k in ("repetition", "sampleStartNanos", "sampleEndNanos")} for w in source_windows],
            "mergedMeasurementIntervals": windows, "rawWindowOverlapMs": (raw_window_ns - union_window_ns) / 1e6,
            "thresholdCounts": {str(t): sum(g["durationMs"] > t for g in intervals) for t in THRESHOLDS},
            "pooledFreshIntervalMs": dist([g["durationMs"] for g in intervals]), **stats,
            "measuredResources": resource_stats(resources), "largestFreshGaps": sorted(gaps, key=lambda g: g["durationMs"], reverse=True)[:5]})
        a = aggregate[variant]
        a["packs"].extend(packs)
        # Process-local nanoTime keys may coincide; keep source pairs attached by a synthetic run key.
        for p in packs:
            p["runKey"] = index
        a["work"].update({(index, key): value for key, value in work.items()})
        a["resources"].extend(resources)
        a["intervals"].extend(intervals)
    if len({r["runtimeSha256"] for r in output_runs}) != 1:
        validation_errors.append("Runtime digests differ; this is not a same-Jar ABBA")
    variants = {}
    for variant, a in aggregate.items():
        # Re-key only for the aggregate statistics; no timestamp matching is performed across JVMs.
        variants[variant] = {**pack_stats(a["packs"], a["work"], process_keys=True),
            "measurementSeconds": sum(r["measurementSeconds"] for r in output_runs if r["variant"] == variant),
            "thresholdCounts": {str(t): sum(g["durationMs"] > t for g in a["intervals"]) for t in THRESHOLDS},
            "pooledFreshIntervalMs": dist([g["durationMs"] for g in a["intervals"]]),
            "measuredResources": resource_stats(a["resources"])}
    workload_matched = all(variants["baseline"][key] == variants["candidate"][key] for key in ("packCount", "packBytes", "packByteSizes"))
    if not workload_matched:
        validation_errors.append("Measured pack counts, bytes or dimension histogram differ between variants")
    return {"source": str(directory), "validation": {"validControlledComparison": not validation_errors,
        "errors": validation_errors, "sameRecordedRuntimeSha256": len({r["runtimeSha256"] for r in output_runs}) == 1,
        "measuredPackWorkloadExactlyMatched": workload_matched}, "fpsComparison": report["comparison"],
        "variants": variants, "runs": output_runs,
        "decision": "No automatic verdict: assess actual gameplay distributions and pack wall time after validation passes.",
        "limits": [
            "Freshness is a generation/sequence change at accepted presentation, not physical scanout or physical mouse input latency.",
            "Only measured windows count; ending fresh intervals include left-boundary crossings and exclude unfinished right tails. Shared endpoints are counted once in pooled statistics.",
            "Observed window ends can overshoot the next scheduled window start; pooled durations, event membership and overlaps use the merged interval union. Original two windows and raw overlap are retained.",
            "Runner FPS/P99 are medians of window values; pooled percentiles here use linear interpolation and are a different aggregation.",
            "Pack call/byte summaries assign complete calls by pack end inside windows; CPU pairs use the same start key even if their later log row crosses a boundary.",
            "ThreadMXBean CPU time can be coarsely quantized: zero means unresolved CPU time, not free conversion. Positive-delta GCD is a descriptive value, not a calibrated CPU timer resolution.",
            "argb-pack-work CPU includes the first pack trace-row write and CPU-clock sampling overhead; it is not an isolated converter CPU timer. Full call CPU cannot be clipped to an overlapping gap.",
            "Pack wall overlaps bound temporal coincidence, not exclusive CPU causation; an event may include thread preemption. Boundary-crossing gap details also show the overlap clipped to measurement windows.",
            "Resource CPU samples span about five seconds; epoch/nanoTime conversion has millisecond rounding and cannot attribute subframe scheduling. No JFR/native wait attribution is supplied.",
            "Same-Jar validation compares recorded diagnostic digests; this analysis does not reread/hash large runtime jars.",
        ]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    result = analyze(directory)
    output = directory / "native-bgra-abba-review.json"
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output), "validation": result["validation"],
                      "variants": result["variants"]}, indent=2))
    raise SystemExit(0 if result["validation"]["validControlledComparison"] else 2)
