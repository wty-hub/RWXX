import unittest

from analyze_input_response import analyze


class InputResponseTest(unittest.TestCase):
    windows = [{'sampleStartNanos': 100, 'sampleEndNanos': 1_000_000_000}]

    def rows(self, changes=('pointer', 'key', 'wheel')):
        rows = []
        for index, kind in enumerate(('pointer', 'key', 'wheel'), 1):
            event = dict(id=str(index), kind=kind, value='1', sampledNanos='1000', appliedNanos='2000',
                         producedNanos='3000', acceptedPresentNanos='4000', cameraX='10', cameraY='20', zoom='1', expectsCamera='true')
            rows += [dict(event, stage='sampled'), dict(event, stage='applied'),
                     dict(event, stage='presented'),
                     dict(event, stage='camera-effect', cameraX='11' if kind in changes else '10',
                          zoom='1.1' if kind == 'wheel' and kind in changes else '1')]
        return rows

    def test_each_input_family_must_change_the_camera(self):
        result = analyze(self.rows(changes=('key', 'wheel')), self.windows)
        self.assertFalse(result['validTrace'])
        self.assertEqual(['key', 'wheel'], result['cameraChangedKinds'])

    def test_missing_event_is_kept_as_failure(self):
        rows = self.rows()
        rows.pop(); rows.pop()
        result = analyze(rows, self.windows)
        self.assertFalse(result['validTrace'])
        self.assertEqual('3', result['missingEvents'][0]['id'])

    def test_latency_uses_sampled_time_and_the_actual_accepted_picture(self):
        rows = self.rows()
        rows[-1]['acceptedPresentNanos'] = '20_000_000'.replace('_', '')
        result = analyze(rows, self.windows)
        self.assertTrue(result['validTrace'])
        self.assertAlmostEqual(19.999, result['cameraResponsesByKind']['wheel']['maxMs'])
        self.assertEqual(1, result['cameraResponsesByKind']['wheel']['events'])

    def test_timestamp_inversion_fails(self):
        rows = self.rows()
        rows[-1]['producedNanos'] = '5000'
        self.assertFalse(analyze(rows, self.windows)['validTrace'])

    def test_wheel_cannot_be_credited_with_camera_motion_from_a_key(self):
        rows = self.rows()
        rows[-1]['zoom'] = '1'
        self.assertFalse(analyze(rows, self.windows)['validTrace'])

    def test_activation_is_retained_and_cannot_hide_an_actual_camera_movement(self):
        rows = self.rows()
        event = dict(rows[0], id='4', kind='pointer/activation', expectsCamera='false')
        rows.extend(dict(event, stage=stage) for stage in ('sampled', 'applied', 'presented'))
        self.assertTrue(analyze(rows, self.windows)['validTrace'])
        self.assertEqual(1, analyze(rows, self.windows)['dragActivationEvents'])
        rows[-1]['cameraX'] = '11'
        self.assertFalse(analyze(rows, self.windows)['validTrace'])


if __name__ == '__main__':
    unittest.main()
