"""Pure fixture validation; never starts Docker, Java, display or a socket."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import xvfb_v7_worker as worker
import xvfb_v7_controller as controller


def visual():
    return dict(worker.VISUAL, groundSha256='a' * 64, decorationSha256='b' * 64)


def canvas(index):
    value = dict(visual(), noGameEngine=True, readOnly=False, connected=True, backend='OpenGL',
        viewCount=1, activePane=0, renderedViewCount=1, width=800, height=600,
        viewSequence=index + 1, tick=60 * index, submittedFrames=30 * index + 1,
        presentedRevision=-1 if index == 0 else 30 * index, renderedGroundCells=100,
        renderedDecorationCells=3, terrainTextures=3,
        actionProbe={'enabled': True, 'completed': index > 0, 'panelSeen': index > 0,
                     'queueObserved': index > 0, 'cancelObserved': index > 0, 'uiActionCount': 3 if index else 0})
    value['wire'] = dict(protocolVersion=7, recvBytes=1000 + index, writtenBytes=100 + index,
        mapInitializations=1, mapCacheHits=0, transactions=index + 1, restores=0,
        stateChecks=index * 6, stateAcks=index * 6, stateMismatches=0, stateDigestNanos=index * 100)
    value['viewports'] = [dict(paneId=0, x=0, y=0, width=800, height=600,
        viewSequence=index + 1, tick=60 * index, readOnly=False, renderedGroundCells=100)]
    return value


def observations():
    return [dict(wallSeconds=0, stateChecks=0, stateAcks=0), dict(wallSeconds=6, stateChecks=6, stateAcks=6)]


def inspection():
    return dict(visual(), passed=True, protocolVersion=7, containerVersion=3, teamId=0,
        noGameEngine=True, readOnly=True, completeIndex=True, staticVisualConsistent=True,
        slices=2, samples=4, views=4, visualViews=4, firstTick=1, finalTick=300,
        joinedAt=1000, historyFrom=1000, **{'from': 1000, 'until': 400000},
        gapCount=0, hasGaps=False, terminalCovered=False, partial=True)


class LiveGuards(unittest.TestCase):
    def test_real_probe_first_unpresented_frame_is_not_false_rejection(self):
        result = worker.validate_canvas([canvas(0), canvas(1)], observations())
        self.assertEqual(result['first']['presentedRevision'], -1)
        self.assertFalse(result['combatPulseVerified'])

    def test_no_local_engine_wrong_backend_or_protocol(self):
        for key, value in [('noGameEngine', False), ('backend', 'Vulkan'), ('readOnly', True), ('connected', False)]:
            rows = [canvas(0), canvas(1)]; rows[-1][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.validate_canvas(rows, observations())
        rows = [canvas(0), canvas(1)]; rows[-1]['wire']['protocolVersion'] = 6
        with self.assertRaises(RuntimeError): worker.validate_canvas(rows, observations())

    def test_ack_and_wall_progress_are_both_real_requirements(self):
        for field, value in [('stateChecks', 4), ('stateAcks', 4), ('wallSeconds', 4)]:
            seen = observations(); seen[-1][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                worker.validate_canvas([canvas(0), canvas(1)], seen)
        rows = [canvas(0), canvas(1)]; rows[-1]['wire']['stateMismatches'] = 1
        with self.assertRaises(RuntimeError): worker.validate_canvas(rows, observations())

    def test_queue_without_refund_completion_is_not_action_pass(self):
        for field in ('completed', 'panelSeen', 'queueObserved', 'cancelObserved'):
            rows = [canvas(0), canvas(1)]; rows[-1]['actionProbe'][field] = False
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                worker.validate_canvas(rows, observations())

    def test_static_public_metadata_and_pane_must_match(self):
        for mutation in ('hash', 'pane', 'cache'):
            rows = [canvas(0), canvas(1)]
            if mutation == 'hash': rows[-1]['groundSha256'] = 'c' * 64
            elif mutation == 'pane': rows[-1]['viewports'][0]['tick'] = 999
            else: rows[-1]['wire']['mapCacheHits'] = 1
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                worker.validate_canvas(rows, observations())

    def test_strict_archive_is_read_only_but_not_claimed_complete_match(self):
        value = worker.validate_inspection(inspection(), 0, visual())
        self.assertTrue(value['partial']); self.assertFalse(value['terminalCovered'])
        for update in ({'teamId': 1}, {'readOnly': False}, {'completeIndex': False}, {'partial': False}, {'views': 3}):
            with self.subTest(update=update), self.assertRaises(RuntimeError):
                worker.validate_inspection(dict(inspection(), **update), 0, visual())

    def test_window_crop_cannot_include_another_owned_window(self):
        left = dict(X=40, Y=100, WIDTH=800, HEIGHT=600)
        right = dict(X=1000, Y=100, WIDTH=800, HEIGHT=600)
        worker.nonoverlapping([left, right])
        with self.assertRaises(RuntimeError): worker.nonoverlapping([left, dict(right, X=700)])
        worker.guards.parse_geometry('X=1000\nY=100\nWIDTH=800\nHEIGHT=600')
        with self.assertRaises(RuntimeError): worker.guards.parse_geometry('X=1500\nY=100\nWIDTH=800\nHEIGHT=600')

    def test_no_partial_line_counted_as_canvas_frame(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'probe'; path.write_bytes(json.dumps(canvas(0)).encode() + b'\n{"partial":')
            self.assertEqual(len(worker.read_probe(path)), 1)
            with self.assertRaises(RuntimeError): worker.read_probe(path, final=True)

    def test_known_resource_and_probe_failures_reject(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'log'; path.write_text('ordinary informational warning\n')
            worker.validate_resources(path)
            for line in ('Freeing 2 leaked images', 'Authority UI font loading: missing', 'Authority action probe failed: timeout'):
                path.write_text(line)
                with self.assertRaises(RuntimeError): worker.validate_resources(path)

    def test_container_limits_and_secret_values_are_not_arguments(self):
        command = controller.create_command('sha256:' + '3' * 64, '1' * 32, '2' * 64, 35, 180)
        self.assertEqual(command[command.index('--network') + 1], 'none')
        self.assertEqual(command[command.index('--cpus') + 1], '2.0')
        for forbidden in ('--cap-add', '--device', '--mount', '-v', '-p'): self.assertNotIn(forbidden, command)
        self.assertEqual(command[command.index('--user') + 1], '1000:1000')
        with self.assertRaises(RuntimeError): controller.create_command('tag:mutable', '1' * 32, '2' * 64, 35, 180)

    def test_stage_preflight_failure_runs_no_helpers(self):
        stream = io.StringIO()
        with patch.object(worker.sys, 'argv', ['worker', '--jar-sha256', '2' * 64]), \
                patch.object(worker.sys, 'platform', 'not-linux'), patch.object(worker.sys, 'stdout', stream), \
                patch.object(worker.signal, 'signal'), patch.object(worker.signal, 'alarm'), \
                patch.object(worker.subprocess, 'run') as run, patch.object(worker.subprocess, 'Popen') as popen:
            self.assertEqual(worker.main(), 1); run.assert_not_called(); popen.assert_not_called()
        result = json.loads(stream.getvalue())
        self.assertFalse(result['preflightPassed']); self.assertEqual(result['children'], [])
        self.assertEqual(result['artifacts'], [])


if __name__ == '__main__': unittest.main()
