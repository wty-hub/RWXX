"""Compare the text-rendering CPU share per presented frame across compact JFR runs.

Raw sample counts measure how long each run was and how busy the desktop was, so the two runs of an A/B
cannot be compared directly: this session measured the identical jar at 60 and at 231 presents per second.
Samples per frame is the stable quantity.
"""
import collections
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'build' / 'rwx-benchmark'
WARMUP = 30.0
TEXT_FRAMES = ('io.github.rwx.render.canvas.KoolCanvasFrameRenderer.writeText',
               'de.fabmax.kool.scene.geometry.MeshBuilder.renderMsdfFont',
               'io.github.rwx.render.canvas.KoolCanvasFrameRenderer.addText')


def frames(tag):
    """New pictures actually presented, from the harness windows."""
    summary = json.loads((ROOT / tag / 'diagnostic-summary.json').read_text(encoding='utf-8'))
    windows = summary['run']['windows']
    total = 0.0
    for window in windows:
        seconds = window['sampleSecondsMeasured']
        total += window['freshSnapshotHz'] * seconds
    return total


def samples(tag):
    path = ROOT / tag / 'compact-profile.ndjson'
    text = 0
    other = 0
    first = None
    for line in path.open(encoding='utf-8'):
        event = json.loads(line)
        if event['type'] not in ('jdk.ExecutionSample', 'jdk.NativeMethodSample'):
            continue
        if first is None:
            first = event['start']
        if (event['start'] - first) / 1e9 < WARMUP:
            continue
        stack = event['stack']
        if any(frame in TEXT_FRAMES for frame in stack[:2]):
            text += 1
        else:
            other += 1
    return text, other


print('%-12s %9s %7s %9s %10s %10s' % ('tag', 'frames', 'text', 'other', 'text/frame', 'textPct'))
for tag in sys.argv[1:]:
    presented = frames(tag)
    text, other = samples(tag)
    total = text + other
    print('%-12s %9.0f %7d %9d %10.2f %9.2f%%' % (
        tag, presented, text, other, text / max(presented, 1), 100 * text / max(total, 1)))
