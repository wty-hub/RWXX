#!/usr/bin/env python3
"""Container-only Linux loopback/netem TCP oracle. It does not run any game protocol."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time


LINUX_TCP_INFO = 11

SCOPE = "Linux isolated-netns loopback netem + real bidirectional TCP; not v7 game acceptance"
FIELDS = {"name", "delay_ms", "jitter_ms", "loss_percent", "duplicate_percent", "reorder_percent",
          "rate_mbit", "seed", "payload_bytes", "timeout_seconds"}
DEFAULT_DOWN_TUNNELS = {"tunl0": "ipip", "gre0": "gre", "gretap0": "ether", "erspan0": "ether",
                        "ip_vti0": "ipip", "ip6_vti0": "tunnel6", "sit0": "sit",
                        "ip6tnl0": "tunnel6", "ip6gre0": "gre6"}
EXTERNAL_LINK_FIELDS = {"master", "link", "link_index", "link_netnsid", "link_netns", "parentdev", "parentbus"}


def validate_network(links, route_tables):
    """Permit only lo and unassociated, inactive exact kernel default tunnel names."""
    if not isinstance(links, list) or not links:
        raise RuntimeError("Refusing netem: invalid interface inventory")
    names = set()
    for entry in links:
        if not isinstance(entry, dict) or not isinstance(entry.get("ifname"), str) or entry["ifname"] in names:
            raise RuntimeError("Refusing netem: malformed or duplicate interface")
        name = entry["ifname"]; names.add(name)
        if any(entry.get(key) is not None for key in EXTERNAL_LINK_FIELDS):
            raise RuntimeError("Refusing netem: linked/master/externally associated interface " + name)
        if name == "lo":
            if entry.get("link_type", "loopback") != "loopback":
                raise RuntimeError("Refusing netem: lo is not loopback")
            continue
        flags = entry.get("flags")
        if name not in DEFAULT_DOWN_TUNNELS or not isinstance(flags, list) or not all(isinstance(v, str) for v in flags) or \
                "UP" in flags or "LOWER_UP" in flags or entry.get("operstate") != "DOWN" or \
                entry.get("link_type") != DEFAULT_DOWN_TUNNELS[name]:
            raise RuntimeError("Refusing netem: unexpected or active interface " + name)
    if "lo" not in names:
        raise RuntimeError("Refusing netem: missing lo")
    for routes in route_tables:
        if not isinstance(routes, list):
            raise RuntimeError("Refusing netem: invalid route table")
        for route in routes:
            if not isinstance(route, dict) or route.get("dev") != "lo" or route.get("nexthops"):
                raise RuntimeError("Refusing netem: route is not exclusively local to lo")


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {"profiles"}:
        raise ValueError("Config must contain only profiles")
    profiles = config["profiles"]
    if not isinstance(profiles, list) or not 1 <= len(profiles) <= 16:
        raise ValueError("Expected 1..16 profiles")
    names = set()
    result = []
    for value in profiles:
        if not isinstance(value, dict) or set(value) != FIELDS:
            raise ValueError("Profile fields must match the closed schema")
        p = dict(value)
        if not isinstance(p["name"], str) or not re.fullmatch(r"[a-z0-9_-]{1,32}", p["name"]) or p["name"] in names:
            raise ValueError("Invalid or duplicate profile name")
        names.add(p["name"])
        for key, maximum in [("delay_ms", 5000), ("jitter_ms", 5000), ("loss_percent", 50),
                             ("duplicate_percent", 50), ("reorder_percent", 50), ("rate_mbit", 1000)]:
            n = p[key]
            if isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n) or not 0 <= n <= maximum:
                raise ValueError("Invalid numeric impairment: " + key)
        if p["jitter_ms"] > p["delay_ms"] or (p["reorder_percent"] and not p["delay_ms"]):
            raise ValueError("Jitter must not exceed delay; reorder requires delay")
        for key, low, high in [("payload_bytes", 65536, 16 * 1024 * 1024), ("timeout_seconds", 1, 90)]:
            if type(p[key]) is not int or not low <= p[key] <= high:
                raise ValueError("Invalid bounded transfer setting: " + key)
        if p["seed"] is not None and (type(p["seed"]) is not int or not 0 <= p["seed"] <= 0xffffffff):
            raise ValueError("seed must be null or uint32")
        result.append(p)
    return result


def netem_command(profile):
    def number(value):
        return format(value, ".9g")
    command = ["tc", "qdisc", "replace", "dev", "lo", "root", "handle", "1:", "netem", "limit", "1024"]
    if profile["delay_ms"]:
        command += ["delay", number(profile["delay_ms"]) + "ms"]
        if profile["jitter_ms"]:
            command += [number(profile["jitter_ms"]) + "ms"]
    for field, keyword in [("loss_percent", "loss"), ("duplicate_percent", "duplicate"), ("reorder_percent", "reorder")]:
        if profile[field]:
            command += [keyword, number(profile[field]) + "%"]
    if profile["rate_mbit"]:
        command += ["rate", number(profile["rate_mbit"]) + "mbit"]
    if profile["seed"] is not None:
        command += ["seed", str(profile["seed"])]
    return command


def checked(command, log):
    result = subprocess.run(command, capture_output=True, text=True, timeout=10, check=False)
    log.append({"argv": command, "returncode": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr})
    if result.returncode:
        raise RuntimeError("Unsupported or failed isolated command: " + " ".join(command) + ": " + result.stderr.strip())
    return result.stdout


def tcp_total_retrans(sock):
    # Linux UAPI struct tcp_info: 8 initial u8 bytes, then 24 u32 through total_retrans.
    # https://github.com/torvalds/linux/blob/master/include/uapi/linux/tcp.h
    info = sock.getsockopt(socket.IPPROTO_TCP, LINUX_TCP_INFO, 256)
    if len(info) < 104:
        raise RuntimeError("TCP_INFO lacks Linux tcpi_total_retrans (need >=104 bytes)")
    return struct.unpack_from("=I", info, 100)[0]


def tc_statistics(raw):
    dropped = re.findall(r"\bdropped\s+(\d+)", raw)
    backlog = re.findall(r"\bbacklog\s+(\S+)\s+(\d+)p\b", raw)
    if not dropped or not backlog:
        raise RuntimeError("Cannot parse tc -s dropped/backlog counters")
    return {"droppedPackets": sum(map(int, dropped)),
            "backlog": [{"bytesText": size, "packets": int(packets)} for size, packets in backlog], "raw": raw}


def transfer(profile):
    size = profile["payload_bytes"]
    payloads = {name: ((name.encode("ascii") + bytes(range(256))) * (size // 256 + 1))[:size]
                for name in ("client-to-server", "server-to-client")}
    expected = {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()}
    sockets, threads, errors, received = [], [], [], {}
    started = time.monotonic()
    try:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sockets.append(listener)
        listener.settimeout(profile["timeout_seconds"])
        listener.setsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG, 1200)
        listener.bind(("127.0.0.1", 0)); listener.listen(1)
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sockets.append(client)
        client.settimeout(profile["timeout_seconds"])
        client.setsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG, 1200)
        client.connect(listener.getsockname())
        server, _ = listener.accept(); sockets.append(server)
        for endpoint in (client, server):
            endpoint.settimeout(profile["timeout_seconds"])
            endpoint.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            endpoint.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
            endpoint.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
        before = {"client": tcp_total_retrans(client), "server": tcp_total_retrans(server)}

        def guarded(action):
            try:
                action()
            except Exception as error:
                errors.append(str(error))

        def send(endpoint, data):
            endpoint.sendall(data); endpoint.shutdown(socket.SHUT_WR)

        def receive(endpoint, label):
            digest, count = hashlib.sha256(), 0
            while True:
                data = endpoint.recv(65536)
                if not data:
                    break
                count += len(data)
                if count > size:
                    raise RuntimeError("Received more than the declared payload")
                digest.update(data)
            received[label] = {"bytes": count, "sha256": digest.hexdigest(), "expectedSha256": expected[label]}

        actions = [lambda: send(client, payloads["client-to-server"]), lambda: send(server, payloads["server-to-client"]),
                   lambda: receive(server, "client-to-server"), lambda: receive(client, "server-to-client")]
        for action in actions:
            thread = threading.Thread(target=guarded, args=(action,), daemon=True)
            threads.append(thread); thread.start()
        deadline = started + profile["timeout_seconds"]
        for thread in threads:
            thread.join(max(0, deadline - time.monotonic()))
        if any(thread.is_alive() for thread in threads):
            raise TimeoutError("Bounded full-duplex TCP transfer timed out")
        if errors:
            raise RuntimeError("TCP transfer failed: " + "; ".join(errors))
        after = {"client": tcp_total_retrans(client), "server": tcp_total_retrans(server)}
        if set(received) != set(expected) or any(v["bytes"] != size or v["sha256"] != v["expectedSha256"] for v in received.values()):
            raise RuntimeError("Bidirectional TCP byte count or SHA-256 mismatch")
        delta = {key: after[key] - before[key] for key in before}
        if any(value < 0 for value in delta.values()):
            raise RuntimeError("TCP_INFO counters regressed")
        return {"directions": received, "elapsedSeconds": time.monotonic() - started,
                "tcpInfoBefore": before, "tcpInfoAfter": after, "tcpRetransDelta": delta,
                "totalRetransDelta": sum(delta.values())}
    finally:
        for endpoint in reversed(sockets):
            endpoint.close()
        for thread in threads:
            thread.join(1)


def evaluate(profile, result, stats):
    if profile["loss_percent"] > 0 and (result["totalRetransDelta"] <= 0 or stats["droppedPackets"] <= 0):
        raise RuntimeError("Loss profile did not demonstrate both netem drops and actual data TCP retransmission")


def run(config):
    profiles = validate_config(config)
    report = {"scope": SCOPE, "passed": False, "preflightPassed": False, "profiles": [], "commands": [], "qdiscRemoved": False}
    installed = False
    try:
        if sys.platform != "linux" or os.environ.get("RWXX_NETEM_WORKER") != "1" or not Path("/.dockerenv").is_file():
            raise RuntimeError("Worker requires an explicitly enabled isolated Docker Linux container")
        if not all(shutil.which(tool) for tool in ("tc", "ip")):
            raise RuntimeError("Image requires python3 and iproute2 (tc/ip)")
        links = json.loads(checked(["ip", "-j", "link", "show"], report["commands"]))
        # Docker kernels may create these default DOWN tunnels even with --network none.
        # No tunnel is changed or removed; unknown names and all non-lo routes are rejected.
        validate_network(links, [])
        routes = json.loads(checked(["ip", "-j", "route", "show", "table", "all"], report["commands"]))
        routes6 = json.loads(checked(["ip", "-j", "-6", "route", "show", "table", "all"], report["commands"]))
        validate_network(links, [routes, routes6])
        report["networkIsolation"] = {"interfaces": links, "ipv4Routes": routes, "ipv6Routes": routes6}
        report["kernel"] = os.uname().release
        report["tcVersion"] = checked(["tc", "-V"], report["commands"]).strip()
        checked(["ip", "link", "set", "dev", "lo", "up"], report["commands"])
        # Probe every requested exact argument, including optional seed; never silently omit a feature.
        for profile in profiles:
            checked(netem_command(profile), report["commands"]); installed = True
            checked(["tc", "qdisc", "del", "dev", "lo", "root"], report["commands"]); installed = False
        report["preflightPassed"] = True
        for profile in profiles:
            entry = {"configuration": profile, "passed": False}
            report["profiles"].append(entry)
            checked(netem_command(profile), report["commands"]); installed = True
            try:
                entry.update(transfer(profile))
                entry["tcStatistics"] = tc_statistics(checked(["tc", "-s", "qdisc", "show", "dev", "lo"], report["commands"]))
                evaluate(profile, entry, entry["tcStatistics"])
                entry["passed"] = True
            finally:
                checked(["tc", "qdisc", "del", "dev", "lo", "root"], report["commands"]); installed = False
        report["qdiscRemoved"] = True
        report["passed"] = True
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        if not installed:
            report["qdiscRemoved"] = True
        if installed:
            try:
                checked(["tc", "qdisc", "del", "dev", "lo", "root"], report["commands"])
                report["qdiscRemoved"] = True
            except Exception as error:
                report["cleanupError"] = str(error)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-json", required=True)
    args = parser.parse_args()
    try:
        report = run(json.loads(args.config_json))
    except Exception as error:
        report = {"scope": SCOPE, "passed": False, "error": type(error).__name__ + ": " + str(error)}
    print(json.dumps(report, sort_keys=True), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
