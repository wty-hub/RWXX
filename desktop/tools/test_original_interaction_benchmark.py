import contextlib
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

import original_interaction_benchmark as benchmark


class OriginalLaunchPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.original = self.directory / 'original'
        (self.original / 'replays').mkdir(parents=True)
        (self.original / 'game-lib.jar').write_bytes(b'pinned-original-core')
        (self.original / 'replays/europe.replay').write_bytes(b'pinned-replay')
        self.replay = self.directory / 'requested.replay'
        self.replay.write_bytes(b'pinned-replay')
        self.runtime_hash = benchmark.digest(self.original / 'game-lib.jar')

    def args(self, output, *extra):
        return ['--output', str(output), '--camera-mode', 'pan-zoom', '--replay', str(self.replay), *extra]

    @staticmethod
    def fake_probe(output, javac):
        probe = output / 'original-probe.jar'
        probe.write_bytes(b'built-probe')
        return probe, {'Probe.java': 'source-hash'}

    def test_windows_defaults_keep_existing_launch_flags_and_paths(self):
        output = self.directory / 'windows-run'
        compiler_home = self.directory / 'windows-jdk'
        with mock.patch.object(benchmark, 'ORIGINAL', self.original), \
                mock.patch.object(benchmark, 'JAVA_HOME', compiler_home), \
                mock.patch.object(benchmark, 'EXPECTED_HASH', self.runtime_hash), \
                mock.patch.object(benchmark.sys, 'platform', 'win32'), \
                mock.patch.object(benchmark.os, 'pathsep', ';'), \
                mock.patch.dict(benchmark.os.environ, {'RWX_BENCHMARK_TOOLING_SHA256': 'tooling'}), \
                mock.patch.object(benchmark, 'fingerprint', return_value=('tooling', {})), \
                mock.patch.object(benchmark, 'build_probe', side_effect=self.fake_probe) as build, \
                mock.patch.object(benchmark.subprocess, 'CREATE_NO_WINDOW', 0x08000000, create=True), \
                mock.patch.object(benchmark.subprocess, 'Popen') as popen, \
                contextlib.redirect_stdout(io.StringIO()):
            # Stop immediately after the mocked launch; no evidence or acceptance checks are bypassed.
            popen.return_value.wait.return_value = 1
            with self.assertRaisesRegex(RuntimeError, 'original benchmark exited 1'):
                benchmark.main(self.args(output))
            build.assert_called_once_with(output, compiler_home / 'bin/javac.exe')
            argv = popen.call_args.args[0]
            self.assertEqual(str(self.original / 'jvm64/bin/java.exe'), argv[0])
            self.assertNotIn('-XstartOnFirstThread', argv)
            self.assertEqual('game-lib.jar;libs/*', argv[argv.index('-cp') + 1])
            self.assertEqual(0x08000000, popen.call_args.kwargs['creationflags'])
            self.assertEqual(self.original, popen.call_args.kwargs['cwd'])

    def test_mac_custom_prepare_builds_manifest_and_command_without_launching(self):
        output = self.directory / 'mac-prepare'
        java = self.directory / 'jdk17/Contents/Home/bin/java'
        javac = self.directory / 'jdk25/Contents/Home/bin/javac'
        before = {p.relative_to(self.original): p.read_bytes() for p in self.original.rglob('*') if p.is_file()}
        with mock.patch.object(benchmark, 'ORIGINAL', self.directory / 'unused-windows-installation'), \
                mock.patch.object(benchmark, 'EXPECTED_HASH', self.runtime_hash), \
                mock.patch.object(benchmark.sys, 'platform', 'darwin'), \
                mock.patch.object(benchmark.os, 'pathsep', ':'), \
                mock.patch.dict(benchmark.os.environ, {'RWX_BENCHMARK_TOOLING_SHA256': 'tooling'}), \
                mock.patch.object(benchmark, 'fingerprint', return_value=('tooling', {})), \
                mock.patch.object(benchmark, 'build_probe', side_effect=self.fake_probe) as build, \
                mock.patch.object(benchmark.subprocess, 'Popen') as popen, \
                contextlib.redirect_stdout(io.StringIO()) as printed:
            benchmark.main(self.args(output, '--original-root', str(self.original), '--java', str(java),
                                     '--javac', str(javac), '--prepare-only'))
            build.assert_called_once_with(output, javac)
            popen.assert_not_called()
            self.assertIn('NOT MEASURED', printed.getvalue())
        manifest = json.loads((output / 'manifest.json').read_text())
        command = json.loads((output / 'command.json').read_text())
        self.assertTrue(manifest['prepareOnly'])
        self.assertFalse(manifest['measurementPerformed'])
        self.assertEqual('not-measured', manifest['measurementStatus'])
        self.assertEqual(2, manifest['trailingCaptureSeconds'])
        self.assertEqual(2, manifest['configuration']['trailingCaptureSeconds'])
        self.assertEqual(2, manifest['configuration']['presentTraceVersion'])
        self.assertIn('isActive-and-isVisible', manifest['configuration']['displayValidityGate'])
        self.assertEqual(self.runtime_hash, manifest['runtimeSha256'])
        self.assertEqual(str(self.original), command['cwd'])
        self.assertEqual(str(java), command['argv'][0])
        self.assertNotIn('-XstartOnFirstThread', command['argv'])
        self.assertIn('-Drwx.orig.measure.seconds=60', command['argv'])
        self.assertIn('-Drwx.orig.trailing.capture.seconds=2', command['argv'])
        self.assertFalse(manifest['startOnFirstThread'])
        self.assertFalse(manifest['startOnFirstThreadRequested'])
        self.assertEqual('game-lib.jar:libs/*', command['argv'][command['argv'].index('-cp') + 1])
        self.assertFalse((output / 'summary.json').exists())
        self.assertFalse((output / 'original.log').exists())
        self.assertEqual(before, {p.relative_to(self.original): p.read_bytes()
                                 for p in self.original.rglob('*') if p.is_file()})

    def test_mac_first_thread_flag_requires_an_explicit_request(self):
        output = self.directory / 'mac-first-thread-prepare'
        with mock.patch.object(benchmark, 'EXPECTED_HASH', self.runtime_hash), \
                mock.patch.object(benchmark.sys, 'platform', 'darwin'), \
                mock.patch.object(benchmark, 'fingerprint', return_value=('tooling', {})), \
                mock.patch.dict(benchmark.os.environ, {'RWX_BENCHMARK_TOOLING_SHA256': 'tooling'}), \
                mock.patch.object(benchmark, 'build_probe', side_effect=self.fake_probe), \
                mock.patch.object(benchmark.subprocess, 'Popen') as popen, \
                contextlib.redirect_stdout(io.StringIO()):
            benchmark.main(self.args(output, '--original-root', str(self.original),
                                     '--prepare-only', '--start-on-first-thread'))
            popen.assert_not_called()
        manifest = json.loads((output / 'manifest.json').read_text())
        command = json.loads((output / 'command.json').read_text())
        self.assertIn('-XstartOnFirstThread', command['argv'])
        self.assertTrue(manifest['startOnFirstThread'])
        self.assertTrue(manifest['startOnFirstThreadRequested'])
        self.assertTrue(manifest['configuration']['startOnFirstThread'])

    def test_prepare_still_rejects_an_unpinned_core(self):
        with mock.patch.object(benchmark, 'EXPECTED_HASH', 'wrong-pinned-hash'), \
                mock.patch.object(benchmark, 'build_probe') as build, \
                mock.patch.object(benchmark.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(RuntimeError, 'original build identity changed'):
                benchmark.main(self.args(self.directory / 'bad-core', '--original-root', str(self.original),
                                         '--prepare-only'))
            build.assert_not_called()
            popen.assert_not_called()

    def test_output_cannot_be_written_into_the_installation(self):
        with mock.patch.object(benchmark, 'build_probe') as build, \
                mock.patch.object(benchmark.subprocess, 'Popen') as popen, \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                benchmark.main(self.args(self.original / 'benchmark-output',
                                         '--original-root', str(self.original), '--prepare-only'))
            build.assert_not_called()
            popen.assert_not_called()
        self.assertFalse((self.original / 'benchmark-output').exists())

    def test_probe_compiler_is_injectable(self):
        tool_root = self.directory / 'tools'
        (tool_root / 'original_probe').mkdir(parents=True)
        (tool_root / 'original_probe/Probe.java').write_text('class Probe {}')
        cache = self.directory / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
        for module in ('asm', 'asm-commons', 'asm-tree'):
            library = cache / module / f'{module}-9.0.jar'
            library.parent.mkdir(parents=True)
            with zipfile.ZipFile(library, 'w') as archive:
                archive.writestr(f'org/objectweb/asm/{module}.class', b'fixture')
        output = self.directory / 'probe-build'
        output.mkdir()
        javac = self.directory / 'custom-javac'
        with mock.patch.object(benchmark.Path, 'home', return_value=self.directory), \
                mock.patch.dict(benchmark.os.environ, {'RWX_BENCHMARK_TOOL_ROOT': str(tool_root)}), \
                mock.patch.object(benchmark.subprocess, 'run') as compile_probe, \
                mock.patch.object(benchmark.subprocess, 'Popen') as popen:
            probe, sources = benchmark.build_probe(output, javac)
            self.assertEqual(str(javac), compile_probe.call_args.args[0][0])
            self.assertEqual(['--release', '8'], compile_probe.call_args.args[0][1:3])
            self.assertTrue(compile_probe.call_args.kwargs['check'])
            self.assertTrue(probe.is_file())
            self.assertIn('Probe.java', sources)
            popen.assert_not_called()

    def completed_mock(self, output_name, invalid=None, tail_stall=False):
        output = self.directory / output_name

        def finish(argv, **options):
            options['stdout'].write(
                '[rwx-probe] camera drive: mode=pan-zoom startNanos=0\n'
                '[rwx-probe] camera evidence viewport=1280x720 spanX=10 spanY=10 zoomMin=0.35 zoomMax=1.5\n'
                '[rwx-probe] fog evidence enabled=true data=true\n'
                '[rwx-probe] simulation evidence initialTick=0 initialGameTimeMillis=0 '
                'sampleStartTick=60 sampleStartGameTimeMillis=1000 sampleEndTick=180 sampleEndGameTimeMillis=3000\n')
            with (output / 'original-present.csv').open('w', newline='') as stream:
                writer = csv.writer(stream)
                writer.writerow(['acceptedPresentNanos', 'sequence', 'overload', 'renderSequence', 'active', 'visible'])
                renders = [1, 2, 3, 3, 3, 3, 3, 4] if tail_stall else [1, 2, 3, 3, 4, 4, 5]
                for i, render in enumerate(renders):
                    writer.writerow([i * 500_000_000, i + 1, '(Z)V', 0 if invalid == 'render' else render,
                                     'false' if invalid == 'focus' and i == 3 else 'true',
                                     'false' if invalid == 'hidden' and i == 3 else 'true'])
            process = mock.Mock()
            process.wait.return_value = 0
            return process

        with mock.patch.object(benchmark, 'EXPECTED_HASH', self.runtime_hash), \
                mock.patch.object(benchmark, 'build_probe', side_effect=self.fake_probe), \
                mock.patch.object(benchmark, 'fingerprint', return_value=('tooling', {})), \
                mock.patch.dict(benchmark.os.environ, {'RWX_BENCHMARK_TOOLING_SHA256': 'tooling'}), \
                mock.patch.object(benchmark.subprocess, 'Popen', side_effect=finish), \
                contextlib.redirect_stdout(io.StringIO()):
            args = self.args(output, '--original-root', str(self.original),
                             '--warmup-seconds', '1', '--sample-seconds', '2')
            if invalid:
                with self.assertRaises(RuntimeError):
                    benchmark.main(args)
            else:
                benchmark.main(args)
        return json.loads((output / 'summary.json').read_text())

    def test_completed_mock_reports_distinct_present_and_rendered_rates(self):
        report = self.completed_mock('mock-valid')
        self.assertEqual('measured', report['measurementStatus'])
        self.assertTrue(report['measurementPerformed'])
        self.assertEqual(2.5, report['acceptedPresentFps'])
        self.assertEqual(1.5, report['newFps'])

    def test_completed_mock_marks_inactive_hidden_and_unrendered_samples_invalid(self):
        for invalid in ('focus', 'hidden', 'render'):
            with self.subTest(invalid=invalid):
                report = self.completed_mock('mock-invalid-' + invalid, invalid)
                self.assertEqual('invalid', report['measurementStatus'])
                self.assertFalse(report['measurementPerformed'])
                self.assertTrue(report['measurementAttempted'])
                self.assertTrue(report['invalidReason'])
                self.assertNotIn('newFps', report)

    def test_completed_mock_keeps_tail_stall_as_measured_performance(self):
        report = self.completed_mock('mock-tail-stall', tail_stall=True)
        self.assertEqual('measured', report['measurementStatus'])
        self.assertTrue(report['measurementPerformed'])
        self.assertEqual(2.5, report['acceptedPresentFps'])
        self.assertEqual(0.5, report['newFps'])
        self.assertEqual(2000, report['freshFrameAgeAtSampleEndMs'])
        self.assertEqual(500, report['freshSnapshotIntervalMaxMs'])


class OriginalPresentTraceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.trace = Path(temporary.name) / 'trace.csv'

    def write(self, render_sequences=None, inactive=None, hidden=None):
        # Two warmup observations plus five in-window swap returns. Repeated swaps are deliberate.
        renders = render_sequences or [1, 2, 3, 3, 4, 4, 5]
        rows = [[i * 500_000_000, i + 1, '(Z)V', value,
                 'false' if i == inactive else 'true', 'false' if i == hidden else 'true']
                for i, value in enumerate(renders)]
        with self.trace.open('w', newline='') as stream:
            writer = csv.writer(stream)
            writer.writerow(['acceptedPresentNanos', 'sequence', 'overload', 'renderSequence', 'active', 'visible'])
            writer.writerows(rows)
        return rows

    def analyze(self):
        return benchmark.analyze_present_trace(self.trace, 1_000_000_000, 3_000_000_000, 2)

    def test_counts_only_new_render_callback_presents_as_fresh(self):
        self.write()
        result = self.analyze()
        self.assertEqual(2.5, result['acceptedPresentFps'])
        self.assertEqual(1.5, result['newFps'])
        self.assertEqual(result['newFps'], result['freshRenderedFps'])
        self.assertEqual(5, result['acceptedPresentCount'])
        self.assertEqual(3, result['freshRenderedPresentCount'])
        self.assertEqual(2, result['presentsWithoutNewRenderCount'])
        self.assertEqual(1000, result['freshSnapshotIntervalP99Ms'])
        self.assertEqual(500, result['acceptedPresentIntervalP99Ms'])
        self.assertTrue(result['displayActiveAtEverySampledPresent'])
        self.assertTrue(result['displayVisibleAtEverySampledPresent'])

    def test_render_sequence_jump_counts_one_presented_frame(self):
        self.write([1, 2, 30, 30, 40, 40, 50])
        self.assertEqual(1.5, self.analyze()['newFps'])

    def test_rejects_no_render_callback_or_no_advancement(self):
        for render_sequences in ([0] * 7, [9] * 7):
            with self.subTest(render_sequences=render_sequences):
                self.write(render_sequences)
                with self.assertRaisesRegex(RuntimeError, 'no completed game-render sequence advancement'):
                    self.analyze()

    def test_rejects_inactive_repeated_swap(self):
        self.write(inactive=3)
        with self.assertRaisesRegex(RuntimeError, 'lost focus'):
            self.analyze()

    def test_rejects_hidden_repeated_swap(self):
        self.write(hidden=3)
        with self.assertRaisesRegex(RuntimeError, 'hidden'):
            self.analyze()

    def test_rejects_inactive_interval_boundary(self):
        self.write(inactive=1)
        with self.assertRaisesRegex(RuntimeError, 'lost focus'):
            self.analyze()

    def test_old_three_column_trace_is_not_new_measurement_evidence(self):
        self.trace.write_text('acceptedPresentNanos,sequence,overload\n100,1,(Z)V\n')
        with self.assertRaisesRegex(RuntimeError, 'lacks render sequence and display-state evidence'):
            self.analyze()

    def test_rejects_malformed_display_state(self):
        self.write()
        self.trace.write_text(self.trace.read_text().replace('true,true', 'unknown,true', 1))
        with self.assertRaisesRegex(RuntimeError, 'malformed display-state evidence'):
            self.analyze()

    def test_rejects_render_sequence_rollback(self):
        self.write([1, 2, 3, 2, 4, 4, 5])
        with self.assertRaisesRegex(RuntimeError, 'monotonically'):
            self.analyze()

    def test_rejects_missing_start_or_end_coverage(self):
        rows = self.write()
        for partial in (rows[2:], rows[:-1]):
            with self.subTest(partial=partial):
                with self.trace.open('w', newline='') as stream:
                    writer = csv.writer(stream)
                    writer.writerow(['acceptedPresentNanos', 'sequence', 'overload', 'renderSequence', 'active', 'visible'])
                    writer.writerows(partial)
                with self.assertRaisesRegex(RuntimeError, 'complete sample window'):
                    self.analyze()

    def test_first_positive_render_observation_can_anchor_next_fresh_present(self):
        self.write([1, 1, 2, 2, 3, 3, 4])
        self.assertEqual(1000, self.analyze()['freshSnapshotIntervalMaxMs'])

    def test_rejects_missing_prior_rendered_present_anchor(self):
        self.write([0, 0, 0, 0, 1, 1, 2])
        with self.assertRaisesRegex(RuntimeError, 'prior rendered-present cadence anchor'):
            self.analyze()

    def test_tail_stall_is_valid_and_trailing_recovery_does_not_raise_sample_fps(self):
        self.write([1, 2, 3, 3, 3, 3, 3, 4])
        result = self.analyze()
        self.assertEqual(0.5, result['newFps'])
        self.assertEqual(2.5, result['acceptedPresentFps'])
        self.assertEqual(1, result['freshRenderedPresentCount'])
        self.assertEqual(2000, result['freshFrameAgeAtSampleEndMs'])
        self.assertEqual(500, result['freshSnapshotIntervalMaxMs'])

    def test_swap_trace_must_reach_sample_end_even_if_only_one_nanosecond_short(self):
        self.write()
        self.trace.write_text(self.trace.read_text().replace('3000000000,', '2999999999,'))
        with self.assertRaisesRegex(RuntimeError, 'complete sample window'):
            self.analyze()


if __name__ == '__main__':
    unittest.main()
