#!/usr/bin/env python3
"""Read-only acceptance audit of original_compatibility.py + DesktopClockNetworkPeer outputs.

This does not launch a process or retry a failed comparison. Full official-peer reports and
independent actual desktop-clock samples must both pass; clock samples alone are insufficient.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def audit_direction(root: Path) -> dict:
    result = json.loads((root / "result.json").read_text())
    clock = root / "rwx" / "clock-evidence"
    manifest = json.loads((clock / "manifest.json").read_text())
    samples = [json.loads(line) for line in (clock / "samples.ndjson").read_text().splitlines() if line.strip()]
    final = json.loads((clock / "final-clock.json").read_text())
    direction = root.name
    checks = []

    def check(name, condition):
        checks.append({"name": name, "passed": bool(condition)})

    check("full official-core network acceptance", result.get("passed") is True and
          result.get("originalPeerMode") == "headless-original-core" and
          result.get("requestedDurationSeconds", 0) >= 600 and result.get("durationSeconds", 0) >= 600)
    check("actual production desktop session and explicit integer policy", manifest.get("actualSession") ==
          "io.github.rwx.kool.KoolDesktopGameSession" and manifest.get("explicitClockEnvironment") == "1")
    rows = samples + [final]
    check("silent and windowless throughout", bool(rows) and all(row.get("headless") is True and
          row.get("koolContextPresent") is False and row.get("audioBackend") == "NoopPlatformAudio" for row in rows))
    check("all actual adapter samples integer and no fallback", bool(rows) and all(
        row.get("originalIntegerMillis") is True and row.get("missingOwnerFallbacks") == 0 for row in rows))
    check("actual owner thread", bool(rows) and all(row.get("ownerThread") == "RWX-engine-owner" for row in rows))
    check("monotonic clock samples and call counter", len(samples) >= 2 and all(
        a["ownerMonotonicNs"] < b["ownerMonotonicNs"] and a["originalOwnerCalls"] <= b["originalOwnerCalls"]
        for a, b in zip(samples, samples[1:])))
    active = [row for row in samples if row.get("mapLoaded") and row.get("networkActive")]
    check("nonempty actual mapped network clock", len(active) >= 2 and any(
        row.get("lastSource") == "OriginalOwnerMillis" for row in active))
    if len(active) >= 2:
        elapsed = (active[-1]["ownerMonotonicNs"] - active[0]["ownerMonotonicNs"]) / 1e9
        max_gap = max((b["ownerMonotonicNs"] - a["ownerMonotonicNs"]) / 1e9 for a, b in zip(active, active[1:]))
        check("actual adapter observed across the completed network window", elapsed >= result.get("durationSeconds", 0) - 2)
        check("no large missing clock-observation gaps", max_gap <= 30)
        check("actual integer owner calls advance", active[-1]["originalOwnerCalls"] > active[0]["originalOwnerCalls"])
        check("actual mapped ticks advance", active[-1]["tick"] > active[0]["tick"])
        check("correct actual host/client role", all(row.get("host") is (direction == "rwx-host") for row in active))
        if direction == "original-host":
            check("actual RWXX-client checksum passes advance", active[-1]["clientChecksumPasses"] > active[0]["clientChecksumPasses"])
    else:
        elapsed = max_gap = None
    check("zero actual desync and resync", bool(active) and all(row.get("desyncErrors") == 0 and row.get("resyncs") == 0 for row in active))
    check("full runner observes rolling client checksums and commands", result.get("finalClientChecksumPasses", 0) >
          result.get("initialClientChecksumPasses", 0) and result.get("rwxExecutedCommands", 0) > 0 and result.get("submittedCommands", 0) >= 2)
    check("shutdown retained actual nonzero adapter use", final.get("actualOwnerCallsObserved") is True and
          final.get("originalOwnerCalls", 0) > 0)
    return {"direction": direction, "passed": all(item["passed"] for item in checks),
            "samples": len(samples), "activeSamples": len(active), "activeObservationSeconds": elapsed,
            "maximumActiveSampleGapSeconds": max_gap, "checks": checks}


def audit(root: Path) -> dict:
    overall = json.loads((root / "result.json").read_text())
    directions = [audit_direction(root / direction) for direction in ("rwx-host", "original-host")]
    return {"kind": "actual-desktop-clock-official-core-compatibility-audit",
            "passed": overall.get("passed") is True and all(item["passed"] for item in directions),
            "directions": directions, "launchedProcesses": 0,
            "scope": "Actual desktop owner+integer adapter and full 600-second official 1.15 core loopback in both host/client directions; rendering skipped, no performance or all-scenario compatibility claim"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_directory", type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.result_directory)
    except (OSError, ValueError, KeyError, TypeError) as error:
        report = {"passed": False, "error": str(error), "launchedProcesses": 0}
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
