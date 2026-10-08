"""Launch one actual Vulkan replay from frozen runtime, replay and measurement scripts."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

from frozen_benchmark_tooling import freeze_tooling, frozen_environment
from map_pan_comparison import PROJECT
from pinned_interaction_comparison import JAVA, REPLAY


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--jar', type=Path, default=PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar')
    parser.add_argument('--replay', type=Path, default=PROJECT / REPLAY)
    parser.add_argument('--extra', nargs=argparse.REMAINDER, default=[])
    args = parser.parse_args()
    root = PROJECT / 'build/rwx-benchmark' / args.tag
    root.mkdir(parents=True, exist_ok=False)
    tooling, digest = freeze_tooling(PROJECT, root)
    runtime, replay = root / 'runtime.jar', root / 'frozen.replay'
    shutil.copy2(args.jar, runtime)
    shutil.copy2(args.replay, replay)
    return subprocess.call([sys.executable, str(tooling / 'map_pan_replay.py'), '--jar', str(runtime),
        '--replay', str(replay), '--java', JAVA, '--output', str(root / 'run'), '--repetitions', '1',
        '--window-width', '1280', '--window-height', '720', *args.extra], cwd=PROJECT,
        env=frozen_environment(PROJECT, tooling, digest, os.environ))


if __name__ == '__main__':
    raise SystemExit(main())
