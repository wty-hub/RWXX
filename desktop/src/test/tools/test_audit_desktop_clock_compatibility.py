import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("clock_audit", Path(__file__).with_name("audit_desktop_clock_compatibility.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ClockAuditTest(unittest.TestCase):
    def prepare(self, root):
        (root / "result.json").write_text(json.dumps({"passed": True}))
        for direction in ("rwx-host", "original-host"):
            stage = root / direction
            clock = stage / "rwx/clock-evidence"
            clock.mkdir(parents=True)
            (stage / "result.json").write_text(json.dumps({"passed": True, "originalPeerMode": "headless-original-core",
                "requestedDurationSeconds": 600, "durationSeconds": 600.2, "initialClientChecksumPasses": 1,
                "finalClientChecksumPasses": 60, "rwxExecutedCommands": 30, "submittedCommands": 30}))
            (clock / "manifest.json").write_text(json.dumps({"actualSession": "io.github.rwx.kool.KoolDesktopGameSession",
                "explicitClockEnvironment": "1"}))
            samples = [dict(headless=True, koolContextPresent=False, audioBackend="NoopPlatformAudio",
                originalIntegerMillis=True, missingOwnerFallbacks=0, ownerThread="RWX-engine-owner",
                ownerMonotonicNs=i * 1_000_000_000, originalOwnerCalls=i * 120, tick=i * 30,
                mapLoaded=True, networkActive=True, host=direction == "rwx-host", lastSource="OriginalOwnerMillis",
                clientChecksumPasses=i // 10, desyncErrors=0, resyncs=0) for i in range(602)]
            (clock / "samples.ndjson").write_text("".join(json.dumps(row) + "\n" for row in samples))
            (clock / "final-clock.json").write_text(json.dumps(dict(samples[-1], actualOwnerCallsObserved=True)))

    def test_complete_both_direction_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root)
            self.assertTrue(MODULE.audit(root)["passed"])

    def test_rolling_official_checksum_evidence_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root)
            report = root / "rwx-host/result.json"
            data = json.loads(report.read_text()); data["finalClientChecksumPasses"] = data["initialClientChecksumPasses"]
            report.write_text(json.dumps(data))
            self.assertFalse(MODULE.audit(root)["passed"])

    def test_stale_calls_and_short_observation_cannot_pass_with_good_runner_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root)
            trace = root / "original-host/rwx/clock-evidence/samples.ndjson"
            rows = [json.loads(row) for row in trace.read_text().splitlines()][:100]
            for row in rows: row["originalOwnerCalls"] = 1
            trace.write_text("".join(json.dumps(row) + "\n" for row in rows))
            self.assertFalse(MODULE.audit(root)["passed"])

    def test_any_fallback_fails_even_with_good_final_row(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root)
            trace = root / "rwx-host/rwx/clock-evidence/samples.ndjson"
            rows = [json.loads(row) for row in trace.read_text().splitlines()]
            rows[25]["missingOwnerFallbacks"] = 1
            trace.write_text("".join(json.dumps(row) + "\n" for row in rows))
            self.assertFalse(MODULE.audit(root)["passed"])

    def test_missing_full_runner_output_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root)
            (root / "original-host/result.json").unlink()
            with self.assertRaises(OSError): MODULE.audit(root)


if __name__ == "__main__":
    unittest.main()
