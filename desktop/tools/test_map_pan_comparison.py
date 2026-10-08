import json
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from map_pan_comparison import REPLAY_ALIAS, analyze_run, camera_evidence, copy_replay_alias, fresh_intervals, read_trace, replay_identity_evidence, run_environment


class MapPanComparisonTest(unittest.TestCase):
    def test_empty_process_arguments_use_verified_alias_and_real_load_log(self):
        with tempfile.TemporaryDirectory() as directory:
            alias = Path(directory) / REPLAY_ALIAS
            content = b"real replay bytes"
            alias.write_bytes(content)
            run = {"replayName": REPLAY_ALIAS, "replayAliasPath": str(alias),
                   "replayAliasSha256": hashlib.sha256(content).hexdigest(), "replayAliasVerified": True}
            setup = {"replayPath": ""}
            log = "Prepared DESKTOP KOOL RW replay asynchronously: comparison.replay (map loaded)"
            result = replay_identity_evidence(setup, run, log)
            self.assertTrue(result["confirmed"])
            self.assertEqual(result["source"], "verified-alias-and-load-log")
            self.assertEqual(result["scenarioReplayPath"], "")
            self.assertEqual(setup["replayPath"], "")
            self.assertFalse(replay_identity_evidence(setup, run, log + "\nReplay not found")["confirmed"])
            self.assertFalse(replay_identity_evidence({"replayPath": "wrong.replay"}, run, log)["confirmed"])
            (alias.parent / "other.replay").write_bytes(content)
            self.assertFalse(replay_identity_evidence(setup, run, log)["confirmed"])

    def test_unicode_source_is_copied_to_verified_ascii_alias(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "2080年欧洲回归🌎.replay"
            content = b"real replay bytes\x00\xff"
            source.write_bytes(content)
            target = copy_replay_alias(source, root / "sandbox", hashlib.sha256(content).hexdigest())
            self.assertEqual(target.name, "comparison.replay")
            self.assertEqual(target.name, REPLAY_ALIAS)
            self.assertTrue(target.name.isascii())
            self.assertEqual(target.read_bytes(), content)
            with self.assertRaises(RuntimeError):
                copy_replay_alias(source, root / "bad-sandbox", "incorrect-digest")

    def test_fresh_interval_crosses_left_boundary_and_ignores_repeats(self):
        trace = [(0, 1, 1), (1_000_000_000, 1, 1), (2_000_000_000, 1, 2),
                 (3_000_000_000, 1, 2), (4_000_000_000, 1, 3), (6_000_000_000, 1, 4)]
        result = fresh_intervals(trace, {"sampleStartNanos": 1_000_000_000, "sampleEndNanos": 5_000_000_000})
        self.assertEqual(result["freshSnapshotIntervalCount"], 2)
        self.assertEqual(result["freshSnapshotIntervalP95Ms"], 2000)
        self.assertEqual(result["freshIntervalLeftBoundaryCrossings"], 1)
        self.assertEqual(result["previousFreshAcceptanceNanos"], 0)
        self.assertEqual(result["rightBoundaryUnfinishedIntervalMs"], 1000)

    def test_generation_change_is_fresh_but_first_observation_has_no_interval(self):
        result = fresh_intervals([(0, 1, 5), (10_000_000, 2, 5)],
                                 {"sampleStartNanos": 0, "sampleEndNanos": 10_000_000})
        self.assertEqual(result["freshSnapshotIntervalCount"], 1)
        self.assertEqual(result["freshSnapshotIntervalP99Ms"], 10)
        self.assertEqual(result["freshIntervalLeftBoundaryCrossings"], 0)

    def test_empty_fresh_window_has_no_fabricated_interval(self):
        result = fresh_intervals([(0, 1, 5), (20, 1, 5)], {"sampleStartNanos": 1, "sampleEndNanos": 20})
        self.assertEqual(result["freshSnapshotIntervalCount"], 0)
        self.assertIsNone(result["freshSnapshotIntervalP95Ms"])
        self.assertIsNone(result["firstFreshAcceptanceNanos"])
        self.assertEqual(result["rightBoundaryUnfinishedIntervalMs"], 20 / 1e6)

    def test_camera_flag_alone_does_not_prove_movement(self):
        setup = {"cameraMode": "pan"}
        self.assertFalse(camera_evidence(setup, {"cameraMode": "pan"})["cameraMovementConfirmed"])
        self.assertFalse(camera_evidence(setup, {"cameraMode": "pan", "cameraSpanX": 2, "cameraSpanY": 0})["cameraMovementConfirmed"])
        self.assertTrue(camera_evidence(setup, {"cameraMode": "pan", "cameraSpanX": 2, "cameraSpanY": 3})["cameraMovementConfirmed"])
        self.assertTrue(camera_evidence({"cameraMode": "jump"}, {"cameraMode": "jump", "cameraSpanX": 2, "cameraSpanY": 3})["cameraMovementConfirmed"])
        self.assertFalse(camera_evidence(setup, {"cameraMode": "jump", "cameraSpanX": 2, "cameraSpanY": 3})["cameraMovementConfirmed"])

    def test_bad_trace_or_window_is_rejected(self):
        with self.assertRaises(ValueError):
            fresh_intervals([(20, 1, 1), (10, 1, 2)], {"sampleStartNanos": 0, "sampleEndNanos": 30})
        with self.assertRaises(ValueError):
            fresh_intervals([], {"sampleStartNanos": 10, "sampleEndNanos": 10})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.csv"
            path.write_text("acceptedPresentNanos,generation,sequence\n1,2\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                read_trace(path)

    def test_inherited_diagnostics_and_settings_are_removed(self):
        inherited = {"RWX_VK_METRICS": "old.json", "RWX_CANVAS_PERF": "1", "RWX_BENCHMARK_UNITS": "661",
                     "JAVA_TOOL_OPTIONS": "-XX:StartFlightRecording", "_JAVA_OPTIONS": "-Xmx64m", "PATH": "test"}
        with patch.dict("os.environ", inherited, clear=True):
            env = run_environment(Path("out"), Path("sandbox"), "test")
        for key in ("RWX_VK_METRICS", "RWX_CANVAS_PERF", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "RWX_BENCHMARK_UNITS"):
            self.assertNotIn(key, env)
        self.assertEqual(env["RWX_REPLAY_PAN_WARMUP_SECONDS"], "20")
        self.assertEqual(env["RWX_REPLAY_PAN_SAMPLE_SECONDS"], "20")
        self.assertEqual(env["RWX_REPLAY_PAN_REPETITIONS"], "2")
        self.assertEqual(env["PATH"], "test")

    def test_static_scenario_is_rejected_when_pan_was_requested(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            setup = {"kind": "scenario", "cameraMode": "static", "warmupSeconds": 20, "sampleSeconds": 20, "repetitions": 2}
            windows = [{"kind": "engine-window", "repetition": index + 1, "minimumLivingUnits": 661,
                        "sampleStartNanos": index * 20_000_000_000, "sampleEndNanos": (index + 1) * 20_000_000_000,
                        "cameraMode": "static", "cameraSpanX": 0, "cameraSpanY": 0,
                        "sampleStartTick": index * 1200, "sampleEndTick": (index + 1) * 1200} for index in range(2)]
            (output / "test-scenario.ndjson").write_text("\n".join(json.dumps(row) for row in [setup, *windows]), encoding="utf-8")
            (output / "test-trace.csv").write_text("acceptedPresentNanos,generation,sequence\n0,1,1\n20000000000,1,2\n40000000000,1,3\n", encoding="utf-8")
            result = analyze_run(output, {"name": "test", "exitCode": 0, "timedOut": False})
            self.assertFalse(result["validMeasurement"])
            self.assertTrue(any("cameraMode" in reason for reason in result["invalidReasons"]))

    def test_fresh_long_frames_are_preserved_with_repeated_presentations(self):
        trace = [(0, 1, 1), (5_000_000, 1, 1), (20_000_000, 1, 2), (75_000_000, 1, 3)]
        result = fresh_intervals(trace, {"sampleStartNanos": 0, "sampleEndNanos": 80_000_000})
        self.assertEqual(result["freshSnapshotIntervalsOver16_667Ms"], 2)
        self.assertEqual(result["freshSnapshotIntervalsOver33_333Ms"], 1)
        self.assertEqual(result["freshSnapshotIntervalsOver50Ms"], 1)
        self.assertEqual(result["freshSnapshotIntervalsOver100Ms"], 0)
        self.assertEqual(result["freshSnapshotLongFrames"], [
            {"acceptedPresentNanos": 20_000_000, "intervalMs": 20},
            {"acceptedPresentNanos": 75_000_000, "intervalMs": 55},
        ])

    def test_uncaught_render_exception_invalidates_otherwise_complete_measurements(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            setup = {"kind": "scenario", "cameraMode": "pan", "replaySuccess": True,
                     "replaySpeed": 1, "replayRate": 1, "warmupSeconds": 20,
                     "sampleSeconds": 20, "repetitions": 2}
            windows = [{"kind": "engine-window", "repetition": index + 1,
                        "sampleStartNanos": index * 20_000_000_000,
                        "sampleEndNanos": (index + 1) * 20_000_000_000,
                        "cameraMode": "pan", "cameraSpanX": 1600, "cameraSpanY": 1600,
                        "replaySpeed": 1, "replayRate": 1,
                        "sampleStartTick": index * 1200, "sampleEndTick": (index + 1) * 1200}
                       for index in range(2)]
            (output / "test-scenario.ndjson").write_text(
                "\n".join(json.dumps(row) for row in [setup, *windows]), encoding="utf-8")
            (output / "test-trace.csv").write_text(
                "acceptedPresentNanos,generation,sequence\n0,1,1\n20000000000,1,2\n40000000000,1,3\n",
                encoding="utf-8")
            run = {"name": "test", "exitCode": 0, "timedOut": False}
            self.assertTrue(analyze_run(output, run)["validMeasurement"])
            (output / "test.log").write_text(
                "RustedWarfare: uncaughtException start\njava.util.ConcurrentModificationException\n",
                encoding="utf-8")
            result = analyze_run(output, run)
            self.assertFalse(result["validMeasurement"])
            self.assertIn("uncaught game exception invalidates the measurement", result["invalidReasons"])


if __name__ == "__main__":
    unittest.main()
