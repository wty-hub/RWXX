#!/usr/bin/env python3
"""Owned virtual display + v7 timeline clients, simultaneous time panes and offline archive replay.

This is a short rendering/isolation acceptance, not a complete security certification.
The reviewed manager/agent are pinned by content hash; a changed adapter needs a new review.
No display mode, system volume, application activation or fallback-screen command is used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import zipfile


MANAGER_SHA256 = "c5d00988ed5303be081d0191a3e99fec7e9b2724d80e390bf4f00378daac7ebb"
AGENT_SHA256 = "fe722f09d55df604af72e2c35bde949f1c35308e5c8f61fb3ee06c461bac973f"
TARGET_CLASSES = {
    "io/github/rwx/SwingKoolHost": "999864d314199c4889969471aeb91d68ce2bc7d644491ce68b5bf34ddfd313fd",
    "io/github/rwx/DesktopWindowActivityKt": "28a955358c2bbe8e7431afc0e2b6e6dda81901b068a9c0374e4fe0d881e0150a",
}
TRANSFORMED_CLASSES = {
    "io/github/rwx/SwingKoolHost": "a998916cf2a67d64dc8107cb1b7622cc9c73390f1e1558c959c201e53b38d2e4",
    "io/github/rwx/DesktopWindowActivityKt": "db8e476f45fdf0cab3b88630ff15b537f5f8e8967490bc2518c7d384cc2e8c30",
}
SILENT_PREFS = ('[preferences]\nenableSounds = "false"\nmasterVolume = "0.0"\n'
                'musicVolume = "0.0"\ngameVolume = "0.0"\ninterfaceVolume = "0.0"\n')
PACING = {"stableFrameDeadlines": False, "guardedRenderPark": True,
          "waitForFreshFrames": True, "postInputFrameWait": False}
PROTOCOL_VERSION = 7
EXPECTED_PUBLIC_VISUAL = {"catalogVersion": 1, "groundCells": 12100,
                          "decorationCells": 53, "resourcePools": 4}
VISUAL_HASH_FIELDS = ("groundSha256", "decorationSha256")
# Exact identity left by the failed phase3 manager registration. Display IDs and bounds are reusable.
# Do not generalize this to a vendor range or to unrelated user-owned virtual displays.
PENDING_OWNED_DISPLAY_IDENTITIES = frozenset({(29561, 807753347)})


def require(condition, reason):
    # Do not use assert: all acceptance and safety checks must survive python -O.
    if not condition:
        raise RuntimeError(reason)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--manager", type=Path, required=True)
    parser.add_argument("--agent", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="Must not exist; previous evidence is never overwritten")
    parser.add_argument("--skip-replay", action="store_true",
                        help="Skip the separate read-only replay GUI check (reported as skipped)")
    return parser.parse_args(argv)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def artifact(path):
    require(path.is_file(), f"Missing artifact: {path}")
    return {"path": str(path), "sizeBytes": path.stat().st_size, "sha256": sha256(path)}


def read_records(path, final=False):
    if not path.exists():
        return []
    raw = path.read_bytes()
    require(len(raw) <= 16 * 1024 * 1024, f"Unexpectedly large evidence log: {path}")
    lines = raw.split(b"\n")
    if lines[-1]:
        require(not final, f"Incomplete final JSON line: {path}")
    lines.pop()  # A writer may still be constructing the last, unterminated line.
    result = []
    for line in lines:
        require(bool(line), f"Empty JSON evidence line: {path}")
        value = json.loads(line)
        require(isinstance(value, dict), f"Non-object JSON evidence: {path}")
        result.append(value)
    return result


def inside(frame, bounds):
    if not isinstance(frame, list) or len(frame) != 4 or not all(type(v) is int for v in frame):
        return False
    x, y, width, height = frame
    bx, by, bw, bh = bounds
    return width > 0 and height > 0 and x >= bx and y >= by and x + width <= bx + bw and y + height <= by + bh


def validate_no_pending_owned_displays(displays):
    require(not any((row.get("vendor"), row.get("serial")) in PENDING_OWNED_DISPLAY_IDENTITIES
                    for row in displays),
            "Known pending owned display is still present; cleanup must be confirmed before any virtual display or GUI is started")


def validate_preflight(records):
    """A read-only --list must confirm every original display is active before any --create."""
    require(len(records) == 1 and records[0].get("kind") == "before",
            "Unexpected display preflight snapshot; no virtual display or GUI was started")
    displays = records[0].get("displays")
    require(isinstance(displays, list) and displays and all(isinstance(row, dict) for row in displays),
            "Missing display preflight inventory; no virtual display or GUI was started")
    validate_no_pending_owned_displays(displays)
    mains = [row for row in displays if row.get("main") is True
             or (type(row.get("main")) is int and row["main"] == 1)]
    require(len(mains) == 1, "Display preflight requires exactly one main display; no virtual display or GUI was started")
    main = mains[0]
    require(main.get("active") is True,
            "Main display is inactive; no virtual display or GUI was started")
    require(main.get("modeAvailable") is True,
            "Main display current mode is unavailable; no virtual display or GUI was started")
    # An inactive original mirror can report a different mode after registration even
    # while the active main remains unchanged. Reject before creating any display.
    require(all(row.get("active") is True for row in displays),
            "An original display is inactive or has unknown activity; no virtual display or GUI was started")
    return {"mainDisplayId": main.get("id"), "mainActive": True, "mainModeAvailable": True,
            "displayCount": len(displays), "pendingOwnedDisplaysAbsent": True, "allOriginalDisplaysActive": True}


def validate_ready(records, manager_pid):
    require(not any(r.get("kind") in ("failure", "cleanup-failure", "cleanup") for r in records),
            "Virtual manager failed or ended before GUI launch")
    ready_rows = [r for r in records if r.get("kind") == "ready"]
    if not ready_rows:
        return None
    require(len(ready_rows) == 1, "Ambiguous manager ready record")
    ready = ready_rows[0]
    require(ready.get("pid") == manager_pid, "Ready record does not belong to owned manager")
    for field in ("originalDisplayProtectionPassed", "oneToOneActualPixelsVerified", "originalDisplaysFullyUnchanged"):
        require(ready.get(field) is True, f"Manager protection check failed: {field}")
    require(ready.get("seconds") == 180, "Manager lifetime is not the bounded 180-second run")
    bounds = [ready.get("bounds", {}).get(k) for k in ("x", "y", "width", "height")]
    require(all(type(v) is int for v in bounds) and bounds[2:] == [1920, 1080], "Unexpected virtual bounds")
    require(ready.get("pixelsWide") == 1920 and ready.get("pixelsHigh") == 1080, "Virtual pixel scale mismatch")
    before = [r for r in records if r.get("kind") == "before"]
    after = [r for r in records if r.get("kind") == "after"]
    require(len(before) == len(after) == 1, "Missing before/after display snapshots")
    original = before[0].get("displays", [])
    current = after[0].get("displays", [])
    require(original and len(current) == len(original) + 1, "Manager must add exactly one display")
    require(all(row in current for row in original), "An original display changed during creation")
    added = [row for row in current if row.get("id") == ready.get("displayID")]
    require(len(added) == 1 and added[0] not in original, "Owned virtual display is not new")
    require(not added[0].get("main") and not added[0].get("mirrorOf") and not added[0].get("inMirrorSet"),
            "Virtual display must not be main or mirrored")
    return ready


def validate_agent(records, bounds, final=False):
    for row in records:
        require(row.get("kind") == "rwx-background-agent" and isinstance(row.get("event"), str),
                "Unexpected agent schema (event, not kind, identifies an observation)")
        require(row.get("failed") is False and row["event"] not in ("failure", "haltInvalidShownWindow"),
                f"Background window guard failed: {row}")
        event = row["event"]
        if event in ("chosenGraphicsConfiguration", "placeBeforeShow", "window"):
            require(row.get("edt") is True and row.get("graphicsBounds") == bounds,
                    "Owned window is not bound to the live virtual display")
        if event == "chosenGraphicsConfiguration":
            require(row.get("beforeJFrameConstruction") is True and row.get("scaleX") == row.get("scaleY") == 1,
                    "Virtual graphics configuration was not selected before native window creation")
        if event in ("placeBeforeShow", "window"):
            require(inside(row.get("frame"), bounds), "Owned window escaped the virtual bounds")
            require(row.get("actualActive") is False and row.get("actualFocused") is False,
                    "Owned window activated or focused")
        if event == "placeBeforeShow":
            require(row.get("showing") is False, "Initial placement occurred after window display")
        if event == "window":
            for key in ("everActive", "everOutsideBounds", "autoRequestFocus", "focusableWindowState"):
                require(row.get(key) is False, f"Background policy violation: {key}")
        if event == "transform":
            name = row.get("class")
            require(name in TARGET_CLASSES and row.get("inputSha256") == TARGET_CLASSES[name]
                    and row.get("outputSha256") == TRANSFORMED_CLASSES[name], "Unexpected agent transformation")
    if not final:
        return None
    require([r.get("record") for r in records] == list(range(1, len(records) + 1)), "Agent records are incomplete")
    by_event = {}
    for row in records:
        by_event.setdefault(row["event"], []).append(row)
    for event in ("premain", "chosenGraphicsConfiguration", "placeBeforeShow", "shutdown", "loadedPacerFlags"):
        require(len(by_event.get(event, [])) == 1, f"Expected one {event} record")
    premain = by_event["premain"][0]
    require(premain.get("bounds") == bounds and premain.get("configuredTargetFps") == 120, "Wrong adapter configuration")
    for field in ("actualActivityRewritten", "ownerPacingRewritten", "renderPacerRewritten"):
        require(premain.get(field) is False, f"Adapter unexpectedly changed {field}")
    windows = by_event.get("window", [])
    shown = [r for r in windows if r.get("showing") is True]
    require(shown, "No actual virtual window observation")
    chosen = by_event["chosenGraphicsConfiguration"][0]
    placed = by_event["placeBeforeShow"][0]
    require(chosen["record"] < placed["record"] < shown[0]["record"], "Window creation evidence is out of order")
    require(len({r.get("frameIdentity") for r in windows}) == 1, "Unexpected extra window identity")
    require(all(r.get("graphicsDevice") == chosen.get("graphicsDevice") for r in windows), "Window device changed")
    transformed = by_event.get("transform", [])
    require(len(transformed) == 2 and {r.get("class") for r in transformed} == set(TARGET_CLASSES), "Both guarded classes must be transformed")
    shutdown = by_event["shutdown"][0]
    for field in ("valid", "configured", "everShowing", "capOverrideHit"):
        require(shutdown.get(field) is True, f"Invalid agent shutdown: {field}")
    require(shutdown.get("transformedClassCount") == 2, "Wrong transformed class count")
    pacer = by_event["loadedPacerFlags"][0]
    require(pacer.get("available") is True and all(pacer.get(k) is v for k, v in PACING.items()), "Unexpected active render pacing flags")
    caps = by_event.get("capOverrideHit", [])
    require(caps and all(r.get("actualInputTargetFps") == 120 for r in caps), "120 FPS policy was not used")
    return {"records": len(records), "windowObservations": len(windows), "shownObservations": len(shown),
            "device": chosen.get("graphicsDevice"), "bounds": bounds, "transforms": transformed,
            "pacer": pacer, "shutdown": shutdown,
            "scope": "Before-construction binding and event/sticky guards; not continuous independent WindowServer observation"}


def validate_public_visual(row):
    """The audited SmallIsland public artwork, independent of any hidden-world data."""
    for field, expected in EXPECTED_PUBLIC_VISUAL.items():
        require(type(row.get(field)) is int and row[field] == expected,
                f"Unexpected public terrain field: {field}")
    for field in VISUAL_HASH_FIELDS:
        require(isinstance(row.get(field), str) and re.fullmatch(r"[0-9a-f]{64}", row[field]) is not None,
                f"Missing or invalid public terrain hash: {field}")
    return {field: row[field] for field in (*EXPECTED_PUBLIC_VISUAL, *VISUAL_HASH_FIELDS)}


def action_probe_environment(environment):
    """Explicit opt-in inherited by the GUI; read-only replay must still suppress all actions."""
    return dict(environment, RWX_AUTHORITY_ACTION_PROBE="1")


def validate_action_probe(row, expected_read_only, final=False):
    probe = row.get("actionProbe")
    require(isinstance(probe, dict), "Missing structured UI action probe")
    flags = ("enabled", "completed", "panelSeen", "queueObserved", "cancelObserved")
    for field in flags:
        require(type(probe.get(field)) is bool, f"Invalid UI action probe field: {field}")
    require(type(probe.get("uiActionCount")) is int and probe["uiActionCount"] >= 0,
            "Invalid UI action count")
    if expected_read_only:
        require(all(probe[field] is False for field in flags) and probe["uiActionCount"] == 0,
                "Read-only replay enabled or performed UI actions")
    else:
        require(probe["enabled"] is True, "Network UI action probe was not enabled")
        if final or probe["completed"]:
            require(all(probe[field] is True for field in flags) and probe["uiActionCount"] >= 3,
                    "UI action probe did not complete selection, production and cancellation")
    return {field: probe[field] for field in (*flags, "uiActionCount")}


def validate_viewports(row, expected_read_only, expected_views):
    require(expected_views in (1, 4), "This acceptance supports one or four panes")
    for key in ("width", "height"):
        require(type(row.get(key)) is int and row[key] > 0, f"Invalid canvas extent: {key}")
    require(type(row.get("viewCount")) is int and row["viewCount"] == expected_views,
            "Wrong logical pane count")
    require(type(row.get("activePane")) is int and row["activePane"] == 0,
            "Acceptance must retain active pane zero")
    panes = row.get("viewports")
    require(isinstance(panes, list) and len(panes) == expected_views and all(isinstance(p, dict) for p in panes),
            "Missing per-pane render evidence")
    width, height = row["width"], row["height"]
    expected_rects = [(0, 0, width, height)] if expected_views == 1 else [
        (0, 0, width // 2, height // 2), (width // 2, 0, width - width // 2, height // 2),
        (0, height // 2, width // 2, height - height // 2),
        (width // 2, height // 2, width - width // 2, height - height // 2)]
    rendered = 0
    for index, (pane, rect) in enumerate(zip(panes, expected_rects)):
        require(type(pane.get("paneId")) is int and pane["paneId"] == index, "Reused or reordered pane identity")
        require(all(type(pane.get(k)) is int for k in ("x", "y", "width", "height"))
                and tuple(pane[k] for k in ("x", "y", "width", "height")) == rect
                and rect[2] > 0 and rect[3] > 0, "Pane rectangles do not partition the submitted viewport")
        require(pane.get("readOnly") is (expected_read_only or index != 0),
                "Historical or replay pane can issue commands")
        for key, minimum in (("tick", 0), ("viewSequence", -1), ("renderedGroundCells", 0)):
            require(type(pane.get(key)) is int and pane[key] >= minimum, f"Invalid pane {key}")
        require(pane["renderedGroundCells"] <= EXPECTED_PUBLIC_VISUAL["groundCells"], "Pane ground count exceeds the public map")
        if pane["renderedGroundCells"] > 0:
            require(pane["viewSequence"] > 0, "Terrain was attributed to an unavailable pane")
            rendered += 1
    require(type(row.get("renderedViewCount")) is int and row["renderedViewCount"] == rendered,
            "Declared render count differs from actual local render evidence")
    require(panes[0]["viewSequence"] == row.get("viewSequence") and panes[0]["tick"] == row.get("tick"),
            "Active canvas counters disagree with pane zero")
    return {"complete": rendered == expected_views, "renderedViewCount": rendered, "viewports": panes}


def validate_wire(row, expected_read_only, final=False):
    wire = row.get("wire")
    if expected_read_only:
        require(wire is None, "Offline replay unexpectedly reports network traffic")
        return None
    require(isinstance(wire, dict) and type(wire.get("protocolVersion")) is int
            and wire["protocolVersion"] == 7, "Missing v7 timeline network counters")
    fields = ("recvBytes", "writtenBytes", "mapInitializations", "mapCacheHits", "transactions", "restores",
              "stateChecks", "stateAcks", "stateMismatches", "stateDigestNanos")
    require(all(type(wire.get(field)) is int and wire[field] >= 0 for field in fields), "Invalid v7 wire counters")
    require(wire["stateMismatches"] == 0 and wire["restores"] == 0, "Timeline digest mismatch or restore occurred")
    if final:
        require(wire["recvBytes"] > 0 and wire["writtenBytes"] > 0
                and wire["mapInitializations"] + wire["mapCacheHits"] == 1 and wire["transactions"] > 1,
                "Map handshake or committed timeline transactions were not observed")
        require(wire["stateChecks"] > 0 and wire["stateAcks"] > 0, "Missing full timeline digest checks and ACKs")
    return wire


def validate_canvas(records, expected_read_only=False, expected_views=1):
    require(len(records) >= 2, "Missing repeated canvas probes")
    visual = None
    ready = []
    complete = []
    for row in records:
        validate_action_probe(row, expected_read_only)
        require(row.get("noGameEngine") is True, "Client created a game simulation")
        require(row.get("readOnly") is expected_read_only, "Unexpected view source read-only state")
        require(str(row.get("backend", "")).casefold() == "vulkan", "Canvas backend is not Vulkan")
        require(row.get("connected") is True, "View source was no longer active during the probe")
        panes = validate_viewports(row, expected_read_only, expected_views)
        validate_wire(row, expected_read_only)
        if row["viewSequence"] > 0:
            current_visual = validate_public_visual(row)
            if visual is None:
                visual = current_visual
            require(current_visual == visual, "Public terrain artwork changed between canvas probes")
            for field, minimum, maximum in (("renderedGroundCells", 1, row["groundCells"]),
                                            ("renderedDecorationCells", 0, row["decorationCells"]),
                                            ("terrainTextures", 1, 11)):
                require(type(row.get(field)) is int and minimum <= row[field] <= maximum,
                        f"Canvas did not submit valid public terrain atlas commands: {field}")
            ready.append(row)
        else:
            # Async archive/network loading explicitly clears the view. It cannot count as rendering.
            require(all(type(row.get(k)) is int and row[k] == 0 for k in
                        (*EXPECTED_PUBLIC_VISUAL, "renderedGroundCells", "renderedDecorationCells"))
                    and all(row.get(k) == "" for k in VISUAL_HASH_FIELDS), "Unavailable pane retained visible world metadata")
            require(type(row.get("terrainTextures")) is int and 0 <= row["terrainTextures"] <= 11,
                    "Invalid loading texture count")
        if panes["complete"] and row.get("presentedRevision", -1) > 0:
            complete.append(row)
    for field in ("submittedFrames", "presentedRevision"):
        values = [r.get(field) for r in records]
        require(all(type(value) is int for value in values), f"Invalid canvas field: {field}")
        require(values == sorted(values), f"Canvas {field} regressed")
        require(values[-1] > values[0], f"No canvas {field} progression")
    require(len(ready) >= 2 and len(complete) >= 2 and complete[-1] is records[-1],
            "Missing repeated simultaneous rendered panes, or final frame is incomplete")
    for field in ("viewSequence", "tick"):
        values = [r[field] for r in ready]
        require(values == sorted(values) and values[-1] > values[0] >= 0, f"No advancing active timeline {field}")
    wire = validate_wire(records[-1], expected_read_only, final=True)
    return {"probeCount": len(records), "readOnly": expected_read_only, "publicMapVisual": visual, "wire": wire,
            "viewCount": expected_views, "completePaneProbes": len(complete), "activePane": 0,
            "actionProbe": validate_action_probe(records[-1], expected_read_only, final=True),
            "first": records[0], "last": records[-1],
            "scope": "Submitted local canvas commands with repeated presentation progress; not a pixel-golden comparison"}


def validate_recording_inspection(inspection, team_id, canvas_report=None):
    require(inspection.get("passed") is True and inspection.get("noGameEngine") is True,
            "Archive did not pass the complete indexed v7 reader")
    require(type(inspection.get("teamId")) is int and inspection["teamId"] == team_id
            and type(inspection.get("protocolVersion")) is int and inspection["protocolVersion"] == PROTOCOL_VERSION
            and type(inspection.get("containerVersion")) is int and inspection["containerVersion"] == 3,
            "Archive has the wrong seat, container or wire version")
    for field in ("slices", "samples", "views", "firstTick", "finalTick", "visualViews", "gapCount",
                  "from", "until", "joinedAt", "historyFrom"):
        require(type(inspection.get(field)) is int and inspection[field] >= 0, f"Invalid archive field: {field}")
    require(inspection["slices"] > 0 and inspection["slices"] <= inspection["samples"] <= 2 * inspection["slices"]
            and inspection["samples"] >= 2 and inspection["finalTick"] > inspection["firstTick"],
            "Archive lacks independently reconstructed, advancing boundary samples")
    require(inspection.get("completeIndex") is True and inspection.get("readOnly") is True
            and inspection.get("staticVisualConsistent") is True
            and inspection["visualViews"] == inspection["views"] == inspection["samples"],
            "Not every archive slice boundary was validated read-only")
    require(inspection["joinedAt"] <= inspection["historyFrom"] <= inspection["from"] < inspection["until"],
            "Archive extends outside its access grant")
    require(type(inspection.get("hasGaps")) is bool and inspection["hasGaps"] == (inspection["gapCount"] > 0)
            and type(inspection.get("terminalCovered")) is bool and type(inspection.get("partial")) is bool
            and inspection["partial"] == (inspection["hasGaps"] or not inspection["terminalCovered"]),
            "Archive completeness or gap reporting is inconsistent")
    visual = validate_public_visual(inspection)
    if canvas_report is not None:
        require(visual == canvas_report.get("publicMapVisual"), "Presented artwork differs from the v7 archive")
    return inspection


def validate_muted(state):
    # TOML values are quoted preferences strings, matching the actual settings reader.
    import tomllib
    preferences = tomllib.loads((state / "preferences.toml").read_text()).get("preferences", {})
    require(preferences.get("enableSounds") == "false", "Client sound was enabled")
    require(all(float(preferences.get(key, "nan")) == 0.0 for key in
                ("masterVolume", "musicVolume", "gameVolume", "interfaceVolume")), "Client volume was not muted")
    return {"startupPreferences": artifact(state / "startup-preferences.toml"),
            "finalPreferences": artifact(state / "preferences.toml")}


def validate_resources(log_path):
    """Inspect only resource failures, allowing ordinary JVM/native informational warnings."""
    failures = []
    for number, raw_line in enumerate(log_path.read_text(errors="replace").splitlines(), 1):
        # Native logger color spans can occur inside the message as well as around its prefix.
        line = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", raw_line)
        leak = re.search(r"\bFreeing\s+(\d+)\s+leaked\s+images\b", line, re.IGNORECASE)
        if leak:
            failures.append(f"line {number}: Freeing {leak.group(1)} leaked images")
        if "Authority UI font loading" in line:
            failures.append(f"line {number}: {line.strip()}")
    require(not failures, f"Native resource cleanup failed in {log_path}: " + "; ".join(failures))
    return {"passed": True, "log": str(log_path), "logSha256": sha256(log_path),
            "checks": ["No leaked-image cleanup warning", "No authority UI font loading failure"],
            "scope": "Known native resource diagnostics; not a general heap or GPU allocation audit"}


def stop_process(process, timeout=10):
    # Only signal the child represented by this Popen. A reaped PID/PGID can be reused.
    # These entry points do not spawn child servers or other owned GUI processes.
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)


def main(argv=None):
    args = parse_args(argv)
    output = args.output_dir.resolve()
    require(not output.exists(), "Use a new output directory; existing evidence must remain unchanged")
    output.mkdir(parents=True)
    root = Path(__file__).resolve().parents[4]
    result = {"passed": False, "controllerPid": os.getpid(), "scope": __doc__.strip(),
              "failures": [], "processes": [], "clientSeconds": 12, "managerSeconds": 180,
              "replaySeconds": 5, "replayRequested": not args.skip_replay,
              "protocolVersion": 7, "expectedViewCounts": {"client-0": 4, "client-1": 1, "replay": 4},
              "networkSeatCount": 2, "replayClient": {"skipped": args.skip_replay},
              "resourceCleanup": {},
              "systemVolumeChanged": False, "physicalDisplayModeCommands": False}
    manager = None
    processes = []
    logs = []
    ready = None
    completed = False
    credentials = None
    manager_log = output / "manager.ndjson"

    def fail(stage, error):
        result["failures"].append({"stage": stage, "error": str(error)})

    def check_resources(role):
        log_path = output / (role + ".log")
        result["resourceCleanup"][role] = {"passed": False, "log": str(log_path)}
        try:
            report = validate_resources(log_path)
        except Exception as error:
            result["resourceCleanup"][role]["error"] = str(error)
            raise
        result["resourceCleanup"][role] = report
        return report

    def interrupt(signum, _frame):
        raise InterruptedError(f"Interrupted by signal {signum}")

    old_handlers = {sig: signal.signal(sig, interrupt) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        require(sys.platform == "darwin", "This reviewed virtual-display adapter is macOS-only")
        java_name = shutil.which(args.java) if not Path(args.java).is_absolute() else args.java
        require(java_name is not None, "Java executable was not found")
        java = Path(java_name).resolve()
        jar, manager_path, agent = (p.resolve() for p in (args.jar, args.manager, args.agent))
        paths = {"java": java, "jar": jar, "manager": manager_path, "agent": agent,
                 "runner": Path(__file__).resolve()}
        result["artifacts"] = {name: artifact(path) for name, path in paths.items()}
        require(os.access(java, os.X_OK) and os.access(manager_path, os.X_OK), "Java/manager must be executable")
        require(result["artifacts"]["manager"]["sha256"] == MANAGER_SHA256, "Manager is not the reviewed v6 binary")
        require(result["artifacts"]["agent"]["sha256"] == AGENT_SHA256, "Agent is not the reviewed v8 JAR")
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            actual_classes = {}
            for name, expected in TARGET_CLASSES.items():
                require(names.count(name + ".class") == 1, f"Missing or duplicate guarded class: {name}")
                actual_classes[name] = hashlib.sha256(archive.read(name + ".class")).hexdigest()
                require(actual_classes[name] == expected, f"Guarded class does not match agent v8: {name}")
            require("io/github/rwx/authoritative/AuthoritativeKoolClient.class" in names, "JAR lacks the Kool view client")
            for name in ("TimelineViewConnection", "TimelineReplaySource", "TimelineReplayInspectMain"):
                require(f"io/github/rwx/authoritative/{name}.class" in names, f"JAR lacks the v7 {name}")
        result["exactWindowClassSha256"] = actual_classes
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith("RWX_") and key not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
        result["environmentPolicy"] = "Inherited RWX_* and Java option injectors removed; explicit test controls only"

        # --list is the pinned manager's read-only path. A sleeping physical main display can
        # otherwise leave a newly registered virtual display without a usable public mode.
        # Do not attempt creation, wake the display, or change any physical display settings.
        preflight_command = [str(manager_path), "--list"]
        preflight_log = output / "preflight.ndjson"
        preflight_stderr = output / "preflight.stderr"
        result["preflight"] = {"passed": False, "argv": preflight_command}
        try:
            with preflight_log.open("x") as stdout, preflight_stderr.open("x") as stderr:
                listed = subprocess.run(preflight_command, cwd=root, env=environment, stdout=stdout,
                                        stderr=stderr, timeout=10, check=False)
            result["preflight"].update({"exitCode": listed.returncode, "snapshot": artifact(preflight_log),
                                         "stderr": artifact(preflight_stderr)})
            require(listed.returncode == 0, "Read-only display preflight failed; no virtual display or GUI was started")
            result["preflight"].update(validate_preflight(read_records(preflight_log, final=True)))
            result["preflight"]["passed"] = True
        except BaseException as error:
            result["preflight"]["error"] = str(error)
            raise

        def launch(role, command, env):
            log_path = output / (role + ".log")
            log = log_path.open("x")
            logs.append(log)
            process = subprocess.Popen(command, cwd=root, env=env, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True,
                                       stdin=subprocess.PIPE if role == "server" else subprocess.DEVNULL)
            processes.append(process)
            result["processes"].append({"role": role, "pid": process.pid, "processGroup": process.pid,
                                        "argv": command, "log": str(log_path)})
            return process

        manager_stdout = manager_log.open("x")
        manager_stderr = (output / "manager.stderr").open("x")
        logs.extend((manager_stdout, manager_stderr))
        manager_command = [str(manager_path), "--create", "--seconds=180", "--refresh=60"]
        manager = subprocess.Popen(manager_command, cwd=root, env=environment, stdout=manager_stdout,
                                   stderr=manager_stderr, start_new_session=True)
        result["manager"] = {"pid": manager.pid, "argv": manager_command}
        deadline = time.monotonic() + 150
        ready_deadline = time.monotonic() + 30
        while ready is None:
            require(manager.poll() is None, "Manager stopped before readiness; no GUI launched")
            ready = validate_ready(read_records(manager_log), manager.pid)
            require(time.monotonic() < ready_deadline, "Manager readiness timed out; no GUI launched")
            if ready is None:
                time.sleep(0.1)
        result["display"] = ready
        bounds = [ready["bounds"][key] for key in ("x", "y", "width", "height")]

        def guard():
            require(manager.poll() is None, "Owned display manager stopped; aborting owned games")
            require(time.monotonic() < deadline, "Acceptance deadline expired")
            validate_ready(read_records(manager_log), manager.pid)
            for role in ("client-0", "client-1", "replay"):
                validate_agent(read_records(output / role / "background-window.ndjson"), bounds)

        credentials = tempfile.TemporaryDirectory(prefix="rwxx-kool-seats-")
        credential_root = Path(credentials.name)
        tokens = [secrets.token_urlsafe(32) for _ in range(2)]

        def secret_file(name, content):
            path = credential_root / name
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write(content)
            return path

        seats = secret_file("seats.properties", "".join(f"{i}={token}\n" for i, token in enumerate(tokens)))
        token_files = [secret_file(f"seat-{i}.token", token) for i, token in enumerate(tokens)]
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        result["port"] = port
        server_state = output / "server"
        server_state.mkdir()
        (server_state / "home").mkdir()
        (server_state / "preferences.toml").write_text(SILENT_PREFS)
        common = ["-cp", str(jar), "io.github.rwx.AuthoritativeDesktopMain"]
        server = launch("server", [str(java), "-Djava.awt.headless=true", f"-Dlaunch.dir={server_state}",
            f"-Duser.home={server_state / 'home'}", *common, "--authority-server", f"--seats={seats}",
            "--bind=127.0.0.1", f"--port={port}", "--owner-stdin=true",
            f"--archive-dir={server_state / 'archives'}"], environment)
        server_deadline = time.monotonic() + 60
        while True:
            guard()
            require(server.poll() is None, "Server exited during startup")
            if f"Timeline server listening on {port}; protocol 7" in (output / "server.log").read_text(errors="replace"):
                break
            require(time.monotonic() < server_deadline, "Server did not become ready")
            time.sleep(0.1)

        def launch_view(role, arguments, seconds):
            guard()
            state = output / role
            state.mkdir()
            (state / "home").mkdir()
            (state / "preferences.toml").write_text(SILENT_PREFS)
            (state / "startup-preferences.toml").write_text(SILENT_PREFS)
            client_environment = dict(action_probe_environment(environment), RWX_DESKTOP_TARGET_FPS="120",
                RWX_AUTHORITY_CANVAS_PROBE=str(state / "canvas.ndjson"), RWX_DEBUG_AUTO_EXIT_SECONDS=str(seconds))
            command = [str(java), f"-Dlaunch.dir={state}", f"-Duser.home={state / 'home'}",
                f"-javaagent:{agent}", "-Dapple.awt.UIElement=true", "-Djava.awt.headless=false",
                "-Drwx.background.display.bounds=" + ",".join(map(str, bounds)), "-Drwx.background.target.fps=120",
                f"-Drwx.background.observer.output={state / 'background-window.ndjson'}",
                # The packaged launcher consumes Manifest Add-Exports (including com.apple.eawt).
                # A direct -cp entry point bypasses those product startup permissions on macOS.
                "-Drwx.desktop.renderer=kool", "-Drwx.kool.backend=vulkan", "-jar", str(jar), *arguments]
            return launch(role, command, client_environment)

        clients = []
        for index in range(2):
            state = output / f"client-{index}"
            clients.append(launch_view(f"client-{index}", ["--authority-client",
                "--connect=127.0.0.1", f"--port={port}", f"--token-file={token_files[index]}",
                f"--name=VirtualPlayer{index}", f"--record-view={state / 'own.rwxr'}",
                f"--views={4 if index == 0 else 1}"], seconds=12))
        require(len({server.pid, *(client.pid for client in clients)}) == 3, "Expected three independent JVMs")
        result["primaryJvmPids"] = {"server": server.pid, "clients": [client.pid for client in clients]}
        while any(client.poll() is None for client in clients):
            guard()
            require(server.poll() is None, "Server exited while clients were running")
            for index, client in enumerate(clients):
                require(client.poll() in (None, 0), f"Kool client {index} failed with exit {client.returncode}")
            time.sleep(0.1)
        guard()
        require(server.poll() is None, "Server exited before recording inspection")
        result["clients"] = []
        for index, client in enumerate(clients):
            require(client.returncode == 0, f"Kool client {index} did not exit normally")
            resource_report = check_resources(f"client-{index}")
            state = output / f"client-{index}"
            agent_report = validate_agent(read_records(state / "background-window.ndjson", final=True), bounds, final=True)
            canvas_report = validate_canvas(read_records(state / "canvas.ndjson", final=True), expected_read_only=False,
                                            expected_views=4 if index == 0 else 1)
            recording = state / "own.rwxr"
            require(recording.is_file() and recording.stat().st_size > 0, "Player-view recording is empty")
            result["clients"].append({"pid": client.pid, "exitCode": client.returncode, "agent": agent_report,
                "canvas": canvas_report, "recording": artifact(recording),
                "resourceCleanup": resource_report,
                **validate_muted(state)})
        for index in range(2):
            state = output / f"client-{index}"
            report_path = state / "recording-inspection.json"
            inspector = launch(f"inspect-{index}", [str(java), "-Djava.awt.headless=true",
                f"-Dlaunch.dir={state}", f"-Duser.home={state / 'home'}", "-cp", str(jar),
                "io.github.rwx.authoritative.TimelineReplayInspectMain",
                f"--input={state / 'own.rwxr'}", f"--output={report_path}"], environment)
            inspect_deadline = time.monotonic() + 20
            while inspector.poll() is None:
                guard()
                require(time.monotonic() < inspect_deadline, "Headless recording inspection timed out")
                time.sleep(0.1)
            require(inspector.returncode == 0, f"Recording inspector {index} failed")
            inspection = json.loads(report_path.read_text())
            result["clients"][index]["recordingInspection"] = validate_recording_inspection(
                inspection, index, result["clients"][index]["canvas"])
        if not args.skip_replay:
            # Playback reads a completed local player-view file. Stop the already verified server
            # first so this check cannot accidentally pass through a new live seat connection.
            require(server.poll() is None, "Server exited unexpectedly before the offline replay stage")
            server.stdin.close()
            server_stop_deadline = time.monotonic() + 17
            while server.poll() is None:
                guard()
                require(time.monotonic() < server_stop_deadline, "Server did not finish archives after owner EOF")
                time.sleep(0.1)
            require(server.returncode == 0, "Server did not stop normally before replay")
            replay_state = output / "replay"
            source_recording = output / "client-0" / "own.rwxr"
            replay = launch_view("replay", ["--authority-replay", f"--replay-file={source_recording}", "--speed=1", "--views=4"], seconds=5)
            replay_deadline = time.monotonic() + 35
            while replay.poll() is None:
                guard()
                require(time.monotonic() < replay_deadline, "Read-only replay GUI did not close normally")
                time.sleep(0.1)
            guard()
            require(replay.returncode == 0, "Read-only replay GUI did not exit normally")
            replay_resources = check_resources("replay")
            replay_agent = validate_agent(read_records(replay_state / "background-window.ndjson", final=True), bounds, final=True)
            replay_canvas = validate_canvas(read_records(replay_state / "canvas.ndjson", final=True), expected_read_only=True, expected_views=4)
            validate_recording_inspection(result["clients"][0]["recordingInspection"], 0, replay_canvas)
            require(sha256(source_recording) == result["clients"][0]["recording"]["sha256"], "Playback changed its input recording")
            result["replayClient"] = {"skipped": False, "pid": replay.pid, "exitCode": replay.returncode,
                "serverStoppedBeforeReplay": True, "serverExitCode": server.returncode,
                "networkConnectionRequested": False, "speed": 1, "readOnly": True,
                "sourceRecording": artifact(source_recording), "agent": replay_agent,
                "canvas": replay_canvas, "resourceCleanup": replay_resources, **validate_muted(replay_state)}
        if server.poll() is None:
            server.stdin.close()
            server_stop_deadline = time.monotonic() + 17
            while server.poll() is None:
                guard()
                require(time.monotonic() < server_stop_deadline, "Server did not finish archives after owner EOF")
                time.sleep(0.1)
        require(server.returncode == 0, "Headless server did not exit normally")
        result["serverOwnerEofExitCode"] = server.returncode
        completed = True
    except BaseException as error:
        fail("acceptance", error)
    finally:
        # Finish owned games before releasing their only permitted display, including on Ctrl-C.
        for sig in old_handlers:
            signal.signal(sig, signal.SIG_IGN)
        # Signal all still-live owned JVMs first; never signal an exited/reused process group.
        for process in reversed(processes):
            try:
                if process.poll() is None:
                    process.terminate()
            except BaseException as error:
                fail("game termination", error)
        for process in reversed(processes):
            try:
                stop_process(process)
            except BaseException as error:
                fail("game cleanup", error)
        for row, process in zip(result["processes"], processes):
            row["exitCode"] = process.poll()
            if row["role"] == "server" and process.poll() not in (0, -signal.SIGTERM, 128 + signal.SIGTERM):
                fail("server cleanup", f"Unexpected server exit code: {process.poll()}")
        if manager is not None:
            try:
                if manager.poll() is None:
                    manager.terminate()
                manager.wait(timeout=20)
            except BaseException as error:
                # SIGKILL is deliberately avoided: the manager owns display release/verification.
                fail("display cleanup", error)
            result["manager"]["exitCode"] = manager.poll()
        if credentials is not None:
            try:
                credentials.cleanup()
            except BaseException as error:
                fail("credential cleanup", error)
        for log in logs:
            log.close()
        try:
            if manager is None:
                # A rejected preflight never owned a virtual display, so no cleanup record is due.
                result["cleanup"] = {"required": False, "reason": "No display create process was launched"}
            else:
                records = read_records(manager_log, final=True)
                cleanup = [row for row in records if row.get("kind") == "cleanup"]
                result["cleanup"] = cleanup[-1] if cleanup else None
                require(len(cleanup) == 1 and manager.poll() == 0, "No successful owned manager cleanup")
                require(not any(row.get("kind") in ("failure", "cleanup-failure") for row in records), "Manager recorded a failure")
                row = cleanup[0]
                require(row.get("removed") is True and row.get("originalDisplaysUnchanged") is True
                        and row.get("exitCode") == 0, "Virtual display removal or original display restoration failed")
                before = next((r.get("displays") for r in records if r.get("kind") == "before"), None)
                require(before is not None and row.get("displays") == before, "Final displays differ from the original snapshot")
                if ready is not None:
                    require(row.get("displayID") == ready["displayID"], "Cleanup refers to a different display")
            for name, info in result.get("artifacts", {}).items():
                require(sha256(Path(info["path"])) == info["sha256"], f"Artifact changed during run: {name}")
        except BaseException as error:
            fail("evidence/cleanup verification", error)
        result["passed"] = completed and not result["failures"]
        with (output / "result.json").open("x") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
    print(json.dumps({"passed": result["passed"], "result": str(output / "result.json"), "failures": result["failures"]}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
