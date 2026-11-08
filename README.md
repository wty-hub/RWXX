<div align="center">

![RWXX](logo.svg)

# RWXX

**Built on RWX, focused on low-level performance and stability.**

[![License](https://img.shields.io/github/license/wty-hub/RWXX?style=flat-square&color=blue)](./LICENSE)
[![Downloads](https://img.shields.io/github/downloads/wty-hub/RWXX/total?style=flat-square&color=e74c3c)](https://github.com/wty-hub/RWXX/releases)

English · [简体中文](README_zh.md) · [Upstream RWX](https://github.com/yomi2539/RWX) · [Download](https://github.com/wty-hub/RWXX/releases)

</div>

RWXX is an optimization fork of [RWX (Rusted Warfare eXtension)](https://github.com/yomi2539/RWX),
focused on rendering, frame scheduling, memory and resource management, and platform stability.
Its goal is to reduce underlying overhead and improve input responsiveness and frame-time consistency
in large battles while building on the existing RWX experience.

RWX's P2P multiplayer, Area Control, Linked Maps, and JVM modding provide the project's gameplay foundation.
Current optimization work primarily targets Windows, Linux, and macOS desktops; Android build support is also retained.

## Optimization focus

| Area | Current implementation |
| --- | --- |
| Desktop rendering | All game rendering uses Kool, replacing the legacy Slick game canvas and per-frame readback path. Windows defaults to Vulkan, macOS uses Metal through MoltenVK, and Linux defaults to OpenGL. |
| Engine and presentation | The game loop runs on its own thread, and the renderer consumes completed frames. Input enters the engine in order, and simulation timing is independent of GPU presentation cadence. |
| Map drawing | Invariant paint state is prepared once per map-layer draw scope, alongside map-cell caching and GPU offscreen rendering, reducing repeated preparation and pixel processing. |
| Text and textures | Text meshes, texture metadata, and immutable pixel snapshots are reused to reduce repeated geometry creation and pixel copying. |
| Resource management | Pixel and upload buffers are reused, and in-flight resources retire after GPU completion. Idle caches and pending frames have limits to control accumulation. |
| Frame scheduling | CPU frame production follows the presentation target, with sleeping waits by default. Presentation is capped when the window loses focus, is hidden, or is minimized to reduce unnecessary rendering and busy-waiting. |

These changes are constrained by deterministic simulation, game rules, and existing network, save, and replay behavior.
Replay checks, pixel comparisons, multiplayer validation, and performance tests document their coverage separately;
compatibility claims are limited to those recorded checks.

## Current status

- **Prepared map-tile drawing is enabled by default.** Invariant paint state is prepared once per layer draw. Set `RWX_PREPARED_MAP_TILES=0` to disable it.
- **Desktop Auto mode has a 300 FPS cap.** Saved manual limits remain effective. VSync, power-saving mode, and background state can impose lower limits; background presentation is capped at 30 FPS. A cap is not a measured frame-rate guarantee.
- **Optimization and validation are ongoing.** Rapid panning, continuous zooming, physical input response, long-session smoothness, and previously observed DirectBuffer exhaustion have not completed overall acceptance. Other experimental optimizations retain their individual switches and defaults.

Performance depends on the build, hardware, map, unit count, and settings. Local microbenchmark gains do not directly
translate into whole-game FPS improvements, and results from older builds do not describe the current defaults.
Desktop results do not establish Android performance. See the [current optimization status](docs/NEXT-SESSION.md)
for current defaults and coverage.

## Download and run

Check [Releases](https://github.com/wty-hub/RWXX/releases) for published versions and the artifacts available for each one.
For desktop use, choose the `desktop.zip` matching your OS and architecture; it includes a Java runtime.

| Artifact | Requirements |
| --- | --- |
| `RWXX-<version>-<platform>-desktop.zip` | Includes Java. Extract the entire archive before launching the application. |
| `RWXX-<version>-<platform>.jar` | JAR for one desktop platform; requires a separate Java 25 installation. |
| `RWXX-<version>-all.jar` | Universal JAR containing native libraries for all supported desktop platforms; requires a separate Java 25 installation. |

`<platform>` is `windows-x64`, `linux-x64`, `linux-arm64`, `macos-x64`, or `macos-arm64`.
After extracting the ZIP, launch `RWXX/RWXX.exe` on Windows, `RWXX/bin/RWXX` on Linux, or `RWXX.app` on macOS.
Android packages require a valid signature; an unsigned Release APK built locally cannot be installed directly.

Starting with 1.4.4, macOS stores personal settings in `~/Library/Application Support/rwx/preferences.toml`,
so replacing or moving `RWXX.app` preserves audio, graphics, controls, key bindings, player name, and theme settings.
On first launch, an existing `preferences.toml` in the current application directory is copied there if no user settings exist.
The original is kept, and existing user settings are never replaced.
For the first upgrade from 1.4.3 or earlier, quit the game and back up the old app's `RWXX.app/preferences.toml`
to that user directory before replacing the app. Settings already deleted with the old app cannot be recovered automatically.
An explicit `-Dlaunch.dir=...` keeps preferences in that directory for portable launches and isolated verification.

## Run and build from source

Building requires **JDK 25**. Android builds also require Android SDK 36, configured through `ANDROID_HOME` or `local.properties`.
Run these commands from the repository root. On Windows, replace `./gradlew` with `.\gradlew.bat`.

```bash
# Run the desktop application from source
./gradlew :desktop:run

# Current-platform JAR and desktop application ZIP with bundled Java
./gradlew :desktop:platformFatJar :desktop:packageDesktopDistribution

# Universal JAR with native libraries for all supported desktop platforms
./gradlew :desktop:multiPlatformFatJar

# Android Release APK
./gradlew :android:assembleRelease

# Core and desktop unit tests
./gradlew :core:test :desktop:test
```

Desktop JARs are written to `desktop/build/libs/`, application ZIPs to `desktop/build/distributions/`,
and Android APKs to `android/build/outputs/apk/`.
Platform JARs can be cross-built with `-PtargetPlatform=<platform>`; application ZIPs with a bundled runtime
must be packaged on the matching OS and architecture.

Without a complete release signing configuration, Android `assembleRelease` produces an unsigned APK.
Configure release signing before installation or publication. Signed APKs staged by CI use the filename prefix
`RWXX-<version>-`. See the [CI/CD workflow](.github/workflows/ci.yml) for signing and release details.

## Technical documentation

- [Current optimization and acceptance status](docs/NEXT-SESSION.md): latest defaults, verified coverage, and open issues.
- [Desktop performance](docs/desktop-performance.md): rendering paths, frame scheduling, and optimization history.
- [Engine and Vulkan separation](docs/vulkan-engine-separation.md): threading, input, frame handoff, and resource lifetimes.
- [Windows panning and zooming optimization](docs/windows-map-pan-optimization.md): bottleneck investigation, experiments, and comparisons.
- [Performance tools](desktop/tools/README.md): reproduction steps, metrics, and test limitations.
- [RWX getting started](https://rwx-docs.netlify.app/tutorial/getting-started): basic usage documentation for the upstream project.

Some technical documents retain historical stages and experimental results; current source and the latest status
record define the defaults. Existing `RWX_*` environment variables, package names, and storage directories are retained
for compatibility with existing configuration and data. On Windows / Linux, set `RWX_KOOL_BACKEND=opengl`
to select OpenGL explicitly; macOS uses Vulkan / MoltenVK.

## Feedback and contributions

Report problems through [Issues](https://github.com/wty-hub/RWXX/issues), or submit a pull request to contribute low-level improvements.
For performance reports, include the build, OS and hardware, map or replay, reproduction steps, frame-rate and VSync settings,
and relevant logs. Performance changes should include reproducible before-and-after comparisons and describe the scope
of rendering, input, replay, or multiplayer consistency checks.

## Credits and disclaimer

Thanks to the authors and contributors of [RWX](https://github.com/yomi2539/RWX) and Rusted Warfare.
See [LICENSE](LICENSE) for the project license.

This is an unofficial Rusted Warfare project focused on low-level optimization of RWX.
All related assets used in this project belong to their original authors.
For educational and research purposes only, commercial use is prohibited.
