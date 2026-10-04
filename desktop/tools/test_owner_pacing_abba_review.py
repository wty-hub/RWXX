"""Tiny synthetic fixtures only: no actual game CSV, JVM or ongoing run is read."""
import io
import json
import runpy
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
review = runpy.run_path(str(Path(__file__).with_name("analyze_owner_pacing_abba.py")))
SECOND = 1_000_000_000


def fixture(comparison="legacy-short"):
    root, files, runs = Path("synthetic-owner-abba"), {}, []
    for index, variant in enumerate(("baseline", "candidate", "candidate", "baseline"), 1):
        target = root / f"{index:02d}-{variant}"
        runs.append({"variant": variant, "name": "live-pan", "validMeasurement": True,
            "exitCode": 0, "timedOut": False, "argv": ["java.exe"],
            "windows": [{"sampleStartNanos": 10 * SECOND, "sampleEndNanos": 30 * SECOND + 2_000_000},
                        {"sampleStartNanos": 30 * SECOND, "sampleEndNanos": 50 * SECOND}],
            "resourceSamples": [{"epochMillis": 100_000 + second * 1000, "pythonMonotonicNanos": second * SECOND,
                "javaCpuPercent": 10, "systemCpuPercent": 25, "availablePhysicalMemoryMiB": 1000 + second,
                "javaWorkingSetMiB": 100 + second, "javaPrivateMiB": 200 + second}
                for second in (9, 10, 20, 30, 50, 51)]})
        files[str(target / "diagnostic-summary.json")] = json.dumps({"runtimeSha256": "a" * 64,
            "diagnosticOnly": False, "diagnosticMode": "none", "protocol": {
                "mode": "moving", "teams": 15, "fog": "off", "units": 500, "recordReplay": False,
                "cameraMode": "pan", "cameraPeriodSeconds": 1, "warmupSeconds": 20, "sampleSeconds": 20,
                "repetitions": 2, "perfWindowLogRequested": False, "nativeBgraUploadsRequested": False,
                "hybridOwnerPacingRequested": variant == "candidate"}})
        enabled = "true" if variant == "candidate" else "false"
        files[str(target / "live-pan.log")] = (
            f"RWXOwnerPacing hybrid={enabled} spinBudgetNanos=1500000 maxHybridPeriodNanos=3333333\n"
            "RWXVulkanConfiguration framebuffer=1600x900 nativeBgraUploads=false\n")
        if comparison in ("guard", "text-reuse"):
            diagnostic_path = str(target / "diagnostic-summary.json")
            diagnostic = json.loads(files[diagnostic_path])
            diagnostic["protocol"].update({"hybridOwnerPacingRequested": True,
                "legacyShortOwnerParkRequested": variant == "baseline" if comparison == "guard" else True,
                "shortParkGuardRequested": comparison == "guard" and variant == "candidate"})
            if comparison == "text-reuse":
                diagnostic["protocol"]["textMeshReuseRequested"] = variant == "candidate"
            files[diagnostic_path] = json.dumps(diagnostic)
            guard = "true" if comparison == "guard" and variant == "candidate" else "false"
            files[str(target / "live-pan.log")] = (
                "RWXOwnerPacing hybrid=true spinBudgetNanos=1500000 maxHybridPeriodNanos=3333333 "
                f"shortParkGuard={guard} minCoarseParkNanos=1500000\n"
                "RWXVulkanConfiguration framebuffer=1600x900 nativeBgraUploads=false\n")
            if comparison == "text-reuse":
                text_enabled = "true" if variant == "candidate" else "false"
                files[str(target / "live-pan.log")] += f"RWXTextMeshReuse enabled={text_enabled}\n"
                files[str(target / "live-pan-frame.jsonl")] = "\n".join(json.dumps({
                    "sampleNanos": second * SECOND, "textMeshCache": {
                        "created": second, "exactHits": second * 4,
                        "reused": second * 2 if variant == "candidate" else 0,
                        "pruned": second // 2, "scanCandidates": second * 3 if variant == "candidate" else 0,
                        "live": index % 3 + 1, "peak": 3}})
                    for index, second in enumerate((9, 15, 25, 35, 45, 51)))
        files[str(target / "live-pan-scenario.ndjson")] = "\n".join(json.dumps(row) for row in [
            {"kind": "scenario", "localMapFixture": True, "fogDisplayEnabled": False, "mapFogEnabled": False,
             "cameraMode": "pan", "cameraPeriodSeconds": 1, "viewportWidth": 1600, "viewportHeight": 900},
            *[{"kind": "engine-window", "minimumLivingUnits": 500, "maximumLivingUnits": 500,
               "framesWithMovingUnits": 100} for _ in range(2)]])
        files[str(target / "live-pan-trace.csv")] = "acceptedPresentNanos,generation,sequence\n" + "\n".join(
            f"{point},0,{sequence}" for point, sequence in [(9_990_000_000, 0), (10_010_000_000, 1),
                (10_020_000_000, 2), (30_000_000_000, 3), (30_001_000_000, 4), (50_000_000_000, 5)]) + "\n"
        header = "frameStartNanos,frameEndNanos,epochMillisAtEnd,tick,ownerCpuNanos,visiblePendingRedraws,workNanos,updateNanos,drawNanos,layerRedrawNanos,snapshotNanos\n"
        files[str(target / "engine.csv")] = header + "\n".join(
            f"{start},{start + 1_000_000},{100_000 + (start + 1_000_000) // 1_000_000},{tick},{15_625_000 if tick % 2 else 0},0,500000,200000,300000,0,500000"
            for tick, start in enumerate((9_990_000_000, 10_010_000_000, 10_020_000_000,
                                         29_990_000_000, 30_000_000_000, 49_990_000_000))) + "\n"
    files[str(root / "summary.json")] = json.dumps({"runs": runs, "comparison": {"validComparison": True,
        "matchingMeasuredViewports": True, "measuredViewports": [[1600, 900]], "medianNewFpsPercentChange": 1.2}})
    return root, files


def analyze(root, files, comparison="legacy-short"):
    with patch.object(Path, "read_text", lambda path, *args, **kwargs: files[str(path)]), \
         patch.object(Path, "open", lambda path, *args, **kwargs: io.StringIO(files[str(path)])), \
         patch.object(Path, "exists", lambda path: str(path) in files):
        return review["analyze"](root, comparison=comparison)


class OwnerPacingReviewTest(unittest.TestCase):
    def test_text_comparison_keeps_pacing_fixed_and_reports_only_covered_deltas(self):
        root, files = fixture("text-reuse")
        result = analyze(root, files, "text-reuse")
        self.assertTrue(result["validation"]["validControlledComparison"], result["validation"])
        for run in result["runs"]:
            self.assertTrue(run["actualOwnerPacing"][0]["hybrid"])
            self.assertFalse(run["actualOwnerPacing"][0]["shortParkGuard"])
            self.assertEqual(run["variant"] == "candidate", run["actualTextMeshReuse"][0])
            cache = run["textMeshCache"]
            self.assertEqual(51, cache["wholeProcessLast"]["textMeshCache"]["created"])
            self.assertEqual(30, cache["measured"]["counterDeltas"]["created"])
            self.assertEqual(3, cache["measured"]["pairCount"])
            self.assertEqual(30, cache["measured"]["coveredSeconds"])
            self.assertEqual(0.75, cache["measured"]["coverageFraction"])
            self.assertEqual(2, len(cache["measured"]["excludedAdjacentPairs"]))
            self.assertNotIn("live", cache["measured"]["counterDeltas"])
        for variant, reuse_delta in (("baseline", 0), ("candidate", 120)):
            cache = result["variants"][variant]["textMeshCache"]
            self.assertEqual(2, len(cache["wholeProcessLastByRun"]))
            self.assertEqual(60, cache["measured"]["coveredSeconds"])
            self.assertEqual(80, cache["measured"]["measurementUnionSeconds"])
            self.assertEqual(reuse_delta, cache["measured"]["counterDeltas"]["reused"])

    def test_text_requested_policy_does_not_replace_actual_mesh_or_pacing_markers(self):
        for original, changed, expected_error in (
                ("RWXTextMeshReuse enabled=true", "", "actual text mesh reuse"),
                ("RWXTextMeshReuse enabled=true", "RWXTextMeshReuse enabled=false", "actual text mesh reuse"),
                ("shortParkGuard=false", "shortParkGuard=true", "actual shortParkGuard")):
            with self.subTest(changed=changed):
                root, files = fixture("text-reuse")
                path = str(root / "02-candidate/live-pan.log")
                files[path] = files[path].replace(original, changed)
                result = analyze(root, files, "text-reuse")
                self.assertFalse(result["validation"]["validControlledComparison"])
                self.assertTrue(any(expected_error in error for error in result["validation"]["errors"]))

    def test_text_metrics_require_timestamps_and_monotonic_counters(self):
        for mutation in ("missing-time", "counter-reset"):
            with self.subTest(mutation=mutation):
                root, files = fixture("text-reuse")
                path = str(root / "03-candidate/live-pan-frame.jsonl")
                points = [json.loads(line) for line in files[path].splitlines()]
                if mutation == "missing-time":
                    points[2].pop("sampleNanos")
                else:
                    points[2]["textMeshCache"]["created"] = 1
                files[path] = "\n".join(json.dumps(point) for point in points)
                result = analyze(root, files, "text-reuse")
                self.assertFalse(result["validation"]["validControlledComparison"])
                self.assertTrue(any("text mesh" in error for error in result["validation"]["errors"]))

    def test_text_metrics_do_not_join_endpoints_from_separate_measurement_intervals(self):
        path = Path("synthetic-text-frame.jsonl")
        text = "\n".join(json.dumps({"sampleNanos": second * SECOND, "textMeshCache": {
            "created": second, "exactHits": second * 2, "reused": second, "pruned": second,
            "scanCandidates": second * 3, "live": index % 4, "peak": 3}})
            for index, second in enumerate((9, 15, 25, 45, 55, 61)))
        windows = [{"sampleStartNanos": 10 * SECOND, "sampleEndNanos": 30 * SECOND},
                   {"sampleStartNanos": 40 * SECOND, "sampleEndNanos": 60 * SECOND}]
        with patch.object(Path, "read_text", lambda *args, **kwargs: text), patch.object(Path, "exists", lambda path: True):
            result = review["text_mesh_metrics"](path, windows)
        self.assertEqual(2, result["measured"]["pairCount"])
        self.assertEqual(20, result["measured"]["counterDeltas"]["created"])
        self.assertEqual(0.5, result["measured"]["coverageFraction"])
        self.assertTrue(any(pair["startNanos"] == 25 * SECOND and pair["endNanos"] == 45 * SECOND
                            for pair in result["measured"]["excludedAdjacentPairs"]))

    def test_guard_viewport_uses_cohort_evidence_and_checks_each_actual_run(self):
        root, files = fixture("guard")
        summary_path = str(root / "summary.json")
        report = json.loads(files[summary_path])
        report["comparison"]["measuredViewports"] = [[1280, 720]]
        files[summary_path] = json.dumps(report)
        for index, variant in enumerate(("baseline", "candidate", "candidate", "baseline"), 1):
            target = root / f"{index:02d}-{variant}"
            log_path = str(target / "live-pan.log")
            files[log_path] = files[log_path].replace("framebuffer=1600x900", "framebuffer=1280x720")
            scenario_path = str(target / "live-pan-scenario.ndjson")
            scenario = [json.loads(line) for line in files[scenario_path].splitlines()]
            scenario[0].update({"viewportWidth": 1280, "viewportHeight": 720})
            files[scenario_path] = "\n".join(json.dumps(row) for row in scenario)
        self.assertTrue(analyze(root, files, "guard")["validation"]["validControlledComparison"])
        path = str(root / "04-baseline/live-pan.log")
        files[path] = files[path].replace("1280x720", "1600x900")
        result = analyze(root, files, "guard")
        self.assertFalse(result["validation"]["validControlledComparison"])
        self.assertTrue(any("framebuffer" in error for error in result["validation"]["errors"]))
        root, files = fixture("guard")
        path = str(root / "summary.json")
        report = json.loads(files[path])
        report["comparison"]["matchingMeasuredViewports"] = False
        files[path] = json.dumps(report)
        with self.assertRaisesRegex(ValueError, "matching.*viewport"):
            analyze(root, files, "guard")

    def test_guard_comparison_requires_hybrid_on_both_sides_and_keeps_aggregation(self):
        root, files = fixture("guard")
        result = analyze(root, files, "guard")
        self.assertTrue(result["validation"]["validControlledComparison"], result["validation"])
        self.assertEqual("guard", result["comparisonMode"])
        self.assertEqual([False, True, True, False],
            [run["actualOwnerPacing"][0]["shortParkGuard"] for run in result["runs"]])
        self.assertTrue(all(run["actualOwnerPacing"][0]["hybrid"] for run in result["runs"]))
        self.assertTrue(all(run["actualOwnerPacing"][0]["minCoarseParkNanos"] == 1_500_000 for run in result["runs"]))
        for variant in ("baseline", "candidate"):
            self.assertEqual(80, result["variants"][variant]["measurementSeconds"])
            self.assertEqual(8, result["variants"][variant]["measuredResources"]["count"])

    def test_guard_requested_intent_does_not_replace_actual_pacing_evidence(self):
        for original, changed, expected_error in (
                ("hybrid=true", "hybrid=false", "actual owner pacing"),
                ("shortParkGuard=true", "shortParkGuard=false", "actual shortParkGuard"),
                ("minCoarseParkNanos=1500000", "minCoarseParkNanos=1000000", "actual minCoarseParkNanos"),
                ("shortParkGuard=true minCoarseParkNanos=1500000", "", "actual shortParkGuard")):
            with self.subTest(changed=changed):
                root, files = fixture("guard")
                path = str(root / "02-candidate/live-pan.log")
                files[path] = files[path].replace(original, changed)
                result = analyze(root, files, "guard")
                self.assertFalse(result["validation"]["validControlledComparison"])
                self.assertTrue(any(expected_error in error for error in result["validation"]["errors"]))

    def test_guard_protocol_must_match_the_actual_single_variable_comparison(self):
        for key, changed in (("legacyShortOwnerParkRequested", False),
                             ("shortParkGuardRequested", True),
                             ("hybridOwnerPacingRequested", False)):
            with self.subTest(key=key):
                root, files = fixture("guard")
                path = str(root / "01-baseline/diagnostic-summary.json")
                diagnostic = json.loads(files[path])
                diagnostic["protocol"][key] = changed
                files[path] = json.dumps(diagnostic)
                result = analyze(root, files, "guard")
                self.assertFalse(result["validation"]["validControlledComparison"])
                self.assertTrue(any("protocol " + key in error for error in result["validation"]["errors"]))

    def test_explicit_comparison_modes_reject_mislabeled_experiments(self):
        for fixture_mode, analysis_mode in (("guard", "legacy-short"), ("legacy-short", "guard")):
            with self.subTest(fixture_mode=fixture_mode):
                root, files = fixture(fixture_mode)
                result = analyze(root, files, analysis_mode)
                self.assertFalse(result["validation"]["validControlledComparison"])
        root, files = fixture()
        with self.assertRaisesRegex(ValueError, "Unknown comparison"):
            analyze(root, files, "automatic")

    def test_overlapping_windows_count_freshness_and_resource_endpoints_once(self):
        root, files = fixture()
        result = analyze(root, files)
        self.assertTrue(result["validation"]["validControlledComparison"], result["validation"])
        self.assertEqual(1.2, result["primaryRunnerMedianWindowComparison"]["medianNewFpsPercentChange"])
        for variant in ("baseline", "candidate"):
            stats = result["variants"][variant]
            self.assertEqual(80, stats["measurementSeconds"])
            self.assertEqual(10, stats["pooledFreshIntervalMs"]["count"])
            self.assertEqual(8, stats["measuredResources"]["count"])
            self.assertEqual(1010, stats["measuredResources"]["minimumAvailablePhysicalMemoryMiB"])
            self.assertEqual(150, stats["measuredResources"]["peakJavaWorkingSetMiB"])
        run = result["runs"][0]
        self.assertEqual(2, run["rawWindowOverlapMs"])
        self.assertEqual(1, len(run["mergedMeasurementIntervals"]))
        self.assertEqual([10, 20, 30, 50], [row["pythonMonotonicNanos"] // SECOND for row in run["selectedResourceSamples"]])
        self.assertTrue(any("quantized" in limit for limit in result["limits"]))

    def test_actual_pacing_and_bgra_states_are_required(self):
        root, files = fixture()
        path = str(root / "02-candidate/live-pan.log")
        files[path] = files[path].replace("hybrid=true", "hybrid=false")
        path = str(root / "04-baseline/live-pan.log")
        files[path] = files[path].replace("nativeBgraUploads=false", "nativeBgraUploads=true")
        result = analyze(root, files)
        self.assertFalse(result["validation"]["validControlledComparison"])
        self.assertTrue(any("actual owner pacing" in error for error in result["validation"]["errors"]))
        self.assertTrue(any("actual Vulkan nativeBgra" in error for error in result["validation"]["errors"]))

    def test_jfr_and_wrong_framebuffer_are_rejected(self):
        root, files = fixture()
        files[str(root / "01-baseline/profile.jfr")] = "not opened"
        path = str(root / "03-candidate/live-pan.log")
        files[path] = files[path].replace("1600x900", "1920x1080")
        result = analyze(root, files)
        self.assertFalse(result["validation"]["validControlledComparison"])
        self.assertTrue(any("no JFR" in error for error in result["validation"]["errors"]))
        self.assertTrue(any("framebuffer" in error for error in result["validation"]["errors"]))

    def test_runtime_and_perf_logging_mismatches_are_rejected(self):
        root, files = fixture()
        path = str(root / "04-baseline/diagnostic-summary.json")
        files[path] = files[path].replace("a" * 64, "b" * 64).replace('"perfWindowLogRequested": false', '"perfWindowLogRequested": true')
        result = analyze(root, files)
        self.assertFalse(result["validation"]["sameRecordedRuntimeSha256"])
        self.assertTrue(any("perfWindowLogRequested" in error for error in result["validation"]["errors"]))

    def test_incomplete_abba_is_rejected_before_csv_reads(self):
        root, files = fixture()
        path = str(root / "summary.json")
        report = json.loads(files[path])
        report["runs"].pop()
        files[path] = json.dumps(report)
        with self.assertRaisesRegex(ValueError, "completed"):
            analyze(root, files)

    def test_owner_update_wall_and_blank_wait_are_distinct_without_causal_labels(self):
        def owner(start, end, update):
            return {"frameStartNanos": start, "frameEndNanos": end, "epochMillisAtEnd": 1,
                "tick": 1, "ownerCpuNanos": 0, "visiblePendingRedraws": 0,
                "workNanos": update, "updateNanos": update, "drawNanos": 0,
                "layerRedrawNanos": 0, "snapshotNanos": 0}
        rows = [owner(0, 2_000_000, 1_000_000), owner(10_000_000, 90_000_000, 75_000_000),
                owner(95_000_000, 101_000_000, 3_000_000)]
        gap = {"startNanos": 1_000_000, "endNanos": 100_000_000, "durationMs": 99}
        windows = [{"sampleStartNanos": 0, "sampleEndNanos": 200_000_000}]
        result = review["owner_gap"](gap, rows, [r["frameStartNanos"] for r in rows], [r["frameEndNanos"] for r in rows], windows)
        self.assertEqual("long-owner-row", result["classification"])
        self.assertEqual(75, result["maximumStagesMs"]["updateMs"])
        self.assertEqual(0, result["uncoveredTraceTimeMs"])
        rows[1] = owner(90_000_000, 92_000_000, 1_000_000)
        result = review["owner_gap"](gap, rows, [r["frameStartNanos"] for r in rows], [r["frameEndNanos"] for r in rows], windows)
        self.assertEqual("unlogged-between-owner-rows", result["classification"])
        self.assertEqual(88, result["maximumOwnerEndToNextStartOverlapMs"])
        self.assertNotIn("GPU", result["classification"])

    def test_old_resource_timestamp_falls_back_to_epoch_without_counting_outside(self):
        windows = [{"sampleStartNanos": 1_000_000, "sampleEndNanos": 2_000_000}]
        rows = [{"epochMillisAtEnd": 100, "frameEndNanos": 1_000_000}]
        selected = review["measured_resources"]([{"epochMillis": 99}, {"epochMillis": 100}, {"epochMillis": 101}, {"epochMillis": 102}], rows, windows)
        self.assertEqual([100, 101], [row["epochMillis"] for row in selected])
        self.assertTrue(all(row["timestampBasis"] == "epoch-ms-fallback" for row in selected))


if __name__ == "__main__":
    unittest.main()
