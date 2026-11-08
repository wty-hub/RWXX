#!/usr/bin/env python3
"""Isolated real-v7 netem acceptance. Never touches host tc or any existing container.

Stage Dockerfile, game.jar and the two exact named workers into a fresh build context; build with
--build-arg TEST_JAR_SHA256=... . Launch with --image rwxx-netem-v7:local --jar-sha256 ...
--output NEW_DIRECTORY [--scenario clean|delay|loss]. Evidence includes sanitized own-container
logs/archives; generated seats.properties and user profiles are never exported.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import tarfile
import time
import uuid

import authoritative_v7_netem_worker as worker

OWNER_LABEL = "io.github.rwx.netem-v7.owner"
JAR_LABEL = "io.github.rwx.netem-v7.jar-sha256"
MAX_EXPORT_BYTES = 256 * 1024 * 1024
MAX_EXPORT_FILES = 64
MAX_EXPORT_FILE_BYTES = 128 * 1024 * 1024


def create_command(image, name, owner, config):
    worker.validate_config(config)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/@-]*", image):
        raise ValueError("Invalid image reference")
    if name != "rwxx-netem-v7-" + owner or not re.fullmatch(r"[0-9a-f]{32}", owner):
        raise ValueError("Expected a fresh exact owned container identity")
    return ["docker", "create", "--interactive", "--pull=never", "--name", name, "--label", OWNER_LABEL + "=" + owner,
            "--network", "none", "--cap-drop", "ALL", "--cap-add", "NET_ADMIN", "--read-only",
            "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=768m", "--pids-limit", "512", "--memory", "2g",
            "--cpus", "2.0", "--security-opt", "no-new-privileges:true", "--env", "RWXX_NETEM_V7_WORKER=1",
            "--entrypoint", "/usr/bin/python3", image, "-u", "-B", "/opt/rwxx-test/authoritative_v7_netem_worker.py",
            "--config-json", json.dumps(config, separators=(",", ":"))]


def inspect_exact(reference):
    response = subprocess.run(["docker", "container", "inspect", reference], capture_output=True, text=True, timeout=10, check=False)
    if response.returncode: return None
    records = json.loads(response.stdout)
    return records[0] if isinstance(records, list) and len(records) == 1 else None


def owned_id(name, owner):
    record = inspect_exact(name)
    if not isinstance(record, dict) or record.get("Name") != "/" + name or record.get("Config", {}).get("Labels", {}).get(OWNER_LABEL) != owner:
        return None
    cid = record.get("Id", "")
    return cid if re.fullmatch(r"[0-9a-f]{64}", cid) else None


def verify_container(cid, name, owner, jar_sha256):
    record = inspect_exact(cid)
    worker.require(isinstance(record, dict), "Cannot inspect exact new container")
    config, host = record.get("Config", {}), record.get("HostConfig", {})
    normalize = lambda values: {str(value).upper().removeprefix("CAP_") for value in values or []}
    worker.require(record.get("Id") == cid and record.get("Name") == "/" + name and
                   config.get("Labels", {}).get(OWNER_LABEL) == owner, "Container identity/ownership differs")
    worker.require(config.get("Labels", {}).get(JAR_LABEL) == jar_sha256, "Container image does not identify the frozen test jar")
    worker.require(host.get("NetworkMode") == "none" and host.get("ReadonlyRootfs") is True and host.get("Privileged") is False and
                   normalize(host.get("CapDrop")) == {"ALL"} and normalize(host.get("CapAdd")) == {"NET_ADMIN"},
                   "Container network/read-only/capability isolation differs")
    worker.require(not host.get("Binds") and not host.get("Devices") and not host.get("DeviceRequests") and not host.get("VolumesFrom") and
                   not host.get("PortBindings") and host.get("PidMode") in (None, "") and host.get("IpcMode") in (None, "private") and
                   all(mount.get("Type") == "tmpfs" and mount.get("Destination") == "/tmp" for mount in record.get("Mounts", [])),
                   "Container has unexpected host mounts/devices/ports/namespaces")
    worker.require(host.get("Memory") == 2 * 1024 ** 3 and host.get("NanoCpus") == 2_000_000_000 and host.get("PidsLimit") == 512,
                   "Container resource limits differ")
    worker.require(host.get("Tmpfs") == {"/tmp": "rw,noexec,nosuid,nodev,size=768m"}, "Container writable filesystem differs")
    worker.require(any(value.split(":")[0] == "no-new-privileges" for value in host.get("SecurityOpt", [])), "Container permits new privileges")
    worker.require(config.get("Entrypoint") == ["/usr/bin/python3"], "Container worker entrypoint differs")
    worker.require(config.get("OpenStdin") is True and config.get("Tty") is False,
                   "Container must retain a non-terminal evidence control pipe")
    return {"Id": cid, "Name": record["Name"], "imageId": record.get("Image"), "networkMode": "none",
            "readOnly": True, "privileged": False, "capDrop": host["CapDrop"], "capAdd": host["CapAdd"],
            "memoryBytes": host["Memory"], "nanoCpus": host["NanoCpus"], "pidsLimit": host["PidsLimit"],
            "tmpfs": host["Tmpfs"], "jarSha256": jar_sha256}


def read_worker_report(process, timeout_seconds, buffers=None):
    """Read one bounded JSON line while draining stderr; leave stdin open for evidence release."""
    deadline = time.monotonic() + timeout_seconds
    stdout, stderr = buffers if buffers is not None else (bytearray(), bytearray())
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise subprocess.TimeoutExpired("owned worker result", timeout_seconds)
            events = selector.select(remaining)
            if not events: raise subprocess.TimeoutExpired("owned worker result", timeout_seconds)
            for key, _mask in events:
                part = os.read(key.fileobj.fileno(), 65536)
                if not part:
                    selector.unregister(key.fileobj)
                    worker.require(key.data != "stdout", "Worker exited without a complete result line")
                    continue
                target = stdout if key.data == "stdout" else stderr
                target.extend(part)
                worker.require(len(target) <= (32 if key.data == "stdout" else 16) * 1024 * 1024,
                               "Worker output exceeded evidence budget")
                if key.data == "stdout" and b"\n" in stdout:
                    worker.require(stdout.endswith(b"\n") and stdout.count(b"\n") == 1,
                                   "Worker result must be exactly one complete JSON line")
                    return bytes(stdout), bytes(stderr)


def evidence_manifest(report):
    worker.require(isinstance(report, dict) and isinstance(report.get("artifacts"), list), "Worker artifact manifest is missing")
    artifacts = report["artifacts"]
    worker.require(len(artifacts) <= MAX_EXPORT_FILES - 3, "Worker artifact manifest has too many files")
    expected = {}
    for item in artifacts:
        worker.require(isinstance(item, dict) and set(item) == {"file", "bytes", "sha256"}, "Malformed worker artifact manifest")
        name = item["file"]
        worker.require(isinstance(name, str) and len(name) <= 128 and
                       re.fullmatch(r"artifact-[0-9]{2}-(?:server\.log|[A-Za-z0-9_.-]+\.rwxr)", name),
                       "Unsafe or unexpected artifact filename")
        worker.require(name not in expected and type(item["bytes"]) is int and 0 <= item["bytes"] <= MAX_EXPORT_FILE_BYTES and
                       isinstance(item["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]),
                       "Duplicate or invalid artifact size/SHA-256")
        expected[name] = item
    names = set(expected) | {"result.json"}
    if "javaCommand" in report: names.add("java.stdout.log")
    if "application" in report: names.add("application.json")
    return expected, names


def capture_tar_stream(process, destination, timeout_seconds=20, max_bytes=MAX_EXPORT_BYTES):
    """Drain exact exec pipes to a NEW bounded tar file, with one absolute deadline."""
    deadline = time.monotonic() + timeout_seconds
    total = 0; stderr = bytearray()
    with Path(destination).open("xb") as output, selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise subprocess.TimeoutExpired("owned evidence tar", timeout_seconds)
            events = selector.select(remaining)
            if not events: raise subprocess.TimeoutExpired("owned evidence tar", timeout_seconds)
            for key, _mask in events:
                part = os.read(key.fileobj.fileno(), 65536)
                if not part: selector.unregister(key.fileobj); continue
                if key.data == "stdout":
                    total += len(part)
                    worker.require(total <= max_bytes, "Evidence tar exceeded total byte budget")
                    output.write(part)
                else:
                    stderr.extend(part)
                    worker.require(len(stderr) <= 1024 * 1024, "Evidence tar stderr exceeded byte budget")
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise subprocess.TimeoutExpired("owned evidence tar", timeout_seconds)
        code = process.wait(timeout=remaining)
        worker.require(code == 0, "Owned evidence tar failed: " + stderr.decode(errors="replace"))
    return {"bytes": total, "stderr": stderr.decode(errors="replace"), "returncode": code}


def extract_evidence_tar(source, destination, report, deadline=None):
    def within_deadline():
        if deadline is not None and time.monotonic() >= deadline:
            raise subprocess.TimeoutExpired("owned evidence tar validation", 20)
    expected, names = evidence_manifest(report)
    within_deadline()
    worker.require(Path(source).stat().st_size <= MAX_EXPORT_BYTES, "Evidence tar exceeded total byte budget")
    destination = Path(destination); destination.mkdir(exist_ok=False)
    seen = set(); total = 0; exported = []
    with Path(source).open("rb") as raw, tarfile.open(fileobj=raw, mode="r:") as archive:
        for member in archive:
            within_deadline()
            worker.require(len(seen) < MAX_EXPORT_FILES, "Evidence tar has too many members")
            name = member.name
            worker.require(member.type in (tarfile.REGTYPE, tarfile.AREGTYPE) and not member.pax_headers and
                           isinstance(name, str) and len(name) <= 128 and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) and
                           name in names and name not in seen, "Evidence tar member is not a unique approved top-level ordinary file")
            worker.require(type(member.size) is int and 0 <= member.size <= MAX_EXPORT_FILE_BYTES,
                           "Evidence tar member exceeded file byte budget")
            if name in expected:
                worker.require(member.size == expected[name]["bytes"], "Artifact size differs from worker manifest")
            if name.endswith(".json"): worker.require(member.size <= 16 * 1024 * 1024, "JSON evidence exceeded byte budget")
            total += member.size; worker.require(total <= MAX_EXPORT_BYTES, "Evidence files exceeded total byte budget")
            seen.add(name); digest = hashlib.sha256(); copied = 0
            with archive.extractfile(member) as entry, (destination / name).open("xb") as target:
                while block := entry.read(65536):
                    within_deadline()
                    copied += len(block); worker.require(copied <= member.size, "Tar payload exceeded declared file size")
                    target.write(block); digest.update(block)
            worker.require(copied == member.size, "Truncated evidence tar member")
            actual = digest.hexdigest()
            if name in expected: worker.require(actual == expected[name]["sha256"], "Artifact SHA-256 differs from worker manifest")
            exported.append({"file": name, "bytes": copied, "sha256": actual, "workerManifestVerified": name in expected})
        # Reject concatenated/hidden entries after the logical tar end as well.
        raw.seek(archive.offset)
        while block := raw.read(65536):
            within_deadline()
            worker.require(not any(block), "Unexpected trailing evidence tar content")
    worker.require(seen == names, "Evidence tar is missing required manifest/report files")
    result = json.loads((destination / "result.json").read_bytes())
    canonical_json = lambda value: json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)
    worker.require(canonical_json(result) == canonical_json({key: value for key, value in report.items() if key != "evidenceLease"}),
                   "Exported result differs from the complete worker report")
    if "application" in report:
        worker.require(canonical_json(json.loads((destination / "application.json").read_bytes())) == canonical_json(report["application"]),
                       "Exported application result differs from the worker report")
    return {"files": exported, "bytes": total, "workerManifestFilesVerified": len(expected)}


def export_evidence(cid, output, report):
    worker.require(isinstance(cid, str) and re.fullmatch(r"[0-9a-f]{64}", cid), "Expected the exact verified container ID")
    _manifest, names = evidence_manifest(report)
    # Explicit validated names avoid tar's directory header for '.', and never follow arbitrary paths.
    command = ["docker", "exec", cid, "/usr/bin/tar", "-C", "/tmp/rwxx-v7-evidence", "-cf", "-", "--", *sorted(names)]
    deadline = time.monotonic() + 20
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        captured = capture_tar_stream(process, Path(output) / "container-evidence.tar", timeout_seconds=max(0, deadline - time.monotonic()))
        extracted = extract_evidence_tar(Path(output) / "container-evidence.tar", Path(output) / "container", report, deadline)
        return {"method": "owned-exec-tar", "command": command, **captured, **extracted}
    finally:
        if process.poll() is None:
            process.kill()
            try: process.wait(timeout=2)
            except subprocess.TimeoutExpired: pass


def run(image, output, config):
    worker.validate_config(config)
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    owner = uuid.uuid4().hex; name = "rwxx-netem-v7-" + owner
    command = create_command(image, name, owner, config)
    report = {"scope": worker.SCOPE, "passed": False, "image": image, "containerName": name,
              "startedAt": datetime.now(timezone.utc).isoformat(), "configuration": config,
              "launcherSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "workerSha256": hashlib.sha256(Path(worker.__file__).read_bytes()).hexdigest(),
              "guardHelperSha256": hashlib.sha256(Path(worker.guards.__file__).read_bytes()).hexdigest()}
    (output / "create-command.json").write_text(json.dumps(command, indent=2) + "\n")
    process = None; cid = None; verified = False; attempted = False; captured = False
    copy_attempted = False; release_sent = False
    buffers = (bytearray(), bytearray())
    def save_process(stdout, stderr):
        nonlocal captured
        buffers[0].extend(stdout); buffers[1].extend(stderr)
        (output / "worker.stdout.json").write_bytes(buffers[0]); (output / "worker.stderr.log").write_bytes(buffers[1])
        captured = True
    def copy_evidence(safe_id):
        nonlocal copy_attempted
        copy_attempted = True
        worker.require(verified and safe_id == cid and process is not None and process.poll() is None,
                       "Evidence export requires this exact verified live container")
        report["evidenceCopy"] = {**export_evidence(safe_id, output, report.get("worker")), "workerAliveBeforeCopy": True}
    def release_and_collect(timeout):
        nonlocal release_sent
        # communicate closes the pipe after the explicit command, so exceptional paths also deliver EOF.
        payload = None if release_sent else b"RELEASE\n"
        release_sent = True
        report["evidenceReleaseSent"] = True
        stdout, stderr = process.communicate(input=payload, timeout=timeout)
        save_process(stdout, stderr)
    try:
        attempted = True
        created = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
        (output / "create.stderr.log").write_text(created.stderr)
        worker.require(created.returncode == 0 and re.fullmatch(r"[0-9a-f]{64}", created.stdout.strip()), "Docker create failed or returned no exact ID")
        cid = created.stdout.strip()
        report["containerIsolation"] = verify_container(cid, name, owner, config["jar_sha256"]); verified = True
        process = subprocess.Popen(["docker", "start", "--attach", "--interactive", cid],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, _stderr = read_worker_report(process, config["timeout_seconds"] + 30, buffers)
        report["worker"] = json.loads(stdout)
        worker.require(report["worker"].get("evidenceLease") == {"protocol": "RELEASE-or-EOF", "timeoutSeconds": 30},
                       "Worker does not support the bounded evidence lease")
        worker.require(report["worker"].get("workerSha256") == report["workerSha256"] and
                       report["worker"].get("guardHelperSha256") == report["guardHelperSha256"] and
                       report["worker"].get("jarSha256") == config["jar_sha256"], "Container worker/helper/jar provenance differs")
        # /tmp is a tmpfs: export only while the worker is explicitly retaining its container.
        copy_evidence(cid)
        release_and_collect(10)
        report["workerExitCode"] = process.returncode
        worker.require(process.returncode == 0 and report["worker"].get("passed") is True, "Real-v7 container acceptance failed")
        worker.require(json.loads(buffers[0]) == report["worker"], "Worker emitted unexpected output after its result")
        report["passed"] = True
    except (Exception, KeyboardInterrupt) as error:
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        # A verified immutable ID remains our own even if a later inspect daemon call times out.
        safe_id = cid if verified else None
        cleanup_errors = []
        try:
            if attempted:
                resolved = owned_id(name, owner)
                if resolved is not None: safe_id = resolved
        except Exception as error:
            cleanup_errors.append("ownership lookup: " + str(error))
        try:
            if safe_id is not None:
                if verified and process is not None and not copy_attempted:
                    try: copy_evidence(safe_id)
                    except Exception as error: cleanup_errors.append("evidence copy: " + str(error))
                if process is not None and process.poll() is None:
                    try: release_and_collect(5)
                    except Exception as error: cleanup_errors.append("evidence lease release: " + str(error))
                if process is not None and process.poll() is None:
                    try:
                        stopped = subprocess.run(["docker", "stop", "--time", "25", safe_id], capture_output=True, text=True, timeout=30, check=False)
                        report["ownedStop"] = {"returncode": stopped.returncode, "stdout": stopped.stdout, "stderr": stopped.stderr}
                    except Exception as error: cleanup_errors.append("owned stop: " + str(error))
                if process is not None and not captured:
                    try:
                        if process.poll() is None: process.kill()
                        stdout, stderr = process.communicate(timeout=5); save_process(stdout, stderr)
                    except Exception as error: cleanup_errors.append("own CLI output: " + str(error))
            else:
                report["cleanup"] = {"removed": False, "notCreatedOrNotOwned": True}
                if verified: report["passed"] = False
        except Exception as error:
            cleanup_errors.append(str(error))
        finally:
            if safe_id is not None:
                try:
                    removed = subprocess.run(["docker", "rm", "--force", safe_id], capture_output=True, text=True, timeout=15, check=False)
                    report["cleanup"] = {"containerId": safe_id, "removed": removed.returncode == 0,
                                         "stdout": removed.stdout, "stderr": removed.stderr}
                    if removed.returncode: cleanup_errors.append("own container removal failed")
                except Exception as error: cleanup_errors.append("own container removal: " + str(error))
            if process is not None and process.poll() is None:
                process.kill()
                try: stdout, stderr = process.communicate(timeout=5); save_process(stdout, stderr)
                except Exception: pass
            if not captured:
                try: save_process(b"", b"")
                except Exception as error: cleanup_errors.append("partial worker output: " + str(error))
            if cleanup_errors: report["passed"] = False; report["cleanupErrors"] = cleanup_errors
            (output / "result.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")

    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True); parser.add_argument("--jar-sha256", required=True); parser.add_argument("--output", required=True)
    parser.add_argument("--scenario", choices=("clean", "delay", "loss"), default="loss")
    parser.add_argument("--delay-ms", type=float, default=10); parser.add_argument("--jitter-ms", type=float, default=2)
    parser.add_argument("--loss-percent", type=float, default=5); parser.add_argument("--duplicate-percent", type=float, default=0)
    parser.add_argument("--reorder-percent", type=float, default=0); parser.add_argument("--rate-mbit", type=float, default=10)
    parser.add_argument("--seed", type=int); parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args(); impaired = args.scenario != "clean"
    config = {"jar_sha256": args.jar_sha256, "timeout_seconds": args.timeout, "profile": {
        "name": args.scenario, "delay_ms": args.delay_ms if impaired else 0, "jitter_ms": args.jitter_ms if impaired else 0,
        "loss_percent": args.loss_percent if args.scenario == "loss" else 0, "duplicate_percent": args.duplicate_percent if impaired else 0,
        "reorder_percent": args.reorder_percent if impaired else 0, "rate_mbit": args.rate_mbit if impaired else 0,
        "seed": args.seed if impaired else None}}
    def terminated(signum, _frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        raise InterruptedError("Received signal " + str(signum))
    previous = signal.signal(signal.SIGTERM, terminated)
    try: report = run(args.image, args.output, config)
    except Exception as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr); return 1
    finally: signal.signal(signal.SIGTERM, previous)
    print(json.dumps({"passed": report["passed"], "output": str(Path(args.output).absolute()), "scope": report["scope"]}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
