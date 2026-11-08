#!/usr/bin/env python3
"""Container-only Linux menu boot/pixel smoke; not a protocol or macOS acceptance.

All children are owned Popen instances. No host X11, audio, GPU or port is required.
The host controller independently verifies Docker isolation before starting this file.
"""
import argparse
import base64
import hashlib
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import resource
import signal
import subprocess
import sys
import time
import zipfile

JAR = Path('/opt/rwxx-gui-smoke/game.jar')
WORK = Path('/tmp/rwxx-gui-smoke')
SCREEN = (1920, 1080)
TITLE = 'RWXX isolated Linux menu smoke'
MAX_FILE = 8 * 1024 * 1024
DEFAULT_DOWN_TUNNELS = {'tunl0': 'ipip', 'gre0': 'gre', 'gretap0': 'ether', 'erspan0': 'ether',
                        'ip_vti0': 'ipip', 'ip6_vti0': 'tunnel6', 'sit0': 'sit',
                        'ip6tnl0': 'tunnel6', 'ip6gre0': 'gre6'}
ASSOCIATION_FIELDS = {'master', 'link', 'link_index', 'link_netnsid', 'link_netns', 'netns',
                      'parentdev', 'parentbus', 'ifalias'}
PREFS = ('[preferences]\nenableSounds = "false"\nmasterVolume = "0.0"\n'
         'musicVolume = "0.0"\ngameVolume = "0.0"\ninterfaceVolume = "0.0"\n'
         'showMainMenuBackgroundDemo = "false"\nslick2dFullScreen = "false"\n')


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def validate_network(links, addresses, ipv4_routes, ipv6_routes):
    """Allow only active loopback plus exact unassociated DOWN kernel defaults.

    Docker's network=none may still expose these default tunnel devices. No link,
    route or address is changed. A sysfs bonding_masters attribute is not a device.
    The independent controller still requires network=none and no capabilities.
    """
    def inventory(rows, with_addresses):
        require(isinstance(rows, list) and 1 <= len(rows) <= 10, 'Invalid interface inventory')
        result = {}
        indices = set()
        for item in rows:
            require(isinstance(item, dict), 'Malformed interface')
            name = item.get('ifname'); index = item.get('ifindex'); flags = item.get('flags')
            require(isinstance(name, str) and name not in result and type(index) is int and index > 0
                    and index not in indices, 'Malformed or duplicate interface identity')
            require(isinstance(flags, list) and all(isinstance(flag, str) for flag in flags)
                    and len(flags) == len(set(flags)), 'Invalid interface flags')
            require(all(item.get(key) is None for key in ASSOCIATION_FIELDS), 'Associated interface: ' + name)
            if name == 'lo':
                require(item.get('link_type') == 'loopback' and 'LOOPBACK' in flags and 'UP' in flags,
                        'lo is not active loopback')
                if with_addresses:
                    info = item.get('addr_info')
                    require(isinstance(info, list) and 1 <= len(info) <= 2, 'Invalid loopback addresses')
                    found = set()
                    for address in info:
                        require(isinstance(address, dict), 'Malformed loopback address')
                        key = (address.get('family'), address.get('local'), address.get('prefixlen'))
                        require(key in {('inet', '127.0.0.1', 8), ('inet6', '::1', 128)}
                                and key not in found and address.get('scope') == 'host'
                                and address.get('peer') is None, 'Unexpected loopback address')
                        found.add(key)
                    require(('inet', '127.0.0.1', 8) in found, 'Missing IPv4 loopback')
            else:
                require(name in DEFAULT_DOWN_TUNNELS and item.get('link_type') == DEFAULT_DOWN_TUNNELS[name]
                        and item.get('operstate') == 'DOWN'
                        and set(flags) <= {'NOARP', 'BROADCAST', 'MULTICAST'}
                        and item.get('qdisc') == 'noop', 'Unexpected or active non-loopback interface: ' + name)
                expected_address = ('00:00:00:00:00:00' if item['link_type'] == 'ether'
                                    else '::' if item['link_type'] in {'tunnel6', 'gre6'} else '0.0.0.0')
                require(item.get('address') == expected_address, 'Nondefault tunnel address: ' + name)
                if with_addresses: require(item.get('addr_info') == [], 'Tunnel has an assigned IP address: ' + name)
            result[name] = item; indices.add(index)
        require('lo' in result, 'Missing loopback interface')
        return result

    link_map = inventory(links, False); address_map = inventory(addresses, True)
    require(link_map.keys() == address_map.keys(), 'Link/address inventory changed')
    for name in link_map:
        require(all(link_map[name].get(key) == address_map[name].get(key)
                    for key in ('ifindex', 'link_type', 'operstate', 'flags', 'address')),
                'Link/address identity changed: ' + name)
    for version, routes in ((4, ipv4_routes), (6, ipv6_routes)):
        require(isinstance(routes, list) and len(routes) <= 32, 'Invalid route table')
        for route in routes:
            require(isinstance(route, dict) and route.get('dev') == 'lo'
                    and not any(route.get(key) is not None for key in ('gateway', 'via', 'nhid', 'encap', 'nexthops'))
                    and route.get('type', 'unicast') in {'local', 'broadcast', 'unicast'},
                    'Route is not exclusively local to loopback')
            destination = route.get('dst')
            require(isinstance(destination, str) and destination != 'default', 'Default or malformed route')
            network = ipaddress.ip_network(destination, strict=False)
            allowed = ipaddress.ip_network('127.0.0.0/8' if version == 4 else '::1/128')
            require(network.version == version and network.subnet_of(allowed), 'Non-loopback route destination')
            for key in ('prefsrc', 'src'):
                if route.get(key) is not None:
                    source = ipaddress.ip_address(route[key])
                    require(source.version == version and source in allowed, 'Non-loopback route source')
    return {'activeInterfaces': ['lo'], 'inactiveDefaultTunnels': sorted(set(link_map) - {'lo'}),
            'links': links, 'addresses': addresses, 'ipv4Routes': ipv4_routes, 'ipv6Routes': ipv6_routes}


def extract_assets(jar, output):
    """Only frozen bundled assets, never the previous image's game/assets."""
    output.mkdir()
    total = 0
    count = 0
    with zipfile.ZipFile(jar) as archive:
        names = set()
        for entry in archive.infolist():
            if not entry.filename.startswith('assets/') or entry.is_dir():
                continue
            relative = entry.filename[len('assets/'):]
            path = PurePosixPath(relative)
            require(relative and not path.is_absolute() and '..' not in path.parts and '\\' not in relative,
                    'Unsafe bundled asset path')
            require(relative not in names and (entry.external_attr >> 16) & 0o170000 != 0o120000,
                    'Duplicate or symbolic bundled asset')
            names.add(relative)
            total += entry.file_size
            count += 1
            require(total <= 256 * 1024 * 1024 and entry.file_size <= 64 * 1024 * 1024 and count <= 20000,
                    'Bundled assets exceed extraction limits')
            target = output.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            data = archive.read(entry)
            require(len(data) == entry.file_size, 'Asset length differs')
            target.write_bytes(data)
            target.chmod(0o444)
    require(count > 0 and (output / 'font/NotoSansCJKsc-Regular.json').is_file(), 'Bundled font assets missing')
    return {'files': count, 'bytes': total, 'origin': 'frozen jar assets/ only'}


def parse_geometry(text):
    result = {}
    for line in text.splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            if key in {'X', 'Y', 'WIDTH', 'HEIGHT'}:
                result[key] = int(value)
    require(set(result) == {'X', 'Y', 'WIDTH', 'HEIGHT'}, 'Missing window geometry')
    x, y, w, h = (result[k] for k in ('X', 'Y', 'WIDTH', 'HEIGHT'))
    require(x >= 0 and y >= 0 and 800 <= w <= SCREEN[0] and 600 <= h <= SCREEN[1]
            and x + w <= SCREEN[0] and y + h <= SCREEN[1], 'Window escapes container display bounds')
    return result


def screenshot(path, geometry):
    from PIL import ImageGrab, ImageStat, features
    require(features.check_feature('xcb'), 'Pillow lacks XCB screenshot support')
    image = ImageGrab.grab(xdisplay=':99').convert('RGB')
    require(image.size == SCREEN, 'Captured framebuffer dimensions differ')
    x, y, w, h = (geometry[k] for k in ('X', 'Y', 'WIDTH', 'HEIGHT'))
    # Exclude decoration/borders; a nonempty X root alone is not application evidence.
    crop = image.crop((x + 8, y + 32, x + w - 8, y + h - 8))
    colors = crop.getcolors(maxcolors=1_000_000)
    color_count = len(colors) if colors is not None else 1_000_001
    variance = sum(ImageStat.Stat(crop).var) / 3
    require(color_count >= 128 and variance >= 4, 'Application pixels are blank or nearly uniform')
    image.save(path, format='PNG')
    require(path.stat().st_size <= MAX_FILE, 'Screenshot exceeds byte limit')
    return {'framebuffer': list(image.size), 'geometry': geometry, 'rgbColorCount': color_count,
            'channelVarianceMean': variance, 'sha256': digest(path),
            'claim': 'Nonblank real X11 framebuffer, not semantic/menu correctness or GPU performance'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar-sha256', required=True)
    parser.add_argument('--seconds', type=int, default=35)
    parser.add_argument('--timeout-seconds', type=int, default=90)
    args = parser.parse_args()
    evidence = WORK / 'evidence'
    home = WORK / 'home'; state = WORK / 'state'; runtime = WORK / 'runtime'; natives = WORK / 'natives'
    environment = {'PATH': os.environ.get('PATH', '/opt/java/openjdk/bin:/usr/bin:/bin'),
        'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'HOME': str(home), 'DISPLAY': ':99',
        'XDG_DATA_HOME': str(home / 'data'), 'XDG_CACHE_HOME': str(home / 'cache'),
        'XDG_CONFIG_HOME': str(home / 'config'), 'XDG_RUNTIME_DIR': str(runtime),
        'LIBGL_ALWAYS_SOFTWARE': '1', 'GALLIUM_DRIVER': 'llvmpipe', 'LP_NUM_THREADS': '2', 'ALSOFT_DRIVERS': 'null',
        'RWX_WINDOW_TITLE': TITLE, 'RWX_WINDOW_WIDTH': '1280', 'RWX_WINDOW_HEIGHT': '800',
        'RWX_DESKTOP_TARGET_FPS': '30', 'RWX_DEBUG_AUTO_EXIT_SECONDS': str(args.seconds)}
    children = []
    logs = []
    created_evidence = False
    deadline = time.monotonic() + max(1, min(120, args.timeout_seconds))
    report = {'scope': 'Isolated Linux OpenGL/llvmpipe menu boot and pixel smoke only',
        'passed': False, 'jarSha256': args.jar_sha256, 'menuSemanticsVerified': False,
        'protocolAccepted': False, 'simulationTicks': {'applicable': False, 'reason': 'Menu-only smoke; no match'},
        'macNativeCoverage': False, 'preflightPassed': False, 'screenshots': [], 'children': []}

    def remaining():
        value = deadline - time.monotonic()
        require(value > 0, 'Worker absolute deadline exceeded')
        return value

    def checked(command, timeout=5, allowed=(0,)):
        result = subprocess.run(command, env=environment, cwd=WORK, capture_output=True,
                                timeout=min(timeout, remaining()), check=False)
        require(len(result.stdout) + len(result.stderr) <= MAX_FILE, 'Command output exceeds budget')
        require(result.returncode in allowed, 'Owned helper failed: ' + command[0])
        return result.stdout.decode('utf-8', 'replace')

    def launch(name, command):
        log = (evidence / (name + '.log')).open('xb')
        logs.append(log)
        # A runaway child log fails the run rather than exhausting its private tmpfs.
        def limits():
            resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE, MAX_FILE))
        process = subprocess.Popen(command, cwd=WORK, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, preexec_fn=limits)
        children.append((name, process))
        report['children'].append({'name': name, 'pid': process.pid, 'command': command})
        return process

    def interrupted(_signum, _frame):
        raise RuntimeError('Worker interrupted')

    try:
        require(re.fullmatch('[0-9a-f]{64}', args.jar_sha256), 'Invalid frozen JAR hash')
        require(25 <= args.seconds <= 60 and args.seconds + 20 <= args.timeout_seconds <= 120, 'Invalid time budget')
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        signal.signal(signal.SIGALRM, interrupted)
        signal.alarm(args.timeout_seconds)
        require(sys.platform == 'linux' and os.getuid() == os.geteuid() == 1000 and os.getgid() == 1000,
                'Worker requires isolated Linux uid/gid 1000')
        require(Path('/.dockerenv').is_file(), 'Worker requires a Docker container')
        require(not Path('/dev/snd').exists() and not Path('/dev/dri').exists(), 'Unexpected host audio/GPU device')
        require(digest(JAR) == args.jar_sha256, 'Frozen JAR bytes differ')
        os.umask(0o077)
        WORK.mkdir(exist_ok=False)
        evidence.mkdir(); created_evidence = True
        home.mkdir(); state.mkdir(); runtime.mkdir(mode=0o700); natives.mkdir()
        (state / 'preferences.toml').write_text(PREFS)
        report['networkIsolation'] = validate_network(
            json.loads(checked(['ip', '-j', 'link', 'show'])),
            json.loads(checked(['ip', '-j', 'address', 'show'])),
            json.loads(checked(['ip', '-j', '-4', 'route', 'show', 'table', 'all'])),
            json.loads(checked(['ip', '-j', '-6', 'route', 'show', 'table', 'all'])))
        report['preflightPassed'] = True
        report['softwareRendererThreadLimit'] = 2
        report['assets'] = extract_assets(JAR, WORK / 'assets')
        xvfb = launch('xvfb', ['Xvfb', ':99', '-screen', '0', '1920x1080x24', '-nolisten', 'tcp',
                              '-noreset', '+extension', 'GLX'])
        ready = False
        for _ in range(60):
            require(xvfb.poll() is None, 'Xvfb exited before readiness')
            result = checked(['xdpyinfo', '-display', ':99'], allowed=(0, 1))
            if 'dimensions:    1920x1080 pixels' in result:
                ready = True; break
            time.sleep(min(.1, remaining()))
        require(ready, 'Container display did not become ready')
        gl = checked(['glxinfo', '-B'], timeout=10)
        (evidence / 'glxinfo.txt').write_text(gl)
        require('llvmpipe' in gl.lower() and 'OpenGL core profile version string:' in gl,
                'Expected actual llvmpipe core OpenGL context')
        report['graphics'] = {'backendRequested': 'opengl', 'actualGlxInfo': gl,
                              'display': ':99', 'hostDisplayUsed': False}
        command = ['java', '-Xmx1536m', '-Djava.awt.headless=false', '-Drwx.kool.backend=opengl',
            '-Dlaunch.dir=' + str(state), '-Duser.home=' + str(home),
            '-Djava.io.tmpdir=' + str(WORK), '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + str(natives),
            '-Drwx.assetsDir=' + str(WORK / 'assets'), '-Drwx.log.dir=' + str(state / 'logs'),
            '-jar', str(JAR), '--screen=main-menu']
        java = launch('java', command)
        started = time.monotonic()
        windows = []
        while time.monotonic() - started < 20:
            require(java.poll() is None, 'Java exited before a visible application window')
            ids = checked(['xdotool', 'search', '--onlyvisible', '--pid', str(java.pid), '--name', '^' + TITLE + '$'],
                          allowed=(0, 1)).split()
            if ids:
                require(len(ids) == 1 and ids[0].isdigit(), 'Unexpected application window set')
                windows = ids; break
            time.sleep(min(.2, remaining()))
        require(len(windows) == 1, 'No visible owned application window')
        window = windows[0]
        checked(['xdotool', 'windowmove', window, '100', '100'])
        # Capture separated actual frames. A static menu need not change its pixels.
        for index, target_seconds in enumerate((15, 23)):
            while time.monotonic() - started < target_seconds:
                require(java.poll() is None, 'Java exited before screenshots')
                time.sleep(min(.2, remaining()))
            geometry = parse_geometry(checked(['xdotool', 'getwindowgeometry', '--shell', window]))
            report['screenshots'].append(screenshot(evidence / ('frame-%d.png' % index), geometry))
        code = java.wait(timeout=remaining())
        require(code == 0, 'Java did not shut down cleanly through its existing timed close hook')
        text = (evidence / 'java.log').read_text(errors='replace')
        require('Using Kool render backend: OpenGL' in text, 'Application did not confirm OpenGL backend')
        report['javaExitCode'] = code
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)[:2048]
    finally:
        signal.alarm(0)
        cleanup = []
        for name, process in reversed(children):
            terminated = False; killed = False
            if process.poll() is None:
                process.terminate(); terminated = True
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill(); killed = True; process.wait(timeout=3)
            cleanup.append({'name': name, 'pid': process.pid, 'exitCode': process.returncode,
                            'terminated': terminated, 'killed': killed})
        for log in logs: log.close()
        report['cleanup'] = cleanup
    # The controller exports only these bounded regular evidence files, never profiles or tokens.
    artifacts = []
    for path in sorted(evidence.iterdir()) if created_evidence else []:
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_FILE,
                'Invalid evidence artifact')
        data = path.read_bytes()
        artifacts.append({'file': path.name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                          'base64': base64.b64encode(data).decode('ascii')})
    report['artifacts'] = artifacts
    print(json.dumps(report, separators=(',', ':')), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
