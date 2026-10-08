import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from frozen_benchmark_tooling import fingerprint, freeze_tooling, frozen_environment


class FrozenBenchmarkToolingTest(unittest.TestCase):
    def test_source_edits_cannot_change_the_snapshot_but_snapshot_edits_change_its_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary) / 'project'
            source = project / 'desktop/tools'
            source.mkdir(parents=True)
            (source / 'runner.py').write_text('version = 1\n')
            (source / 'original_probe').mkdir()
            (source / 'original_probe/Agent.java').write_text('class Agent {}\n')
            output = project / 'evidence'
            output.mkdir()
            snapshot, digest = freeze_tooling(project, output)
            (source / 'runner.py').write_text('version = 2\n')
            self.assertEqual(fingerprint(snapshot)[0], digest)
            self.assertNotEqual(fingerprint(source)[0], digest)
            self.assertTrue((snapshot / 'original_probe/Agent.java').is_file())
            (snapshot / 'runner.py').write_text('version = 3\n')
            self.assertNotEqual(fingerprint(snapshot)[0], digest)

    def test_frozen_imports_keep_real_asset_root_without_loading_live_tool_modules(self):
        project = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            snapshot, digest = freeze_tooling(project, output)
            environment = frozen_environment(project, snapshot, digest, os.environ)
            result = subprocess.run([sys.executable, '-c',
                'import json,map_pan_comparison as m,vulkan_native_matrix as v; '
                'print(json.dumps([str(m.PROJECT),str(v.PROJECT),m.__file__,v.__file__]))'],
                cwd=snapshot, env=environment, check=True, capture_output=True, text=True)
            left, right, map_file, vulkan_file = json.loads(result.stdout)
            self.assertEqual(Path(left), project)
            self.assertEqual(Path(right), project)
            self.assertEqual(Path(map_file).parent, snapshot)
            self.assertEqual(Path(vulkan_file).parent, snapshot)


if __name__ == '__main__':
    unittest.main()
