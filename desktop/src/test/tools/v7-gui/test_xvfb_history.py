"""Offline only: no Docker, Java, X server, network socket, or production process."""
import ast
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import xvfb_history_worker as worker
import xvfb_history_controller as controller


def row(index=1, active=1, time1=100_000, time2=200_000):
    observed = 2_500_000 + index * 61_440
    value = dict(worker.VISUAL, groundSha256='a' * 64, decorationSha256='b' * 64,
        noGameEngine=True, readOnly=active != 0, connected=True, backend='OpenGL', viewCount=4,
        activePane=active, renderedViewCount=4, width=1280, height=800, submittedFrames=index * 30,
        presentedRevision=index * 30 - 1, terrainTextures=3,
        actionProbe=dict(enabled=True, completed=True, panelSeen=True, queueObserved=True, cancelObserved=True, uiActionCount=3),
        inputWrites=dict(commandsWritten=2, selectionsWritten=1))
    value['wire'] = dict(protocolVersion=7, recvBytes=1000 + index * 100, writtenBytes=100 + index * 50,
        mapInitializations=1, mapCacheHits=0, transactions=index + 5, restores=0,
        stateChecks=5 + index, stateAcks=5 + index, stateMismatches=0, stateDigestNanos=index * 100)
    panes = []
    for pane in range(4):
        sample = observed - 6144 if pane == 0 else time1 if pane == 1 else time2 if pane == 2 else 0
        lo = observed - 30 * 60 * 1024 if pane == 0 else 0
        hi = observed + 61440 if pane == 0 else 1_800_000
        item = dict(paneId=pane, x=(pane % 2) * 640, y=(pane // 2) * 400, width=640, height=400,
            readOnly=pane != 0, viewSequence=index * 4 + pane + 1, tick=sample // 1024, renderedGroundCells=100,
            sourceMode='LIVE' if pane == 0 else 'HISTORY', sourceStreamId=10 + pane,
            sourceEpoch=1, sourceRevision=index if pane == 0 else 7, sourceControlEpoch=1 if pane == 0 else 0,
            sampledTime=sample, sourceObservedThrough=observed if pane == 0 else 2_000_000,
            sourceRetainedFrom=lo, sourceRetainedUntil=hi, sourceSealedThrough=observed - 100_000 if pane == 0 else hi,
            completedHistory=None if pane == 0 else dict(streamId=10 + pane, epoch=1, revision=7,
                acceptedFrom=lo, acceptedUntil=hi, endReason=2))
        panes.append(item)
    value['viewports'] = panes; value['tick'] = panes[active]['tick']; value['viewSequence'] = panes[active]['viewSequence']
    return value


def change_time(value, pane, sampled):
    value['viewports'][pane]['sampledTime'] = sampled
    value['viewports'][pane]['tick'] = sampled // 1024
    if value['activePane'] == pane: value['tick'] = sampled // 1024
    return value


class HistoryEvidence(unittest.TestCase):
    def test_real_history_requires_same_render_complete_identity_not_merely_readonly(self):
        value = row(); worker.validate_canvas([value]); self.assertTrue(worker.history_ready(value, 1, True))
        paused = copy.deepcopy(value); p = paused['viewports'][1]
        p.update(sourceMode='LIVE', sourceStreamId=10, sourceControlEpoch=1, sourceRetainedUntil=2_100_000,
                 sourceSealedThrough=1_800_000, completedHistory=None)
        worker.validate_canvas([paused])
        self.assertFalse(worker.history_ready(paused, 1, True))
        incomplete = copy.deepcopy(value); incomplete['viewports'][1]['completedHistory'] = None
        worker.validate_canvas([incomplete]); self.assertFalse(worker.history_ready(incomplete, 1))

    def test_each_completion_identity_field_and_reason_is_checked(self):
        for field in ('streamId', 'epoch', 'revision', 'acceptedFrom', 'acceptedUntil', 'endReason'):
            value = row(); value['viewports'][1]['completedHistory'][field] += 1
            with self.subTest(field=field), self.assertRaises(RuntimeError): worker.validate_canvas([value])
        for field in worker.SOURCE_FIELDS:
            value = row(); del value['viewports'][1][field]
            with self.subTest(missing=field), self.assertRaises(RuntimeError): worker.validate_canvas([value])

    def test_future_sample_wrong_tick_writable_history_and_oversized_window_fail(self):
        changes = [('sampledTime', 1_800_000), ('sourceControlEpoch', 1), ('tick', 999), ('readOnly', False)]
        for key, value in changes:
            actual = row(); actual['viewports'][1][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.validate_canvas([actual])
        actual = row(); p = actual['viewports'][1]
        p['sourceRetainedUntil'] = p['sourceSealedThrough'] = p['sourceObservedThrough'] = 121 * 60 * 1024
        p['completedHistory']['acceptedUntil'] = p['sourceRetainedUntil']
        with self.assertRaises(RuntimeError): worker.validate_canvas([actual])

    def test_live_floor_must_really_pass_historical_sample(self):
        actual = row(); self.assertTrue(worker.history_ready(actual, 1, True))
        live = actual['viewports'][0]; live['sourceRetainedFrom'] = 0
        worker.validate_canvas([actual]); self.assertFalse(worker.history_ready(actual, 1, True))
        self.assertTrue(worker.history_ready(actual, 1, False))

    def test_unavailable_sources_clear_all_fields_and_cannot_claim_presentation(self):
        value = row(); p = value['viewports'][1]
        for key in worker.SOURCE_FIELDS: p[key] = None
        p.update(completedHistory=None, viewSequence=-1, tick=0, renderedGroundCells=0)
        value.update(viewSequence=-1, tick=0, renderedViewCount=3)
        worker.validate_canvas([value]); self.assertFalse(worker.history_ready(value, 1))
        p['sourceStreamId'] = 11
        with self.assertRaises(RuntimeError): worker.validate_canvas([value])

    def test_real_four_pane_geometry_no_engine_protocol_and_digest_guards_remain(self):
        for key, changed in [('noGameEngine', False), ('backend', 'Vulkan'), ('connected', False), ('readOnly', False)]:
            actual = row(); actual[key] = changed
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.validate_canvas([actual])
        for key, changed in [('protocolVersion', 6), ('stateMismatches', 1), ('restores', 1)]:
            actual = row(); actual['wire'][key] = changed
            with self.subTest(key=key), self.assertRaises(RuntimeError): worker.validate_canvas([actual])
        actual = row(); actual['viewports'][2]['width'] -= 1
        with self.assertRaises(RuntimeError): worker.validate_canvas([actual])
        actual = row(); actual['viewports'][0]['sourceMode'] = 'HISTORY'
        with self.assertRaises(RuntimeError): worker.validate_canvas([actual])

    def test_pause_reverse_forward_seek_and_live_progress_have_separate_assertions(self):
        held = [row(i) for i in (1, 2, 3)]
        identity = worker.history_identity(held[0], 1)
        self.assertTrue(worker.phase_matches('pause', held, 1, identity))
        self.assertFalse(worker.phase_matches('pause', held[:2], 1, identity))
        reverse = [row(1, time1=200_000), row(2, time1=150_000)]
        self.assertTrue(worker.phase_matches('reverse', reverse, 1, identity))
        self.assertFalse(worker.phase_matches('forward', reverse, 1, identity))
        forward = [row(1, time1=100_000), row(2, time1=150_000)]
        self.assertTrue(worker.phase_matches('forward', forward, 1, identity))
        seek = [row(i, time1=100_000 + 614_400) for i in (1, 2)]
        self.assertTrue(worker.phase_matches('seek', seek, 1, identity, 100_000))
        self.assertFalse(worker.phase_matches('seek', seek, 1, identity, 100_001))
        held[-1]['viewports'][0]['sampledTime'] = held[0]['viewports'][0]['sampledTime']
        self.assertFalse(worker.phase_matches('pause', held, 1, identity))

    def test_independent_pane_controls_preserve_held_primary_history(self):
        samples = [row(i, active=2, time1=500_000, time2=614_400) for i in (1, 2)]
        identity = worker.history_identity(samples[0], 2)
        self.assertTrue(worker.phase_matches('seek', samples, 2, identity, 0, {1: 500_000}))
        change_time(samples[-1], 1, 500_001)
        self.assertFalse(worker.phase_matches('seek', samples, 2, identity, 0, {1: 500_000}))
        samples[-1]['activePane'] = 1
        self.assertFalse(worker.phase_matches('active', samples, 2, identity))

    def test_readonly_actual_command_and_selection_writes_cannot_hide_behind_acks(self):
        before = row(1, active=2); samples = [row(i, active=2) for i in (2, 3, 4)]
        identity = worker.history_identity(before, 2)
        result = worker.validate_readonly_interval(before, samples, 2, identity)
        self.assertEqual(result['commandsWrittenDelta'], 0); self.assertGreater(result['stateAcksDelta'], 0)
        for field in ('commandsWritten', 'selectionsWritten'):
            changed = copy.deepcopy(samples); changed[1]['inputWrites'][field] += 1
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                worker.validate_readonly_interval(before, changed, 2, identity)
        for field in ('stateChecks', 'stateAcks'):
            changed = copy.deepcopy(samples); changed[-1]['wire'][field] = before['wire'][field]
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                worker.validate_readonly_interval(before, changed, 2, identity)

    def test_writer_quiescence_requires_repeated_fresh_samples(self):
        samples = [row(i, active=2) for i in (1, 2, 3)]
        identity = worker.history_identity(samples[0], 2)
        self.assertTrue(worker.phase_matches('quiet', samples, 2, identity))
        samples[-1]['inputWrites']['commandsWritten'] += 1
        self.assertFalse(worker.phase_matches('quiet', samples, 2, identity))
        value = row(); value['inputWrites'] = None
        with self.assertRaises(RuntimeError): worker.validate_canvas([value])
        value = row(); value['inputWrites']['commandsWritten'] = True
        with self.assertRaises(RuntimeError): worker.validate_canvas([value])

    def test_failed_preflight_starts_no_server_gui_or_helper(self):
        output = io.StringIO()
        with patch.object(worker.sys, 'argv', ['worker', '--jar-sha256', 'a' * 64]), \
                patch.object(worker.sys, 'platform', 'not-linux'), patch.object(worker.sys, 'stdout', output), \
                patch.object(worker.signal, 'signal'), patch.object(worker.signal, 'alarm'), \
                patch.object(worker.subprocess, 'run') as run, patch.object(worker.subprocess, 'Popen') as popen:
            self.assertEqual(worker.main(), 1); run.assert_not_called(); popen.assert_not_called()
        result = json.loads(output.getvalue())
        self.assertFalse(result['preflightPassed']); self.assertFalse(result['remoteHistoryStreamVerified'])
        self.assertEqual(result['children'], []); self.assertEqual(result['artifacts'], [])

    def test_deadline_and_container_creation_stay_bounded_and_isolated(self):
        command = controller.create_command('sha256:' + 'a' * 64, 'b' * 32, 'c' * 64, 100, 240)
        self.assertEqual(command[command.index('--network') + 1], 'none')
        self.assertEqual(command[command.index('--user') + 1], '1000:1000')
        for option in ('--mount', '-v', '--device', '-p', '--cap-add', '--privileged'): self.assertNotIn(option, command)
        for seconds, timeout in ((79, 240), (101, 240), (100, 219), (100, 301)):
            with self.subTest(seconds=seconds, timeout=timeout), self.assertRaises(RuntimeError):
                controller.create_command('sha256:' + 'a' * 64, 'b' * 32, 'c' * 64, seconds, timeout)

    def test_no_success_without_all_pixels_history_completion_input_and_normal_exits(self):
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(RuntimeError):
            controller.export_artifacts({'passed': True, 'artifacts': [], 'clientExitCode': 0,
                'serverOwnerEofExitCode': 0, 'remoteHistoryStreamVerified': True, 'readOnlyInputVerified': True}, Path(temp))


    def test_reviewed_guard_bodies_and_common_helper_are_byte_pinned(self):
        functions = {'verify_jar': 'c6205ba79490f1edec9edb969ed049677429e9eb275f6af7d1f8f225bc61d350', 'inspect': '3cb25118ada8ebdecb109e727bdfe38c1147547a87c304dacb1dffda007b9024', 'verify_container': '27da775ab388930fee1fbc49d05db829e1b4f64bdc105eebb228fd4c633f2ccd', 'bounded_output': '575c148338fa999fc635e9edb208f7c469ea1ec610e9b941643dc18143bcd534', 'save_attach_output': '6f362e1363fbdc8d103bd7ead95234cf8cfe039c92df21f8ce47796531035e39'}
        source = Path(controller.__file__).read_text()
        bodies = {node.name: ast.get_source_segment(source, node).replace('v7-gui-history', 'v7-gui-live')
                  for node in ast.parse(source).body if isinstance(node, ast.FunctionDef)}
        for name, expected in functions.items():
            self.assertEqual(hashlib.sha256(bodies[name].encode()).hexdigest(), expected, name)
        cleanup = source[source.index('    finally:\n        if attached is not None:'):source.index('    print(json.dumps(result, indent=2))')]
        self.assertEqual(hashlib.sha256(cleanup.replace('v7-gui-history', 'v7-gui-live').encode()).hexdigest(), '5d99b242e6e3c14666d5a00088b9ffc751eda5aa298629058602185e4be8fb0b')
        self.assertEqual(hashlib.sha256(Path(worker.guards.__file__).read_bytes()).hexdigest(), '5e7114cb5587da6567b3527d65482f9d9da8254375cd9435c0784863e36be4d9')

    def test_probe_partial_record_and_resource_failures_are_not_silently_accepted(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'probe'; path.write_text(json.dumps(row()) + '\n{"partial":')
            self.assertEqual(len(worker.read_probe(path)), 1)
            with self.assertRaises(RuntimeError): worker.read_probe(path, final=True)
            log = Path(temp) / 'log'; log.write_text('ordinary Mesa warning\n')
            worker.validate_resources(log)
            for line in ('Freeing 2 leaked images', 'Authority UI font loading: failed', 'Authority action probe failed: timeout'):
                log.write_text(line)
                with self.subTest(line=line), self.assertRaises(RuntimeError): worker.validate_resources(log)

    def test_final_live_positive_control_and_wall_clock_checks_are_required(self):
        rows = [row(1), row(7)]
        observed = [{'wallSeconds': 1, 'stateChecks': 6, 'stateAcks': 6},
                    {'wallSeconds': 7, 'stateChecks': 12, 'stateAcks': 12}]
        worker.validate_canvas(rows, observed, final=True)
        for changed in ({'wallSeconds': 4}, {'stateChecks': 10}, {'stateAcks': 10}):
            bad = copy.deepcopy(observed); bad[-1].update(changed)
            with self.subTest(changed=changed), self.assertRaises(RuntimeError): worker.validate_canvas(rows, bad, final=True)
        bad = copy.deepcopy(rows); bad[-1]['actionProbe']['completed'] = False
        with self.assertRaises(RuntimeError): worker.validate_canvas(bad, observed, final=True)


if __name__ == '__main__': unittest.main()
