#!/usr/bin/env python3
"""Run the container-only real TCP/netem oracle; never configures host networking.

Build: docker build --pull=false -t rwxx-netem:local -f desktop/src/test/tools/netem/Dockerfile desktop/src/test/tools/netem
Run:   python3 desktop/src/test/tools/authoritative_netem_acceptance.py --image rwxx-netem:local --output NEW_DIRECTORY
This verifies a known-byte TCP channel, not v7 gameplay or WAN performance.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import uuid

import authoritative_netem_worker as worker

OWNER_LABEL = "io.github.rwx.netem.owner"


def create_command(image, name, owner):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/@-]*", image):
        raise ValueError("Invalid image reference")
    return ["docker", "create", "--pull=never", "--name", name, "--label", OWNER_LABEL + "=" + owner,
            "--network", "none", "--cap-drop", "ALL", "--cap-add", "NET_ADMIN", "--read-only",
            "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=32m", "--pids-limit", "64", "--memory", "256m",
            "--cpus", "1.0", "--security-opt", "no-new-privileges:true", "--env", "RWXX_NETEM_WORKER=1",
            "--entrypoint", "/usr/bin/python3", "--interactive", image, "-u", "-B", "-", "--config-json"]


def owned_id(name, owner):
    """Only resolve our exact random name after an interrupted create; never enumerate containers."""
    response = subprocess.run(["docker", "container", "inspect", name], capture_output=True, text=True, timeout=10, check=False)
    if response.returncode:
        return None
    records = json.loads(response.stdout)
    if not isinstance(records, list) or len(records) != 1:
        return None
    record = records[0]
    if record.get("Name") != "/" + name or record.get("Config", {}).get("Labels", {}).get(OWNER_LABEL) != owner:
        return None
    cid = record.get("Id", "")
    return cid if re.fullmatch(r"[0-9a-f]{64}", cid) else None


def cleanup(cid):
    if not re.fullmatch(r"[0-9a-f]{64}", cid):
        raise ValueError("Refusing cleanup without an exact container ID")
    response = subprocess.run(["docker", "rm", "--force", cid], capture_output=True, text=True, timeout=15, check=False)
    return {"containerId": cid, "removed": response.returncode == 0,
            "returncode": response.returncode, "stdout": response.stdout, "stderr": response.stderr}


def verify_container(cid, name, owner):
    response = subprocess.run(["docker", "container", "inspect", cid], capture_output=True, text=True, timeout=10, check=False)
    if response.returncode:
        raise RuntimeError("Cannot inspect the exact newly created container")
    records = json.loads(response.stdout)
    if not isinstance(records, list) or len(records) != 1:
        raise RuntimeError("Invalid own-container inspection")
    record = records[0]
    config, host = record.get("Config", {}), record.get("HostConfig", {})
    normalize = lambda values: {v.upper().removeprefix("CAP_") for v in (values or [])}
    if record.get("Id") != cid or record.get("Name") != "/" + name or config.get("Labels", {}).get(OWNER_LABEL) != owner:
        raise RuntimeError("Own-container identity or ownership label differs")
    if host.get("NetworkMode") != "none" or host.get("ReadonlyRootfs") is not True or host.get("Privileged") is not False or \
            normalize(host.get("CapDrop")) != {"ALL"} or normalize(host.get("CapAdd")) != {"NET_ADMIN"}:
        raise RuntimeError("Own-container network/read-only/capability isolation differs")
    if host.get("Binds") or host.get("Devices") or host.get("DeviceRequests") or \
            any(m.get("Type") != "tmpfs" or m.get("Destination") != "/tmp" for m in record.get("Mounts", [])):
        raise RuntimeError("Own-container unexpectedly has host mounts or devices")
    if host.get("Memory") != 256 * 1024 * 1024 or host.get("NanoCpus") != 1_000_000_000 or host.get("PidsLimit") != 64:
        raise RuntimeError("Own-container resource limits differ")
    if config.get("Entrypoint") != ["/usr/bin/python3"]:
        raise RuntimeError("Own-container Python entrypoint differs")
    return {"Id": cid, "Name": record["Name"], "Image": record.get("Image"),
            "networkMode": host["NetworkMode"], "readOnly": True, "privileged": False,
            "capDrop": host["CapDrop"], "capAdd": host["CapAdd"], "memoryBytes": host["Memory"],
            "nanoCpus": host["NanoCpus"], "pidsLimit": host["PidsLimit"], "entrypoint": config["Entrypoint"]}


def run(image, output, config, timeout_seconds=120):
    worker.validate_config(config)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)  # Never replace historical evidence.
    owner = uuid.uuid4().hex
    name = "rwxx-netem-" + owner
    source = Path(worker.__file__).read_bytes()
    command = create_command(image, name, owner) + [json.dumps(config, separators=(",", ":"))]
    report = {"scope": worker.SCOPE, "passed": False, "containerName": name, "image": image,
              "startedAt": datetime.now(timezone.utc).isoformat(), "configuration": config,
              "workerSha256": hashlib.sha256(source).hexdigest(),
              "launcherSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (output / "create-command.json").write_text(json.dumps(command, indent=2) + "\n")
    cid, process, create_attempted = None, None, False
    try:
        create_attempted = True
        created = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
        (output / "create.stderr.log").write_text(created.stderr)
        if created.returncode:
            raise RuntimeError("Isolated container creation failed: " + created.stderr.strip())
        cid = created.stdout.strip()
        if not re.fullmatch(r"[0-9a-f]{64}", cid):
            cid = None
            raise RuntimeError("Docker did not return an exact container ID")
        report["containerId"] = cid
        report["containerIsolation"] = verify_container(cid, name, owner)
        process = subprocess.Popen(["docker", "start", "--attach", "--interactive", cid],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, stderr = process.communicate(input=source, timeout=timeout_seconds)
        (output / "worker.stdout.json").write_bytes(stdout)
        (output / "worker.stderr.log").write_bytes(stderr)
        report["workerExitCode"] = process.returncode
        report["worker"] = json.loads(stdout)
        if process.returncode != 0 or report["worker"].get("passed") is not True:
            raise RuntimeError("Container TCP/netem oracle failed; inspect worker evidence")
        report["passed"] = True
    except (Exception, KeyboardInterrupt) as error:
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        # These are only our own docker CLI process and exact labeled container, never other jobs.
        if process is not None and process.poll() is None:
            process.kill()
            stdout, stderr = process.communicate()
            (output / "worker.stdout.json").write_bytes(stdout)
            (output / "worker.stderr.log").write_bytes(stderr)
        try:
            if cid is None and create_attempted:
                cid = owned_id(name, owner)
            if cid is not None:
                report["cleanup"] = cleanup(cid)
                if not report["cleanup"]["removed"]:
                    report["passed"] = False
            else:
                report["cleanup"] = {"removed": False, "notCreatedOrNotOwned": True}
        except Exception as error:
            report["passed"] = False
            report["cleanupError"] = str(error)
        (output / "result.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return report


def profiles(args):
    result = []
    for name in (["clean", "delay", "loss"] if args.scenario == "all" else [args.scenario]):
        impaired = name != "clean"
        result.append({"name": name, "delay_ms": args.delay_ms if impaired else 0,
                       "jitter_ms": args.jitter_ms if impaired else 0,
                       "loss_percent": args.loss_percent if name == "loss" else 0,
                       "duplicate_percent": args.duplicate_percent if impaired else 0,
                       "reorder_percent": args.reorder_percent if impaired else 0,
                       "rate_mbit": args.rate_mbit if impaired else 0, "seed": args.seed if impaired else None,
                       "payload_bytes": args.payload_bytes if name == "loss" else min(args.payload_bytes, 1024 * 1024),
                       "timeout_seconds": 45})
    return {"profiles": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scenario", choices=["all", "clean", "delay", "loss"], default="all")
    parser.add_argument("--seed", type=int)
    parser.add_argument("--delay-ms", type=float, default=10)
    parser.add_argument("--jitter-ms", type=float, default=2)
    parser.add_argument("--loss-percent", type=float, default=5)
    parser.add_argument("--duplicate-percent", type=float, default=0)
    parser.add_argument("--reorder-percent", type=float, default=0)
    parser.add_argument("--rate-mbit", type=float, default=10)
    parser.add_argument("--payload-bytes", type=int, default=2 * 1024 * 1024)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 300:
        parser.error("--timeout must be 1..300 seconds")

    def terminated(signum, _frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        raise InterruptedError("Received signal " + str(signum))
    previous = signal.signal(signal.SIGTERM, terminated)
    try:
        report = run(args.image, args.output, profiles(args), args.timeout)
    except Exception as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous)
    print(json.dumps({"passed": report["passed"], "output": str(Path(args.output).absolute()), "scope": report["scope"]}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
