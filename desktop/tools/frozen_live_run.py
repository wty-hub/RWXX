"""Launch an ordinary local game from a frozen runtime, optional TMX and measurement scripts."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

from frozen_benchmark_tooling import freeze_tooling, frozen_environment
from map_pan_comparison import PROJECT
from pinned_interaction_comparison import JAVA


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--jar', type=Path, default=PROJECT / 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar')
    parser.add_argument('--map-file', type=Path)
    parser.add_argument('--extra', nargs=argparse.REMAINDER, default=[])
    args = parser.parse_args()
    root = PROJECT / 'build/rwx-benchmark' / args.tag
    root.mkdir(parents=True, exist_ok=False)
    tooling, digest = freeze_tooling(PROJECT, root)
    runtime = root / 'runtime.jar'
    shutil.copy2(args.jar, runtime)
    map_flags = []
    if args.map_file is not None:
        frozen_map = root / 'frozen-map.tmx'
        shutil.copy2(args.map_file, frozen_map)
        map_flags = ['--map-file', str(frozen_map)]
    return subprocess.call([sys.executable, str(tooling / 'map_pan_live.py'), '--jar', str(runtime),
        '--java', JAVA, '--output', str(root / 'run'), '--window-width', '1280', '--window-height', '720',
        *map_flags, *args.extra], cwd=PROJECT, env=frozen_environment(PROJECT, tooling, digest, os.environ))


if __name__ == '__main__':
    raise SystemExit(main())
