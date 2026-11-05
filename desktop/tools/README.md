# Vulkan 本机验收工具

## 真实慢帧诊断与图集索引修正（2026-11-02）

`RWX_WINDOWLESS_FRAME_DIAGNOSTICS=1`仅启用test oracle的可选observer，围住真实renderFrame，
含pacer／collect／backend，原pacer不替换。固定容量primitive记录结束后写`timings.ndjson`，
JFR `rwx.WindowlessGpuFrame`逐行核验留存；CPU跨线程或不支持标invalid，不造0。
同进程nanoTime夹取JFR begin/end；GC overlap直接用共同JFR时钟，避免Python跨进程时钟假设。
详细scene JSON每60cycle保留，资源／lease／fence正确性仍逐帧检查。全部为有仪器诊断，
不测显示FPS／物理输入，同步producer也不等于desktop独立owner架构。

完整opening回放诊断各1200预热＋1200采样，G1/Xmx1000m/CPU2/nice15、JFR＋VKtrace，
禁CanvasTrace。发现新pool整16MiB hash出现在57ms慢帧采样中，现改为原blit中维护fingerprint，
仍完整byte比较后才共享；未共享root不做无用索引计算。相同pool复用4/3保持，hash采样4→0；
单轮最大57.37→49.60ms，但P99 29.97→30.66ms，**不支持一致尾延迟或FPS提高的结论**。
仍有约23ms GCPhasePause，旧可见185ms并未被这里归因或证明消除。
最新1.4.3包625项回归（1跳过）0失败、真实6格像素maxDelta0、随包JVM验收通过。
详见[诊断报告](../../docs/verification/render-stall-diagnostics-2026-11-02.json)。

原版无agent控制存活至28秒TERM、退出时PC0崩溃保留。Probe早期Display.update驱动现有ready门禁，
只prepare-only通过离线回归，实际游戏启动待验证；不沿用旧367b probe或8df工具快照。
只读观察和启动控制在用户同一次35秒时段授权内，未激活／操控窗口，没有自动重试。

## 保持采样坐标的图集内存共享（2026-11-02）

`RWX_SHARE_MAP_ATLAS_PIXELS`产品默认ON，设0回退。相同atlas页先hash再逐字节确认，只共享
不可变CPU数组与readonly direct上传内容；每texture仍用独立cursor／limit／fence，增长COW。
packing、UV、shader、GPU纹理及上传流量不变，`RWX_DIRECT_TARGET_TEXTURES`仍默认OFF。
live/replay用`--share-map-atlas-pixels`请求ON，省略显式env0，避免新默认污染历史控制臂。
请求ON须同时有`RWXAtlasPixelSharing reuseConfirmed=true`和
`RWXAtlasPixelUploads reuseConfirmed=true`，仅初始化开关不算实际复用。
比较当前产品默认应同时传`--prepared-map-tiles --frozen-reuse --share-texture-registrations
--share-map-atlas-pixels`；0df…旧包与8df…旧工具快照仍作为已完成尝试的历史证据，不改写。

实际默认512MiB实景完成360个generated／adopted／GPU fence帧，24个相同页各共享一份
16MiB CPU与upload payload；只共享CPU的中间版仍direct OOM，失败原样保留。
候选及最终默认三个快照共4,718,592个真实地图格像素与独立旧atlas精确一致，maxDelta0；
完整core479＋desktop145项无失败（1跳过），工具28项通过。新macOS ARM64包已构建并通过
随包JVM120tick headless验收；这些不能宣称显示FPS提高或原版差距已消除。
`atlas-copy`／`atlas-allocate` trace的byte字段仍是逻辑payload大小，共享路径不能据此推导
物理复制或实际分配字节数。详见[共享验证记录](../../docs/verification/atlas-page-sharing-2026-11-02.json)。

## 真实无窗口 GPU 诊断（2026-11-02）

测试入口 `WindowlessKoolGpuOracle --probe|--map-cell` 和
`RealReplayWindowlessGpuOracle --profile-only=on` 通过内存窗口适配器＋
`VK_EXT_headless_surface` 执行真实 MoltenVK，不由 JUnit 自动运行。
必须显式设置 `RWX_RUN_WINDOWLESS_VULKAN_ORACLE=1`、`RWX_GPU_MAP_CELL_TARGETS=1`；
真实回放另需 `RWX_RUN_REAL_REPLAY_WINDOWLESS_GPU=1`、独立 launch.dir/user.home、完整真实
素材和固定回放，外层必须施加进程硬超时。使用真实 60Hz limiter，不提供无上限 tight loop。
这是 GPU 执行／fence／读回诊断，**不能测显示 FPS、物理输入或原版水平**。

`RWX_REAL_REPLAY_CELL_REFERENCE=1` 要求实际图集共享或direct候选，标记全部性能数据无效；
受控direct诊断须另设`RWX_SHARE_MAP_ATLAS_PIXELS=0`，避免混入当前默认池。它对最终实际
地图格逐个创建独立 atlas 参考并读回全图，严格检查 frozen 版本／frame／registry／附件身份，
还要求参考实际上传和渲染 atlas，不能用空白或两次直接纹理绘制冒充差分。
上下文 MSAA4，实际地图格 offscreen pass 为1，参考匹配实际 pass。

512 MiB 真实拖动缩放复现 atlas 页 OOM；direct 可完成360帧真实提交，但6格共1,572,864像素中
215像素超阈值3，最大差64，集中于爆炸痕迹的Nearest整数texel边界。
**direct仍默认关闭**。两条路径的小型地图格 fixture 均3844项通过，不能代替真实图差分通过。
macOS ARM64 1.4.3 包已构建且随包 JVM 的120tick headless验收通过，尚无官方FPS对照。
详见 [真实无窗口 GPU 记录](../../docs/verification/windowless-gpu-2026-11-02.json)。

## 真实回放 CPU 优化与逐帧差分（2026-11-02）

产品现默认开启冻结内容复用和共享像素注册戳；分别用
`RWX_FROZEN_CONTENT_REUSE=0`、`RWX_SHARE_TEXTURE_REGISTRATIONS=0` 回退。
live／replay 工具仍是显式控制：只有传入 `--frozen-reuse`／
`--share-texture-registrations` 才设为 1，省略时强制为 0，避免默认值污染历史控制臂。

新增手动入口 `desktop/src/test/kotlin/io/github/rwx/benchmark/RealReplayCanvasCapture.kt`，
不会由 JUnit 自动运行。使用 desktop 的 test runtimeClasspath、Java 25、全新隔离的
`launch.dir`／`user.home` 和显式真实 `rwx.assetsDir`；隔离目录须先放入指定回放。
不创建窗口或 KoolContext，安装守卫只允许记录 GPU target 命令，实际 GPU 安装即失败。
完整本机命令、编译目录／依赖指纹及冻结 runtime 见下方验证记录。

参数示例为 `--replay=bridges-fixed.replay --output=<尚不存在的目录>
--seek-tick=6000 --replay-fog=on --mode=pan-zoom --warmup-frames=120 --frames=240 --fps=60`。
预推进使用普通 1x gameLoop，计时前启用正常回放迷雾显示；不注入单位或修改对局规则。
绘制顺序沿用生产的 gameLoop → 可见格重绘 → snapshot/freeze → 后台格重绘。
固定镜头 tape 与名义 60Hz 等待只用于 CPU 诊断，迟帧不追赶；实际间隔另行记录。
逐帧只检查资源级标量，详细命令统计限首末帧，JSON／IO 延后到结束。

同一真实双桥回放在 90–93 单位、迷雾开启、实际缩放 0.45–1.5 下，顺序独立 JVM
A/B/B/A 比较两开关均 OFF／均 ON。拖动缩放的冻结 CPU 降低 69.28%、分配降低
77.19%；四个已测生产阶段的线程 CPU 合计降低 25.83%、分配降低 51.41%。每臂只有
两个进程，实际采样间隔约 19.20–19.64 ms；这些不是 FPS、GPU 或原版对照结果。

`--verify-full-freeze=on` 仅允许显式 `RWX_FROZEN_CONTENT_REUSE=1`，标记为
correctness-only，并使全部性能数据无效。候选先冻结，随后对同一已录制帧暂关复用，
在后台重绘前完整冻结并精确比较 Frame（含 visualStats）及完整资源 Map；同时断言
control 没有复用尝试、开关恢复且候选确实命中。完整缩放周期的 360 帧全部通过、
2579 次实际命中。该差分验证冻结复用；共享注册另由像素更新、透明处理、尺寸、
回退与旧 lease 回归覆盖，不能混称为两项共同的逐帧正确性证明。

默认开启后完整 headless core 474 项（1 跳过）、desktop 145 项无失败；工具 26 项
mock 测试通过，清除候选环境的产品默认和显式双 OFF 实际录制检查均通过。
该 CPU 阶段当时没有新安装包，后续包及 GPU 证据见上节；官方同机 FPS／输入／十分钟
验收仍未完成。所有这些诊断运行无窗口、低优先级，
没有恢复或改写 Steam 安装目录。详见
[真实回放验证记录](../../docs/verification/real-replay-cpu-2026-11-02.json)。

## 官方原版基线准备（macOS / Windows）

`original_interaction_benchmark.py` 支持显式 `--original-root`、`--java`、`--javac`，历史
Windows 默认不变。Mac 默认遵循原版启动脚本，不加 `-XstartOnFirstThread`；只有显式
`--start-on-first-thread` 才加入。classpath 使用平台分隔符。
`--prepare-only` 只校验固定官方核心 SHA、已安装的同字节回放、编译探针并输出 manifest
和 command.json；不会启动游戏，也不生成性能通过结果。输出目录不得位于原版目录内。

用户已在指定 Steam 目录重新下载官方原版，最新对照是官方 PC 1.15，**不是 RWPP**。
根目录官方核心 SHA 固定为 `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`；
完整官方素材、字体、PC 库及原配 Java 8 已复制到独立 `build/original-comparison-20261102/runtime`，
排除旧 RWPP／generated_lib／模组文件，不改 Steam 安装。此前缺少资源的记录是历史状态。
探针分别记录 Display 更新和成功完成的游戏 render 序列，重复显示不能算新画面；样本要求
窗口可见且活跃，失焦不自动修复。固定采样窗口结束后额外保留两秒追踪，尾部冻结按实际
低新画面率报告，不能当成无效样本删掉。准备成功不代表启动／视觉／帧率验收。
历史准备记录见 [官方基线准备记录](../../docs/verification/original-macos-baseline-2026-11-02.json)。

## 启用前的动态冻结与 CPU 候选筛选（2026-11-02）

本节保留默认切换前的局部筛选；当前默认值及真实回放结果以上节为准。

`CanvasFreezeBenchmark.java` 新增 `--update-every=N` 和 `--source-update-every=N`，均默认
为 0。前者每 N 次迭代轮流重录并注册一个地图格，后者提交并注册一个新源纹理版本；
迭代编号连续覆盖预热与测量，更新发生在该次冻结之前。动态计时包含指定的重录／注册
及像素提交成本；两个参数均为 0 时，下节原有“仅冻结与 lease 释放”的范围保持不变。

动态格子重录先发显式 Clear，避免 GPU target 保留旧内容并不断追加命令。计时外检查
实际命令、像素、冻结版本与旧 lease 内容，而不只检查资源数量；动态计时额外保留一个
旧 lease。结果同时报告实际更新次数、store revision 变化、重录绘制数、Clear 数及这项
额外保留。请求的非零间隔若在测量窗口完全不触发会报错；`--update-every` 要求格子数大于 0。

例如使用同一冻结 runtime、先于它放置待比较 core JAR，以顺序独立 JVM A/B/B/A 比较
`--cells=32 --draws=128 --warmup=2000 --frames=4000 --update-every=1
--source-update-every=16`；静态结果应单独记录，不能用静态缓存命中收益代表连续源变化。
`--source-update-every=1` 保留为可选诊断；每帧共享源改写不再作为默认开关或真实
游戏性能的硬门槛。继续使用 Java 25、`nice -n 15`、强制 headless、192 MiB 堆、SerialGC
和 `-XX:ActiveProcessorCount=1`，清除继承的候选／诊断环境变量并保存各臂协议与指纹。

`RWX_SHARE_TEXTURE_REGISTRATIONS` 是新的**默认关闭**候选；live／replay 工具通过
`--share-texture-registrations` 控制，并要求实际日志
`RWXSharedTextureRegistrations sharingConfirmed=true`。省略参数时工具显式设为 0。
它共享引擎间的像素注册戳，资源 lease 与 GPU fence 仍各自持有。既有
`RWX_FROZEN_CONTENT_REUSE` 继续默认关闭；本轮 scope memo 仅在该开关开启时减少重复依赖
记录，不能把它当作默认配置下的提速。要测两者组合，应明确记录两开关的 0／0 与 1／1。

最终同一 core 的组合筛选中，静态每帧分配降低 87.00%；每帧一格、每 16 帧源更新降低
63.06%，CPU 降低 58.36%；每帧源更新的诊断结果为分配增加 104.21%、CPU 增加 72.81%。
该极端诊断不否决候选；候选仍默认关闭，等待真实游戏与纯官方对照。这些只有局部 CPU
筛选意义，未测 GPU、实际新画面率、物理输入或原版水平。
协议、三阶段记录、保留的初版 fixture 失败与兼容性边界见
[候选验证记录](../../docs/verification/canvas-parity-candidates-2026-11-02.json)。

`core/src/test/kotlin/io/github/rwx/render/canvas/CanvasRenderCpuBenchmark.kt` 是另一个手动 main，
不会由 `core:test` 自动启动。它只测预建命令在 `renderInto` 中的 CPU 分派与网格／实例／
材质对象构建；不包含录制、冻结、KoolContext、scene collection、shader 编译、原生 pipeline、
GPU 执行、fence 或呈现。各阶段在计时前后核对绘制顺序、绑定和原始几何位数据，独立 JVM
还比较几何摘要；该检查不能代替像素验收。运行 `--mode=moving --tiles=128 --phases=32`，
分别控制 `RWX_REUSE_MAP_MESH_SLOTS`、`RWX_REUSE_INSTANCED_TEXTURE_SHADERS`、
`RWX_COMPACT_QUAD_STORAGE`、`RWX_COMPACT_TEXTURE_INSTANCES`，不要混入其他候选收益。

本轮没有新安装包，原版同机对照与十分钟流畅性目标仍未完成。用户选择纯官方游戏；
当前指定 Steam 目录含 RWPP 注入，须先定位未修改的官方包，不能直接当作原版基线。
所有本地验证继续无窗口、低优先级，不抢焦点、不激活或置顶游戏。

## 无窗口冻结微基准（2026-11-02）

`CanvasFreezeBenchmark.java` 只测生产路径的快照冻结和 lease 释放，不创建窗口或 GPU context。
它用一个禁止实际安装资源的 installer 守卫来记录 GPU target，随后校验冻结图中 target 的
类型和嵌套命令数量，防止无窗口时静默落到 CPU 光栅化而测错路径。

先冻结改动前后的 core JAR 与同一份桌面 fat JAR，再编译一次这个类。将 core JAR 放在
classpath 中 fat JAR 之前；按 baseline/candidate/candidate/baseline 启动独立进程，
两侧都固定 `RWX_CANVAS_FREEZE_SCRATCH_POOL=1`、`RWX_FROZEN_CONTENT_REUSE=0`、
`RWX_REUSE_FROZEN_TEXTURE_REFERENCES=0`、`RWX_SHARE_IMMUTABLE_COMMANDS=0`、
`RWX_MAP_TEXTURE_BATCHES=0`，清除其他继承的 `RWX_*` 诊断变量。

使用 Java 25、`nice -n 15`（支持时）、`-Djava.awt.headless=true -Xms192m -Xmx192m
-XX:+UseSerialGC -XX:ActiveProcessorCount=1`。命令参数为
`--cells=32 --draws=128 --warmup=3000 --frames=6000`；另测 `--cells=0 --draws=128`
和 `--cells=128 --draws=32`。进程顺序运行，不与构建或其他压力测试重叠。

主指标是每帧线程分配字节数；CPU／墙钟只作局部筛选。该工具不包含录制、GPU 绘制、
输入或呈现，不能用其结果宣称整局帧率提升或拖动缩放已经无顿挫。本轮结果见
[冻结分配验证记录](../../docs/verification/canvas-freeze-allocation-2026-11-02.json)。

## 地图层预备绘制与帧关联诊断（2026-10-30）

按用户要求，产品从本轮默认开启地图层预备绘制；`RWX_PREPARED_MAP_TILES=0` 可关闭。
`map_pan_replay.py` 与 `map_pan_live.py` 为受控对照显式设置此变量：传入
`--prepared-map-tiles` 时为 `1`，否则为 `0`。每个同步 MapLayer 绘制范围内只准备一次不变的纹理画笔；
每块瓦片仍提交待更新源纹理、注册像素版本并读取当前绘制状态，坐标与迷雾顺序保持原样。
它可以独立于 `--map-texture-batches` 使用；不得将已有批次的收益归因给本候选。
运行器要求实际录制日志 `RWXPreparedMapTiles recordingConfirmed=true`。

`--frame-lifecycle-trace` 输出按画面与原生提交身份关联的有界诊断，运行标为
diagnosticOnly。分析和边界见 [帧生命周期诊断](../../docs/frame-lifecycle-diagnostics.md)。
诊断、像素读回及局部录制微基准均不能代替交互性能验收。

后续测试不得影响用户正常工作：不抢焦点、不置顶、不激活或恢复前台应用，不自动
运行可见游戏压力测试。默认采用无窗口、低优先级及单进程验证；不满足前台条件时保留
无效记录，不能通过强制前台或后台限帧数据制造通过结果。

## 视口外文字剔除候选（2026-10-13）

`map_pan_replay.py` 与 `map_pan_live.py` 的 `--cull-offscreen-text` 对应默认关闭的
`RWX_CULL_OFFSCREEN_TEXT=1`。运行器要求日志确认实际剔除了文字，避免把未生效的开关当作对照。
当前实现仅缓存字形边界和对齐宽度；可见文字保留原绘制流程，多行文字、性能 HUD 和嵌套投影不参与剔除。

普通默认配置的独立四轮筛选命令如下，`--tag` 每次须使用新值：

```powershell
python desktop/tools/candidate_interaction_abba.py --tag text-visibility-new-run `
  --mode zoom --fog off --candidate-flag=--cull-offscreen-text
```

这只是候选筛选，不是原版比较或最终验收。第二版默认对照未改善 P99，因此继续默认关闭。
测试范围、实景像素结果和冻结指纹见
[文字可见性验证数据](../../docs/verification/text-visibility-2026-10-13.json)。

## 构建与运行

先构建当前平台的完整运行包，再执行测试。运行器把运行包复制到新的结果目录，避免构建期间修改正在运行的类文件；游戏使用正常退出流程。

```sh
./gradlew :desktop:platformFatJar
python3 desktop/tools/vulkan_native_matrix.py \
  --jar desktop/build/libs/RWXX-1.4.0-macos-arm64.jar \
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
  --jar desktop/build/libs/RWXX-1.4.0-windows-x64.jar `
  --output build/rwx-benchmark/windows-comparison
```

优化前后测试分别传入冻结的旧包、新包，使用不同的 `--output`，并指定相同的 `--only 2000-vulkan-idle`。每个进程预热 30 秒，然后记录三个连续的 30 秒窗口；这三个窗口不是三次独立启动。测试期间不要同时运行构建或其他测试。`--default-backend` 与 Vulkan 的 `--only` 场景配合，可验证没有显式后端选择时的 Windows 启动。

`--diagnostics` 同时记录 JFR、画布重放耗时及 Vulkan 原生指标，会增加运行开销，应独立用于瓶颈定位。正式性能对照使用未开启该选项的结果。OpenGL 的指标取可见交换返回时间，Vulkan 取成功接受呈现的时间；新画面率和接受呈现率均不代表显示器的物理扫描帧率。

## 快速拖图对照与回放诊断

以下 PowerShell 命令从仓库根目录执行，使用 Java 25 或更新版本。先构建当前运行包，再设置路径；如需复查历史结果，把 `$jar` 换成对应的冻结包。每个结果目录必须尚不存在。测试期间保持游戏窗口可见，停止构建及其他测试，并记录供电、DPI 和后台负载。

```powershell
.\gradlew.bat :desktop:platformFatJar
$jar = (Resolve-Path 'desktop/build/libs/RWXX-1.4.0-windows-x64.jar').Path
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
