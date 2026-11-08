"""Offline v7 evidence checks. Never launches a JVM, display manager or graphical surface."""
import ast
import copy
import hashlib
import inspect
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import authoritative_timeline_acceptance as acceptance


# Frozen before active v6 tools were retired. Re-review is required to change any guard body.
# Only validate_preflight was strengthened on 2026-11-07 to require active=True on
# every original display; all manager/agent pins and other guard bodies stay frozen.
REVIEWED_GUARD_SHA256 = {'read_records': '27482f7c92f20cc629ad080584edf3899d4d843061e4f933a9a5a79f0205e163',
 'inside': 'e3938d78c1de58b4fc4e6d2c5803e7672c6ad3669461bce5c774509a77ddf91b',
 'validate_no_pending_owned_displays': '42ce4fec6c24c108c1c26ac5aac92b0ed5010f494ef1a85fdf8317f2c4d68bb2',
 'validate_preflight': '3193e0851e1678a9026795de276b64306e30ba5ee4027170ef9016bb546e1a07',
 'validate_ready': '68b81754a094487e3f244bbfefe54fa34dd5f7a4b88ca3223a643b19e054760c',
 'validate_agent': 'c558817e80f52c4d7a06472eaa859e958061455d9e09e20ac6b013a9f89b156d',
 'validate_resources': 'e63cb8913df0981901641a202a384572cb10e4f25644078f8d454ab5f955ab2f',
 'validate_muted': 'd721e575994ec87121466aaf1254e2fde0b26a417e11a5462dbdb9d3c5f2a1a1',
 'stop_process': '7f6ef704fb06168d4242a460cb6cd0064775f40601911ffce0697be710ca5582',
 'action_probe_environment': 'cb7a5929c1904eb8cd4414340ecf0471112cfd926654b8cd9b0f30b3b9e2e4ae',
 'validate_action_probe': 'f99ef9417967f4b840f4251f411fe13354f25e15517f01fed1da14c33f33a6da'}
REVIEWED_PINS = {'MANAGER_SHA256': 'c5d00988ed5303be081d0191a3e99fec7e9b2724d80e390bf4f00378daac7ebb',
 'AGENT_SHA256': 'fe722f09d55df604af72e2c35bde949f1c35308e5c8f61fb3ee06c461bac973f',
 'TARGET_CLASSES': {'io/github/rwx/SwingKoolHost': '999864d314199c4889969471aeb91d68ce2bc7d644491ce68b5bf34ddfd313fd',
                    'io/github/rwx/DesktopWindowActivityKt': '28a955358c2bbe8e7431afc0e2b6e6dda81901b068a9c0374e4fe0d881e0150a'},
 'TRANSFORMED_CLASSES': {'io/github/rwx/SwingKoolHost': 'a998916cf2a67d64dc8107cb1b7622cc9c73390f1e1558c959c201e53b38d2e4',
                         'io/github/rwx/DesktopWindowActivityKt': 'db8e476f45fdf0cab3b88630ff15b537f5f8e8967490bc2518c7d384cc2e8c30'},
 'PACING': {'stableFrameDeadlines': False,
            'guardedRenderPark': True,
            'waitForFreshFrames': True,
            'postInputFrameWait': False},
 'SILENT_PREFS': '[preferences]\n'
                 'enableSounds = "false"\n'
                 'masterVolume = "0.0"\n'
                 'musicVolume = "0.0"\n'
                 'gameVolume = "0.0"\n'
                 'interfaceVolume = "0.0"\n',
 'PENDING_OWNED_DISPLAY_IDENTITIES': frozenset({(29561, 807753347)})}


def visual():
    return {"catalogVersion": 1, "groundCells": 12100, "decorationCells": 53, "resourcePools": 4,
            "groundSha256": "a" * 64, "decorationSha256": "b" * 64}


def action(read_only, complete):
    return {"enabled": not read_only, "completed": complete and not read_only,
            "panelSeen": complete and not read_only, "queueObserved": complete and not read_only,
            "cancelObserved": complete and not read_only, "uiActionCount": 3 if complete and not read_only else 0}


def records(read_only=False, views=4):
    result = []
    for index in range(3):
        width, height = 1281, 753
        rects = [(0, 0, width, height)] if views == 1 else [
            (0, 0, 640, 376), (640, 0, 641, 376), (0, 376, 640, 377), (640, 376, 641, 377)]
        sequence = 11 + index * 10
        tick = 60 + index * 20
        row = {"submittedFrames": 1 + index * 30, "presentedRevision": -1 if index == 0 else index * 30,
               "viewSequence": sequence, "tick": tick, "width": width, "height": height,
               "connected": True, "readOnly": read_only, "noGameEngine": True, "backend": "Vulkan",
               **visual(), "renderedGroundCells": 120, "renderedDecorationCells": 2, "terrainTextures": 5,
               "viewCount": views, "activePane": 0, "renderedViewCount": views,
               "actionProbe": action(read_only, index == 2),
               "wire": None if read_only else {"protocolVersion": 7, "recvBytes": 100000 + index * 1000,
                  "writtenBytes": 1000 + index * 100, "mapInitializations": 1, "mapCacheHits": 0,
                  "transactions": 5 + index, "restores": 0, "stateChecks": 1 + index, "stateAcks": 1 + index,
                  "stateMismatches": 0, "stateDigestNanos": 1000 + index}}
        row["viewports"] = [{"paneId": pane, "x": x, "y": y, "width": w, "height": h,
                             "viewSequence": sequence if pane == 0 else sequence + pane,
                             "tick": tick if pane == 0 else 60, "readOnly": read_only or pane != 0,
                             "renderedGroundCells": 120} for pane, (x, y, w, h) in enumerate(rects)]
        result.append(row)
    return result


def archive():
    return {"passed": True, "noGameEngine": True, "protocolVersion": 7, "containerVersion": 3,
            "teamId": 0, "slices": 12, "samples": 24, "views": 24, "visualViews": 24,
            "firstTick": 60, "finalTick": 780, "joinedAt": 61440, "historyFrom": 61440,
            "from": 61440, "until": 800000, "completeIndex": True, "readOnly": True,
            "gapCount": 0, "hasGaps": False, "partial": True, "terminalCovered": False,
            "staticVisualConsistent": True, **visual()}


class TimelineCanvasTest(unittest.TestCase):
    def test_four_simultaneous_views_and_single_live_view_pass(self):
        for read_only, views in ((False, 4), (False, 1), (True, 4)):
            result = acceptance.validate_canvas(records(read_only, views), read_only, views)
            self.assertEqual(views, result["viewCount"])
            self.assertEqual(2, result["completePaneProbes"])
            self.assertIs(result["readOnly"], read_only)

    def test_submitted_view_count_alone_cannot_replace_actual_four_renders(self):
        for mutation in ("ground", "count", "missing", "duplicate", "active"):
            data = records()
            if mutation == "ground":
                data[-1]["viewports"][3]["renderedGroundCells"] = 0
                data[-1]["renderedViewCount"] = 3
            elif mutation == "count":
                data[-1]["renderedViewCount"] = 3
            elif mutation == "missing":
                data[-1]["viewports"].pop()
            elif mutation == "duplicate":
                data[-1]["viewports"][3]["paneId"] = 2
            else:
                data[-1]["activePane"] = 1
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                acceptance.validate_canvas(data, expected_views=4)

    def test_geometry_must_cover_the_real_odd_sized_viewport_without_overlap(self):
        for pane, field, value in ((1, "x", 639), (2, "y", 375), (3, "width", 640),
                                    (0, "height", 0), (0, "width", True)):
            data = records(); data[-1]["viewports"][pane][field] = value
            with self.subTest(pane=pane, field=field), self.assertRaises(RuntimeError):
                acceptance.validate_canvas(data, expected_views=4)

    def test_every_historical_pane_is_read_only_in_every_probe(self):
        for read_only in (False, True):
            for pane in range(4):
                data = records(read_only)
                data[0]["viewports"][pane]["readOnly"] = not (read_only or pane != 0)
                with self.subTest(read_only=read_only, pane=pane), self.assertRaises(RuntimeError):
                    acceptance.validate_canvas(data, read_only, 4)
        data = records(); data[-1]["viewports"][1]["readOnly"] = 1
        with self.assertRaises(RuntimeError):
            acceptance.validate_canvas(data, expected_views=4)

    def test_loading_cannot_count_as_a_presented_world(self):
        data = records()
        first = data[0]
        first.update({key: 0 for key in (*acceptance.EXPECTED_PUBLIC_VISUAL, "renderedGroundCells", "renderedDecorationCells")})
        first.update({key: "" for key in acceptance.VISUAL_HASH_FIELDS})
        first.update(viewSequence=-1, tick=0, renderedViewCount=0)
        for pane in first["viewports"]:
            pane.update(viewSequence=-1, tick=0, renderedGroundCells=0)
        self.assertEqual(2, acceptance.validate_canvas(data, expected_views=4)["completePaneProbes"])
        data[-1] = copy.deepcopy(first)
        data[-1].update(submittedFrames=100, presentedRevision=99)
        with self.assertRaises(RuntimeError):
            acceptance.validate_canvas(data, expected_views=4)

    def test_no_engine_and_actual_presentation_are_mandatory(self):
        for field, value in (("noGameEngine", False), ("backend", "OpenGL"), ("readOnly", True),
                              ("presentedRevision", -1), ("connected", False)):
            data = records(); data[1][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                acceptance.validate_canvas(data, expected_views=4)

    def test_v6_counters_and_missing_v7_checks_are_rejected(self):
        for field, value in (("protocolVersion", 6), ("transactions", 0), ("stateChecks", 0),
                              ("stateAcks", 0), ("restores", 1), ("stateMismatches", 1),
                              ("writtenBytes", 0), ("recvBytes", True)):
            data = records(); data[-1]["wire"][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                acceptance.validate_canvas(data, expected_views=4)
        data = records(); del data[0]["wire"]["protocolVersion"]
        with self.assertRaises(RuntimeError):
            acceptance.validate_canvas(data, expected_views=4)

    def test_replay_has_no_network_and_never_enables_the_action_probe(self):
        for mutation in ("wire", "probe"):
            data = records(True)
            if mutation == "wire": data[1]["wire"] = records()[1]["wire"]
            else: data[0]["actionProbe"]["enabled"] = True
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                acceptance.validate_canvas(data, True, 4)

    def test_network_must_complete_real_selection_production_cancellation(self):
        for field in ("completed", "panelSeen", "queueObserved", "cancelObserved", "uiActionCount"):
            data = records(); data[-1]["actionProbe"][field] = 2 if field == "uiActionCount" else False
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                acceptance.validate_canvas(data, expected_views=4)


class TimelineArchiveTest(unittest.TestCase):
    def test_partial_recording_is_valid_without_claiming_complete_match(self):
        data = archive()
        canvas = acceptance.validate_canvas(records(), expected_views=4)
        self.assertIs(data, acceptance.validate_recording_inspection(data, 0, canvas))
        data.update(gapCount=1, hasGaps=True)
        acceptance.validate_recording_inspection(data, 0)
        data.update(terminalCovered=True)
        acceptance.validate_recording_inspection(data, 0)

    def test_false_complete_or_wrong_format_is_rejected(self):
        for field, value in (("protocolVersion", 6), ("containerVersion", 2), ("completeIndex", False),
                              ("readOnly", False), ("partial", False), ("hasGaps", True),
                              ("samples", 25), ("views", 23), ("visualViews", 23), ("noGameEngine", False),
                              ("historyFrom", 61439), ("from", 61439), ("until", 61440)):
            data = archive(); data[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                acceptance.validate_recording_inspection(data, 0)

    def test_every_slice_requires_boundary_sampling_and_consistent_public_map(self):
        for field in ("samples", "slices", "staticVisualConsistent", "groundSha256"):
            data = archive(); del data[field]
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                acceptance.validate_recording_inspection(data, 0)
        data = archive(); data["groundSha256"] = "c" * 64
        with self.assertRaises(RuntimeError):
            acceptance.validate_recording_inspection(data, 0, acceptance.validate_canvas(records(), expected_views=4))


class SafetyRulesUnchangedTest(unittest.TestCase):
    def test_pins_and_reviewed_guard_bodies_match_frozen_review_hashes(self):
        for name, expected in REVIEWED_PINS.items():
            self.assertEqual(expected, getattr(acceptance, name), name)
        for name, expected in REVIEWED_GUARD_SHA256.items():
            actual = hashlib.sha256(inspect.getsource(getattr(acceptance, name)).encode()).hexdigest()
            self.assertEqual(expected, actual, name)

    def test_acceptance_contains_no_assert_that_optimization_can_remove(self):
        tree = ast.parse(Path(acceptance.__file__).read_text())
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(tree)))

    def test_event_failure_and_focus_escape_still_fail_closed(self):
        bounds = [2000, 0, 1920, 1080]
        row = {"kind": "rwx-background-agent", "event": "window", "failed": False, "edt": True,
               "graphicsBounds": bounds, "frame": [2010, 10, 1280, 752], "actualActive": False,
               "actualFocused": False, "everActive": False, "everOutsideBounds": False,
               "autoRequestFocus": False, "focusableWindowState": False}
        acceptance.validate_agent([row], bounds)
        for field, value in (("event", "failure"), ("actualActive", True), ("actualFocused", True),
                              ("everOutsideBounds", True), ("frame", [1999, 10, 1280, 752])):
            changed = dict(row, **{field: value})
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                acceptance.validate_agent([changed], bounds)

    def test_inactive_physical_main_and_known_pending_display_are_rejected(self):
        for pending in (False, True):
            rows = [{"id": 1, "main": 1, "active": pending, "modeAvailable": True}]
            if pending: rows.append({"id": 999, "vendor": 29561, "serial": 807753347})
            with self.subTest(pending=pending), self.assertRaises(RuntimeError):
                acceptance.validate_preflight([{"kind": "before", "displays": rows}])

    def test_resource_leak_and_exited_pid_rules_are_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "native.log"
            log.write_text("WARNING: native access warning\n")
            self.assertTrue(acceptance.validate_resources(log)["passed"])
            for bad in ("Freeing 2 leaked images", "Authority UI font loading: failure"):
                log.write_text(bad)
                with self.assertRaises(RuntimeError): acceptance.validate_resources(log)
        process = Mock(); process.poll.return_value = 0
        acceptance.stop_process(process)
        process.terminate.assert_not_called(); process.kill.assert_not_called()


def preflight_record():
    return {"kind": "before", "virtualDisplayApiAvailable": 1, "displays": [
        {"id": 1, "main": 1, "active": True, "modeAvailable": True},
    ]}


def mirrored_inactive_preflight_record():
    # Observed layout 2026-11-07 (synthetic identifiers): the active main stayed unchanged, while this inactive
    # original mirror transiently changed reported mode after virtual registration.
    return {"kind": "before", "virtualDisplayApiAvailable": 1, "displays": [
        {"id": 1, "main": 0, "active": False, "modeAvailable": True,
         "vendor": 1552, "serial": 42, "inMirrorSet": 1, "mirrorOf": 3,
         "pixelsWide": 1920, "pixelsHigh": 1080,
         "mode": {"width": 1920, "height": 1080}},
        {"id": 3, "main": 1, "active": True, "modeAvailable": True,
         "vendor": 25001, "serial": 1, "inMirrorSet": 1, "mirrorOf": 0,
         "pixelsWide": 1920, "pixelsHigh": 1080,
         "mode": {"width": 1920, "height": 1080}},
    ]}

def window_record():
    # Agent v8 uses kind as a schema tag and event to identify each observation.
    return {"kind": "rwx-background-agent", "event": "window", "failed": False,
            "edt": True, "graphicsBounds": [1470, 0, 1920, 1080],
            "frame": [1490, 20, 1280, 752], "actualActive": False, "actualFocused": False,
            "everActive": False, "everOutsideBounds": False, "autoRequestFocus": False,
            "focusableWindowState": False, "showing": True}

class WindowEvidenceTest(unittest.TestCase):
    bounds = [1470, 0, 1920, 1080]

    def test_failure_event_is_not_missed_when_kind_remains_the_agent_schema_tag(self):
        healthy = window_record()
        acceptance.validate_agent([healthy], self.bounds)
        for event in ("failure", "haltInvalidShownWindow"):
            # Keep failed=False deliberately: this exercises event dispatch independently of
            # the sticky failure flag, and catches the old kind-versus-event parsing bug.
            failure = {"kind": "rwx-background-agent", "event": event, "failed": False}
            with self.subTest(event=event), self.assertRaises(RuntimeError):
                acceptance.validate_agent([failure, healthy], self.bounds)

    def test_out_of_bounds_or_wrong_display_window_is_rejected(self):
        for field, value in (
            ("frame", [1469, 20, 1280, 752]),
            ("frame", [1490, -1, 1280, 752]),
            ("frame", [3000, 20, 1280, 752]),
            ("frame", [1490, 500, 1280, 752]),
            ("graphicsBounds", [0, 0, 1470, 956]),
        ):
            unsafe = window_record()
            unsafe[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                acceptance.validate_agent([unsafe], self.bounds)

    def test_current_and_sticky_focus_violations_are_rejected(self):
        healthy = window_record()
        for field in ("actualActive", "actualFocused", "everActive", "everOutsideBounds",
                      "autoRequestFocus", "focusableWindowState"):
            unsafe = copy.deepcopy(healthy)
            unsafe[field] = True
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                acceptance.validate_agent([unsafe, healthy], self.bounds)

    def test_cleanup_never_signals_an_already_exited_child(self):
        child = Mock()
        child.poll.return_value = 0
        acceptance.stop_process(child)
        child.terminate.assert_not_called()
        child.kill.assert_not_called()
        child.wait.assert_not_called()

class DisplayPreflightTest(unittest.TestCase):
    def test_awake_main_with_available_current_mode_passes_read_only_preflight(self):
        report = acceptance.validate_preflight([preflight_record()])
        self.assertEqual(1, report["mainDisplayId"])
        self.assertIs(report["mainActive"], True)
        self.assertIs(report["mainModeAvailable"], True)

    def test_active_main_with_inactive_original_mirror_is_rejected_before_creation(self):
        for reverse in (False, True):
            row = mirrored_inactive_preflight_record()
            if reverse:
                row["displays"].reverse()
            with self.subTest(reverse=reverse), self.assertRaisesRegex(RuntimeError, "An original display is inactive"):
                acceptance.validate_preflight([row])

    def test_every_original_requires_literal_true_without_changing_all_active_behavior(self):
        for value in (None, False, 0, 1, "true", {}):
            row = mirrored_inactive_preflight_record()
            if value is None:
                del row["displays"][0]["active"]
            else:
                row["displays"][0]["active"] = value
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "An original display is inactive"):
                acceptance.validate_preflight([row])
        row = preflight_record()
        row["displays"].append({"id": 3, "main": 0, "active": True, "modeAvailable": True})
        report = acceptance.validate_preflight([row])
        self.assertIs(report["allOriginalDisplaysActive"], True)
        self.assertEqual(2, report["displayCount"])

    def test_known_pending_display_blocks_awake_main_even_with_reused_id_or_changed_bounds(self):
        for display_id in (50, 901):
            for active in (False, True):
                row = preflight_record()
                row["displays"].append({"id": display_id, "main": 0, "active": active, "modeAvailable": True,
                    "vendor": 29561, "serial": 807753347,
                    "bounds": {"x": -3840, "y": 240, "width": 1280, "height": 720}})
                with self.subTest(display_id=display_id, active=active), self.assertRaisesRegex(RuntimeError, "Known pending owned display"):
                    acceptance.validate_preflight([row])

    def test_removed_pending_tuple_and_other_displays_keep_existing_preflight_behavior(self):
        row = preflight_record()
        self.assertIs(acceptance.validate_preflight([row])["pendingOwnedDisplaysAbsent"], True)
        # ID 50, the same vendor, or the same serial alone cannot identify the pending display.
        for vendor, serial in ((29561, 807753348), (29562, 807753347), (1552, 4251086178)):
            row = preflight_record()
            row["displays"].append({"id": 50, "main": 0, "active": True, "modeAvailable": True,
                                    "vendor": vendor, "serial": serial})
            with self.subTest(vendor=vendor, serial=serial):
                self.assertIs(acceptance.validate_preflight([row])["pendingOwnedDisplaysAbsent"], True)

    def test_inactive_main_is_rejected_even_when_an_existing_virtual_display_is_active(self):
        row = preflight_record()
        row["displays"][0]["active"] = False
        row["displays"].append({"id": 50, "main": 0, "active": True, "modeAvailable": False})
        with self.assertRaisesRegex(RuntimeError, "Main display is inactive"):
            acceptance.validate_preflight([row])

    def test_missing_or_false_main_mode_is_rejected(self):
        for value in (None, False, 0, "true"):
            row = preflight_record()
            if value is None:
                del row["displays"][0]["modeAvailable"]
            else:
                row["displays"][0]["modeAvailable"] = value
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "current mode is unavailable"):
                acceptance.validate_preflight([row])

    def test_rejected_preflight_saves_read_only_evidence_and_never_creates_display_or_gui(self):
        for failure in ("inactive", "missing-mode", "pending-display", "inactive-original-mirror"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(prefix="rwxx-preflight-test-") as directory:
                root = Path(directory).resolve()
                java, manager, agent, jar = [root / name for name in ("java", "manager", "agent.jar", "app.jar")]
                for path in (java, manager, agent):
                    path.write_bytes(b"offline fixture")
                guarded = b"offline guarded class"
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr("fixture/Guarded.class", guarded)
                    archive.writestr("io/github/rwx/authoritative/AuthoritativeKoolClient.class", b"offline client")
                    for name in ("TimelineViewConnection", "TimelineReplaySource", "TimelineReplayInspectMain"):
                        archive.writestr(f"io/github/rwx/authoritative/{name}.class", b"offline timeline fixture")
                snapshot = preflight_record()
                if failure == "inactive":
                    snapshot["displays"][0]["active"] = False
                elif failure == "missing-mode":
                    del snapshot["displays"][0]["modeAvailable"]
                elif failure == "inactive-original-mirror":
                    snapshot = mirrored_inactive_preflight_record()
                else:
                    snapshot["displays"].append({"id": 901, "main": 0, "active": True, "modeAvailable": True,
                                                "vendor": 29561, "serial": 807753347})

                def read_only_list(command, **kwargs):
                    self.assertEqual([str(manager), "--list"], command)
                    self.assertEqual(10, kwargs["timeout"])
                    kwargs["stdout"].write(json.dumps(snapshot) + "\n")
                    return Mock(returncode=0)

                output = root / "evidence"
                argv = [f"--java={java}", f"--jar={jar}", f"--manager={manager}",
                        f"--agent={agent}", f"--output-dir={output}"]
                with patch.multiple(acceptance, MANAGER_SHA256=acceptance.sha256(manager),
                                    AGENT_SHA256=acceptance.sha256(agent),
                                    TARGET_CLASSES={"fixture/Guarded": hashlib.sha256(guarded).hexdigest()}), \
                        patch.object(acceptance.sys, "platform", "darwin"), \
                        patch.object(acceptance.os, "access", return_value=True), \
                        patch.object(acceptance.signal, "signal", return_value=acceptance.signal.SIG_DFL), \
                        patch.object(acceptance.subprocess, "run", side_effect=read_only_list) as listing, \
                        patch.object(acceptance.subprocess, "Popen") as launch, patch("builtins.print"):
                    self.assertEqual(1, acceptance.main(argv))
                    listing.assert_called_once()
                    launch.assert_not_called()
                result = json.loads((output / "result.json").read_text())
                self.assertFalse(result["passed"])
                self.assertFalse(result["preflight"]["passed"])
                self.assertEqual([], result["processes"])
                self.assertNotIn("manager", result)
                self.assertEqual(1, len(result["failures"]), "A rejected preflight owes no manager cleanup")
                self.assertIs(result["cleanup"]["required"], False)
                self.assertFalse((output / "manager.ndjson").exists())
                self.assertEqual(snapshot, json.loads((output / "preflight.ndjson").read_text()))

class NativeResourceEvidenceTest(unittest.TestCase):
    def check_log(self, text):
        with tempfile.TemporaryDirectory(prefix="rwxx-resource-evidence-") as directory:
            path = Path(directory) / "client.log"
            path.write_text(text)
            return acceptance.validate_resources(path)

    def test_normal_native_access_warnings_do_not_fail_resource_cleanup(self):
        report = self.check_log(
            "WARNING: A restricted method in java.lang.System has been called\n"
            "WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning\n"
            "[MoltenVK] Vulkan instance initialized\n"
            "Releasing render backend\n"
        )
        self.assertTrue(report["passed"])
        self.assertEqual(64, len(report["logSha256"]))

    def test_leaked_native_images_are_rejected_even_with_embedded_ansi_color(self):
        for warning in ("Freeing 1 leaked images", "\x1b[31mFreeing 12 leaked images\x1b[0m",
                        "Freeing \x1b[33m2\x1b[0m leaked images"):
            with self.subTest(warning=warning), self.assertRaises(RuntimeError):
                self.check_log("Renderer stopped\n" + warning + "\nNormal process exit\n")

    def test_font_loading_failure_is_rejected_despite_successful_process_exit(self):
        with self.assertRaises(RuntimeError):
            self.check_log("Authority UI font loading: atlas registration failed\nProcess exited with code 0\n")

class ExplicitProbeEnvironmentTest(unittest.TestCase):
    def test_only_explicit_gui_environment_enables_probe_without_mutating_base_environment(self):
        base = {"PATH": "/safe/bin", "RWX_AUTHORITY_ACTION_PROBE": "0"}
        opted_in = acceptance.action_probe_environment(base)
        self.assertEqual({"PATH": "/safe/bin", "RWX_AUTHORITY_ACTION_PROBE": "1"}, opted_in)
        self.assertEqual("0", base["RWX_AUTHORITY_ACTION_PROBE"])


if __name__ == "__main__":
    unittest.main()
