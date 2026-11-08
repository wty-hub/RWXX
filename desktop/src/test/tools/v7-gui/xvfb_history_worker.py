#!/usr/bin/env python3
"""One real LIVE+remote HISTORY GUI in an owned network-none Linux container."""
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

JAR = Path('/opt/rwxx-v7-gui-history/game.jar')
WORK = Path('/tmp/rwxx-v7-gui-history')
MAX_FILE = 8 * 1024 * 1024
MAX_EXPORT = 40 * 1024 * 1024
require = guards.require
VISUAL = {'catalogVersion': 1, 'groundCells': 12100, 'decorationCells': 53, 'resourcePools': 4}
HASH_FIELDS = ('groundSha256', 'decorationSha256')
SUBTICKS = 1024
TICKS_PER_SECOND = 60  # Explicit production server fixture; checked against the recorded sampled ticks.
SOURCE_FIELDS = ('sourceMode', 'sourceStreamId', 'sourceEpoch', 'sourceRevision', 'sourceControlEpoch',
                 'sampledTime', 'sourceObservedThrough', 'sourceRetainedFrom', 'sourceRetainedUntil', 'sourceSealedThrough')
WIRE_FIELDS = ('recvBytes', 'writtenBytes', 'mapInitializations', 'mapCacheHits', 'transactions',
               'restores', 'stateChecks', 'stateAcks', 'stateMismatches', 'stateDigestNanos')

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

def validate_source(pane):
    require(all(key in pane for key in (*SOURCE_FIELDS, 'completedHistory')), 'Missing same-render source provenance')
    mode = pane['sourceMode']; completion = pane['completedHistory']
    if mode is None:
        require(all(pane[key] is None for key in SOURCE_FIELDS) and completion is None
                and pane.get('renderedGroundCells') == 0 and pane.get('viewSequence', 1) <= 0,
                'Unavailable sample borrowed source metadata or rendered stale terrain')
        return
    require(mode in ('LIVE', 'HISTORY'), 'Unknown source mode')
    require(all(type(pane[key]) is int and 0 <= pane[key] <= 2 ** 63 - 1 for key in SOURCE_FIELDS[1:]),
            'Invalid exact source integer')
    require(all(pane[key] > 0 for key in ('sourceStreamId', 'sourceEpoch', 'sourceRevision')), 'Invalid source identity')
    lo, hi, seal, observed, sampled = (pane[key] for key in
        ('sourceRetainedFrom', 'sourceRetainedUntil', 'sourceSealedThrough', 'sourceObservedThrough', 'sampledTime'))
    require(lo <= seal <= observed and lo < hi and lo <= sampled < hi
            and pane.get('tick') == sampled // SUBTICKS, 'Source window/time disagrees with rendered pane')
    if mode == 'LIVE':
        require(pane['sourceControlEpoch'] > 0 and hi > observed and completion is None,
                'LIVE sample borrowed a history completion or lost its control identity')
    else:
        require(pane['sourceControlEpoch'] == 0 and hi <= seal and pane.get('readOnly') is True
                and hi - lo <= 120 * TICKS_PER_SECOND * SUBTICKS, 'HISTORY source is writable, unsealed or oversized')
    if completion is not None:
        require(mode == 'HISTORY' and isinstance(completion, dict)
                and set(completion) == {'streamId', 'epoch', 'revision', 'acceptedFrom', 'acceptedUntil', 'endReason'}
                and all(type(v) is int for v in completion.values()) and completion['endReason'] == 2,
                'HISTORY completion is missing a real successful end')
        require(all(completion[left] == pane[right] for left, right in
                (('streamId', 'sourceStreamId'), ('epoch', 'sourceEpoch'), ('revision', 'sourceRevision'),
                 ('acceptedFrom', 'sourceRetainedFrom'), ('acceptedUntil', 'sourceRetainedUntil'))),
                'Completion belongs to another render/stream/epoch/revision/window')


def complete(row):
    return row.get('renderedViewCount') == 4 and row.get('presentedRevision', -1) > 0


def input_counts(row):
    value = row.get('inputWrites')
    require(isinstance(value, dict) and set(value) == {'commandsWritten', 'selectionsWritten'}
            and all(type(v) is int and v >= 0 for v in value.values()), 'Missing actual successful C5/C6 writer counters')
    return value['commandsWritten'], value['selectionsWritten']


def validate_canvas(rows, observations=None, final=False):
    require(rows, 'Missing actual canvas evidence')
    visual = None
    for row in rows:
        require(isinstance(row, dict) and row.get('noGameEngine') is True and row.get('connected') is True
                and row.get('backend') == 'OpenGL', 'Unexpected engine/backend/connection')
        active = row.get('activePane')
        require(row.get('viewCount') == 4 and type(active) is int and active in range(4)
                and row.get('readOnly') is (active != 0), 'The primary LIVE pane or readonly panes changed role')
        width, height = row.get('width'), row.get('height')
        require(type(width) is int and type(height) is int and width > 0 and height > 0, 'Invalid canvas extent')
        panes = row.get('viewports')
        require(isinstance(panes, list) and len(panes) == 4, 'Missing actual four-pane records')
        rectangles = [(0, 0, width // 2, height // 2), (width // 2, 0, width - width // 2, height // 2),
                      (0, height // 2, width // 2, height - height // 2),
                      (width // 2, height // 2, width - width // 2, height - height // 2)]
        rendered = 0
        for index, (pane, rect) in enumerate(zip(panes, rectangles)):
            require(isinstance(pane, dict) and type(pane.get('paneId')) is int and pane['paneId'] == index
                    and pane.get('readOnly') is (index != 0)
                    and all(type(pane.get(k)) is int for k in ('x', 'y', 'width', 'height', 'tick', 'viewSequence', 'renderedGroundCells'))
                    and tuple(pane[k] for k in ('x', 'y', 'width', 'height')) == rect
                    and pane['tick'] >= 0 and pane['viewSequence'] >= -1
                    and 0 <= pane['renderedGroundCells'] <= VISUAL['groundCells'], 'Invalid pane partition/identity/read-only state')
            validate_source(pane)
            if index == 0: require(pane['sourceMode'] in (None, 'LIVE'), 'Primary pane stopped following LIVE')
            if pane['renderedGroundCells'] > 0:
                require(pane['viewSequence'] > 0 and pane['sourceMode'] is not None, 'Unavailable pane claimed a rendered world')
                rendered += 1
        require(type(row.get('renderedViewCount')) is int and row['renderedViewCount'] == rendered,
                'Actual rendered pane count differs')
        require(row.get('tick') == panes[active]['tick'] and row.get('viewSequence') == panes[active]['viewSequence'],
                'Active pane source and canvas disagree')
        require(all(type(row.get(k)) is int for k in ('submittedFrames', 'presentedRevision')), 'Invalid presentation counters')
        if row['viewSequence'] > 0:
            current = public_visual(row)
            require(visual is None or visual == current, 'Static public map changed')
            visual = current
            require(1 <= row.get('terrainTextures', 0) <= 11, 'Missing actual public terrain textures')
        wire = row.get('wire')
        require(isinstance(wire, dict) and wire.get('protocolVersion') == 7
                and all(type(wire.get(key)) is int and wire[key] >= 0 for key in WIRE_FIELDS), 'Invalid real wire counters')
        require(wire['restores'] == wire['stateMismatches'] == 0, 'State mismatch or restore occurred')
        input_counts(row)
        action = row.get('actionProbe')
        require(isinstance(action, dict) and action.get('enabled') is True
                and all(type(action.get(k)) is bool for k in ('completed', 'panelSeen', 'queueObserved', 'cancelObserved'))
                and type(action.get('uiActionCount')) is int and action['uiActionCount'] >= 0, 'Missing initial LIVE UI positive control')
    for field in ('submittedFrames', 'presentedRevision'):
        values = [r[field] for r in rows]
        require(values == sorted(values), 'Render counter regressed: ' + field)
    for field in WIRE_FIELDS:
        values = [r['wire'][field] for r in rows]
        require(values == sorted(values), 'Wire counter regressed: ' + field)
    for field in ('commandsWritten', 'selectionsWritten'):
        values = [r['inputWrites'][field] for r in rows]
        require(values == sorted(values), 'Input writer counter regressed')
    live = [r['viewports'][0] for r in rows if r['viewports'][0]['sourceMode'] == 'LIVE']
    for field in ('sampledTime', 'sourceObservedThrough', 'sourceRetainedFrom'):
        values = [p[field] for p in live]
        require(values == sorted(values), 'Primary LIVE source regressed: ' + field)
    if final:
        require(visual is not None and len(live) >= 2 and live[-1]['sampledTime'] > live[0]['sampledTime']
                and live[-1]['sourceRetainedFrom'] > live[0]['sourceRetainedFrom'], 'LIVE retention floor never advanced')
        last = rows[-1]; action = last['actionProbe']
        require(all(action[k] is True for k in ('completed', 'panelSeen', 'queueObserved', 'cancelObserved'))
                and action['uiActionCount'] >= 3 and input_counts(last)[0] >= 2 and input_counts(last)[1] >= 1,
                'Initial actual LIVE selection/production/cancel positive control did not complete')
        require(last['wire']['mapInitializations'] == 1 and last['wire']['mapCacheHits'] == 0
                and last['wire']['stateDigestNanos'] > 0, 'Expected uncached map and actual full digest work')
        require(observations and observations[-1]['wallSeconds'] - observations[0]['wallSeconds'] >= 5
                and all(observations[-1][k] - observations[0][k] >= 5 for k in ('stateChecks', 'stateAcks')),
                'Missing repeated actual wall-observed checks and ACKs')
    return {'probeCount': len(rows), 'completeFourPaneProbes': sum(complete(r) for r in rows),
            'first': rows[0], 'last': rows[-1], 'publicVisual': visual, 'wallObservations': observations or []}


def history_identity(row, pane):
    value = row['viewports'][pane]
    return tuple(value[k] for k in ('sourceStreamId', 'sourceEpoch', 'sourceRevision', 'sourceRetainedFrom', 'sourceRetainedUntil'))


def history_ready(row, pane, older_than_live=False):
    if not complete(row): return False
    value, live = row['viewports'][pane], row['viewports'][0]
    if value.get('sourceMode') != 'HISTORY' or live.get('sourceMode') != 'LIVE' or value.get('completedHistory') is None: return False
    validate_source(value)
    if value['sourceStreamId'] == live['sourceStreamId']: return False
    return not older_than_live or value['sampledTime'] < live['sourceRetainedFrom']


def phase_matches(kind, samples, pane, identity=None, base_time=None, held=None):
    count = 3 if kind in ('pause', 'quiet') else 2
    if len(samples) < count: return False
    tail = samples[-count:]
    if not all(complete(r) and r['activePane'] == pane and history_ready(r, pane) for r in tail): return False
    if identity is not None and any(history_identity(r, pane) != identity for r in tail): return False
    if held and any(r['viewports'][p]['sampledTime'] != t for r in tail for p, t in held.items()): return False
    if tail[-1]['viewports'][0]['sampledTime'] <= tail[0]['viewports'][0]['sampledTime']: return False
    values = [r['viewports'][pane]['sampledTime'] for r in tail]
    if kind == 'pause': return len(set(values)) == 1 and tail[-1]['submittedFrames'] - tail[0]['submittedFrames'] >= 60
    if kind == 'quiet': return len({input_counts(r) for r in tail}) == 1 and tail[-1]['submittedFrames'] - tail[0]['submittedFrames'] >= 60
    if kind == 'forward': return values[-1] - values[0] >= 30 * SUBTICKS
    if kind == 'reverse': return values[0] - values[-1] >= 30 * SUBTICKS
    if kind == 'seek': return base_time is not None and all(v == base_time + 10 * TICKS_PER_SECOND * SUBTICKS for v in values)
    if kind == 'active': return True
    raise ValueError('Unknown history phase')


def validate_readonly_interval(before, samples, pane, identity):
    require(len(samples) >= 3 and samples[0]['submittedFrames'] > before['submittedFrames']
            and samples[-1]['submittedFrames'] - samples[0]['submittedFrames'] >= 60, 'Read-only input observation is not fresh/sustained')
    counts = input_counts(before)
    require(counts[0] >= 2 and counts[1] >= 1, 'Writer counters have no prior live positive control')
    require(all(r['activePane'] == pane and r['readOnly'] is True and history_ready(r, pane)
                and history_identity(r, pane) == identity and input_counts(r) == counts for r in samples),
            'Readonly input changed C5/C6 writer totals, source identity or active history role')
    require(samples[-1]['viewports'][0]['sampledTime'] > before['viewports'][0]['sampledTime']
            and all(samples[-1]['wire'][key] > before['wire'][key] for key in ('stateChecks', 'stateAcks')),
            'LIVE world/check/ACK did not continue during read-only input')
    return {'passed': True, 'before': before, 'after': samples[-1], 'samples': samples,
            'counterMeaning': 'Actual successful C5 Command and C6 Select writes, not enqueue or gameplay acceptance',
            'commandsWrittenDelta': 0, 'selectionsWrittenDelta': 0,
            'stateChecksDelta': samples[-1]['wire']['stateChecks'] - before['wire']['stateChecks'],
            'stateAcksDelta': samples[-1]['wire']['stateAcks'] - before['wire']['stateAcks']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar-sha256', required=True)
    parser.add_argument('--seconds', type=int, default=100)
    parser.add_argument('--timeout-seconds', type=int, default=240)
    args = parser.parse_args()
    start = time.monotonic(); deadline = start + min(240, max(1, args.timeout_seconds))
    evidence = WORK / 'evidence'; assets = WORK / 'assets'
    children = []; logs = []; tokens = []; created = False
    report = {'scope': 'Production server and one four-pane v7 GUI: LIVE plus completed remote HISTORY, XTest controls and readonly writer counters',
        'passed': False, 'jarSha256': args.jar_sha256, 'preflightPassed': False, 'children': [], 'phases': [], 'inputEvents': [], 'screenshots': [],
        'tls': False, 'networkScope': 'Container-local loopback only, not WAN/TLS acceptance',
        'menuClicksVerified': False, 'macNativeCoverage': False, 'combatPulseVerified': False,
        'remoteHistoryStreamVerified': False, 'readOnlyInputVerified': False, 'offlineReplayGuiVerified': False}
    env = {'PATH': os.environ.get('PATH', '/opt/java/openjdk/bin:/usr/bin:/bin'), 'LANG': 'C.UTF-8',
        'LC_ALL': 'C.UTF-8', 'DISPLAY': ':99', 'LIBGL_ALWAYS_SOFTWARE': '1', 'GALLIUM_DRIVER': 'llvmpipe',
        'LP_NUM_THREADS': '2', 'ALSOFT_DRIVERS': 'null'}

    def remain():
        left = deadline - time.monotonic(); require(left > 0, 'History GUI absolute deadline exceeded'); return left

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

    def interrupted(_signal, _frame): raise RuntimeError('History GUI worker interrupted')

    try:
        require(re.fullmatch('[0-9a-f]{64}', args.jar_sha256) and 80 <= args.seconds <= 100
                and args.seconds + 120 <= args.timeout_seconds <= 240, 'Invalid test settings')
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
        server_ready = min(time.monotonic() + 60, deadline)
        while f'Timeline server listening on {port}; protocol 7' not in (evidence / 'server.log').read_text(errors='replace'):
            require(server.poll() is None and time.monotonic() < server_ready, 'Production server failed startup')
            time.sleep(min(.1, remain()))
        state, client_env, java = role_environment('client')
        title = 'RWXX isolated v7 remote HISTORY'
        probe_path = evidence / 'client.ndjson'
        client_env.update(RWX_WINDOW_TITLE=title, RWX_WINDOW_WIDTH='1280', RWX_WINDOW_HEIGHT='800',
            RWX_DESKTOP_TARGET_FPS='30', RWX_DEBUG_AUTO_EXIT_SECONDS=str(args.seconds),
            RWX_AUTHORITY_ACTION_PROBE='1', RWX_AUTHORITY_CANVAS_PROBE=str(probe_path))
        child = launch('client', java + ['-Djava.awt.headless=false', '-Drwx.kool.backend=opengl', '-jar', str(JAR),
            '--authority-client', '--connect=127.0.0.1', '--port=' + str(port), '--token-file=' + str(credentials / '0.token'),
            '--name=HistorySeat0', '--record-view=' + str(evidence / 'client.rwxr'), '--views=4', '--map-cache-limit=0'], client_env)
        report['primaryJvmPids'] = [server.pid, child.pid]
        require(server.pid != child.pid, 'GUI shares server process')
        observed = {}; observations = []; last_seen = 0; window = None
        window_deadline = min(time.monotonic() + 20, deadline)
        while window is None:
            require(child.poll() is None and xvfb.poll() is None and time.monotonic() < window_deadline, 'GUI window failed startup')
            ids = command(['xdotool', 'search', '--onlyvisible', '--pid', str(child.pid), '--name', '^' + title + '$'], allowed=(0, 1)).split()
            if ids:
                require(len(ids) == 1 and ids[0].isdigit(), 'Unexpected owned window count'); window = ids[0]
            else: time.sleep(min(.1, remain()))
        command(['xdotool', 'windowmove', window, '100', '100'])
        command(['xdotool', 'windowfocus', '--sync', window])
        guards.parse_geometry(command(['xdotool', 'getwindowgeometry', '--shell', window]))

        def rows_now(allow_exit=False):
            nonlocal last_seen
            require((child.poll() in (None, 0) if allow_exit else child.poll() is None)
                    and server.poll() is None and xvfb.poll() is None, 'GUI/server/display exited during HISTORY controls')
            remain()
            rows = read_probe(probe_path)
            if rows: validate_canvas(rows)
            now = time.monotonic() - start
            for row in rows: observed.setdefault(row['submittedFrames'], now)
            if rows and rows[-1]['submittedFrames'] > last_seen:
                last = rows[-1]; last_seen = last['submittedFrames']
                observations.append({'wallSeconds': now, 'submittedFrames': last_seen,
                    'liveTick': last['viewports'][0]['tick'], 'stateChecks': last['wire']['stateChecks'], 'stateAcks': last['wire']['stateAcks']})
            return rows

        def key(keys):
            rows = rows_now(); require(rows, 'No rendered source before keyboard input')
            before = rows[-1]['submittedFrames']
            report['inputEvents'].append({'keys': keys, 'afterSubmittedFrame': before,
                'wallSeconds': time.monotonic() - start, 'delivery': 'XTest keyboard into focused owned canvas; success checked against later source probes'})
            command(['xdotool', 'key', '--clearmodifiers', '--delay', '40', *keys])
            return before

        def wait_phase(name, kind, after, pane, identity=None, base_time=None, held=None, timeout=12):
            until = min(time.monotonic() + timeout, deadline)
            while True:
                rows = rows_now(); samples = [r for r in rows if r['submittedFrames'] > after]
                if phase_matches(kind, samples, pane, identity, base_time, held):
                    tail = samples[-3:] if kind in ('pause', 'quiet') else samples[-2:]
                    wall = [observed[r['submittedFrames']] for r in tail]
                    if kind not in ('pause', 'quiet') or wall[-1] - wall[0] >= 2:
                        report['phases'].append({'name': name, 'kind': kind, 'pane': pane, 'samples': tail,
                                                'observedWallSeconds': wall})
                        return tail[-1]
                require(time.monotonic() < until, 'Actual HISTORY keyboard phase failed: ' + name)
                time.sleep(min(.1, remain()))

        def image(index):
            geometry = guards.parse_geometry(command(['xdotool', 'getwindowgeometry', '--shell', window]))
            report['screenshots'].append(dict(capture(evidence / f'history-frame-{index}.png', geometry),
                                             wallSeconds=time.monotonic() - start))

        # Do not infer HISTORY from paused/readOnly status. Wait for the actual LIVE floor and
        # the same-render completion receipt, after the normal LIVE UI probe has really completed.
        history_deadline = min(time.monotonic() + 55, deadline)
        while True:
            rows = rows_now()
            if rows:
                last = rows[-1]
                if last['actionProbe']['completed'] and all(history_ready(last, pane, older_than_live=True) for pane in (1, 2)):
                    require(last['inputWrites']['commandsWritten'] >= 2 and last['inputWrites']['selectionsWritten'] >= 1,
                            'Automatic LIVE positive control did not reach the actual writer')
                    report['historyAdmission'] = {'sample': last, 'wallSeconds': observed[last['submittedFrames']],
                        'requestTrigger': 'Precreated paused panes automatically request remote HISTORY once LIVE retention no longer contains their times; no keyboard-fetch attribution'}
                    report['remoteHistoryStreamVerified'] = True
                    break
            require(time.monotonic() < history_deadline, 'No completed remote HISTORY strictly older than the LIVE floor')
            time.sleep(min(.1, remain()))
        image(0)
        identity1 = history_identity(last, 1); identity2 = history_identity(last, 2)
        # Tab activates pane 1; F8 establishes movement so a following F6 has an observable effect.
        forward = wait_phase('remote-history-forward', 'forward', key(['Tab', 'F8']), 1, identity1)
        paused = wait_phase('pause-remote-history', 'pause', key(['F6']), 1, identity1)
        require(paused['viewports'][1]['sampledTime'] + 10 * TICKS_PER_SECOND * SUBTICKS < identity1[-1],
                'Accepted HISTORY window is too short for the requested seek')
        sought = wait_phase('seek-within-accepted-history', 'seek', key(['bracketright']), 1, identity1,
                            paused['viewports'][1]['sampledTime'])
        image(1)
        wait_phase('reverse-remote-history', 'reverse', key(['F7']), 1, identity1)
        wait_phase('resume-remote-history', 'forward', key(['F8']), 1, identity1)
        held = wait_phase('pause-before-independent-pane', 'pause', key(['F6']), 1, identity1)
        held_time = held['viewports'][1]['sampledTime']
        pane2 = wait_phase('activate-independent-history', 'active', key(['Tab']), 2, identity2, held={1: held_time})
        require(pane2['viewports'][2]['sampledTime'] + 10 * TICKS_PER_SECOND * SUBTICKS < identity2[-1],
                'Second HISTORY window is too short for independent seek')
        sought2 = wait_phase('independent-history-seek', 'seek', key(['bracketright']), 2, identity2,
                             pane2['viewports'][2]['sampledTime'], held={1: held_time})
        quiet = wait_phase('quiesce-live-writer-before-readonly-input', 'quiet', sought2['submittedFrames'], 2,
                           identity2, held={1: held_time, 2: sought2['viewports'][2]['sampledTime']})
        # These are ordinary canvas input routes, not protocol writes or a fake read-only probe.
        pane = quiet['viewports'][2]
        x, y = pane['x'] + 80, pane['y'] + 80
        require(x + 100 < pane['x'] + pane['width'] and y + 80 < pane['y'] + pane['height'], 'History input point leaves its pane')
        report['inputEvents'].append({'kind': 'readonly-selection-order-action-attempts', 'pane': 2,
            'afterSubmittedFrame': quiet['submittedFrames'], 'wallSeconds': time.monotonic() - start,
            'attempts': ['left-drag-selection', 'right-click-order', 'ctrl+a-selection', 'a-attack-move', 's-stop', '1-action', 'ctrl+1-cancel'],
            'delivery': 'Real XTest mouse and keyboard events within the active HISTORY pane'})
        command(['xdotool', 'mousemove', '--window', window, str(x), str(y)])
        command(['xdotool', 'mousedown', '1'])
        try: command(['xdotool', 'mousemove', '--window', window, str(x + 100), str(y + 80)])
        finally: command(['xdotool', 'mouseup', '1'])
        command(['xdotool', 'click', '3'])
        command(['xdotool', 'key', '--clearmodifiers', '--delay', '80', 'ctrl+a', 'a', 's', '1', 'ctrl+1'])
        until = min(time.monotonic() + 12, deadline)
        while True:
            rows = rows_now(); samples = [r for r in rows if r['submittedFrames'] > quiet['submittedFrames']]
            if len(samples) >= 3 and samples[-1]['submittedFrames'] - samples[0]['submittedFrames'] >= 60 \
                    and observed[samples[-1]['submittedFrames']] - observed[samples[0]['submittedFrames']] >= 2:
                report['readOnlyInput'] = validate_readonly_interval(quiet, samples, 2, identity2)
                require(all(r['viewports'][1]['sampledTime'] == held_time for r in samples), 'Independent paused pane changed after input')
                report['readOnlyInputVerified'] = True
                break
            require(time.monotonic() < until, 'Readonly writer evidence did not stabilize')
            time.sleep(min(.1, remain()))
        image(2)
        # No further user input is issued. Pane 0 remains LIVE throughout even while pane 2 is active.
        while child.poll() is None:
            rows_now(allow_exit=True); time.sleep(min(.1, remain()))
        require(child.returncode == 0, 'GUI did not exit through its normal timed-close')
        report['clientExitCode'] = child.returncode
        report['canvas'] = validate_canvas(read_probe(probe_path, final=True), observations, final=True)
        report['knownResourceDiagnostics'] = validate_resources(evidence / 'client.log')
        import tomllib
        prefs = tomllib.loads((state / 'preferences.toml').read_text()).get('preferences', {})
        require(prefs.get('enableSounds') == 'false' and all(float(prefs.get(k, 'nan')) == 0 for k in
                ('masterVolume', 'musicVolume', 'gameVolume', 'interfaceVolume')), 'Client sound preferences changed')
        recording = evidence / 'client.rwxr'
        require(recording.is_file() and 0 < recording.stat().st_size <= MAX_FILE, 'Player recording missing or oversized')
        inspection_file = evidence / 'client.inspection.json'
        _, inspector_env, java = role_environment('inspect')
        inspector = launch('inspect', java + ['-Djava.awt.headless=true', '-cp', str(JAR),
            'io.github.rwx.authoritative.TimelineReplayInspectMain', '--input=' + str(recording), '--output=' + str(inspection_file)], inspector_env)
        require(inspector.wait(timeout=min(25, remain())) == 0, 'Formal recording inspector failed')
        report['recordingInspection'] = validate_inspection(json.loads(inspection_file.read_text()), 0, report['canvas']['publicVisual'])
        report['recordingSha256'] = guards.digest(recording)
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
