"""Tiny in-memory fixtures: no game, JVM, existing report or generated CSV is read."""
import io
import json
import runpy
import unittest
from pathlib import Path
from unittest.mock import patch

review = runpy.run_path(str(Path(__file__).with_name("analyze_native_bgra_abba.py")))


def fixture():
    root = Path("synthetic-native-bgra-abba")
    files, runs = {}, []
    for i, variant in enumerate(("baseline", "candidate", "candidate", "baseline"), 1):
        target = root / f"{i:02d}-{variant}"
        runs.append({"name": "live-pan", "variant": variant, "exitCode": 0,
            "validMeasurement": True, "timedOut": False, "resourceSamples": [{"epochMillis": 1}],
            "windows": [{"sampleStartNanos": 100, "sampleEndNanos": 200},
                        {"sampleStartNanos": 200, "sampleEndNanos": 300}]})
        files[str(target / "diagnostic-summary.json")] = json.dumps({"runtimeSha256": "a" * 64,
            "protocol": {"fog": "off", "cameraMode": "pan", "cameraPeriodSeconds": 1,
                "units": 500, "teams": 15, "mode": "moving", "perfWindowLogRequested": False}})
        flag = 2 if variant == "candidate" else 0
        enabled = "true" if flag == 2 else "false"
        files[str(target / "live-pan.log")] = f"RWXVulkanConfiguration framebuffer=1600x900 nativeBgraUploads={enabled}\n"
        files[str(target / "canvas-stages.csv")] = (
            "startNanos,endNanos,stage,value0,value1,value2\n"
            "1,2,engine-freeze,0,0,-1\n"
            "180,200,argb-pack,16,4,1\n"
            f"180,305,argb-pack-work,4,0,{flag}\n"
            "240,280,argb-pack,16,4,1\n"
            f"240,282,argb-pack-work,4,15625000,{flag}\n")
        files[str(target / "live-pan-trace.csv")] = (
            "acceptedPresentNanos,generation,sequence\n"
            "90,0,0\n110,0,1\n200,0,2\n250,0,2\n290,0,3\n320,0,4\n")
        files[str(target / "engine.csv")] = "epochMillisAtEnd,frameEndNanos\n1,100\n"
    files[str(root / "summary.json")] = json.dumps({"runs": runs, "comparison": {"validComparison": True}})
    return root, files


def analyze(root, files):
    def read_text(path, *args, **kwargs):
        return files[str(path)]
    def open_file(path, *args, **kwargs):
        return io.StringIO(files[str(path)])
    with patch.object(Path, "read_text", read_text), patch.object(Path, "open", open_file):
        return review["analyze"](root)


class NativeBgraReviewTest(unittest.TestCase):
    def test_shared_endpoint_counted_once_and_later_cpu_row_still_matches(self):
        root, files = fixture()
        result = analyze(root, files)
        self.assertTrue(result["validation"]["validControlledComparison"], result["validation"])
        for variant in ("baseline", "candidate"):
            stats = result["variants"][variant]
            self.assertEqual(6, stats["pooledFreshIntervalMs"]["count"])
            self.assertEqual(4, stats["packCount"])
            self.assertEqual(4, stats["pairedWorkCount"])
            self.assertEqual(64, stats["packBytes"])
            self.assertEqual(2, stats["packCpuZeroCount"])
            self.assertAlmostEqual(31.25, stats["packThreadCpuTotalMs"])
            self.assertAlmostEqual(.00012, stats["packWallTotalMs"])
        self.assertTrue(any("quantized" in limit for limit in result["limits"]))

    def test_candidate_legacy_work_flag_is_rejected(self):
        root, files = fixture()
        path = str(root / "02-candidate/canvas-stages.csv")
        files[path] = files[path].replace("argb-pack-work,4,0,2", "argb-pack-work,4,0,0")
        result = analyze(root, files)
        self.assertFalse(result["validation"]["validControlledComparison"])
        self.assertTrue(any("layout flags" in e for e in result["validation"]["errors"]))

    def test_missing_actual_vulkan_enable_proof_is_rejected(self):
        root, files = fixture()
        files[str(root / "03-candidate/live-pan.log")] = "requested vulkan only\n"
        result = analyze(root, files)
        self.assertFalse(result["validation"]["validControlledComparison"])
        self.assertTrue(any("actual Vulkan" in e for e in result["validation"]["errors"]))

    def test_mismatched_measured_pack_workload_and_runtime_are_rejected(self):
        root, files = fixture()
        path = str(root / "04-baseline/canvas-stages.csv")
        files[path] = files[path].replace("240,280,argb-pack,16,4,1", "240,280,argb-pack,32,8,1")
        files[path] = files[path].replace("240,282,argb-pack-work,4,", "240,282,argb-pack-work,8,")
        path = str(root / "04-baseline/diagnostic-summary.json")
        files[path] = files[path].replace("a" * 64, "b" * 64)
        result = analyze(root, files)
        self.assertFalse(result["validation"]["sameRecordedRuntimeSha256"])
        self.assertFalse(result["validation"]["measuredPackWorkloadExactlyMatched"])

    def test_partial_run_is_refused_before_trace_reads(self):
        root, files = fixture()
        path = str(root / "summary.json")
        report = json.loads(files[path])
        report["runs"].pop()
        files[path] = json.dumps(report)
        with self.assertRaisesRegex(ValueError, "completed"):
            analyze(root, files)

    def test_observed_window_overlap_is_merged_without_double_counting(self):
        root, files = fixture()
        path = str(root / "summary.json")
        report = json.loads(files[path])
        report["runs"][0]["windows"][0]["sampleEndNanos"] = 215
        files[path] = json.dumps(report)
        result = analyze(root, files)
        run = result["runs"][0]
        self.assertEqual(2, len(run["originalWindowRanges"]))
        self.assertEqual(1, len(run["mergedMeasurementIntervals"]))
        self.assertEqual(3, run["pooledFreshIntervalMs"]["count"])
        self.assertAlmostEqual(.000015, run["rawWindowOverlapMs"])
        self.assertAlmostEqual(200 / 1e9, run["measurementSeconds"])


if __name__ == "__main__":
    unittest.main()
