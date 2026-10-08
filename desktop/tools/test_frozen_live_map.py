import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import map_pan_live


class FrozenLiveMapTest(unittest.TestCase):
    def test_embedded_map_and_asset_inventory_are_isolated_and_fingerprinted(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            source = project / 'europe.tmx'; source.write_bytes(b'<map width="320" height="200"/>')
            original = project / 'assets/maps/skirmish'; original.mkdir(parents=True)
            original.joinpath('original.tmx').write_bytes(b'original map')
            project.joinpath('assets/tilesets').mkdir()
            project.joinpath('assets/tilesets/ground.png').write_bytes(b'embedded asset fixture')
            output = project / 'measurement'; output.mkdir()
            with patch.object(map_pan_live, 'PROJECT', project):
                assets, map_path, evidence = map_pan_live.freeze_map(source, output)
            self.assertEqual((assets / map_path).read_bytes(), source.read_bytes())
            self.assertEqual(evidence['sha256'], hashlib.sha256(source.read_bytes()).hexdigest())
            manifest = json.loads((output / 'asset-manifest.json').read_text())
            self.assertEqual(evidence['assetInventorySha256'], hashlib.sha256(json.dumps(manifest,
                sort_keys=True, separators=(',', ':')).encode()).hexdigest())
            self.assertFalse((project / 'assets' / map_path).exists())
            (assets / 'tilesets/ground.png').write_bytes(b'changed isolated copy')
            self.assertEqual(project.joinpath('assets/tilesets/ground.png').read_bytes(), b'embedded asset fixture')

    def test_default_map_does_not_create_asset_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            with patch.object(map_pan_live, 'PROJECT', project):
                assets, path, evidence = map_pan_live.freeze_map(None, project / 'unused-output')
            self.assertEqual(assets, project / 'assets')
            self.assertEqual(path, 'maps/skirmish/[p8]Interlocked Large (8p).tmx')
            self.assertIsNone(evidence)
            self.assertFalse((project / 'unused-output').exists())


if __name__ == '__main__':
    unittest.main()
