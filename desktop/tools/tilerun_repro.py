"""Repeat-run screenshot harness for the nondeterministic terrain row-merge defect.

The row merge (`RWX_MAP_TILE_RUN_TILE`) intermittently covers the map with large axis-aligned black
bands. The same binary and the same flags have rendered both cleanly and corrupted, so a single run
cannot tell "fixed" from "lucky": a defect that appears in some runs and not others needs several runs
per configuration before either outcome means anything.

This runs one configuration N times, captures the window at the same moment in each run, and reports
how many captures were written so the caller can compare them. It never drives input; it only launches
the existing replay harness and screenshots the game window by handle.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
CAPTURE = Path.home() / '.dsh' / 'skills' / 'screenshot-window' / 'scripts' / 'capture-window.ps1'
JAVA = r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot\bin\java.exe'
REPLAY = str(PROJECT / 'replays' / '2080年欧洲回归🌎结盟15p城夺4.0(15p) [v1.15] (4 Oct 2026 23.06.30).replay')


def run_capture(*extra: str) -> str:
    """Run the capture script and return its stdout.

    Decoded as UTF-8 with replacement: window titles on this machine are not all ASCII, and the default
    locale codec (GBK) fails on them, which silently yields empty stdout and looks exactly like "no window".
    """
    result = subprocess.run(
        ['pwsh', '-NoProfile', '-File', str(CAPTURE), *extra],
        capture_output=True, check=False,
    )
    return (result.stdout or b'').decode('utf-8', errors='replace')


def capture(out: Path) -> bool:
    """Screenshot the RWX window, or report that it was not there yet."""
    listing = run_capture('-List')
    handle = None
    for line in listing.splitlines():
        if 'RWX Game' in line:
            handle = line.split()[0]
            break
    if handle is None:
        return False
    result = run_capture('-Hwnd', handle, '-Out', str(out))
    if 'Reliable : True' not in result:
        print(f'    capture unreliable, discarding {out.name}')
        out.unlink(missing_ok=True)
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--runs', type=int, default=6)
    parser.add_argument('--capture-at', type=float, default=40.0,
                        help='Seconds after launch to capture; the replay is under way by then')
    parser.add_argument('--tile-run', action='store_true',
                        help='Enable RWX_MAP_TILE_RUN_TILE for this configuration')
    parser.add_argument('--no-gpu-cell-cache', action='store_true',
                        help='Drop --gpu-map-cell-cache, to test whether the race is in cell recording')
    args = parser.parse_args()

    out_dir = PROJECT / 'build' / 'rwx-benchmark' / f'repro-{args.tag}'
    out_dir.mkdir(parents=True, exist_ok=True)
    env_extra = {'RWX_MAP_TILE_RUN_TILE': '1'} if args.tile_run else {}

    captured = 0
    for run in range(1, args.runs + 1):
        run_dir = out_dir / f'run-{run}'
        shot = out_dir / f'{args.tag}-{run}.png'
        shot.unlink(missing_ok=True)
        command = [
            sys.executable, str(PROJECT / 'desktop/tools/map_pan_replay.py'),
            '--jar', str(PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar'),
            '--replay', REPLAY, '--java', JAVA, '--output', str(run_dir),
            '--camera-mode', 'pan-zoom', '--zoom-min', '0.35', '--zoom-max', '0.70',
            '--fog', 'on', '--window-width', '1280', '--window-height', '720',
            '--disable-native-bgra-upload', '--no-perf-window-log',
        ]
        if not args.no_gpu_cell_cache:
            command.append('--gpu-map-cell-cache')
        if args.tile_run:
            command.append('--tile-run')

        print(f'  run {run}/{args.runs}: launching')
        process = subprocess.Popen(command, env={**__import__('os').environ, **env_extra},
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + args.capture_at
        while time.monotonic() < deadline:
            time.sleep(1.0)
        if capture(shot):
            captured += 1
            print(f'    captured {shot.name}')
        else:
            print('    no window at capture time')
        process.wait()

    print(f'\n{args.tag}: {captured}/{args.runs} captures in {out_dir}')
    return 0 if captured else 1


if __name__ == '__main__':
    raise SystemExit(main())
