"""Run the pinned original PC build with RWX's four camera modes, without modifying its files."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

from map_pan_comparison import percentile
from frozen_benchmark_tooling import fingerprint

PROJECT = Path(os.environ.get('RWX_BENCHMARK_PROJECT_ROOT', Path(__file__).resolve().parents[2]))
ORIGINAL = Path(r'C:\Users\daerh\Downloads\Rusted Warfare1.15电脑版\Rusted Warfare')
EXPECTED_HASH = '8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9'
JAVA_HOME = Path(r'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_probe(output):
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
    subprocess.run([str(JAVA_HOME / 'bin/javac.exe'), '--release', '8', '-encoding', 'UTF-8',
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--camera-mode', choices=('static', 'pan', 'zoom', 'pan-zoom'), required=True)
    parser.add_argument('--replay', type=Path, required=True)
    parser.add_argument('--warmup-seconds', type=int, default=20)
    parser.add_argument('--sample-seconds', type=int, default=40)
    parser.add_argument('--load-seconds', type=int, default=30)
    parser.add_argument('--normal-input-probe', action='store_true')
    args = parser.parse_args()
    if args.warmup_seconds < 1 or args.sample_seconds < 1 or args.load_seconds < 1:
        parser.error('warmup, sample and load durations must be positive')
    runtime_hash = digest(ORIGINAL / 'game-lib.jar')
    if runtime_hash != EXPECTED_HASH:
        raise RuntimeError(f'original build identity changed: {runtime_hash}')
    # Require an already-installed matching replay; never overwrite the user's original installation.
    replay_hash = digest(args.replay)
    matches = [p for p in (ORIGINAL / 'replays').glob('*.replay') if digest(p) == replay_hash]
    if not matches:
        raise RuntimeError('the original installation has no replay matching the requested SHA256')
    # Java 13's Windows argument encoding cannot preserve supplementary characters in replay names.
    # Prefer an existing ASCII alias only after verifying its bytes are identical.
    replay = min(matches, key=lambda path: (not path.name.isascii(), path.name))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    probe, source_hashes = build_probe(output)
    trace = output / 'original-present.csv'
    command = [str(ORIGINAL / 'jvm64/bin/java.exe'), '-Xmx1000M', '-Dfile.encoding=UTF-8',
               '-Djava.library.path=.', f'-javaagent:{probe}', f'-Drwx.orig.trace={trace}',
               '-Drwx.orig.replay=trigger', f'-Drwx.orig.replayname={replay.name}',
               f'-Drwx.orig.autostart.seconds={args.load_seconds}', f'-Drwx.orig.camera.mode={args.camera_mode}',
               f'-Drwx.orig.warmup.seconds={args.warmup_seconds}',
               f'-Drwx.orig.measure.seconds={args.warmup_seconds + args.sample_seconds}',
               '-cp', 'game-lib.jar;libs/*', 'com.corrodinggames.rts.java.Main', '-width', '1280', '-height', '720']
    if args.normal_input_probe:
        command[1:1] = ['-Drwx.orig.normal.input=true', f'-Drwx.orig.input.trace={output / "input-response.csv"}']
    tooling_digest, _ = fingerprint(Path(__file__).resolve().parent)
    if os.environ.get('RWX_BENCHMARK_TOOLING_SHA256', tooling_digest) != tooling_digest:
        raise RuntimeError('frozen benchmark tooling fingerprint changed before launch')
    manifest = {'originalRoot': str(ORIGINAL), 'runtimeSha256': runtime_hash, 'replaySha256': replay_hash,
                'toolingSha256': tooling_digest,
                'diagnosticOnly': args.normal_input_probe, 'normalInputProbe': args.normal_input_probe,
                'replayName': replay.name, 'probeSha256': digest(probe), 'sourceSha256': source_hashes,
                'cameraMode': args.camera_mode, 'panPeriodSeconds': 20, 'zoomPeriodSeconds': 4,
                'zoomMinimum': .35, 'zoomMaximum': 1.5, 'width': 1280, 'height': 720,
                'warmupSeconds': args.warmup_seconds, 'sampleSeconds': args.sample_seconds, 'argv': command}
    manifest['configuration'] = {key: manifest[key] for key in ('cameraMode', 'panPeriodSeconds',
        'zoomPeriodSeconds', 'zoomMinimum', 'zoomMaximum', 'width', 'height', 'warmupSeconds', 'sampleSeconds')}
    manifest['configuration']['presentTraceBoundary'] = 'after-native-swap-return'
    manifest['configuration']['normalInputProbe'] = args.normal_input_probe
    manifest['configuration']['installationPreferencesSha256'] = {
        path.name: digest(path) for path in sorted(ORIGINAL.glob('preferences*')) if path.is_file()}
    manifest['configurationSha256'] = hashlib.sha256(json.dumps(manifest['configuration'],
        sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'START original {args.camera_mode} output={output}', flush=True)
    with (output / 'original.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=ORIGINAL, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            code = process.wait(timeout=args.load_seconds + args.warmup_seconds + args.sample_seconds + 60)
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
    with trace.open(encoding='utf-8') as stream:
        times = [int(row[0]) for row in csv.reader(stream) if row and row[0].isdigit() and row[2] == '(Z)V']
    selected = [i for i in range(1, len(times)) if start <= times[i] <= end]
    if not selected or times[-1] < end - 100_000_000:
        raise RuntimeError('original trace does not cover the complete sample window')
    intervals = [(times[i] - times[i - 1]) / 1e6 for i in selected]
    report = {**manifest, 'newFps': len(selected) / args.sample_seconds,
              'cameraEvidence': {'viewportWidth': 1280, 'viewportHeight': 720, 'spanX': span_x,
                                 'spanY': span_y, 'zoomMinimum': zoom_min, 'zoomMaximum': zoom_max},
              'fogDisplayEnabled': fog_display,
              'simulationEvidence': simulation_evidence,
              'freshSnapshotIntervalP99Ms': percentile(intervals, .99),
              'freshSnapshotIntervalP95Ms': percentile(intervals, .95),
              'freshSnapshotIntervalP999Ms': percentile(intervals, .999),
              'freshSnapshotIntervalMaxMs': max(intervals),
              'longFrames': [{'acceptedPresentNanos': times[i], 'intervalMs': (times[i] - times[i - 1]) / 1e6}
                             for i in selected if times[i] - times[i - 1] > 16_667_000]}
    for limit in (16.667, 33.333, 50, 100):
        report[f'intervalsOver{limit}Ms'] = sum(value > limit for value in intervals)
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
