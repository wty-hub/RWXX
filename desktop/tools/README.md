# Vulkan 本机验收工具

先构建当前平台的完整运行包，再执行测试。运行器把运行包复制到新的结果目录，避免构建期间修改正在运行的类文件；游戏使用正常退出流程。

```sh
./gradlew :desktop:platformFatJar
python3 desktop/tools/vulkan_native_matrix.py \
  --jar desktop/build/libs/RWXX-1.1.0-macos-arm64.jar \
  --java /path/to/jdk25/bin/java \
  --operating-state visible-unlocked
```

请按当前平台替换 jar 路径，使用 Java 25 或更新版本。默认结果保存在 `build/rwx-benchmark/<时间>/`。`--output` 可以指定新的目录；已有测试清单的目录不会被覆盖。运行状态由调用者如实填写，工具不会推断窗口可见性或锁屏状态。

默认执行 661／1000／2000 单位的陆空、海空、综合混编九组测试，每组先预热 30 秒，再连续记录三段 30 秒采样。请求 1920×1080，实际引擎视口与 Vulkan 交换链尺寸写入结果，不能仅依据请求尺寸判定。单位使用正常构造、伤害和命令规则，战损会降低存活数；这类窗口不能标为“持续 2000 单位”验收。

随后执行正常模拟下的 2000 单位单一友军闲置基线，以及上传延迟 50 毫秒、呈现延迟 50 毫秒、隐藏窗口 10 秒的独立短测试。闲置容量与持续交战分别报告。完整运行约 24 分钟。可使用 `--units 2000 --mixes all` 缩小矩阵，或用 `--combat-only` 省略诊断测试。

```sh
python3 desktop/tools/analyze_vulkan_matrix.py build/rwx-benchmark/<时间>
python3 -m unittest discover -s desktop/tools -p 'test_*.py'
```

分析器使用帧 CSV 中的单调时钟及画面世代、序号，按引擎窗口的实际起止时间计算帧率、新画面率、重复比例、p95、p99。窗口边界保留前一张画面的身份，防止把重复帧误算为新画面。分位数采用排序后 `int((数量-1)×分位)` 的位置。

呈现指标表示 `vkQueuePresent` 接受提交的时刻，不等于显示器实际扫描输出。原生指标还记录 GPU 时间戳、上传量、等待与提交耗时、常驻上传内存及退休队列。请保留日志中的实际呈现模式、目标帧率、可见／锁屏状态，以及存活单位数后再解释结果。

需要测试未锁定帧率时，先在游戏设置中关闭垂直同步。运行器保留原有显示设置，并记录实际呈现模式；环境中的 `RWX_DESKTOP_TARGET_FPS` 不能绕过 FIFO 模式或平台的原生 drawable 等待。

Kool 原有默认画质使用 4 倍 MSAA，继续保持该默认值。`--msaa-samples 1` 或 `--msaa-samples 2` 可用于单独研究 GPU 成本；运行器显式指定采样数，原生日志记录实际采样数。直接启动游戏时也可用 `RWX_KOOL_MSAA_SAMPLES=1` 或 `-Drwx.kool.msaaSamples=1`，只接受 1／2／4。降低 MSAA 只影响渲染，但仍需要检查选择标记、线条、字体及特殊效果的画面质量；帧率提高不能替代视觉验收。

## Windows 后端及优化前后对照

`windows_backend_comparison.py` 为每次启动创建独立设置目录，固定 1920×1080、4 倍 MSAA、关闭垂直同步、300 帧上限，并记录机器、供电状态及包的 SHA256。不会改写工作目录中的游戏设置。默认依次比较两种后端的 661 / 2000 单位闲置场景和 2000 单位交战场景；附属单位会使实际存活数高于请求数，以采样记录为准。

```powershell
python desktop/tools/windows_backend_comparison.py `
  --jar desktop/build/libs/RWXX-1.1.0-windows-x64.jar `
  --output build/rwx-benchmark/windows-comparison
```

优化前后测试分别传入冻结的旧包、新包，使用不同的 `--output`，并指定相同的 `--only 2000-vulkan-idle`。每个进程预热 30 秒，然后记录三个连续的 30 秒窗口；这三个窗口不是三次独立启动。测试期间不要同时运行构建或其他测试。`--default-backend` 与 Vulkan 的 `--only` 场景配合，可验证没有显式后端选择时的 Windows 启动。

`--diagnostics` 同时记录 JFR、画布重放耗时及 Vulkan 原生指标，会增加运行开销，应独立用于瓶颈定位。正式性能对照使用未开启该选项的结果。OpenGL 的指标取可见交换返回时间，Vulkan 取成功接受呈现的时间；新画面率和接受呈现率均不代表显示器的物理扫描帧率。

## 快速拖图对照与回放诊断

以下 PowerShell 命令从仓库根目录执行，使用 Java 25 或更新版本。先构建当前运行包，再设置路径；如需复查历史结果，把 `$jar` 换成对应的冻结包。每个结果目录必须尚不存在。测试期间保持游戏窗口可见，停止构建及其他测试，并记录供电、DPI 和后台负载。

```powershell
.\gradlew.bat :desktop:platformFatJar
$jar = (Resolve-Path 'desktop/build/libs/RWXX-1.1.0-windows-x64.jar').Path
$java = (Get-Command java -ErrorAction Stop).Source
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
```

文字网格复用对照将同一个包作为 baseline 和 candidate，按 baseline、candidate、candidate、baseline 启动四个独立进程。每轮在自带地图构建 500 单位、15 队动态移动场景，预热 20 秒，再记录两个 20 秒窗口。两侧均使用默认混合等待和 RGBA 上传，关闭实验性粗等待保护及同步性能窗口日志；只有 baseline 关闭文字复用。

```powershell
$textOut = Join-Path 'build/rwx-benchmark' "text-reuse-$stamp"
python desktop/tools/map_pan_live_comparison.py `
  --baseline $jar --candidate $jar --java $java --output $textOut `
  --baseline-disable-text-mesh-reuse `
  --legacy-short-owner-park --disable-native-bgra-upload --no-perf-window-log `
  --window-width 1280 --window-height 720 `
  --fog off --camera-mode pan --camera-period-seconds 1
python desktop/tools/analyze_owner_pacing_abba.py $textOut --comparison text-reuse
```

这里的 1280×720 是 AWT 逻辑窗口尺寸。DPI 会改变实际引擎视口与交换链尺寸；必须核对 `summary.json` 中的 `matchingMeasuredViewports` 及各轮日志，不能直接与其他分辨率的结果比较。严格分析结果写入 `owner-pacing-abba-review.json`，核对同包 SHA256、真实开关、场景及窗口有效性，并汇总新画面间隔、Java／系统 CPU 和文字缓存计数。文字计数差只包含两个采样端点都在同一测量区间内的完整采样对；查看覆盖秒数，不把边界采样块推算成整段分配量。

当前 `map_pan_live.py` 未指定 `--disable-native-bgra-upload` 时会显式请求实验性 BGRA，尽管直接启动游戏的该路径默认关闭。以上命令用共同禁用参数固定 RGBA。正式 ABBA 不录制 JFR，也不启用 Vulkan 或画布阶段诊断；四轮均保留相同的引擎帧追踪与资源采样开销。记录新画面率和尾部间隔，不能只凭接受呈现帧率判断流畅程度。

若研究默认关闭的短粗等待保护，使用另一结果目录，并保持两侧文字复用开启。共同请求 guard，仅 baseline 请求旧短 park 策略；此例不要再加入共同的 `--legacy-short-owner-park`，否则 candidate 的 guard 也会关闭。

```powershell
$guardOut = Join-Path 'build/rwx-benchmark' "owner-guard-$stamp"
python desktop/tools/map_pan_live_comparison.py `
  --baseline $jar --candidate $jar --java $java --output $guardOut `
  --guard-short-owner-park --baseline-legacy-short-owner-park `
  --disable-native-bgra-upload --no-perf-window-log `
  --window-width 1280 --window-height 720 `
  --fog off --camera-mode pan --camera-period-seconds 1
python desktop/tools/analyze_owner_pacing_abba.py $guardOut --comparison guard
```

原欧洲回放诊断使用单独进程和结果目录，运行器复制运行包及回放，并核对回放 SHA256。将 `$replay` 改为本机文件路径；以下保留本次问题所用文件名。

```powershell
$replay = 'C:\Users\daerh\Desktop\RWXX-1.1.0-windows-x64-desktop\RWXX\app\replays\2080年欧洲回归🌎结盟15p城夺4.0(15p) [v1.15] (3 Oct 2026 16.42.58).replay'
$replayOut = Join-Path 'build/rwx-benchmark' "europe-replay-diagnostic-$stamp"
python desktop/tools/map_pan_replay.py `
  --jar $jar --java $java --replay $replay --output $replayOut `
  --diagnostic --vk-trace --engine-section-trace `
  --legacy-short-owner-park --disable-native-bgra-upload --no-perf-window-log `
  --window-width 1280 --window-height 720 `
  --fog off --camera-mode pan --camera-period-seconds 1
python desktop/tools/analyze_clocked_live_gaps.py $replayOut
python desktop/tools/analyze_replay_normal_epochs.py $replayOut
```

`--diagnostic` 录制 JFR，并自动导出 `compact-profile.ndjson`；另外两个追踪选项记录原生 Vulkan 等待及超过 2 ms 的引擎阶段。这些记录有开销，单次诊断用于定位，不能作为无诊断 ABBA 的性能成绩。

`analyze_clocked_live_gaps.py` 生成 `clocked-gap-attribution.json`，用 `rwx.ClockSync` 的 JFR 与单调时钟起止点分段校准事件时间和持续时间，报告校准残差及边缘外推。固定的 epoch 与 QPC 差值已不能可靠对齐这台机器上的 JFR；缺少有效标记时分析器会拒绝分析，不回退到固定偏移。样本栈与长帧重叠只提供归因线索，不证明某方法独占整个停顿。

`analyze_replay_normal_epochs.py` 随后生成 `normal-replay-gap-attribution.json`，从实际 `load_map` 所在 owner 帧到首次重载后新画面的提交划定同步重载区间，分别保留原始间隔与排除此区间后的普通拖动统计。不能把同步重载的改善计入正常拖图尾帧收益。

已有测量窗口只覆盖指定场景、自动摄像机轨迹和时段。用户最新反馈仍是欧洲地图实际拖动／缩放时 `new` 暴降；这些固定轨迹窗口不能覆盖该问题，也不能据此宣称问题已完全解决。实现、采纳或撤回的实验及证据限制见 [Windows 快速拖图优化实现记录](../../docs/windows-map-pan-optimization.md)。

连续放缩补测在上述回放命令中将相机参数改为 `--camera-mode pan-zoom --zoom-period-seconds 4 --zoom-min .35 --zoom-max 1.5`。这只请求普通 UI 范围内的 `targetZoom`，由正常游戏循环应用缩放；不改变单位和模拟规则。`--diagnostic` 同时生成缓冲写入的 `camera.csv`，记录进入游戏循环前已应用的 actualZoom 和即将请求的 targetZoom，归因时保留两者的时序区别。运行器确认实际缩放范围确实变化，防止旧运行包忽略开关后被算作有效测试。

`--time-based-map-zoom-cache` 请求默认关闭的显示缓存真实时间节拍实验，`--disable-texture-metadata-reuse` 回退纹理元数据命中复用。两者的请求和实际启动日志都须核对。当前真实反馈来自正常联机对局，迷雾显示消失后仍卡；回放缩放只能覆盖地图显示路径，不能替代联机验收。

`map_pan_live.py` 与 `map_pan_live_comparison.py` 也支持同样的连续缩放参数，使用 `--units 500` 可构建与当前欧洲回放数量接近的动态本地场景。运行器核对实际地图迷雾、fixture 数量、每窗移动／弹丸及真实缩放变化。以下对照只切换整格并行，两侧使用两个 worker；实验仍默认关闭。

```powershell
$stripeOut = Join-Path 'build/rwx-benchmark' "live-zoom-stripe-$stamp"
python desktop/tools/map_pan_live_comparison.py `
  --baseline $jar --candidate $jar --java $java --output $stripeOut `
  --units 500 --fog on --camera-mode pan-zoom --camera-period-seconds 1 `
  --zoom-period-seconds 4 --zoom-min .35 --zoom-max 1.5 `
  --baseline-raster-threads 2 --candidate-raster-threads 2 `
  --candidate-parallel-cell-raster `
  --legacy-short-owner-park --disable-native-bgra-upload --no-perf-window-log `
  --window-width 1280 --window-height 720
```

单次运行的 `--parallel-cell-raster`、`--primitive-text-metrics` 分别请求默认关闭的整格光栅和字体度量实验；comparison 的 `--candidate-` 前缀只启用候选侧。`--raster-threads 1..8` 覆盖 worker 数，comparison 分别接受 baseline／candidate 参数。回放工具另提供 `--cpu-target-profile` 逐格记录复制／采样及实际 stripe 路径；它有日志开销，只用于独立诊断。

`--adaptive-cell-raster` 必须配合 `--parallel-cell-raster`，请求按实际采样工作量选择整格并行；comparison 可只启用候选。`--gpu-map-cell-cache` 及 comparison 的 `--candidate-gpu-map-cell-cache` 用于独立 GPU 原型包。运行器要求原生能力日志通过，且测量期已创建格子的命中计数确有增长。复用预热时已创建的格子可以有效，创建计数不必继续增长；仅请求开启、只有预热记录或测量期计数不动均不算有效。回退原因计数没有单调时钟标记，报告保留全进程计数。

GPU 地图缓存实验默认关闭。支持 pass 复用的运行包在开启该实验后，可用 replay／live 的 `--disable-gpu-map-cell-pass-reuse` 回退到每次创建绘制资源；该参数必须同时指定 `--gpu-map-cell-cache`，通过 `RWX_DISABLE_GPU_MAP_CELL_PASS_REUSE=1` 显式传入，并写入协议 `gpuMapCellPassReuseDisabled`。同包对照应在两侧均启用 GPU 缓存，仅基线添加回退参数。旧原型包的缓存命中证据不能证明实际进入 pass 池；24 包需另查原生分配及池复用计数。

工具回归命令如下；2026-10-05 补测时全部 **56 项测试通过**，包含两项新增 pass 复用参数回归。检查参数的实际环境传递、协议记录、继承环境隔离及缺少 GPU 开关时在建立输出目录前失败；该工具检查不启动 Java。

```powershell
python -m unittest discover -s desktop/tools -p 'test_*.py'
```
