#!/usr/bin/env python3
"""Two actual v7 LIVE GUIs on isolated container loopback. No macOS display code.

Stage one verifies displayed stock production/cancellation and independent recordings;
combat PULSE, remote HISTORY GUI, offline replay GUI and menu clicks are separate gates.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import secrets
import signal
import socket
import subprocess
import sys
import time

import gui_common as guards

JAR = Path('/opt/rwxx-v7-gui-live/game.jar')
WORK = Path('/tmp/rwxx-v7-gui-live')
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


def validate_canvas(rows, observed, final=True):
    require(len(rows) >= 2, 'Missing repeated canvas probes')
    ready = []
    for row in rows:
        require(isinstance(row, dict) and row.get('noGameEngine') is True and row.get('readOnly') is False
                and row.get('connected') is True and row.get('backend') == 'OpenGL', 'Client simulation, connection or backend differs')
        require(row.get('viewCount') == 1 and row.get('activePane') == 0, 'Expected one live pane')
        wire = row.get('wire')
        fields = ('recvBytes', 'writtenBytes', 'mapInitializations', 'mapCacheHits', 'transactions',
                  'restores', 'stateChecks', 'stateAcks', 'stateMismatches', 'stateDigestNanos')
        require(isinstance(wire, dict) and wire.get('protocolVersion') == 7
                and all(type(wire.get(k)) is int and wire[k] >= 0 for k in fields), 'Invalid real v7 counters')
        require(wire['restores'] == wire['stateMismatches'] == 0, 'Digest mismatch or restore occurred')
        probe = row.get('actionProbe')
        require(isinstance(probe, dict) and probe.get('enabled') is True
                and all(type(probe.get(k)) is bool for k in ('completed', 'panelSeen', 'queueObserved', 'cancelObserved'))
                and type(probe.get('uiActionCount')) is int and probe['uiActionCount'] >= 0, 'Missing real UI action probe')
        require(all(type(row.get(k)) is int for k in ('tick', 'viewSequence', 'submittedFrames', 'presentedRevision')), 'Invalid rendering counters')
        if row['viewSequence'] > 0:
            visual = public_visual(row)
            require(1 <= row.get('renderedGroundCells', 0) <= VISUAL['groundCells']
                    and 0 <= row.get('renderedDecorationCells', -1) <= VISUAL['decorationCells']
                    and 1 <= row.get('terrainTextures', 0) <= 11 and row.get('renderedViewCount') == 1,
                    'No actual terrain submissions')
            panes = row.get('viewports')
            require(isinstance(panes, list) and len(panes) == 1, 'Missing pane evidence')
            pane = panes[0]
            require(pane.get('paneId') == 0 and pane.get('readOnly') is False
                    and pane.get('viewSequence') == row['viewSequence'] and pane.get('tick') == row['tick']
                    and pane.get('x') == pane.get('y') == 0 and pane.get('width') == row.get('width')
                    and pane.get('height') == row.get('height'), 'Live pane and canvas disagree')
            if ready: require(public_visual(ready[0]) == visual, 'Static public visuals changed')
            ready.append(row)
    require(len(ready) >= 2, 'No advancing authorized view')
    for field in ('tick', 'viewSequence', 'submittedFrames', 'presentedRevision'):
        values = [r[field] for r in ready]
        minimum = -1 if field == 'presentedRevision' else 0
        require(values == sorted(values) and values[-1] > values[0] >= minimum and values[-1] > 0,
                'No real progress: ' + field)
    for field in ('recvBytes', 'writtenBytes', 'transactions', 'stateChecks', 'stateAcks'):
        values = [r['wire'][field] for r in rows]
        require(values == sorted(values), 'Wire counter regressed: ' + field)
    last = ready[-1]; wire = last['wire']
    require(wire['mapInitializations'] == 1 and wire['mapCacheHits'] == 0, 'Expected one uncached map initialization')
    if final:
        action = last['actionProbe']
        require(all(action[k] is True for k in ('completed', 'panelSeen', 'queueObserved', 'cancelObserved'))
                and action['uiActionCount'] >= 3, 'Presented selection/production debit/queue/cancellation refund did not complete')
        require(len(observed) >= 2 and observed[-1]['wallSeconds'] - observed[0]['wallSeconds'] >= 5,
                'Insufficient independently observed wall duration')
        for field in ('stateChecks', 'stateAcks'):
            require(observed[-1][field] - observed[0][field] >= 5, 'Fewer than five actual wall-clock checks/ACKs')
    return {'probeCount': len(rows), 'first': ready[0], 'last': last, 'publicVisual': public_visual(last),
            'wallObservations': observed, 'actionMeaning': 'Existing UI probe verifies real credit debit, queued builder, cancellation and refund against presented server views',
            'combatPulseVerified': False, 'remoteHistoryStreamVerified': False}


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


def nonoverlapping(frames):
    require(len(frames) == 2, 'Expected two owned windows')
    a, b = frames
    require(a['X'] + a['WIDTH'] <= b['X'] or b['X'] + b['WIDTH'] <= a['X']
            or a['Y'] + a['HEIGHT'] <= b['Y'] or b['Y'] + b['HEIGHT'] <= a['Y'], 'Owned GUI windows overlap')


def capture_crop(path, own, all_frames):
    from PIL import ImageGrab, ImageStat, features
    nonoverlapping(all_frames)
    require(features.check_feature('xcb'), 'Pillow lacks XCB screenshot support')
    image = ImageGrab.grab(xdisplay=':99').convert('RGB')
    require(image.size == guards.SCREEN, 'X framebuffer extent differs')
    x, y, w, h = (own[k] for k in ('X', 'Y', 'WIDTH', 'HEIGHT'))
    crop = image.crop((x + 8, y + 32, x + w - 8, y + h - 8))
    colors = crop.getcolors(maxcolors=1_000_000)
    count = len(colors) if colors is not None else 1_000_001
    variance = sum(ImageStat.Stat(crop).var) / 3
    require(count >= 128 and variance >= 4, 'Owned client canvas pixels are blank')
    crop.save(path, format='PNG')
    return {'frame': own, 'crop': [x + 8, y + 32, w - 16, h - 40], 'pixels': list(crop.size),
            'rgbColors': count, 'channelVarianceMean': variance, 'sha256': guards.digest(path)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar-sha256', required=True)
    parser.add_argument('--seconds', type=int, default=35)
    parser.add_argument('--timeout-seconds', type=int, default=180)
    args = parser.parse_args()
    start = time.monotonic(); deadline = start + min(240, max(1, args.timeout_seconds))
    evidence = WORK / 'evidence'; assets = WORK / 'assets'
    children = []; logs = []; tokens = []; created = False
    report = {'scope': 'Two production v7 LIVE view-only Linux GUIs and one headless server on isolated loopback',
        'passed': False, 'jarSha256': args.jar_sha256, 'preflightPassed': False, 'children': [], 'clients': [],
        'tls': False, 'networkScope': 'Container-local loopback only, not WAN/TLS acceptance',
        'menuClicksVerified': False, 'macNativeCoverage': False, 'combatPulseVerified': False,
        'remoteHistoryStreamVerified': False, 'offlineReplayGuiVerified': False}
    env = {'PATH': os.environ.get('PATH', '/opt/java/openjdk/bin:/usr/bin:/bin'), 'LANG': 'C.UTF-8',
        'LC_ALL': 'C.UTF-8', 'DISPLAY': ':99', 'LIBGL_ALWAYS_SOFTWARE': '1', 'GALLIUM_DRIVER': 'llvmpipe',
        'LP_NUM_THREADS': '2', 'ALSOFT_DRIVERS': 'null'}

    def remain():
        left = deadline - time.monotonic(); require(left > 0, 'Live GUI absolute deadline exceeded'); return left

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

    def launch(role, argv, environment, pipe=False):
        require(all(not any(secret in arg for secret in tokens) for arg in argv), 'Credential value reached process arguments')
        log = (evidence / (role + '.log')).open('xb'); logs.append(log)
        def limits(): resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE, MAX_FILE))
        child = subprocess.Popen(argv, env=environment, cwd=WORK, stdin=subprocess.PIPE if pipe else subprocess.DEVNULL,
                                 stdout=log, stderr=subprocess.STDOUT, preexec_fn=limits)
        children.append((role, child))
        report['children'].append({'role': role, 'pid': child.pid, 'command': argv})
        return child

    def interrupted(_signal, _frame): raise RuntimeError('Live GUI worker interrupted')

    try:
        require(re.fullmatch('[0-9a-f]{64}', args.jar_sha256) and 25 <= args.seconds <= 60
                and max(120, args.seconds + 90) <= args.timeout_seconds <= 240, 'Invalid test settings')
        signal.signal(signal.SIGALRM, interrupted); signal.signal(signal.SIGTERM, interrupted); signal.signal(signal.SIGINT, interrupted)
        signal.alarm(args.timeout_seconds)
        require(sys.platform == 'linux' and os.getuid() == os.geteuid() == os.getgid() == 1000 and Path('/.dockerenv').is_file(),
                'Requires isolated nonroot Linux Docker')
        require(not Path('/dev/snd').exists() and not Path('/dev/dri').exists(), 'Unexpected host audio/GPU device')
        require(guards.digest(JAR) == args.jar_sha256, 'Frozen production JAR differs')
        os.umask(0o077); WORK.mkdir(exist_ok=False); evidence.mkdir(); created = True
        report['networkIsolation'] = guards.validate_network(
            json.loads(command(['ip', '-j', 'link', 'show'])), json.loads(command(['ip', '-j', 'address', 'show'])),
            json.loads(command(['ip', '-j', '-4', 'route', 'show', 'table', 'all'])),
            json.loads(command(['ip', '-j', '-6', 'route', 'show', 'table', 'all'])))
        report['preflightPassed'] = True
        report['assets'] = guards.extract_assets(JAR, assets)
        xvfb = launch('xvfb', ['Xvfb', ':99', '-screen', '0', '1920x1080x24', '-nolisten', 'tcp', '-noreset', '+extension', 'GLX'], env)
        for _ in range(60):
            require(xvfb.poll() is None, 'Owned Xvfb exited')
            if re.search(r'dimensions:\s+1920x1080 pixels', command(['xdpyinfo'], allowed=(0, 1))): break
            time.sleep(min(.1, remain()))
        else: raise RuntimeError('Container X display failed readiness')
        gl = command(['glxinfo', '-B'], timeout=10)
        require('llvmpipe' in gl.lower() and 'OpenGL core profile version string:' in gl, 'Expected actual software OpenGL')
        (evidence / 'glxinfo.txt').write_text(gl)
        report['graphics'] = {'backend': 'OpenGL', 'glxinfo': gl, 'mesaThreads': 2}
        credentials = WORK / 'credentials'; credentials.mkdir(mode=0o700)
        tokens = [secrets.token_urlsafe(32) for _ in range(2)]
        seats = credentials / 'seats.properties'; seats.write_text(''.join(f'{i}={token}\n' for i, token in enumerate(tokens)))
        for i, token in enumerate(tokens): (credentials / f'{i}.token').write_text(token)
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0)); port = reservation.getsockname()[1]
        state, server_env, java = role_environment('server')
        server = launch('server', java + ['-Djava.awt.headless=true', '-cp', str(JAR), 'io.github.rwx.AuthoritativeDesktopMain',
            '--authority-server', '--seats=' + str(seats), '--bind=127.0.0.1', '--port=' + str(port), '--owner-stdin=true',
            '--archive-dir=' + str(state / 'archives')], server_env, pipe=True)
        server_ready = time.monotonic() + 60
        while f'Timeline server listening on {port}; protocol 7' not in (evidence / 'server.log').read_text(errors='replace'):
            require(server.poll() is None and time.monotonic() < server_ready, 'Production server failed startup')
            time.sleep(min(.1, remain()))
        clients = []; observations = [[], []]; seen = [0, 0]; windows = [None, None]
        for i in range(2):
            role = f'client-{i}'; state, client_env, java = role_environment(role)
            title = f'RWXX isolated v7 LIVE seat {i}'
            client_env.update(RWX_WINDOW_TITLE=title, RWX_WINDOW_WIDTH='800', RWX_WINDOW_HEIGHT='600',
                RWX_DESKTOP_TARGET_FPS='30', RWX_DEBUG_AUTO_EXIT_SECONDS=str(args.seconds),
                RWX_AUTHORITY_ACTION_PROBE='1', RWX_AUTHORITY_CANVAS_PROBE=str(evidence / (role + '.ndjson')))
            child = launch(role, java + ['-Djava.awt.headless=false', '-Drwx.kool.backend=opengl', '-jar', str(JAR),
                '--authority-client', '--connect=127.0.0.1', '--port=' + str(port), '--token-file=' + str(credentials / f'{i}.token'),
                f'--name=LinuxSeat{i}', '--record-view=' + str(evidence / (role + '.rwxr')), '--views=1', '--map-cache-limit=0'], client_env)
            clients.append((child, title, state))
        report['primaryJvmPids'] = [server.pid] + [c[0].pid for c in clients]
        require(len(set(report['primaryJvmPids'])) == 3, 'JVMs are not independent processes')
        capture_times = []
        while any(child.poll() is None for child, _, _ in clients):
            remain(); require(server.poll() is None and xvfb.poll() is None, 'Server/display exited while clients active')
            rows_by_client = []
            for i, (child, title, _) in enumerate(clients):
                require(child.poll() in (None, 0), f'GUI seat {i} failed')
                rows = read_probe(evidence / f'client-{i}.ndjson'); rows_by_client.append(rows)
                if rows and rows[-1]['submittedFrames'] != seen[i]:
                    last = rows[-1]; seen[i] = last['submittedFrames']
                    observations[i].append({'wallSeconds': time.monotonic() - start, 'submittedFrames': seen[i],
                        'tick': last['tick'], 'stateChecks': last['wire']['stateChecks'], 'stateAcks': last['wire']['stateAcks']})
                if windows[i] is None and child.poll() is None:
                    ids = command(['xdotool', 'search', '--onlyvisible', '--pid', str(child.pid), '--name', '^' + title + '$'], allowed=(0, 1)).split()
                    if ids:
                        require(len(ids) == 1 and ids[0].isdigit(), 'Unexpected owned window count')
                        windows[i] = ids[0]; command(['xdotool', 'windowmove', ids[0], str(40 + i * 960), '100'])
            # Actual completed action + repeated check evidence gates screenshots, not elapsed startup alone.
            ready = all(rows and rows[-1].get('actionProbe', {}).get('completed') is True
                        and rows[-1].get('wire', {}).get('stateAcks', 0) >= 5 for rows in rows_by_client)
            now = time.monotonic()
            if ready and all(windows) and len(capture_times) < 2 and (not capture_times or now - capture_times[-1] >= 3):
                require(all(child.poll() is None for child, _, _ in clients), 'GUI closed before paired screenshots')
                frames = [guards.parse_geometry(command(['xdotool', 'getwindowgeometry', '--shell', window])) for window in windows]
                nonoverlapping(frames)
                for i, frame in enumerate(frames):
                    image = capture_crop(evidence / f'client-{i}-frame-{len(capture_times)}.png', frame, frames)
                    report.setdefault('screenshots', []).append(dict(image, seat=i, wallSeconds=now - start))
                capture_times.append(now)
            time.sleep(min(.1, remain()))
        require(len(capture_times) == 2, 'Two paired actual screenshot batches were not reached')
        for i, (child, _, state) in enumerate(clients):
            require(child.returncode == 0, 'Client did not close normally')
            canvas = validate_canvas(read_probe(evidence / f'client-{i}.ndjson', final=True), observations[i])
            import tomllib
            prefs = tomllib.loads((state / 'preferences.toml').read_text()).get('preferences', {})
            require(prefs.get('enableSounds') == 'false' and all(float(prefs.get(k, 'nan')) == 0 for k in
                    ('masterVolume', 'musicVolume', 'gameVolume', 'interfaceVolume')), 'Client sound preferences changed')
            recording = evidence / f'client-{i}.rwxr'
            require(recording.is_file() and 0 < recording.stat().st_size <= MAX_FILE, 'Player recording missing/oversized')
            inspection_file = evidence / f'client-{i}.inspection.json'
            _, inspector_env, java = role_environment(f'inspect-{i}')
            inspector = launch(f'inspect-{i}', java + ['-Djava.awt.headless=true', '-cp', str(JAR),
                'io.github.rwx.authoritative.TimelineReplayInspectMain', '--input=' + str(recording), '--output=' + str(inspection_file)], inspector_env)
            require(inspector.wait(timeout=min(25, remain())) == 0, 'Formal recording inspector failed')
            inspection = validate_inspection(json.loads(inspection_file.read_text()), i, canvas['publicVisual'])
            report['clients'].append({'seat': i, 'pid': child.pid, 'exitCode': child.returncode,
                'canvas': canvas, 'recordingSha256': guards.digest(recording), 'recordingInspection': inspection,
                'knownResourceDiagnostics': validate_resources(evidence / f'client-{i}.log')})
        require(report['clients'][0]['canvas']['publicVisual'] == report['clients'][1]['canvas']['publicVisual'], 'Seats disagree on public map')
        require(server.poll() is None, 'Server exited before its owner EOF')
        server.stdin.close()
        require(server.wait(timeout=min(20, remain())) == 0, 'Server owner EOF shutdown failed')
        report['serverOwnerEofExitCode'] = server.returncode
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)[:2048]
    finally:
        signal.alarm(0)
        report['cleanup'] = []
        for role, child in reversed(children):
            terminated = False; killed = False
            try:
                if child.stdin is not None and not child.stdin.closed: child.stdin.close()
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
            require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_FILE, 'Invalid bounded evidence file')
            data = path.read_bytes()
            if any(token.encode() in data for token in tokens):
                report['passed'] = False; report['credentialLeakDetected'] = True
                for token in tokens: data = data.replace(token.encode(), b'[REDACTED-CREDENTIAL]')
            total += len(data); require(total <= MAX_EXPORT, 'Evidence exceeds total export budget')
            artifacts.append({'file': path.name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                              'base64': base64.b64encode(data).decode()})
    except Exception as error:
        report['passed'] = False; report['evidenceFailure'] = str(error)[:1024]
    report['artifacts'] = artifacts
    print(json.dumps(report, separators=(',', ':')), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__': sys.exit(main())
