import json
from pathlib import Path
import tempfile
import unittest

from analyze_render_resource_samples import analyze


class ResourceSampleAttributionTest(unittest.TestCase):
    def test_zero_duration_samples_use_calibrated_time_and_keep_the_complete_stack(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            clocks = [dict(type='rwx.ClockSync', start=x, duration=1000,
                monoStart=y, monoEnd=y + 1000, thread='main')
                for x, y in ((1_000_000_000, 10_000_000_000), (2_000_000_000, 11_000_000_000))]
            stack = ['vmaCreateBuffer', 'MemoryManager.createBuffer', 'GrowingBufferVk.makeBuffer',
                     *['caller' + str(i) for i in range(20)]]
            samples = [dict(type='jdk.NativeMethodSample', start=1_650_000_000, duration=0,
                            thread='main', stack=stack),
                       dict(type='jdk.NativeMethodSample', start=1_900_000_000, duration=0,
                            thread='main', stack=['vmaCreateBuffer', 'VulkanUploadState.allocate'])]
            (root / 'compact-profile.ndjson').write_text('\n'.join(map(json.dumps, clocks + samples)), encoding='utf-8')
            report = {'run': {'windows': [{'sampleStartNanos': 10_100_000_000,
                'sampleEndNanos': 10_800_000_000, 'freshSnapshotLongFrames': [
                    {'acceptedPresentNanos': 10_700_000_000, 'intervalMs': 100.0}]}]}}
            (root / 'diagnostic-summary.json').write_text(json.dumps(report), encoding='utf-8')
            (root / 'vulkan-stages.csv').write_text(
                'startNanos,endNanos,stage,value0,value1,value2\n'
                '10600000000,10670000000,backend-prepare-pipelines,-1,-1,-1\n', encoding='utf-8')
            result = analyze(root)
            sample = result['freshGaps'][0]['samples'][0]
            self.assertEqual(sample['startNanos'], 10_650_000_000)
            self.assertEqual(sample['mappedDurationNanos'], 0)
            self.assertEqual(sample['role'], 'geometry-growing-buffer')
            self.assertEqual(sample['stack'], stack)
            self.assertEqual(len(result['freshGaps'][0]['samples']), 1)
            self.assertEqual(sample['coveringStages'][0]['durationMs'], 70)
            self.assertEqual({(r['window'], r['role']) for r in result['sampleCounts']},
                {('measurement', 'geometry-growing-buffer'), ('outside-measurement', 'persistent-upload-slot')})


if __name__ == '__main__':
    unittest.main()
