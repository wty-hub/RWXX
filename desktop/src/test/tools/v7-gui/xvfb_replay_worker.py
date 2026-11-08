#!/usr/bin/env python3
"""Offline four-pane GUI with actual keyboard controls; no server or network client."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import signal
import subprocess
import sys
import time
import gui_common as guards

ROOT = Path('/opt/rwxx-v7-gui-replay')
JAR = ROOT / 'game.jar'
WORK = Path('/tmp/rwxx-v7-gui-replay')
MAX_FILE = 8 * 1024 * 1024
MAX_EXPORT = 40 * 1024 * 1024
require = guards.require
VISUAL = {'catalogVersion': 1, 'groundCells': 12100, 'decorationCells': 53, 'resourcePools': 4}
HASH_FIELDS = ('groundSha256', 'decorationSha256')

def read_probe(path, final=False):
    if not path.exists(): return []
    data = path.read_bytes(); require(len(data) <= MAX_FILE, 'Canvas probe exceeds budget')
    if final: require(not data or data.endswith(b'\n'), 'Canvas probe ends with an incomplete record')
    lines = data.split(b'\n')[:-1]
    require(len(lines) <= 6000, 'Too many canvas records')
    return [json.loads(line) for line in lines if line]


def public_visual(row):
    require(all(type(row.get(k)) is int and row[k] == v for k, v in VISUAL.items()), 'Unexpected public Small Island visual')
    require(all(isinstance(row.get(k), str) and re.fullmatch('[0-9a-f]{64}', row[k]) for k in HASH_FIELDS), 'Invalid public visual hash')
    return {key: row[key] for key in (*VISUAL, *HASH_FIELDS)}


def validate_inspection(value, seat, visual):
    require(value.get('passed') is True and value.get('protocolVersion') == 7 and value.get('containerVersion') == 3
            and value.get('teamId') == seat and value.get('noGameEngine') is True and value.get('readOnly') is True,
            'Player recording failed strict seat/version/engine/read-only inspection')
    require(value.get('completeIndex') is True and value.get('staticVisualConsistent') is True
            and type(value.get('slices')) is int and value['slices'] > 0
            and value['slices'] <= value.get('samples', 0) <= 2 * value['slices']
            and value.get('samples') == value.get('views') == value.get('visualViews')
            and value.get('finalTick', -1) > value.get('firstTick', -1) >= 0,
            'Recording lacks complete independent slice reconstruction')
    require(value.get('joinedAt', -1) <= value.get('historyFrom', -1) <= value.get('from', -1) < value.get('until', -1),
            'Recording exceeds its authorized access window')
    require(type(value.get('gapCount')) is int and value['gapCount'] >= 0
            and value.get('hasGaps') is (value['gapCount'] > 0)
            and type(value.get('terminalCovered')) is bool
            and value.get('partial') is (value['hasGaps'] or not value['terminalCovered']), 'Incorrect archive completeness claim')
    require(public_visual(value) == visual, 'Recorded public map differs from presented map')
    return value


def validate_resources(path):
    text = path.read_text(errors='replace')
    for raw in text.splitlines():
        line = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', raw)
        require(re.search(r'\bFreeing\s+\d+\s+leaked\s+images\b', line, re.I) is None,
                'Client reports leaked native images')
        require('Authority UI font loading' not in line and 'Authority action probe failed:' not in line,
                'Client reports UI font/action probe failure')
    return {'passed': True, 'sha256': guards.digest(path),
            'scope': 'Known native image/font/action diagnostics only; not a general GPU or heap audit'}


def complete(row):
    return row.get('renderedViewCount') == 4 and row.get('presentedRevision', -1) > 0


def validate_replay(rows, visual, final=False):
    require(rows, 'Missing actual replay canvas probes')
    completed = []
    for row in rows:
        require(row.get('noGameEngine') is True and row.get('readOnly') is True and row.get('connected') is True
                and row.get('wire') is None and row.get('backend') == 'OpenGL', 'Replay created network/engine or writable state')
        require(row.get('viewCount') == 4 and row.get('activePane') in range(4), 'Expected four replay panes')
        action = row.get('actionProbe')
        require(isinstance(action, dict) and all(action.get(k) is False for k in
                ('enabled', 'completed', 'panelSeen', 'queueObserved', 'cancelObserved'))
                and type(action.get('uiActionCount')) is int and action['uiActionCount'] == 0,
                'Read-only replay enabled or sent action-probe input')
        width = row.get('width'); height = row.get('height')
        require(type(width) is int and type(height) is int and width > 0 and height > 0, 'Invalid replay viewport')
        panes = row.get('viewports')
        require(isinstance(panes, list) and len(panes) == 4, 'Missing actual pane records')
        rectangles = [(0, 0, width // 2, height // 2), (width // 2, 0, width - width // 2, height // 2),
                      (0, height // 2, width // 2, height - height // 2),
                      (width // 2, height // 2, width - width // 2, height - height // 2)]
        rendered = 0
        for index, (pane, rect) in enumerate(zip(panes, rectangles)):
            require(pane.get('paneId') == index and pane.get('readOnly') is True
                    and tuple(pane.get(k) for k in ('x', 'y', 'width', 'height')) == rect,
                    'Replay pane identity/read-only/rectangle differs')
            require(type(pane.get('tick')) is int and pane['tick'] >= 0
                    and type(pane.get('viewSequence')) is int and pane['viewSequence'] >= -1
                    and type(pane.get('renderedGroundCells')) is int
                    and 0 <= pane['renderedGroundCells'] <= VISUAL['groundCells'], 'Invalid replay pane counters')
            if pane['renderedGroundCells'] > 0:
                require(pane['viewSequence'] > 0, 'Unavailable pane claims terrain')
                rendered += 1
        require(row.get('renderedViewCount') == rendered, 'Reported pane count differs from actual terrain submissions')
        active = panes[row['activePane']]
        require(row.get('tick') == active['tick'] and row.get('viewSequence') == active['viewSequence'], 'Active pane counters differ')
        if row['viewSequence'] > 0:
            require(public_visual(row) == visual and 1 <= row.get('terrainTextures', 0) <= 11, 'Replay visual differs from source')
        if complete(row): completed.append(row)
    for key in ('submittedFrames', 'presentedRevision'):
        values = [r.get(key) for r in rows]
        require(all(type(v) is int for v in values) and values == sorted(values), 'Replay render counter regressed')
        if final: require(values[-1] > values[0], 'No actual replay rendering progress')
    if final: require(len(completed) >= 2, 'Fewer than two four-pane presentations')
    return {'probeCount': len(rows), 'completeFourPaneProbes': len(completed),
            'first': rows[0], 'last': rows[-1], 'visual': visual, 'readOnly': True,
            'networkRequested': False, 'actionProbeRequestedButDisabled': True}


def tick(row, pane=0):
    return row['viewports'][pane]['tick']


def phase_matches(kind, samples, base_tick=None, pane=0, held_tick=None):
    """Validate actual fresh probe transitions, never the key-sender exit status."""
    if len(samples) < (3 if kind == 'pause' else 2): return False
    tail = samples[-3:] if kind == 'pause' else samples[-2:]
    if not all(complete(row) and row['activePane'] == pane for row in tail): return False
    values = [tick(row, pane) for row in tail]
    if held_tick is not None and any(tick(row, 0) != held_tick for row in tail): return False
    if kind == 'pause': return len(set(values)) == 1 and tail[-1]['submittedFrames'] - tail[0]['submittedFrames'] >= 60
    if kind == 'forward': return values[-1] - values[0] >= 30
    if kind == 'reverse': return values[0] - values[-1] >= 30
    if kind == 'seek': return base_tick is not None and all(abs(value - base_tick - 600) <= 1 for value in values)
    if kind == 'active': return True
    raise ValueError('Unknown phase kind')


def tcp_inventory():
    result = {}
    for name in ('tcp', 'tcp6'):
        path = Path('/proc/net') / name
        require(path.is_file(), 'Cannot inspect offline TCP sockets: ' + name)
        rows = path.read_text().splitlines()
        actual = [line for line in rows[1:] if line.strip()]
        require(not actual, 'Offline replay has a TCP socket: ' + name)
        result[name] = {'socketRows': len(actual)}
    return result


def capture(path, geometry):
    from PIL import ImageGrab, ImageStat, features
    require(features.check_feature('xcb'), 'Pillow lacks XCB screenshot support')
    image = ImageGrab.grab(xdisplay=':99').convert('RGB')
    require(image.size == guards.SCREEN, 'Unexpected framebuffer extent')
    x, y, width, height = (geometry[k] for k in ('X', 'Y', 'WIDTH', 'HEIGHT'))
    crop = image.crop((x + 8, y + 32, x + width - 8, y + height - 8))
    colors = crop.getcolors(maxcolors=1_000_000)
    count = len(colors) if colors is not None else 1_000_001
    variance = sum(ImageStat.Stat(crop).var) / 3
    require(count >= 128 and variance >= 4, 'Replay window pixels are blank')
    crop.save(path, format='PNG')
    return {'geometry': geometry, 'crop': [x + 8, y + 32, width - 16, height - 40],
            'colors': count, 'variance': variance, 'sha256': guards.digest(path)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar-sha256', required=True)
    for index in range(2): parser.add_argument('--recording-%d-sha256' % index, required=True)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--timeout-seconds', type=int, default=240)
    args = parser.parse_args()
    hashes = [getattr(args, 'recording_%d_sha256' % i) for i in range(2)]
    start = time.monotonic(); deadline = start + min(300, max(1, args.timeout_seconds))
    evidence = WORK / 'evidence'; assets = WORK / 'assets'
    children = []; logs = []; created = False
    report = {'scope': 'Two source archives, sequential offline four-pane GUIs with real pause/seek/reverse/forward inputs',
        'passed': False, 'jarSha256': args.jar_sha256, 'recordingSha256': hashes, 'preflightPassed': False,
        'children': [], 'replays': [], 'serverStarted': False, 'networkClientStarted': False,
        'remoteHistoryStreamVerified': False, 'combatPulseVerified': False, 'menuClicksVerified': False,
        'macNativeCoverage': False}
    env = {'PATH': os.environ.get('PATH', '/opt/java/openjdk/bin:/usr/bin:/bin'), 'LANG': 'C.UTF-8',
        'LC_ALL': 'C.UTF-8', 'DISPLAY': ':99', 'LIBGL_ALWAYS_SOFTWARE': '1', 'GALLIUM_DRIVER': 'llvmpipe',
        'LP_NUM_THREADS': '2', 'ALSOFT_DRIVERS': 'null'}

    def remain():
        left = deadline - time.monotonic(); require(left > 0, 'Replay absolute deadline exceeded'); return left

    def command(argv, timeout=5, allowed=(0,)):
        value = subprocess.run(argv, env=env, cwd=WORK, capture_output=True, timeout=min(timeout, remain()), check=False)
        require(len(value.stdout) + len(value.stderr) <= MAX_FILE and value.returncode in allowed, 'Owned helper failed: ' + argv[0])
        return value.stdout.decode(errors='replace')

    def role_environment(role):
        state = WORK / role; state.mkdir(); (state / 'home').mkdir(); (state / 'runtime').mkdir(mode=0o700)
        (state / 'natives').mkdir(); (state / 'preferences.toml').write_text(guards.PREFS)
        environment = dict(env, HOME=str(state / 'home'), XDG_DATA_HOME=str(state / 'home/data'),
            XDG_CACHE_HOME=str(state / 'home/cache'), XDG_CONFIG_HOME=str(state / 'home/config'), XDG_RUNTIME_DIR=str(state / 'runtime'))
        java = ['java', '-Xmx768m', '-Dlaunch.dir=' + str(state), '-Duser.home=' + str(state / 'home'),
            '-Djava.io.tmpdir=' + str(state), '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + str(state / 'natives'),
            '-Drwx.assetsDir=' + str(assets), '-Drwx.log.dir=' + str(state / 'logs')]
        return state, environment, java

    def launch(role, argv, environment):
        require('--authority-client' not in argv and '--authority-server' not in argv and '--authority-host' not in argv,
                'Offline test refuses a network/server role')
        log = (evidence / (role + '.log')).open('xb'); logs.append(log)
        def limits(): resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE, MAX_FILE))
        child = subprocess.Popen(argv, env=environment, cwd=WORK, stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, preexec_fn=limits)
        children.append((role, child)); report['children'].append({'role': role, 'pid': child.pid, 'command': argv})
        return child

    def interrupted(_signal, _frame): raise RuntimeError('Replay worker interrupted')

    try:
        require(all(re.fullmatch('[0-9a-f]{64}', h) for h in [args.jar_sha256, *hashes]) and 60 <= args.seconds <= 90
                and 2 * args.seconds + 80 <= args.timeout_seconds <= 300, 'Invalid replay settings')
        signal.signal(signal.SIGALRM, interrupted); signal.signal(signal.SIGTERM, interrupted); signal.signal(signal.SIGINT, interrupted)
        signal.alarm(args.timeout_seconds)
        require(sys.platform == 'linux' and os.getuid() == os.geteuid() == os.getgid() == 1000 and Path('/.dockerenv').is_file(),
                'Requires isolated nonroot Linux Docker')
        require(not Path('/dev/snd').exists() and not Path('/dev/dri').exists(), 'Unexpected host audio/GPU device')
        require(guards.digest(JAR) == args.jar_sha256, 'Frozen production JAR differs')
        for i, expected in enumerate(hashes): require(guards.digest(ROOT / f'seat-{i}.rwxr') == expected, 'Source recording bytes differ')
        os.umask(0o077); WORK.mkdir(exist_ok=False); evidence.mkdir(); created = True
        report['networkIsolation'] = guards.validate_network(
            json.loads(command(['ip', '-j', 'link', 'show'])), json.loads(command(['ip', '-j', 'address', 'show'])),
            json.loads(command(['ip', '-j', '-4', 'route', 'show', 'table', 'all'])),
            json.loads(command(['ip', '-j', '-6', 'route', 'show', 'table', 'all'])))
        report['tcpBefore'] = tcp_inventory(); report['preflightPassed'] = True
        report['assets'] = guards.extract_assets(JAR, assets)
        inspections = []
        for i in range(2):
            _, inspector_env, java = role_environment(f'inspect-{i}')
            output = evidence / f'source-{i}.inspection.json'
            child = launch(f'inspect-{i}', java + ['-Djava.awt.headless=true', '-cp', str(JAR),
                'io.github.rwx.authoritative.TimelineReplayInspectMain', '--input=' + str(ROOT / f'seat-{i}.rwxr'), '--output=' + str(output)], inspector_env)
            require(child.wait(timeout=min(25, remain())) == 0, 'Formal source archive inspection failed')
            value = json.loads(output.read_text())
            inspections.append(validate_inspection(value, i, public_visual(value)))
            require(value['gapCount'] == 0 and value['finalTick'] - value['firstTick'] > 1500, 'Source lacks continuous time for keyboard phases')
        xvfb = launch('xvfb', ['Xvfb', ':99', '-screen', '0', '1920x1080x24', '-nolisten', 'tcp', '-noreset', '+extension', 'GLX'], env)
        for _ in range(60):
            require(xvfb.poll() is None, 'Owned Xvfb exited')
            if re.search(r'dimensions:\s+1920x1080 pixels', command(['xdpyinfo'], allowed=(0, 1))): break
            time.sleep(min(.1, remain()))
        else: raise RuntimeError('Container display failed readiness')
        gl = command(['glxinfo', '-B'], timeout=10)
        require('llvmpipe' in gl.lower() and 'OpenGL core profile version string:' in gl, 'Expected actual llvmpipe context')
        (evidence / 'glxinfo.txt').write_text(gl)
        for i in range(2):
            role = f'replay-{i}'; state, replay_env, java = role_environment(role)
            title = f'RWXX isolated offline replay seat {i}'
            probe_path = evidence / (role + '.ndjson'); source = ROOT / f'seat-{i}.rwxr'
            replay_env.update(RWX_WINDOW_TITLE=title, RWX_WINDOW_WIDTH='1280', RWX_WINDOW_HEIGHT='800',
                RWX_DESKTOP_TARGET_FPS='30', RWX_DEBUG_AUTO_EXIT_SECONDS=str(args.seconds),
                RWX_AUTHORITY_ACTION_PROBE='1', RWX_AUTHORITY_CANVAS_PROBE=str(probe_path))
            child = launch(role, java + ['-Djava.awt.headless=false', '-Drwx.kool.backend=opengl', '-jar', str(JAR),
                '--authority-replay', '--replay-file=' + str(source), '--speed=1', '--views=4'], replay_env)
            local = {'seat': i, 'pid': child.pid, 'sourceSha256': hashes[i], 'inspection': inspections[i],
                     'phases': [], 'screenshots': [], 'inputEvents': []}
            report['replays'].append(local)  # retain failed phase/input evidence as well as successful runs
            visual = public_visual(inspections[i]); observed = {}; window = None
            window_deadline = time.monotonic() + 20
            while window is None:
                require(child.poll() is None and time.monotonic() < window_deadline, 'Replay window failed startup')
                ids = command(['xdotool', 'search', '--onlyvisible', '--pid', str(child.pid), '--name', '^' + title + '$'], allowed=(0, 1)).split()
                if ids:
                    require(len(ids) == 1 and ids[0].isdigit(), 'Unexpected replay window count'); window = ids[0]
                else: time.sleep(min(.1, remain()))
            command(['xdotool', 'windowmove', window, '100', '100'])
            geometry = guards.parse_geometry(command(['xdotool', 'getwindowgeometry', '--shell', window]))
            command(['xdotool', 'windowfocus', '--sync', window])
            command(['xdotool', 'mousemove', '--window', window, '200', '200'])
            command(['xdotool', 'click', '1'])  # focus the normal canvas/pane zero; read-only remains enforced

            def rows_now():
                require(child.poll() is None and xvfb.poll() is None, 'Replay/display exited during keyboard phases')
                rows = read_probe(probe_path)
                if rows: validate_replay(rows, visual)
                for row in rows: observed.setdefault(row['submittedFrames'], time.monotonic() - start)
                return rows

            def after_frame():
                rows = rows_now(); return rows[-1]['submittedFrames'] if rows else 0

            def key(keys):
                before = after_frame()
                local['inputEvents'].append({'keys': keys, 'afterSubmittedFrame': before, 'wallSeconds': time.monotonic() - start,
                                            'delivery': 'XTest keyboard into focused container canvas; verified by later real probes'})
                command(['xdotool', 'key', '--clearmodifiers', '--delay', '40', *keys])
                return before

            def wait_phase(name, kind, after, base_tick=None, pane=0, held_tick=None, timeout=12):
                until = time.monotonic() + timeout
                while True:
                    rows = rows_now(); samples = [r for r in rows if r['submittedFrames'] > after and complete(r)]
                    if phase_matches(kind, samples, base_tick, pane, held_tick):
                        tail = samples[-3:] if kind == 'pause' else samples[-2:]
                        if kind != 'pause' or observed[tail[-1]['submittedFrames']] - observed[tail[0]['submittedFrames']] >= 2:
                            local['phases'].append({'name': name, 'kind': kind, 'pane': pane, 'samples': tail,
                                'observedWallSeconds': [observed[r['submittedFrames']] for r in tail]})
                            return tail[-1]
                    require(time.monotonic() < until, 'Keyboard phase did not change real replay state: ' + name)
                    time.sleep(min(.1, remain()))

            def image(index):
                current = guards.parse_geometry(command(['xdotool', 'getwindowgeometry', '--shell', window]))
                local['screenshots'].append(capture(evidence / f'{role}-frame-{index}.png', current))

            initial = wait_phase('initial-forward', 'forward', 0)
            image(0)
            paused = wait_phase('pause-primary', 'pause', key(['F6']))
            sought = wait_phase('seek-forward-ten-seconds', 'seek', key(['bracketright']), tick(paused))
            image(1)
            reverse = wait_phase('reverse-primary', 'reverse', key(['F7']))
            forward = wait_phase('resume-forward-primary', 'forward', key(['F8']))
            held = wait_phase('pause-again', 'pause', key(['F6']))
            pane1 = wait_phase('activate-second-pane', 'active', key(['Tab']), pane=1, held_tick=tick(held))
            wait_phase('independent-second-pane-seek', 'seek', key(['bracketright']), tick(pane1, 1), pane=1, held_tick=tick(held))
            wait_phase('return-primary-with-independent-times', 'active', key(['Tab', 'Tab', 'Tab']), pane=0)
            image(2)
            local['tcpWhileReplayOpen'] = tcp_inventory()
            require(child.wait(timeout=remain()) == 0, 'Replay did not exit through normal timed-close')
            local['exitCode'] = child.returncode
            local['canvas'] = validate_replay(read_probe(probe_path, final=True), visual, final=True)
            local['knownResourceDiagnostics'] = validate_resources(evidence / (role + '.log'))
            require(guards.digest(source) == hashes[i], 'Playback changed source recording')
            local['sourceUnchanged'] = True; local['tcpAfterReplay'] = tcp_inventory()
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)[:2048]
    finally:
        signal.alarm(0); report['cleanup'] = []
        for role, child in reversed(children):
            terminated = False; killed = False
            try:
                if child.poll() is None:
                    child.terminate(); terminated = True
                    try: child.wait(timeout=3)
                    except subprocess.TimeoutExpired: child.kill(); killed = True; child.wait(timeout=3)
                report['cleanup'].append({'role': role, 'pid': child.pid, 'exitCode': child.returncode, 'terminated': terminated, 'killed': killed})
            except Exception as error:
                report['passed'] = False; report['cleanup'].append({'role': role, 'failure': str(error)[:512]})
        for log in logs: log.close()
    artifacts = []; total = 0
    try:
        for path in sorted(evidence.iterdir()) if created else []:
            require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_FILE, 'Invalid bounded replay evidence')
            data = path.read_bytes(); total += len(data); require(total <= MAX_EXPORT, 'Replay evidence exceeds export budget')
            artifacts.append({'file': path.name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                              'base64': base64.b64encode(data).decode()})
    except Exception as error:
        report['passed'] = False; report['evidenceFailure'] = str(error)[:1024]
    report['artifacts'] = artifacts
    print(json.dumps(report, separators=(',', ':')), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__': sys.exit(main())


