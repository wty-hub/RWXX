#!/usr/bin/env python3
"""Real v7 game traffic under container-local Linux netem; no host networking or GUI.

The unchanged channel worker is imported only for its reviewed network guards and tc helpers.
Its synthetic transfer oracle is never called. Ready/START/end/CLEAN gates isolate established
game-data retransmissions from all connection handshakes and subsequent reconnect tests.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import re
import selectors
import shutil
import signal
import subprocess
import sys
import threading
import time

import authoritative_netem_worker as guards

SCOPE = "Four real v7 clients and an independent production headless server, isolated Linux loopback netem after handshake; not WAN or capacity acceptance"
JAR = Path("/opt/rwxx-test/game.jar")
WORK = Path("/tmp/rwxx-v7-work")
EVIDENCE = Path("/tmp/rwxx-v7-evidence")
PROFILE_FIELDS = {"name", "delay_ms", "jitter_ms", "loss_percent", "duplicate_percent", "reorder_percent", "rate_mbit", "seed"}
STAT_FIELDS = {"observedThrough", "tick", "receivedBytes", "writtenBytes", "stateChecks", "stateAcks", "stateMismatches", "restores", "transactions", "mapCacheHits"}
EVIDENCE_LEASE_SECONDS = 30


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {"profile", "jar_sha256", "timeout_seconds"}:
        raise ValueError("Expected only profile, jar_sha256 and timeout_seconds")
    if not isinstance(config["jar_sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", config["jar_sha256"]):
        raise ValueError("A frozen test-only jar SHA-256 is required")
    if type(config["timeout_seconds"]) is not int or not 100 <= config["timeout_seconds"] <= 180:
        raise ValueError("Worker timeout must be 100..180 seconds")
    profile = config["profile"]
    if not isinstance(profile, dict) or set(profile) != PROFILE_FIELDS:
        raise ValueError("Impairment profile has unknown or missing fields")
    # Reuse exact reviewed numeric/seed restrictions, without running the channel oracle.
    guards.validate_config({"profiles": [dict(profile, payload_bytes=65536, timeout_seconds=45)]})
    return profile


def tcp_snmp(text):
    lines = [line.split() for line in text.splitlines() if line.startswith("Tcp:")]
    require(len(lines) == 2 and len(lines[0]) == len(lines[1]), "Malformed /proc/net/snmp TCP counters")
    try:
        result = dict(zip(lines[0][1:], map(int, lines[1][1:])))
    except ValueError as error:
        raise RuntimeError("Noninteger TCP counter") from error
    required = {"RetransSegs", "ActiveOpens", "PassiveOpens", "CurrEstab", "EstabResets", "InSegs", "OutSegs"}
    require(required <= result.keys() and all(result[key] >= 0 for key in required), "Missing TCP lifecycle/data counters")
    return result


def validate_peer_stats(value):
    require(isinstance(value, dict) and STAT_FIELDS <= value.keys(), "Incomplete v7 peer statistics")
    require(all(type(value[key]) is int and value[key] >= 0 for key in STAT_FIELDS), "Invalid v7 peer counter")
    require(value["tick"] == value["observedThrough"] // 1024, "Peer tick disagrees with timeline observation")


def advanced(before, after, periodic=False):
    validate_peer_stats(before); validate_peer_stats(after)
    require(all(after[key] >= before[key] for key in STAT_FIELDS), "Peer statistics regressed")
    for key in ("observedThrough", "receivedBytes", "writtenBytes", "stateChecks", "stateAcks"):
        require(after[key] > before[key], "Established game peer did not advance " + key)
    require(before["stateMismatches"] == after["stateMismatches"] == 0 and before["restores"] == after["restores"] == 0,
            "State mismatch or restore occurred during the game traffic test")
    if periodic:
        require(after["stateChecks"] - before["stateChecks"] >= 5 and after["stateAcks"] - before["stateAcks"] >= 5,
                "Ten-second game phase lacks repeated complete-state check/ACK evidence")


def evaluate_game(data):
    require(isinstance(data, dict) and type(data.get("durationSeconds")) in (float, int) and
            math.isfinite(data["durationSeconds"]) and data["durationSeconds"] >= 10, "Established data phase lasted less than ten seconds")
    require(data.get("connections") == 4 and type(data.get("connectionsChanged")) is int and data["connectionsChanged"] == 0 and
            type(data.get("transportFailures")) is int and data["transportFailures"] == 0,
            "Connections changed or failed during the measured data phase")
    require(isinstance(data.get("mapContentKeyHex"), str) and re.fullmatch(r"[0-9a-f]{64}", data["mapContentKeyHex"]),
            "Missing canonical static-map SHA-256")
    clients = data.get("clients")
    require(isinstance(clients, list) and len(clients) == 4 and {p.get("teamId") for p in clients} == {0, 1, 2, 63}, "Expected four distinct game peers")
    for peer in clients:
        advanced(peer["before"], peer["after"], periodic=True)
        require(type(peer.get("commandsSent")) is int and peer["commandsSent"] >= 9 and
                type(peer.get("observedPositionChanges")) is int and peer["observedPositionChanges"] > 0 and
                peer.get("movementObserved") is True and type(peer.get("maxDisplacementWorld")) in (int, float) and
                math.isfinite(peer["maxDisplacementWorld"]) and peer["maxDisplacementWorld"] > 0,
                "Commands lack actual authorized movement evidence")
    slow = data.get("slowReader")
    require(isinstance(slow, dict) and slow.get("kind") == "application-reader-hold" and slow.get("teamId") == 63 and
            slow.get("completed") is True and slow.get("othersAdvanced") is True and slow.get("recovered") is True and
            slow.get("tcpZeroWindowClaimed") is False and type(slow.get("actualHoldSeconds")) in (int, float) and
            math.isfinite(slow["actualHoldSeconds"]) and 3.9 <= slow["actualHoldSeconds"] < 9,
            "Missing bounded slow application-reader isolation/recovery evidence")
    others = slow.get("otherClients")
    require(isinstance(others, list) and len(others) == 3 and {p.get("teamId") for p in others} == {0, 1, 2}, "Missing unaffected game peers")
    for peer in others:
        advanced(peer["before"], peer["after"])
    advanced(slow["slowAtRelease"], slow["slowAfter"])
    return {"completeStateChecksRepeated": True, "stateMismatches": 0, "restores": 0,
            "actualMovement": True, "slowReaderIsolation": True, "tcpZeroWindowClaimed": False,
            "mapContentKeyHex": data["mapContentKeyHex"]}


def evaluate_tcp(profile, before, after, tc):
    require(before["CurrEstab"] == after["CurrEstab"] == 8, "Expected exactly four existing loopback TCP connections")
    for key in ("ActiveOpens", "PassiveOpens", "EstabResets"):
        require(after[key] == before[key], "Data-phase TCP lifecycle changed: " + key)
    require(all(after[key] >= before[key] for key in ("RetransSegs", "InSegs", "OutSegs")), "TCP counters regressed")
    delta = after["RetransSegs"] - before["RetransSegs"]
    require(after["InSegs"] > before["InSegs"] and after["OutSegs"] > before["OutSegs"], "No established TCP data was measured")
    if profile["loss_percent"] > 0:
        require(delta > 0 and tc["droppedPackets"] > 0, "Configured loss lacks established-data retransmission and qdisc drop evidence")
    return {"dataRetransmissionSegments": delta, "activeOpensDelta": 0, "passiveOpensDelta": 0,
            "establishedResetsDelta": 0, "establishedSocketsBefore": 8, "establishedSocketsAfter": 8}


def snapshot(report):
    raw = Path("/proc/net/snmp").read_text()
    return {"tcp": tcp_snmp(raw), "snmpRaw": raw, "ssRaw": guards.checked(["ss", "-nti"], report["commands"])}


def remove_owned_qdisc(report):
    raw = guards.checked(["tc", "-j", "qdisc", "show", "dev", "lo"], report["commands"])
    entries = json.loads(raw)
    ours = [item for item in entries if item.get("kind") == "netem" and item.get("handle") == "1:" and item.get("root") is True]
    require(len(ours) <= 1, "Ambiguous owned qdisc")
    if ours:
        guards.checked(["tc", "qdisc", "del", "dev", "lo", "root"], report["commands"])
    else:
        require(all(item.get("kind") == "noqueue" and item.get("handle") == "0:" for item in entries), "Refusing to remove an unexpected qdisc")
    report["qdiscRemoved"] = True


class PhaseController:
    def __init__(self, profile, report, send):
        self.profile, self.report, self.send = profile, report, send
        self.phase = "initial"

    def accept(self, message):
        require(message.get("kind") == "rwxx-netem-phase" and message.get("connections") == 4, "Invalid game phase envelope")
        if message.get("phase") == "ready":
            require(self.phase == "initial", "Repeated/out-of-order ready phase")
            self.report["before"] = snapshot(self.report)
            require(self.report["before"]["tcp"]["CurrEstab"] == 8, "Netem cannot begin before all four TCP handshakes finish")
            current = json.loads(guards.checked(["tc", "-j", "qdisc", "show", "dev", "lo"], self.report["commands"]))
            require(isinstance(current, list) and all(item.get("kind") == "noqueue" and item.get("handle") == "0:" for item in current),
                    "Refusing to replace an unexpected qdisc")
            self.report["netemConfigurationAttempted"] = True
            guards.checked(guards.netem_command(self.profile), self.report["commands"])
            self.report["qdiscInstalledAfterReady"] = True
            self.send("START\n"); self.phase = "data"
        elif message.get("phase") == "end":
            require(self.phase == "data", "Game data ended without ready/START")
            self.report["after"] = snapshot(self.report)
            self.report["tcStatistics"] = guards.tc_statistics(guards.checked(["tc", "-s", "qdisc", "show", "dev", "lo"], self.report["commands"]))
            self.report["dataStats"] = message.get("dataStats")
            remove_owned_qdisc(self.report)
            self.send("CLEAN\n"); self.phase = "complete"
        else:
            raise RuntimeError("Unknown game traffic phase")


def harness(profile, report, timeout_seconds):
    environment = dict(os.environ)
    for key in ("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY"):
        environment.pop(key, None)
    environment.update({"RWX_RUN_TIMELINE_PROCESS_ACCEPTANCE": "1", "RWX_RUN_TIMELINE_NETEM_PHASE": "1",
                        "JAVA_TOOL_OPTIONS": "-Xms64m -Xmx640m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2 -Djava.awt.headless=true -Duser.home=/tmp/rwxx-v7-user-home"})
    command = ["java", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED",
               "--sun-misc-unsafe-memory-access=allow", "-cp", str(JAR), "io.github.rwx.TimelineProcessAcceptanceMain",
               "--output=" + str(WORK / "application.json")]
    report["javaCommand"] = command
    child = subprocess.Popen(command, cwd=WORK, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue(maxsize=256); reader_error = []; draining = threading.Event()
    def read_lines():
        try:
            total = 0
            with (EVIDENCE / "java.stdout.log").open("w") as log:
                for line in child.stdout:
                    require(len(line) <= 65536, "Java output line exceeded evidence budget")
                    total += len(line.encode("utf-8")); require(total <= 16 * 1024 * 1024, "Java log exceeded evidence budget")
                    log.write(line); log.flush()
                    if not draining.is_set(): lines.put_nowait(line)
        except Exception as error:
            reader_error.append(error)
        finally:
            try: lines.put_nowait(None)
            except queue.Full: pass
    reader = threading.Thread(target=read_lines, daemon=True); reader.start()
    def send(value):
        child.stdin.write(value); child.stdin.flush()
    phases = PhaseController(profile, report, send)
    deadline = time.monotonic() + timeout_seconds
    try:
        ended = False
        while not ended:
            require(time.monotonic() < deadline, "Real v7 harness exceeded the worker deadline")
            require(not reader_error, "Java output reader failed: " + str(reader_error))
            try: line = lines.get(timeout=0.1)
            except queue.Empty:
                if child.poll() is not None and not reader.is_alive(): break
                continue
            if line is None: ended = True; continue
            if line.lstrip().startswith("{"):
                try: message = json.loads(line)
                except json.JSONDecodeError: continue
                if isinstance(message, dict) and message.get("kind") == "rwxx-netem-phase": phases.accept(message)
        require(not reader_error, "Java output reader failed: " + str(reader_error))
        report["javaExitCode"] = child.wait(timeout=max(1, deadline - time.monotonic()))
        require(phases.phase == "complete", "Real v7 harness never completed its gated data phase")
    finally:
        # Continue recording final exception/cleanup lines even after phase validation has failed.
        draining.set()
        if child.poll() is None:
            # Closing only our control pipe lets the harness execute its normal finally/owner EOF.
            try: child.stdin.close()
            except OSError: pass
            try: child.wait(timeout=20)
            except subprocess.TimeoutExpired:
                child.terminate()
                try: child.wait(timeout=3)
                except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=3)
                report["harnessForcedTermination"] = True
        reader.join(timeout=1)


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024): digest.update(block)
    return digest.hexdigest()


def collect_evidence(report):
    manifests = []
    source = WORK / "application.json"
    if source.is_file() and not source.is_symlink():
        require(source.stat().st_size <= 16 * 1024 * 1024, "Application report exceeded evidence budget")
        application = json.loads(source.read_text())
        report["application"] = application
        shutil.copyfile(source, EVIDENCE / "application.json")
    else:
        application = None
    # Export only explicitly selected logs/archives. Never copy seats.properties or profile data.
    files = list(WORK.glob("application-state-*/server.log"))
    files += list(WORK.glob("application-state-*/*.rwxr"))
    files += list(WORK.glob("application-state-*/server-archives/*.rwxr"))
    require(len(files) <= 16, "Unexpected evidence file count")
    for number, path in enumerate(files):
        require(not path.is_symlink() and path.resolve().is_relative_to(WORK.resolve()), "Evidence escaped owned work directory")
        require(path.is_file() and path.stat().st_size <= 128 * 1024 * 1024, "Evidence file exceeded bounded size")
        target = EVIDENCE / (f"artifact-{number:02d}-" + path.name)
        shutil.copyfile(path, target)
        manifests.append({"file": target.name, "bytes": target.stat().st_size, "sha256": file_hash(target)})
    report["artifacts"] = manifests
    return application


def run(config):
    report = {"scope": SCOPE, "passed": False, "preflightPassed": False, "qdiscRemoved": False,
              "commands": [], "netemConfigurationAttempted": False, "harnessForcedTermination": False}
    enabled = False
    try:
        profile = validate_config(config); report["configuration"] = config
        require(sys.platform == "linux" and os.environ.get("RWXX_NETEM_V7_WORKER") == "1" and Path("/.dockerenv").is_file(),
                "Explicit isolated Linux Docker worker opt-in is required")
        enabled = True; EVIDENCE.mkdir(exist_ok=False); WORK.mkdir(exist_ok=False)
        report["workerSha256"] = file_hash(Path(__file__))
        report["guardHelperSha256"] = file_hash(Path(guards.__file__))
        require(all(shutil.which(name) for name in ("java", "python3", "ip", "tc", "ss")), "Image lacks JRE25/python3/iproute2")
        links = json.loads(guards.checked(["ip", "-j", "link", "show"], report["commands"]))
        routes = json.loads(guards.checked(["ip", "-j", "route", "show", "table", "all"], report["commands"]))
        routes6 = json.loads(guards.checked(["ip", "-j", "-6", "route", "show", "table", "all"], report["commands"]))
        guards.validate_network(links, [routes, routes6])
        report["networkIsolation"] = {"interfaces": links, "ipv4Routes": routes, "ipv6Routes": routes6}
        report["kernel"] = os.uname().release; report["tcVersion"] = guards.checked(["tc", "-V"], report["commands"]).strip()
        guards.checked(["ip", "link", "set", "dev", "lo", "up"], report["commands"])
        require(JAR.is_file() and not JAR.is_symlink(), "Frozen test jar is missing")
        report["jarSha256"] = file_hash(JAR)
        require(report["jarSha256"] == config["jar_sha256"], "Frozen test jar SHA-256 differs")
        version = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=10, check=False)
        report["javaVersion"] = version.stdout + version.stderr
        require(version.returncode == 0 and re.search(r'(?:openjdk|java) (?:version )?"?25[.\s"]', report["javaVersion"]), "Image must provide Java 25")
        report["preflightPassed"] = True
        harness(profile, report, config["timeout_seconds"])
        application = collect_evidence(report)
        require(report["javaExitCode"] == 0 and not report["harnessForcedTermination"] and isinstance(application, dict) and
                application.get("passed") is True and application.get("protocolVersion") == 7 and application.get("netemPhaseEnabled") is True,
                "Production v7 process acceptance failed")
        checks = application.get("checks")
        require(isinstance(checks, dict) and checks and all(value is True for value in checks.values()), "One or more actual game checks failed")
        require(application.get("netemPhase") == report["dataStats"], "Final game statistics differ from the gated data phase")
        report["gameValidation"] = evaluate_game(report["dataStats"])
        report["tcpValidation"] = evaluate_tcp(profile, report["before"]["tcp"], report["after"]["tcp"], report["tcStatistics"])
        require(report["qdiscRemoved"], "Owned qdisc was not removed")
        report["passed"] = True
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        if enabled:
            if report["netemConfigurationAttempted"] and not report["qdiscRemoved"]:
                try: remove_owned_qdisc(report)
                except Exception as error: report["cleanupError"] = str(error); report["passed"] = False
            if not report["netemConfigurationAttempted"]: report["qdiscRemoved"] = True
            if EVIDENCE.is_dir():
                if "application" not in report:
                    try: collect_evidence(report)
                    except Exception as error: report["evidenceError"] = str(error)
                (EVIDENCE / "result.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return report


def wait_for_release(stream, timeout_seconds=EVIDENCE_LEASE_SECONDS):
    """Keep the owned tmpfs alive only until RELEASE, EOF or an absolute bounded deadline."""
    deadline = time.monotonic() + timeout_seconds
    pending = bytearray()
    with selectors.DefaultSelector() as selector:
        selector.register(stream, selectors.EVENT_READ)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not selector.select(remaining): return "timeout"
            part = os.read(stream.fileno(), 9 - len(pending))
            if not part:
                require(not pending, "Incomplete evidence release command")
                return "eof"
            pending.extend(part)
            require(len(pending) <= 8 and b"RELEASE\n".startswith(pending), "Expected only RELEASE followed by newline")
            if pending == b"RELEASE\n": return "released"


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--config-json", required=True)
    options = parser.parse_args()
    def terminated(signum, _frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        raise InterruptedError("Owned worker received signal " + str(signum))
    previous = signal.signal(signal.SIGTERM, terminated)
    try:
        report = run(json.loads(options.config_json))
        report["evidenceLease"] = {"protocol": "RELEASE-or-EOF", "timeoutSeconds": EVIDENCE_LEASE_SECONDS}
        # The result is complete. No game, qdisc or data measurement stays active during this lease.
        print(json.dumps(report, sort_keys=True), flush=True)
        if wait_for_release(sys.stdin.buffer) == "timeout":
            print("Evidence lease expired without RELEASE or EOF", file=sys.stderr, flush=True)
            return 124
        return 0 if report["passed"] else 1
    finally: signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    raise SystemExit(main())
