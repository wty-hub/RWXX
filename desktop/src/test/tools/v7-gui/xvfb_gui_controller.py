#!/usr/bin/env python3
"""Create exactly one private Linux GUI-smoke container; never touch a host display.

Build the adjacent Dockerfile with the frozen production JAR, then pass its immutable
image ID. This controller does not build, pull, mount host files, or publish ports.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time
import uuid

OWNER = 'io.github.rwx.gui-smoke.owner'
JAR_LABEL = 'io.github.rwx.gui-smoke.jar-sha256'
TMPFS = {'/tmp': 'rw,exec,nosuid,nodev,size=1536m,mode=1777'}
ARTIFACTS = {'java.log', 'xvfb.log', 'glxinfo.txt', 'frame-0.png', 'frame-1.png'}
MAX_OUTPUT = 64 * 1024 * 1024
MAX_FILE = 8 * 1024 * 1024


def require(value, message):
    if not value: raise RuntimeError(message)


def verify_jar(path, expected):
    require(re.fullmatch('[0-9a-f]{64}', expected), 'Invalid frozen JAR SHA')
    path = Path(path).resolve(strict=True)
    require(path.is_file(), 'Frozen JAR must be a regular file')
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): digest.update(block)
    require(digest.hexdigest() == expected, 'Explicit frozen JAR bytes differ')
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': expected,
            'providedByHostMount': False}


def create_command(image_id, owner, jar_sha, seconds, timeout):
    require(re.fullmatch('sha256:[0-9a-f]{64}', image_id), 'An immutable local image ID is required')
    require(re.fullmatch('[0-9a-f]{32}', owner), 'Invalid owned container identifier')
    require(re.fullmatch('[0-9a-f]{64}', jar_sha), 'Invalid frozen JAR SHA')
    require(25 <= seconds <= 60 and seconds + 20 <= timeout <= 120, 'Invalid deadline')
    return ['docker', 'create', '--pull=never', '--name', 'rwxx-gui-smoke-' + owner,
        '--label', OWNER + '=' + owner, '--label', JAR_LABEL + '=' + jar_sha,
        '--network', 'none', '--cap-drop', 'ALL', '--read-only', '--user', '1000:1000',
        '--tmpfs', '/tmp:' + TMPFS['/tmp'], '--pids-limit', '512', '--memory', '3g',
        '--cpus', '2.0', '--shm-size', '256m', '--security-opt', 'no-new-privileges:true',
        '--entrypoint', '/usr/bin/python3', image_id, '-u', '-B', '/opt/rwxx-gui-smoke/worker.py',
        '--jar-sha256', jar_sha, '--seconds', str(seconds), '--timeout-seconds', str(timeout)]


def inspect(reference, kind='container'):
    result = subprocess.run(['docker', kind, 'inspect', reference], capture_output=True,
                            text=True, timeout=10, check=False)
    if result.returncode: return None
    require(len(result.stdout) <= 2 * 1024 * 1024, 'Inspect response exceeds limit')
    rows = json.loads(result.stdout)
    require(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict), 'Invalid inspect response')
    return rows[0]


def verify_container(row, cid, owner, image_id, jar_sha):
    require(isinstance(row, dict), 'Missing owned container')
    config = row.get('Config', {}); host = row.get('HostConfig', {})
    require(row.get('Id') == cid and row.get('Name') == '/rwxx-gui-smoke-' + owner
            and config.get('Labels', {}).get(OWNER) == owner
            and config.get('Labels', {}).get(JAR_LABEL) == jar_sha and row.get('Image') == image_id,
            'Container/image ownership differs')
    normalized = lambda values: {str(v).upper().removeprefix('CAP_') for v in values or []}
    require(host.get('NetworkMode') == 'none' and host.get('ReadonlyRootfs') is True
            and host.get('Privileged') is False and normalized(host.get('CapDrop')) == {'ALL'}
            and not host.get('CapAdd') and config.get('User') == '1000:1000', 'Isolation differs')
    require(not host.get('Binds') and not host.get('Devices') and not host.get('DeviceRequests')
            and not host.get('VolumesFrom') and not host.get('PortBindings') and not host.get('Mounts')
            and host.get('PidMode') in (None, '') and host.get('IpcMode') in (None, 'private')
            and host.get('UTSMode') in (None, '') and host.get('Tmpfs') == TMPFS,
            'Unexpected host mount, namespace, device or port')
    require(all(m.get('Type') == 'tmpfs' and m.get('Destination') == '/tmp' for m in row.get('Mounts', [])),
            'Unexpected filesystem mount')
    require(host.get('Memory') == 3 * 1024 ** 3 and host.get('NanoCpus') == 2_000_000_000
            and host.get('PidsLimit') == 512 and host.get('ShmSize') == 256 * 1024 ** 2,
            'Resource limits differ')
    require(any(v.split(':')[0] == 'no-new-privileges' for v in host.get('SecurityOpt', [])), 'Privileges may increase')
    require(config.get('Entrypoint') == ['/usr/bin/python3'] and not config.get('Tty')
            and not config.get('OpenStdin'), 'Worker entrypoint or I/O differs')
    return {'containerId': cid, 'imageId': image_id, 'owner': owner, 'network': 'none',
            'readOnlyRootfs': True, 'user': '1000:1000', 'capabilitiesAdded': [], 'capabilitiesDropped': ['ALL'],
            'hostBindsDevicesPorts': False, 'tmpfs': TMPFS, 'memoryBytes': host['Memory'],
            'cpus': 2, 'pidsLimit': 512, 'shmBytes': host['ShmSize']}


def bounded_output(process, timeout, buffers=None):
    deadline = time.monotonic() + timeout
    outputs = buffers if buffers is not None else {'stdout': bytearray(), 'stderr': bytearray()}
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ, 'stdout')
        selector.register(process.stderr, selectors.EVENT_READ, 'stderr')
        while selector.get_map():
            remaining = deadline - time.monotonic()
            require(remaining > 0, 'Container worker deadline exceeded')
            events = selector.select(min(remaining, 1))
            for key, _ in events:
                data = os.read(key.fileobj.fileno(), 65536)
                if not data: selector.unregister(key.fileobj); continue
                output = outputs[key.data]
                limit = MAX_OUTPUT if key.data == 'stdout' else 1024 * 1024
                remaining_bytes = limit - len(output)
                output.extend(data[:remaining_bytes])
                require(len(data) <= remaining_bytes, 'Worker output exceeds limit')
    process.wait(timeout=max(.01, deadline - time.monotonic()))
    return {key: bytes(value) for key, value in outputs.items()}


def save_attach_output(output, directory):
    """Preserve bounded raw evidence before any JSON assumption, including failures."""
    for name, limit in (('stdout', MAX_OUTPUT), ('stderr', 1024 * 1024)):
        data = output[name]
        require(isinstance(data, (bytes, bytearray)) and len(data) <= limit, 'Unbounded attached output')
        with (directory / ('attach.' + name + '.log')).open('xb') as target: target.write(data)


def export_artifacts(report, output):
    require(isinstance(report, dict) and isinstance(report.get('artifacts'), list), 'Malformed worker report')
    items = report['artifacts']
    require(len(items) <= len(ARTIFACTS), 'Too many artifacts')
    seen = set(); sanitized = []
    for item in items:
        require(isinstance(item, dict) and set(item) == {'file', 'bytes', 'sha256', 'base64'}, 'Malformed artifact')
        name = item['file']
        require(name in ARTIFACTS and name not in seen, 'Unexpected/duplicate artifact path')
        require(type(item['bytes']) is int and 0 <= item['bytes'] <= MAX_FILE, 'Invalid artifact size')
        data = base64.b64decode(item['base64'], validate=True)
        require(len(data) == item['bytes'] and hashlib.sha256(data).hexdigest() == item['sha256'], 'Artifact bytes differ')
        with (output / name).open('xb') as target: target.write(data)
        sanitized.append({key: item[key] for key in ('file', 'bytes', 'sha256')}); seen.add(name)
    report = dict(report); report['artifacts'] = sanitized
    if report.get('passed') is True:
        require({'java.log', 'glxinfo.txt', 'frame-0.png', 'frame-1.png'} <= seen
                and report.get('javaExitCode') == 0 and len(report.get('screenshots', [])) == 2,
                'Passing smoke lacks required actual evidence')
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image-id', required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--jar-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=35)
    parser.add_argument('--timeout-seconds', type=int, default=90)
    args = parser.parse_args()
    owner = uuid.uuid4().hex
    command = create_command(args.image_id, owner, args.jar_sha256, args.seconds, args.timeout_seconds)
    args.output.mkdir(parents=True, exist_ok=False)
    result = {'scope': 'Linux isolated menu/pixel smoke; no protocol acceptance or macOS replacement',
              'passed': False, 'controllerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'createCommand': command, 'cleanup': None}
    cid = None; attached = None; create_attempted = False
    captured = {'stdout': bytearray(), 'stderr': bytearray()}
    def interrupted(_signum, _frame): raise RuntimeError('Controller interrupted')
    signal.signal(signal.SIGINT, interrupted); signal.signal(signal.SIGTERM, interrupted)
    try:
        result['frozenJar'] = verify_jar(args.jar, args.jar_sha256)
        image = inspect(args.image_id, 'image')
        require(image and image.get('Id') == args.image_id
                and image.get('Config', {}).get('Labels', {}).get(JAR_LABEL) == args.jar_sha256,
                'Local image lacks expected frozen JAR binding')
        create_attempted = True
        created = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
        require(created.returncode == 0 and re.fullmatch('[0-9a-f]{64}\n?', created.stdout), 'New container creation failed')
        cid = created.stdout.strip()
        result['isolation'] = verify_container(inspect(cid), cid, owner, args.image_id, args.jar_sha256)
        attached = subprocess.Popen(['docker', 'start', '--attach', cid], stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        output = bounded_output(attached, args.timeout_seconds + 15, captured)
        result['attachExitCode'] = attached.returncode
        require(output['stdout'].count(b'\n') == 1 and output['stdout'].endswith(b'\n'), 'Expected one complete worker JSON line')
        report = export_artifacts(json.loads(output['stdout']), args.output)
        (args.output / 'worker-result.json').write_text(json.dumps(report, indent=2) + '\n')
        require(report.get('jarSha256') == args.jar_sha256, 'Worker JAR binding differs')
        ended = inspect(cid)
        verify_container(ended, cid, owner, args.image_id, args.jar_sha256)
        require(ended.get('State', {}).get('Running') is False and not ended.get('State', {}).get('OOMKilled'),
                'Container remains running or was killed for memory')
        result['containerExit'] = ended.get('State')
        result['passed'] = report.get('passed') is True and attached.returncode == 0 and ended['State'].get('ExitCode') == 0
    except Exception as error:
        result['failure'] = str(error)
    finally:
        if attached is not None:
            try: save_attach_output(captured, args.output)
            except Exception as error:
                result['passed'] = False
                result['rawOutputFailure'] = str(error)[:2048]
        # Resolve only our fresh UUID name after partial create; never enumerate/reuse another container.
        try:
            owned = inspect(cid or ('rwxx-gui-smoke-' + owner)) if create_attempted else None
            if owned is not None:
                require(owned.get('Name') == '/rwxx-gui-smoke-' + owner
                        and owned.get('Config', {}).get('Labels', {}).get(OWNER) == owner
                        and re.fullmatch('[0-9a-f]{64}', owned.get('Id', '')), 'Refusing cleanup of unowned container')
                removed = subprocess.run(['docker', 'rm', '--force', owned['Id']], capture_output=True,
                                         text=True, timeout=15, check=False)
                result['cleanup'] = {'containerId': owned['Id'], 'removeExitCode': removed.returncode,
                                     'removed': removed.returncode == 0 and inspect(owned['Id']) is None}
            else: result['cleanup'] = {'removed': True, 'alreadyAbsent': True, 'creationAttempted': create_attempted}
        except Exception as error:
            result['cleanup'] = {'removed': False, 'failure': str(error)}
        if attached is not None and attached.poll() is None:
            attached.terminate()
            try: attached.wait(timeout=3)
            except subprocess.TimeoutExpired: attached.kill(); attached.wait(timeout=3)
        if not result['cleanup']['removed']: result['passed'] = False
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
