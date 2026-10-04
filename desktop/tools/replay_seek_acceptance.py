"""Run real replay seek/fog/simulation acceptance without a GPU benchmark load."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from map_pan_comparison import PROJECT, REPLAY_ALIAS, copy_replay_alias, isolated_seed, run_environment
from map_pan_builtin_comparison import launch
from vulkan_native_matrix import java_command


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--replay', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    out = a.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    sandbox = out / 'sandbox'; sandbox.mkdir()
    jar = out / 'runtime.jar'; shutil.copy2(a.jar, jar)
    copy_replay_alias(a.replay, sandbox, hashlib.sha256(a.replay.read_bytes()).hexdigest())
    (sandbox / 'preferences.toml').write_text(isolated_seed(), encoding='utf-8')
    cmd = java_command(a.java, jar, PROJECT / 'desktop/build/slick-natives')
    cmd = [x for x in cmd if x not in ('--screen=battleroom', '--auto-start-battleroom')]
    cmd[cmd.index('io.github.rwx.KoolDesktopMain')] = 'io.github.rwx.HeadlessMain'
    cmd += [f'--replay={REPLAY_ALIAS}']
    cmd[1:1] = [f'-Dlaunch.dir={sandbox}', f'-Drwx.assetsDir={PROJECT / "assets"}', '-Xms512m', '-Xmx2g']
    env = run_environment(out, sandbox, 'seek')
    env = {k: v for k, v in env.items() if not k.startswith('RWX_')}
    env['RWX_REPLAY_SEEK_OUTPUT'] = str(out / 'acceptance.json')
    launch(cmd, env, out, 'seek', 600)
    print(json.dumps(json.loads((out / 'acceptance.json').read_text()), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
