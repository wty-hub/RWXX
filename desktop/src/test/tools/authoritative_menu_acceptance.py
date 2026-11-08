#!/usr/bin/env python3
"""Real desktop room-launcher host/join acceptance on one owned inactive virtual display.

Exercises automatic TLS/seats/invitations and separate player JVMs, not a full security audit.
Only the supplied, hash-checked probe launches the games; no original lobby/network path is reused.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import zipfile

import authoritative_timeline_acceptance as evidence


PROBE_CLASS = "io.github.rwx.AuthoritativeMenuLaunchProbeMain"


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    for option in ("jar", "test-classes", "manager", "agent", "output-dir"):
        parser.add_argument("--" + option, type=Path, required=True)
    return parser.parse_args(argv)


def class_directory(path):
    evidence.require(path.is_dir(), "--test-classes must be a compiled test directory")
    evidence.require(path.joinpath(*PROBE_CLASS.split(".")).with_suffix(".class").is_file(),
                     "The menu-launch probe class has not been compiled")
    entries = []
    combined = hashlib.sha256()
    for file in sorted(path.rglob("*.class")):
        evidence.require(not file.is_symlink(), "Test class symlinks are unsupported")
        relative = str(file.relative_to(path))
        digest = evidence.sha256(file)
        combined.update(relative.encode() + b"\0" + digest.encode() + b"\n")
        entries.append({"path": relative, "sha256": digest, "sizeBytes": file.stat().st_size})
    return {"path": str(path), "kind": "classDirectory", "sha256": combined.hexdigest(), "entries": entries}


def read_json(path, final=False):
    if not path.exists():
        evidence.require(not final, f"Missing evidence: {path}")
        return None
    raw = path.read_bytes()
    evidence.require(len(raw) <= 1024 * 1024, f"Unexpectedly large process metadata: {path}")
    try:
        result = json.loads(raw)
    except json.JSONDecodeError:
        evidence.require(not final, f"Incomplete process metadata: {path}")
        return None
    evidence.require(isinstance(result, dict), f"Expected an object in {path}")
    return result


def in_state_root(value, root):
    path = Path(value).resolve()
    evidence.require(path.is_relative_to(root), "Probe evidence path escaped the private state root")
    return path


def process_table(timeout=5):
    """Read identities only. Never signal groups, and never persist unrelated process argv."""
    run = subprocess.run(["ps", "-ww", "-axo", "pid=,ppid=,stat=,lstart=,command="], capture_output=True,
                         text=True, timeout=timeout, env=dict(os.environ, LC_ALL="C", TZ="UTC"))
    evidence.require(run.returncode == 0, "Unable to inspect owned process identities")
    pattern = re.compile(r"^\s*(\d+)\s+(\d+)\s+(\S+)\s+(\w{3})\s+(\w{3})\s+(\d{1,2})\s+"
                         r"(\d\d):(\d\d):(\d\d)\s+(\d{4})(?:[ \t]+(.*))?$")
    months = {name: index for index, name in enumerate(
        ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"), 1)}
    table = {}
    for line in run.stdout.splitlines():
        match = pattern.match(line)
        if not match:
            continue
        pid, ppid, state, _, month, day, hour, minute, second, year, command = match.groups()
        started = datetime(int(year), months[month], int(day), int(hour), int(minute), int(second), tzinfo=timezone.utc)
        table[int(pid)] = {"pid": int(pid), "ppid": int(ppid), "state": state,
                           "startSecond": int(started.timestamp()), "command": command or ""}
    evidence.require(os.getpid() in table, "Process identity output could not be parsed safely")
    return table


class OwnedChildren:
    """Bind cleanup to an observed child identity, private run directory and authority argv."""
    def __init__(self, state_root, jar):
        self.root = state_root
        self.jar = jar
        self.tracked = {}
        self.actions = []
        self.identity_resamples = []

    def is_authority_command(self, row):
        command = row["command"]
        return (str(self.root) in command and str(self.jar) in command
                and any(mode in command for mode in ("--authority-host", "--authority-client", "--authority-server")))

    @staticmethod
    def descendants(parent, table):
        if parent is None or parent.poll() is not None:
            return set()
        descendants = {parent.pid}
        changed = True
        while changed:
            changed = False
            for row in table.values():
                if row["ppid"] in descendants and row["pid"] not in descendants:
                    descendants.add(row["pid"])
                    changed = True
        return descendants - {parent.pid}

    def settle_exiting(self, pid, table):
        """Read through ps's exit placeholders for an already verified identity only.

        macOS ps may lose argv while the same process exits, including <exiting>
        or the fallback (java). These placeholders never establish ownership or
        authorize a signal. A different command/birth or an unresolved transition
        fails closed. Every resample, including ps itself, shares a 0.5s budget.
        """
        placeholders = ("", "(java)", "<exiting>")
        identity = self.tracked.get(pid)
        row = table.get(pid)
        if (not identity or not row or "Z" in row["state"]
                or row["startSecond"] != identity["startSecond"]
                or row["command"].strip() not in placeholders):
            return table
        deadline = time.monotonic() + 0.5
        attempts = 0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(0.01, remaining))
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            table = process_table(timeout=remaining)
            attempts += 1
            row = table.get(pid)
            if (not row or "Z" in row["state"] or row["startSecond"] != identity["startSecond"]
                    or row["command"].strip() not in placeholders):
                break
        command_match = bool(row and self.is_authority_command(row))
        self.identity_resamples.append({"pid": pid, "kind": "bound-exit", "attempts": attempts,
            "expectedStartSecond": identity["startSecond"],
            "observedStartSecond": row.get("startSecond") if row else None, "commandMatch": command_match})
        evidence.require(not row or "Z" in row["state"]
                         or (row["startSecond"] == identity["startSecond"] and command_match),
                         f"Unresolved tracked process exit identity: pid={pid}, commandMatch={command_match}, "
                         f"resamples={attempts}")
        return table

    def observe(self, parent, metadata):
        table = process_table()
        for pid in self.tracked:
            table = self.settle_exiting(pid, table)
        # While our Popen is live, its actual descendant tree independently identifies children,
        # including the interval between ProcessBuilder.start and publishing running.json.
        if parent is not None and parent.poll() is None:
            for pid in self.descendants(parent, table):
                row = table.get(pid)
                if row and self.is_authority_command(row):
                    self.tracked.setdefault(pid, {"startSecond": row["startSecond"], "source": "live-descendant"})
        for document in metadata:
            if not document:
                continue
            entries = list(document.get("clients", []))
            server = document.get("server")
            if isinstance(server, dict):
                entries.append(server)
            elif document.get("serverPid"):
                entries.append({"pid": document["serverPid"], "startInstant": document.get("serverStartInstant")})
            for entry in entries:
                pid = entry.get("pid")
                if type(pid) is not int or pid <= 1:
                    continue
                row = table.get(pid)
                if not row or "Z" in row["state"]:
                    continue
                start = entry.get("startInstant")
                if start is None:
                    # A live descendant is already independently bound; metadata alone is not enough.
                    evidence.require(pid in self.tracked, "Live child metadata has no startInstant or observed ancestry")
                    continue
                expected = int(datetime.fromisoformat(start.replace("Z", "+00:00")).timestamp())
                # ps reads process identity and argv separately. A newly started, independently
                # observed descendant can briefly expose its pre-exec/empty command. Resample
                # only this unbound startup state; never accept it or signal it before the same
                # exact identity also passes the full private authority-command check.
                attempts = 0
                if (pid not in self.tracked and row["startSecond"] == expected
                        and not self.is_authority_command(row) and pid in self.descendants(parent, table)):
                    deadline = time.monotonic() + 0.5
                    while time.monotonic() < deadline:
                        time.sleep(0.01)
                        table = process_table()
                        attempts += 1
                        row = table.get(pid)
                        if (not row or "Z" in row["state"] or row["startSecond"] != expected
                                or pid not in self.descendants(parent, table) or self.is_authority_command(row)):
                            break
                    self.identity_resamples.append({"pid": pid, "expectedStartSecond": expected,
                        "observedStartSecond": row.get("startSecond") if row else None,
                        "commandMatch": bool(row and self.is_authority_command(row)), "attempts": attempts})
                    if not row or "Z" in row["state"]:
                        continue
                command_match = self.is_authority_command(row)
                evidence.require(row["startSecond"] == expected and self.is_authority_command(row),
                    "Owned process metadata does not match current PID identity/command: "
                    f"pid={pid}, expectedStartSecond={expected}, observedStartSecond={row['startSecond']}, "
                    f"commandMatch={command_match}, resamples={attempts}")
                self.tracked.setdefault(pid, {"startSecond": expected, "source": "probe-startInstant"})
        return table

    def live(self, table=None):
        table = process_table() if table is None else table
        unknown = [row["pid"] for row in table.values() if "Z" not in row["state"]
                   and self.is_authority_command(row) and row["pid"] not in self.tracked]
        evidence.require(not unknown, "Unverified authority process still references this run's state root")
        for pid in self.tracked:
            table = self.settle_exiting(pid, table)
        # All decisions use the same final snapshot, including identities visited
        # before another child's exit caused ps to be sampled again.
        unknown = [row["pid"] for row in table.values() if "Z" not in row["state"]
                   and self.is_authority_command(row) and row["pid"] not in self.tracked]
        evidence.require(not unknown, "Unverified authority process appeared during exit resampling")
        live = []
        for pid, identity in self.tracked.items():
            row = table.get(pid)
            if not row or "Z" in row["state"]:
                continue
            # A different start time means our former child exited and the PID was reused.
            if row["startSecond"] != identity["startSecond"]:
                evidence.require(not self.is_authority_command(row),
                                 "A replacement authority process has no verified start identity")
                continue
            evidence.require(self.is_authority_command(row), "Tracked live child changed its authority command")
            live.append(pid)
        return live

    def signal_live(self, sig):
        for pid in self.live():
            # Re-read immediately before signalling; stale PGIDs/PIDs are never blindly killed.
            row = self.settle_exiting(pid, process_table()).get(pid)
            identity = self.tracked[pid]
            if not row or "Z" in row["state"] or row["startSecond"] != identity["startSecond"]:
                continue
            evidence.require(self.is_authority_command(row), "Refusing to signal an unrelated process")
            try:
                os.kill(pid, sig)
                self.actions.append({"pid": pid, "signal": int(sig), **identity})
            except ProcessLookupError:
                pass


def validate_muted_from_start(state):
    import tomllib
    preferences = tomllib.loads((state / "startup-preferences.toml").read_text()).get("preferences", {})
    evidence.require(preferences.get("enableSounds") == "false", "Client sound was enabled at startup")
    evidence.require(all(float(preferences.get(key, "nan")) == 0.0 for key in
                         ("masterVolume", "musicVolume", "gameVolume", "interfaceVolume")),
                     "Client volume was not muted at startup")
    return evidence.validate_muted(state)


def validate_preflight(records):
    evidence.require(len(records) == 1 and records[0].get("kind") == "before",
                     "Read-only display preflight did not return one complete snapshot")
    snapshot = records[0]
    evidence.require(snapshot.get("virtualDisplayApiAvailable") in (True, 1), "Virtual display API is unavailable")
    displays = snapshot.get("displays")
    evidence.require(isinstance(displays, list) and displays and all(isinstance(row, dict) for row in displays),
                     "Display preflight contains no usable display inventory")
    evidence.validate_no_pending_owned_displays(displays)
    mains = [row for row in displays if row.get("main") in (True, 1)]
    evidence.require(len(mains) == 1, "Display preflight must identify one physical main display")
    main = mains[0]
    evidence.require(main.get("active") is True and main.get("modeAvailable") is True,
                     "Main display is asleep/inactive or its mode is unavailable; no virtual display was created")
    for row in displays:
        if row.get("active") is True or row in mains:
            evidence.require(row.get("modeAvailable") is True,
                             "An existing active display has no readable mode; no virtual display was created")
    return {"passed": True, "mainDisplayId": main.get("id"), "displays": displays, "pendingOwnedDisplaysAbsent": True,
            "scope": "Read-only manager --list; requires an active main display before any create command"}


def main(argv=None):
    args = parse_args(argv)
    output = args.output_dir.resolve()
    evidence.require(not output.exists(), "Use a new output directory; existing evidence is never overwritten")
    output.mkdir(parents=True)
    state_root = output / "rooms"
    state_root.mkdir(mode=0o700)
    root = Path(__file__).resolve().parents[4]
    result = {"passed": False, "scope": __doc__.strip(), "controllerPid": os.getpid(),
              "failures": [], "resourceCleanup": {}, "managerSeconds": 180, "networkSeatCount": 2}
    manager = None
    probe = None
    inspector = None
    children = None
    ready = None
    complete = False
    logs = []
    manager_log = output / "manager.ndjson"
    running_file = output / "running.json"
    probe_output = output / "probe-result.json"

    def failure(stage, error):
        result["failures"].append({"stage": stage, "error": str(error)})

    def interrupt(signum, _frame):
        raise InterruptedError(f"Interrupted by signal {signum}")

    old_handlers = {sig: signal.signal(sig, interrupt) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        evidence.require(sys.platform == "darwin", "This reviewed virtual-display adapter is macOS-only")
        java_name = shutil.which(args.java) if not Path(args.java).is_absolute() else args.java
        evidence.require(java_name is not None, "Java executable was not found")
        java = Path(java_name).resolve()
        jar, test_classes, manager_path, agent = (p.resolve() for p in
            (args.jar, args.test_classes, args.manager, args.agent))
        paths = {"java": java, "jar": jar, "manager": manager_path, "agent": agent,
                 "runner": Path(__file__).resolve(), "evidenceHelpers": Path(evidence.__file__).resolve()}
        result["artifacts"] = {name: evidence.artifact(path) for name, path in paths.items()}
        result["testClasses"] = class_directory(test_classes)
        evidence.require(os.access(java, os.X_OK) and os.access(manager_path, os.X_OK), "Java/manager must be executable")
        evidence.require(result["artifacts"]["manager"]["sha256"] == evidence.MANAGER_SHA256, "Manager is not the reviewed v6 binary")
        evidence.require(result["artifacts"]["agent"]["sha256"] == evidence.AGENT_SHA256, "Agent is not the reviewed v8 JAR")
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            hashes = {}
            for name, expected in evidence.TARGET_CLASSES.items():
                evidence.require(names.count(name + ".class") == 1, "Missing or duplicated guarded window class")
                hashes[name] = hashlib.sha256(archive.read(name + ".class")).hexdigest()
                evidence.require(hashes[name] == expected, "Guarded class does not match agent v8")
            evidence.require("io/github/rwx/DesktopAuthoritativeRoomLauncher.class" in names, "JAR lacks the desktop room launcher")
        result["exactWindowClassSha256"] = hashes
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith("RWX_") and key not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
        # An asleep physical main display must never reach --create: registration can otherwise
        # leave an unusable virtual device behind even when the manager exits unsuccessfully.
        preflight_path = output / "preflight.ndjson"
        preflight_command = [str(manager_path), "--list"]
        result["preflight"] = {"passed": False, "argv": preflight_command, "log": str(preflight_path)}
        with preflight_path.open("x") as preflight_stdout, (output / "preflight.stderr").open("x") as preflight_stderr:
            preflight_process = subprocess.run(preflight_command, cwd=root, env=environment,
                stdout=preflight_stdout, stderr=preflight_stderr, timeout=10)
        result["preflight"]["exitCode"] = preflight_process.returncode
        evidence.require(preflight_process.returncode == 0, "Read-only display preflight failed; no virtual display was created")
        preflight = validate_preflight(evidence.read_records(preflight_path, final=True))
        result["preflight"].update(preflight)
        result["preflight"]["evidence"] = evidence.artifact(preflight_path)
        children = OwnedChildren(state_root, jar)
        manager_stdout = manager_log.open("x")
        manager_stderr = (output / "manager.stderr").open("x")
        logs.extend((manager_stdout, manager_stderr))
        manager_command = [str(manager_path), "--create", "--seconds=180", "--refresh=60"]
        manager = subprocess.Popen(manager_command, cwd=root, env=environment, stdout=manager_stdout,
                                   stderr=manager_stderr)
        result["manager"] = {"pid": manager.pid, "argv": manager_command}
        # Reserve one minute of the manager's bounded lifetime for verified child cleanup.
        deadline = time.monotonic() + 120
        ready_deadline = time.monotonic() + 30
        while ready is None:
            evidence.require(manager.poll() is None, "Manager stopped before readiness; no GUI launched")
            ready = evidence.validate_ready(evidence.read_records(manager_log), manager.pid)
            evidence.require(time.monotonic() < ready_deadline, "Manager readiness timed out; no GUI launched")
            if ready is None:
                time.sleep(0.1)
        result["display"] = ready
        created_before = next(row for row in evidence.read_records(manager_log) if row.get("kind") == "before")
        evidence.require(created_before.get("displays") == preflight["displays"],
                         "Display state changed between read-only preflight and manager creation")
        bounds = [ready["bounds"][key] for key in ("x", "y", "width", "height")]

        def guard():
            evidence.require(manager.poll() is None, "Owned virtual display stopped; aborting owned games")
            evidence.require(time.monotonic() < deadline, "Acceptance deadline expired")
            evidence.validate_ready(evidence.read_records(manager_log), manager.pid)
            running = read_json(running_file)
            final = read_json(probe_output)
            children.observe(probe, (running, final))
            windows = set(state_root.glob("room-*/background-window.ndjson"))
            for document in (running, final):
                for client in (document or {}).get("clients", []):
                    if client.get("windowProbe"):
                        windows.add(in_state_root(client["windowProbe"], state_root))
            for path in windows:
                evidence.validate_agent(evidence.read_records(path), bounds)

        probe_log = (output / "probe.log").open("x")
        logs.append(probe_log)
        probe_state = output / "probe-state"
        probe_state.mkdir()
        probe_command = [str(java), "-Djava.awt.headless=true", f"-Dlaunch.dir={probe_state}",
            "-cp", os.pathsep.join((str(jar), str(test_classes))), PROBE_CLASS,
            f"--jar={jar}", f"--agent={agent}", "--bounds=" + ",".join(map(str, bounds)),
            f"--state-root={state_root}", f"--output={probe_output}", f"--running={running_file}"]
        # The helper's GUI children inherit this explicit opt-in; ordinary inherited RWX_* was removed.
        probe = subprocess.Popen(probe_command, cwd=root, env=evidence.action_probe_environment(environment),
                                 stdout=probe_log, stderr=subprocess.STDOUT)
        result["probe"] = {"pid": probe.pid, "argv": probe_command, "log": str(output / "probe.log")}
        probe_deadline = time.monotonic() + 105
        while probe.poll() is None:
            guard()
            evidence.require(time.monotonic() < probe_deadline, "Menu launcher probe timed out")
            time.sleep(0.1)
        guard()
        evidence.require(probe.returncode == 0, "Menu launcher probe exited unsuccessfully")
        report = read_json(probe_output, final=True)
        for key, expected in (("passed", True), ("noGameEngine", True), ("credentialsInArgv", False), ("serverStopped", True)):
            evidence.require(report.get(key) is expected, f"Menu launcher probe failed: {key}")
        clients = report.get("clients", [])
        evidence.require(len(clients) == 2 and {c.get("role") for c in clients} == {"host", "guest"}, "Expected host and guest only")
        pids = [probe.pid, report.get("serverPid"), *(c.get("pid") for c in clients)]
        evidence.require(all(type(pid) is int and pid > 1 for pid in pids) and len(set(pids)) == 4,
                         "Expected separate probe, authority server, host view and guest view JVMs")
        evidence.require(not children.live(), "Probe claims completion while owned authority processes are still live")
        result["probe"]["exitCode"] = probe.returncode
        result["probeReport"] = evidence.artifact(probe_output)
        result["serverPid"] = report["serverPid"]
        result["credentialsInArgv"] = False
        result["serverStopped"] = True
        result["clients"] = []
        for client in sorted(clients, key=lambda row: row["role"]):
            role = client["role"]
            evidence.require(client.get("exitCode") == 0, f"{role} view did not exit normally")
            state = in_state_root(client["stateDir"], state_root)
            paths = {key: in_state_root(client[key], state_root) for key in ("log", "recording", "canvas", "windowProbe")}
            evidence.require(all(path.is_relative_to(state) for path in paths.values()), "Client evidence belongs to a different room")
            result["resourceCleanup"][role] = {"passed": False, "log": str(paths["log"])}
            resources = evidence.validate_resources(paths["log"])
            result["resourceCleanup"][role] = resources
            window = evidence.validate_agent(evidence.read_records(paths["windowProbe"], final=True), bounds, final=True)
            canvas = evidence.validate_canvas(evidence.read_records(paths["canvas"], final=True), expected_read_only=False, expected_views=1)
            evidence.require(paths["recording"].is_file() and paths["recording"].stat().st_size > 0, "Player-view recording is empty")
            inspection_path = output / f"{role}-recording-inspection.json"
            inspect_log = (output / f"{role}-inspect.log").open("x")
            logs.append(inspect_log)
            inspect_command = [str(java), "-Djava.awt.headless=true", f"-Dlaunch.dir={probe_state}", "-cp", str(jar),
                "io.github.rwx.authoritative.TimelineReplayInspectMain", f"--input={paths['recording']}",
                f"--output={inspection_path}"]
            inspector = subprocess.Popen(inspect_command, cwd=root, env=environment, stdout=inspect_log, stderr=subprocess.STDOUT)
            inspect_deadline = time.monotonic() + 20
            while inspector.poll() is None:
                guard()
                evidence.require(time.monotonic() < inspect_deadline, "Recording inspection timed out")
                time.sleep(0.1)
            evidence.require(inspector.returncode == 0, "Recording inspection failed")
            inspection = evidence.validate_recording_inspection(read_json(inspection_path, final=True),
                team_id=0 if role == "host" else 1, canvas_report=canvas)
            result["clients"].append({"role": role, "pid": client["pid"], "exitCode": client["exitCode"],
                "stateDir": str(state), "agent": window, "canvas": canvas, "resourceCleanup": resources,
                "recording": evidence.artifact(paths["recording"]), "inspection": inspection, **validate_muted_from_start(state)})
        complete = True
    except BaseException as error:
        failure("acceptance", error)
    finally:
        for sig in old_handlers:
            signal.signal(sig, signal.SIG_IGN)
        # Ask the probe's own shutdown hook to close its Process objects/server descendants first.
        if probe is not None and probe.poll() is None:
            try:
                probe.terminate()
                probe.wait(timeout=10)
            except subprocess.TimeoutExpired:
                failure("probe shutdown", "Probe did not finish cooperative cleanup within 10 seconds")
            except BaseException as error:
                failure("probe shutdown", error)
        try:
            if children is not None:
                children.observe(probe, (read_json(running_file), read_json(probe_output)))
                children.signal_live(signal.SIGTERM)
                child_deadline = time.monotonic() + 10
                while children.live() and time.monotonic() < child_deadline:
                    time.sleep(0.1)
                if children.live():
                    children.signal_live(signal.SIGKILL)
                    child_deadline = time.monotonic() + 5
                    while children.live() and time.monotonic() < child_deadline:
                        time.sleep(0.1)
                evidence.require(not children.live(), "Owned authority processes survived cleanup; do not release their display")
                result["ownedChildCleanup"] = {"allStopped": True, "identities": children.tracked, "signals": children.actions,
                    "identityResamples": children.identity_resamples,
                    "identityScope": "Live descendant or Java startInstant, ps UTC start second, private state root and JAR command"}
            evidence.stop_process(inspector, timeout=2)
            evidence.stop_process(probe, timeout=2)
        except BaseException as error:
            failure("owned child cleanup", error)
            result["ownedChildCleanup"] = {"allStopped": False}
        games_stopped = result.get("ownedChildCleanup", {}).get("allStopped", children is None)
        if manager is not None:
            try:
                # Never proactively remove the virtual display while an unverified GUI may remain.
                if games_stopped and manager.poll() is None:
                    manager.terminate()
                if games_stopped:
                    manager.wait(timeout=20)
                else:
                    failure("display cleanup", "Manager left to its bounded lifetime because child cleanup could not be verified")
            except BaseException as error:
                failure("display cleanup", error)
            result["manager"]["exitCode"] = manager.poll()
        if probe is not None:
            result["probe"]["exitCode"] = probe.poll()
        for log in logs:
            log.close()
        try:
            records = evidence.read_records(manager_log, final=True)
            cleanup = [r for r in records if r.get("kind") == "cleanup"]
            result["cleanup"] = (cleanup[-1] if cleanup else None) if manager is not None else {
                "required": False, "reason": "Virtual manager was never started"}
            if manager is not None:
                evidence.require(len(cleanup) == 1 and manager.poll() == 0, "No successful manager cleanup")
                evidence.require(not any(r.get("kind") in ("failure", "cleanup-failure") for r in records), "Manager reported failure")
                row = cleanup[0]
                evidence.require(row.get("removed") is True and row.get("originalDisplaysUnchanged") is True
                                 and row.get("exitCode") == 0, "Virtual display or original-screen restoration failed")
                before = next((r.get("displays") for r in records if r.get("kind") == "before"), None)
                evidence.require(before is not None and row.get("displays") == before, "Original displays changed")
                if ready is not None:
                    evidence.require(row.get("displayID") == ready["displayID"], "Cleanup belongs to another display")
            for name, info in result.get("artifacts", {}).items():
                evidence.require(evidence.sha256(Path(info["path"])) == info["sha256"], f"Artifact changed during run: {name}")
            if "testClasses" in result:
                evidence.require(class_directory(Path(result["testClasses"]["path"]))["sha256"] == result["testClasses"]["sha256"],
                                 "Compiled test classes changed during the run")
        except BaseException as error:
            failure("final evidence", error)
        result["passed"] = complete and not result["failures"]
        with (output / "result.json").open("x") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
    print(json.dumps({"passed": result["passed"], "result": str(output / "result.json"), "failures": result["failures"]}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
