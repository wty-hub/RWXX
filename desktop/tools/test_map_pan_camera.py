import argparse
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import map_pan_live
import map_pan_live_comparison
import map_pan_comparison
import map_pan_replay

from map_pan_builtin_comparison import (add_camera_arguments, camera_protocol,
    camera_environment, confirm_camera_zoom, confirm_experimental_modes, confirm_dynamic_activity, confirm_gpu_map_cell_cache)


class MapPanCameraTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.preference_project = Path(directory.name)
        self.preference_seed = '[preferences]\n' + ''.join(
            f'{key} = "{value}"\n' for key, value in {
                'renderVsync': 'true', 'slick2dFullScreen': 'true', 'maxFrameRate': '60',
                'highRefreshRate': 'false', 'batterySaving': 'true', 'sendReports': 'true',
                'enableSounds': 'true', 'musicVolume': '0.8', 'masterVolume': '0.8',
                'gameVolume': '0.8', 'interfaceVolume': '0.8',
            }.items())
        (self.preference_project / 'preferences.toml').write_text(self.preference_seed, encoding='utf-8')
        project = patch.object(map_pan_comparison, 'PROJECT', self.preference_project)
        project.start()
        self.addCleanup(project.stop)

    def test_isolated_preferences_are_silent_and_do_not_modify_the_source_seed(self):
        seed = map_pan_comparison.isolated_seed()
        self.assertIn('enableSounds = "false"', seed)
        self.assertIn('maxFrameRate = "300"', seed)
        for key in ('musicVolume', 'masterVolume', 'gameVolume', 'interfaceVolume'):
            self.assertIn(f'{key} = "0.0"', seed)
        self.assertEqual((self.preference_project / 'preferences.toml').read_text(encoding='utf-8'),
                         self.preference_seed)

    def protocol(self, *arguments):
        parser = argparse.ArgumentParser()
        add_camera_arguments(parser)
        return camera_protocol(parser.parse_args(arguments))

    def cycle_run(self):
        return {'validMeasurement': True, 'invalidReasons': [],
                'setup': {'zoomMode': 'cycle', 'zoomPeriodSeconds': 4,
                          'zoomRequestedMinimum': .35, 'zoomRequestedMaximum': 1.5},
                'windows': [{'zoomMode': 'cycle', 'actualZoomMinimum': .4, 'actualZoomMaximum': 1.45},
                            {'zoomMode': 'cycle', 'actualZoomMinimum': .42, 'actualZoomMaximum': 1.46}]}

    def gpu_run(self):
        run = self.cycle_run()
        for i, window in enumerate(run['windows']):
            window.update(sampleStartNanos=(20 + i * 20) * 1_000_000_000,
                          sampleEndNanos=(40 + i * 20) * 1_000_000_000, maximumMovingUnits=100)
        run['frameWindows'] = [{'sampleNanos': second * 1_000_000_000,
                               'gpuMapCellCache': {'created': index * 10, 'hits': index * 100, 'retired': 0, 'live': 15, 'pending': 0}}
                              for index, second in enumerate((15, 25, 30, 35, 45, 50, 55))]
        return run

    def fixed_run(self, request=.45, density=1):
        run = self.gpu_run()
        run['setup'].update(zoomMode='fixed', zoomRequestedMinimum=request,
                            actualScreenWidthPixels=1280, actualScreenHeightPixels=720,
                            worldWidth=4000, worldHeight=2000, densityZoomScale=density)
        target = max(.32 / density, min(4.6 / density, 4.6, request))
        for window in run['windows']:
            window.update(zoomMode='fixed', densityZoomScale=density, requestedTargetZoomEnd=target,
                          actualZoomMinimum=target * density, actualZoomMaximum=target * density)
        return run

    def test_default_and_jump_keep_zoom_disabled(self):
        for arguments, expected in (((), 'pan'), (('--camera-mode', 'jump'), 'jump')):
            with self.subTest(arguments=arguments):
                protocol = self.protocol(*arguments)
                self.assertEqual(protocol['cameraMode'], expected)
                self.assertEqual(protocol['zoomMode'], 'none')
                self.assertEqual(protocol['cameraPeriodSeconds'], 20)

    def test_pan_zoom_alias_and_explicit_cycle_use_same_engine_controls(self):
        alias = self.protocol('--camera-mode', 'pan-zoom', '--camera-period-seconds', '1')
        explicit = self.protocol('--zoom-mode', 'cycle', '--camera-period-seconds', '1')
        self.assertEqual(camera_environment(alias), camera_environment(explicit))
        self.assertEqual(alias['cameraModeRequested'], 'pan-zoom')
        self.assertEqual(alias['cameraMode'], 'pan')
        self.assertEqual(camera_environment(alias), {
            'RWX_REPLAY_PAN_CAMERA_MODE': 'pan', 'RWX_REPLAY_PAN_PERIOD_SECONDS': '1',
            'RWX_REPLAY_PAN_ZOOM_MODE': 'cycle', 'RWX_REPLAY_PAN_ZOOM_PERIOD_SECONDS': '4',
            'RWX_REPLAY_PAN_ZOOM_MIN': '0.35', 'RWX_REPLAY_PAN_ZOOM_MAX': '1.5'})

    def test_fixed_uses_zoom_min_without_a_fake_cycle_and_static_supports_zoom_only(self):
        protocol = self.protocol('--zoom-mode', 'fixed', '--zoom-min', '.45', '--zoom-max', '.45')
        self.assertEqual(protocol['zoomMode'], 'fixed')
        self.assertEqual(protocol['zoomMinimumRequested'], .45)
        self.assertEqual(camera_environment(protocol)['RWX_REPLAY_PAN_ZOOM_MODE'], 'fixed')
        self.assertEqual(camera_environment(protocol)['RWX_REPLAY_PAN_ZOOM_MIN'], '0.45')
        self.assertEqual(self.protocol('--zoom-mode', 'fixed', '--zoom-min', '2')['zoomMinimumRequested'], 2)
        zoom_only = self.protocol('--camera-mode', 'static', '--zoom-mode', 'cycle')
        self.assertEqual(zoom_only['cameraMode'], 'static')
        self.assertEqual(zoom_only['zoomMode'], 'cycle')

    def test_bad_period_limits_and_conflicting_alias_fail_before_launch(self):
        for arguments in (('--camera-period-seconds', '0'), ('--zoom-period-seconds', '0'),
                          ('--zoom-min', 'nan'), ('--zoom-max', 'inf'), ('--zoom-min', '0'),
                          ('--zoom-min', '2'), ('--camera-mode', 'pan-zoom', '--zoom-mode', 'none'),
                          ('--camera-mode', 'pan-zoom', '--zoom-mode', 'fixed')):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                self.protocol(*arguments)

    def test_cycle_accepts_constrained_actual_zoom_that_still_changes(self):
        run = self.cycle_run()
        confirm_camera_zoom(run, self.protocol('--camera-mode', 'pan-zoom'))
        self.assertTrue(run['validMeasurement'])
        self.assertTrue(run['zoomModeConfirmed'])
        self.assertEqual(run['invalidReasons'], [])

    def test_ignored_cycle_or_static_window_is_invalid_even_if_frame_data_passed(self):
        original = self.cycle_run()
        mutations = [('setup-mode', lambda run: run['setup'].update(zoomMode='none')),
                     ('period', lambda run: run['setup'].update(zoomPeriodSeconds=8)),
                     ('limits', lambda run: run['setup'].update(zoomRequestedMinimum=.5)),
                     ('missing-setup', lambda run: run.update(setup=None)),
                     ('empty-windows', lambda run: run.update(windows=[])),
                     ('missing-actual', lambda run: run['windows'][0].pop('actualZoomMinimum')),
                     ('wrong-window-mode', lambda run: run['windows'][0].update(zoomMode='none')),
                     ('static-window', lambda run: run['windows'][1].update(actualZoomMinimum=1.46)),
                     ('nonfinite-window', lambda run: run['windows'][0].update(actualZoomMaximum=float('nan')))]
        for name, mutate in mutations:
            with self.subTest(name=name):
                run = copy.deepcopy(original)
                mutate(run)
                confirm_camera_zoom(run, self.protocol('--zoom-mode', 'cycle'))
                self.assertFalse(run['validMeasurement'])
                self.assertFalse(run['zoomModeConfirmed'])
                self.assertEqual(len(run['invalidReasons']), 1)

    def test_older_runtime_without_zoom_fields_remains_valid_for_original_pan(self):
        run = {'setup': {}, 'windows': [], 'invalidReasons': [], 'validMeasurement': True}
        confirm_camera_zoom(run, self.protocol())
        self.assertTrue(run['validMeasurement'])
        self.assertFalse(run['zoomModeConfirmed'])

    def test_fixed_confirms_constant_applied_zoom_with_ordinary_bounds_and_density(self):
        for request, density in ((.45, 1), (.1, 1), (.1, 2), (10, 2)):
            with self.subTest(request=request, density=density):
                run = self.fixed_run(request, density)
                confirm_camera_zoom(run, self.protocol('--zoom-mode', 'fixed', '--zoom-min', str(request)))
                self.assertTrue(run['validMeasurement'])
                self.assertTrue(run['zoomModeConfirmed'])
                self.assertEqual(run['invalidReasons'], [])

    def test_fixed_rejects_ignored_request_near_constant_cycles_and_missing_actual_evidence(self):
        original = self.fixed_run()
        mutations = [('mode', lambda run: run['setup'].update(zoomMode='cycle')),
                     ('request', lambda run: run['setup'].update(zoomRequestedMinimum=.5)),
                     ('missing-setup', lambda run: run.update(setup=None)),
                     ('missing-dimensions', lambda run: run['setup'].pop('worldWidth')),
                     ('empty-windows', lambda run: run.update(windows=[])),
                     ('missing-density', lambda run: run['windows'][0].pop('densityZoomScale')),
                     ('wrong-window-mode', lambda run: run['windows'][0].update(zoomMode='cycle')),
                     ('wrong-target', lambda run: run['windows'][0].update(requestedTargetZoomEnd=.5)),
                     ('near-cycle', lambda run: run['windows'][0].update(actualZoomMaximum=.450001)),
                     ('nan', lambda run: run['windows'][0].update(actualZoomMinimum=float('nan')))]
        for name, mutate in mutations:
            with self.subTest(name=name):
                run = copy.deepcopy(original); mutate(run)
                confirm_camera_zoom(run, self.protocol('--zoom-mode', 'fixed', '--zoom-min', '.45'))
                self.assertFalse(run['validMeasurement'])
                self.assertFalse(run['zoomModeConfirmed'])
                self.assertEqual(len(run['invalidReasons']), 1)

    def test_fixed_camera_requests_reach_live_and_replay_runners_without_java(self):
        for module in (map_pan_live, map_pan_replay):
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                jar = root / 'runtime.jar'; jar.write_bytes(b'fixture')
                replay = root / 'fixture.replay'; replay.write_bytes(b'fixture')
                output = root / 'run'; invoked = {}
                def fake_launch(command, environment, out, name, timeout):
                    invoked.update(environment=environment)
                    (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true\n', encoding='utf-8')
                    return {'name': name}
                arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                             '--camera-mode', 'pan', '--zoom-mode', 'fixed', '--zoom-min', '.45', '--zoom-max', '.45']
                if module is map_pan_replay:
                    arguments.extend(['--replay', str(replay)])
                fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                with patch('sys.argv', arguments), patch.object(module, 'java_command', return_value=['unused-java']), \
                        patch.object(module, 'launch', side_effect=fake_launch), \
                        patch.object(module, 'analyze_run', return_value=self.fixed_run()), \
                        patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                    self.assertEqual(module.main(), 0)
                self.assertEqual(invoked['environment']['RWX_REPLAY_PAN_ZOOM_MODE'], 'fixed')
                self.assertEqual(invoked['environment']['RWX_REPLAY_PAN_ZOOM_MIN'], '0.45')
                report = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                self.assertEqual(report['protocol']['zoomMode'], 'fixed')
                self.assertTrue(report['run']['zoomModeConfirmed'])

    def test_opt_in_modes_require_effective_initialization_logs(self):
        for log, stripe, text, valid in (
            ('', False, False, True),
            ('', True, False, False),
            ('', False, True, False),
            ('[RWX canvas] parallelCellRaster=true\nRWXPrimitiveTextMetrics enabled=false', True, False, True),
            ('[RWX canvas] parallelCellRaster=false\nRWXPrimitiveTextMetrics enabled=true', False, True, True),
            ('[RWX canvas] parallelCellRaster=true', False, False, False),
            ('RWXPrimitiveTextMetrics enabled=true', False, False, False),
            ('[RWX canvas] parallelCellRaster=true\n[RWX canvas] parallelCellRaster=false', True, False, False)):
            with self.subTest(log=log, stripe=stripe, text=text):
                run = {'invalidReasons': [], 'validMeasurement': True}
                confirm_experimental_modes(run, log, stripe, text)
                self.assertEqual(run['validMeasurement'], valid)
                self.assertEqual(bool(run['invalidReasons']), not valid)

    def test_adaptive_mode_missing_in_old_runtime_is_allowed_only_when_off(self):
        for adaptive_log, requested, expected in (
            ('', False, True), ('', True, False),
            ('[RWX canvas] adaptiveCellRaster=false', False, True),
            ('[RWX canvas] adaptiveCellRaster=false', True, False),
            ('[RWX canvas] adaptiveCellRaster=true', True, True),
            ('[RWX canvas] adaptiveCellRaster=true', False, False),
            ('[RWX canvas] adaptiveCellRaster=true\n[RWX canvas] adaptiveCellRaster=false', True, False)):
            with self.subTest(adaptive_log=adaptive_log, requested=requested):
                run = {'invalidReasons': [], 'validMeasurement': True}
                confirm_experimental_modes(run, '[RWX canvas] parallelCellRaster=true\n' + adaptive_log,
                                           parallel_cell_raster=True, adaptive_cell_raster=requested)
                self.assertEqual(run['validMeasurement'], expected)

    def test_gpu_activation_uses_vulkan_and_measured_growth_despite_initial_disabled_constructor(self):
        run = self.gpu_run()
        log = ('RWXGpuMapCellCache requested=true supported=false enabled=false\n'
               'RWXVulkanConfiguration framebuffer=1280x720 gpuMapCellSupported=true\n'
               'RWXGpuMapCellFallback reason=premultiplied-source cpu=true\n'
               'RWXGpuMapCellFallback reason=premultiplied-source cpu=true\n'
               'RWXGpuMapCellFallback reason=non-pixel-source cpu=false')
        confirm_experimental_modes(run, log, gpu_map_cell_cache=True)
        self.assertTrue(run['validMeasurement'])
        evidence = run['gpuMapCellCacheEvidence']
        self.assertTrue(evidence['actualActivityConfirmed'])
        self.assertEqual(evidence['measuredCounterGrowth'], {'created': 50, 'hits': 500})
        self.assertEqual(evidence['processFallbackReasonCounts'], {'premultiplied-source': 2, 'non-pixel-source': 1})
        self.assertEqual(evidence['processFallbackCpuStates'], {'true': 2, 'false': 1})

    def test_gpu_cache_reuse_of_warmup_cells_requires_measured_hit_growth(self):
        for requested in (True, False):
            with self.subTest(requested=requested):
                run = self.gpu_run()
                for sample in run['frameWindows']:
                    sample['gpuMapCellCache']['created'] = 10
                confirm_gpu_map_cell_cache(run, 'RWXVulkanConfiguration gpuMapCellSupported=true', requested)
                self.assertEqual(run['validMeasurement'], requested)
                self.assertTrue(run['gpuMapCellCacheEvidence']['actualActivityConfirmed'])
                self.assertEqual(run['gpuMapCellCacheEvidence']['measuredCounterGrowth'], {'created': 0, 'hits': 500})

    def test_gpu_activation_rejects_unsupported_silent_fallback_warmup_only_and_flat_counters(self):
        for name, mutate, log, requested, expected in (
            ('old-runtime-off', lambda run: run.update(frameWindows=[]), '', False, True),
            ('missing-capability', lambda run: None, '', True, False),
            ('unsupported', lambda run: None, 'RWXVulkanConfiguration gpuMapCellSupported=false', True, False),
            ('missing-metrics', lambda run: run.update(frameWindows=[]), 'RWXVulkanConfiguration gpuMapCellSupported=true', True, False),
            ('flat-metrics', lambda run: [sample['gpuMapCellCache'].update(created=10, hits=100) for sample in run['frameWindows']],
                'RWXVulkanConfiguration gpuMapCellSupported=true', True, False),
            ('warmup-only', lambda run: run.update(frameWindows=run['frameWindows'][:1]),
                'RWXVulkanConfiguration gpuMapCellSupported=true', True, False),
            ('no-hits-growth', lambda run: [sample['gpuMapCellCache'].update(hits=100) for sample in run['frameWindows']],
                'RWXVulkanConfiguration gpuMapCellSupported=true', True, False),
            ('no-created-cells', lambda run: [sample['gpuMapCellCache'].update(created=0) for sample in run['frameWindows']],
                'RWXVulkanConfiguration gpuMapCellSupported=true', True, False),
            ('decreasing-counter', lambda run: run['frameWindows'][-1]['gpuMapCellCache'].update(created=0),
                'RWXVulkanConfiguration gpuMapCellSupported=true', True, False),
            ('unexpected-off-activity', lambda run: None, 'RWXVulkanConfiguration gpuMapCellSupported=true', False, False)):
            with self.subTest(name=name):
                run = self.gpu_run(); mutate(run)
                confirm_gpu_map_cell_cache(run, log, requested)
                self.assertEqual(run['validMeasurement'], expected)

    def test_gpu_counter_growth_outside_disjoint_measurements_does_not_confirm_activation(self):
        run = self.gpu_run()
        run['windows'] = [{'sampleStartNanos': 20, 'sampleEndNanos': 25}, {'sampleStartNanos': 40, 'sampleEndNanos': 45}]
        run['frameWindows'] = [{'sampleNanos': time, 'gpuMapCellCache': {'created': created, 'hits': hits}}
                              for time, created, hits in ((21, 10, 100), (22, 10, 100), (41, 100, 1000), (42, 100, 1000))]
        confirm_gpu_map_cell_cache(run, 'RWXVulkanConfiguration gpuMapCellSupported=true', True)
        self.assertFalse(run['validMeasurement'])
        self.assertEqual(run['gpuMapCellCacheEvidence']['measuredCounterGrowth'], {'created': 0, 'hits': 0})

    def test_dynamic_windows_require_measured_movement_or_projectiles(self):
        for windows, expected in (
            ([], False), ([{}], False), ([{'maximumMovingUnits': 0, 'framesWithProjectiles': 0}], False),
            ([{'maximumMovingUnits': 5}, {'framesWithProjectiles': 12}], True),
            ([{'maximumMovingUnits': 5}, {'maximumMovingUnits': 0}], False)):
            with self.subTest(windows=windows):
                run = {'windows': windows, 'invalidReasons': [], 'validMeasurement': True}
                confirm_dynamic_activity(run)
                self.assertEqual(run['validMeasurement'], expected)
                self.assertEqual(run['dynamicActivityConfirmed'], expected)

    def test_live_runner_passes_camera_workers_modes_and_real_map_fog_without_java(self):
        for fog, adaptive in (('on', True), ('off', False)):
            with self.subTest(fog=fog), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                jar = root / 'frozen.jar'
                jar.write_bytes(b'frozen runtime test fixture')
                output = root / 'measurement'
                invoked = {}
                def fake_launch(command, environment, out, name, timeout):
                    invoked.update(command=command, environment=environment)
                    (out / f'{name}.log').write_text(
                        'RWXVulkanConfiguration gpuMapCellSupported=true\n[RWX canvas] parallelCellRaster=true\nRWXPrimitiveTextMetrics enabled=true' +
                        ('\n[RWX canvas] adaptiveCellRaster=true' if adaptive else ''), encoding='utf-8')
                    return {'name': name}
                run = self.gpu_run()
                for window in run['windows']:
                    window['maximumMovingUnits'] = 661
                fixture = [{'kind': 'scenario', 'requestedUnits': 661, 'mode': 'moving', 'teams': 15,
                            'fogEnabled': fog == 'on'}]
                arguments = ['map_pan_live.py', '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                             '--units', '661', '--fog', fog, '--camera-mode', 'pan-zoom',
                             '--camera-period-seconds', '1', '--raster-threads', '8',
                             '--parallel-cell-raster', '--primitive-text-metrics',
                             '--disable-native-bgra-upload', '--no-perf-window-log']
                if adaptive:
                    arguments.append('--adaptive-cell-raster')
                with patch('sys.argv', arguments), patch.object(map_pan_live, 'java_command', return_value=['unused-java']), \
                        patch.object(map_pan_live, 'launch', side_effect=fake_launch), \
                        patch.object(map_pan_live, 'analyze_run', return_value=run), \
                        patch.object(map_pan_live, 'read_json_lines', return_value=fixture), patch('builtins.print'):
                    self.assertEqual(map_pan_live.main(), 0)
                environment = invoked['environment']
                self.assertEqual(environment['RWX_BENCHMARK_UNITS'], '661')
                self.assertEqual(environment['RWX_BENCHMARK_FOG'], fog)
                self.assertEqual(environment['RWX_REPLAY_PAN_CAMERA_MODE'], 'pan')
                self.assertEqual(environment['RWX_REPLAY_PAN_ZOOM_MODE'], 'cycle')
                self.assertEqual(environment['RWX_REPLAY_PAN_PERIOD_SECONDS'], '1')
                self.assertEqual(environment['RWX_PARALLEL_CELL_RASTER'], '1')
                self.assertEqual(environment.get('RWX_ADAPTIVE_CELL_RASTER'), '1' if adaptive else None)
                self.assertEqual(environment['RWX_PRIMITIVE_TEXT_METRICS'], '1')
                self.assertEqual(environment['RWX_DISABLE_NATIVE_BGRA_UPLOAD'], '1')
                self.assertNotIn('RWX_NATIVE_BGRA_UPLOAD', environment)
                self.assertNotIn('RWX_PERF_LOG', environment)
                self.assertIn('-Drwx.koolRasterThreads=8', invoked['command'])
                self.assertIn('-Xmx1000M', invoked['command'])
                summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                self.assertEqual(summary['protocol']['adaptiveCellRasterRequested'], adaptive)
                self.assertTrue(summary['protocol']['gpuMapCellCacheRequested'])

    def test_adaptive_cli_rejects_missing_parallel_before_creating_output(self):
        cases = ((map_pan_live, ['--jar', 'unused.jar', '--adaptive-cell-raster']),
                 (map_pan_replay, ['--jar', 'unused.jar', '--replay', 'unused.replay', '--adaptive-cell-raster']),
                 (map_pan_live_comparison, ['--baseline', 'unused.jar', '--candidate', 'unused.jar', '--adaptive-cell-raster']),
                 (map_pan_live_comparison, ['--baseline', 'unused.jar', '--candidate', 'unused.jar', '--candidate-adaptive-cell-raster']),
                 (map_pan_live_comparison, ['--baseline', 'unused.jar', '--candidate', 'unused.jar',
                    '--adaptive-cell-raster', '--candidate-parallel-cell-raster']))
        for module, flags in cases:
            with self.subTest(module=module.__name__, flags=flags), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'must-not-exist'
                arguments = [module.__name__, '--java', 'unused-java', '--output', str(output), *flags]
                with patch('sys.argv', arguments), patch('sys.stderr'), self.assertRaises(SystemExit) as failure:
                    module.main()
                self.assertEqual(failure.exception.code, 2)
                self.assertFalse(output.exists())

    def test_owner_deadline_option_is_isolated_and_requires_actual_owner_confirmation_without_java(self):
        for module in (map_pan_live, map_pan_replay):
            for confirmed in (False, True):
                with self.subTest(module=module.__name__, confirmed=confirmed), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'runtime.jar'; jar.write_bytes(b'fixture')
                    replay = root / 'fixture.replay'; replay.write_bytes(b'fixture')
                    output = root / 'run'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked.update(environment=environment)
                        text = 'RWXVulkanConfiguration gpuMapCellSupported=true\n'
                        if confirmed:
                            text += 'RWXOwnerPacing hybrid=false stableFrameDeadlines=true\n'
                        (out / f'{name}.log').write_text(text, encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--camera-mode', 'pan-zoom', '--stable-owner-deadlines']
                    if module is map_pan_replay: arguments.extend(['--replay', str(replay)])
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    with patch('sys.argv', arguments), patch.dict('os.environ', {'RWX_STABLE_FRAME_DEADLINES': '1'}), \
                            patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 0 if confirmed else 1)
                    self.assertEqual(invoked['environment']['RWX_STABLE_OWNER_DEADLINES'], '1')
                    self.assertNotIn('RWX_STABLE_FRAME_DEADLINES', invoked['environment'])

    def test_replay_runner_preserves_worker_and_profile_flags_with_adaptive_without_java(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
            replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
            output = root / 'measurement'
            invoked = {}
            def fake_launch(command, environment, out, name, timeout):
                invoked.update(command=command, environment=environment)
                (out / f'{name}.log').write_text(
                    'RWXVulkanConfiguration gpuMapCellSupported=true\n[RWX canvas] parallelCellRaster=true\n[RWX canvas] adaptiveCellRaster=true', encoding='utf-8')
                return {'name': name}
            arguments = ['map_pan_replay.py', '--jar', str(jar), '--replay', str(replay), '--java', 'unused-java',
                         '--output', str(output), '--parallel-cell-raster', '--adaptive-cell-raster',
                         '--raster-threads', '8', '--cpu-target-profile', '--camera-mode', 'pan-zoom']
            with patch('sys.argv', arguments), patch.object(map_pan_replay, 'java_command', return_value=['unused-java']), \
                    patch.object(map_pan_replay, 'launch', side_effect=fake_launch), \
                    patch.object(map_pan_replay, 'analyze_run', return_value=self.gpu_run()), patch('builtins.print'):
                self.assertEqual(map_pan_replay.main(), 0)
            self.assertEqual(invoked['environment']['RWX_ADAPTIVE_CELL_RASTER'], '1')
            self.assertEqual(invoked['environment']['RWX_PARALLEL_CELL_RASTER'], '1')
            self.assertIn('-Drwx.koolRasterThreads=8', invoked['command'])
            self.assertIn('-Drwx.kool.cpuTargetProfile=true', invoked['command'])
            self.assertIn('-Xmx1000M', invoked['command'])
            summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
            self.assertTrue(summary['protocol']['adaptiveCellRasterRequested'])
            self.assertTrue(summary['protocol']['gpuMapCellCacheRequested'])
            self.assertTrue(summary['diagnosticOnly'])

    def test_comparison_passes_adaptive_only_to_matching_parallel_cases_without_java(self):
        for flags, expected in ((['--candidate-parallel-cell-raster', '--candidate-adaptive-cell-raster'],
                                 [False, True, True, False]),
                                (['--parallel-cell-raster', '--adaptive-cell-raster'], [True, True, True, True]),
                                (['--parallel-cell-raster', '--candidate-adaptive-cell-raster'], [False, True, True, False])):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'measurement'
                invoked = []
                def fake_run(command, check):
                    invoked.append(command)
                    target = Path(command[command.index('--output') + 1]); target.mkdir()
                    (target / 'diagnostic-summary.json').write_text(json.dumps({'run': {
                        'validMeasurement': True, 'windows': [], 'setup': {'viewportWidth': 1280, 'viewportHeight': 720}}}), encoding='utf-8')
                arguments = ['map_pan_live_comparison.py', '--baseline', 'unused.jar', '--candidate', 'unused.jar',
                             '--java', 'unused-java', '--output', str(output), *flags]
                with patch('sys.argv', arguments), patch.object(map_pan_live_comparison.subprocess, 'run', side_effect=fake_run), \
                        patch('builtins.print'):
                    map_pan_live_comparison.main()
                self.assertEqual(['--adaptive-cell-raster' in command for command in invoked], expected)
                for command in invoked:
                    if '--adaptive-cell-raster' in command:
                        self.assertIn('--parallel-cell-raster', command)
                summary = json.loads((output / 'summary.json').read_text(encoding='utf-8'))
                self.assertEqual(summary['protocol']['adaptiveCellRasterRequested'],
                    {'baseline': expected[0], 'candidate': expected[1]})

    def test_gpu_runner_flag_sets_environment_and_confirms_real_counters_without_other_features_or_java(self):
        for module in (map_pan_live, map_pan_replay):
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                output = root / 'measurement'; invoked = {}
                def fake_launch(command, environment, out, name, timeout):
                    invoked['environment'] = environment
                    (out / f'{name}.log').write_text(
                        'RWXGpuMapCellCache requested=true supported=false enabled=false\n'
                        'RWXVulkanConfiguration gpuMapCellSupported=true', encoding='utf-8')
                    return {'name': name}
                arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                             '--gpu-map-cell-cache', '--camera-mode', 'pan-zoom']
                if module is map_pan_replay:
                    arguments.extend(['--replay', str(replay)])
                fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                with patch('sys.argv', arguments), patch.object(module, 'java_command', return_value=['unused-java']), \
                        patch.object(module, 'launch', side_effect=fake_launch), patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                        patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                    self.assertEqual(module.main(), 0)
                self.assertEqual(invoked['environment']['RWX_GPU_MAP_CELL_CACHE'], '1')
                self.assertNotIn('RWX_PARALLEL_CELL_RASTER', invoked['environment'])
                self.assertNotIn('RWX_ADAPTIVE_CELL_RASTER', invoked['environment'])
                summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                self.assertTrue(summary['protocol']['gpuMapCellCacheRequested'])
                self.assertTrue(summary['run']['gpuMapCellCacheEvidence']['actualActivityConfirmed'])

    def test_comparison_passes_gpu_flag_only_to_requested_variants_without_java(self):
        for flags, expected in ((['--candidate-gpu-map-cell-cache'], [False, True, True, False]),
                                (['--gpu-map-cell-cache'], [True, True, True, True]),
                                ([], [True, True, True, True]),
                                (['--cpu-cell-raster'], [False, False, False, False])):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'measurement'; invoked = []
                def fake_run(command, check):
                    invoked.append(command)
                    target = Path(command[command.index('--output') + 1]); target.mkdir()
                    (target / 'diagnostic-summary.json').write_text(json.dumps({'run': {
                        'validMeasurement': True, 'windows': [], 'setup': {'viewportWidth': 1280, 'viewportHeight': 720}}}), encoding='utf-8')
                arguments = ['map_pan_live_comparison.py', '--baseline', 'unused.jar', '--candidate', 'unused.jar',
                             '--java', 'unused-java', '--output', str(output), *flags]
                with patch('sys.argv', arguments), patch.object(map_pan_live_comparison.subprocess, 'run', side_effect=fake_run), patch('builtins.print'):
                    map_pan_live_comparison.main()
                self.assertEqual(['--gpu-map-cell-cache' in command for command in invoked], expected)
                self.assertEqual(['--cpu-cell-raster' in command for command in invoked], [not gpu for gpu in expected])
                summary = json.loads((output / 'summary.json').read_text(encoding='utf-8'))
                self.assertEqual(summary['protocol']['gpuMapCellCacheRequested'], {'baseline': expected[0], 'candidate': expected[1]})

    def test_default_gpu_and_explicit_cpu_match_environment_protocol_and_counters(self):
        for module in (map_pan_live, map_pan_replay):
            for cpu in (False, True):
                with self.subTest(module=module.__name__, cpu=cpu), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                    replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                    output = root / 'measurement'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked['environment'] = environment
                        (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true', encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--camera-mode', 'pan-zoom']
                    if module is map_pan_replay:
                        arguments.extend(['--replay', str(replay)])
                    if cpu:
                        arguments.append('--cpu-cell-raster')
                    run = self.gpu_run()
                    if cpu:
                        for frame in run['frameWindows']:
                            frame['gpuMapCellCache'].update(created=0, hits=0)
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    with patch('sys.argv', arguments), patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=run), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 0)
                    self.assertEqual(invoked['environment'].get('RWX_GPU_MAP_CELL_TARGETS', '1'), '0' if cpu else '1')
                    summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                    self.assertEqual(summary['protocol']['gpuMapCellCacheRequested'], not cpu)
                    self.assertEqual(summary['run']['gpuMapCellCacheEvidence']['actualActivityConfirmed'], not cpu)
                    self.assertTrue(summary['run']['validMeasurement'])

    def test_gpu_pass_reuse_cli_rejects_explicit_cpu_before_creating_output(self):
        for module in (map_pan_live, map_pan_replay):
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'must-not-exist'
                arguments = [module.__name__, '--jar', 'unused.jar', '--java', 'unused-java', '--output', str(output),
                             '--cpu-cell-raster', '--disable-gpu-map-cell-pass-reuse']
                if module is map_pan_replay:
                    arguments.extend(['--replay', 'unused.replay'])
                with patch('sys.argv', arguments), patch('sys.stderr'), self.assertRaises(SystemExit) as failure:
                    module.main()
                self.assertEqual(failure.exception.code, 2)
                self.assertFalse(output.exists())

    def test_prepared_map_control_and_candidate_pin_environment_despite_product_default_and_inheritance(self):
        for module in (map_pan_live, map_pan_replay):
            for requested in (False, True):
                with self.subTest(module=module.__name__, requested=requested), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                    replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                    output = root / 'measurement'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked['environment'] = environment
                        (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true\n' +
                            ('RWXPreparedMapTiles recordingConfirmed=true' if requested else ''), encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--camera-mode', 'pan-zoom']
                    if module is map_pan_replay:
                        arguments.extend(['--replay', str(replay)])
                    if requested:
                        arguments.append('--prepared-map-tiles')
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    with patch('sys.argv', arguments), patch.dict('os.environ', {'RWX_PREPARED_MAP_TILES': '0' if requested else '1'}), \
                            patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 0)
                    self.assertEqual(invoked['environment']['RWX_PREPARED_MAP_TILES'], '1' if requested else '0')
                    summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                    self.assertEqual(summary['protocol']['preparedMapTilesRequested'], requested)
                    self.assertTrue(summary['run']['validMeasurement'])

    def test_requested_prepared_map_recording_requires_runtime_confirmation(self):
        for module in (map_pan_live, map_pan_replay):
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                output = root / 'measurement'
                def fake_launch(command, environment, out, name, timeout):
                    (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true', encoding='utf-8')
                    return {'name': name}
                arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                             '--prepared-map-tiles', '--camera-mode', 'pan-zoom']
                if module is map_pan_replay:
                    arguments.extend(['--replay', str(replay)])
                fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                with patch('sys.argv', arguments), patch.object(module, 'java_command', return_value=['unused-java']), \
                        patch.object(module, 'launch', side_effect=fake_launch), \
                        patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                        patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                    self.assertEqual(module.main(), 1)
                summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                self.assertFalse(summary['run']['validMeasurement'])
                self.assertIn('prepared map tile recording was not exercised', summary['run']['invalidReasons'])

    def test_frozen_reuse_controls_are_explicit_despite_product_default_and_parent_environment(self):
        for module in (map_pan_live, map_pan_replay):
            for requested in (False, True):
                with self.subTest(module=module.__name__, requested=requested), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                    replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                    output = root / 'measurement'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked['environment'] = environment
                        (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true\n' +
                            ('frozenContentReuse=true' if requested else ''), encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--camera-mode', 'pan-zoom']
                    if module is map_pan_replay:
                        arguments.extend(['--replay', str(replay)])
                    if requested:
                        arguments.append('--frozen-reuse')
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    with patch('sys.argv', arguments), patch.dict('os.environ', {'RWX_FROZEN_CONTENT_REUSE': '1'}), \
                            patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 0)
                    self.assertEqual(invoked['environment']['RWX_FROZEN_CONTENT_REUSE'], '1' if requested else '0')
                    summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                    self.assertEqual(summary['configuration']['frozen_reuse'], requested)
                    self.assertTrue(summary['run']['validMeasurement'])

    def test_shared_texture_registration_candidate_is_explicit_and_requires_actual_sharing(self):
        for module in (map_pan_live, map_pan_replay):
            for requested, confirmed in ((False, False), (True, False), (True, True)):
                with self.subTest(module=module.__name__, requested=requested, confirmed=confirmed), \
                        tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                    replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                    output = root / 'measurement'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked['environment'] = environment
                        marker = 'RWXSharedTextureRegistrations sharingConfirmed=true' if confirmed else ''
                        (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true\n' + marker,
                                                       encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--camera-mode', 'pan-zoom']
                    if module is map_pan_replay:
                        arguments.extend(['--replay', str(replay)])
                    if requested:
                        arguments.append('--share-texture-registrations')
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    with patch('sys.argv', arguments), \
                            patch.dict('os.environ', {'RWX_SHARE_TEXTURE_REGISTRATIONS': '0' if requested else '1'}), \
                            patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 1 if requested and not confirmed else 0)
                    self.assertEqual(invoked['environment']['RWX_SHARE_TEXTURE_REGISTRATIONS'], '1' if requested else '0')
                    summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                    self.assertEqual(summary['protocol']['shareTextureRegistrationsRequested'], requested)
                    self.assertEqual(summary['configuration']['share_texture_registrations'], requested)
                    self.assertEqual(summary['run']['validMeasurement'], not requested or confirmed)
                    reason = 'shared texture registrations were not exercised'
                    self.assertEqual(reason in summary['run']['invalidReasons'], requested and not confirmed)

    def test_shared_atlas_pixels_require_explicit_switch_and_both_actual_reuse_markers(self):
        for module in (map_pan_live, map_pan_replay):
            for requested, cpu_reuse, upload_reuse in (
                    (False, False, False), (True, False, False), (True, True, False),
                    (True, False, True), (True, True, True)):
                with self.subTest(module=module.__name__, requested=requested, cpu_reuse=cpu_reuse,
                                  upload_reuse=upload_reuse), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                    replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                    output = root / 'measurement'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked['environment'] = dict(environment)
                        markers = ['RWXVulkanConfiguration gpuMapCellSupported=true']
                        if cpu_reuse:
                            markers.append('RWXAtlasPixelSharing reuseConfirmed=true')
                        if upload_reuse:
                            markers.append('RWXAtlasPixelUploads reuseConfirmed=true')
                        (out / f'{name}.log').write_text('\n'.join(markers), encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--camera-mode', 'pan-zoom']
                    if module is map_pan_replay:
                        arguments.extend(['--replay', str(replay)])
                    if requested:
                        arguments.append('--share-map-atlas-pixels')
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    valid = not requested or cpu_reuse and upload_reuse
                    with patch('sys.argv', arguments), \
                            patch.dict('os.environ', {'RWX_SHARE_MAP_ATLAS_PIXELS': '0' if requested else '1'}), \
                            patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 0 if valid else 1)
                    self.assertEqual(invoked['environment']['RWX_SHARE_MAP_ATLAS_PIXELS'], '1' if requested else '0')
                    summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                    self.assertEqual(summary['protocol']['shareMapAtlasPixelsRequested'], requested)
                    self.assertEqual(summary['configuration']['share_map_atlas_pixels'], requested)
                    self.assertEqual(summary['run']['validMeasurement'], valid)
                    for reason, confirmed in (
                            ('shared atlas CPU pixel reuse was not confirmed', cpu_reuse),
                            ('shared atlas upload reuse was not confirmed', upload_reuse)):
                        self.assertEqual(reason in summary['run']['invalidReasons'], requested and not confirmed)

    def test_pacing_and_zoom_metadata_follow_final_environment_without_changing_switches(self):
        for module in (map_pan_live, map_pan_replay):
            cases = [((), {}), (('--legacy-owner-pacing',), {'RWX_LEGACY_OWNER_PACING': '1'})]
            if module is map_pan_replay:
                cases.extend([
                    (('--frame-based-zoom-cache',), {'RWX_TIME_BASED_MAP_ZOOM_CACHE': '0'}),
                    (('--time-based-map-zoom-cache',), {'RWX_TIME_BASED_MAP_ZOOM_CACHE': '1'}),
                    (('--time-based-map-zoom-cache', '--frame-based-zoom-cache'),
                     {'RWX_TIME_BASED_MAP_ZOOM_CACHE': '0'})])
            for flags, expected_switches in cases:
                with self.subTest(module=module.__name__, flags=flags), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                    replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                    output = root / 'measurement'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked['environment'] = dict(environment)
                        (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true', encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--camera-mode', 'pan-zoom', *flags]
                    if module is map_pan_replay:
                        arguments.extend(['--replay', str(replay)])
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    with patch('sys.argv', arguments), \
                            patch.dict('os.environ', {'RWX_HYBRID_OWNER_PACING': '1', 'RWX_TIME_BASED_MAP_ZOOM_CACHE': '0'}), \
                            patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 0)
                    environment = invoked['environment']
                    observed_switches = {key: environment[key] for key in (
                        'RWX_HYBRID_OWNER_PACING', 'RWX_LEGACY_OWNER_PACING', 'RWX_TIME_BASED_MAP_ZOOM_CACHE')
                        if key in environment}
                    self.assertEqual(observed_switches, expected_switches)
                    summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                    self.assertFalse(summary['protocol']['hybridOwnerPacingRequested'])
                    self.assertEqual(summary['configuration']['legacy_owner_pacing'], '--legacy-owner-pacing' in flags)
                    if module is map_pan_replay:
                        self.assertEqual(summary['protocol']['timeBasedMapZoomCacheRequested'],
                                         expected_switches.get('RWX_TIME_BASED_MAP_ZOOM_CACHE') != '0')

    def test_gpu_pass_reuse_runner_is_explicit_and_records_control_protocol_without_java(self):
        for module in (map_pan_live, map_pan_replay):
            for disabled in (False, True):
                with self.subTest(module=module.__name__, disabled=disabled), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'frozen.jar'; jar.write_bytes(b'frozen runtime test fixture')
                    replay = root / 'europe.replay'; replay.write_bytes(b'replay test fixture')
                    output = root / 'measurement'; invoked = {}
                    def fake_launch(command, environment, out, name, timeout):
                        invoked['environment'] = environment
                        (out / f'{name}.log').write_text('RWXVulkanConfiguration gpuMapCellSupported=true', encoding='utf-8')
                        return {'name': name}
                    arguments = [module.__name__, '--jar', str(jar), '--java', 'unused-java', '--output', str(output),
                                 '--gpu-map-cell-cache', '--camera-mode', 'pan-zoom']
                    if module is map_pan_replay:
                        arguments.extend(['--replay', str(replay)])
                    if disabled:
                        arguments.append('--disable-gpu-map-cell-pass-reuse')
                    fixture = [{'kind': 'scenario', 'requestedUnits': 500, 'mode': 'moving', 'teams': 15, 'fogEnabled': False}]
                    with patch('sys.argv', arguments), patch.dict('os.environ', {'RWX_DISABLE_GPU_MAP_CELL_PASS_REUSE': '1'}), \
                            patch.object(module, 'java_command', return_value=['unused-java']), \
                            patch.object(module, 'launch', side_effect=fake_launch), \
                            patch.object(module, 'analyze_run', return_value=self.gpu_run()), \
                            patch.object(module, 'read_json_lines', return_value=fixture, create=True), patch('builtins.print'):
                        self.assertEqual(module.main(), 0)
                    self.assertEqual(invoked['environment']['RWX_GPU_MAP_CELL_CACHE'], '1')
                    self.assertEqual(invoked['environment'].get('RWX_DISABLE_GPU_MAP_CELL_PASS_REUSE'), '1' if disabled else None)
                    summary = json.loads((output / 'diagnostic-summary.json').read_text(encoding='utf-8'))
                    self.assertEqual(summary['protocol']['gpuMapCellPassReuseDisabled'], disabled)
                    self.assertTrue(summary['run']['gpuMapCellCacheEvidence']['actualActivityConfirmed'])


if __name__ == '__main__':
    unittest.main()
