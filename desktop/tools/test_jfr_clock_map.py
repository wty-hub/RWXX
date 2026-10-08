import unittest

from attribute_fresh_stalls import jfr_clock_map, render_thread_names


class JfrClockMapTest(unittest.TestCase):
    def test_backend_thread_is_found_after_context_renames_it(self):
        events = [dict(type='jdk.ExecutionSample', thread='renamed-vulkan-owner',
                       stack=['io.github.rwx.render.canvas.KoolCanvasFrameRenderer.renderSurface']),
                  dict(type='jdk.ExecutionSample', thread='RWX-engine-owner', stack=['GameLogic.gameLoop'])]
        names = render_thread_names(events)
        self.assertIn('renamed-vulkan-owner', names)
        self.assertNotIn('RWX-engine-owner', names)

    def test_drift_maps_event_interval_at_large_epoch_precisely(self):
        epoch = 1_800_000_000_000_000_000
        anchors = [dict(type='rwx.ClockSync', start=epoch + 1_004_000_000 * index, duration=1004,
                        monoStart=500_000_000_000 + 1_000_000_000 * index,
                        monoEnd=500_000_001_000 + 1_000_000_000 * index)
                   for index in range(3)]
        convert = jfr_clock_map(anchors)
        self.assertEqual(convert(epoch + 1_506_000_000), 501_500_000_000)
        self.assertEqual(convert(epoch + 1_516_040_000) - convert(epoch + 1_506_000_000), 10_000_000)
        self.assertEqual(convert(epoch - 1_004_000_000), 499_000_000_000)
        self.assertEqual(convert(epoch + 3_012_000_000), 503_000_000_000)

    def test_insufficient_anchors_cannot_attribute_jfr(self):
        self.assertIsNone(jfr_clock_map([]))
        self.assertIsNone(jfr_clock_map([dict(type='rwx.ClockSync', start=1000, duration=1000,
                                            monoStart=90, monoEnd=1090)]))


if __name__ == '__main__':
    unittest.main()
