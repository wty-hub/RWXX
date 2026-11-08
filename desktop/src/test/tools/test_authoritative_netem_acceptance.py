import json
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import unittest
from unittest import mock

import authoritative_netem_acceptance as launcher
import authoritative_netem_worker as worker


CID = "a" * 64


def profile(**changes):
    result = {"name": "loss", "delay_ms": 10, "jitter_ms": 2, "loss_percent": 5,
              "duplicate_percent": 0, "reorder_percent": 0, "rate_mbit": 10,
              "seed": None, "payload_bytes": 1024 * 1024, "timeout_seconds": 30}
    result.update(changes)
    return result


def completed(stdout="", returncode=0, stderr=""):
    return subprocess.CompletedProcess([], returncode, stdout, stderr)


def inspection(create, host_changes=None):
    owner = create[create.index("--label") + 1].split("=", 1)[1]
    host = {"NetworkMode": "none", "ReadonlyRootfs": True, "Privileged": False,
            "CapDrop": ["ALL"], "CapAdd": ["NET_ADMIN"], "Memory": 256 * 1024 * 1024,
            "NanoCpus": 1_000_000_000, "PidsLimit": 64}
    host.update(host_changes or {})
    return [{"Id": CID, "Name": "/" + create[create.index("--name") + 1], "Image": "sha256:own-image",
             "Config": {"Labels": {launcher.OWNER_LABEL: owner}, "Entrypoint": ["/usr/bin/python3"]},
             "HostConfig": host, "Mounts": []}]


def docker_mock(host_changes=None):
    create = []
    def run(cmd, **_kwargs):
        if cmd[1] == "create":
            create[:] = cmd
            return completed(CID + "\n")
        if cmd == ["docker", "container", "inspect", CID]:
            return completed(json.dumps(inspection(create, host_changes)))
        if cmd == ["docker", "rm", "--force", CID]:
            return completed(CID + "\n")
        raise AssertionError("Unexpected external command: " + repr(cmd))
    return run


class FakeProcess:
    def __init__(self, error=None):
        self.error, self.killed, self.returncode = error, False, 0
        self.inputs = []

    def communicate(self, input=None, timeout=None):
        self.inputs.append(input)
        if self.error is not None:
            error, self.error = self.error, None
            self.returncode = None
            raise error
        self.returncode = 0 if not self.killed else -9
        return (json.dumps({"passed": True, "scope": worker.SCOPE}).encode(), b"")

    def poll(self):
        return self.returncode

    def kill(self):
        self.killed = True


class NetemOfflineTest(unittest.TestCase):
    def test_container_command_has_only_the_authorized_isolation_and_limits(self):
        cmd = launcher.create_command("rwxx-netem:local", "rwxx-netem-own", "nonce")
        for option, value in [("--network", "none"), ("--cap-drop", "ALL"), ("--cap-add", "NET_ADMIN"),
                              ("--memory", "256m"), ("--cpus", "1.0"), ("--pids-limit", "64"),
                              ("--entrypoint", "/usr/bin/python3"),
                              ("--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=32m")]:
            self.assertEqual(value, cmd[cmd.index(option) + 1])
        self.assertIn("--read-only", cmd)
        self.assertIn("--pull=never", cmd)
        self.assertFalse(any(x in cmd for x in ("--privileged", "--volume", "-v", "--mount", "--device", "--pid", "--net=host")))
        self.assertNotIn("/var/run/docker.sock", " ".join(cmd))
        self.assertEqual(1, cmd.count("--cap-add"))
        image_index = cmd.index("rwxx-netem:local")
        self.assertEqual(["-u", "-B", "-", "--config-json"], cmd[image_index + 1:])

    def test_image_cannot_inject_docker_flags(self):
        for image in ("--privileged", "image name", "image;touch /tmp/x"):
            with self.assertRaises(ValueError):
                launcher.create_command(image, "name", "owner")

    def test_configuration_is_closed_bounded_and_rejects_nonfinite_numbers(self):
        self.assertEqual([profile()], worker.validate_config({"profiles": [profile()]}))
        for changes in ({"loss_percent": float("nan")}, {"seed": True}, {"seed": -1}, {"payload_bytes": 1},
                        {"reorder_percent": 2, "delay_ms": 0, "jitter_ms": 0}, {"jitter_ms": 20}, {"rate_mbit": -1}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                worker.validate_config({"profiles": [profile(**changes)]})
        extra = profile(); extra["device"] = "eth0"
        with self.assertRaises(ValueError):
            worker.validate_config({"profiles": [extra]})

    def test_all_impairments_are_exact_tc_arguments_never_shell_text(self):
        cmd = worker.netem_command(profile(seed=42, duplicate_percent=1, reorder_percent=2))
        for subsequence in (["dev", "lo"], ["delay", "10ms", "2ms"], ["loss", "5%"],
                            ["duplicate", "1%"], ["reorder", "2%"], ["rate", "10mbit"], ["seed", "42"]):
            start = cmd.index(subsequence[0]); self.assertEqual(subsequence, cmd[start:start + len(subsequence)])

    def test_linux_tcp_info_uses_total_retrans_not_transient_retrans_field(self):
        data = bytearray(256)
        struct.pack_into("=I", data, 36, 900)  # tcpi_retrans is not the lifetime counter.
        struct.pack_into("=I", data, 100, 17)
        endpoint = mock.Mock(); endpoint.getsockopt.return_value = bytes(data)
        self.assertEqual(17, worker.tcp_total_retrans(endpoint))
        endpoint.getsockopt.assert_called_once_with(socket.IPPROTO_TCP, worker.LINUX_TCP_INFO, 256)
        endpoint.getsockopt.return_value = b"short"
        with self.assertRaises(RuntimeError):
            worker.tcp_total_retrans(endpoint)

    def test_tc_drops_and_backlog_are_actual_parsed_evidence(self):
        raw = "qdisc netem 1: root limit 1024\n Sent 100 bytes 20 pkt (dropped 7, overlimits 0 requeues 0)\n backlog 4Kb 3p requeues 0\n"
        result = worker.tc_statistics(raw)
        self.assertEqual(7, result["droppedPackets"])
        self.assertEqual([{"bytesText": "4Kb", "packets": 3}], result["backlog"])
        self.assertEqual(raw, result["raw"])
        with self.assertRaises(RuntimeError):
            worker.tc_statistics("unsupported stats format")

    def test_loss_cannot_pass_on_sha_success_without_real_drop_and_retransmission(self):
        for retrans, drops in ((0, 1), (1, 0), (0, 0)):
            with self.assertRaises(RuntimeError):
                worker.evaluate(profile(), {"totalRetransDelta": retrans}, {"droppedPackets": drops})
        worker.evaluate(profile(), {"totalRetransDelta": 2}, {"droppedPackets": 3})

    def test_worker_refuses_host_execution_before_touching_tc_or_sockets(self):
        with mock.patch.object(worker.sys, "platform", "darwin"), mock.patch.object(worker.subprocess, "run") as run, \
                mock.patch.object(worker.socket, "socket") as connect:
            result = worker.run({"profiles": [profile()]})
        self.assertFalse(result["passed"]); self.assertFalse(result["preflightPassed"])
        run.assert_not_called(); connect.assert_not_called()

    def test_unsupported_seed_is_preflight_failure_not_silently_dropped(self):
        def command(cmd, _log):
            if cmd == ["ip", "-j", "link", "show"]:
                return '[{"ifname":"lo"}]'
            if cmd[:2] == ["ip", "-j"]:
                return '[]'
            if "seed" in cmd:
                raise RuntimeError("tc seed unsupported")
            return "tc mock"
        with mock.patch.object(worker.sys, "platform", "linux"), mock.patch.dict(worker.os.environ, {"RWXX_NETEM_WORKER": "1"}), \
                mock.patch.object(worker.Path, "is_file", return_value=True), mock.patch.object(worker.shutil, "which", return_value="present"), \
                mock.patch.object(worker, "checked", side_effect=command), mock.patch.object(worker, "transfer") as transfer:
            result = worker.run({"profiles": [profile(seed=123)]})
        self.assertFalse(result["passed"]); self.assertFalse(result["preflightPassed"])
        self.assertIn("seed unsupported", result["error"]); transfer.assert_not_called()

    def test_worker_rejects_any_external_interface_before_netem(self):
        with mock.patch.object(worker.sys, "platform", "linux"), mock.patch.dict(worker.os.environ, {"RWXX_NETEM_WORKER": "1"}), \
                mock.patch.object(worker.Path, "is_file", return_value=True), mock.patch.object(worker.shutil, "which", return_value="present"), \
                mock.patch.object(worker, "checked", return_value='[{"ifname":"lo"},{"ifname":"eth0"}]') as command:
            result = worker.run({"profiles": [profile()]})
        self.assertFalse(result["passed"])
        self.assertEqual([["ip", "-j", "link", "show"]], [call.args[0] for call in command.call_args_list])

    def test_success_passes_worker_only_by_stdin_and_removes_exact_created_id(self):
        process = FakeProcess()
        with tempfile.TemporaryDirectory() as parent, mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock()) as run, \
                mock.patch.object(launcher.subprocess, "Popen", return_value=process) as popen:
            result = launcher.run("rwxx-netem:local", Path(parent) / "new", {"profiles": [profile()]})
            self.assertTrue(result["passed"]); self.assertTrue(result["cleanup"]["removed"])
            self.assertEqual(["docker", "rm", "--force", CID], run.call_args_list[-1].args[0])
            self.assertEqual(["docker", "start", "--attach", "--interactive", CID], popen.call_args.args[0])
            self.assertEqual(Path(worker.__file__).read_bytes(), process.inputs[0])
            self.assertEqual(result, json.loads((Path(parent) / "new/result.json").read_text()))

    def test_timeout_and_termination_clean_only_our_container_and_cli(self):
        for error in (subprocess.TimeoutExpired("docker start", 1), InterruptedError("SIGTERM"), KeyboardInterrupt()):
            process = FakeProcess(error)
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as parent, \
                    mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock()) as run, \
                    mock.patch.object(launcher.subprocess, "Popen", return_value=process):
                result = launcher.run("rwxx-netem:local", Path(parent) / "new", {"profiles": [profile()]})
                self.assertFalse(result["passed"]); self.assertTrue(process.killed)
                self.assertEqual(["docker", "rm", "--force", CID], run.call_args_list[-1].args[0])

    def test_interrupted_create_resolves_only_our_random_name_and_owner_label(self):
        first = []
        def run(cmd, **kwargs):
            first.append(cmd)
            if cmd[1] == "create":
                raise subprocess.TimeoutExpired(cmd, 1)
            if cmd[1:3] == ["container", "inspect"]:
                create = first[0]; owner = create[create.index("--label") + 1].split("=", 1)[1]
                return completed(json.dumps([{"Id": CID, "Name": "/" + cmd[-1], "Config": {"Labels": {launcher.OWNER_LABEL: owner}}}]))
            return completed(CID)
        with tempfile.TemporaryDirectory() as parent, mock.patch.object(launcher.subprocess, "run", side_effect=run):
            result = launcher.run("rwxx-netem:local", Path(parent) / "new", {"profiles": [profile()]})
        self.assertFalse(result["passed"]); self.assertTrue(result["cleanup"]["removed"])
        self.assertEqual(["docker", "rm", "--force", CID], first[-1])

    def test_name_collision_or_foreign_owner_never_removes_that_container(self):
        with tempfile.TemporaryDirectory() as parent, mock.patch.object(launcher.subprocess, "run", side_effect=[
                completed(returncode=1, stderr="name conflict"), completed(json.dumps([{"Id": CID, "Name": "/foreign", "Config": {"Labels": {}}}]))]) as run:
            result = launcher.run("rwxx-netem:local", Path(parent) / "new", {"profiles": [profile()]})
        self.assertFalse(result["passed"])
        self.assertFalse(any(call.args[0][1] == "rm" for call in run.call_args_list))

    def test_evidence_directory_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as parent, mock.patch.object(launcher.subprocess, "run") as run:
            with self.assertRaises(FileExistsError):
                launcher.run("rwxx-netem:local", parent, {"profiles": [profile()]})
        run.assert_not_called()

    def test_exact_default_down_tunnels_are_allowed_without_any_interface_mutation(self):
        links = [{"ifname": "lo", "link_type": "loopback"}]
        links += [{"ifname": name, "link_type": kind, "flags": ["NOARP"], "operstate": "DOWN", "link": None}
                  for name, kind in worker.DEFAULT_DOWN_TUNNELS.items()]
        worker.validate_network(links, [[], [{"dev": "lo", "dst": "127.0.0.0/8"}]])

    def test_same_tunnel_name_up_linked_or_wrong_type_is_rejected(self):
        normal = {"ifname": "gre0", "link_type": "gre", "flags": ["NOARP"], "operstate": "DOWN", "link": None}
        for changes in ({"flags": ["UP"]}, {"operstate": "UP"}, {"master": "br0"}, {"link": "eth0"},
                        {"link_netnsid": 0}, {"link_index": 7}, {"link_type": "ether"}):
            value = dict(normal); value.update(changes)
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                worker.validate_network([{"ifname": "lo"}, value], [[]])
        for name in ("eth0", "veth0", "br0", "docker0", "gre1"):
            value = dict(normal, ifname=name)
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                worker.validate_network([{"ifname": "lo"}, value], [[]])

    def test_all_route_tables_must_be_lo_only_including_ipv6_and_nexthops(self):
        for route in ({"dev": "eth0"}, {"dev": "gre0"}, {"dst": "default"},
                      {"dev": "lo", "nexthops": [{"dev": "eth0"}]}):
            with self.subTest(route=route), self.assertRaises(RuntimeError):
                worker.validate_network([{"ifname": "lo"}], [[], [route]])
        worker.validate_network([{"ifname": "lo"}], [[], [{"dev": "lo", "dst": "::1"}]])

    def test_actual_docker_configuration_is_checked_before_start_and_failures_still_clean_own_id(self):
        for changes in ({"NetworkMode": "host"}, {"ReadonlyRootfs": False}, {"Privileged": True},
                        {"CapAdd": ["NET_ADMIN", "SYS_ADMIN"]}, {"CapDrop": []}, {"Memory": 0},
                        {"NanoCpus": 0}, {"Binds": ["/var/run/docker.sock:/socket"]}):
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as parent, \
                    mock.patch.object(launcher.subprocess, "run", side_effect=docker_mock(changes)) as run, \
                    mock.patch.object(launcher.subprocess, "Popen") as popen:
                result = launcher.run("rwxx-netem:local", Path(parent) / "new", {"profiles": [profile()]})
                self.assertFalse(result["passed"]); self.assertTrue(result["cleanup"]["removed"])
                popen.assert_not_called()
                self.assertEqual(["docker", "container", "inspect", CID], run.call_args_list[1].args[0])
                self.assertEqual(["docker", "rm", "--force", CID], run.call_args_list[-1].args[0])


if __name__ == "__main__":
    unittest.main()
