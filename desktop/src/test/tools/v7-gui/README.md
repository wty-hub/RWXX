# Isolated Linux v7 GUI checks

These tools run the production desktop JAR in a private Linux container using
Xvfb and Mesa software OpenGL. They do not use the host display, audio device,
GPU, home directory, network interface, X socket or a published port. They do
not verify native macOS graphics; the current macOS GUI gate remains unpassed.

Run the controllers on the host. They create, verify and later remove exactly
one fresh owned container. Workers run inside that container only. No controller
builds or pulls an image. Every run requires an immutable local image ID, an
explicit production JAR and its SHA-256. Replay additionally binds both input
recordings by SHA-256. Use a new output directory for every attempt, including
retries, and retain failed evidence.

| Check | Controller | Image recipe | Actual scope |
| --- | --- | --- | --- |
| Menu | `xvfb_gui_controller.py` | `Dockerfile.menu` | Production menu opens, pixels, software GL and clean close; no host/join button click claim |
| LIVE | `xvfb_v7_controller.py` | `Dockerfile.live` | Production server and two independent clients; actual UI selection, production and cancellation; checks/ACKs, player recordings and paired pictures |
| Offline replay | `xvfb_replay_controller.py` | `Dockerfile.replay` | Two recorded player views, four simultaneous panes each, XTest pause/seek/reverse/independent controls, no TCP and no writable action path |
| Remote HISTORY | `xvfb_history_controller.py` | `Dockerfile.history` | Production server and one four-pane client; real completed HISTORY older than the LIVE floor, independent controls, readonly C5/C6 writer totals, continuing LIVE/check/ACK traffic |

All four scenarios have prior Linux runtime evidence with the earlier derived
graphics base. The latest HISTORY run completed nine actual playback phases;
successful Command/Select counts stayed at 2/3 during readonly input while checks
and ACKs each increased by four. Its result is
`desktop/build/verification/authoritative-v7-linux-history-gui-2026-11-07-v1/result.json`.
These results apply to their recorded JAR/image fingerprints. This source
consolidation and the standalone base recipe still need a fresh runtime result
before they can be described as tested together. The new base has not been built
or verified and is not the same immutable image as the prior derived base.
No scenario establishes WAN behavior, large-unit capacity, combat effects,
pixel-golden correctness or macOS acceptance. LIVE and HISTORY use plaintext
container-local loopback; TLS has a separate acceptance path.

## Offline tests

From the repository root:

```sh
python3 -B -m unittest discover -s desktop/src/test/tools/v7-gui -p 'test_*.py'
python3 -O -B -m unittest discover -s desktop/src/test/tools/v7-gui -p 'test_*.py'
```

These tests use fixtures and mocks. They do not start Docker, Java, X11, a socket
or a window. Normal and optimized modes must both pass; runtime guard checks do
not depend on Python `assert`. Source fingerprints in `source-manifest.json`
bind the reviewed worker/controller bodies and shared guard module. They are
tool fingerprints, not a substitute for binding and testing the chosen JAR.

## Build the software graphics base

Prerequisites are Docker with Linux containers, Python 3.11+ on the host, a local
Temurin 25 JRE Ubuntu Noble base image, and a universal production JAR containing
the Linux native dependencies and bundled assets. A macOS-only platform JAR is
not sufficient. For HISTORY the JAR must include exact accepted HISTORY-End
receipts, same-render source provenance and successful C5/C6 write counters;
missing fields fail closed. Archive input must be v7/container3 player recordings.

Choose and record the exact reviewed Java base reference. `Dockerfile.base`
contains the JRE-family and package list used by the existing software-graphics
environment, with its unrelated network-emulation artifacts removed. The apt
repository can change; this is a reproducible recipe, not a claim of bit-for-bit
image reproducibility. Record the resulting image ID and Docker build output.

```sh
TOOLS=desktop/src/test/tools/v7-gui
JAVA_BASE='eclipse-temurin:25-jre-noble@sha256:REVIEWED_BASE_DIGEST'
docker build --pull=false -f "$TOOLS/Dockerfile.base" \
  --build-arg BASE_IMAGE="$JAVA_BASE" -t rwxx-v7-gui-base:local "$TOOLS"
BASE_ID=$(docker image inspect --format '{{.Id}}' rwxx-v7-gui-base:local)
```

Image building can access package repositories. The later acceptance container
has `--network none`; repository access is never enabled in that container.
The base recipe does not need a game JAR, recording or credential. Runtime
isolation is independently checked against Docker's actual inspect response.

## Freeze the input JAR and prepare fresh image contexts

The following commands are examples to execute deliberately. They do not install
or publish the application. Point `JAR` at the tested universal production JAR;
do not substitute an unreviewed file that merely has the same name.

```sh
JAR=/absolute/path/to/frozen-production.jar
JAR_SHA=$(python3 -c 'import hashlib,sys; print(hashlib.file_digest(open(sys.argv[1],"rb"),"sha256").hexdigest())' "$JAR")
RUN_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/rwxx-v7-gui.XXXXXX")

mkdir "$RUN_ROOT/menu" "$RUN_ROOT/live" "$RUN_ROOT/history" "$RUN_ROOT/replay"
for SCENARIO in menu live history replay; do
  cp "$JAR" "$RUN_ROOT/$SCENARIO/game.jar"
  cp "$TOOLS/gui_common.py" "$RUN_ROOT/$SCENARIO/gui_common.py"
  cp "$TOOLS/Dockerfile.$SCENARIO" "$RUN_ROOT/$SCENARIO/Dockerfile"
done
cp "$TOOLS/xvfb_v7_worker.py" "$RUN_ROOT/live/"
cp "$TOOLS/xvfb_history_worker.py" "$RUN_ROOT/history/"
cp "$TOOLS/xvfb_replay_worker.py" "$RUN_ROOT/replay/"
```

`gui_common.py` is the single byte-preserved menu worker and shared network,
geometry and asset guard module. The menu image copies it as `worker.py`;
the other workers import it as `gui_common`. Imports do not launch the menu.
There is no additional launcher abstraction or hidden runtime configuration.

## Menu, LIVE and HISTORY

Build each scenario using the explicit base image ID, then pass its resulting
immutable image ID to its controller. No volume mount is involved: inputs are
copied into the image and checked again inside the worker.

```sh
docker build --pull=false --build-arg BASE_IMAGE="$BASE_ID" \
  --build-arg GAME_JAR_SHA256="$JAR_SHA" -t rwxx-v7-gui:menu "$RUN_ROOT/menu"
MENU_ID=$(docker image inspect --format '{{.Id}}' rwxx-v7-gui:menu)
python3 -B "$TOOLS/xvfb_gui_controller.py" --image-id "$MENU_ID" \
  --jar "$JAR" --jar-sha256 "$JAR_SHA" --output "$RUN_ROOT/menu-result"

docker build --pull=false --build-arg BASE_IMAGE="$BASE_ID" \
  --build-arg GAME_JAR_SHA256="$JAR_SHA" -t rwxx-v7-gui:live "$RUN_ROOT/live"
LIVE_ID=$(docker image inspect --format '{{.Id}}' rwxx-v7-gui:live)
python3 -B "$TOOLS/xvfb_v7_controller.py" --image-id "$LIVE_ID" \
  --jar "$JAR" --jar-sha256 "$JAR_SHA" --output "$RUN_ROOT/live-result"

docker build --pull=false --build-arg BASE_IMAGE="$BASE_ID" \
  --build-arg GAME_JAR_SHA256="$JAR_SHA" -t rwxx-v7-gui:history "$RUN_ROOT/history"
HISTORY_ID=$(docker image inspect --format '{{.Id}}' rwxx-v7-gui:history)
python3 -B "$TOOLS/xvfb_history_controller.py" --image-id "$HISTORY_ID" \
  --jar "$JAR" --jar-sha256 "$JAR_SHA" --output "$RUN_ROOT/history-result"
```

The controllers refuse a reused output directory. A nonzero exit or
`result.json.passed=false` is a failed attempt; do not edit it into a pass.
Default GUI durations are 35 seconds for menu and LIVE, and 100 seconds for
HISTORY. HISTORY waits for the actual default 30-second LIVE retention floor;
it does not shorten the production window or use paused LIVE as fake history.
Its worker has a 240-second deadline and its host has a 300-second collection
envelope. Other controllers also impose their reviewed scenario-specific bounds.

HISTORY success requires a complete same-render receipt matching the pane's
stream, epoch, revision and accepted L/U, with end reason 2. Global archive bounds
in StreamEnd are not substituted for that accepted window. Actual XTest inputs
must change playback while a separate pane keeps following LIVE. After writer
quiescence, ordinary selection/order/action attempts in a historical pane must
leave successful C5/C6 counts unchanged while checks and ACKs continue.

## Offline replay from a successful LIVE run

Use the two formally inspected player recordings emitted by LIVE, or separately
verified compatible recordings. The replay worker opens each recording through
the production strict inspector before launching the UI. It never starts a
server and rejects network statistics in replay probes.

```sh
REC0="$RUN_ROOT/live-result/client-0.rwxr"
REC1="$RUN_ROOT/live-result/client-1.rwxr"
REC0_SHA=$(python3 -c 'import hashlib,sys; print(hashlib.file_digest(open(sys.argv[1],"rb"),"sha256").hexdigest())' "$REC0")
REC1_SHA=$(python3 -c 'import hashlib,sys; print(hashlib.file_digest(open(sys.argv[1],"rb"),"sha256").hexdigest())' "$REC1")
cp "$REC0" "$RUN_ROOT/replay/seat-0.rwxr"
cp "$REC1" "$RUN_ROOT/replay/seat-1.rwxr"
docker build --pull=false --build-arg BASE_IMAGE="$BASE_ID" \
  --build-arg GAME_JAR_SHA256="$JAR_SHA" \
  --build-arg RECORDING_0_SHA256="$REC0_SHA" --build-arg RECORDING_1_SHA256="$REC1_SHA" \
  -t rwxx-v7-gui:replay "$RUN_ROOT/replay"
REPLAY_ID=$(docker image inspect --format '{{.Id}}' rwxx-v7-gui:replay)
python3 -B "$TOOLS/xvfb_replay_controller.py" --image-id "$REPLAY_ID" \
  --jar "$JAR" --jar-sha256 "$JAR_SHA" \
  --recording-0 "$REC0" --recording-0-sha256 "$REC0_SHA" \
  --recording-1 "$REC1" --recording-1-sha256 "$REC1_SHA" \
  --output "$RUN_ROOT/replay-result"
```

Replay uses 60-second timed closes and its own bounded collection deadline.
Four-pane pictures are captured for human review. Nonblank screenshots and
nonzero terrain counters do not by themselves establish correct unit sprites.

## Isolation, evidence and cleanup

Every actual run verifies: network `none`; no published ports, host binds,
devices or shared host namespaces; uid/gid 1000; all capabilities dropped;
no-new-privileges; readonly rootfs; 2 CPU quota, 3 GiB RAM, 512 PIDs, private
256 MiB shared memory and a bounded private `/tmp`. The worker reads IPv4 and
IPv6 links, addresses and routes separately. Only active loopback and the exact
reviewed inactive unassociated kernel tunnel devices are permitted. It never
changes network interfaces or routes.

Each JVM has an isolated home, preferences and native-extraction directory.
Sounds start muted, OpenAL uses its null driver, and no host volume is changed.
Random seat credentials stay in private files; their values never enter argv.
Only allowlisted evidence is exported with size limits and SHA-256 checks.
Credential matches make the run fail and are redacted from exported evidence.

Read `result.json` and `worker-result.json`, then the raw logs, probe NDJSON,
recording inspections and PNGs. Retain `attach.stdout.log` and
`attach.stderr.log` even when parsing fails. A pass requires clean scenario
exits and completed owned-container cleanup. The controller only removes its
fresh UUID/label/ID-verified container; it never enumerates or kills unrelated
containers. It does not delete the built images, copied JAR or exported results.
Review those artifacts before manually removing this run's `RUN_ROOT` or images.
