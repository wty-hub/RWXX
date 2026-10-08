import unittest
from pinned_interaction_comparison import assess, normalize_rwx


class ComparisonGateTest(unittest.TestCase):
    def records(self, rwx_fps=310, p99=4, long_frames=0, sample=40):
        return [{'valid': True, 'mode': 'pan', 'arm': arm,
                 'metrics': {'newFps': 300 if arm == 'original' else rwx_fps,
                     'p99Ms': 5 if arm == 'original' else p99, 'p999Ms': 9, 'maxMs': 12,
                     'over16_667PerSecond': .1 if arm == 'original' else long_frames / sample,
                     'longFrames': [{'intervalMs': 17}] * long_frames if arm == 'rwx' else [],
                     'sampleSeconds': sample, 'replaySha256': 'same-replay', 'runtimeSha256': arm, 'toolingSha256': 'same-tooling',
                     'simulationEvidence': {'initialGameTimeMillis': 0, 'startGameTimeMillis': 20_000, 'endGameTimeMillis': 60_000}}}
                for _ in range(7) for arm in ('original', 'rwx')]

    def test_fps_cannot_be_traded_for_better_tail_or_degradation_ratio(self):
        value = assess(self.records(rwx_fps=250, p99=2), 7, ['pan'])['pan']
        self.assertTrue(value['validComparison'])
        self.assertFalse(value['measuredPerformancePassed'])

    def test_good_fps_cannot_hide_more_long_frames(self):
        value = assess(self.records(long_frames=10), 7, ['pan'])['pan']
        self.assertFalse(value['measuredPerformancePassed'])

    def test_short_zero_stall_run_does_not_pass_continuous_gate(self):
        value = assess(self.records(), 7, ['pan'])['pan']
        self.assertTrue(value['measuredPerformancePassed'])
        self.assertFalse(value['continuousWindowPassed'])
        self.assertTrue(assess(self.records(sample=600), 7, ['pan'])['pan']['continuousWindowPassed'])

    def test_missing_or_mismatched_replay_is_not_a_valid_comparison(self):
        records = self.records()
        records.pop()
        self.assertFalse(assess(records, 7, ['pan'])['pan']['validComparison'])
        records = self.records()
        records[0]['metrics']['replaySha256'] = 'different-replay'
        self.assertFalse(assess(records, 7, ['pan'])['pan']['validComparison'])

    def test_diagnostic_fps_cannot_enter_formal_comparison(self):
        with self.assertRaises(ValueError):
            normalize_rwx({'diagnosticOnly': True, 'run': {'validMeasurement': True}})

    def test_mutated_or_missing_measurement_tools_cannot_enter_formal_comparison(self):
        for digest in (None, 'changed-tooling'):
            records = self.records()
            records[1]['metrics']['toolingSha256'] = digest
            self.assertFalse(assess(records, 7, ['pan'])['pan']['validComparison'])

    def test_different_simulation_interval_cannot_enter_formal_comparison(self):
        records = self.records()
        records[1]['metrics']['simulationEvidence']['startGameTimeMillis'] = 25_000
        self.assertFalse(assess(records, 7, ['pan'])['pan']['validComparison'])

    def test_mismatched_fog_cannot_enter_formal_comparison(self):
        records = self.records()
        records[1]['metrics']['fogDisplayEnabled'] = True
        self.assertFalse(assess(records, 7, ['pan'])['pan']['validComparison'])


if __name__ == '__main__':
    unittest.main()
