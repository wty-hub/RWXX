"""Run the pinned original PC build with RWX's four camera modes, without modifying its files."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from map_pan_comparison import percentile
from frozen_benchmark_tooling import fingerprint

PROJECT = Path(os.environ.get('RWX_BENCHMARK_PROJECT_ROOT', Path(__file__).resolve().parents[2]))
ORIGINAL = Path(r'C:\Users\daerh\Downloads\Rusted Warfare1.15电脑版\Rusted Warfare')
EXPECTED_HASH = '8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9'
JAVA_HOME = Path(r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot')
TRAILING_CAPTURE_SECONDS = 2


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def analyze_present_trace(trace, start, end, sample_seconds):
    """Separate swap returns from presents following a completed game-render callback.

    The recorded active/visible flags must hold for every sampled swap, including repeats.
    These observations do not measure physical scanout or OS input delivery.
    """
    columns = ['acceptedPresentNanos', 'sequence', 'overload', 'renderSequence', 'active', 'visible']
    rows = []
    with trace.open(encoding='utf-8', newline='') as stream:
        reader = csv.reader(stream)
        if next(reader, [])[:len(columns)] != columns:
            raise RuntimeError('original trace lacks render sequence and display-state evidence')
        for row in reader:
            if not row:
                continue
            if len(row) < len(columns):
                raise RuntimeError('original trace has incomplete render/display evidence')
            if row[2] != '(Z)V':
                continue
            try:
                nanos, sequence, render_sequence = map(int, (row[0], row[1], row[3]))
            except ValueError as error:
                raise RuntimeError('original trace has malformed sequence evidence') from error
            if row[4] not in ('true', 'false') or row[5] not in ('true', 'false'):
                raise RuntimeError('original trace has malformed display-state evidence')
            if nanos < 0 or sequence < 1 or render_sequence < 0:
                raise RuntimeError('original trace has invalid sequence evidence')
            if rows and (nanos <= rows[-1][0] or sequence <= rows[-1][1]
                         or render_sequence < rows[-1][2]):
                raise RuntimeError('original trace timestamps or sequences did not advance monotonically')
            rows.append((nanos, sequence, render_sequence, row[4] == 'true', row[5] == 'true'))
    selected = [i for i in range(1, len(rows)) if start <= rows[i][0] <= end]
    if not selected or rows[0][0] >= start or rows[-1][0] < end:
        raise RuntimeError('original trace does not cover the complete sample window')
    # Include the boundary observation used by the first sampled interval. Never activate the window.
    observed = [selected[0] - 1, *selected]
    if any(not rows[i][3] for i in observed):
        raise RuntimeError('original sample invalid: display lost focus (isActive=false)')
    if any(not rows[i][4] for i in observed):
        raise RuntimeError('original sample invalid: display was hidden (isVisible=false)')
    fresh = ([0] if rows[0][2] > 0 else []) + [
        i for i in range(1, len(rows)) if rows[i][2] > rows[i - 1][2]]
    fresh_selected = [i for i in fresh if start <= rows[i][0] <= end]
    if not fresh_selected:
        raise RuntimeError('original sample has no completed game-render sequence advancement')
    prior_fresh = {fresh[i]: fresh[i - 1] for i in range(1, len(fresh))}
    if fresh_selected[0] not in prior_fresh:
        raise RuntimeError('original trace lacks a prior rendered-present cadence anchor')
    # A tail of repeated swaps is a measured stall, not grounds for rejecting a slow sample.
    # Closed intervals end at a fresh present; the unfinished tail is reported separately as age.
    intervals = [(rows[i][0] - rows[prior_fresh[i]][0]) / 1e6 for i in fresh_selected]
    accepted_intervals = [(rows[i][0] - rows[i - 1][0]) / 1e6 for i in selected]
    return {
        'newFps': len(fresh_selected) / sample_seconds,
        'freshRenderedFps': len(fresh_selected) / sample_seconds,
        'acceptedPresentFps': len(selected) / sample_seconds,
        'acceptedPresentCount': len(selected),
        'freshRenderedPresentCount': len(fresh_selected),
        'presentsWithoutNewRenderCount': len(selected) - len(fresh_selected),
        'renderSequenceStart': rows[selected[0] - 1][2],
        'renderSequenceEnd': rows[selected[-1]][2],
        'freshFrameAgeAtSampleEndMs': (end - rows[fresh_selected[-1]][0]) / 1e6,
        'displayActiveAtEverySampledPresent': True,
        'displayVisibleAtEverySampledPresent': True,
        'freshSnapshotIntervalP99Ms': percentile(intervals, .99),
        'freshSnapshotIntervalP95Ms': percentile(intervals, .95),
        'freshSnapshotIntervalP999Ms': percentile(intervals, .999),
        'freshSnapshotIntervalMaxMs': max(intervals),
        'acceptedPresentIntervalP99Ms': percentile(accepted_intervals, .99),
        'acceptedPresentIntervalMaxMs': max(accepted_intervals),
        'longFrames': [{'acceptedPresentNanos': rows[i][0],
                        'intervalMs': (rows[i][0] - rows[prior_fresh[i]][0]) / 1e6}
                       for i in fresh_selected if rows[i][0] - rows[prior_fresh[i]][0] > 16_667_000],
        **{f'intervalsOver{limit}Ms': sum(value > limit for value in intervals)
           for limit in (16.667, 33.333, 50, 100)},
    }


def build_probe(output, javac=None):
    sources = sorted((Path(os.environ.get('RWX_BENCHMARK_TOOL_ROOT', PROJECT / 'desktop/tools')) / 'original_probe').glob('*.java'))
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    libraries = []
    for module in ('asm', 'asm-commons', 'asm-tree'):
        candidates = sorted((cache / module).rglob(f'{module}-*.jar'))
        candidates = [p for p in candidates if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
        if not candidates:
            raise RuntimeError(f'missing build dependency: {module}')
        libraries.append(candidates[-1])
    classes = output / 'probe-classes'
    classes.mkdir()
    subprocess.run([str(javac if javac is not None else JAVA_HOME / 'bin/javac.exe'), '--release', '8', '-encoding', 'UTF-8',
                    '-cp', os.pathsep.join(map(str, libraries)), '-d', str(classes), *map(str, sources)], check=True)
    jar = output / 'original-probe.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(zipfile.ZipInfo('META-INF/MANIFEST.MF', (1980, 1, 1, 0, 0, 0)),
                         'Manifest-Version: 1.0\nPremain-Class: io.github.rwx.probe.OrigPresentAgent\nCan-Retransform-Classes: true\n\n')
        for path in sorted(classes.rglob('*.class')):
            info = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
        for library in libraries:
            with zipfile.ZipFile(library) as dependency:
                for entry in dependency.infolist():
                    if entry.filename.startswith('org/objectweb/asm/') and entry.filename.endswith('.class'):
                        info = zipfile.ZipInfo(entry.filename, (1980, 1, 1, 0, 0, 0))
                        info.compress_type = zipfile.ZIP_DEFLATED
                        archive.writestr(info, dependency.read(entry))
    return jar, {p.name: digest(p) for p in sources}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--original-root', type=Path, default=ORIGINAL)
    parser.add_argument('--java', type=Path)
    parser.add_argument('--javac', type=Path, default=JAVA_HOME / 'bin/javac.exe')
    parser.add_argument('--prepare-only', action='store_true',
                        help='validate pinned inputs and build the probe without launching or measuring the game')
    parser.add_argument('--camera-mode', choices=('static', 'pan', 'zoom', 'pan-zoom'), required=True)
    parser.add_argument('--replay', type=Path, required=True)
    parser.add_argument('--warmup-seconds', type=int, default=20)
    parser.add_argument('--sample-seconds', type=int, default=40)
    parser.add_argument('--load-seconds', type=int, default=30)
    parser.add_argument('--normal-input-probe', action='store_true')
    parser.add_argument('--start-on-first-thread', action='store_true',
                        help='explicitly request macOS -XstartOnFirstThread; the stock launcher does not set it')
    args = parser.parse_args(argv)
    if args.warmup_seconds < 1 or args.sample_seconds < 1 or args.load_seconds < 1:
        parser.error('warmup, sample and load durations must be positive')
    original = args.original_root.resolve()
    output = args.output.resolve()
    if output == original or original in output.parents:
        parser.error('benchmark output must be outside the original installation')
    runtime_hash = digest(original / 'game-lib.jar')
    if runtime_hash != EXPECTED_HASH:
        raise RuntimeError(f'original build identity changed: {runtime_hash}')
    # Require an already-installed matching replay; never overwrite the user's original installation.
    replay_hash = digest(args.replay)
    matches = [p for p in (original / 'replays').glob('*.replay') if digest(p) == replay_hash]
    if not matches:
        raise RuntimeError('the original installation has no replay matching the requested SHA256')
    # Java 13's Windows argument encoding cannot preserve supplementary characters in replay names.
    # Prefer an existing ASCII alias only after verifying its bytes are identical.
    replay = min(matches, key=lambda path: (not path.name.isascii(), path.name))
    output.mkdir(parents=True, exist_ok=False)
    probe, source_hashes = build_probe(output, args.javac)
    trace = output / 'original-present.csv'
    java = args.java if args.java is not None else original / 'jvm64/bin/java.exe'
    command = [str(java), '-Xmx1000M', '-Dfile.encoding=UTF-8',
               '-Djava.library.path=.', f'-javaagent:{probe}', f'-Drwx.orig.trace={trace}',
               '-Drwx.orig.replay=trigger', f'-Drwx.orig.replayname={replay.name}',
               f'-Drwx.orig.autostart.seconds={args.load_seconds}', f'-Drwx.orig.camera.mode={args.camera_mode}',
               f'-Drwx.orig.warmup.seconds={args.warmup_seconds}',
               f'-Drwx.orig.measure.seconds={args.warmup_seconds + args.sample_seconds}',
               f'-Drwx.orig.trailing.capture.seconds={TRAILING_CAPTURE_SECONDS}',
               '-cp', os.pathsep.join(('game-lib.jar', 'libs/*')), 'com.corrodinggames.rts.java.Main', '-width', '1280', '-height', '720']
    start_on_first_thread = sys.platform == 'darwin' and args.start_on_first_thread
    if start_on_first_thread:
        command.insert(1, '-XstartOnFirstThread')
    if args.normal_input_probe:
        command[1:1] = ['-Drwx.orig.normal.input=true', f'-Drwx.orig.input.trace={output / "input-response.csv"}']
    tooling_digest, _ = fingerprint(Path(__file__).resolve().parent)
    if os.environ.get('RWX_BENCHMARK_TOOLING_SHA256', tooling_digest) != tooling_digest:
        raise RuntimeError('frozen benchmark tooling fingerprint changed before launch')
    manifest = {'originalRoot': str(original), 'runtimeSha256': runtime_hash, 'replaySha256': replay_hash,
                'toolingSha256': tooling_digest,
                'platform': sys.platform, 'javaExecutable': str(java), 'javacExecutable': str(args.javac),
                'prepareOnly': args.prepare_only, 'measurementPerformed': False,
                'startOnFirstThread': start_on_first_thread,
                'startOnFirstThreadRequested': args.start_on_first_thread,
                'measurementStatus': 'not-measured',
                'diagnosticOnly': args.normal_input_probe, 'normalInputProbe': args.normal_input_probe,
                'replayName': replay.name, 'probeSha256': digest(probe), 'sourceSha256': source_hashes,
                'cameraMode': args.camera_mode, 'panPeriodSeconds': 20, 'zoomPeriodSeconds': 4,
                'zoomMinimum': .35, 'zoomMaximum': 1.5, 'width': 1280, 'height': 720,
                'warmupSeconds': args.warmup_seconds, 'sampleSeconds': args.sample_seconds,
                'trailingCaptureSeconds': TRAILING_CAPTURE_SECONDS, 'argv': command}
    manifest['configuration'] = {key: manifest[key] for key in ('cameraMode', 'panPeriodSeconds',
        'zoomPeriodSeconds', 'zoomMinimum', 'zoomMaximum', 'width', 'height', 'warmupSeconds', 'sampleSeconds',
        'trailingCaptureSeconds')}
    manifest['configuration']['presentTraceBoundary'] = 'after-native-swap-return-and-display-state-observation'
    manifest['configuration']['presentTraceVersion'] = 2
    manifest['configuration']['freshFrameEvidence'] = (
        'advanced completed u.render(GameContainer,Graphics) callback sequence at swap return')
    manifest['configuration']['freshIntervalStatistics'] = (
        'closed intervals ending inside the sample; unfinished trailing age is freshFrameAgeAtSampleEndMs')
    manifest['configuration']['displayValidityGate'] = 'isActive-and-isVisible-for-every-sampled-present'
    manifest['configuration']['presentationLimitations'] = (
        'Callback completion and swap return do not establish physical scanout or OS input delivery.')
    manifest['configuration']['normalInputProbe'] = args.normal_input_probe
    manifest['configuration']['startOnFirstThread'] = start_on_first_thread
    manifest['configuration']['installationPreferencesSha256'] = {
        path.name: digest(path) for path in sorted(original.glob('preferences*')) if path.is_file()}
    manifest['configurationSha256'] = hashlib.sha256(json.dumps(manifest['configuration'],
        sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    (output / 'command.json').write_text(json.dumps({'cwd': str(original), 'argv': command},
        ensure_ascii=False, indent=2), encoding='utf-8')
    if args.prepare_only:
        print(f'PREPARED original {args.camera_mode} output={output}; NOT MEASURED (game was not launched)', flush=True)
        return
    print(f'START original {args.camera_mode} output={output}', flush=True)
    with (output / 'original.log').open('w', encoding='utf-8') as log:
        launch_options = {'creationflags': subprocess.CREATE_NO_WINDOW} if sys.platform == 'win32' else {}
        process = subprocess.Popen(command, cwd=original, stdout=log, stderr=subprocess.STDOUT,
                                   **launch_options)
        try:
            code = process.wait(timeout=args.load_seconds + args.warmup_seconds + args.sample_seconds
                                + TRAILING_CAPTURE_SECONDS + 60)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            raise RuntimeError('original benchmark did not complete; partial trace is not an acceptance result')
    if code:
        raise RuntimeError(f'original benchmark exited {code}; inspect {output / "original.log"}')
    log_text = (output / 'original.log').read_text(encoding='utf-8', errors='replace')
    match = re.search(r'camera drive: mode=\S+ startNanos=(\d+)', log_text)
    if not match or '[rwx-probe] pan failed:' in log_text or 'Cannot load replay:' in log_text:
        raise RuntimeError('original camera protocol failed')
    evidence = re.search(r'camera evidence viewport=(\d+)x(\d+) spanX=([\d.Ee+-]+) spanY=([\d.Ee+-]+) '
                         r'zoomMin=([\d.Ee+-]+) zoomMax=([\d.Ee+-]+)', log_text)
    if not evidence or tuple(map(int, evidence.group(1, 2))) != (1280, 720):
        raise RuntimeError('original physical viewport or camera evidence missing / mismatched')
    fog_evidence = re.search(r'fog evidence enabled=(true|false) data=(true|false)', log_text)
    if not fog_evidence:
        raise RuntimeError('original fog display evidence missing')
    fog_display = all(value == 'true' for value in fog_evidence.groups())
    simulation = re.search(r'simulation evidence initialTick=(\d+) initialGameTimeMillis=(\d+) '
        r'sampleStartTick=(\d+) sampleStartGameTimeMillis=(\d+) sampleEndTick=(\d+) sampleEndGameTimeMillis=(\d+)', log_text)
    if not simulation:
        raise RuntimeError('original simulation interval evidence missing')
    simulation_evidence = dict(zip(('initialTick', 'initialGameTimeMillis', 'startTick', 'startGameTimeMillis',
        'endTick', 'endGameTimeMillis'), map(int, simulation.groups())))
    if simulation_evidence['endTick'] <= simulation_evidence['startTick']:
        raise RuntimeError('original replay simulation did not advance')
    span_x, span_y, zoom_min, zoom_max = map(float, evidence.group(3, 4, 5, 6))
    if not args.normal_input_probe and args.camera_mode in ('pan', 'pan-zoom') and min(span_x, span_y) <= 0:
        raise RuntimeError('original pan was requested but actual camera did not move')
    if not args.normal_input_probe and args.camera_mode in ('zoom', 'pan-zoom') and zoom_max - zoom_min <= .01:
        raise RuntimeError('original zoom was requested but actual zoom did not change')
    start = int(match.group(1)) + args.warmup_seconds * 1_000_000_000
    end = start + args.sample_seconds * 1_000_000_000
    try:
        cadence = analyze_present_trace(trace, start, end, args.sample_seconds)
    except RuntimeError as error:
        invalid = {**manifest, 'measurementAttempted': True, 'measurementPerformed': False,
                   'measurementStatus': 'invalid', 'invalidReason': str(error)}
        (output / 'summary.json').write_text(json.dumps(invalid, ensure_ascii=False, indent=2), encoding='utf-8')
        raise
    report = {**manifest, 'measurementPerformed': True, 'measurementStatus': 'measured',
              **cadence,
              'cameraEvidence': {'viewportWidth': 1280, 'viewportHeight': 720, 'spanX': span_x,
                                 'spanY': span_y, 'zoomMinimum': zoom_min, 'zoomMaximum': zoom_max},
              'fogDisplayEnabled': fog_display,
              'simulationEvidence': simulation_evidence}
    (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    if args.normal_input_probe:
        from analyze_input_response import analyze
        with (output / 'input-response.csv').open(encoding='utf-8') as stream:
            response = analyze(list(csv.DictReader(stream)), [{'sampleStartNanos': start, 'sampleEndNanos': end}])
        response['measurementBoundary'] = 'normal Slick input callback sampling to native swap return; OS event delivery before callback sampling is not included'
        (output / 'input-response-summary.json').write_text(json.dumps(response, indent=2), encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('newFps', 'freshSnapshotIntervalP99Ms', 'freshSnapshotIntervalMaxMs')}) )


if __name__ == '__main__':
    main()
