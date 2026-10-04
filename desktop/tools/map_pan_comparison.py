#!/usr/bin/env python3
"""Compare frozen runtimes while panning the same real replay.

Protocol: Vulkan, 1920x1080, MSAA 4, vsync off, FPS cap 300, replay at 1x,
20 s warmup followed by two 20 s windows in each fresh process. No unit fixture.
Process order is baseline, candidate, candidate, baseline. No JFR or native GPU
diagnostic instrumentation is enabled. The caller must finish builds and close
other game instances before starting this runner.

The CSV records successful presentation acceptance, not physical monitor scanout.
Freshness means a change of (generation, sequence) at presentation acceptance.
Fresh intervals join successive fresh acceptances; the first interval in a window
can begin before its left boundary. The unfinished interval at the right boundary
is excluded. Both boundaries are inclusive, matching presentation_window().
"""

import argparse
import bisect
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "desktop/tools"))
from analyze_vulkan_matrix import percentile, presentation_window, read_json_lines
from vulkan_native_matrix import java_command
from windows_backend_comparison import system_state, write_json

WARMUP_SECONDS = 20
SAMPLE_SECONDS = 20
REPETITIONS = 2
AUTO_EXIT_SECONDS = 95  # Also allows map loading before the fixture warmup starts.
PROCESS_TIMEOUT_SECONDS = 155
ORDER = ("baseline", "candidate", "candidate", "baseline")
REPLAY_ALIAS = "comparison.replay"  # Windows Java launcher can lose non-ANSI names.
NO_CONSOLE = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def read_trace(path: Path) -> list[tuple[int, int, int]]:
    trace = []
    with path.open(encoding="utf-8") as stream:
        for index, row in enumerate(csv.reader(stream)):
            if index == 0 and row == ["acceptedPresentNanos", "generation", "sequence"]:
                continue
            if len(row) != 3:
                raise ValueError(f"Malformed frame row {index + 1} in {path}")
            trace.append(tuple(map(int, row)))
    if any(a[0] > b[0] for a, b in zip(trace, trace[1:])):
        raise ValueError("Accepted presentation timestamps must be ordered")
    return trace


def fresh_intervals(trace: list[tuple[int, int, int]], sample: dict) -> dict:
    """Assign intervals to their ending fresh acceptance, including left crossing."""
    start, end = sample["sampleStartNanos"], sample["sampleEndNanos"]
    if end <= start:
        raise ValueError("Sample end must follow its start")
    if any(a[0] > b[0] for a, b in zip(trace, trace[1:])):
        raise ValueError("Accepted presentation timestamps must be ordered")
    times = [row[0] for i, row in enumerate(trace)
             if i == 0 or row[1:] != trace[i - 1][1:]]
    first, last = bisect.bisect_left(times, start), bisect.bisect_right(times, end)
    intervals = [(times[i] - times[i - 1]) / 1e6 for i in range(max(first, 1), last)]
    crosses_left = first > 0 and first < last and times[first - 1] < start
    previous = times[first - 1] if first > 0 else None
    return {
        "freshSnapshotIntervalP95Ms": percentile(intervals, .95),
        "freshSnapshotIntervalP99Ms": percentile(intervals, .99),
        "freshSnapshotIntervalMaxMs": max(intervals) if intervals else None,
        "freshSnapshotIntervalCount": len(intervals),
        "freshIntervalLeftBoundaryCrossings": int(crosses_left),
        "previousFreshAcceptanceNanos": previous,
        "firstFreshAcceptanceNanos": times[first] if first < last else None,
        "lastFreshAcceptanceNanos": times[last - 1] if first < last else None,
        "rightBoundaryUnfinishedIntervalMs": (end - times[last - 1]) / 1e6 if last else None,
    }


def camera_evidence(setup: dict, window: dict) -> dict:
    spans = {axis: window.get(f"cameraSpan{axis}") for axis in ("X", "Y")}
    confirmed = (setup.get("cameraMode") in ("pan", "jump") and window.get("cameraMode") == setup.get("cameraMode")
                 and all(isinstance(value, (int, float)) and value > 0 for value in spans.values()))
    return {"cameraMovementConfirmed": confirmed, "measuredCameraSpans": spans}


def analyze_run(output: Path, run: dict) -> dict:
    name = run["name"]
    scenario_path, trace_path = output / f"{name}-scenario.ndjson", output / f"{name}-trace.csv"
    scenario = read_json_lines(scenario_path) if scenario_path.exists() else []
    trace = read_trace(trace_path) if trace_path.exists() else []
    setups = [row for row in scenario if row.get("kind") == "scenario"]
    setup = setups[0] if len(setups) == 1 else {}
    samples = [row for row in scenario if row.get("kind") == "engine-window"]
    windows = []
    for sample in samples:
        window = presentation_window(trace, sample)
        window.update(fresh_intervals(trace, sample))
        window.update(camera_evidence(setup, sample))
        seconds = (sample["sampleEndNanos"] - sample["sampleStartNanos"]) / 1e9
        window.update({"sampleSecondsMeasured": seconds, "renderFps": window["acceptedPresentFps"],
                       "newFps": window["freshSnapshotHz"]})
        windows.append(window)
    reasons = []
    if run.get("exitCode") != 0 or run.get("timedOut"):
        reasons.append("process did not exit normally")
    required = {"cameraMode": run.get("cameraModeExpected", "pan"), "replaySuccess": True, "replaySpeed": 1, "replayRate": 1,
                "warmupSeconds": WARMUP_SECONDS,
                "sampleSeconds": SAMPLE_SECONDS, "repetitions": REPETITIONS}
    if run.get("localMapExpected"):
        required.update({"replaySuccess": False, "localMapFixture": True})
    if "mapFogExpected" in run and setup.get("mapFogEnabled") != run["mapFogExpected"]:
        reasons.append("live map fog state differs from requested case")
    for key, expected in required.items():
        if setup.get(key) != expected:
            reasons.append(f"scenario {key}: expected {expected!r}, got {setup.get(key)!r}")
    if "fogDisplayExpected" in run and setup.get("fogDisplayEnabled") != run["fogDisplayExpected"]:
        reasons.append("replay fog display state differs from requested case")
    if len(setups) != 1:
        reasons.append("expected exactly one scenario setup")
    if [window.get("repetition") for window in windows] != list(range(1, REPETITIONS + 1)):
        reasons.append(f"expected {REPETITIONS} consecutive measurement windows")
    for window in windows:
        if not window["cameraMovementConfirmed"]:
            reasons.append(f"window {window.get('repetition')}: no measured camera movement")
        if window.get("replaySpeed") != 1 or window.get("replayRate") != 1:
            reasons.append(f"window {window.get('repetition')}: replay speed is not 1x")
        if window["acceptedPresentFps"] <= 0 or window["freshSnapshotHz"] <= 0:
            reasons.append(f"window {window.get('repetition')}: no fresh accepted presentations")
        if not isinstance(window.get("sampleStartTick"), int) or not isinstance(window.get("sampleEndTick"), int) or window["sampleEndTick"] <= window["sampleStartTick"]:
            reasons.append(f"window {window.get('repetition')}: replay simulation did not advance")
        if not SAMPLE_SECONDS <= window["sampleSecondsMeasured"] <= SAMPLE_SECONDS + 2:
            reasons.append(f"window {window.get('repetition')}: sample duration outside expected range")
    log_path = output / f"{name}.log"
    log = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
    if re.search(r"uncaughtException start|RWX engine owner stopped after a failed loop", log):
        reasons.append("uncaught game exception invalidates the measurement")
    replay_identity = replay_identity_evidence(setup, run, log)
    if run.get("replayName") and not replay_identity["confirmed"]:
        reasons.append("requested replay identity could not be confirmed from scenario or verified alias/load log")
    evidence = [line for line in log.splitlines() if re.search(
        r"Using Kool render backend|device:|Device:|device name|Device name|swapchain|Swapchain|"
        r"MSAA|samples=|Present mode|present mode|Vulkan version|Vulkan device|Exception|ERROR|FATAL|Failed", line)]
    frame_path = output / f"{name}-frame.jsonl"
    return {**run, "setup": setup or None, "windows": windows,
            "frameWindows": read_json_lines(frame_path) if frame_path.exists() else [],
            "backendEvidence": evidence[:80], "replayIdentityEvidence": replay_identity, "validMeasurement": not reasons,
            "invalidReasons": reasons}


def replay_identity_evidence(setup: dict, run: dict, log: str) -> dict:
    """Preserve missing ProcessHandle arguments and record independent load proof."""
    expected = run.get("replayName")
    reported = setup.get("replayPath")
    matched_lines = [line for line in log.splitlines() if expected and re.search(
        r"Prepared DESKTOP KOOL RW replay asynchronously:\s*" + re.escape(expected) + r"(?![\w.])", line)]
    no_missing_replay = "replay not found" not in log.lower()
    alias_path = Path(run["replayAliasPath"]) if run.get("replayAliasPath") else None
    verified_alias = False
    single_file = False
    if alias_path is not None and alias_path.is_file():
        verified_alias = (bool(run.get("replayAliasVerified")) and alias_path.name == expected
                          and hashlib.sha256(alias_path.read_bytes()).hexdigest() == run.get("replayAliasSha256"))
        single_file = list(alias_path.parent.iterdir()) == [alias_path]
    direct = bool(expected) and reported == expected
    fallback = (reported in (None, "") and bool(matched_lines) and no_missing_replay
                and verified_alias and single_file)
    return {"confirmed": direct or fallback,
            "source": "scenario" if direct else "verified-alias-and-load-log" if fallback else None,
            "expectedAlias": expected, "scenarioReplayPath": reported,
            "aliasSha256Verified": verified_alias, "isolatedReplayDirectoryContainsOnlyAlias": single_file,
            "noReplayNotFoundLog": no_missing_replay, "loadLogMatches": matched_lines}


def summarize_comparison(runs: list[dict]) -> dict:
    """Use median window statistics; p95/p99 medians are not pooled quantiles."""
    metrics = ("engineFps", "renderFps", "newFps", "repeatRatio", "presentIntervalP95Ms",
               "presentIntervalP99Ms", "freshSnapshotIntervalP95Ms", "freshSnapshotIntervalP99Ms")
    variants = {}
    for variant in ("baseline", "candidate"):
        subset = [run for run in runs if run["variant"] == variant]
        windows = [window for run in subset for window in run.get("windows", [])]
        variants[variant] = {"processCount": len(subset), "windowCount": len(windows),
                             "allMeasurementsValid": bool(subset) and all(run["validMeasurement"] for run in subset)}
        for metric in metrics:
            values = [window[metric] for window in windows if window.get(metric) is not None]
            variants[variant][metric] = statistics.median(values) if values else None
    changes = {}
    for metric in metrics:
        before, after = variants["baseline"][metric], variants["candidate"][metric]
        changes[metric] = {"absolute": after - before if before is not None and after is not None else None,
                           "percent": (after / before - 1) * 100 if before and after is not None else None}
    complete = len(runs) == len(ORDER) and tuple(run["variant"] for run in runs) == ORDER
    valid = complete and all(run["validMeasurement"] for run in runs)
    viewports = {(run["setup"].get("viewportWidth"), run["setup"].get("viewportHeight"))
                 for run in runs if run.get("setup")}
    return {"validComparison": valid and len(viewports) == 1,
            "matchingMeasuredViewports": len(viewports) == 1,
            "measuredViewports": [list(viewport) for viewport in sorted(viewports, key=str)],
            "aggregation": "median of measurement-window values; percentile medians are not pooled percentiles",
            "variants": variants, "candidateChange": changes}


def machine_state() -> dict:
    state = system_state()
    try:
        active = subprocess.run(["powercfg", "/getactivescheme"], capture_output=True,
                                text=True, errors="replace", creationflags=NO_CONSOLE)
        state["activePowerScheme"] = active.stdout.strip() if active.returncode == 0 else active.stderr.strip()
    except OSError as error:
        state["activePowerScheme"] = {"error": str(error)}
    return state


def isolated_seed() -> str:
    seed = (PROJECT / "preferences.toml").read_text(encoding="utf-8")
    settings = {"renderVsync": "false", "slick2dFullScreen": "false", "maxFrameRate": "300",
                "highRefreshRate": "true", "batterySaving": "false"}
    for key, value in settings.items():
        seed, count = re.subn(rf'(?m)^{key}\s*=\s*"[^"\n]*"', f'{key} = "{value}"', seed)
        if count != 1:
            raise RuntimeError(f"Expected exactly one preference entry for {key}")
    return seed


def run_environment(output: Path, sandbox: Path, name: str) -> dict[str, str]:
    env = {key: value for key, value in os.environ.items() if not key.startswith("RWX_")
           and key not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                           "MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS")}
    env.update({"APPDATA": str(sandbox / "appdata"), "RWX_WINDOW_WIDTH": "1920", "RWX_WINDOW_HEIGHT": "1080",
                "RWX_KOOL_MSAA_SAMPLES": "4", "RWX_DESKTOP_TARGET_FPS": "300",
                "RWX_FRAME_METRICS": str(output / f"{name}-frame.jsonl"),
                "RWX_FRAME_TRACE": str(output / f"{name}-trace.csv"),
                "RWX_REPLAY_PAN_WARMUP_SECONDS": str(WARMUP_SECONDS),
                "RWX_REPLAY_PAN_SAMPLE_SECONDS": str(SAMPLE_SECONDS), "RWX_REPLAY_PAN_REPETITIONS": str(REPETITIONS),
                "RWX_REPLAY_PAN_OUTPUT": str(output / f"{name}-scenario.ndjson"),
                "RWX_PERF_LOG": "1", "RWX_DEBUG_AUTO_EXIT_SECONDS": str(AUTO_EXIT_SECONDS)})
    return env


def copy_replay_alias(frozen_replay: Path, sandbox: Path, expected_sha256: str) -> Path:
    target = sandbox / "replays" / REPLAY_ALIAS
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(frozen_replay, target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != expected_sha256:
        raise RuntimeError(f"Replay alias bytes do not match frozen source: {target}")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--replay", type=Path, required=True, help="Real replay file copied into each isolated launch directory")
    parser.add_argument("--fog", choices=("off", "on"), default="off")
    parser.add_argument("--native-metrics", action="store_true", help="Collect identical Vulkan GPU/upload counters in both variants")
    default_java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if "JAVA_HOME" in os.environ else shutil.which("java")
    parser.add_argument("--java", default=default_java)
    args = parser.parse_args()
    if not args.java or not all(jar.is_file() for jar in (args.baseline, args.candidate)):
        parser.error("Existing baseline/candidate jars and a Java 25+ executable are required")
    if not args.replay.is_file():
        parser.error(f"Replay file does not exist: {args.replay}")
    version = subprocess.run([args.java, "-version"], capture_output=True, text=True, creationflags=NO_CONSOLE)
    java_version = version.stdout + version.stderr
    match = re.search(r'version "(?:1\.)?(\d+)', java_version)
    if version.returncode or match is None or int(match[1]) < 25:
        parser.error("The packaged runtime requires Java 25 or newer")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    runtimes = {}
    for variant, jar in (("baseline", args.baseline), ("candidate", args.candidate)):
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        snapshot = output / f"{variant}-{digest[:16]}.jar"
        shutil.copy2(jar, snapshot)
        runtimes[variant] = {"source": str(jar.resolve()), "runtime": str(snapshot), "sha256": digest}
    if runtimes["baseline"]["sha256"] == runtimes["candidate"]["sha256"]:
        parser.error("Baseline and candidate jars have identical SHA-256 hashes")
    replay_digest = hashlib.sha256(args.replay.read_bytes()).hexdigest()
    frozen_replay = output / args.replay.name
    shutil.copy2(args.replay, frozen_replay)
    seed = isolated_seed()
    (output / "seed-preferences.toml").write_text(seed, encoding="utf-8")
    report = {"startedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(),
              "runtimes": runtimes, "javaVersion": java_version.strip(), "machine": machine_state(),
              "replay": {"source": str(args.replay.resolve()), "originalName": args.replay.name,
                         "snapshot": str(frozen_replay), "sha256": replay_digest, "launchAlias": REPLAY_ALIAS,
                         "aliasVerification": "SHA-256 of each isolated alias must equal source SHA-256"},
              "protocol": {"order": ORDER, "backend": "vulkan", "width": 1920, "height": 1080,
                           "msaa": 4, "vsync": False, "targetFps": 300, "replaySpeed": 1,
                           "replayFogDisplay": args.fog,
                           "camera": "pan", "unitFixture": False,
                           "warmupSeconds": WARMUP_SECONDS, "sampleSeconds": SAMPLE_SECONDS,
                           "samplesPerProcess": REPETITIONS, "freshProcesses": True, "isolatedPreferences": True,
                           "autoExitSeconds": AUTO_EXIT_SECONDS, "hardTimeoutSeconds": PROCESS_TIMEOUT_SECONDS,
                           "nativeGpuDiagnostics": args.native_metrics, "jfr": False,
                           "maxPowerSettings": {"batterySaving": False, "highRefreshRate": True},
                           "otherGameAndBuildLoads": "caller must close/finish before invocation",
                           "measurement": "successful Vulkan queue-present acceptance, not physical monitor scanout",
                           "freshness": "change of generation/sequence at successful presentation acceptance",
                           "windowBounds": "inclusive [sampleStartNanos, sampleEndNanos]; shared endpoint can occur in both windows",
                           "freshIntervals": "ending fresh acceptance inside window; include left-boundary crossing; exclude unfinished right tail",
                           "quantileMethod": "sorted_values[int((sample_count - 1) * fraction)]",
                           "operatingState": "visible game window requested; lock/occlusion not independently measured"}, "runs": []}
    summary_path = output / "summary.json"
    write_json(summary_path, report)
    for index, variant in enumerate(ORDER, 1):
        name = f"{index:02d}-{variant}-vulkan-replay-pan"
        sandbox = output / name
        sandbox.mkdir()
        alias_path = copy_replay_alias(frozen_replay, sandbox, replay_digest)
        (sandbox / "preferences.toml").write_text(seed, encoding="utf-8")
        command = java_command(args.java, Path(runtimes[variant]["runtime"]), PROJECT / "desktop/build/slick-natives")
        command = [argument for argument in command if argument not in ("--screen=battleroom", "--auto-start-battleroom")]
        command.append(f"--replay={REPLAY_ALIAS}")
        command[1:1] = [f"-Dlaunch.dir={sandbox}", f"-Drwx.assetsDir={PROJECT / 'assets'}",
                        "-Drwx.kool.backend=vulkan", "-Xms512m", "-Xmx2g"]
        env = run_environment(output, sandbox, name)
        env["RWX_REPLAY_PAN_FOG"] = args.fog
        if args.native_metrics:
            env["RWX_VK_METRICS"] = str(output / f"{name}-vulkan.jsonl")
        before = machine_state()
        started = time.monotonic()
        print(f"START {index}/{len(ORDER)} {variant}: {WARMUP_SECONDS}s warmup + {REPETITIONS} x {SAMPLE_SECONDS}s samples", flush=True)
        timed_out = False
        with (output / f"{name}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=NO_CONSOLE)
            print(f"PID {process.pid}", flush=True)
            try:
                exit_code = process.wait(timeout=PROCESS_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                timed_out = True
                process.kill()
                exit_code = process.wait()
        run = {"name": name, "variant": variant, "index": index, "pid": process.pid,
               "fogDisplayExpected": args.fog == "on",
               "exitCode": exit_code, "timedOut": timed_out, "elapsedSeconds": time.monotonic() - started,
               "replayName": REPLAY_ALIAS, "replayAliasPath": str(alias_path),
               "replayAliasSha256": replay_digest, "replayAliasVerified": True,
               "systemBefore": before, "systemAfter": machine_state(), "argv": command,
               "environment": {key: value for key, value in env.items() if key.startswith("RWX_") or key == "APPDATA"}}
        try:
            analyzed = analyze_run(output, run)
        except (ValueError, KeyError, OSError) as error:
            analyzed = {**run, "windows": [], "validMeasurement": False, "invalidReasons": [f"analysis error: {error}"]}
        report["runs"].append(analyzed)
        report["comparison"] = summarize_comparison(report["runs"])
        write_json(summary_path, report)
        print(f"DONE {variant} exit={exit_code} valid={analyzed['validMeasurement']}", flush=True)
        for window in analyzed["windows"]:
            print(json.dumps({key: window.get(key) for key in ("repetition", "engineFps", "renderFps", "newFps",
                              "repeatRatio", "freshSnapshotIntervalP95Ms", "freshSnapshotIntervalP99Ms",
                              "cameraSpanX", "cameraSpanY", "sampleStartTick", "sampleEndTick", "minimumLivingUnits")}), flush=True)
        if not analyzed["validMeasurement"]:
            print("INVALID: " + "; ".join(analyzed["invalidReasons"]), flush=True)
            print(f"SUMMARY {summary_path}", flush=True)
            return 1
    print("COMPARISON " + json.dumps(report["comparison"], ensure_ascii=False), flush=True)
    print(f"SUMMARY {summary_path}", flush=True)
    return 0 if report["comparison"]["validComparison"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
