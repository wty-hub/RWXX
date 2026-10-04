#!/usr/bin/env python3
"""Record one real-replay pan run with engine phases and JFR instrumentation.

This is a diagnostic recording, not an A/B performance measurement. The caller
must finish builds and close other game instances before invoking it.
"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from map_pan_comparison import (AUTO_EXIT_SECONDS, NO_CONSOLE, PROCESS_TIMEOUT_SECONDS, PROJECT,
                                REPLAY_ALIAS, REPETITIONS, SAMPLE_SECONDS, WARMUP_SECONDS,
                                analyze_run, copy_replay_alias, isolated_seed, machine_state,
                                run_environment, write_json)
from vulkan_native_matrix import java_command

JFR_EVENTS = ("jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GarbageCollection,jdk.GCPhasePause,"
              "jdk.JavaMonitorEnter,jdk.JavaMonitorWait,jdk.ThreadPark,jdk.ThreadSleep,"
              "jdk.SafepointBegin,jdk.SafepointEnd,jdk.FileRead,jdk.FileWrite,"
              "jdk.CPULoad,jdk.ThreadCPULoad")


def export_jfr(java, recording, output, compact=False):
    executable = Path(shutil.which(java) or java).resolve()
    if compact:
        target = output / "compact-profile.ndjson"
        command = [str(executable), "--source", "25", str(PROJECT / "desktop/tools/CompactJfr.java"), str(recording), str(target)]
        print("EXPORT COMPACT JFR", flush=True)
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=120, creationflags=NO_CONSOLE)
            return {"success": result.returncode == 0, "exitCode": result.returncode,
                    "argv": command, "ndjson": str(target), "diagnostics": result.stdout + result.stderr}
        except (OSError, subprocess.TimeoutExpired) as error:
            return {"success": False, "error": str(error), "argv": command}
    jfr = executable.with_name("jfr.exe" if os.name == "nt" else "jfr")
    if not jfr.is_file():
        return {"success": False, "error": f"JDK jfr executable not found: {jfr}"}
    command = [str(jfr), "print", "--json", "--stack-depth", "64", "--events", JFR_EVENTS, str(recording)]
    print("EXPORT JFR JSON", flush=True)
    try:
        with (output / "profile.json").open("w", encoding="utf-8") as target, (output / "jfr-export.log").open("w", encoding="utf-8") as errors:
            result = subprocess.run(command, stdout=target, stderr=errors, timeout=120, creationflags=NO_CONSOLE)
        return {"success": result.returncode == 0, "exitCode": result.returncode,
                "argv": command, "events": JFR_EVENTS, "json": str(output / "profile.json"),
                "log": str(output / "jfr-export.log")}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"success": False, "error": str(error), "argv": command}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True, help="Frozen baseline jar containing replay pan and engine trace hooks")
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stages", action="store_true", help="Record precise Canvas and Vulkan stage timelines")
    parser.add_argument("--compact-jfr", action="store_true", help="Stream compact JFR samples instead of expanding a large JSON document")
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if "JAVA_HOME" in os.environ else shutil.which("java"))
    args = parser.parse_args()
    if not args.java or not args.jar.is_file() or not args.replay.is_file():
        parser.error("Existing baseline jar, real replay file and Java 25+ executable are required")
    version = subprocess.run([args.java, "-version"], capture_output=True, text=True, creationflags=NO_CONSOLE)
    java_version = version.stdout + version.stderr
    match = re.search(r'version "(?:1\.)?(\d+)', java_version)
    if version.returncode or match is None or int(match[1]) < 25:
        parser.error("The packaged runtime requires Java 25 or newer")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    jar_digest = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    replay_digest = hashlib.sha256(args.replay.read_bytes()).hexdigest()
    frozen_jar = output / f"baseline-{jar_digest[:16]}.jar"
    frozen_replay = output / args.replay.name
    shutil.copy2(args.jar, frozen_jar)
    shutil.copy2(args.replay, frozen_replay)
    name = "baseline-diagnostic-replay-pan"
    sandbox = output / name
    sandbox.mkdir()
    alias = copy_replay_alias(frozen_replay, sandbox, replay_digest)
    seed = isolated_seed()
    (output / "seed-preferences.toml").write_text(seed, encoding="utf-8")
    (sandbox / "preferences.toml").write_text(seed, encoding="utf-8")
    profile = output / "profile.jfr"
    engine_trace = output / "engine.csv"
    command = java_command(args.java, frozen_jar, PROJECT / "desktop/build/slick-natives")
    command = [argument for argument in command if argument not in ("--screen=battleroom", "--auto-start-battleroom")]
    command.append(f"--replay={REPLAY_ALIAS}")
    command[1:1] = [f"-Dlaunch.dir={sandbox}", f"-Drwx.assetsDir={PROJECT / 'assets'}", "-Drwx.kool.backend=vulkan",
                    "-Xms512m", "-Xmx2g", "-XX:FlightRecorderOptions=stackdepth=64",
                    f"-XX:StartFlightRecording=filename={profile},settings=profile,dumponexit=true"]
    env = run_environment(output, sandbox, name)
    env.update({"RWX_ENGINE_FRAME_TRACE": str(engine_trace), "RWX_CANVAS_PERF": "1"})
    if args.stages:
        env.update({"RWX_CANVAS_TRACE": str(output / "canvas-stages.csv"),
                    "RWX_VK_TRACE": str(output / "vulkan-stages.csv"),
                    "RWX_VK_METRICS": str(output / "vulkan-windows.jsonl")})
    report = {"startedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(), "diagnosticOnly": True,
              "runtime": {"source": str(args.jar.resolve()), "snapshot": str(frozen_jar), "sha256": jar_digest},
              "replay": {"source": str(args.replay.resolve()), "originalName": args.replay.name,
                         "snapshot": str(frozen_replay), "sha256": replay_digest, "launchAlias": REPLAY_ALIAS},
              "javaVersion": java_version.strip(),
              "protocol": {"backend": "vulkan", "width": 1920, "height": 1080, "msaa": 4,
                           "targetFps": 300, "vsync": False, "replaySpeed": 1, "camera": "pan", "unitFixture": False,
                           "warmupSeconds": WARMUP_SECONDS, "sampleSeconds": SAMPLE_SECONDS,
                           "samplesPerProcess": REPETITIONS, "autoExitSeconds": AUTO_EXIT_SECONDS,
                           "jfrSettings": "profile", "jfrStackDepth": 64, "engineFrameTrace": True,
                           "canvasPerf": True, "nativeGpuDiagnostics": args.stages,
                           "limitation": "JFR and detailed engine tracing add overhead; use only for stall attribution, not baseline/candidate FPS comparison"},
              "profile": str(profile), "engineTrace": str(engine_trace), "argv": command,
              "environment": {key: value for key, value in env.items() if key.startswith("RWX_") or key == "APPDATA"}}
    summary_path = output / "diagnostic-summary.json"
    write_json(summary_path, report)
    before = machine_state()
    started = time.monotonic()
    print(f"START diagnostic: {WARMUP_SECONDS}s warmup + {REPETITIONS} x {SAMPLE_SECONDS}s samples; JFR + engine trace", flush=True)
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
    run = {"name": name, "variant": "baseline-diagnostic", "pid": process.pid,
           "exitCode": exit_code, "timedOut": timed_out, "elapsedSeconds": time.monotonic() - started,
           "replayName": REPLAY_ALIAS, "replayAliasPath": str(alias),
           "replayAliasSha256": replay_digest, "replayAliasVerified": True,
           "systemBefore": before, "systemAfter": machine_state()}
    try:
        report["run"] = analyze_run(output, run)
    except (ValueError, KeyError, OSError) as error:
        report["run"] = {**run, "validMeasurement": False, "invalidReasons": [f"analysis error: {error}"], "windows": []}
    report["recordingPresent"] = profile.is_file() and profile.stat().st_size > 0
    report["engineTracePresent"] = engine_trace.is_file() and engine_trace.stat().st_size > 0
    write_json(summary_path, report)
    if report["recordingPresent"]:
        report["jfrExport"] = export_jfr(args.java, profile, output, args.compact_jfr)
        write_json(summary_path, report)
    print("DONE " + json.dumps({"exitCode": exit_code, "validMeasurement": report["run"]["validMeasurement"],
                               "recordingPresent": report["recordingPresent"], "engineTracePresent": report["engineTracePresent"],
                               "jfrExportSuccess": report.get("jfrExport", {}).get("success")}), flush=True)
    print(f"SUMMARY {summary_path}", flush=True)
    return 0 if exit_code == 0 and not timed_out and report["recordingPresent"] and report["engineTracePresent"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
