#!/usr/bin/env python3
"""Run the vanilla Vulkan matrix against an immutable packaged runtime.

Each combat case has 30 seconds of initial warmup and three consecutive 30-second
measurement windows. The fixture keeps normal unit rules; casualties remain visible
in its output and can invalidate the requested unit count. See README.md.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time


PROJECT = Path(os.environ.get('RWX_BENCHMARK_PROJECT_ROOT', Path(__file__).resolve().parents[2]))
UNIT_COUNTS = (661, 1000, 2000)
MIXES = ("land-air", "sea-air", "all")


def java_command(java: str, jar: Path, natives: Path) -> list[str]:
    command = [java, "-Dorg.lwjgl.opengl.contextAPI=native", "-Dorg.lwjgl.system.stackSize=512",
               "-Drwx.desktop.renderer=kool", "--enable-native-access=ALL-UNNAMED",
               "--sun-misc-unsafe-memory-access=allow"]
    if natives.is_dir():
        command.extend([f"-Djava.library.path={natives}", f"-Dorg.lwjgl.librarypath={natives}",
                        f"-Drwx.slick.nativesDir={natives}"])
    for package in ("java.desktop/sun.awt", "java.desktop/sun.awt.im", "java.base/java.lang"):
        command.extend(["--add-opens", f"{package}=ALL-UNNAMED"])
    return command + ["-cp", str(jar), "io.github.rwx.KoolDesktopMain",
                      "--screen=battleroom", "--auto-start-battleroom"]


def append_manifest(path: Path, entry: dict) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(entry) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True, help="Current platformFatJar output")
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java")
                        if "JAVA_HOME" in os.environ else shutil.which("java"))
    parser.add_argument("--project", type=Path, default=PROJECT)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--units", type=int, nargs="+", choices=UNIT_COUNTS, default=list(UNIT_COUNTS))
    parser.add_argument("--mixes", nargs="+", choices=MIXES, default=list(MIXES))
    parser.add_argument("--target-fps", type=int, default=300)
    parser.add_argument("--msaa-samples", type=int, choices=(1, 2, 4), default=4,
                        help="Render-only quality diagnostic; default quality remains four samples")
    parser.add_argument("--selected", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--combat-only", action="store_true", help="Omit idle and delay/visibility probes")
    parser.add_argument("--operating-state", default="unspecified",
                        help="Record conditions such as visible-unlocked or locked; never inferred")
    args = parser.parse_args()
    if not args.java or not args.jar.is_file() or args.target_fps <= 0:
        parser.error("An existing packaged jar, Java 25+ executable and positive target FPS are required")
    try:
        version = subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        parser.error(f"Cannot execute Java: {error}")
    match = re.search(r'version "(?:1\.)?(\d+)', version.stdout + version.stderr)
    if match is None or int(match[1]) < 25:
        parser.error("The packaged runtime requires Java 25 or newer")
    project = args.project.resolve()
    output = (args.output or project / "build/rwx-benchmark" / time.strftime("%Y%m%d-%H%M%S")).resolve()
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / "vulkan-native-matrix-manifest.ndjson"
    if manifest.exists():
        parser.error("Output already contains a matrix manifest; use a fresh output directory")
    digest = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    snapshot = output / f"runtime-{digest[:16]}.jar"
    shutil.copy2(args.jar, snapshot)
    command = java_command(args.java, snapshot, project / "desktop/build/slick-natives")
    append_manifest(manifest, {"kind": "runtime", "jar": str(snapshot), "sha256": digest,
                              "argv": command, "javaVersion": (version.stdout + version.stderr).strip(),
                              "platform": platform.platform(),
                              "architecture": platform.machine(), "operatingState": args.operating_state,
                              "configuredMsaaSamples": args.msaa_samples,
                              "measurement": "accepted queue-present calls, not physical scanout",
                              "combatProtocol": "30 s initial warmup + three consecutive 30 s samples"})
    cases = [(units, mix, "combat", None) for units in args.units for mix in args.mixes]
    if not args.combat_only:
        cases.extend((2000, "all", "idle", probe) for probe in (None, "upload", "present", "hide"))
    for units, mix, mode, probe in cases:
        name = f"{units}-{mix}-vulkan-{mode}" + (f"-delay-{probe}" if probe else "")
        env = os.environ.copy()
        # A previous shell's delay or fixture mode must not silently contaminate this case.
        for key in ("RWX_VK_SUBMIT_DELAY_MS", "RWX_VK_UPLOAD_DELAY_MS", "RWX_VK_PRESENT_DELAY_MS",
                    "RWX_DEBUG_HIDE_AFTER_SECONDS", "RWX_DEBUG_HIDE_SECONDS",
                    "MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS"):
            env.pop(key, None)
        env.update({"RWX_WINDOW_WIDTH": "1920", "RWX_WINDOW_HEIGHT": "1080",
                    "RWX_KOOL_MSAA_SAMPLES": str(args.msaa_samples),
                    "RWX_FRAME_METRICS": str(output / f"{name}-frame.jsonl"),
                    "RWX_FRAME_TRACE": str(output / f"{name}-trace.csv"),
                    "RWX_VK_METRICS": str(output / f"{name}-native.jsonl"),
                    "RWX_BENCHMARK_UNITS": str(units), "RWX_BENCHMARK_MIX": mix,
                    "RWX_BENCHMARK_MODE": mode, "RWX_BENCHMARK_SELECTED": "1" if args.selected else "0",
                    "RWX_BENCHMARK_OUTPUT": str(output / f"{name}-scenario.ndjson"),
                    "RWX_PERF_LOG": "1", "RWX_KOOL_BACKEND": "vulkan",
                    "RWX_DESKTOP_TARGET_FPS": str(args.target_fps),
                    "RWX_DEBUG_AUTO_EXIT_SECONDS": "40" if probe else "130"})
        if probe == "hide":
            env.update({"RWX_DEBUG_HIDE_AFTER_SECONDS": "15", "RWX_DEBUG_HIDE_SECONDS": "10"})
        elif probe:
            env[f"RWX_VK_{probe.upper()}_DELAY_MS"] = "50"
        started = time.monotonic()
        print(f"START {name}", flush=True)
        log_path = output / f"{name}.log"
        with log_path.open("w", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT)
        record = {"kind": "run", "name": name, "units": units, "mix": mix, "mode": mode,
                  "delay": probe, "exitCode": result.returncode,
                  "elapsedSeconds": time.monotonic() - started, "log": str(log_path)}
        append_manifest(manifest, record)
        print("DONE " + json.dumps(record), flush=True)
        if result.returncode:
            return result.returncode
    print(f"Analyze with: python3 {Path(__file__).with_name('analyze_vulkan_matrix.py')} {output}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
