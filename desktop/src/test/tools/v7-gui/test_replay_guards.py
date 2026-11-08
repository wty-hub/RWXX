"""Pure Python guards only. These tests never invoke Docker, Java, X11 or a display."""
import base64
import copy
import hashlib
import io
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

import xvfb_replay_controller as controller
import gui_common as worker

OWNER = '1' * 32
CID = '2' * 64
IMAGE = 'sha256:' + '3' * 64
JAR = '4' * 64


def row():
    return {'Id': CID, 'Name': '/rwxx-v7-gui-replay-' + OWNER, 'Image': IMAGE,
        'Config': {'Labels': {controller.OWNER: OWNER, controller.JAR_LABEL: JAR, controller.RECORD_LABELS[0]: '4' * 64, controller.RECORD_LABELS[1]: '5' * 64},
                   'User': '1000:1000', 'Entrypoint': ['/usr/bin/python3'], 'Tty': False, 'OpenStdin': False},
        'HostConfig': {'NetworkMode': 'none', 'ReadonlyRootfs': True, 'Privileged': False,
            'CapDrop': ['ALL'], 'CapAdd': [], 'Binds': [], 'Devices': [], 'DeviceRequests': [],
            'VolumesFrom': [], 'PortBindings': {}, 'PidMode': '', 'IpcMode': 'private', 'UTSMode': '',
            'Tmpfs': controller.TMPFS, 'Memory': 3 * 1024 ** 3, 'NanoCpus': 2_000_000_000,
            'PidsLimit': 512, 'ShmSize': 256 * 1024 ** 2, 'SecurityOpt': ['no-new-privileges:true']},
        'Mounts': []}


class Guards(unittest.TestCase):
    def observed_network(self):
        probe = json.loads((Path(__file__).parent / 'observed-network.json').read_text())
        return json.loads(probe['links']), json.loads(probe['addresses'])

    def test_actual_default_down_tunnels_are_not_active_networks(self):
        links, addresses = self.observed_network()
        result = worker.validate_network(links, addresses, [], [])
        self.assertEqual(result['activeInterfaces'], ['lo'])
        self.assertEqual(set(result['inactiveDefaultTunnels']), set(worker.DEFAULT_DOWN_TUNNELS))
        self.assertNotIn('bonding_masters', result['inactiveDefaultTunnels'])

    def test_only_loopback_and_local_routes_are_permitted(self):
        links, addresses = self.observed_network()
        routes4 = [{'type': 'local', 'dst': '127.0.0.0/8', 'dev': 'lo', 'prefsrc': '127.0.0.1'},
                   {'type': 'broadcast', 'dst': '127.255.255.255', 'dev': 'lo', 'prefsrc': '127.0.0.1'}]
        routes6 = [{'type': 'local', 'dst': '::1', 'dev': 'lo'}]
        worker.validate_network(links, addresses, routes4, routes6)
        worker.validate_network(links[:1], addresses[:1], routes4, routes6)
        for route in [{'dst': 'default', 'dev': 'lo'}, {'dst': '10.0.0.0/8', 'dev': 'lo'},
                      {'dst': '127.0.0.1', 'dev': 'eth0'}, {'dst': '127.0.0.1', 'dev': 'lo', 'gateway': '1.2.3.4'},
                      {'dst': '127.0.0.1', 'dev': 'lo', 'nexthops': []}]:
            with self.subTest(route=route), self.assertRaises(RuntimeError):
                worker.validate_network(links, addresses, [route], [])
        with self.assertRaises(RuntimeError):
            worker.validate_network(links, addresses, [], [{'dst': '::/0', 'dev': 'lo'}])

    def test_down_unknown_type_association_and_up_tunnel_are_rejected(self):
        links, addresses = self.observed_network()
        changes = [{'ifname': 'eth0'}, {'ifname': 'veth0'}, {'link_type': 'veth'},
                   {'flags': ['NOARP', 'UP']}, {'flags': ['NOARP', 'LOWER_UP']}, {'operstate': 'UP'},
                   {'master': 'bridge0'}, {'link': 'eth0'}, {'link_index': 99}, {'link_netnsid': 1},
                   {'link_netns': 'host'}, {'netns': 'host'}, {'parentdev': 'eth0'},
                   {'address': '192.0.2.1'}, {'qdisc': 'fq_codel'}]
        for update in changes:
            a = copy.deepcopy(links); b = copy.deepcopy(addresses)
            a[1].update(update); b[1].update(update)
            with self.subTest(update=update), self.assertRaises(RuntimeError):
                worker.validate_network(a, b, [], [])
        b = copy.deepcopy(addresses); b[1]['addr_info'] = [{'family': 'inet', 'local': '192.0.2.1'}]
        with self.assertRaises(RuntimeError): worker.validate_network(links, b, [], [])

    def test_network_inventory_must_be_consistent_and_nonduplicate(self):
        links, addresses = self.observed_network()
        for a, b in [(links, addresses[:-1]), (links + [links[1]], addresses), ([], [])]:
            with self.assertRaises(RuntimeError): worker.validate_network(a, b, [], [])
        b = copy.deepcopy(addresses); b[0]['addr_info'][0]['local'] = '10.0.0.1'
        with self.assertRaises(RuntimeError): worker.validate_network(links, b, [], [])

    def test_environment_preflight_failure_is_bounded_json_without_children(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as temp, patch.object(worker.sys, 'platform', 'not-linux'), \
                patch.object(worker.sys, 'argv', ['worker', '--jar-sha256', JAR]), \
                patch.object(worker.sys, 'stdout', output), patch.object(worker, 'WORK', Path(temp) / 'new'), \
                patch.object(worker.signal, 'signal'), patch.object(worker.signal, 'alarm'), \
                patch.object(worker.subprocess, 'Popen') as popen, patch.object(worker.subprocess, 'run') as run:
            self.assertEqual(worker.main(), 1)
            popen.assert_not_called(); run.assert_not_called()
        report = json.loads(output.getvalue())
        self.assertFalse(report['passed']); self.assertFalse(report['preflightPassed'])
        self.assertEqual(report['children'], []); self.assertEqual(report['artifacts'], [])
        self.assertIn('Linux uid/gid', report['failure'])
        self.assertLess(len(output.getvalue()), 4096)

    def test_malformed_worker_stdout_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            output = {'stdout': b'not JSON\npartial', 'stderr': b'preflight failed'}
            controller.save_attach_output(output, Path(temp))
            self.assertEqual((Path(temp) / 'attach.stdout.log').read_bytes(), output['stdout'])
            self.assertEqual((Path(temp) / 'attach.stderr.log').read_bytes(), output['stderr'])

    def test_explicit_jar_bytes_bind_any_frozen_version(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'v26.jar'; path.write_bytes(b'frozen version 26')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            result = controller.verify_jar(path, digest)
            self.assertEqual(result['sha256'], digest)
            self.assertFalse(result['providedByHostMount'])
            path.write_bytes(b'changed')
            with self.assertRaises(RuntimeError): controller.verify_jar(path, digest)

    def test_create_has_no_mount_ports_devices_or_extra_capability(self):
        command = controller.create_command(IMAGE, OWNER, JAR, 60, 240, ['4' * 64, '5' * 64])
        self.assertIn('--read-only', command)
        self.assertEqual(command[command.index('--network') + 1], 'none')
        self.assertEqual(command[command.index('--user') + 1], '1000:1000')
        self.assertEqual(command[command.index('--cpus') + 1], '2.0')
        self.assertEqual(command[command.index('--tmpfs') + 1], '/tmp:' + controller.TMPFS['/tmp'])
        for forbidden in ('--mount', '-v', '--volume', '--device', '--privileged', '--cap-add', '-p', '--publish'):
            self.assertNotIn(forbidden, command)

    def test_no_tag_or_unbounded_run(self):
        for image, seconds, timeout in [('some:latest', 35, 90), (IMAGE, 5, 90), (IMAGE, 60, 70), (IMAGE, 35, 121)]:
            with self.assertRaises(RuntimeError): controller.create_command(image, OWNER, JAR, seconds, timeout, ['4' * 64, '5' * 64])

    def test_exact_isolation(self):
        controller.verify_container(row(), CID, OWNER, IMAGE, JAR, ['4' * 64, '5' * 64])
        cases = [('NetworkMode', 'host'), ('ReadonlyRootfs', False), ('CapAdd', ['NET_ADMIN']),
                 ('Devices', [{'PathOnHost': '/dev/dri'}]), ('Binds', ['/tmp/.X11-unix:/tmp/.X11-unix']),
                 ('PortBindings', {'80/tcp': []}), ('PidMode', 'host'), ('IpcMode', 'host'),
                 ('Memory', 0), ('Tmpfs', {'/tmp': 'rw'}), ('Privileged', True)]
        for key, value in cases:
            item = copy.deepcopy(row()); item['HostConfig'][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                controller.verify_container(item, CID, OWNER, IMAGE, JAR, ['4' * 64, '5' * 64])

    def test_identity_and_user_do_not_fall_back(self):
        for key, value in [('Id', '9' * 64), ('Name', '/unrelated'), ('Image', 'sha256:' + '9' * 64)]:
            item = row(); item[key] = value
            with self.assertRaises(RuntimeError): controller.verify_container(item, CID, OWNER, IMAGE, JAR, ['4' * 64, '5' * 64])
        item = row(); item['Config']['User'] = 'root'
        with self.assertRaises(RuntimeError): controller.verify_container(item, CID, OWNER, IMAGE, JAR, ['4' * 64, '5' * 64])

    def test_geometry_requires_real_fully_bounded_window(self):
        self.assertEqual(worker.parse_geometry('WINDOW=42\nX=100\nY=100\nWIDTH=1280\nHEIGHT=800\n')['WIDTH'], 1280)
        for text in ['X=-1\nY=1\nWIDTH=1280\nHEIGHT=800', 'X=1000\nY=1\nWIDTH=1280\nHEIGHT=800',
                     'X=1\nY=1\nWIDTH=100\nHEIGHT=100', 'X=1\nY=1\nWIDTH=1280']:
            with self.assertRaises(RuntimeError): worker.parse_geometry(text)

    def test_artifact_hash_and_closed_path_list(self):
        data = b'actual bytes'
        item = {'file': 'inspect-0.log', 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                'base64': base64.b64encode(data).decode()}
        with tempfile.TemporaryDirectory() as temp:
            result = controller.export_artifacts({'passed': False, 'artifacts': [item]}, Path(temp))
            self.assertEqual((Path(temp) / 'inspect-0.log').read_bytes(), data)
            self.assertNotIn('base64', result['artifacts'][0])
        for update in [{'file': '../secret'}, {'sha256': '0' * 64}, {'bytes': len(data) + 1}]:
            with tempfile.TemporaryDirectory() as temp, self.assertRaises(RuntimeError):
                controller.export_artifacts({'artifacts': [dict(item, **update)]}, Path(temp))

    def test_no_pass_without_pixels(self):
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(RuntimeError):
            controller.export_artifacts({'passed': True, 'artifacts': [], 'javaExitCode': 0}, Path(temp))

    def test_assets_from_frozen_jar_only(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / 'game.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('assets/font/NotoSansCJKsc-Regular.json', '{}')
                archive.writestr('not-an-asset', 'omitted')
            result = worker.extract_assets(jar, Path(temp) / 'assets')
            self.assertEqual(result['files'], 1)
            self.assertFalse((Path(temp) / 'assets/not-an-asset').exists())

    def test_bad_asset_paths_are_rejected(self):
        for name in ('assets/../escape', 'assets//absolute', 'assets/a\\escape'):
            with tempfile.TemporaryDirectory() as temp:
                jar = Path(temp) / 'game.jar'
                with zipfile.ZipFile(jar, 'w') as archive: archive.writestr(name, 'bad')
                with self.assertRaises(RuntimeError): worker.extract_assets(jar, Path(temp) / 'assets')


if __name__ == '__main__': unittest.main()
