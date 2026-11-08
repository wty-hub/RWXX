"""Pure offline replay validators: no Docker, JVM, X11 or socket execution."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import xvfb_replay_worker as worker
import xvfb_replay_controller as controller


def visual(): return dict(worker.VISUAL, groundSha256='a' * 64, decorationSha256='b' * 64)


def row(frame, time, active=0, pane1=11):
    value = dict(visual(), noGameEngine=True, readOnly=True, connected=True, wire=None, backend='OpenGL',
        viewCount=4, activePane=active, renderedViewCount=4, submittedFrames=frame, presentedRevision=frame - 1,
        width=1280, height=800, viewSequence=frame, tick=time if active == 0 else pane1, terrainTextures=3,
        actionProbe={'enabled': False, 'completed': False, 'panelSeen': False, 'queueObserved': False,
                     'cancelObserved': False, 'uiActionCount': 0})
    value['viewports'] = [dict(paneId=i, x=(i % 2) * 640, y=(i // 2) * 400, width=640, height=400,
        readOnly=True, viewSequence=frame if i == active else frame + i,
        tick=time if i == 0 else pane1 if i == 1 else 11, renderedGroundCells=100) for i in range(4)]
    return value


class ReplayChecks(unittest.TestCase):
    def test_four_panes_reverse_time_is_legal_while_render_counters_advance(self):
        value = worker.validate_replay([row(30, 800), row(60, 700)], visual(), final=True)
        self.assertTrue(value['readOnly']); self.assertFalse(value['networkRequested'])

    def test_all_readonly_probes_must_remain_disabled(self):
        for key in ('enabled', 'completed', 'panelSeen', 'queueObserved', 'cancelObserved', 'uiActionCount'):
            item = row(30, 100); item['actionProbe'][key] = 1 if key == 'uiActionCount' else True
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.validate_replay([item], visual())

    def test_no_network_or_engine_allowed(self):
        for key, value in [('wire', {}), ('noGameEngine', False), ('readOnly', False), ('backend', 'Vulkan'), ('connected', False)]:
            item = row(30, 100); item[key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.validate_replay([item], visual())

    def test_pane_partition_and_active_identity_are_actual(self):
        worker.validate_replay([row(30, 100, active=1, pane1=611)], visual())
        for key, value in [('readOnly', False), ('width', 639), ('paneId', 2)]:
            item = row(30, 100); item['viewports'][1][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.validate_replay([item], visual())
        item = row(30, 100); item['tick'] = 999
        with self.assertRaises(RuntimeError): worker.validate_replay([item], visual())

    def test_pause_requires_three_fresh_presentations_at_same_tick(self):
        self.assertTrue(worker.phase_matches('pause', [row(30, 100), row(60, 100), row(90, 100)]))
        self.assertFalse(worker.phase_matches('pause', [row(30, 100), row(60, 100)]))
        self.assertFalse(worker.phase_matches('pause', [row(30, 100), row(60, 101), row(90, 102)]))
        self.assertFalse(worker.phase_matches('pause', [row(30, 100), row(31, 100), row(32, 100)]))

    def test_seek_requires_ten_real_seconds_not_sender_success(self):
        self.assertTrue(worker.phase_matches('seek', [row(30, 700), row(60, 700)], base_tick=100))
        for value in (100, 200, 1300):
            self.assertFalse(worker.phase_matches('seek', [row(30, value), row(60, value)], base_tick=100))

    def test_reverse_and_forward_are_distinct(self):
        reverse = [row(30, 900), row(60, 800)]
        self.assertTrue(worker.phase_matches('reverse', reverse)); self.assertFalse(worker.phase_matches('forward', reverse))
        forward = [row(30, 800), row(60, 900)]
        self.assertTrue(worker.phase_matches('forward', forward)); self.assertFalse(worker.phase_matches('reverse', forward))

    def test_second_pane_seek_preserves_primary_time(self):
        samples = [row(30, 900, active=1, pane1=611), row(60, 900, active=1, pane1=611)]
        self.assertTrue(worker.phase_matches('seek', samples, base_tick=11, pane=1, held_tick=900))
        samples[-1]['viewports'][0]['tick'] = 901
        self.assertFalse(worker.phase_matches('seek', samples, base_tick=11, pane=1, held_tick=900))

    def test_unavailable_or_wrong_active_pane_cannot_pass_a_phase(self):
        samples = [row(30, 800), row(60, 700)]; samples[-1]['renderedViewCount'] = 3
        self.assertFalse(worker.phase_matches('reverse', samples))
        self.assertFalse(worker.phase_matches('active', [row(30, 800), row(60, 700)], pane=1))

    def test_container_carries_source_hashes_but_no_host_mounts(self):
        hashes = ['4' * 64, '5' * 64]
        command = controller.create_command('sha256:' + '3' * 64, '1' * 32, '2' * 64, 60, 240, hashes)
        self.assertEqual(command[command.index('--recording-0-sha256') + 1], hashes[0])
        self.assertEqual(command[command.index('--recording-1-sha256') + 1], hashes[1])
        self.assertEqual(command[command.index('--network') + 1], 'none')
        self.assertEqual(command[command.index('--cpus') + 1], '2.0')
        for option in ('--mount', '-v', '--device', '-p', '--cap-add'): self.assertNotIn(option, command)
        with self.assertRaises(RuntimeError): controller.create_command('sha256:' + '3' * 64, '1' * 32, '2' * 64, 60, 240, ['bad', hashes[1]])

    def test_failure_before_environment_preflight_has_no_processes(self):
        output = io.StringIO()
        with patch.object(worker.sys, 'argv', ['worker', '--jar-sha256', '2' * 64,
                '--recording-0-sha256', '4' * 64, '--recording-1-sha256', '5' * 64]), \
                patch.object(worker.sys, 'platform', 'not-linux'), patch.object(worker.sys, 'stdout', output), \
                patch.object(worker.signal, 'signal'), patch.object(worker.signal, 'alarm'), \
                patch.object(worker.subprocess, 'run') as run, patch.object(worker.subprocess, 'Popen') as popen:
            self.assertEqual(worker.main(), 1); run.assert_not_called(); popen.assert_not_called()
        value = json.loads(output.getvalue())
        self.assertFalse(value['serverStarted']); self.assertFalse(value['networkClientStarted'])
        self.assertEqual(value['children'], []); self.assertEqual(value['artifacts'], [])

    def test_passing_controller_report_requires_all_six_screenshots(self):
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(RuntimeError):
            controller.export_artifacts({'passed': True, 'artifacts': [], 'replays': [{'exitCode': 0}, {'exitCode': 0}]}, Path(temp))


if __name__ == '__main__': unittest.main()
