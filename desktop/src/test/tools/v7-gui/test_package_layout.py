"""Offline delivery checks; no Docker/JVM/display or subprocess execution."""
import ast
import hashlib
import json
from pathlib import Path
import shlex
import unittest

ROOT = Path(__file__).parent
CONTROLLERS = ('xvfb_gui_controller.py', 'xvfb_v7_controller.py',
               'xvfb_replay_controller.py', 'xvfb_history_controller.py')
WORKERS = ('gui_common.py', 'xvfb_v7_worker.py', 'xvfb_replay_worker.py', 'xvfb_history_worker.py')
RECIPES = ('Dockerfile.base', 'Dockerfile.menu', 'Dockerfile.live', 'Dockerfile.replay', 'Dockerfile.history')


class PackageLayout(unittest.TestCase):
    def test_all_runtime_sources_are_fingerprint_bound_without_historical_paths(self):
        manifest = json.loads((ROOT / 'source-manifest.json').read_text())
        rows = manifest['files']
        names = [row['file'] for row in rows]
        self.assertEqual(set(names), set(CONTROLLERS + WORKERS + RECIPES + ('observed-network.json',)))
        self.assertEqual(len(names), len(set(names)))
        for row in rows:
            with self.subTest(file=row['file']):
                self.assertEqual(Path(row['file']).name, row['file'])
                data = (ROOT / row['file']).read_bytes()
                self.assertEqual(len(data), row['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), row['sha256'])

    def test_one_common_module_also_supplies_the_unchanged_menu_worker(self):
        self.assertFalse((ROOT / 'xvfb_gui_worker.py').exists())
        self.assertEqual(hashlib.sha256((ROOT / 'gui_common.py').read_bytes()).hexdigest(),
                         '5e7114cb5587da6567b3527d65482f9d9da8254375cd9435c0784863e36be4d9')
        menu = (ROOT / 'Dockerfile.menu').read_text()
        self.assertIn('COPY gui_common.py /opt/rwxx-gui-smoke/worker.py', menu)
        self.assertIn('/opt/rwxx-gui-smoke/worker.py', menu.split('ENTRYPOINT', 1)[1])
        for filename in WORKERS[1:]:
            tree = ast.parse((ROOT / filename).read_text())
            self.assertTrue(any(isinstance(node, ast.Import) and any(
                alias.name == 'gui_common' for alias in node.names) for node in tree.body), filename)

    def test_image_inputs_resolve_in_a_fresh_minimal_context(self):
        inputs = {'game.jar', 'seat-0.rwxr', 'seat-1.rwxr'}
        expected = {
            'Dockerfile.menu': {'game.jar', 'gui_common.py'},
            'Dockerfile.live': {'game.jar', 'gui_common.py', 'xvfb_v7_worker.py'},
            'Dockerfile.replay': {'game.jar', 'gui_common.py', 'xvfb_replay_worker.py', 'seat-0.rwxr', 'seat-1.rwxr'},
            'Dockerfile.history': {'game.jar', 'gui_common.py', 'xvfb_history_worker.py'},
        }
        for recipe, names in expected.items():
            copied = set()
            for line in (ROOT / recipe).read_text().splitlines():
                if not line.startswith('COPY '): continue
                tokens = shlex.split(line)
                self.assertTrue(tokens[-1].startswith('/opt/'))
                copied.update(tokens[1:-1])
                for name in tokens[1:-1]:
                    self.assertEqual(Path(name).name, name)
                    self.assertTrue(name in inputs or (ROOT / name).is_file(), (recipe, name))
            self.assertEqual(copied, names, recipe)
            text = (ROOT / recipe).read_text()
            self.assertIn('ARG GAME_JAR_SHA256', text)
            self.assertIn('sha256sum -c -', text)
            self.assertIn('USER 1000:1000', text)

    def test_standalone_base_has_the_reviewed_dependencies_without_baked_game(self):
        text = (ROOT / 'Dockerfile.base').read_text()
        self.assertIn('ARG BASE_IMAGE=eclipse-temurin:25-jre-noble', text)
        for item in ('python3', 'iproute2', 'xvfb', 'xauth', 'x11-apps', 'x11-utils', 'xdotool',
                     'mesa-utils', 'libgl1-mesa-dri', 'libglx-mesa0', 'libegl1', 'mesa-vulkan-drivers',
                     'libvulkan1', 'libxtst6', 'libxi6', 'libxrandr2', 'libxinerama1', 'libxcursor1',
                     'libxkbcommon0', 'libgtk-3-0t64', 'libasound2t64', 'libopenal1', 'fonts-noto-cjk',
                     'fonts-noto-color-emoji', 'python3-pil'):
            self.assertIn(item, text.split(), item)
        self.assertFalse(any(line.startswith(('COPY ', 'ADD ')) for line in text.splitlines()))
        self.assertNotIn('rwxx-netem', text)
        self.assertIn('USER 1000:1000', text)

    def test_readme_replay_names_match_real_live_exports(self):
        import xvfb_v7_controller as live
        readme = (ROOT / 'README.md').read_text()
        for seat in range(2):
            name = f'client-{seat}.rwxr'
            self.assertIn(name, live.ARTIFACTS)
            self.assertIn(f'live-result/{name}', readme)
        self.assertIn('same immutable image', readme)
        self.assertIn('has not been built', readme)


if __name__ == '__main__':
    unittest.main()
