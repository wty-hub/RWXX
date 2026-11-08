"""Offline ownership/evidence tests. No actual subprocesses, signals, displays or JVMs."""
from datetime import datetime, timezone
from pathlib import Path
import signal
import tempfile
import unittest
from unittest.mock import Mock, patch
import copy

import authoritative_menu_acceptance as acceptance


class OwnedProcessTest(unittest.TestCase):
    root = Path("/private/isolated-acceptance/rooms")
    jar = Path("/private/artifacts/game.jar")
    birth = 1793941380

    def row(self, pid=101, parent=100, mode="--authority-host", birth=None, command=None):
        return {"pid": pid, "ppid": parent, "state": "S", "startSecond": self.birth if birth is None else birth,
                "command": (f"java -Dlaunch.dir={self.root}/room-test -jar {self.jar} {mode}"
                            if command is None else command)}

    def metadata(self, pid=101, birth=None):
        instant = datetime.fromtimestamp(self.birth if birth is None else birth, timezone.utc).isoformat()
        return {"clients": [{"pid": pid, "startInstant": instant}]}

    def test_running_parent_binds_gui_and_nested_server_before_metadata_is_written(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        parent = Mock(pid=100)
        parent.poll.return_value = None
        rows = {101: self.row(), 102: self.row(102, 101, "--authority-server"),
                200: self.row(200, 1, command="unrelated GUI")}
        with patch.object(acceptance, "process_table", return_value=rows):
            owned.observe(parent, [])
            self.assertEqual([101, 102], sorted(owned.live()))
            self.assertEqual({"live-descendant"}, {row["source"] for row in owned.tracked.values()})

    def test_orphan_metadata_requires_birth_time_and_private_command(self):
        for row, metadata in (
            (self.row(birth=self.birth + 1), self.metadata()),
            (self.row(command="java -jar another.jar --authority-host"), self.metadata()),
            (self.row(), {"clients": [{"pid": 101}]}),
        ):
            with self.subTest(row=row, metadata=metadata):
                owned = acceptance.OwnedChildren(self.root, self.jar)
                with patch.object(acceptance, "process_table", return_value={101: row}):
                    with self.assertRaises(RuntimeError):
                        owned.observe(None, [metadata])

    def test_new_descendant_exec_transition_requires_full_command_before_binding(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        parent = Mock(pid=100)
        parent.poll.return_value = None
        transitional = self.row(command="/jdk/lib/jspawnhelper 25.0.4 4:5:6")
        with patch.object(acceptance, "process_table", side_effect=[{101: transitional}, {101: self.row()}]), \
                patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
            final_table = owned.observe(parent, [self.metadata()])
            self.assertEqual([101], owned.live(final_table))
            self.assertEqual("probe-startInstant", owned.tracked[101]["source"])
            self.assertEqual(1, owned.identity_resamples[0]["attempts"])
            self.assertIs(owned.identity_resamples[0]["commandMatch"], True)
            kill.assert_not_called()

    def test_unconfirmed_exec_transition_expires_without_binding_or_signalling(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        parent = Mock(pid=100)
        parent.poll.return_value = None
        transitional = self.row(command="jspawnhelper private-token-must-not-appear")
        with patch.object(acceptance, "process_table", return_value={101: transitional}), \
                patch.object(acceptance.time, "monotonic", side_effect=[0, 0, 1]), \
                patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
            with self.assertRaisesRegex(RuntimeError, "pid=101.*commandMatch=False.*resamples=1") as caught:
                owned.observe(parent, [self.metadata()])
            self.assertNotIn("private-token", str(caught.exception))
            self.assertEqual({}, owned.tracked)
            kill.assert_not_called()

    def test_retry_cannot_accept_a_reused_pid(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        parent = Mock(pid=100)
        parent.poll.return_value = None
        transitional = self.row(command="jspawnhelper")
        replacement = self.row(birth=self.birth + 1)
        with patch.object(acceptance, "process_table", side_effect=[{101: transitional}, {101: replacement}]), \
                patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
            with self.assertRaisesRegex(RuntimeError, "expectedStartSecond=.*observedStartSecond=.*commandMatch=True"):
                owned.observe(parent, [self.metadata()])
            self.assertEqual({}, owned.tracked)
            kill.assert_not_called()

    def test_orphan_wrong_birth_and_previously_bound_command_change_never_retry(self):
        parent = Mock(pid=100)
        parent.poll.return_value = None
        for owner, row, tracked in (
            (None, self.row(command="jspawnhelper"), False),
            (parent, self.row(command="jspawnhelper", birth=self.birth + 1), False),
            (parent, self.row(command="jspawnhelper"), True),
        ):
            with self.subTest(owner=owner, row=row, tracked=tracked):
                owned = acceptance.OwnedChildren(self.root, self.jar)
                if tracked:
                    owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
                with patch.object(acceptance, "process_table", return_value={101: row}), \
                        patch.object(acceptance.time, "sleep") as sleep:
                    with self.assertRaises(RuntimeError):
                        owned.observe(owner, [self.metadata()])
                    sleep.assert_not_called()

    def test_verified_server_metadata_allows_individual_signal_only(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        server = self.row(102, 1, "--authority-server")
        metadata = {"serverPid": 102, "serverStartInstant": self.metadata()["clients"][0]["startInstant"]}
        with patch.object(acceptance, "process_table", return_value={102: server}), patch.object(acceptance.os, "kill") as kill:
            owned.observe(None, [metadata])
            owned.signal_live(signal.SIGTERM)
            kill.assert_called_once_with(102, signal.SIGTERM)
            self.assertEqual("probe-startInstant", owned.actions[0]["source"])

    def test_reused_or_exited_pid_is_never_signalled(self):
        for replacement in ({}, {101: self.row(birth=self.birth + 30, command="unrelated system helper")},
                            {101: dict(self.row(), state="Z")}):
            with self.subTest(replacement=replacement):
                owned = acceptance.OwnedChildren(self.root, self.jar)
                with patch.object(acceptance, "process_table", return_value={101: self.row()}):
                    owned.observe(None, [self.metadata()])
                with patch.object(acceptance, "process_table", return_value=replacement), patch.object(acceptance.os, "kill") as kill:
                    owned.signal_live(signal.SIGKILL)
                    self.assertEqual([], owned.live())
                    kill.assert_not_called()

    def test_unbound_or_replacement_authority_process_blocks_display_removal(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        with self.assertRaises(RuntimeError):
            owned.live({101: self.row()})
        owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
        with self.assertRaises(RuntimeError):
            owned.live({101: self.row(birth=self.birth + 1)})

    def test_pid_is_rechecked_immediately_before_signal(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
        replacement = self.row(birth=self.birth + 30, command="unrelated system helper")
        with patch.object(acceptance, "process_table", side_effect=[{101: self.row()}, {101: replacement}]), \
                patch.object(acceptance.os, "kill") as kill:
            owned.signal_live(signal.SIGTERM)
            kill.assert_not_called()

    def test_bound_exit_placeholders_require_disappearance_zombie_or_full_command(self):
        for placeholder in ("", "(java)", "<exiting>"):
            for final in ({}, {101: dict(self.row(), state="Z")}, {101: self.row()}):
                with self.subTest(placeholder=placeholder, final=final):
                    owned = acceptance.OwnedChildren(self.root, self.jar)
                    owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
                    with patch.object(acceptance, "process_table", side_effect=[
                            {101: self.row(command=placeholder)}, final]) as inspect, \
                            patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
                        table = owned.observe(None, [self.metadata()])
                        expected = [101] if final and "Z" not in final[101]["state"] else []
                        self.assertEqual(expected, owned.live(table))
                        self.assertEqual("bound-exit", owned.identity_resamples[0]["kind"])
                        self.assertLessEqual(inspect.call_args.kwargs["timeout"], 0.5)
                        kill.assert_not_called()

    def test_bound_exit_placeholder_timeout_fails_closed(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
        with patch.object(acceptance, "process_table", return_value={101: self.row(command="(java)")}), \
                patch.object(acceptance.time, "monotonic", side_effect=[0, 0, 0.01, 0.6]), \
                patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
            with self.assertRaisesRegex(RuntimeError, "Unresolved tracked process exit identity"):
                owned.live()
            kill.assert_not_called()

    def test_bound_exit_transition_cannot_hide_pid_reuse_or_another_command(self):
        for final in (self.row(birth=self.birth + 1), self.row(command="jspawnhelper secret-token")):
            with self.subTest(final=final):
                owned = acceptance.OwnedChildren(self.root, self.jar)
                owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
                with patch.object(acceptance, "process_table", side_effect=[
                        {101: self.row(command="<exiting>")}, {101: final}]), \
                        patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
                    with self.assertRaises(RuntimeError) as caught:
                        owned.live()
                    self.assertNotIn("secret-token", str(caught.exception))
                    kill.assert_not_called()

    def test_untracked_exit_placeholder_cannot_bind_an_orphan(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        with patch.object(acceptance, "process_table", return_value={101: self.row(command="<exiting>")}), \
                patch.object(acceptance.time, "sleep") as sleep, patch.object(acceptance.os, "kill") as kill:
            with self.assertRaises(RuntimeError):
                owned.observe(None, [self.metadata()])
            self.assertEqual({}, owned.tracked)
            sleep.assert_not_called()
            kill.assert_not_called()

    def test_signal_recheck_waits_for_bound_exit_without_signalling_a_placeholder(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
        with patch.object(acceptance, "process_table", side_effect=[
                {101: self.row()}, {101: self.row(command="")}, {}]), \
                patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
            owned.signal_live(signal.SIGTERM)
            kill.assert_not_called()

    def test_exit_inspection_timeout_is_an_error_not_disappearance(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
        with patch.object(acceptance, "process_table", side_effect=[
                {101: self.row(command="(java)")}, acceptance.subprocess.TimeoutExpired("ps", 0.1)]), \
                patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
            with self.assertRaises(acceptance.subprocess.TimeoutExpired):
                owned.live()
            kill.assert_not_called()

    def test_exit_resampling_cannot_hide_a_new_unbound_authority_process(self):
        owned = acceptance.OwnedChildren(self.root, self.jar)
        owned.tracked[101] = {"startSecond": self.birth, "source": "fixture"}
        with patch.object(acceptance, "process_table", side_effect=[
                {101: self.row(command="(java)")}, {102: self.row(pid=102, parent=1)}]), \
                patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
            with self.assertRaisesRegex(RuntimeError, "Unverified authority process appeared"):
                owned.signal_live(signal.SIGTERM)
            kill.assert_not_called()

    def test_all_tracked_identities_are_rechecked_in_the_final_exit_snapshot(self):
        for birth in (self.birth, self.birth + 1):
            with self.subTest(birth=birth):
                owned = acceptance.OwnedChildren(self.root, self.jar)
                owned.tracked = {pid: {"startSecond": self.birth, "source": "fixture"} for pid in (101, 102)}
                with patch.object(acceptance, "process_table", side_effect=[
                        {102: self.row(pid=102, command="(java)")}, {101: self.row(birth=birth)}]), \
                        patch.object(acceptance.time, "sleep"), patch.object(acceptance.os, "kill") as kill:
                    if birth == self.birth:
                        self.assertEqual([101], owned.live())
                    else:
                        with self.assertRaisesRegex(RuntimeError, "replacement authority process"):
                            owned.live()
                    kill.assert_not_called()


class MetadataEvidenceTest(unittest.TestCase):
    def test_process_output_parse_failure_cannot_be_reported_as_all_children_stopped(self):
        output = Mock(returncode=0, stdout="changed ps output format\n")
        with patch.object(acceptance.subprocess, "run", return_value=output):
            with self.assertRaises(RuntimeError):
                acceptance.process_table()

    def test_process_parser_uses_utc_untruncated_command_and_does_not_persist_argv(self):
        pid = acceptance.os.getpid()
        output = Mock(returncode=0, stdout=f"{pid} 1 S Fri Nov  6 09:03:00 2026 python controller.py\n")
        with patch.object(acceptance.subprocess, "run", return_value=output) as run:
            rows = acceptance.process_table()
            self.assertEqual(int(datetime(2026, 11, 6, 9, 3, tzinfo=timezone.utc).timestamp()), rows[pid]["startSecond"])
            self.assertIn("-ww", run.call_args.args[0])
            self.assertEqual("UTC", run.call_args.kwargs["env"]["TZ"])

    def test_process_parser_keeps_empty_argv_without_trailing_spaces(self):
        pid = acceptance.os.getpid()
        output = Mock(returncode=0, stdout=f"{pid} 1 E Fri Nov  6 09:03:00 2026\n")
        with patch.object(acceptance.subprocess, "run", return_value=output):
            rows = acceptance.process_table(timeout=0.2)
            self.assertEqual("", rows[pid]["command"])
            self.assertEqual("E", rows[pid]["state"])

    def test_final_metadata_must_be_complete_and_stay_in_private_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / "running.json"
            metadata.write_text('{"clients":')
            self.assertIsNone(acceptance.read_json(metadata))
            with self.assertRaises(RuntimeError):
                acceptance.read_json(metadata, final=True)
            with self.assertRaises(RuntimeError):
                acceptance.in_state_root(root / ".." / "unrelated.log", root)

    def test_startup_sound_cannot_be_enabled_even_if_final_preferences_are_muted(self):
        quiet = '[preferences]\nenableSounds="false"\nmasterVolume="0.0"\nmusicVolume="0.0"\ngameVolume="0.0"\ninterfaceVolume="0.0"\n'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "preferences.toml").write_text(quiet)
            startup = root / "startup-preferences.toml"
            startup.write_text(quiet)
            self.assertIn("startupPreferences", acceptance.validate_muted_from_start(root))
            for field in ("masterVolume", "musicVolume", "gameVolume", "interfaceVolume"):
                startup.write_text(quiet.replace(f'{field}="0.0"', f'{field}="1.0"'))
                with self.subTest(field=field), self.assertRaises(RuntimeError):
                    acceptance.validate_muted_from_start(root)
            startup.write_text(quiet.replace('enableSounds="false"', 'enableSounds="true"'))
            with self.assertRaises(RuntimeError):
                acceptance.validate_muted_from_start(root)


class DisplayPreflightTest(unittest.TestCase):
    def snapshot(self):
        return [{"kind": "before", "virtualDisplayApiAvailable": 1, "displays": [
            {"id": 1, "main": 1, "active": True, "modeAvailable": True,
             "bounds": {"x": 0, "y": 0, "width": 1470, "height": 956}}]}]

    def test_awake_main_display_allows_preflight(self):
        report = acceptance.validate_preflight(self.snapshot())
        self.assertIs(report["passed"], True)
        self.assertEqual(1, report["mainDisplayId"])

    def test_known_pending_tuple_blocks_menu_creation_after_main_recovers(self):
        for display_id in (50, 901):
            rows = self.snapshot()
            rows[0]["displays"].append({"id": display_id, "main": 0, "active": True, "modeAvailable": True,
                "vendor": 29561, "serial": 807753347,
                "bounds": {"x": -100, "y": 0, "width": 800, "height": 600}})
            with self.subTest(display_id=display_id), self.assertRaisesRegex(RuntimeError, "Known pending owned display"):
                acceptance.validate_preflight(rows)

    def test_other_display_with_reused_id_is_allowed_after_pending_tuple_is_absent(self):
        for vendor, serial in ((29561, 807753348), (29562, 807753347)):
            rows = self.snapshot()
            rows[0]["displays"].append({"id": 50, "main": 0, "active": True, "modeAvailable": True,
                                       "vendor": vendor, "serial": serial})
            with self.subTest(vendor=vendor, serial=serial):
                self.assertIs(acceptance.validate_preflight(rows)["pendingOwnedDisplaysAbsent"], True)
        self.assertIs(acceptance.validate_preflight(self.snapshot())["pendingOwnedDisplaysAbsent"], True)

    def test_sleeping_or_unreadable_main_display_is_rejected(self):
        for field in ("active", "modeAvailable"):
            for value in (False, None, "true", 1):
                rows = self.snapshot()
                rows[0]["displays"][0][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                    acceptance.validate_preflight(rows)

    def test_missing_ambiguous_or_failed_inventory_is_rejected(self):
        rows = self.snapshot()
        no_main = copy.deepcopy(rows)
        no_main[0]["displays"][0]["main"] = 0
        multiple_main = copy.deepcopy(rows)
        multiple_main[0]["displays"].append(dict(multiple_main[0]["displays"][0], id=2))
        failed = copy.deepcopy(rows) + [{"kind": "failure"}]
        no_api = copy.deepcopy(rows)
        no_api[0]["virtualDisplayApiAvailable"] = False
        stale_virtual = copy.deepcopy(rows)
        stale_virtual[0]["displays"].append({"id": 50, "main": 0, "active": True, "modeAvailable": False})
        for inventory in ([], no_main, multiple_main, failed, no_api, stale_virtual):
            with self.subTest(inventory=inventory), self.assertRaises(RuntimeError):
                acceptance.validate_preflight(inventory)


if __name__ == "__main__":
    unittest.main()
