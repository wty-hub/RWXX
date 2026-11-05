import unittest
from pathlib import Path
from unittest.mock import patch

from analyze_vulkan_matrix import presentation_window
import vulkan_native_matrix


class JavaCommandTest(unittest.TestCase):
    def test_macos_export_preserves_other_arguments_and_optional_native_paths(self):
        export = "--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED"
        natives = Path("fixture-natives")
        for native_paths in (False, True):
            expected = ["unused-java", "-Dorg.lwjgl.opengl.contextAPI=native", "-Dorg.lwjgl.system.stackSize=512",
                        "-Drwx.desktop.renderer=kool", "--enable-native-access=ALL-UNNAMED",
                        "--sun-misc-unsafe-memory-access=allow"]
            if native_paths:
                expected.extend([f"-Djava.library.path={natives}", f"-Dorg.lwjgl.librarypath={natives}",
                                 f"-Drwx.slick.nativesDir={natives}"])
            for package in ("java.desktop/sun.awt", "java.desktop/sun.awt.im", "java.base/java.lang"):
                expected.extend(["--add-opens", f"{package}=ALL-UNNAMED"])
            expected.extend(["-cp", "fixture.jar", "io.github.rwx.KoolDesktopMain",
                             "--screen=battleroom", "--auto-start-battleroom"])
            for platform in ("darwin", "linux", "win32"):
                with self.subTest(platform=platform, native_paths=native_paths), \
                        patch.object(vulkan_native_matrix.sys, "platform", platform), \
                        patch.object(Path, "is_dir", return_value=native_paths):
                    command = vulkan_native_matrix.java_command("unused-java", Path("fixture.jar"), natives)
                self.assertEqual(command.count(export), 1 if platform == "darwin" else 0)
                self.assertEqual([argument for argument in command if argument != export], expected)


class PresentationWindowTest(unittest.TestCase):
    def test_repeat_at_window_boundary_uses_previous_accepted_frame(self):
        trace = [(0, 1, 10), (1_000_000_000, 1, 10), (2_000_000_000, 1, 11),
                 (3_000_000_000, 2, 11), (4_000_000_000, 2, 11)]
        window = presentation_window(trace, {"sampleStartNanos": 1_000_000_000, "sampleEndNanos": 4_000_000_000})
        self.assertEqual(window["acceptedPresentFps"], 4 / 3)
        self.assertEqual(window["freshSnapshotHz"], 2 / 3)
        self.assertEqual(window["repeatRatio"], .5)
        self.assertEqual(window["presentIntervalP95Ms"], 1000)

    def test_bad_window_or_unordered_trace_is_rejected(self):
        with self.assertRaises(ValueError):
            presentation_window([], {"sampleStartNanos": 10, "sampleEndNanos": 10})
        with self.assertRaises(ValueError):
            presentation_window([(20, 1, 1), (10, 1, 2)], {"sampleStartNanos": 0, "sampleEndNanos": 30})

    def test_empty_window_has_no_fabricated_freshness_or_percentile(self):
        window = presentation_window([(0, 1, 1)], {"sampleStartNanos": 1, "sampleEndNanos": 100})
        self.assertEqual(window["acceptedPresentFps"], 0)
        self.assertEqual(window["freshSnapshotHz"], 0)
        self.assertIsNone(window["repeatRatio"])
        self.assertIsNone(window["presentIntervalP99Ms"])


if __name__ == "__main__":
    unittest.main()
