from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile

from check_desktop_natives import PLATFORMS, check_archive, required_entries


class DesktopNativesTest(unittest.TestCase):
    def header(self, platform):
        _, kind, machine = PLATFORMS[platform]
        header = bytearray(128)
        if kind == 'elf':
            header[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<H', header, 18, machine)
        elif kind == 'macho':
            header[:4] = b'\xcf\xfa\xed\xfe'
            struct.pack_into('<I', header, 4, machine)
        else:
            header[:2] = b'MZ'
            struct.pack_into('<I', header, 60, 64)
            header[64:68] = b'PE\0\0'
            struct.pack_into('<H', header, 68, machine)
        return header

    def archive(self, directory, omit=(), replace=None, duplicate=None):
        path = Path(directory) / 'fixture.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            for platform in PLATFORMS:
                for module, entry in required_entries(platform).items():
                    if (platform, module) in omit:
                        continue
                    data = replace.get((platform, module), self.header(platform)) if replace else self.header(platform)
                    archive.writestr(entry, data)
                    if (platform, module) == duplicate:
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore', UserWarning)
                            archive.writestr(entry, data)
        return path

    def test_all_five_packaged_architectures_pass_without_claiming_runtime_support(self):
        with tempfile.TemporaryDirectory() as directory:
            result = check_archive(self.archive(directory))
        self.assertTrue(result['valid'], result['errors'])
        self.assertEqual(5, len(result['platforms']))
        self.assertTrue(result['staticOnly'])
        self.assertFalse(result['nativeLibrariesLoaded'])
        self.assertFalse(result['runtimeCompatibilityVerified'])

    def test_original_arm64_basic_only_packaging_failure_is_detected(self):
        missing = [('linux-arm64', module) for module in ('lwjgl-jemalloc', 'lwjgl-vma', 'lwjgl-shaderc',
                                                        'lwjgl-nfd', 'lwjgl-stb', 'lwjgl-msdfgen')]
        with tempfile.TemporaryDirectory() as directory:
            result = check_archive(self.archive(directory, omit=missing))
        self.assertFalse(result['valid'])
        self.assertEqual(6, len(result['errors']))
        self.assertTrue(all('linux/arm64/' in error for error in result['errors']))

    def test_correct_native_path_cannot_hide_the_wrong_architecture(self):
        replacement = {('linux-arm64', 'lwjgl-msdfgen'): self.header('linux-x64'),
                       ('windows-x64', 'lwjgl-vma'): self.header('linux-x64'),
                       ('macos-arm64', 'lwjgl-shaderc'): self.header('macos-x64')}
        with tempfile.TemporaryDirectory() as directory:
            result = check_archive(self.archive(directory, replace=replacement))
        self.assertFalse(result['valid'])
        self.assertEqual(3, len(result['errors']))

    def test_moltenvk_is_required_only_for_the_two_macos_architectures(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, omit=[('macos-arm64', 'lwjgl-vulkan-moltenvk')])
            result = check_archive(path)
            linux = check_archive(path, ['linux-arm64'])
        self.assertFalse(result['valid'])
        self.assertTrue(linux['valid'])
        self.assertEqual(1, len(result['errors']))
        self.assertIn('libMoltenVK.dylib', result['errors'][0])

    def test_empty_and_duplicate_entries_cannot_pass_as_packaged_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            result = check_archive(self.archive(directory,
                replace={('linux-arm64', 'lwjgl-nfd'): b''}, duplicate=('windows-x64', 'lwjgl-stb')))
        self.assertFalse(result['valid'])
        self.assertEqual(2, len(result['errors']))


if __name__ == '__main__':
    unittest.main()
