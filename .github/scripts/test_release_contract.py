import unittest
from pathlib import Path

from check_release_contract import check


class ReleaseContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[2]
        cls.sources = [(root / path).read_text(encoding='utf-8') for path in (
            'gradle.properties', 'desktop/build.gradle.kts', '.github/workflows/ci.yml',
            '.github/scripts/test-windows-bundled-runtime.ps1')]

    def mutated(self, source, before, after):
        texts = self.sources.copy()
        self.assertIn(before, texts[source], 'mutation must alter its intended contract')
        texts[source] = texts[source].replace(before, after)
        return check(*texts)

    def test_checked_in_contract_matches_without_claiming_native_or_signed_release_verification(self):
        result = check(*self.sources)
        self.assertTrue(result['valid'], result['errors'])
        self.assertTrue(result['staticOnly'])
        self.assertFalse(result['windowsRuntimeVerified'])
        self.assertFalse(result['signedReleaseVerified'])
        self.assertFalse(result['crossPlatformBuildVerified'])

    def test_property_rename_cannot_silently_leave_workflow_and_launcher_behind(self):
        result = self.mutated(0, 'appName=RWXX', 'appName=RenamedGame')
        self.assertFalse(result['valid'])
        self.assertTrue(any('upload path' in error for error in result['errors']))
        self.assertTrue(any('AppName default' in error for error in result['errors']))

    def test_original_desktop_and_universal_upload_mismatches_are_rejected(self):
        for path in ('desktop/build/distributions/RWXX-', 'desktop/build/libs/RWXX-'):
            result = self.mutated(2, path, path.replace('RWXX-', 'RWX-'))
            self.assertFalse(result['valid'])
            self.assertTrue(any('upload path' in error for error in result['errors']))

    def test_correct_image_directory_does_not_hide_a_wrong_executable(self):
        result = self.mutated(3, 'Join-Path $resolvedImage "${AppName}.exe"', "Join-Path $resolvedImage 'RWX.exe'")
        self.assertFalse(result['valid'])
        self.assertTrue(any('runtime paths' in error for error in result['errors']))

    def test_wrong_image_directory_and_wrong_release_download_pattern_are_rejected(self):
        for before, after in (('windows-x64/RWXX', 'windows-x64/RWX'), ('pattern: RWXX-*', 'pattern: RWX-*')):
            self.assertFalse(self.mutated(2, before, after)['valid'])

    def test_version_producer_and_consumer_must_change_together(self):
        self.assertFalse(self.mutated(2, '-Pversion="$RWXX_VERSION"', '-Pversion="$RWX_VERSION"')['valid'])

    def test_universal_native_content_guard_cannot_be_silently_removed(self):
        self.assertFalse(self.mutated(2, 'python3 .github/scripts/check_desktop_natives.py', 'echo skip-native-check')['valid'])

    def test_literal_signing_value_is_rejected_without_returning_the_value(self):
        sentinel = 'DO_NOT_INCLUDE_THIS_FAKE_SECRET_IN_ERRORS'
        result = self.mutated(2, '${{ secrets.ANDROID_KEYSTORE_PASSWORD }}', sentinel)
        self.assertFalse(result['valid'])
        self.assertNotIn(sentinel, str(result))

    def test_secret_logging_shell_tracing_and_missing_cleanup_are_rejected(self):
        for before, after in (
            ('test -n "$SIGNING_KEYSTORE_PASSWORD"', 'echo "$SIGNING_KEYSTORE_PASSWORD"'),
            ('test -n "$SIGNING_KEYSTORE_PASSWORD"', 'set -x\n          test -n "$SIGNING_KEYSTORE_PASSWORD"'),
            ('if: always()', 'if: success()'),
        ):
            self.assertFalse(self.mutated(2, before, after)['valid'])

    def test_gradle_archive_and_windows_producer_names_are_part_of_the_contract(self):
        for before, after in (('archiveBaseName = appName', 'archiveBaseName = "RWX"'),
                              ('outfile.set("$appName.exe")', 'outfile.set("RWX.exe")')):
            self.assertFalse(self.mutated(1, before, after)['valid'])


if __name__ == '__main__':
    unittest.main()
