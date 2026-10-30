import unittest

from analyze_frame_lifecycle import analyze


class FrameLifecycleTest(unittest.TestCase):
    def event(self, event, now, generation=1, sequence=7, submission=-1):
        return {'event': event, 'eventNanos': str(now), 'generation': str(generation),
                'sequence': str(sequence), 'submission': str(submission)}

    def complete(self, rows, dropped=0, native=True):
        return rows + [dict(self.event('trace-complete', 10_000), recordedEvents=str(len(rows)),
                           droppedEvents=str(dropped), capacity='100', nativeBackend=str(native).lower())]

    def rows(self):
        return [self.event('produced', 100), self.event('chosen', 200),
                self.event('submitted', 300, submission=10), self.event('presented', 400, submission=10)]

    def inputs(self):
        values = dict(id='5', kind='wheel', stage='sampled', sampledNanos='10', appliedNanos='50',
                      producedNanos='101', acceptedPresentNanos='400', generation='1', sequence='7')
        return [dict(values, stage=stage) for stage in ('sampled', 'applied', 'presented', 'camera-effect')] + [
            dict(values, stage='trace-complete', value='4')]

    def test_input_segments_join_exact_picture_and_sum_to_end_to_end_latency(self):
        result = analyze(self.complete(self.rows()), self.inputs())
        self.assertTrue(result['validTrace'], result['invalidReasons'])
        path = result['inputPaths'][-1]
        self.assertEqual(390, sum(path['segmentsNanos'].values()))
        self.assertEqual(390, path['totalNanos'])
        self.assertEqual(1, path['inputPictureTimestampOffsetNanos'])
        self.assertEqual(10, path['submission'])

    def test_submission_identity_cannot_be_replaced_by_a_nearby_event(self):
        rows = self.rows()
        rows[-1]['submission'] = '11'
        result = analyze(self.complete(rows))
        self.assertFalse(result['validTrace'])
        self.assertEqual([], result['framePaths'])
        self.assertTrue(any('exact submission edge' in reason for reason in result['invalidReasons']))

    def test_generation_prevents_sequence_collision(self):
        rows = self.rows()
        rows[-1]['generation'] = '2'
        self.assertFalse(analyze(self.complete(rows))['validTrace'])

    def test_unpresented_mailbox_frame_is_kept_without_crediting_a_picture(self):
        rows = [self.event('produced', 90, sequence=6)] + self.rows()
        result = analyze(self.complete(rows))
        self.assertTrue(result['validTrace'])
        self.assertEqual(1, result['producedButNotPresentedPictures'])

    def test_repeat_keeps_its_own_submit_and_is_not_counted_as_fresh(self):
        rows = self.rows() + [self.event('chosen', 500), self.event('submitted', 600, submission=11),
                              self.event('presented', 700, submission=11)]
        result = analyze(self.complete(rows))
        self.assertTrue(result['validTrace'])
        self.assertEqual(2, result['acceptedPictureEvents'])
        self.assertEqual(1, result['freshPictureEvents'])

    def test_truncated_or_overflowed_trace_cannot_pass_on_a_valid_prefix(self):
        self.assertFalse(analyze(self.rows())['validTrace'])
        self.assertFalse(analyze(self.complete(self.rows(), dropped=1))['validTrace'])

    def test_input_join_requires_exact_accepted_picture_not_similar_time(self):
        inputs = self.inputs()
        inputs[-2]['acceptedPresentNanos'] = '401'
        result = analyze(self.complete(self.rows()), inputs)
        self.assertFalse(result['validTrace'])
        self.assertTrue(any('exact input/picture edge' in reason for reason in result['invalidReasons']))

    def test_inverted_boundary_and_missing_choice_fail(self):
        rows = self.rows()
        rows[1]['eventNanos'] = '99'
        self.assertFalse(analyze(self.complete(rows))['validTrace'])
        self.assertFalse(analyze(self.complete(self.rows()[:1] + self.rows()[2:]))['validTrace'])

    def test_gl_boundary_is_explicitly_not_a_verified_native_submit(self):
        rows = [self.event('produced', 100), self.event('chosen', 200), self.event('presented', 400)]
        result = analyze(self.complete(rows, native=False))
        self.assertTrue(result['validTrace'])
        self.assertFalse(result['submissionBoundaryVerified'])
        self.assertNotIn('submitted', result['supportedBoundaryStages'])
        self.assertEqual({'producedToChosen': 100, 'chosenToAcceptedPresent': 200}, result['framePaths'][0]['segmentsNanos'])

    def test_failed_submission_missing_from_the_trace_cannot_complete_native_path(self):
        rows = self.rows() + [self.event('chosen', 500), self.event('presented', 700, submission=11)]
        self.assertFalse(analyze(self.complete(rows))['validTrace'])

    def test_first_failed_native_submit_cannot_be_misclassified_as_opengl(self):
        rows = [self.event('produced', 100), self.event('chosen', 200), self.event('presented', 400)]
        self.assertFalse(analyze(self.complete(rows))['validTrace'])

    def test_repeated_present_of_one_native_submission_is_not_another_picture(self):
        rows = self.rows() + [self.event('presented', 700, submission=10)]
        result = analyze(self.complete(rows))
        self.assertFalse(result['validTrace'])
        self.assertEqual(1, result['acceptedPictureEvents'])

    def test_input_capture_must_be_complete_counted_and_nonempty(self):
        for inputs in (self.inputs()[:-1], self.inputs()[:-1] + [dict(self.inputs()[-1], value='5')],
                       [dict(self.inputs()[-1], value='0')], self.inputs() + [self.inputs()[-1]]):
            result = analyze(self.complete(self.rows()), inputs)
            self.assertFalse(result['validTrace'], inputs)
            self.assertNotEqual('complete', result['inputCaptureStatus'])


if __name__ == '__main__':
    unittest.main()
