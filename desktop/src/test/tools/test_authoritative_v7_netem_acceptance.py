import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import tarfile
import unittest
from unittest import mock

import authoritative_v7_netem_acceptance as launcher
import authoritative_v7_netem_worker as worker

CID = "a" * 64
OWNER = "b" * 32
NAME = "rwxx-netem-v7-" + OWNER
SHA = "c" * 64


def config(**changes):
    value = {"jar_sha256": SHA, "timeout_seconds": 120, "profile": {
        "name": "loss", "delay_ms": 10, "jitter_ms": 2, "loss_percent": 5,
        "duplicate_percent": 0, "reorder_percent": 0, "rate_mbit": 10, "seed": None}}
    value.update(changes)
    return value


def stats(tick=10):
    return {"observedThrough": tick * 1024, "tick": tick, "receivedBytes": tick * 1000, "writtenBytes": tick * 100,
            "stateChecks": tick, "stateAcks": tick, "stateMismatches": 0, "restores": 0, "transactions": tick,
            "mapCacheHits": 0}


def game():
    return {"durationSeconds": 10.1, "connections": 4, "connectionsChanged": 0, "transportFailures": 0,
            "mapContentKeyHex": "d" * 64, "clients": [
                {"teamId": team, "before": stats(10), "after": stats(20), "commandsSent": 10,
                 "observedPositionChanges": 9, "maxDisplacementWorld": 20.0, "movementObserved": True}
                for team in (0, 1, 2, 63)], "slowReader": {
                    "kind": "application-reader-hold", "teamId": 63, "requestedHoldSeconds": 4, "actualHoldSeconds": 4.0,
                    "otherClients": [{"teamId": team, "before": stats(12), "after": stats(16)} for team in (0, 1, 2)],
                    "slowBefore": stats(12), "slowAtRelease": stats(13), "slowAfter": stats(20),
                    "othersAdvanced": True, "recovered": True, "tcpZeroWindowClaimed": False, "completed": True}}


def tcp(retrans=0):
    return {"RetransSegs": retrans, "ActiveOpens": 4, "PassiveOpens": 4, "CurrEstab": 8,
            "EstabResets": 0, "InSegs": 100 + retrans, "OutSegs": 100 + retrans}


def inspection(create, changes=None):
    host = {"NetworkMode": "none", "ReadonlyRootfs": True, "Privileged": False, "CapDrop": ["ALL"], "CapAdd": ["NET_ADMIN"],
            "Memory": 2 * 1024 ** 3, "NanoCpus": 2_000_000_000, "PidsLimit": 512,
            "Tmpfs": {"/tmp": "rw,noexec,nosuid,nodev,size=768m"}, "SecurityOpt": ["no-new-privileges:true"]}
    host.update(changes or {})
    owner = create[create.index("--label") + 1].split("=", 1)[1]
    name = create[create.index("--name") + 1]
    return {"Id": CID, "Name": "/" + name, "Image": "sha256:owned",
            "Config": {"Labels": {launcher.OWNER_LABEL: owner, launcher.JAR_LABEL: SHA}, "Entrypoint": ["/usr/bin/python3"], "OpenStdin": True, "Tty": False},
            "HostConfig": host, "Mounts": []}


def completed(stdout="", returncode=0, stderr=""):
    return subprocess.CompletedProcess([], returncode, stdout, stderr)


class FakeProcess:
    def __init__(self, error=None):
        self.returncode = None; self.error = error; self.killed = False; self.events = []
    def result(self):
        return {"passed": True, "scope": worker.SCOPE, "jarSha256": SHA, "javaCommand": ["java"],
                "application": {"passed": True}, "artifacts": [{"file": "artifact-00-server.log", "bytes": 3,
                    "sha256": hashlib.sha256(b"log").hexdigest()}],
                "evidenceLease": {"protocol": "RELEASE-or-EOF", "timeoutSeconds": 30},
                "workerSha256": worker.file_hash(Path(worker.__file__)),
                "guardHelperSha256": worker.file_hash(Path(worker.guards.__file__))}
    def spawn(self, command, **kwargs):
        if command[:2] == ["docker", "start"]: return self
        if command[:2] == ["docker", "exec"]:
            if self.returncode is not None: raise AssertionError("Evidence must be exported before worker exit")
            self.events.append("export")
            return FakeTarProcess(self.result(), self.export_error if hasattr(self, "export_error") else None)
        raise AssertionError("Unexpected Popen " + repr(command))
    def receive(self):
        if self.error is not None:
            error = self.error; self.error = None; raise error
        self.events.append("result")
        return json.dumps(self.result()).encode() + b"\n", b"early worker log\n"
    def communicate(self, input=None, timeout=None):
        if input is not None:
            if input != b"RELEASE\n": raise AssertionError("Unexpected evidence lease input")
            self.events.append("release")
        self.returncode = -9 if self.killed else 0
        self.events.append("exit")
        return b"", b"final worker log\n"
    def poll(self): return self.returncode
    def kill(self): self.events.append("kill"); self.killed = True; self.returncode = -9


class FakeTarProcess:
    def __init__(self, report, error=None):
        self.report = report; self.returncode = None; self.error = error
    def poll(self): return self.returncode
    def kill(self): self.returncode = -9
    def wait(self, timeout=None): return self.returncode


def evidence_files(report):
    return {"result.json": json.dumps({k: v for k, v in report.items() if k != "evidenceLease"}).encode(),
            "application.json": json.dumps(report["application"]).encode(), "java.stdout.log": b"java log",
            "artifact-00-server.log": b"log"}


def tar_bytes(files, extra_members=()):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, body in files.items():
            entry = tarfile.TarInfo(name); entry.size = len(body); archive.addfile(entry, io.BytesIO(body))
        for entry, body in extra_members: archive.addfile(entry, io.BytesIO(body))
    return output.getvalue()


def fake_capture_tar(process, destination, timeout_seconds=20, max_bytes=launcher.MAX_EXPORT_BYTES):
    if process.error: raise process.error
    data = tar_bytes(evidence_files(process.report)); Path(destination).write_bytes(data); process.returncode = 0
    return {"bytes": len(data), "stderr": "", "returncode": 0}


def fake_read_worker_report(process, _timeout_seconds, buffers=None):
    stdout, stderr = process.receive()
    if buffers is not None: buffers[0].extend(stdout); buffers[1].extend(stderr)
    return stdout, stderr


REAL_READ_WORKER_REPORT = launcher.read_worker_report
REAL_CAPTURE_TAR_STREAM = launcher.capture_tar_stream


def docker_mock(changes=None, cp_error=False, foreign=False):
    create = []
    def run(command, **_kwargs):
        if command[1] == "create": create[:] = command; return completed(CID + "\n")
        if command[1:3] == ["container", "inspect"]:
            value = inspection(create, changes)
            if foreign: value["Config"]["Labels"] = {}
            return completed(json.dumps([value]))
        if command[1] == "cp":
            if cp_error: raise subprocess.TimeoutExpired(command, 20)
            return completed()
        if command[1] in ("stop", "rm"): return completed(CID + "\n")
        raise AssertionError("Unexpected external command " + repr(command))
    return run


class RealV7NetemOfflineTest(unittest.TestCase):
    def setUp(self):
        patch = mock.patch.object(launcher, "read_worker_report", side_effect=fake_read_worker_report)
        patch.start(); self.addCleanup(patch.stop)
        capture = mock.patch.object(launcher, "capture_tar_stream", side_effect=fake_capture_tar)
        capture.start(); self.addCleanup(capture.stop)

    def test_command_has_only_new_owned_isolated_container_and_explicit_jvm_limits(self):
        command = launcher.create_command("rwxx-netem-v7:local", NAME, OWNER, config())
        for option, value in (("--network", "none"), ("--cap-drop", "ALL"), ("--cap-add", "NET_ADMIN"),
                              ("--memory", "2g"), ("--cpus", "2.0"), ("--pids-limit", "512"),
                              ("--entrypoint", "/usr/bin/python3"), ("--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=768m")):
            self.assertEqual(value, command[command.index(option) + 1])
        self.assertIn("--interactive", command); self.assertIn("--read-only", command); self.assertIn("--pull=never", command)
        self.assertFalse(any(value in command for value in ("--privileged", "--volume", "-v", "--mount", "--device", "--pid", "--net=host")))
        self.assertNotIn("/var/run/docker.sock", " ".join(command))
        self.assertEqual(1, command.count("--cap-add"))

    def test_image_name_and_configuration_cannot_inject_flags_or_unbounded_settings(self):
        for image in ("--privileged", "image name", "image;evil"):
            with self.assertRaises(ValueError): launcher.create_command(image, NAME, OWNER, config())
        for changes in ({"jar_sha256": "x"}, {"timeout_seconds": 1000}, {"timeout_seconds": True}, {"extra": 1}):
            with self.assertRaises(ValueError): worker.validate_config(config(**changes))
        invalid = config(); invalid["profile"]["loss_percent"] = float("nan")
        with self.assertRaises(ValueError): worker.validate_config(invalid)
        invalid = config(); invalid["profile"]["device"] = "eth0"
        with self.assertRaises(ValueError): worker.validate_config(invalid)

    def test_snmp_reads_lifetime_retransmissions_and_connection_lifecycle_counters(self):
        raw = "Ip: Noise\nIp: 1\nTcp: RtoAlgorithm ActiveOpens PassiveOpens EstabResets CurrEstab InSegs OutSegs RetransSegs\nTcp: 1 4 4 0 8 100 105 7\n"
        self.assertEqual(7, worker.tcp_snmp(raw)["RetransSegs"])
        for invalid in ("", raw.replace(" 7\n", " broken\n"), raw.replace("RetransSegs", "Other")):
            with self.assertRaises(RuntimeError): worker.tcp_snmp(invalid)

    def test_game_validation_requires_actual_movement_repeated_full_checks_and_no_restoration(self):
        self.assertTrue(worker.evaluate_game(game())["actualMovement"])
        for key, value in (("stateMismatches", 1), ("restores", 1), ("stateChecks", 11), ("stateAcks", 11)):
            data = game(); data["clients"][0]["after"][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.evaluate_game(data)
        data = game(); data["clients"][0]["movementObserved"] = False
        with self.assertRaises(RuntimeError): worker.evaluate_game(data)
        data = game(); data["mapContentKeyHex"] = "not a SHA"
        with self.assertRaises(RuntimeError): worker.evaluate_game(data)

    def test_slow_reader_booleans_do_not_substitute_for_unaffected_peer_and_recovery_counters(self):
        data = game(); data.pop("slowReader")
        with self.assertRaises(RuntimeError): worker.evaluate_game(data)
        data = game(); data["slowReader"]["otherClients"][0]["after"] = stats(12)
        with self.assertRaises(RuntimeError): worker.evaluate_game(data)
        data = game(); data["slowReader"]["slowAfter"] = stats(13)
        with self.assertRaises(RuntimeError): worker.evaluate_game(data)
        data = game(); data["slowReader"]["tcpZeroWindowClaimed"] = True
        with self.assertRaises(RuntimeError): worker.evaluate_game(data)

    def test_loss_requires_real_post_handshake_data_retransmission_and_drops(self):
        self.assertEqual(3, worker.evaluate_tcp(config()["profile"], tcp(), tcp(3), {"droppedPackets": 2})["dataRetransmissionSegments"])
        for after, drops in ((tcp(), 1), (tcp(3), 0), (dict(tcp(3), ActiveOpens=5), 2), (dict(tcp(3), PassiveOpens=5), 2),
                             (dict(tcp(3), EstabResets=1), 2), (dict(tcp(3), CurrEstab=7), 2)):
            with self.assertRaises(RuntimeError): worker.evaluate_tcp(config()["profile"], tcp(), after, {"droppedPackets": drops})

    def test_phase_controller_installs_only_after_ready_and_removes_before_clean(self):
        events = []; report = {"commands": []}
        def checked(command, _log):
            events.append(command)
            if command[:3] == ["tc", "-j", "qdisc"]:
                installed = any("replace" in previous for previous in events)
                return json.dumps([{"kind": "netem", "handle": "1:", "root": True}] if installed else [{"kind": "noqueue", "handle": "0:", "root": True}])
            if "-s" in command: return "Sent 100 bytes 10 pkt (dropped 2, overlimits 0 requeues 0)\n backlog 0b 0p requeues 0\n"
            return ""
        controller = worker.PhaseController(config()["profile"], report, lambda value: events.append(value))
        self.assertEqual([], events)
        with mock.patch.object(worker, "snapshot", side_effect=[{"tcp": tcp()}, {"tcp": tcp(3)}]), \
                mock.patch.object(worker.guards, "checked", side_effect=checked):
            controller.accept({"kind": "rwxx-netem-phase", "phase": "ready", "connections": 4})
            controller.accept({"kind": "rwxx-netem-phase", "phase": "end", "connections": 4, "dataStats": game()})
        installed = next(index for index, event in enumerate(events) if isinstance(event, list) and "replace" in event)
        removed = next(index for index, event in enumerate(events) if isinstance(event, list) and "del" in event)
        self.assertLess(installed, events.index("START\n")); self.assertLess(removed, events.index("CLEAN\n"))
        self.assertEqual("complete", controller.phase); self.assertTrue(report["qdiscRemoved"])

    def test_phase_rejects_out_of_order_messages_or_unfinished_handshake_without_touching_tc(self):
        report = {"commands": []}; controller = worker.PhaseController(config()["profile"], report, mock.Mock())
        with mock.patch.object(worker.guards, "checked") as checked:
            with self.assertRaises(RuntimeError): controller.accept({"kind": "rwxx-netem-phase", "phase": "end", "connections": 4})
            with mock.patch.object(worker, "snapshot", return_value={"tcp": dict(tcp(), CurrEstab=6)}):
                with self.assertRaises(RuntimeError): controller.accept({"kind": "rwxx-netem-phase", "phase": "ready", "connections": 4})
            checked.assert_not_called()

    def test_unsupported_exact_impairment_never_sends_start_or_drops_requested_arguments(self):
        send = mock.Mock(); report = {"commands": []}
        controller = worker.PhaseController(dict(config()["profile"], seed=123), report, send)
        def checked(command, _log):
            if "replace" in command:
                self.assertEqual("123", command[command.index("seed") + 1])
                raise RuntimeError("seed unsupported")
            return '[{"kind":"noqueue","handle":"0:","root":true}]'
        with mock.patch.object(worker, "snapshot", return_value={"tcp": tcp()}), mock.patch.object(worker.guards, "checked", side_effect=checked):
            with self.assertRaisesRegex(RuntimeError, "unsupported"):
                controller.accept({"kind": "rwxx-netem-phase", "phase": "ready", "connections": 4})
        send.assert_not_called(); self.assertTrue(report["netemConfigurationAttempted"])

    def test_worker_refuses_host_before_any_files_commands_or_jvm(self):
        with mock.patch.object(worker.sys, "platform", "darwin"), mock.patch.object(worker.subprocess, "run") as run, \
                mock.patch.object(worker, "harness") as harness, mock.patch.object(worker.Path, "mkdir") as mkdir:
            result = worker.run(config())
        self.assertFalse(result["passed"]); self.assertFalse(result["preflightPassed"])
        run.assert_not_called(); harness.assert_not_called(); mkdir.assert_not_called()

    def test_foreign_qdisc_is_never_removed(self):
        with mock.patch.object(worker.guards, "checked", return_value='[{"kind":"netem","handle":"2:","root":true}]') as checked:
            with self.assertRaises(RuntimeError): worker.remove_owned_qdisc({"commands": []})
        self.assertEqual(1, checked.call_count)

    def test_launcher_success_exports_then_removes_only_the_exact_owned_id(self):
        process = FakeProcess()
        docker = docker_mock()
        def run_owned(command, **kwargs):
            if command[1] == "cp":
                self.assertIsNone(process.poll(), "Copy must happen before releasing the live tmpfs lease")
                process.events.append("copy")
            return docker(command, **kwargs)
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(launcher.subprocess, "run", side_effect=run_owned) as run, \
                mock.patch.object(launcher.subprocess, "Popen", side_effect=process.spawn) as popen:
            result = launcher.run("rwxx-netem-v7:local", Path(directory) / "new", config())
            self.assertTrue(result["passed"]); self.assertTrue(result["cleanup"]["removed"])
            self.assertEqual(["result", "export", "release", "exit"], process.events)
            self.assertEqual(["docker", "start", "--attach", "--interactive", CID], popen.call_args_list[0].args[0])
            self.assertEqual(subprocess.PIPE, popen.call_args_list[0].kwargs["stdin"])
            self.assertEqual(["docker", "exec", CID, "/usr/bin/tar", "-C", "/tmp/rwxx-v7-evidence", "-cf", "-", "--"],
                             popen.call_args_list[1].args[0][:9])
            self.assertEqual(1, result["evidenceCopy"]["workerManifestFilesVerified"])
            self.assertTrue(result["evidenceCopy"]["workerAliveBeforeCopy"])
            self.assertEqual(["docker", "rm", "--force", CID], run.call_args_list[-1].args[0])
            self.assertFalse(any(call.args[0][1] == "cp" for call in run.call_args_list))

    def test_resource_or_network_mismatch_never_starts_java_but_still_removes_our_container(self):
        for changes in ({"NetworkMode": "host"}, {"CapAdd": ["SYS_ADMIN"]}, {"Memory": 1}, {"Tmpfs": {}}, {"Binds": ["/host:/host"]}):
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock(changes)) as run, \
                    mock.patch.object(launcher.subprocess, "Popen") as popen:
                result = launcher.run("image", Path(directory) / "new", config())
            self.assertFalse(result["passed"]); self.assertTrue(result["cleanup"]["removed"]); popen.assert_not_called()
            self.assertEqual(["docker", "rm", "--force", CID], run.call_args_list[-1].args[0])

    def test_timeout_interruption_and_failed_evidence_copy_never_skip_exact_cleanup(self):
        for error, cp_error in ((subprocess.TimeoutExpired("attach", 150), False), (KeyboardInterrupt(), False), (None, True)):
            process = FakeProcess(error)
            if cp_error: process.export_error = subprocess.TimeoutExpired("exec tar", 20)
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock(cp_error=cp_error)) as run, \
                    mock.patch.object(launcher.subprocess, "Popen", side_effect=process.spawn):
                result = launcher.run("image", Path(directory) / "new", config())
            self.assertFalse(result["passed"]); self.assertTrue(result["cleanup"]["removed"])
            self.assertIn("release", process.events)
            self.assertEqual(["docker", "rm", "--force", CID], run.call_args_list[-1].args[0])

    def test_worker_lease_accepts_release_or_eof_and_has_a_real_bounded_deadline(self):
        for payload, expected in ((b"RELEASE\n", "released"), (b"", "eof")):
            read_fd, write_fd = os.pipe()
            with os.fdopen(read_fd, "rb", buffering=0) as stream:
                if payload: os.write(write_fd, payload)
                os.close(write_fd)
                self.assertEqual(expected, worker.wait_for_release(stream, .1))
        read_fd, write_fd = os.pipe()
        try:
            with os.fdopen(read_fd, "rb", buffering=0) as stream:
                self.assertEqual("timeout", worker.wait_for_release(stream, .001))
        finally: os.close(write_fd)

    def test_worker_lease_rejects_partial_or_extra_control_commands(self):
        for payload in (b"OTHER\n", b"RELEASE\nX", b"RELE"):
            read_fd, write_fd = os.pipe()
            with os.fdopen(read_fd, "rb", buffering=0) as stream:
                os.write(write_fd, payload); os.close(write_fd)
                with self.assertRaises(RuntimeError): worker.wait_for_release(stream, .1)

    def test_result_reader_returns_before_worker_exit_and_preserves_partial_output_on_failure(self):
        class Pipes: pass
        process = Pipes()
        read_fd, write_fd = os.pipe(); err_fd, err_write_fd = os.pipe()
        try:
            with os.fdopen(read_fd, "rb", buffering=0) as process.stdout, os.fdopen(err_fd, "rb", buffering=0) as process.stderr:
                os.write(err_write_fd, b"diagnostic\n"); os.write(write_fd, b'{"passed":true}\n')
                raw, _stderr = REAL_READ_WORKER_REPORT(process, .1)
                self.assertTrue(json.loads(raw)["passed"])
                # The writers are still open: a wait-for-process-exit implementation would time out.
                buffers = (bytearray(), bytearray())
                os.write(write_fd, b'{"partial":'); os.close(write_fd); write_fd = None
                with self.assertRaisesRegex(RuntimeError, "without a complete"):
                    REAL_READ_WORKER_REPORT(process, .1, buffers)
                self.assertEqual(b'{"partial":', buffers[0])
        finally:
            if write_fd is not None: os.close(write_fd)
            os.close(err_write_fd)

    def test_release_is_still_sent_when_provenance_or_live_copy_validation_fails(self):
        class MissingLease(FakeProcess):
            def result(self):
                result = super().result(); result.pop("evidenceLease"); return result
        for process, cp_error in ((MissingLease(), False), (FakeProcess(), True)):
            if cp_error: process.export_error = subprocess.TimeoutExpired("exec tar", 20)
            with tempfile.TemporaryDirectory() as directory, \
                    mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock(cp_error=cp_error)), \
                    mock.patch.object(launcher.subprocess, "Popen", side_effect=process.spawn):
                result = launcher.run("image", Path(directory) / "new", config())
            self.assertFalse(result["passed"]); self.assertTrue(result["cleanup"]["removed"])
            self.assertEqual(1, process.events.count("release"))

    def test_tar_extract_verifies_every_manifest_artifact_and_binds_both_json_reports(self):
        report = FakeProcess().result()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / "evidence.tar"; source.write_bytes(tar_bytes(evidence_files(report)))
            exported = launcher.extract_evidence_tar(source, root / "new", report)
            self.assertEqual(1, exported["workerManifestFilesVerified"])
            self.assertEqual(4, len(exported["files"]))
            self.assertEqual(b"log", (root / "new/artifact-00-server.log").read_bytes())
            for name, changed in (("artifact-00-server.log", b"BAD"), ("application.json", b'{"passed":1}'), ("result.json", b'{}')):
                files = evidence_files(report); files[name] = changed
                source.write_bytes(tar_bytes(files))
                with self.subTest(name=name), self.assertRaises(RuntimeError):
                    launcher.extract_evidence_tar(source, root / (name + "-bad"), report)

    def test_tar_rejects_links_devices_directories_paths_and_duplicate_members(self):
        report = FakeProcess().result(); filename = "artifact-00-server.log"
        cases = []
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.BLKTYPE, tarfile.FIFOTYPE, tarfile.DIRTYPE):
            entry = tarfile.TarInfo(filename); entry.type = kind; entry.linkname = "/host/secret"
            cases.append((entry, b"", False))
        for name in ("../escape", "/absolute", "nested/file", ".", "./result.json", "--flag"):
            entry = tarfile.TarInfo(name); cases.append((entry, b"", False))
        duplicate = tarfile.TarInfo(filename); duplicate.size = 3; cases.append((duplicate, b"log", True))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, (entry, body, duplicate) in enumerate(cases):
                files = evidence_files(report)
                if not duplicate: files.pop(filename)
                source = root / f"{index}.tar"; source.write_bytes(tar_bytes(files, [(entry, body)]))
                with self.subTest(name=entry.name, kind=entry.type), self.assertRaises(RuntimeError):
                    launcher.extract_evidence_tar(source, root / f"new-{index}", report)

    def test_tar_rejects_missing_invalid_or_duplicate_manifest_before_exec(self):
        for change in (lambda value: value.pop("artifacts"), lambda value: value.update(artifacts=None),
                       lambda value: value["artifacts"].append(dict(value["artifacts"][0])),
                       lambda value: value["artifacts"][0].update(file="../escape"),
                       lambda value: value["artifacts"][0].update(bytes=True),
                       lambda value: value["artifacts"][0].update(sha256="x")):
            report = FakeProcess().result(); change(report)
            with tempfile.TemporaryDirectory() as directory, mock.patch.object(launcher.subprocess, "Popen") as popen:
                with self.assertRaises(RuntimeError): launcher.export_evidence(CID, directory, report)
                popen.assert_not_called()

    def test_tar_rejects_missing_manifest_file_oversize_and_existing_destination(self):
        report = FakeProcess().result()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / "evidence.tar"
            for name in ("artifact-00-server.log", "result.json"):
                files = evidence_files(report); files.pop(name); source.write_bytes(tar_bytes(files))
                with self.subTest(name=name), self.assertRaises(RuntimeError):
                    launcher.extract_evidence_tar(source, root / (name + "-missing"), report)
            source.write_bytes(tar_bytes(evidence_files(report)))
            with mock.patch.object(launcher, "MAX_EXPORT_BYTES", 100), self.assertRaises(RuntimeError):
                launcher.extract_evidence_tar(source, root / "too-large", report)
            malformed = dict(report, artifacts=[dict(report["artifacts"][0], bytes=launcher.MAX_EXPORT_FILE_BYTES + 1)])
            with self.assertRaises(RuntimeError): launcher.extract_evidence_tar(source, root / "huge-file", malformed)
            destination = root / "existing"; destination.mkdir(); (destination / "result.json").write_text("KEEP")
            with self.assertRaises(FileExistsError): launcher.extract_evidence_tar(source, destination, report)
            self.assertEqual("KEEP", (destination / "result.json").read_text())

    def test_tar_rejects_more_than_64_members_and_trailing_hidden_content(self):
        report = FakeProcess().result()
        report["artifacts"] = [{"file": f"artifact-{n:02d}-server.log", "bytes": 1,
                                "sha256": hashlib.sha256(b"x").hexdigest()} for n in range(61)]
        files = evidence_files(report); files.pop("artifact-00-server.log")
        files.update({item["file"]: b"x" for item in report["artifacts"]})
        extra = tarfile.TarInfo("artifact-00-server.log"); extra.size = 1
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / "evidence.tar"
            source.write_bytes(tar_bytes(files, [(extra, b"x")]))
            with self.assertRaisesRegex(RuntimeError, "too many members"):
                launcher.extract_evidence_tar(source, root / "too-many", report)
            source.write_bytes(tar_bytes(evidence_files(FakeProcess().result())) + b"HIDDEN")
            with self.assertRaisesRegex(RuntimeError, "trailing"):
                launcher.extract_evidence_tar(source, root / "trailing", FakeProcess().result())

    def test_tar_stream_has_total_byte_budget_absolute_deadline_and_preserves_only_new_files(self):
        class Pipes:
            def wait(self, timeout=None): return 0
        for payload, limit, expected in ((b"1234567890", 4, RuntimeError), (b"", 100, subprocess.TimeoutExpired)):
            process = Pipes(); read_fd, write_fd = os.pipe(); err_fd, err_write_fd = os.pipe()
            try:
                with tempfile.TemporaryDirectory() as directory, os.fdopen(read_fd, "rb", buffering=0) as process.stdout, \
                        os.fdopen(err_fd, "rb", buffering=0) as process.stderr:
                    if payload: os.write(write_fd, payload)
                    with self.assertRaises(expected):
                        REAL_CAPTURE_TAR_STREAM(process, Path(directory) / "new.tar", timeout_seconds=.001, max_bytes=limit)
            finally: os.close(write_fd); os.close(err_write_fd)

    def test_failed_tar_validation_still_releases_lease_and_removes_only_owned_container(self):
        class WrongManifest(FakeProcess):
            def result(self):
                report = super().result(); report["artifacts"][0]["sha256"] = "0" * 64; return report
        process = WrongManifest()
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock()), \
                mock.patch.object(launcher.subprocess, "Popen", side_effect=process.spawn):
            result = launcher.run("image", Path(directory) / "new", config())
        self.assertFalse(result["passed"]); self.assertTrue(result["cleanup"]["removed"])
        self.assertIn("SHA-256", result["error"]); self.assertIn("release", process.events)

    def test_stale_worker_source_cannot_pass_with_the_same_game_jar(self):
        class StaleProcess(FakeProcess):
            def result(self):
                report = super().result(); report["workerSha256"] = "0" * 64
                return report
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock()), \
                mock.patch.object(launcher.subprocess, "Popen", side_effect=StaleProcess().spawn):
            result = launcher.run("image", Path(directory) / "new", config())
        self.assertFalse(result["passed"]); self.assertIn("provenance", result["error"])
        self.assertTrue(result["cleanup"]["removed"])

    def test_interrupted_create_resolves_only_the_exact_new_name_and_label_before_removal(self):
        create = []
        def run(command, **_kwargs):
            if command[1] == "create": create[:] = command; raise subprocess.TimeoutExpired(command, 20)
            if command[1:3] == ["container", "inspect"]:
                self.assertEqual(create[create.index("--name") + 1], command[-1])
                return completed(json.dumps([inspection(create)]))
            if command == ["docker", "rm", "--force", CID]: return completed(CID)
            raise AssertionError("Unexpected command " + repr(command))
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(launcher.subprocess, "run", side_effect=run) as commands, \
                mock.patch.object(launcher.subprocess, "Popen") as popen:
            result = launcher.run("image", Path(directory) / "new", config())
        self.assertFalse(result["passed"]); self.assertTrue(result["cleanup"]["removed"]); popen.assert_not_called()
        self.assertEqual(["docker", "rm", "--force", CID], commands.call_args_list[-1].args[0])

    def test_foreign_container_ownership_never_triggers_stop_copy_or_remove(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock(foreign=True)) as run, \
                mock.patch.object(launcher.subprocess, "Popen") as popen:
            result = launcher.run("image", Path(directory) / "new", config())
        self.assertFalse(result["passed"]); popen.assert_not_called()
        self.assertFalse(any(call.args[0][1] in ("stop", "cp", "rm") for call in run.call_args_list))

    def test_sanitized_export_never_copies_generated_tokens_or_user_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); work = root / "work"; evidence = root / "evidence"
            state = work / "application-state-owned"; state.mkdir(parents=True); evidence.mkdir()
            (state / "seats.properties").write_text("PRIVATE TOKEN MUST STAY HERE")
            (state / "server.log").write_text("server log")
            (state / "client-seat-0.rwxr").write_bytes(b"archive fixture")
            (work / "application.json").write_text('{"passed":false}')
            with mock.patch.object(worker, "WORK", work), mock.patch.object(worker, "EVIDENCE", evidence):
                report = {}; worker.collect_evidence(report)
            self.assertEqual(2, len(report["artifacts"]))
            self.assertFalse(any("seats" in path.name for path in evidence.iterdir()))
            self.assertFalse(any(b"PRIVATE TOKEN" in path.read_bytes() for path in evidence.iterdir()))


if __name__ == "__main__": unittest.main()
