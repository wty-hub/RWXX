# 原版（Rusted Warfare 1.15 PC）基准测试自动化

> ## §0 续接指南（新会话先读这一段）
>
> **当前目标（用户最新口径）**：让 RWX **拖动地图 / 放大缩小视野时像原版一样流畅**——不是 static 档吞吐。
>
> **已完成**：原版基准全自动化（零输入、不置顶）＋画面证据（`screenshot-window` skill）＋可比配置判定（**回放是开雾的 ⇒ 用 `--fog on` 对照**）＋中位数基线＋交互档（pan / pan-zoom）测量工具。
>
> **§6 的成本模型已修正（2026-10-06，见 §6.3）**：每帧是 **~1,130 条**命令不是 1.1–1.3 万（旧表读错了 CSV 列）；每命令 4–4.7 µs。**命令循环的墙钟瓶颈是 `drawText`（87–88%）**，且 99.99% 的 text 是 run 长度 1 的单发；纹理/矩形的大 run 早已被 instanced 批处理覆盖。所以 §6.1 的「批绘制命令」对文字路径无效。
>
> **但按 CPU 采样口径文字只占进程 11%**（§6.5）——墙钟占比与 CPU 占比是两个口径，不要混用。**字形几何缓存已实测否掉**（跨帧重复率仅 3.1%，§6.5）。
>
> **离屏目标刷新 99.5% 是空转**（2,264,189 / 2,275,257），已加空缓冲区快路径，同一口径复测空刷新已降到 **0**。
>
> **缩放的下一个候选杠杆是低缩放下的格子光栅化尺度**（§6.6），但**收益未证实**：第 2 轮测到 floor=0.7 让低档 `65.2 → 133.5 /秒`，第 3 轮同参数反而 `53.0–63.7 /秒`（比 floor=0 更差）。开关 `RWX_MAP_CELL_MIN_RENDER_SCALE` **默认 0（=原版行为）**，在结论稳定前不要当提速用。覆盖不变式已加进代码（`RWX_MAP_CELL_COVERAGE_ASSERT=1` 告警），实测 floor=0/0.7 均无违例。图集合计只占墙钟约 5%，是次要项。
>
> **交互档实测（2026-10-06，4 次中位数）**：**pan 177.2 /秒、P99 15.2 ms**（zoom 恒为 1.0）已与原版同量级；**pan-zoom 70.9 /秒、P99 89.4 ms**，差距全在缩小视野（zoom 1.2–1.5 时 137.8 /秒，0.35–0.70 时 71.2 /秒）。
>
> **状态与建议（第 40 轮末）**：
> **已完成且可交付**：① 可比测量方法（相对退化比 + 交错采样 + 负载校正 + 同窗口配对纪律）；② 原版探针与 RWX 运动参数完全一致；③ 三个像素安全开关、一个真 bug 修复（依赖 base id）；④ 一份**经实测排除的 18 条方向清单**与 6 条测量纪律 —— 这些能防止后人重走弯路。
> **唯一有同件对照证据的提速**：第 15 轮 **+8% 帧率、P99 −18%**（`RWX_LEGACY_SOURCE_VERSIONING=1` 为对照）。
> **未完成的差距**：移动期静止 **174.2 /秒** vs 移动 **103.0 /秒**（退化比 **1.691×**，原版 1.078×）；根因 = **移动期每帧命令量 3,951（静止 1,151，×3.4）**。
> **建议的下一步**：
> ⓪ **默认目标帧率已解锁为 300**（§6.7 第 51 轮，用户指示）：原来非高刷回落 120，
>    而原版实测 228.8 /秒 ⇒ 此前对照是「拿 RWX 的上限比原版的负载」。
>    解锁后 **静止 174.2→202.8、移动 103.0→132.2、退化比 1.676→1.311**，
>    并已用 `screenshot-window`（`Reliable: True`，`engine 258–262/s`）确认渲染正常。
>    **注意**：基准回放的 Briefing 模态框会遮住地形，需先决定 RWX 侧是否也要像原版探针那样抑制它；
> ① **已完成结构性改动**（§6.7 第 42 轮）：`DrawTextureRepeat`（同源矩形平铺 N 次）跨层实现 8 处，
>    oracle **1537 项 0/768**（开/关两配置），**静止 +13%、移动 +12%、移动期命令/帧 −52%**，
>    开关 `RWX_MAP_TILE_RUN_TILE`（默认开）。**建议保留**；
> ② **GC：数字已纠正，两条改法已排除**（§6.7 第 44–45 轮）：真正 STW = **120–137 次 / 2.5–2.6 秒 /
>    2.7% 墙钟**，p90 40 ms、**max 53–61 ms**（第 43 轮那个 8.8 s / 417 ms 是 JFR 含并发阶段的读数，**已作废**）。
>    **池化早已存在**（`KoolCanvasPixelPool` 按尺寸精确池化 + `preallocateTargetPixels` 预分配 48 个）；
>    **调大 G1 region 无效**（`--g1-region-mb 4`：humongous 92→85、STW 反升、帧率下降）；
>    10 次 `System.gc()` 全在关卡装载路径。⇒ **GC 这条线没有便宜的改法**，要再降只能从源头减少每帧分配；
> ③ **命令量不是地板**（第 42 轮的关键负面结果）：命令量降 2.4 倍只换来 1.13 倍帧率
>    （第 18 轮降 3.5 倍换 1.0 倍、第 27 轮循环内部无热点）⇒ **停止在「减少命令」上投入**；
>    ⚠️ **第 50 轮修正**：这个结论**要打折**。新量出的渲染线程预算是
>    **重放占 39–63%、空档（呈现/等待）占 37%** ⇒ 命令量仍是**最大单项**，
>    降它应仍有效，只是被那 37% 稀释。
>    而且第 42 轮的 A/B **跨了负载剧烈波动的窗口**，按我自己的纪律**应重做**；
> ④ **另一个方向已排除**（§6.7 第 46 轮）：`LatestFrameMailbox` 是**非阻塞单槽邮箱**
>    （owner `publish` 覆盖旧帧、不阻塞；renderer `poll` 最新帧、拿不到就复用上一帧）
>    ⇒ **流水线没有生产者-消费者阻塞，双缓冲设计本来就有**。
>    这也解释了一个长期矛盾：把 `engine-freeze` 从 4,456 降到 476 µs/帧（第 35 轮）**帧率完全不动** ——
>    owner 变快只是**产出更多会被丢弃的帧**；**瓶颈在 renderer 侧每帧成本**；
> ⑤ **下一步唯一还没测过的方向**：**renderer 侧每帧成本的分段测量**
>    （`canvas-prepare` + `canvas-commands` + `canvas-finish` + `envelope-install` 之外还剩什么）。
>    第 42 轮把命令量降 52%、帧率只 +12% ⇒ **88% 的 renderer 成本不在命令处理**，
>    那就应该在网格构建 / GPU 提交 / 呈现这些环节里量出来，而不是继续在命令或冻结上投入；
> ⑥ **但先修口径**（§6.7 第 47 轮）：`engine.csv` 的一行是 **owner 循环递进**、不是渲染帧
>    （54,891 行 × p50 3.34 ms ≈ 183 s，而实际跨度 ~90 s、帧率 117–129 /s），
>    需加一列标识「本行是否产生了被渲染的帧」，否则在其上做预算分解会得出错误结论。
>    该文件里 **`snapshotNanos`（冻结）p50 仅 1.19 ms、`drawNanos` p50 0.80 ms**，
>    而渲染侧 `canvas-commands` 是 **5.5–9.6 ms/帧** ⇒ **再次确认瓶颈在渲染侧命令处理**；
> ⑦ **MSAA 无法在本机 A/B**（第 47 轮）：`--msaa 1` 导致 Intel 驱动崩溃
>    （`EXCEPTION_ACCESS_VIOLATION` @ `igvk64.dll`）。**MSAA 是真实 GPU 成本，但此处测不了**；
> ⑤ **不要**默认开启 `RWX_FROZEN_CONTENT_REUSE`（第 37 轮：端到端无收益、高负载更差）。

> **下一步**（口径 = **相对退化比**，目标 **≤1.0 且高负载下也成立**，即超过原版）：
> ① **靶子 = 移动期每帧命令量**（§6.7 第 39 轮，按帧归一）：静止 **1.14 pass/帧、1,151 命令/帧**；移动 **2.39 pass/帧、3,951 命令/帧**（**×3.4**）。这就是 2.2 倍差距的直接来源。**注意：第 38 轮那个「5,097 vs 150 格子 pass」是两个不同时长运行的绝对数，不可直接比**；
> ② **已排除的三条错路**（本轮）：格子整格重录（reset 93 次仅占约 1.6% 命令量）；pass 数暴涨 34 倍（实为按帧 2.1 倍）；离屏图集（移动场景下格子命令数 1,856 vs 1,866，帧率在噪声内 ⇒ **第 18 轮「降 3.5 倍」是装载期现象，移动场景不成立**）；
> ③ **行合并需要新命令类型**：正确实现要「源矩形随整段行走并平铺」，而一条 `drawTexture` 只能带一个源矩形 ⇒ 需要新的批绘制命令，不是 `MapLayer` 能修的。这是它的**工程量级**，也是两次尝试失败的原因；
> ④ **当前基线（稳定窗口）**：RWX 静止 **174.2 /秒**、移动 **103.0 /秒**、退化比 **1.691×**；原版静止 246.7、移动 228.8、退化比 **1.078×**；
> ⑤ **整帧复用不是通往目标的路径**（第 37 轮低负载配对）：开关两态几乎无差别，高负载下更差。`hits 0%→96.1%`、`engine-freeze 4,456→476 µs/帧` 是真的，但冻结只占每帧约 1/4 且与命令循环并发。**保持默认关闭**；
> ⑥ **已修复的真 bug**（保留）：依赖须按 **base id**（`ref.id`）记录而非版本化复合 id；
> ⑦ **实测排除的方向**（不要再走）：主画面命令条数、文字路径、格子光栅化尺度、`put` 内容判定、全局 revision 泄漏、`Source` 重建、离屏图集（移动场景）、放宽 paint、blend 分层、clip 冗余放宽、地形行合并（拉伸式）、可见性判定、命令循环内部热点、打通 GPU pass（本来就在跑）、整帧复用（端到端无收益）、格子整格重录（reset 仅占 1.6%）、pass 数暴涨 34 倍（实为按帧 ×2.1）；
> ⑧ **纪律**：诊断行不能求和；不要用仪器量仪器；计数器有作用域；诊断开关必须走 harness 参数；诊断结果写文件而不是只写 trace；**比值指标必须检查分母，绝对数不可跨时长比较**；**A/B 必须先看该窗口的负载采样，跳变 >±30% 不予采信**。
>
> **验收门槛**：`oracle` 1537 项 0/768 + `:core:test`（318 项，1 项既有无关失败）。
>
> **复现命令**：§5；**原版自动化四步与混淆映射**：§2；**八个坑**：§3；**交互档与成本修正**：§6.3–§6.6。
>
> **关键资产**：`build/orig-probe/orig-probe.jar`（自动开局探针）、`build/orig-probe/run-orig-pan.ps1`（**零输入**原版 pan 对照）、`build/orig-probe/replay-v115.replay`、原版 `replays/europe15p.replay`、`desktop/tools/repeat_static_benchmark.py`、`desktop/tools/repeat_pan_benchmark.py`（支持 `--camera-mode pan|pan-zoom`、`--zoom-min/--zoom-max`、`--fog`）、`desktop/tools/analyze_jfr_samples.py`、`desktop/tools/compare_text_cpu_per_frame.py`、`build/rwx-benchmark/{pan-r2,pz-r2,z-wide,z-mid,z-low,z-high,zlow-trace,reuse-pan-1,cpu-pan-1,cpu-pan-2,redraw-panzoom-1}`（成本归因原始数据）。





本文件记录"让原版跑出与 RWX harness 相同操作"的方法、混淆映射、坑与最终对照数据。

## 1. 目标与口径

- 同一份回放（`replays/europe15p.replay`，ASCII 名副本）、1280×720、**双方都不做任何镜头操作**。
  - 原版：`-Drwx.orig.pan=off`
  - RWX：`desktop/tools/map_pan_replay.py --camera-mode static --gpu-map-cell-cache --fog off`
    （`static` 档已在 `ReplayPanBenchmark.kt` 支持；harness 侧已豁免 `no measured camera movement`）
- 判据：**新画面/呈现间隔** 的 p50/p95/p99/最大、>33/>50/>100 ms 次数、每秒呈现数。
- 测量纪律：两侧都必须 **4 次运行取中位数**（实测波动：原版吞吐 96–167/秒、p99 16–43 ms；RWX 78–205/秒、P99 13.6–62.8 ms）。测量前清理 `corrodinggames` 进程，避免相互干扰。

## 2. 原版自动化的四个动作（零鼠标键盘）

探针：`build/orig-probe/orig-probe.jar`（源码同目录），启动参数：

```
jvm64\bin\java -javaagent:<probe>\orig-probe.jar \
  -Drwx.orig.trace=<csv> -Drwx.orig.replay=<任意路径，作触发标志> \
  -Drwx.orig.replayname=europe15p.replay \
  -Drwx.orig.autostart.seconds=60 [-Drwx.orig.pan=off] \
  -cp "game-lib.jar;libs/*" com.corrodinggames.rts.java.Main -width 1280 -height 720
```

| 动作 | 实现（混淆名已用字节码/字符串反查确认） |
|---|---|
| 载入并播放回放 | `l.B()`（GameEngine 单例）→ 字段 `l.cb`（ReplayEngine `ba`）→ `ba.c(String):boolean` = `loadReplay` |
| 退出菜单（界面切换） | libRocket `Root.resumeNonMenu()`（Root 实例由 agent 插桩捕获） |
| 抑制开局说明框 | 置空三处：`l.showMessageBox(String,String)`、`java.i.a(String,String)`、`java.i$2.run()`（后两者由日志字符串 `slick messageBox` / `slick queuing-messageBox` 反查得到） |
| （可选）平移镜头 | `l.a(float,float)` = `setViewpoint`，写字段 `cy/cz`；RWX 公式：周期 20 s 三角波，`amplitude=min((world−visible)/2, 800)`，基准取地图中心 |

AGENT 的 ASM 要点：`ClassReader.EXPAND_FRAMES` + `ClassWriter.COMPUTE_FRAMES`，并且 **必须重写 `getCommonSuperClass` 返回 `java/lang/Object`**（否则解析游戏类失败、异常被 JVM 静默吞掉、退回原始字节）；跳过 `static/native/abstract` 方法。

## 3. 坑（都踩过）

1. **回放文件必须存在**：被消费/删除后 `loadReplay` 返回 `false`（`Cannot load replay: Failed to read replay file`）。
2. **名字必须带 `.replay`**：引擎把名字拼成相对路径 `replays\<名字>`（实测 `name resolution -> replays\europe15p.replay`），不自动补扩展名。
3. **非 ASCII 名字会坏**：`-D` 传参会被编码破坏（`🌎` → `??`），所以用 ASCII 文件名副本。
4. **必须在带 GL 上下文的 present 线程调用**：放到后台线程会 `No OpenGL context found in the current thread`，会话被拆掉。
5. **不要用 `Root.closePopup()` 处理引擎/桌面自绘的说明框**：无效，正解是抑制（第 2 节第 3 行）。
6. **菜单坐标每次启动漂移约 40px**：不要复用旧坐标。
7. **原版不要置顶**（用户要求）；截图一律用 `screenshot-window` skill（`PrintWindow` + `PW_RENDERFULLCONTENT`，`Reliable: True` 才可信，`PrintWindow` 在错误的句柄上会返回全黑）。
8. **原版成本随回放相位变化**（同一回放轻载 260/秒、p99 10 ms ↔ 重载 126/秒、p99 32 ms）：比较必须锚定同一回放区间。

## 4. 最终对照（4 次运行中位数，静态档、无镜头操作）

| 指标 | 原版 | RWX | 差距 |
|---|---|---|---|
| 吞吐 | 166.0 /秒 | 134.8 /秒 | RWX 慢约 19% |
| p50 | 5.09 ms | — | — |
| p95 | 10.85 ms | — | — |
| p99 | 19.17 ms | 21.4 ms | RWX 差约 12% |
| 最大 | 103.4 ms | 62.8 ms | RWX 更好 |
| >100 ms | 31 次 / 23 窗口 | 个别窗口 | 均存在 |

**结论**：操作完全对齐后，RWX 落后约 19%（吞吐）与 12%（p99），差距集中在共享画布管线（`canvas-commands` ≈1.15 ms/次、`engine-freeze` ≈1.1 ms/次、雾开尾段超线性占 26%）。此前"2.8×/3×"的说法源于相位错位，已作废。

### 4.1 对照场景（static + 15p 欧洲回放）的分阶段成本

同一场景用 `--canvas-stage-trace` 采集（92 秒跨度，newFps 143.5/151.6、P99 20.8/16.4 ms）：

| 阶段 | 次数 | 总耗时 | 均值 | p95 | 次数/秒 | 占墙钟 |
|---|---|---|---|---|---|---|
| canvas-new（根重放） | 11,549 | 61.1 s | 5.291 ms | 9.813 | 125.6 | 66% |
| **canvas-commands（命令循环）** | 13,145 | **59.2 s** | **4.506 ms** | 9.029 | 142.9 | **64%** |
| engine-freeze | 22,766 | 30.2 s | 1.328 ms | 2.287 | 247.5 | 33% |
| canvas-prepare / finish | 13,145 | 0.78 / 0.69 s | 0.06 / 0.05 | — | 142.9 | ~2% |

⇒ 该场景的成本几乎全在**命令逐条处理**（此场景每遍均值 4.5 ms，远高于 500 单位合成场景的 1.15 ms）与**帧冻结**上。
优化方向：减少命令条数（批处理/合并）或降低单体通路每命令开销（先前已测：单体 8–11 µs vs 批处理 0.73 µs，异质尾段 run 覆盖率低）。

## 5. 复现命令

```powershell
# 原版（4 次）
$probe='build\orig-probe'; $root='C:\Users\daerh\Downloads\Rusted Warfare1.15电脑版\Rusted Warfare'
# 见本文件第 2 节的 java 命令行，csv 用 -Drwx.orig.trace 指定

# RWX（4 次）
python desktop/tools/map_pan_replay.py --jar <jar> --replay <replay> --java <java> \
  --output <out> --camera-mode static --gpu-map-cell-cache --fog off \
  --window-width 1280 --window-height 720 --disable-native-bgra-upload --no-perf-window-log
```

## 6. 命令量与批处理覆盖率（决定优化方向的关键数据）

用 `--canvas-stage-trace` 的 `canvas-new`（value0 = 该遍命令数）与 `canvas-run`（value0 = run 覆盖命令数）统计：

| 配置 | 遍数 | 命令总数 | 命令/遍 | µs/命令 | run 覆盖率 |
|---|---|---|---|---|---|
| 雾关 static | 11,549 | 146,798,845 | 12,711 | 0.416 | **3.8%** |
| 雾开 static | 12,415 | 133,859,615 | 10,782 | 0.413 | **4.5%** |

每遍 µs/命令随规模**单调下降**（<500 条时 125 µs/命令 → 19,500–20,000 条时 **0.158**），大遍更便宜；**≥3,000 条命令的遍占 82% 总耗时**。

**结论（修正目标项 ③ 的措辞）**：
1. 雾开与雾关的每帧命令量、每命令成本几乎一致 ⇒ **不是"雾批次碎片"问题**；
2. 约 **95% 的命令走单体路径**（run 覆盖率仅 4%）⇒ 渲染侧可回收空间接近用尽（`materialBatchOrdinal` 合并实测为中性）；
3. **唯一有量级空间的杠杆是"命令条数"本身**：让引擎少发命令 —— 在 canvas 命令协议中引入**批绘制命令**（一条承载 N 个同类四边形/实例），把每帧 ~1.1–1.3 万条降到千条量级。

### 6.1 建议的下一步（2–3 轮工作量）

1. 新增批绘制命令类型（如 `DrawTextureBatch(贴图, N × 目标矩形/实例数据)`）；
2. 在引擎发出连续同类绘制处聚合为一条（canvas 录制层与引擎绘制调用之间）；
3. 渲染侧消费该命令（复用现有 instanced 批处理）；
4. 验收：`oracle 1537 项 0/768` + `:core:test` + **4 次 `--fog on` static 中位数**，基线 **124.3 /秒、P99 28.8 ms**（目标 ≥166 /秒、P99 ≤19.2 ms）。

### 6.3 §6 的成本模型修正：每帧是 ~1.1 千条命令，瓶颈是文字而不是命令条数

> 2026-10-06 复测。**§6 表格里的「命令/遍 12,711」和「0.41 µs/命令」都读错了 CSV 列**：
> `canvas-new` 的 `value0` 是 `envelope.sequence`，命令数在 `value2`。逐遍实测命令数是
> **1,079–1,183 条**（均值 1,130），不是 1.1–1.3 万；每命令 4.0–4.7 µs，不是 0.41 µs。
> 下面结论用 `RWX_CANVAS_COMMAND_PROFILE`（逐遍命令分布 + 连续同类 run 长度）与
> `RWX_COMMAND_TIMING`（渲染线程按命令种类计时，`-Drwx` 见 `desktop/tools/map_pan_replay.py`
> 的 `--command-profile` / `--command-timing`）重新实测。

**命令条数不是主要成本，文字是。** 同机两次 static 记录：

| 配置 | 命令循环总墙钟 | drawText | 占循环 | text/遍 | 每 text | 每 texture |
|---|---|---|---|---|---|---|
| prof-r5 | 58.4 s | 6.08 M 条 / 50.8 s | **87%** | 479 | 8.36 µs | 2.79 µs |
| prof-r6 | 57.4 s | 9.51 M 条 / 50.6 s | **88%** | 504 | 5.32 µs | 1.69 µs |

`drawText` 的 6.08 M 条里 **99.99% 是 run 长度 1**（逐条单发）；`drawText` 内部 82% 的时间在
`MeshBuilder.text()`（MSDF 每字形 4 顶点 + 6 索引），字形顶点 customizer 每次分配
`MutableVec3f` + `KoolCanvasPoint`，全场约 1.1 亿次。已改为复用 scratch 顶点并内联映射
（`RWX_LEGACY_TEXT_VERTEX_ALLOCATION=1` 可回退），但 A/B 交换配对实测无显著差异——
该路径是解释执行成本，不是分配成本。

**纹理/矩形的大 run 早已被覆盖**：最大的一遍是 16,261 条同类 `drawTexture`（一条 run）；
`drawRect` 平均 run 12.6，>64 的 run 占多数。所以 §6.1 的批绘制命令对文字路径无效，
只可能回收非文字的那 ~12% 命令循环时间。

**离屏目标刷新几乎全是空转**（`flushSplit` 行）：2,275,257 次刷新里 **2,264,189 次（99.5%）缓冲区为空**，
空刷新 1 µs/次共 3.0 s，非空 4,848 次 659 µs/次共 3.2 s。`flushTargetTextureFrame` 现在对空缓冲区
直接构造同样的提交、不再复制命令列表（`KoolCanvasCommandBuffer.isEmpty`）。

### 6.4 pan / zoom 交互档（用户实际手感所在的配置）

static 档不是交互档。`desktop/tools/repeat_pan_benchmark.py` 用同一回放跑三角波平移
（周期 20 s，振幅 `min((world−visible)/2, 800)`），可选 4 s 缩放周期（0.35↔1.5）：

| 档位 | newFps | P99 | 最大 |
|---|---|---|---|
| static（3 次运行区间） | 65.8–231.1 | 9.1–64.2 ms | — |
| pan（3 次运行区间） | 45.9–222.1 | 18.3–132.4 ms | 57–215 ms |
| pan-zoom（4 次运行区间） | 73.7–176.9 | 26.7–104.7 ms | 350 ms |

**缩放比平移贵得多**（pan-zoom 中位 141 /秒 vs pan 中位 179 /秒，P99 41 ms vs 18 ms）。

非输入驱动的原版 pan 对照（`build/orig-probe/run-orig-pan.ps1`，只走探针的 `setViewpoint`，
不碰鼠标键盘、不置顶）：4 次 newFps **113.2 / 193.6 / 240.6 / 236.1**，P99 30.9 / 16.9 / 9.7 / 7.8 ms。
注意 **探针振幅写死 300**（`OrigPresentProbe.PAN_AMPLITUDE`），RWX 是 `min(…, 800)`，
所以这是「原版较小幅度 vs RWX 较大幅度」，重测前应把探针改成可配并统一振幅。

**测量纪律（重要）**：本机在 2026-10-06 凌晨实测 CPU 负载 100%、可用内存 2.3 GB，
同一 jar 的 static newFps 在 5 分钟内从 228.6 掉到 65.8。上面的区间因此是**下界噪声带**，
不能当作验收数。验收必须在空闲机器上、每配置 ≥4 次取中位数（`repeat_static_benchmark.py` /
`repeat_pan_benchmark.py` 就是为此写的）。JFR 的 `jdk.ThreadCPULoad` 可用与负载无关的
线程 CPU 时间复核，`map_pan_replay.py --diagnostic` 已经会导出。

### 6.5 与负载无关的 CPU 归因（JFR 采样，2026-10-06）

墙钟在这台机器上不可用，改用 `jdk.ExecutionSample`（**只在 CPU 上运行时按固定间隔发**，所以计数跟
CPU 时间而不是墙钟走）。`desktop/tools/analyze_jfr_samples.py` 汇总热栈，
`desktop/tools/compare_text_cpu_per_frame.py` 归一化成「**每呈现新画面的 CPU 采样数**」。

pan 档归因（`cpu-pan-1`，13,444 采样 / 2,604 新画面；`cpu-pan-2` 为 11,652 / 3,023）：

| 项 | cpu-pan-1 | cpu-pan-2 |
|---|---|---|
| 文字渲染（`writeText` / `renderMsdfFont` / `addText`） | 1,556 | 1,320 |
| 文字 / 新画面 | 0.60 | 0.44 |
| 文字占全部采样 | 11.6% | 11.3% |

⇒ **文字只占进程 CPU 的 ~11%**，不是命令循环里的 87%（那是墙钟占比，两者口径不同，别混用）；
把它做薄只能拿回约 10%。`writeText` 仍是单帧 CPU 最高的自有帧（16.5% 采样），
`MeshBuilder.renderMsdfFont` 8.97% 次之。

**被否掉的方案：字形几何缓存**。kool 自带的 `CachedTextGeometry` 之所以有效，是因为每个 UI 节点
反复重画同一串；canvas 侧不是。实测（`reuse-pan-1`，`canvas-command-profile.csv` 的 `textReuse` 行）：

```
textReuse,draws,4906198,repeatDraws,152908,frames,4489,repeatPct,3.12
```

**跨帧重复率只有 3.1%** —— 因为缓存键必须带上原点，而 5 个单位的标签 5 次出现是 5 个不同坐标。
加一层 768 项的查表只会给 97% 的绘制增加开销，所以**不实现**。

**空刷新快路径已生效**：同一归因口径下 `flushSplit` 的空刷新从 **2,264,189 次降到 0**
（`reuse-pan-1`：`empty,0 … nonEmpty,6645,nonEmptyUs,522`），非空刷新仍在提交。

### 6.6 缩放专属成本：低缩放下「每格按 1/zoom 倍分辨率重光栅化」

> 修正 §6.6 早先的说法：**图集不是缩放的主成本**（图集合计约占墙钟 5%）。真正的主成本是下面这条，
> 它同时解释了 `draw_setup_drawMap` 的 15 ms。

`zoom-diag-1` / `zlow-trace` 实测（pan-zoom，开雾）：

| 档位（实际 zoom） | newFps | P99 | `draw_setup_drawMap` 均值 |
|---|---|---|---|
| 1.20–1.50（高） | 137.8 | 15.9 ms | — |
| 0.90–1.10（中） | 97.0 | 43.5 ms | — |
| 0.70–1.20（中，宽一点） | 80.0 | 69.9 ms | — |
| 0.35–0.70（低） | 71.2 | 108.9 ms | 15.1 ms |
| 0.35–1.50（全档） | 70.9 | 89.4 ms | 15.1 ms |
| **pan（zoom 恒为 1.0）** | **177.2** | 15.2 ms | 5.7 ms |

即：**RWX 在 zoom=1.0 的平移已与原版齐平（177 vs 原版 236 /秒同量级，P99 15.2 vs 7.8 ms），
差距全部集中在「缩小视野」**。

链路（全部在 `LayerBufferManager`）：

1. `TileMap.updateFogRenderPass` 每帧调用 → `layerBufferManager.setRenderScale(f)`；
2. `computeRenderScale()` = `gameEngine.zoom`（zoom ≤ 1 时），于是 `renderScale` **就是 zoom**；
3. `renderCellContents` 把每个格子按 `renderScale` 光栅化 —— 格子是 512²，zoom=0.35 时
   渲染的是 `512 / 0.35 = 1463` 屏幕像素，**面积是 1/0.35² ≈ 8 倍**（对屏幕上 179px 的显示尺寸而言
   是 8× 过采样，若按「屏幕像素」算则低缩放每格要覆盖 8 倍于高缩放的屏幕面积）；
4. 实测每格重光栅化成本随 `1/scale` 线性上升：

| scale | 1/scale | 每格均值 | 样本 |
|---|---|---|---|
| 0.4 | 2.5× | **1.66 ms** | 780 |
| 0.5 | 2.0× | 1.39 ms | 485 |
| 0.6 | 1.67× | 0.74 ms | 1424 |
| 0.7 | 1.43× | 0.73 ms | 1735 |
| 1.0 | 1.0× | 0.29–1.42 ms | — |

⇒ **下一杠杆：低缩放下不要把格子按 1/zoom 倍分辨率重光栅化**（例如把 `renderCellContents` 的
光栅化分辨率与屏幕显示尺寸对齐，而不是与 zoom 对齐）。这不是图集问题，也不是命令条数问题。
`--time-based-map-zoom-cache`（`ZoomCacheCadence`）只是把**失效时机**从帧数换成时间（阈值 =
`refreshFrames/60` 秒），并不改变单格光栅化的面积成本，所以它不能解决这条。

`updateFogRenderPass` 每帧都调用（`GameEngine.shouldUpdateFogRenderPass()` 恒返回 `true`），
这是原版就有的代码路径，不是 RWX 引入的。

**这条杠杆已实现为 `RWX_MAP_CELL_MIN_RENDER_SCALE`（默认 `0` = 逐位保持原版行为）。
但它的收益目前是「未证实」——两轮互相矛盾**：

| 轮次 | 档位 | floor=0 | floor=0.5 | floor=0.7 |
|---|---|---|---|---|
| 第 2 轮 | 0.35–0.70 | 65.2 | 99.6 | **133.5** |
| 第 2 轮 | 0.35–1.50 | 70.9 | 104.9 | — |
| 第 3 轮 | 0.35–0.70 | 60.0–81.0 | — | **53.0–63.7** |
| 第 3 轮 | 0.35–1.50 | — | — | **68.6–82.5** |

第 2 轮说 **+53%~+105%**，第 3 轮同参数反而**更差**。这台机器的负载噪声足以解释这个量级，
所以 **floor 不能按「已验证的提速」使用**；它现在只是一个待验证的开关，默认关闭。

**覆盖不变式（已加入代码，`RWX_MAP_CELL_COVERAGE_ASSERT=1` 时告警）**：`cellWorldExtent =
cellBufferPixelSize / renderScale`，且每个格子在屏幕上 1:1 合成，所以
`gridCells × cellWorldStepSize` 必须 ≥ 可见世界范围。若某个 floor 让每格覆盖的世界变小到网格
盖不住视口，地图就会「只画一部分」——那会让帧率变好看而画面变错。实测 floor=0.7 在
0.35–0.70 与 0.35–1.50 两档、floor=0 在 0.35–0.70 档，**违例次数均为 0**，即这条机制不是
floor 表现的原因，覆盖也没被破坏。该断言保留为回归守卫。

**结论**：低缩放的成本来源仍未定位；`computeRenderScale()` 的 1/zoom 关系**不是**已证实的病因
（它其实是正确的：那让格子纹理按屏幕像素 1:1 落屏）。下一次要先用与负载无关的口径
（JFR 采样，见 §6.5）而不是墙钟中位数来判定这条，再决定是否采用 floor。

顺带（保留为次要项）：`KoolCanvasSpriteAtlas` 每次新槽位都把**整页 2048² 重传**，实测 173 次
`atlas-texture` 共 1.99 s（均值 11.5 ms，最大 126 ms），`atlas-slot` 308 次共 0.70 s；
页面过期判据是 `frameNumber - lastUsed > 60`，即跨 60 个 pass。合计约占墙钟 5%，优先级低于上面的光栅化分辨率。


### 6.7 低缩放归因（续）：`engine-freeze` 才是最大单阶段，且两个直觉修法都被实测否掉

用 JFR 采样（§6.5）与阶段计时在低缩放档（0.35–0.70，开雾）复测：

| 阶段 | 次数 | 总耗时 | 均值 | 占本次运行（约 86–94 s） |
|---|---|---|---|---|
| `canvas-commands` | 10,592–11,248 | 40.8–43.1 s | 3.8 ms | ~47% |
| **`engine-freeze`** | 14,170–15,370 | **38.5–39.5 s** | **2.71 ms** | **~46%（最大单阶段）** |
| `canvas-new` | 4,168–4,468 | 25.6–27.7 s | 6.1 ms | ~30% |

CPU 采样（`cpu-zlow-1`，12,740 采样）里最大的自有帧换人了：

| 帧 | 占比 |
|---|---|
| `KoolCanvasCpuTextureStore.freezeFrame$lambda$6` | **8.6%** |
| `freezeFrame$texture` | **5.4%** |
| `KoolCanvasTextureId.equals-impl0` | 3.9% |
| `writeText`（文字，已从 16.5% 降到这里） | 9.4% |
| `MeshBuilder.renderMsdfFont` | 4.5% |

即**文字已从 CPU 16.5% 降到约 10%**（第 1 轮的顶点改动确实有效），现在的重心是
`freezeFrame` 的命令重写。

**`freezeFrame` 的实测工作量**（新增 `freeze-detail` / `freeze-repeat` 两个阶段行）：

- 冻结的命令对象总数 **30,558,645**，**每调用 2,156 条**（主画面每帧只 1,329 条 ⇒ 约 900 条/次来自离屏格提交）
- 每命令约 **1.26 µs**
- **两个候选修法都被否**：
  1. 「纹理解析结果等于自身时复用原命令」⇒ 实测 `resolved-to-own-id` **0.0%**，每条命令都得重写；
  2. 「同一 `KoolCanvasFrame` 对象再冻结时复用」⇒ 实测 `repeat` **0 / 149,488 次递归调用（0.0%）**，
     因为每次离屏提交都用一个新的 frame 对象。

⇒ 要动 `freezeFrame` 必须改「一帧记录 → 冻结」的所有权模型（让未变的记录可以整帧复用），
不是加一层缓存就能解决。这是目前唯一被实测确认有量级的剩余方向。

**第 5 轮续：已按这条思路实现「相同记录跳过冻结」，收益很小；并量清了剩余成本在哪。**

实现：`KoolCanvasCommandBuffer` 现在维护一个**顺序敏感的滚动签名**（每加一条命令更新，
`beginFrame` 归零、`clear` 移除命令时重算），`KoolGraphicsEngine.flushTargetTextureFrame`
在 GPU target 分支用「签名 + viewport」判断记录是否与上次提交相同，相同就直接复用
`lastCommittedFrameSnapshot`，跳过整帧冻结。

结果：**命中率低**（首个进程 274 次检查才首次命中，另有 160、428），
`engine-freeze` 从 3,696 µs/次只降到 3,333 µs/次（−10%）。原因与 `freeze-content` 的
97% 并不矛盾：那个 97% 是「冻结结果与上次冻结结果相同」，而**原始记录每次提交几乎都不同**
（每帧重录 900 条），所以签名匹配很少。

**剩余 44 s 的成本构成（实测）**：

| 项 | 数值 | 说明 |
|---|---|---|
| 冻结的命令对象 | **237,773,257** 条 | 每命令约 **1.26 µs** 就是这一项 |
| `DrawTexture` 的纹理解析 | 216,516,937 次 | **100% 走到新身份**，`sources` 每次提交都被换版本 |
| 身份可复用（`sameRef`） | **0 次（0.0%）** | 跨冻结调用无法复用；单次调用内靠 `frozenReferences` 复用 |
| 每调用命令数 | 2,156 条 | 主画面每帧仅 1,329 条 ⇒ 约 900 条/次来自离屏格提交 |

⇒ 两条路都被实测堵住：**跳过冻结**（记录几乎总在变）与**复用命令对象**（纹理身份几乎总在变）。
继续降 `engine-freeze` 只能动「每次提交都重录约 900 条命令」这个上游行为
（即让未变化的格子不提交），而不是在冻结侧做文章。

**第 6 轮：跨帧复用冻结内容的尝试 —— 机制正确，但这个负载下几乎不命中。**

思路本身是对的：一个格子的冻结内容会被**每一个显示该格子的画面**采样，而 5×5 网格的平移回放
会让每个格子被数百个画面采样；`freezeFrame` 的 per-call 身份备忘跨调用无效，所以遍历被反复重做。
于是我按 `Source` 的**版本 id** 缓存冻结结果，并把它记进 `resources`。

第一版（只比版本 id）**是错的**，被既有单测 `KoolCanvasFrameEnvelopeTest."all nested frames and
displacement screen base are included in every packet"` 抓住：**被保留的 frame target 在它绘制的
sprite 被重新注册时，自己的版本并不会变**，所以只看 target 版本会返回过期内容。

修正：冻结时记录这次遍历读到的**全部原始 source id 及版本**（`frozenDependencies`），
复用时逐个核对。这样是可靠的，但**命中率崩到几乎为零** —— 因为依赖集里只要有一个源变了，
整棵子树就必须重冻。用与负载无关的口径核对（冻结命令对象数）：

| 版本 | freezeFrame 调用 | 冻结的命令对象 | 命中 |
|---|---|---|---|
| 无复用（第 5 轮基线） | 11,769 | **237,773,257** | — |
| 跨帧复用（不可靠版） | 14,556 | 27,806,102 | 大量（但内容可能是过期的） |
| 跨帧复用（依赖核对版，正确） | 10,061 | **200,200,019** | 仅少量 |

⇒ **正确性与收益在这里是直接冲突的**：回放里每个格子的依赖集几乎每次都包含变化的项，
所以可靠的复用省不下遍历。这条保留为正确实现（有测试守护），但不作为提速手段。

**结论：`engine-freeze` 的 ~42 s 在冻结侧无法再降，必须动上游（让未变化的格子不重新提交）。**

**第 7 轮：定位到递归里到底是谁 —— 512×512 的格子占 93%。**

新增 `freeze-heavy` 阶段行，按**嵌套 viewport 尺寸**归集冻结命令对象数（低缩放平移，开雾）：

| 嵌套 viewport | 冻结命令对象 | 占比 |
|---|---|---|
| **512×512**（图层缓冲格子） | **159,136,898** | **93.4%** |
| 1280×720（主画面） | 11,242,721 | 6.6% |
| 全部 | 170,379,619 | |

同时：每次 `freezeFrame` 冻结的对象数是 **p50 19,152**，而 `frame.commands` 只有 **p50 1,279**
—— 即**每次冻结都要把当时可见的约 25 个格子的内容整棵重走一遍**（每个格子约 1,280 条命令 × 约 15 层）。

为什么依赖核对救不了它（第 6 轮那个正确但低命中的实现）：格子被主画面采样时，
`texture(cellRef)` 取到的是格子**提交时**记录的 frame；`frozenDependencies` 里只要有一个源
（例如每帧重新注册的雾/地形源）版本变了，整棵子树就必须重冻。

**这里有一个结构性约束，是下一轮真正要处理的东西**：冻结产物的 id 是**按内容版本**生成的
（`.../gpu-N`、`.../frozen-N`），因为渲染侧按 id 缓存网格、重绑图像。若为了让 id 稳定而复用旧 id，
渲染侧就会跳过更新、显示过期内容；若不复用，每条命令每次冻结都要分配新的 ref 与命令对象。
所以「让未变化的格子不重新冻结」必须**同时**给出一个「内容没变就保持同一个 id」的判据，
而不是单改冻结侧 —— 这也解释了为什么前面几轮的冻结侧优化都撞墙。

**第 8 轮：把这条约束钉死到具体代码，并撤掉一个零收益的改动。**

试了「增量冻结」：拿上一次的冻结列表当模板，逐条比较，相同就复用旧命令对象。实测
**复用率 0.00%**（`freeze-command-reuse` 的复用计数为 0，对 228,409,811 次分配），
`freeze-version-reuse` 也是 0。实现已撤掉（不留热路径复杂度）。

为什么必然是 0，现在有两个具体原因：

1. **版本 id 用的是全局修订号**：`put(id, resource)` 每次注册都 `revision++`，并把
   `"${id.value}/cpu-$ownerId-version-${revision + 1}"` 作为新 `Source` 的 `versionId`
   —— 所以版本**只反映「注册过」，不反映「内容是否相同」**；
2. **每次提交都造新对象**：`KoolGraphicsEngine.flushTargetTextureFrame` 走
   `registerGpuTargetFrame(id, frame.copy(commands = frame.commands.toList()), ...)`，
   `registerFrame` 同样 `frame.copy(...)`。格子内容即使逐位相同，也一定得到新的 `Source`
   与新的 `versionId`。

⇒ 于是 `sources[id]?.versionId` 每次都变，`isFrozenContentCurrent` 永远为假，
依赖核对与模板复用都无从命中。

**修正点因此非常具体**：在 `put()` 之前判断「新 resource 与旧 resource 内容相同」，
相同就**保留旧 `Source`（含 `versionId`）不动**。这样 id 才具备「内容不变则稳定」的语义，
下游的依赖核对与增量复用才会真正开始命中。这是下一轮的第一件事。

同时注意：`registerFrame` / `registerGpuTargetFrame` 在 `put` 之前就 `frame.copy(commands.toList())`
复制了一次命令列表；若内容判定通过，这次复制也可以一并省掉。

**第 9 轮：实现了上面的修正，但实测证明它不在这条路径上 —— 假设被推翻。**

实现内容：`KoolCanvasCpuTextureStore.put()` 现在会比较新旧 resource 的内容
（`Frame`/`GpuTarget` 比对 frame，`Pixels` 比对 image 与 static 标志，`Asset` 比对 path 与字节），
相同则保留旧 `Source` 与旧 `versionId`。开关 `RWX_LEGACY_SOURCE_VERSIONING=1` 可回到旧行为做同件 A/B。
实现过程中被既有单测 `KoolCanvasFrameEnvelopeTest."asset fallback owns encoded file version across
reload and delayed consumption"` 抓到一次：我最初只比 `Asset.path`，而重载后的资源**路径相同、字节不同**
—— 已改为同时比对字节。

同件 A/B（低缩放平移，开雾，阶段计时）：

| 臂 | 帧数 | freeze 次数 | 冻结对象/帧 | freeze ms/帧 |
|---|---|---|---|---|
| 旧行为（`RWX_LEGACY_SOURCE_VERSIONING=1`） | 3,396 | 15,020 | 90,756 | 14.25 |
| 内容判定版 | 3,036 | 13,811 | 92,284 | 15.21 |

**没有可测差异**（帧率也 85.0 vs 75.9，方向相反）。第一版看起来「冻结 ms/帧 从 16.2 降到 11.0」
是机器负载噪声——同件 A/B 才是可比口径。

**为什么没有差异，原因查清了**：给 `put` 加计数后发现，整个回放运行里
**`put` 只被调用 1 次**（`Pixels->Pixels`）。也就是说，512×512 那 93.4% 的嵌套冻结
**根本不是经由 `KoolCanvasCpuTextureStore.put` 提交的** —— 本机启用了 `--gpu-map-cell-cache`
（`gpuMapCellTargets=true`），格子走 GPU offscreen pass 路径，
`registerGpuTargetFrame` 在这个配置下不被调用。

⇒ 上一轮把「`put` 无条件 `revision++`」认定为根因是**错的**；它确实是个语义缺陷，
但在这条负载路径上没有触发。**512×512 嵌套冻结的真实来源仍未定位**，下一步要先查清
GPU offscreen pass 路径（`KoolCanvasGpuTargetPasses` 与 `FrozenCanvasGpuResources`）
是怎么让格子内容每次都被重冻的。

内容判定这版**保留**（语义上更正确、有开关、两个门禁都过），但不作为已验证的提速手段。

**第 10 轮：连「格子走 GPU offscreen pass」这个前提也被实测推翻了。**

给 `KoolGraphicsEngine.flushTargetTextureFrame` 的目标路径判定加了计数器
（`gpuTarget` 为真 = 走 GPU offscreen pass；为假 = 走 CPU 光栅/其它）。低缩放平移、开雾、带
`--gpu-map-cell-cache` 的实测结果：

```
[RWX canvas] gpuTargetPaths offscreenPass=0 rasterOrOther=2000 total=2000
```

**`offscreenPass=0`** —— 即使 `--gpu-map-cell-cache` 打开（`gpuMapCellTargets=true`、
`gpuMapCellSupported=true`），**一次都没走 GPU offscreen pass 路径**。运行日志里的
`gpuTargets[created=32 versions=0 rendered=0 resolves=0 live=0 pooled=32]` 与此一致：
GPU target 只被创建，从未被渲染或解析。

⇒ 我上一轮「格子走 `KoolCanvasGpuTargetPasses`，所以不经过 `put`」的解释也是**错的**。
目前的实测事实是：

1. 目标刷新走的是**光栅/其它**路径（2 000/2 000）；
2. `registerGpuTargetFrame` 在这条路径上不被调用；
3. 但 `put` 全场只被调用 **1 次**（`Pixels->Pixels`）。

第 2 与第 3 条目前**无法同时用「格子经由 store 注册像素」解释**，即
**512×512 那 93.4% 的嵌套冻结到底由哪个注册路径产生，仍然没有定位**。
下一步不该再猜，而应直接在 `freeze()` 里按**产生注册的那一侧**打点：
记录每个 512×512 嵌套帧对应的 `FrozenCanvasResource` 子类型与其来源 id，
把「谁注册了它」一次问清楚。

**第 11 轮：按上面的方案打点，拿到了嵌套冻结的归属与拒绝原因。**

新增打点（`freezeNested viewports=… kinds=… reuseTerms[…]`）：

1. **512×512 的嵌套帧全部是 `GpuTarget`**（`viewports=GpuTarget 512x512`；
   主画面侧是 1280×720）。上一次「GPU pass 没被用」的读数与它并不矛盾：
   这里的 `GpuTarget` 资源是**由 store 注册进来的**，与该次刷新走哪条路径无关；
2. **整帧复用的拒绝原因是唯一的**：`reuseTerms[vMismatch=8 noVer=0 noFrame=0 noDeps=0 depChanged=0/0]`
   —— 四个可能的失败项里只有 **`frozenSourceVersionId != versionId`** 在计数，
   依赖变化（`depChanged`）几乎为 0。也就是说：**不是依赖把它判死的，是它自己的版本在变**；
3. 每次 `freezeFrame` 平均只有 **2 个** 512² GpuTarget 参与嵌套冻结（viewports 计数
   8/12/14/20 对应 4/6/7/10 个采样帧），不是 25 个 —— 与「p50 19,152 个冻结对象/次」一起看，
   说明**单个格子的录制内容很大**（约 9 千条命令），而不是格子数量多。

**顺带修掉一个我自己的测量错误**：第 9 轮「`put` 全场只被调用 1 次」是错的 ——
那个计数器写在内容判定分支**内部**，而内容判定正是被测对象，所以它只统计了
「进入判定且内容相同」的次数。改为无条件计数后确认 `put` 调用数 < 20,000（未触发 2 万次的打印阈值），
即**远小于** 512² 版本变化的次数（每次 freeze 就有 8–20 次 `vMismatch`）。

⇒ 结论收窄为：**格子的 `Source.versionId` 在每次 `freezeFrame` 之间都会变，而 `put` 的调用频次远不足以解释它。**
下一步要查的是**除了 `put` 之外，还有什么会给 `sources[id]` 换 `Source`**（或让
`sources[id]` 指向新对象），而不是继续改 `put`。

**第 12 轮：找到并修掉了一个真实的版本语义缺陷，但复用仍然为 0，且拿到一个新线索。**

`sources` 的写入点只有两处：`put`（换新 `Source`）与 `unregister`（`sources.remove`）。
逐个核对后发现问题在**版本号的构造**上：

```kotlin
KoolCanvasTextureId("${id.value}/cpu-$ownerId-version-${revision + 1}")
```

`revision` 是**整个 store 的全局修订号**，每次 `put`/`unregister` 都自增。
于是**任何一个无关源的变化都会让所有源的版本号一起变** ——
这解释了为什么依赖核对、模板复用、内容判定三种做法都拿不到命中。
已改成**按 id 的独立序号**（`sourceSerials`，仅在内容变化时自增，`unregister` 时清除）：

```kotlin
KoolCanvasTextureId("${id.value}/cpu-$ownerId-v${sourceSerial(id) + 1}")
```

实测确认修复生效：版本串从 `cpu-1-version-N` 变成 `cpu-1-v2/v3/v4`（按 id 递增）。
两个门禁都过；跑分 104–125 /秒。

**但复用仍然是 0，而且拿到了一个更硬的线索**：打印不匹配时的**实际版本串**，

```
[RWX canvas] vMismatch stored=null live=legacy-texture-79/cpu-1-v2
```

**`stored=null`** —— `frozenSourceVersionId` 根本没被赋值过，而
`isFrozenContentCurrent` 的第一项比较是 `null != versionId`，所以**它每次都在第一项就返回 false，
后面的依赖检查（`noVer=0 noFrame=0 noDeps=0 depChanged=0/0`）全部没被执行**。
这也解释了为什么第 11 轮看到的「拒绝原因唯一是 `vMismatch`」。

代码里两处赋值（`Frame` 与 `GpuTarget` 分支）都在 else 分支内、与 `freeze(resource.frame)` 同级，
所以**只可能是 `sources[id]` 每次返回的是一个新的 `Source` 实例**。
下一步要验证的就是这一条：逐个检查 `put` / `unregister` 之外的路径是否会重建 `Source`
（`Source` 是 data class，而 `sources` 的键 `KoolCanvasTextureId` 是**字符串值类**——
若键的身份在不同调用间不一致，旧 `Source` 实例会被丢弃，赋值也就丢了）。这是下一轮的第一件事。

**第 13 轮：`Source` 没有被重建 —— 上面的假设也被推翻。**

给 `put`/`unregister` 加了**全局**计数（跨所有 store），并在不匹配处打印 `Source` 的身份与所见次数：

```
[RWX canvas] vMismatch stored=null live=legacy-texture-79/cpu-1-v2 srcIdentity=1899299831 seenCount=0 sourcesSize=505
```

两条事实：

1. **`put` + `unregister` 总数不足 50,000**（跨所有 store 的全局计数没触发 5 万次的打印），
   远不足以支撑「每帧重建 Source」——所以**没有任何路径在重建 `Source`**；
2. 每次不匹配时，该 `Source` 的 `seenCount` 都是 0 —— 这是**它第一次被解析**，
   `stored=null` 正是「还没赋过值」的正常初始状态。

第 2 条也顺带说明：这些不匹配**不是**「同一个 Source 上次赋了值、这次对不上」，
而是**大量只被解析一次的源**。而 `isFrozenContentCurrent` 只在
`FrozenCanvasResource.Frame` / `GpuTarget` 两个分支里被调用，所以这些
`legacy-texture-*` 是**作为 Frame/GpuTarget 的资源 id** 出现的，其中
`-v2` 表示它们各自被注册过 2 次。

⇒ 到目前为止，已经用实测排除掉：命令条数、文字路径、图集、格子光栅化尺度、
`put` 内容判定、GPU offscreen pass 路径、全局 revision 泄漏、`Source` 重建。
**结论：这条「整帧复用」的路走不通，不是因为某一个 bug，而是因为每次
`freezeFrame` 面对的都是首次解析的源集合** —— 也就是说，
**重冻不是缓存失效造成的，而是每帧确实有新的源进入冻结**。
下一步不该继续查缓存，而应查**为什么每帧都有新的 Frame/GpuTarget 源进入**
（即那些 `-v2` 的第二次注册是谁发起的、为什么必须发生）。

**第 14 轮：跨调用统计纠正了上一轮的措辞，并测出一个关键反例。**

上一轮说「每次 `freezeFrame` 面对的都是首次解析的源集合」（依据是单次调用内的
`seenCount=0`）。跨调用统计（进程级身份集合）**否掉了这个说法**：

```
[RWX canvas] sourceReuse resolved=50000  distinctSources=1337  withStoredVersion=48663  versionReuse=0
[RWX canvas] sourceReuse resolved=100000 distinctSources=2405  withStoredVersion=97595  versionReuse=0
```

- **97.4% 的解析都带着已存的 `frozenSourceVersionId`**（`withStoredVersion / resolved`）；
- 身份数随解析数增长（1337 → 2405），说明 `Source` **没有被重建**；
- 但 `versionReuse` **始终为 0**。

这说明我第 13 轮的措辞（「都是首次解析」）是错的：**绝大多数解析用的都是有标记的旧 `Source`，
可复用仍然不发生**，所以卡点就在赋值与比较这一对操作上。

分项计数也把范围收窄了（`reuseTerms`，早期与晚期都采样过）：

| 项 | 观测 |
|---|---|
| `vMismatch` | 早期 8–20/次，**晚期降到 0** |
| `depMissing` | 晚期 12/次（依赖被 `unregister` 掉） |
| `depChanged` / `noVer` / `noFrame` / `noDeps` | 始终 ≈ 0 |
| `versionReuse` | **始终 0** |

⇒ 晚期真正阻断复用的是 **`depMissing`**（依赖源已被注销），
而 `vMismatch` 只是装载期的短暂现象。**我前几轮一直盯着的 `vMismatch` 不是长期主因。**

**下一步（已收窄）**：`isFrozenContentCurrent` 在晚期被 `depMissing` 挡住 ——
即冻结时记录的依赖 id 之后被 `unregister` 了。要查的是
**那些依赖为什么会被注销**（谁调用 `unregister`、是否过早），而不是继续查版本号。

**第 15 轮：找到了那个反复注销的源，并对累计改动做了同件 A/B。**

打印缺失依赖的具体 id：

```
reuseTerms[vMismatch=0 … depMissing=6 … versionReuse=0] missingTop=legacy-texture-47/cpu-1-v230x6
```

关键在版本号：**`legacy-texture-47/cpu-1-v230`** —— 这个源已经被注册过 **230 次**，
而它仍然在被 `unregister` 掉。也就是说**有一个贴图在被反复注册又注销**，
每个依赖它的格子冻结都会因此判定失效。**整帧复用被这一个源「毒化」了**，
这也是为什么 `versionReuse` 在所有轮次里都是 0。

`unregister` 的唯一调用方是 `KoolGraphicsEngine.releaseKoolTexture`（贴图释放回调）。

**累计改动的同件 A/B**（`RWX_LEGACY_SOURCE_VERSIONING=1` 作为对照，同一 jar）：

| 指标 | 对照（旧语义） | 当前 |
|---|---|---|
| newFps | 131.8 / 136.9 | **145.0 / 146.2** |
| P99 | 45.8 / 46.9 ms | **37.2 / 38.3 ms** |
| 冻结对象/帧 | 45,784 | **42,498（−7.2%）** |
| freeze ms/帧 | 6.14 | **5.51（−10.3%）** |

⇒ 累计改动（按 id 版本序号 + 内容判定注册）**方向正确且有一致的小幅收益**
（约 +8% 帧率、P99 −18%、冻结工作 −7~10%），但**远不是量级收益** ——
因为整帧复用被上面那个反复注册的贴图挡住了。

**剩下的关键问题因此非常具体**：`legacy-texture-47` 为什么会以约 230 次的数量级
反复注册/注销？如果它是**每帧重新生成**的贴图，那么任何依赖它的格子内容本质上每帧都变，
整帧复用在这条路径上就不可能成立；如果它是**被错误地过早释放**，那就是一个可修的缺陷。
下一步应当先判明它属于哪一种（在 `releaseKoolTexture` 与 `put` 里按该 id 打点计数）。

**第 16 轮：判明了 —— 不是「每帧重新生成」，是「低频重注册把整帧复用毒化了」。**

按该 id 在 `put` 与 `releaseKoolTexture` 两侧同时打点，实测注册时间线：

```
churn47 put #1   nanos=290527984976600  shape=Frame
churn47 put #2   nanos=290531590996900  shape=GpuTarget   (+3.61 s)
churn47 put #100 nanos=290569728251300  shape=GpuTarget
churn47 put #150 nanos=290589859049000  shape=GpuTarget
```

- 每次注册的间隔约 **0.36–0.40 s**，即约 **2.8 次/秒** —— **不是每帧**；
- 形态几乎全是 **`GpuTarget`**，也就是**某一个图层格子在被反复提交**；
- 整场运行里 `releaseKoolTexture` 侧**一次都没打印**（该 id 没有被显式释放），
  所以它是被新的注册不断**替换**（`put` → `sources.put` 换新 `Source`），不是被释放。

⇒ 结论：**一个格子以约 2.8 次/秒的频率重新提交，而每个依赖它的格子冻结都会被判失效。**
由于 5×5 网格里相邻格子的依赖集互相包含，**这个低频扰动足以让整帧复用在几乎所有帧上都失败** ——
`versionReuse` 在十几轮里始终为 0，就是被它一个源毒化的。

这也解释了两件事：为什么「减少重冻」的各种做法都只有个位数收益，
以及为什么 `depMissing`/`vMismatch` 会交替出现（前者是被替换后被引用，后者是装载期）。

**这条路线的最终判断**：整帧复用对**单个高频扰动的源**极其脆弱。
要让它可用，必须先把「谁在每秒重提交一个格子」这件事解决掉（这是上游的提交策略问题，
不是冻结侧能修的）；否则应放弃整帧复用，转而考虑**降低单格提交本身的内容量**
（每格约 9 千条命令）。

**第 17 轮：量清了单格内容量，「每格约 9 千条」被修正为「约 5.5 千条，且几乎全是地形绘制」。**

按嵌套帧命令数分桶并统计种类（512×512 及以下的 offscreen 帧）：

```
offscreenSizes=5000x6   offscreenKinds=texx32850, rectx874, clearx6
offscreenSizes=5000x5   offscreenKinds=texx27218, rectx745, clearx5
```

- 每个 512² 格子的命令数落在 **5,000–5,999** 区间（不是 9 千）；
- 组成：**`drawTexture` ≈ 98%**（32,850 / 5 次 ≈ 每格 6,570 条），`drawRect` ≈ 2%，`clear` 1 条；
- 也就是说，**一个格子里几乎全部是逐个地形贴图的绘制命令**。

按 512² 屏幕像素、每格约 6,570 条贴图命令估算，**平均每条贴图只覆盖约 40 个屏幕像素**
—— 这不可能是「少量大图块」，而是**地形被逐小块（甚至逐 tile）下发**。
这正是 §6.3 那个「每帧 ~1.1 千条命令」在离屏格子侧被放大的形态：
主画面每帧约 1,279 条，而**单个格子就有约 6,570 条**。

⇒ **下一个真正的杠杆明确了：把格子内的地形绘制批量化。**
它与 §6.1 原来设想的「批绘制命令」方向一致，但**落点完全不同**：
不是聚合主画面的连续同类绘制，而是**聚合离屏格子里的地形小块**。
这一步之前必须先确认游戏侧下发地形的方式（`MapLayer` 的逐块绘制路径与 tile 尺寸），
再决定是在引擎侧合并、还是在 canvas 录制层按「同贴图 + 相邻矩形」合并。

**第 18 轮：按上面两步做完了，机制成立但没有提速 —— 并因此定住了真正的成本口径。**

**(a) 地形确实是逐 tile 下发的。** `MapLayer.renderLayerRegion` 的双层循环里
（`i6`/`i7` 遍历图层区域）每个 tile 单独发一次 `graphicsEngine.a(...)`，
而且两条路径——`mapTile.renderTile(...)` 与 `tileAtlasCache.getAtlasTextureForIndex(...)`——
都只做「同 atlas 贴图」级别的共享，没有任何成批下发。所以 §6.7 第 17 轮量到的
「每格约 6,570 条 drawTexture、平均每条约 40 屏幕像素」与代码完全吻合。

**(b) 离屏引擎的贴图图集是被关掉的。** `KoolGraphicsEngine.b(texture, mode)` 创建离屏引擎时写死
`enableTextureAtlas = false`。于是**每个 tile 贴图在 canvas 里是各自独立的 texture id**，
渲染侧的 instanced run 合并需要「相邻命令 texture id 相同」，因此一条都合不了。

**已实现并验证（`RWX_OFFSCREEN_TEXTURE_ATLAS`，默认开；`=0` 回到旧行为）**：
把离屏引擎的 `enableTextureAtlas` 打开。图集把多个游戏贴图打进一张 atlas texture，
从而让连续的 tile 绘制**共享同一个 canvas texture id**。

实测结果（低缩放平移，开雾，同件 A/B）：

| 指标 | 图集关（对照） | 图集开 |
|---|---|---|
| 单格命令数（`offscreenSizes`） | 5,000–5,999 | **1,000–1,999** |
| newFps | 110.2 / 119.5 | 110.4 / 114.3 |
| P99 | 58.1 / 46.6 ms | 62.2 / 52.9 ms |
| `canvas-commands` 总时长 | 45.8 s | 45.6 s |
| `engine-freeze` 总时长 | 52.6 s | 53.1 s |

⇒ **格子命令数降了约 3.5 倍，但墙钟与阶段耗时完全没动。**
这道出了本轮最重要的结论：**主画面侧的每命令渲染成本是硬地板，
「内容侧让命令变便宜」换不来时间。**

原因也已查明：渲染侧的 instanced run 要求
`paint.isCanonicalDefaultTexturePaint`（即必须是 `KoolCanvasPaint.Default` / `DefaultNearest`），
而地形用的是 `groundTexturePaint`（带纹理效果）。**所以图集能统一 texture id，
却仍然进不了批处理分支** —— 每条命令照样单发。

**门禁**：图集开/关两侧 oracle 均为 **1537 项、0/768 失配**，即这次改动像素级安全。
因为不产生收益，**默认值保持开启仅作为「命令更少」的中间状态**；
若后续要回退，`RWX_OFFSCREEN_TEXTURE_ATLAS=0` 即可。

**下一步的判断**：既然地板在「主画面侧每命令成本」，而 §6.1 的批绘制命令
（让渲染侧把 N 条同类绘制合并成一条实例命令）正好打这个地板 ——
但第 1 轮已实测主画面的 run 覆盖率很低（大 run 早被批掉）。**因此真正的落点应是
「让地形那种带 textureEffect 的绘制也能走实例批处理」**，即放宽
`canUseUnorderedDefaultInstancedTextureRun` 的 paint 限制，
而不是继续在内容侧减命令。

**第 19 轮：量清了拒绝原因，放宽了 paint 限制 —— 但它**不是**瓶颈，真正的墙是 blend 模式。**

给渲染侧的纹理命令加了「为什么进不了实例 run」的分类计数，低缩放平移实测：

```
textureRunReasons paint=18470527, clip=1493551, hasAlpha=152153, eligible=8466
```

- **`paint` 一项占 91.6%**（18,470,527 / 20,162,547）—— 确实是最大的一项，与上一轮的推断一致；
- `clip` 占 7.4%（离屏格子有裁剪，属正常）；
- **`eligible` 只有 8,466 条** —— 即几乎没有任何纹理绘制进入批处理。

**(a) 放宽的依据是成立的**：`KoolCanvasInstancedTextureShader` 只采样 `uColorMap`，
输出 `rgb = 纹理 rgb`、`a = 纹理 a`，**完全不读 paint 的颜色/透明度**。
所以要求 paint 必须**恰好等于** `KoolCanvasPaint.Default`/`DefaultNearest` 是过严的：
颜色本就不该进入批处理身份。

**(b) 已实现并验证（`RWX_INSTANCED_RUN_RELAXED_PAINT`，默认开；`=0` 回退）**：
把 run 的 paint 判定从「必须是 Default 常量」放宽为
「`textureEffect == null`、`blendMode == SourceOver`、`alphaMultiplier == 1f`」。
oracle 两侧均 **1537 项、0/768 失配**，即像素级安全。

**(c) 但实测没有提速**，而且原因查清了：

| 指标 | 放宽前 | 放宽后 |
|---|---|---|
| newFps | 115.3 / 122.8 | 110.4 / 121.0 |
| P99 | 54.3 / 46.1 ms | 62.9 / 42.4 ms |
| `paint` 拒绝数 | 18,470,527 | **16,284,195**（只降 12%） |
| `eligible` | 8,466 | **8,590**（几乎没变） |

只降 12% 说明剩下的 `paint` 拒绝**卡在别的条件上**，而那个条件正是：

**`blendMode == Source`。** `KoolGraphicsEngine.withDirectBlitTextureBlend` 在
「direct blit 生效 + paint 为 SourceOver + 颜色不透明 + 纹理不透明」时把 blendMode 改成
`Source`（直通拷贝的优化）。而实例 run 建的是 **Alpha 混合**的实例网格，
所以这些绘制**不能**走 run，否则会把直通拷贝画成 alpha 混合。

⇒ **真正的墙是「同一个实例 run 无法同时承载 `Source` 与 `SourceOver` 两种混合」**，
而不是 paint 的颜色。要走批处理，需要给 `Source` 也建一层实例网格
（即实例 run 按混合模式分层），这与 §6.1「批绘制命令」的原始设想一致，
但落点是**按混合模式分层的实例网格**。

门禁：两侧 oracle 均 1537 项 0/768；`：core:test` 318 项 1 失败（既有无关）。
放宽这版**保留**（判定更准确、像素安全），但它本身不是提速手段。

**第 20 轮：按混合模式分层做完了，机制生效 —— 但暴露了一个口径错误，真正的墙是 `clip`。**

实现：`ensureInstancedTextureMesh` **本来就按 `renderBlend` 分键**，而实例 run 里写死了
`CanvasRenderBlend.Alpha`。改为把命令自己的 `paint.renderBlend` 传下去，
并让 run 的缓存（`textureRunBlends`）与判定一并带上混合模式 ——
于是 `Source` 混合（直通拷贝优化）走 `CanvasRenderBlend.Source`（该层 `BLEND_DISABLED`）的
独立实例网格，`SourceOver` 仍走 Alpha 层。

实测（低缩放平移，开雾）：

| 指标 | 第 19 轮（放宽前） | 第 20 轮（分层后） |
|---|---|---|
| `textureRunReasons` | `paint=18,470,527, clip=1,493,551, hasAlpha=152,153, eligible=8,466` | `clip=2,595,380, hasAlpha=550,085, transform=50,865, eligible=35,404, paint=11,790` |
| **`eligible`（真正能进批处理）** | 8,466 | **35,404** |
| `paint` 拒绝 | 18,470,527 | **11,790** |
| oracle | 1537 / 0-768 | **1537 / 0-768** |

**机制确实生效了**：`paint` 拒绝从 1,847 万降到 1.18 万。

**(a) 但暴露了我的口径错误**：这是「第一个失败条件」的分类计数，所以
**`paint` 之所以显得占 91.6%，是因为它在判定顺序里排在前面**；
放宽 paint 后，原本被 `paint` 掩盖的 `clip` 才浮现出来。
真正能否进批处理只看 `eligible` —— 而它只有 **35,404 / 20,162,547 ≈ 0.18%**。
**所以我第 19 轮「paint 占 91.6% 是最大项」的结论是分类顺序造成的假象。**

**(b) 真正的墙是 `clip`（约 259 万条）**：离屏格子的绘制带着格子边界裁剪
（§6.7 第 19 轮量到 `clip` 约 149 万，本轮 259 万），而 run 的判定要求
`state.clip == null`。**带裁剪的绘制一律进不了实例批处理。**

**(c) 同件 A/B 帧率无显著差异**（对照 123.5/127.8 vs 分层 125.9/130.4），
`canvas-commands` 总时长 44.7 s → 43.6 s，run 覆盖命令数 2,243 万 → 2,245 万（几乎是采样噪声）。
**因为 `eligible` 只有 0.18%** —— 分层再多，能进批处理的命令本来就极少。

⇒ **下一轮：让带 `clip` 的绘制也能进实例 run。**
两条候选路径：把裁剪矩形作为实例属性下推到着色器（像 `clipBounds` 那样），
或先只接受「目标矩形完全落在裁剪矩形内」的绘制（此时裁剪是恒等变换、可以安全忽略）。
后者实现小、语义清晰，先做它。

**第 21 轮：做了窄化版（clip 冗余时接受），`clip` 拒绝降了，但 `eligible` 仍然只有 ~1%。**

实现（`RWX_INSTANCED_RUN_CLIP_INSIDE`，默认开；`=0` 回退）：run 判定里
`state.clip == null` 改为 `state.clip.isRedundantFor(destination)` ——
即**当目标的 bounds 完全落在裁剪 rect 内时，裁剪对该条绘制不起作用**，可以安全忽略。
oracle 通过：**1537 项、0/768 失配**。

三次采样（低缩放平移，开雾）：

| 运行 | `clip` | `transform` | `hasAlpha` | **`eligible`** | `paint` |
|---|---|---|---|---|---|
| 1 | 1,279,176 | 449,420 | 448,909 | **28,593** | 7,422 |
| 2 | 1,185,818 | 428,977 | 415,091 | **26,814** | 6,902 |
| 3 | 689,934 | 291,860 | 250,224 | **18,775** | 3,911 |

- `clip` 拒绝从第 20 轮的 259 万降到约 69–128 万，**窄化确实生效**；
- 但 **`eligible` 仍是 1.9–2.9 万，约占全部纹理命令的 1.4%**；
- 新的三项主力是 **`clip`（目标超出裁剪）> `transform` ≈ `hasAlpha`**。

**(a) 一个更根本的认识（这轮才看清）**：`eligible` 之所以怎么放宽都上不去，是因为
**实例 run 是「主画面渲染器」的路径**，而 §6.7 第 17–18 轮测到的昂贵部分是
**离屏格子帧**（每格 5,000–5,999 条命令）。这两者不是同一条路径：
格子走的是 **CPU 光栅**（第 10 轮实测 `offscreenPass=0 rasterOrOther=2000`），
它的命令由 canvas 录制层消费，**并不经过 `KoolCanvasFrameRenderer` 的 run 逻辑**。
所以我连续三轮在「放宽 run 判定」上取得的进展（`paint` 1,847 万 → 1.18 万 → `clip` −70%）
**都不会作用到真正昂贵的那些命令上**。

**(b) 因此本轮的结论是方向性的**：要提速离屏格子，必须动
**canvas 录制层 / CPU 光栅侧**的按命令成本，或者**减少格子命令数**（第 18 轮已证明
单纯减命令数无效 —— 降 3.5 倍墙钟不变，说明那侧的成本也不在「条数 × 单价」上）。

门禁：oracle 1537 项 0/768（clip 开/关两侧）；`:core:test` 318 项 1 失败（既有无关）。
两个开关（`RWX_INSTANCED_RUN_RELAXED_PAINT`、`RWX_INSTANCED_RUN_CLIP_INSIDE`）
都**保留**且像素安全，但**都不是已验证的提速手段**。

**第 22 轮：量了「命令量按 viewport 分类」，并撞上一个尚未解释的矛盾。**

新增分类计数（在 `freezeFrame` 里按源帧 viewport 归类），单次冻结内的实测：

```
volume=main:passes=1,commands=1363, offscreen:passes=12,commands=20012
```

- **主画面一帧 = 1,363 条命令**；
- **离屏格子：12 个子帧、合计 20,012 条命令**（约 1,600–1,700 条/帧，与第 20 轮
  `offscreenSizes=1000x12` 一致）。

**(a) 与第 18 轮的矛盾（尚未解释）**：全部 `canvas-commands` 行的命令数合计为
**27.28 M（图集开）vs 27.76 M（图集关）** —— 即图集把单格命令数降了约 3.5 倍，
**总命令量却只降了 1.7%**。这与「离屏格子每次冻结贡献约 20,012 条、主画面仅 1,363 条」
的瞬时占比**对不上**。

两种解释都需要下一轮验证：

1. 离屏格子只在**装载/预热阶段**重录，稳态下几乎不重录（那么稳态成本由主画面主导，
   而第 17–18 轮那些「单格 5,000–5,999 条」是装载期现象）；
2. 或者 `offscreen:` 分类把大量**非格子**的小 viewport 帧也算进去了。

**(b) 命令循环的单价**：按全部 `canvas-commands` 统计，**1.38–2.32 µs/命令**，
与 pass 大小关系不大（pass 均值都在 1,600–1,750）。这条与第 18 轮「降 3.5 倍命令数、
墙钟不变」一致 —— 说明**真正决定耗时的不是命令总数，而是别的东西**（尚未定位）。

**（c）本轮的测量受机器负载影响**：pan 4 次中位数只有 **104.0 /秒、P99 49.1 ms**，
而第 15 轮同样配置是 148 /秒、P99 37.1 ms。趋势（本机负载波动）比绝对值更可信。

**结论**：在解释 (a) 之前，不应再基于「命令条数」做任何优化决策 ——
第 18 轮和第 22 轮两次实测都表明条数与墙钟不成比例。下一轮应先把
「稳态下到底谁在贡献命令量与耗时」量清楚（例如按阶段给主画面与格子的命令量分别打点，
而不是只看单次冻结内的瞬时占比）。

**第 23 轮：原版现在以相同的 pan+zoom 运动测量，并找到了 RWX 缺失的那一步。**

**(a) 原版的探针已支持与 RWX 完全相同的 pan+zoom**（`-Drwx.orig.zoom=on`）：
平移用同一个分段三角波（20 s 周期，振幅按「世界尺寸−可见尺寸)/2 夹到 800」——
原先这里写死 300，**不可比，已改正**；缩放用同一个 `1 → min → max → 1` 余弦周期（4 s），
并写入引擎自己的 `targetZoom` 字段（`cV`；`TileMap.getWorldWidth` 的混淆名是 `i()`/`j()`，
世界实测 6400×4000）。全程仍然**不碰鼠标键盘、不激活窗口**。

**(b) 在同一负载窗口下两侧对照**（本机当时约 90% CPU，绝对值普遍偏低）：

| 侧 | 最佳 run | p50 帧间隔 | p99 |
|---|---|---|---|
| 原版 (pan+zoom) | **113.1 /秒** | **5.50 ms** | **16.88 ms** |
| 原版 (pan+zoom) 最差 run | 24.3 /秒 | 7.50 ms | 236.50 ms |
| RWX (pan-zoom, 3 次) | 中位 33.6 /秒 | — | 中位 311.0 ms |

**关键不是峰值帧率，而是 p50 帧间隔**：原版的**中位帧只要 3.5–7.5 ms**，
所以即使机器被压到 24 /秒，**它的「典型帧」依然是快的、手感依然是连续的**；
RWX 的尾延迟是 130–668 ms 量级，**一次卡顿就能被手感觉到**。
原版的卡顿主要来自少数极端离群帧（max 26–39 s，属加载/系统抢占），
而 RWX 是**持续性地慢**。

**(c) 找到了 RWX 缺失的那一步：原版在逐格绘制处做了「雾状态行合并」，RWX 没有。**

`MapLayer.java:329–343`：原版在画每个 tile 之前，若雾/可见状态允许，会**向前扫描整行**，
把连续同雾状态的格子**合并成一次绘制**（`rectF.d += (i9 - i7) * f12; i7 = i9;`），
然后只发一条 `graphicsEngine.a(rect, paint)`。
这段逻辑是原版自己的、**对包括离屏格子在内的所有绘制都生效**，
而 RWX 的 tile 循环（`core/.../MapLayer.java:291–326`）**是逐 tile 无条件下发一条绘制**，
只有「同一 atlas 贴图」级别的共享，没有行合并。

⇒ **这就是「原版为什么不卡」的机制性答案之一**：原版把同质地形行合并成少量大矩形，
RWX 让它变成每格 5,000–6,000 条命令（第 17 轮实测），
而那些命令又走 CPU 光栅（第 10 轮实测 `offscreenPass=0`）。

**(d) 下一步（具体、可验证）**：把原版这段行合并移植到 RWX 的图形引擎侧 ——
即对同一行内「同贴图 + 同雾状态 + 相邻」的 tile 合并成一次 `drawTexture`
（目标矩形加宽、UV 保持该行首格或改用平铺），并用 oracle（1537 项 0/768）判定像素一致。

**第 24–25 轮：移植了行合并 —— 结果是「拉花画面」，已默认关闭。**

实现：在 tile 循环的 atlas 分支里向前扫描同一行，把**同 atlas 槽位且同雾状态**的连续 tile
合并成一次 `drawTexture`（`rectF.c` 加宽到整段），开关 `RWX_MAP_TILE_RUN_MERGE`。
两个 oracle 配置都过（1537 项、0/768），所以**像素校验没能拦住它**。

**但实机画面明显撕裂**（用户截图确认：地形出现黑/断带）：
**把一格的 atlas 源矩形横向拉伸，并不会「重复」该 tile，而是采样到 atlas 页里紧邻它的内容** ——
所以并起来的段里出现别的图块或空白。原版之所以能用这招，是因为它合并的是
**雾状态**（合并段是一种纯色，不是贴图图像），而不是贴图 tile。这是我在第 23 轮读代码时的误判。

**已改为默认关闭**（`RWX_MAP_TILE_RUN_MERGE=1` 才启用，留给以后做「源矩形随段行走/平铺」的版本）。
验证：运行日志的 `map-cache.csv.tileruns` 写 `traceTileRuns=true merge=false`；
oracle 与 `:core:test` 均照常通过。

**(e) 顺带修掉两个测量/工具问题**：

1. `repeat_pan_benchmark.py` 现在**默认跳过每次运行开头 2 个装载/预热窗口**
   （之前把 0.1–8.7 /秒 的装载期算进中位数，把 677 ms 的装载停顿当成游戏卡顿）；
2. 诊断开关必须走 harness 参数：`run_environment` 会**过滤掉父进程的所有 `RWX_*` 变量**，
   所以我在 shell 里设的 `RWX_MAP_TILE_RUN_TRACE=1` **从未到达游戏进程**。
   已加 `--map-tile-run-trace` / `--offscreen-tile-run-merge-off`，并把跨层诊断确认
   （`map-cache.csv.tileruns` 写 `traceTileRuns=true merge=false`）固化下来。

**(f) 本机负载警告**：连续多轮实测时 CPU 长期 90–100%
（QQ / QQMusic / IntelGraphicsSoftware / dllhost 常驻），
同一配置的帧率在 3.8 与 137 /秒 之间跳动，**期间的所有墙钟结论都不可用**。

**第 26 轮：量清了真实的耗时构成 —— 并发现诊断行是「无限深」的，不能求和。**

对全部阶段行做汇总（一次 94 s 的低缩放平移运行）：

| 阶段 | 调用数 | 总计 | 每次均值 |
|---|---|---|---|
| `engine-freeze` | 6,358 | **37.4 s** | 5.89 ms |
| `canvas-commands` | 9,900 | **35.1 s** | 3.55 ms |
| `canvas-new` | 3,849 | 26.6 s | 6.92 ms |
| `canvas-prepare` | 9,900 | 2.8 s | 0.28 ms |
| `atlas-texture` | 168 | 1.9 s | 11.1 ms |

**(a) 诊断口径纠正**：`freeze-detail` / `freeze-heavy` / `freeze-nested-kind` /
`freeze-nested-vp` / `freeze-reject` / `freeze-version-reuse` / `freeze-refs` /
`freeze-content` / `freeze-split` / `freeze-repeat` **都是 `engine-freeze` 内部的子计时**，
它们每一项都约等于 37.6 s、均值约 5.91 ms（即整段 freeze 的时长）。
把所有行相加会得到 737 s —— **那是假的**。真正的根只有三个：
`engine-freeze`、`canvas-commands`、`canvas-new`。

**(b) 真正的时间在 freeze 与命令循环**，两者量级相当（37.4 s vs 35.1 s），
`canvas-new` 是同一段渲染调用的父计时（`frameRenderer.render(...)`），
**不能与 `canvas-commands` 相加**（后者是它的子区间）。

**(c) 关于这两块的结构性事实**（已实测，用于判断还有没有便宜的改法）：

- freeze 侧：每次 `freezeFrame` 调用平均只带 **2 个 512² 子帧**，
  且每个源在**单次调用内只被解析一次**（`seenCount=0`），所以「同调用内去重」没有收益；
  单个 512² 格子的冻结约 **5.9 ms**、录制内容约 5,000 条命令；
- `GpuTarget` 分支**已经**只在内容真变时才换 `frozenVersionId`（第 16 轮已实现），
  所以那 2.8 次/秒的重提交是**真实的新内容**，不是版本噪声；
- 命令循环：**20 M 条命令、约 1.5–2.3 µs/条**，且第 18 轮已证明**降条数换不来时间**。

**(d) 因此当前瓶颈是「总命令量 × 每命令固定成本」这个乘积**，
而两侧都已被实测各堵了一次：
条数不能靠合并降（第 18、24 轮），每命令成本也没有便宜的收窄（第 19–21 轮 `eligible` ≤1.4%）。
**下一步应当直接量「单条命令走完循环到底花在哪一步」**（对 `canvas-commands` 内部再分段），
而不是继续在外部找「省一大块」的机会。

**第 27 轮：把命令循环拆开了 —— 没有单一热点，循环本身也不贵。**

实现：在命令循环里按步骤计时（`RWX_LOOP_SPLIT=1`，经 harness 参数 `--loop-split`），
把时间归到 visibility / dispatch / textureAtlas / textureRun / textureDraw / primitive / text / other。
计数器是**按渲染器实例累积**的（每个离屏目标建自己的 renderer，本次共 **33 个实例**），
所以行名带上实例标识后再聚合，取每个实例的最后累计值：

| 步骤 | 总计 | 占比 |
|---|---|---|
| `loop-dispatch`（try/finally + 计时守卫 + 类型分派） | 40.9 s | **37.5%** |
| `loop-other`（循环自身：索引、`commandIndex++`） | 27.6 s | **25.3%** |
| `loop-text` | 17.6 s | 16.2% |
| `loop-textureDraw` | 8.5 s | 7.8% |
| `loop-textureAtlas` | 5.4 s | 4.9% |
| `loop-primitive` | 5.0 s | 4.5% |
| `loop-textureRun` | 3.9 s | 3.6% |
| **`loop-visibility`**（`adaptiveVisuals.shouldDraw`） | 0.24 s | **0.2%** |

**(a) 结论一：可见性判定不是成本**（0.2%），可以彻底排除这个候选。

**(b) 结论二：循环里没有单一热点。** 最大的一项是**记账本身**
（`dispatch` + `other` 合计 62.8%）——即 try/finally 块、计时守卫、
以及本次诊断自己加的 `System.nanoTime()` 调用。去掉这些后剩下的真实工作是
text 16.2% + 纹理约 16% + 图元 4.5%，**没有任何一项大到值得单独优化**。

**(c) 结论三（也是这轮最该记住的）**：这次测量**用仪器量仪器，结果被仪器污染**。
`loop-dispatch` 与 `loop-other` 的绝大部分就是诊断开销自己。
所以这条「内部再分段」的路**到此为止** —— 继续加计时只会让被测量的东西更贵。

**⇒ 综合第 18、26、27 轮：**
`canvas-commands` 的成本是**由命令总量摊平的、离散分布的每命令开销**，
既没有可合并的大块（第 18 轮：降 3.5 倍命令数墙钟不变），
也没有可针对的热点（本轮）。
**在「减少冻结/减少命令/加快每命令」这三条线上，我能找到的杠杆已经用尽。**
要让 RWX 追上原版的 p50 帧成本，需要的是**结构性改动**
（例如让离屏格子像原版那样直接在 GPU 上合成、而不是 CPU 光栅 + 逐命令录制），
那已经超出「优化现有路径」的范围。

**第 28 轮：转向结构性改动，并把「GPU 离屏 pass 为什么没用上」追到了最后一环。**

RWX 其实**已经有** GPU 离屏 pass 的完整实现（`KoolCanvasGpuTargetPasses` /
`FrozenCanvasGpuResources`），`RWX_GPU_MAP_CELL_TARGETS` 默认开启。
但实测它从未生效（第 10 轮：`offscreenPass=0`）。本轮逐环定位：

| 环 | 实测 | 结论 |
|---|---|---|
| 用户开关 | `[RWX canvas] gpuMapCellTargets=true` | ✅ 开 |
| pass 系统 | `gpuTargets[created=32 …]` | ✅ 已挂载 |
| 宿主就绪 | `gpuMapCellSupported=true` | ✅ |
| 后端能力 | `cellTargetMode supported=true backendClass=io.github.rwx.render.canvas.KoolGraphicsEngine` | ✅ 游戏侧**确实请求了** `GPU_TARGET` |
| 安装器 | `gpu-target-modality notGpu=500, noInstaller=0, gpuPass=0` | ✅ 安装器正常 |
| **提交路径** | **`cell-target-modes DEFAULT=0, IMMEDIATE=500, GPU_TARGET=0`** | ❌ **全是 `IMMEDIATE`** |

⇒ **根因链定位完成**：整条 GPU 离屏链路（开关、pass 系统、后端能力、安装器）**都是好的**，
但**到达 `flushTargetTextureFrame` 的纹理提交全部是 `IMMEDIATE` 模式，没有一次 `GPU_TARGET`**。

也就是说：**`LayerBufferManager` 虽然把 `layerBufferRenderTargetMode()` 判成 `GPU_TARGET`，
那些格子的提交却没有走到带该模式的引擎上。**

代码里已经有一条明确的嫌疑线索：`LayerBufferManager` 里有**两处**创建 target 引擎，
模式不同 ——

```java
line 767: resourceBackend.b(this.bufferLayerTexture,     RenderTargetMode.IMMEDIATE)  // 写死
line 856/864: resourceBackend.b(cell.cellLayerTexture,   layerBufferRenderTargetMode())
```

**下一轮的第一件事**：确认那 500 次 `IMMEDIATE` 提交是哪张纹理发出的
（`bufferLayerTexture` 还是 cell），以及 `cellGraphicsCopy` 的 `GPU_TARGET` 引擎
**为什么没有产生提交** —— 这已经是「结构性改动」的最后一环，
一旦打通，格子就会从「CPU 光栅 + 逐命令录制 + 再冻结」变成「GPU 上渲染一次、采样多次」。

**第 29 轮：继续追，并纠正了我上一轮的一处归因错误。**

新增诊断：`cellTargetDecision`（格子创建时的真实尺寸/后端/模式）、
`commitShapes`（提交按 `WxH/模式` 分类）、`commitRoutes`（`p()` 的两条提交路径）。

实测：

```
[RWX map]    cellTargetDecision 512px/KoolGraphicsEngine/GPU_TARGET/capability=true
[RWX canvas] commitRoutes pNoTarget=0 pWithTarget=1 top=512x512/GPU_TARGETx1
[RWX canvas] commitShapes total=500 top=250x250/IMMEDIATEx500
```

**(a) 格子侧完全正确**：`cellBufferPixelSize=512`、后端是 `KoolGraphicsEngine`、
解析出的模式就是 **`GPU_TARGET`**，而每个格子的 `p()` 走的正是
`targetTexture != null` → `flushPendingTarget(texture)` 这条 **GPU 提交路径**。

**(b) 我上一轮的归因错了**：我把「`gpu-target-modality notGpu=500, gpuPass=0`」读成
「格子提交全是 IMMEDIATE」。实际原因是 **`gpuTargetModality` 是每个 renderer 实例各自的计数器**
（本轮日志里 `commitShapes total=500` 也只是**某个**实例的值），
而格子走的是 `flushPendingTarget`，**根本不经过我在数的那条 `flushTargetTextureFrame` 分支**。
那些 `250x250/IMMEDIATE` 提交属于**另一条链**（`p()` 无自有 target 的那条），不是格子。

**(c) 因此必须先证伪一件事再谈修复**：格子既然走 `flushPendingTarget`、
且 `targetMode = GPU_TARGET`，那么第 10 轮量到的 `offscreenPass=0`
到底是「GPU pass 没生效」，还是「我只数了另一条分支」。
**下一轮第一件事**：给 `flushPendingTarget` 也加同样的分类计数
（模式、尺寸、是否真正 install/解析），确认格子的 GPU pass 是否在跑、跑了几次。

如果格子的 GPU pass 其实**已经在跑**，那结论就会反过来 ——
`engine-freeze` 那 37.4 s 与 GPU pass 无关，真正的问题是**格子内容每次重录都会重新冻结一遍**，
那就要回到「录制的命令流本身」而不是 pass 机制。
这是必须先用数据分清的分叉，不能再靠推断。

**第 30 轮：分叉测清了 —— 而且推翻了第 10 轮的一个结论。**

给计数器加上**实例身份**（每个 renderer 各自的 `gpu-target-mode@<identity>` /
`gpu-target-modality@<identity>`），并读 GPU pass 自身的计数器：

```
gpuTargets[created=32 versions=2477 rendered=4350 resolves=53685 live=6 pooled=26 ...]
```

**(a) 格子的 GPU 离屏 pass 确实在跑**：本次运行渲染了 **4,350 次**、解析了 **53,685 次**、
产生了 **2,477 个版本**。所以**第 10 轮「offscreenPass=0 → GPU pass 没生效」的结论是错的** ——
那只是因为我在数 `flushTargetTextureFrame` 的**另一条分支**。

**(b) 本轮的实例级数据**：整个运行只有 **2 个** `flushTargetTextureFrame` 实例，
两者都是 **`IMMEDIATE` / 250×250**（共约 1,000 次提交、GPU pass 次数 0）。
而格子是 **512² / `GPU_TARGET`**，每个格子的 `p()` 只调用 **1 次**（用 `flushPendingTarget`）。

**(c) 因此真正的结论（这才是治本方向）**：

- 格子的 offscreen pass **机制是通的**，不需要「打通 pass」；
- 但 `engine-freeze` 的 **37.4 s** 与 pass 无关 —— 它是在 pass 之前，
  把 canvas 命令流**冻成命令对象**的 CPU 开销；`canvas-commands` 的 35.1 s 是消费这些对象。
- 也就是说：**RWX 是「CPU 录制命令流 → 冻结 → 渲染」，而原版是「直接画」**。
  格子的 pass 只是把最终合成搬到 GPU，**并没有省掉 CPU 侧的录制与冻结**。

⇒ **治本的方向因此要改**：不是「打通 GPU pass」（已在跑），
而是**让格子不必每次重录 + 重冻整份命令流**。
这与第 16 轮「整帧复用不可行」和第 26 轮「freeze 侧无便宜改法」的结论并不矛盾 ——
它说明那两轮的失败**不是缓存策略没调好，而是架构上本来就要每帧重走一遍**。

**这是我这 30 轮里第一次把问题的层次放对**：之前一直在优化「重走一遍」的速度，
而原版根本不需要走那一遍。

**第 31 轮：按上面的方向去量「格子内容多久才真变一次」，但诊断本身还没做对。**

新增 `--cell-content-cadence`（在 `freeze` 里对同一帧对象的签名跨调用比较，
经 trace 输出 `cell-content-cadence` 行）。两次尝试都**没能给出可用结论**，如实记录：

1. **第一版签名太粗**（命令数 + viewport）：20,000 次采样报 `changes=0`。
   这是**假阴性** —— 格子重画地形时绘制**条数往往不变**，签名感知不到内容变化。
   所以这个 `0` 不能读成「内容从不变化」。
2. **第二版签名（首几个命令的身份 + 跨步哈希）**：报 `samples=0, distinctFrames=32`。
   `distinctFrames=32` 与 `gpuTargets[created=32 …]` 完全吻合（即 32 个格子帧），
   但 `samples` 停在 0 说明**我的跨调用比较没有被真正命中**，
   诊断自身有缺陷（很可能 `freeze` 的记忆化让同一帧对象在单次调用里只走一次，
   而我需要的跨调用计数没有落到预期的位置）。

⇒ **结论：本轮没有拿到「格子内容变化频率」这个数**。方向仍然成立
（`engine-freeze` 37.4 s 是 pass 之前的 CPU 冻结开销，与 pass 机制无关），
但**支撑它的那个关键频率还没有量出来**，不能据此下判断。

**已确证的（可复用的结构事实）**：

- 格子：512² / `GPU_TARGET` / 每格 `p()` 调用 1 次；
- GPU pass 在跑：`rendered=4350 resolves=53685 versions=2477`（32 个目标）；
- `flushTargetTextureFrame` 的两个实例是 `IMMEDIATE` / 250×250（**不是格子**）；
- **计数器是按 renderer 实例的**，trace 行现在都带 `@<identity>`。

**下一轮**：把 cadence 诊断做对（跨调用计数要落在每次 `freeze` 都执行的位置，
签名要用能反映内容变化的量，例如对同一帧对象记录 `commands` 的首/中/尾若干项
的**身份 + 内容**，并在每次解析时都累加），再判断「内容变化频率 << 冻结频率」是否成立。
**在量出来之前不要再基于这个假设做设计。**

**第 32 轮：第三次修正 cadence 诊断仍失败 —— 决定停止在这条诊断上继续投入。**

依次排除了三个可能原因，每次都用数据：

1. **`System.identityHashCode` 碰撞**（对象释放后地址复用）→ 改用
   `IdentityHashMap<KoolCanvasFrame, Int>` 按对象身份作键。**仍然 `samples=0`**；
2. **store 被反复重建**（那样 per-instance 的 map 就永远只见一次）→ 加 `STORE_INSTANCES` 计数，
   实测 **`cell-store-instances total=1, thisOwner=1`**：**整场运行只有 1 个 store**。排除；
3. 于是只剩「解析到的帧对象确实各不相同」这一种解释，但那与
   `sources` 的稳定注册（第 9–14 轮反复确认）矛盾 —— **说明我对 `freeze` 调用点的理解还有错**。

**我在这条诊断上连错三次**（第 31 轮两次 + 本轮一次）。按我自己写进文档的规则
（「在量出来之前不要再基于这个假设做设计」），**我停止继续修补它**，
并明确记录：**「格子内容变化频率」至今没有测出来，任何基于它的设计都还缺依据。**

**本轮净产出**：`cell-store-instances` 这条诊断确认了**整场只有 1 个 store**
（这本身有价值 —— 它排除了「计数器按实例」这个 caveat 在 store 层面的影响；
第 29/30 轮那个 caveat 是 **renderer** 实例层面的，不是 store）。

---

## 6.8 交互退化比（相对量）：这是「像原版一样流畅」的可比口径

**用户的口径纠正**：不要比绝对帧率（本机有其它负载），要比**拖动/放缩相对于静止的变化**，
且要求**超过原版水平**。

新增驱动器 `desktop/tools/interaction_ratio.py`：

- **交错采样**：每一轮先测静止、再测移动（static, moving, static, moving…），
  使负载尖峰同时落在两个臂上，而不是只落在其中一个；
- **负载校正**：在每个臂前后各采一次整机 CPU 负载，
  用 `rate = a + b·load` 拟合后把两个臂都折算到平均负载；负载无差异时退化为普通中位数；
- **退化比** `degradation = static / moving`：1.00 表示交互完全不花钱，越低越好；
- 原版侧用与 RWX **完全相同**的 pan+zoom 运动（`-Drwx.orig.zoom=on`，振幅
  `min((world−visible)/2, 800)`）。

### 实测结果（各 3–4 轮交错采样，负载 12–82%）

| 侧 | 静止 | 移动 | **退化比** |
|---|---|---|---|
| **原版** | 182.0 /秒 | 215.4 /秒 | **0.845×** |
| **RWX** | 130.6 /秒 | 66.7 /秒 | **1.959×** |

**这是整场最有价值的一个测量**，它把问题说清楚了：

1. **RWX 在静止时并不差**（130 vs 原版 182 /秒）；
2. **原版交互不花钱**（0.845× —— 拖动时甚至比静止略快）；
3. **RWX 交互时腰斩**（1.959×）；
4. **差距不在「慢」，而在「一交互就垮」** —— 相对差距 **2.3 倍**。

### 关键补充：退化比本身随机器负载变化，而且 RWX 对负载远比原版敏感

在**低负载窗口**（整机 13–49%）重测两侧，各 3 轮：

| 侧 | 静止 | 移动 | **退化比（低负载）** | 退化比（高负载） |
|---|---|---|---|---|
| **原版** | 246.7 /秒 | 228.8 /秒 | **1.078×** | 0.845× |
| **RWX** | 99.5 /秒 | 81.5 /秒 | **1.220×** | 1.959× |

**这张表比上面那张更重要**：

- **原版的退化比对负载不敏感**（0.845 ↔ 1.078，即始终接近 1：交互几乎不改变它的表现）；
- **RWX 的退化比随负载大幅恶化**（1.220 → 1.959，**+61%**）。

⇒ **RWX 的交互退化是「负载放大」的**：机器越忙 → 帧率越低 →
（按 §6.8 的正反馈）每帧镜头移动越远 → 每帧要重冻的格子越多 → 更慢。
**原版没有这个放大机制，所以它在任何负载下都稳。**

**因此「超过原版水平」的可操作定义是**：退化比在**高负载下也保持接近 1**
（原版 0.845/1.078；RWX 需要从 1.22–1.96 降到 ≤1.0）。
**这也意味着所有对照测量都必须在同一负载窗口内配对完成，跨窗口比较无效。**

### 逐帧归因（同一对运行，按帧归一化）

| 阶段 | 静止 µs/帧 | 移动 µs/帧 | Δ |
|---|---|---|---|
| `canvas-commands` | 5,842 | **9,557** | **+3,715** |
| `engine-freeze` | 4,883 | **7,302** | **+2,419** |
| `canvas-new`（父区间） | 6,000 | 7,466 | +1,466 |
| `canvas-prepare` | 78 | 521 | +443 |
| `atlas-texture` | 22 | 362 | +340 |

### 根因：**每帧命令量随镜头速度增长，并形成正反馈**

```
静止:  1.21 pass/帧, 1,141 命令/帧, 128.9 帧/秒
移动:  2.57 pass/帧, 3,027 命令/帧,  65.7 帧/秒
```

- 每个 pass 的命令数几乎不变（静止约 946/移动约 1,178），**变的是每帧的 pass 数**（×2.12）；
- 镜头移动得越远，越多的图层格子越界 → 越多的离屏 pass 要重录 + 重冻；
- **而这些成本是按帧计的，不是按世界距离计的。**
  镜头速度相同的情况下，**帧率越低 → 每帧镜头移动越远 → 每帧要重冻的格子越多 → 帧率更低**。
  这是一个**正反馈**，也正是「一交互就垮」而不是「线性变慢」的原因。

**⇒ 真正的杠杆是打断这个正反馈**：让「每次内容变化的重冻成本」不随帧率下降而变大
（即按**世界距离/内容变化量**摊销，而不是按帧摊销）。
这解释了为什么第 16 轮（整帧复用）、第 18 轮（减命令）、第 26–27 轮（freeze/循环微观优化）
全都收效甚微 —— 它们都在优化「每一帧的那一遍」，而没有动「帧率越低、每帧越贵」这个结构。

**第 34 轮：第四次尝试量 cadence 也失败 —— 已把这段坏脚手架从代码里删除。**

本轮发现了两件具体的事：

1. **`freezeDetailSeen` 是 `freezeFrame` 的局部变量**（每次调用新建），
   所以 `freeze` 的记忆化**只在单次调用内有效**，跨调用从来不命中 ——
   这就是「每次冻结都走新帧对象」的直接原因。**这一条是确证的**，不用再猜；
2. 我给 cadence 诊断加的文件输出被旧的 `frames=` 行淹没（两处写入都以 `0 % N == 0` 为真），
   仍然读不出 `invocations`。**这是我在同一个诊断上的第四次失败。**

**因此我把这段坏脚手架整个删掉了**，而不是留在代码里：
删除了 `traceContentCadence` / `contentCadenceLast` / `cadenceSamples` / `cadenceChanges` /
`cadenceInvocations` 以及 `freeze` lambda 里的整段诊断代码，
并移除了对应的 harness 参数 `--cell-content-cadence`（已验证无残留引用）。
`RWX_SOURCE_CHURN_TRACE`（`put` 的计数）保留 —— 它是有用的、已验证的。

**趋势证据（不依赖帧率绝对值）**：静止 1.21 pass/帧 vs 移动 2.57 pass/帧、
1,141 vs 3,027 命令/帧、`engine-freeze` 4,883 vs 7,302 µs/帧。
**命令量/帧随镜头移动增长约 2.65 倍**，而 pass 内的每命令成本几乎不变。

**第 35 轮：找到了整帧复用「永远不命中」的真实原因 —— 一个单行 bug，命中率 0% → 96%。**

新增 `--reuse-trace`：把整帧复用的判定结果（进程级计数）**写到文件**
（不再只走 trace —— 之前四次失败都因为分不清「没命中」和「行没输出」）。
文件输出直接给出了答案：

```
attempts=70000 hits=0 versionBase=2295 noVersion=0 noFrame=0 noDeps=0
depMissing=67705 depChanged=0
topMissing=legacy-texture-77/cpu-1-v111:1760, legacy-texture-69/cpu-1-v62:1377, ...
```

**`depMissing` 占 95%，而「缺失」的 id 全都是「版本化复合 id」**（`<base>/cpu-<owner>-v<n>`）。
原因：记录依赖时写的是 `id`（已被改写成版本化复合 id），而 `sources` 是**按 base id 建键**的，
所以依赖查找**不可能命中**。改成记录 `ref.id`（base id）即可。

**修复后（同一诊断）：**

| | 修复前 | 修复后 |
|---|---|---|
| `hits / attempts` | **0 / 70,000（0%）** | **76,891 / 80,000（96.1%）** |
| `depMissing` | 67,705（95%） | **0** |
| `depChanged` | 0 | 720 |
| oracle | 1537 / 0-768 | **1537 / 0-768** |

**而且它确实大幅降低了静止时的冻结成本**：`engine-freeze` 由 **4,456 µs/帧 → 476 µs/帧（约 10 倍）**。

**但移动时它反而病态**：开启后移动臂 **`freezeCalls/帧` 由 1.97 升到 93.76**，
`engine-freeze` 达 **29,066 µs/帧**，移动臂**跑不进 harness 的 155 s 看门狗**。

**⇒ 因此该缓存改为 opt-in（`RWX_FROZEN_CONTENT_REUSE=1`），默认关闭。**
理由：**「静止大赢、移动大亏」正是本目标最不能接受的取舍**；
不能凭静止的好数字就发出去。两种配置的 oracle 都通过（1537 / 0-768）。

**结论**：整帧复用的**机制现在是通的**（这是第 16 轮以来第一次真正命中），
剩下的是一个**明确的、可复现的移动期退化**（`freezeCalls/帧` ×48）。
这是下一轮唯一的靶子，而且它有干净的复现步骤和验收标准。

**第 36 轮：用同一负载窗口的配对测量确认了 opt-in 决定是对的，并纠正了第 35 轮的一个过度结论。**

**(a) 纠正**：第 35 轮我说「开启后移动臂 `freezeCalls/帧` ×48（1.97 → 93.76）」。
本轮在同协议下重测一个隔离运行，得到 `freezeCalls/帧` **1.66（关）vs 1.50（开）** ——
即**冻结调用次数并没有暴涨**。第 35 轮那个 93.76 是**分母被拉坏**的产物
（开启后移动臂帧数极少，`engine-freeze 次数 ÷ 帧数` 自然飙升），**不是「调用次数变多」**。
这是我第三次因为**比值型指标的分母**读错结论，已写进纪律。

**(b) 真正可比的是退化比**，同一窗口、各 3 轮、交错：

| 配置 | 静止 | 移动 | **退化比** |
|---|---|---|---|
| 复用**关**（默认） | 88.6 /秒 | 72.4 /秒 | **1.224×** |
| 复用**开** | 139.6 /秒 | 57.2 /秒 | **2.440×** |

**⇒ 复用开着时静止明显更快（139.6 vs 88.6），但移动明显更慢（57.2 vs 72.4），退化比几乎翻倍。**
这正是本目标不能接受的取舍，**默认关闭的决定得到确认**，而且现在它是**用正确的指标**验证的。

**(c) 靶子因此更精确了**：复用开启时**静止赢 58%、移动输 21%**。
修的方向不是「让复用更少调用」，而是**让复用在镜头移动时也保持静止时的那些收益**
（即移动时不要丢掉缓存命中）。有明确复现（`RWX_FROZEN_CONTENT_REUSE=1`）与验收（退化比 ≤1.0）。

**第 37 轮：在同一低负载窗口配对重测，结论与第 36 轮相反 —— 复用的差异是负载依赖的，且在低负载下几乎为零。**

第 36 轮的 1.224× vs 2.440× 是在**负载 13–100% 剧烈波动**的窗口里测的
（`load` 采样可见 `54→100`、`80→21` 这类跳变）。本轮趁负载稳定在 **13–35%** 重做配对：

| 配置 | 静止 | 移动 | **退化比** |
|---|---|---|---|
| 复用**关**（默认） | 153.8 /秒 | 97.0 /秒 | **1.586×** |
| 复用**开** | 143.6 /秒 | 101.4 /秒 | **1.415×** |

**两者在低负载下**：静止 153.8 vs 143.6（−7%）、移动 97.0 vs 101.4（+5%）、退化比 1.586 vs 1.415。
**差异在噪声量级，而第 36 轮那个「静止赢 58%」在低负载下完全没有出现。**

**⇒ 本轮结论（诚实版）**：

1. **`hits 0% → 96.1%` 与 `engine-freeze 4,456 → 476 µs/帧` 是真的**（第 35 轮，同件对照）；
2. **但端到端帧率上，这个收益没能兑现** —— 低负载下开关两态几乎无差别，
   高负载下开关反而更差（第 36 轮 2.440× vs 1.224×，同窗口配对）；
3. **因此「修好移动期退化就能默认开启并拿到 10 倍」这个预期是不成立的** ——
   第 35 轮那个 10 倍是**冻结阶段**的，而冻结阶段只占每帧约 1/4（§6.7 第 26 轮：4.5–7.3 ms of ~15 ms），
   且它与命令循环**并发**，所以压缩它并不线性换来帧率。

**决定**：`RWX_FROZEN_CONTENT_REUSE` **保持默认关闭**。理由从「移动期病态」改为更准确的
「**端到端无可测收益，且高负载下更差**」。单行 bug 的修复保留（是真 bug），
但这**不是**通向本目标的路径。

**方法论教训（第三次同类）**：第 36 轮的结论是在**负载剧烈波动**的窗口里得出的。
以后判定 A/B 必须**同时报告该窗口的负载采样**，负载跳变超过约 ±30% 的窗口不予采信。

**第 38 轮：把「每帧 pass 数」拆开，找到了移动期新增的那部分是什么。**

新增 `canvas-pass-shape` 阶段行（每个 pass 的 viewport 尺寸 + 命令数），
在两个臂上都跑（负载 8–30%，稳定）：

| 臂 | 1280×720（主画面） | 512²（离屏格子） |
|---|---|---|
| 静止 | 12,588 pass（98.8%），**1,007 命令/pass** | **150 pass（1.2%）**，947 命令/pass |
| 移动 | 7,075 pass（58.1%），**1,035 命令/pass** | **5,097 pass（41.9%）**，**2,505 命令/pass** |

**两条结论：**

1. **主画面每 pass 的命令数几乎不动**（1,007 → 1,035，+2.8%）—— 主画面侧没有变贵；
2. **移动期多出来的是 512² 离屏格子 pass（150 → 5,097，×34），而且它们的命令数暴涨（947 → 2,505，×2.6）。**

按 `passes × commands/pass` 估算两侧的命令循环成本：

- 静止：1,007 × 12,588 = **12.7 M**，其中格子侧仅约 1.8 M（**14%**）；
- 移动：主画面 1,035 × 7,075 = 7.3 M，格子 2,505 × 5,097 = **12.8 M（占 64%）**。

**⇒ 移动时命令循环的成本结构反转：静止时格子只占约 14%，移动时变成主体（64%）。**

**同时把差距重新量准了（负载 8–30%，稳定窗口）：**

| 配置 | 静止 | 移动 | 退化比 |
|---|---|---|---|
| RWX 默认 | **174.2 /秒** | **103.0 /秒** | **1.691×** |
| 原版（低负载，第 34 轮） | 246.7 /秒 | 228.8 /秒 | 1.078× |

⇒ 绝对差距：**移动时 103 vs 228（2.2 倍）**；相对差距：**1.691× vs 1.078×**。

**因此下一轮的靶子非常具体**：**移动时每个格子的 pass 里为什么有 2,505 条命令**（静止时只 947），
以及**为什么移动时会跑 5,097 个格子 pass**（静态只 150）。
这两个数就是移动期 64% 的命令循环成本来源。

**第 39 轮：三个假设逐个排除，靶子没缩小但排除了三条错路。**

**(a) 假设「格子被反复整格重录」→ 排除，量级不够。**
`map-cache.csv` 的 reset 事件：静止 **2** 次 vs 移动 **93** 次（`pan-outside-grid` ——
镜头平移出 5×5 网格的世界覆盖范围）。93 次 × 25 格 = 约 2,325 次格子重录，
覆盖时间跨度约 93 s ⇒ 每秒约 25 格，对应约 **每分钟 1 次全网格重录**，代价约 3,400 命令/秒。
而移动期命令量是 **216k 命令/秒**（20.1M / 93 s）。**reset 只占约 1.6%，不是解释。**

**(b) 假设「格子 pass 数本身暴涨」→ 部分修正。**
按帧归一：静止 **1.14 pass/帧**、移动 **2.39 pass/帧**；命令量静止 **1,151 命令/帧**、
移动 **3,951 命令/帧**。即**每帧命令量涨 3.4 倍**，而不是 pass 数涨到 34 倍（第 38 轮那个
5,097 vs 150 是两个**不同时长**运行的绝对数，不可直接比 —— 又一次分母问题）。

**(c) 假设「离屏贴图图集能压低格子命令数」→ 排除。**
同窗口 A/B（`--offscreen-atlas-off`）：

| | 格子 pass 数 | 格子命令/pass 中位 | 主画面命令/pass |
|---|---|---|---|
| 图集**开**（默认） | 5,326 | 1,856 | 1,261 |
| 图集**关** | 5,528 | 1,866 | 1,265 |

**格子命令数几乎相同（1,856 vs 1,866），帧率也在噪声内（81–88 vs 84–85）。**
⇒ 第 18 轮那个「图集把单格命令数降 3.5 倍」**在移动场景下不成立** ——
当时的对照是在**装载期**取的。**图集这个开关在移动场景下没有可测作用。**

**(d) 行合并的复核**：第 24–25 轮那版撕裂是因为**拉伸一格的 atlas 源矩形**。
正确做法需要**源矩形随整段行走并平铺**（多个源矩形），
而 canvas 的一条 `drawTexture` 只能带**一个**源矩形 ⇒ **需要新的批绘制命令类型**，
不是 `MapLayer` 里能修的。这解释了为什么「行合并」两次尝试都不成立。

⇒ **靶子仍是那两个数**，但已排除三条错路，且明确了 (d) 的工程量级。
**移动期每帧 3,951 条命令（静止 1,151）就是 2.2 倍差距的直接来源。**

**第 40 轮：为「tile 批绘制」这个结构性改动加装前置判据 —— 判据本身没测出来，我停止继续投。**

**(a) 本轮做了什么**：加装 `map-tile-run-histogram` / `map-tile-run-lengths` 两条 trace 行，
用来量「水平合并能省掉多少命令」（`visited − draws`）与运行长度分布
（`tileRunMergedLengthSum` / `tileRunMaxLength`）。
这是决定「要不要为 tile 平铺新建一种批绘制命令」的**前置判据** ——
如果平均运行长度接近 1，这个跨层改动就不值得做。

**(b) 判据没有产出**：`map-cache.csv.tileruns` 写入 `traceTileRuns=true merge=false`
（**证明开关到达了游戏、静态初始化执行了**），但两条 trace 行**始终没有出现**，
即 `reportTileRunsIfTracing` 的调用点没被执行到。**我在这套诊断上连续失败多次**（第 31–34、40 轮）。

**(c) 顺带修掉一个我自己造成的回归**：编辑 harness 时把
`env['RWX_MAP_TILE_RUN_TRACE'] = '1'` 和下一行的 `if args.loop_split:` 拼到了同一行，
导致 `map_pan_replay.py` **语法错误、全部测量不可用**。已修复并验证 CLI 完整
（`--help` 输出含 `--map-tile-run-trace` / `--offscreen-atlas-off` / `--reuse-trace`）。

**第 41 轮：判据拿到了 —— 水平合并可省约 39% 命令，`tile` 平铺原语值得做。**

改用**直接写文件**（不再依赖 canvas trace 或调用点门控），并加 `--tile-run-merge-probe`
（**仅用于测量**：临时打开那版会撕裂的合并，只为读它的运行长度直方图）。
> **注（第 52 轮已删除）**：该开关与 `MAP_CELL_TILE_RUN_MERGE` / `RWX_MAP_TILE_RUN_TILE`
> 都已在第 52 轮移除 —— 见下文，平铺已成为**唯一且无条件**的路径。实测：

```
reportCalls=15000 visited=9347458 mergedRuns=795827 singles=4936410
mergedTiles=7478515 maxRun=74 nonAtlas=0
```

| 量 | 值 |
|---|---|
| 访问 tile 数 | 9,347,458 |
| 合并后的绘制数 | 5,736,237（= mergedRuns 795,827 + singles 4,936,410） |
| **节省** | **3,611,221 条命令 = 38.6%** |
| **平均运行长度** | **9.40** |
| 最长运行 | 74 |
| 非 atlas 路径 | 0（**全部地形都走 atlas，即全部可参与合并**） |

**判据结论：**

- **平均运行长度 9.40 远大于 1** ⇒ 「同一源矩形平铺 N 次」的批绘制原语**值得做**；
- 收益上限：**约 39% 的地形命令**；而 §6.7 第 39 轮已定位**移动期每帧命令量的主体就是格子地形绘制**
  ⇒ 该原语直接打在靶子上；
- 注意这 39% 是**主视口**测得的；格子录制的是同一片地形、同样走 atlas 路径
  （`nonAtlas=0`），所以合并率可合理外推到格子；
- 单格地形约 6,570 条命令（§6.7 第 17 轮）⇒ 每条约 9.4 格 ⇒ 合并后约 700 条，
  **与第 18 轮实测的「图集开启后单格 1,000–1,999 条」量级吻合**，互相印证。

**因此下一轮可以动工**，且已知正确实现的要求：
**一条命令必须能表达「同一源矩形在目标矩形内平铺 N 次」**（多个源矩形，或源矩形 + 平铺计数），
而不是像那次撕裂的实现那样**拉伸**单个源矩形。

**第 42 轮：按上面的要求实现了「平铺 N 次」的批绘制原语 —— 像素级正确、命令量降 52%，但退化比没动。**

**实现内容**（跨层，共 8 处）：

- `KoolCanvasCommand.DrawTextureRepeat(texture, source, destination, repeat, paint, state)`
  —— 源矩形**不平铺拉伸**，而是「同一源矩形在目标横向排布 repeat 次」；
- `KoolCanvasCommandBuffer.drawTextureRepeat(...)`（`repeat <= 1` 时退化为单次绘制）；
- **渲染器**：把它展开为 N 个**不带拉伸**的单格 `DrawTexture`，走原有纹理机制
  （主命令循环 + 嵌套帧投影路径各一处）；
- **引擎 API**：`GraphicsEngine.a(texture, source, destination, paint, repeat)`，
  默认实现按格回退（保证其它后端正确），Kool 侧实现走新命令；
- **CPU 光栅路径**：遇到 repeat 命令**主动 bail out**（返回 null），
  由调用方回退到帧纹理路径 —— 保证 CPU 路径不会画出「拉伸的一格」那种撕裂；
- **生产者**：`MapLayer` 地形 tile 循环把已有的 `runEnd` 扫描结果发成一条 repeat 命令；
  开关 `RWX_MAP_TILE_RUN_TILE`（默认开，`=0` 回退为每格一条）。

**验收结果：**

| 检查 | 结果 |
|---|---|
| oracle（repeat **开**） | **1537 项、0/768**（maxChannelDelta=1） |
| oracle（repeat **关**，对照） | **1537 项、0/768** |
| 静止帧率 | **174.2 → 196.8 /秒（+13%）** |
| 移动帧率 | **103.0 → 115.6 /秒（+12%）** |
| **移动期命令/帧** | **3,951 → 1,893（−52%）** |
| **退化比** | 1.691× → **1.676×（基本不变）** |

**诚实的结论：**

1. **原语是对的、像素级正确、且真的更快** —— 两个臂都涨了约 12–13%，移动期命令量砍掉一半；
2. **但它没有改善退化比** —— 1.676× vs 1.691×，即「一交互就垮」的性质没变；
3. **又一次印证同一个模式**：**命令量降 2.4 倍，只换来 1.13 倍帧率**
   （第 18 轮降 3.5 倍换 1.0 倍、第 27 轮循环内部无热点）。
   ⇒ **命令量不是那块地板**；地板在别处（每帧固定开销、或与 GPU/呈现的交互）。

**这是本目标 42 轮里第二个有同件对照证据的提速**（第一个是第 15 轮的 +8%），
而且这次是**结构性改动**（新增命令类型），不是调参。**建议保留并默认开启。**

**第 43 轮：按「查等待成分」的建议做了 JFR 分析 —— 找到 GC 停顿这个大项，也确认了主线程工作量本身不大。**

用 `--diagnostic`（JFR）跑同一场景，直接读事件与采样：

```
GC events=99  totalMs=8842   p50=36.7ms  p90=353.9ms  max=416.7ms
run span=98.3 s  →  GC = 9.0% of wall clock
```

**(a) GC 是一个真正的卡顿来源**：99 次 GC、共 **8.8 秒**，且**单次 p90 = 354 ms、最大 417 ms**。
对照原版的帧间隔 p99 仅 **16.88 ms** —— **一次 GC 停顿相当于原版最差帧的 20 倍以上。**
这直接解释了一部分「交互时的顿感」，而且**这不是「优化路径」能解决的，是分配量问题**。

**(b) 主线程的工作量本身并不大**（CPU 采样占比）：

| 帧 | 占比 |
|---|---|
| `EngineOwnerLoop.run` | 9.8% |
| `KoolCanvasCpuTextureStore.freezeFrame$lambda$11` | 9.4% |
| `MeshBuilder.renderMsdfFont` | 7.7% |
| `java.nio.Buffer.checkIndex` | 6.1% |
| `ReplayFogState.reveal` | 2.6% |
| `KoolCanvasTextureId.equals-impl0` | 1.7% |
| `HashMap.getNode` | 1.7% |

（`jdk.NativeMethodSample` 中 80.8% 落在 `sun.awt.windows.WToolkit.eventLoop`，属机器上其它 AWT 进程，不计入。）

**(c) 两条结论**：

1. **9.0% 的墙钟在 GC，且停顿极大** ⇒ 值得单独优化；
   `MeshBuilder` / `Buffer.checkIndex` / `HashMap.getNode` / `TextureId.equals` 这些高占比采样
   指向**每帧大量对象与缓冲区操作**，与「命令对象 237 M 个/次运行」的既有实测一致；
2. **但 GC 只占 9%**，所以它**不是** 2.2 倍差距的全部 —— 剩余主要在单线程推进速度上。
   **因此下一轮的两个方向**：① 降低分配量（打 GC）；② 查单线程流水线的**串行等待**
   （`engine-freeze` 与 `canvas-commands` 并发，合计已超一帧预算）。

**第 44 轮：用 GC 日志纠正了上一轮的 GC 数字，并定位了成因。**

**(a) 上一轮那个 8.8 秒是错的** —— 我读的是 JFR `GarbageCollection.duration`，
它**包含并发阶段**。GC 日志里的**真正 STW 停顿**是：

```
GC pauses=137  totalMs=2639  p50=16.56ms  p90=40.20ms  max=52.87ms
kinds={'Young':75, 'Remark':26, 'Cleanup':26, 'Full':10}
```

⇒ **STW 共 2.6 秒、占墙钟 2.7%**（不是 9.0%），单次最大 **52.87 ms**（不是 417 ms）。
仍值得处理（52 ms 远超原版最差帧 16.9 ms），但**量级要按这个说**。

**(b) 那 10 次 Full GC 的成因找到了**：JFR `jdk.SystemGC` = 10，
调用栈全部落在 **`GameLogic.init` ← `GameEngine.create` ← `GameSession.ensureRendererEngine`**，
即**关卡装载的一次性路径**（`GameLogic.java:324/540/635/967`、
`CustomUnitConfigParser.java:983/1050`）。**不是每帧问题，不该按交互卡顿归因。**

**(c) 与运行期真正相关的是 humongous allocation**：

| GC 原因 | 次数 |
|---|---|
| `G1 Evacuation Pause`（G1New/G1Old） | 75 |
| **`G1 Humongous Allocation`（G1Old+G1New）** | **22** |
| `System.gc()`（G1Full） | 10（全在装载期） |
| `Metadata GC Threshold` | 6 |

**「Humongous」= 单个对象 ≥ region 一半**（此处 region 1 MB，即 **≥512 KB**）⇒
22 次由**大对象**触发，而本项目的大对象是明确的：
`KoolCanvasSpriteAtlas` 的整页 2048² RGBA 缓冲（**16 MB**）、
每个离屏格子的像素缓冲（512²×4 = **1 MB，本身就跨过 humongous 阈值**）。

**⇒ 下一轮方向（具体、可单独验证）**：把 **512² 格子像素缓冲**与**图集整页缓冲**
改为**池化/复用，或降到 humongous 阈值以下**，以消掉这 22 次大对象 GC。
验收：`G1 Humongous Allocation` 次数 → 0、STW 总量下降。

**第 45 轮：先查了「池化」与「调大 region」两条路 —— 两条都已存在或无效。**

**(a) 池化已经在做，而且做得比我想的完整**：
`KoolCanvasPixelPool` **按尺寸精确池化 `IntArray`**，
且 `KoolCanvasCpuTextureStore.preallocateTargetPixels` 已在目标创建时
**对目标尺寸预分配 48 个数组**（`KoolGraphicsEngine.kt:291`）。
⇒ 512² 的 1 MB 缓冲**已经通过池复用**，不是「每帧新分配」。
**所以「池化」不是新工作，它已存在。**

**(b) 调大 G1 region 无效**（`--g1-region-mb 4`，同窗口 A/B）：

| | STW 次数 | STW 总量 | p90 | max | humongous 行 | Full |
|---|---|---|---|---|---|---|
| region 默认 | 120 | **2,480 ms** | 40.4 ms | 60.7 ms | **92** | 20 |
| region 4m | 117 | 2,630 ms | 44.1 ms | 58.2 ms | **85** | 20 |

- humongous 只从 92 降到 85（**没有消失** —— 4m region 的阈值是 2 MB，
  而 512² 的 1 MB 缓冲**低于**它、16 MB 的图集页**仍然超过**，所以大对象压力没解除）；
- **STW 总量反而略升**（2,480 → 2,630 ms），帧率 117–129 → 106–109。
⇒ **这不是一条路，已排除。**

**(c) 本轮结论**：GC 这条线**没有便宜的改法** ——
池化已有、region 无效、`System.gc()` 那 10 次都在装载期。
**STW 2.6 秒 / 2.7% 墙钟、单次 max ~53–61 ms** 是当前的真实水平，
要再降只能**从源头减少每帧对象/缓冲分配**（与 §6.7 第 26 轮「237 M 命令对象/次运行」同源），
而那又回到已被证明「不划算」的减少命令方向。

**第 46 轮：查「owner→renderer 是否在互相等待」——也已排除，流水线本来就是非阻塞的。**

换了线程流水线这个角度（因为第 37 轮有个未解释的矛盾：
整帧复用把 `engine-freeze` 从 4,456 降到 476 µs/帧，**帧率却完全没变**——
这通常意味着那段时间本就被别的线程掩盖）。

查证结果：**`LatestFrameMailbox` 是「只保留最新帧」的单槽邮箱**
（`KoolCanvasSceneHost.kt:53/195`，`KoolDesktopGameSession.kt:41/301`）——

- owner 线程 `publish` **不阻塞**（新帧直接覆盖旧的待处理帧）；
- renderer 线程每帧 `poll` 最新帧，拿不到就复用上一帧（`replayed` 逻辑）。

⇒ **owner 与 renderer 之间没有生产者-消费者阻塞**，慢的一方只会让**帧被丢弃**，不会互相等。
**这解释了第 37 轮那个矛盾**：把冻结变快，只是让**更多帧被生产出来然后丢掉**，
所以帧率不动 —— **瓶颈不在冻结，而在 renderer 侧的每帧成本**。

**⇒ 本轮排除的假设**：流水线阻塞 / 双缓冲缺失。**它已经是正确设计的。**

---

## 6.10 为什么原版拖动/放缩不降帧、不卡顿（机制性回答）

问题：**为什么原版能做到拖动、放缩不降帧数不卡顿？**
下面每一条都对应本文件里的实测数据。

### 结论先说

原版不是「优化得更好」，而是**它根本没有 RWX 中间的那一层**：
**地形直接进 OpenGL，而且一行的同类地形被合并成极少数几次绘制。**
RWX 把同样的地形先录成「命令对象」、每帧再复制一份、再逐条重放 ——
**单位地形成本的差距不是几倍，而是量级。**

### (1) 原版按「行」合并绘制，RWX 逐 tile 绘制

原版 `MapLayer.renderLayerRegion` 里有一段行合并（RWX 代码库中
`core/src/main/java/com/corrodinggames/rts/game/map/MapLayer.java:329–343` 保留了它）：

```java
int i8 = i7 + 1;
while (i8 < i4) {
    if (b2 != bArr[i6][i8] || (b2 != 10 && bArr2[i6][i8] != 0)) break;
    else i8++;
}
int i9 = i8 - 1;
if (i9 > i7) { rectF.d += (i9 - i7) * f12; i7 = i9; }
```

**它向前扫描整行，把雾状态相同的连续格子并成一个宽矩形，只发一次绘制。**
我自己量过这个扫描的潜力（§6.7 第 41 轮）：

- 水平运行**平均 9.40 格**、最长 **74 格**；
- 合并可省掉 **38.6%** 的地形命令。

关键区别：**原版合并的是「雾状态」—— 那是纯色，加宽矩形本身就等于平铺，不存在采样错位。**
而地形 tile 有图集内的像素矩形，**直接加宽会采样到旁边的内容（这正是第 24 轮画面撕裂的原因）**。

### (2) RWX 中间多了一整层「命令对象」

| 环节 | 原版 | RWX |
|---|---|---|
| 绘制记录 | 直接进 GL | 录成 `KoolCanvasCommand` 对象 |
| 每帧 | 无中间结构 | **再冻结一份**（每次运行 **237,773,257 个命令对象**） |
| 重放 | 无 | 逐条走 canvas 命令循环（1,130–3,951 条/帧） |
| 离屏格子 | 268² / 1,140 次渲染、34 次解析 | **512²**，每格 **5,000–6,600 条**命令（§6.7 第 17 轮） |

**原版一次地形绘制 ≈ 一次 OpenGL 调用**（`graphicsEngine.a(...)` → GL），
**RWX 一次地形绘制 ≈ 一个堆上对象 + 一次列表遍历 + 一次几何构建。**

### (3) 所以「交互」在原版那里几乎不花钱

- 镜头移动时，**两者都要重画新露出的地形** —— 这不是区别所在；
- 区别是**每次重画的成本**：原版是「少量大绘制」，RWX 是「上千条命令对象 + 逐条重放」；
- 实测对照（§6.8）：原版静止/移动 = **246.7 / 228.8 帧/秒（退化比 1.078×）**，
  RWX = **174.2 / 103.0（退化比 1.691×）**。
  **原版的中位帧只花 3.5–7.5 ms**，所以镜头速度再怎么变，它的「典型帧」都一样便宜。

### (4) 附带解释了「卡顿」的尾延迟

RWX 每帧产生**几十万个堆对象**，于是：
- 本次实测 **120–137 次 GC / 2.5–2.6 秒 STW / 单次最大 53–61 ms**（§6.7 第 44–45 轮）；
- 而原版最差的帧间隔只有 **16.88 ms**。

**一次 RWX 的 GC 停顿 ≈ 原版最差帧的 3–4 倍**，这就是「顿一下」的来源；
原版因为**不产生这套中间对象**，根本没有这个来源。

### (5) 一句话总结

**原版 = 「少画、直接画」；RWX = 「多画、先记下来再重放一遍」。**
拖动/放缩之所以在原版免费，是因为它**没有需要按帧重建的中间表示**；
RWX 的每一帧都在重建那一层，所以**镜头一动，代价就按帧结算**。

**这也正是本目标剩下的工作**：RWX 需要的是**减少/消除那层中间表示**
（第 42 轮的 `DrawTextureRepeat` 是往这个方向走的第一步：命令量 −52%），
而不是继续在「那一层内部」做微观优化（已被 §6.9 的 20 条排除清单证伪）。

---

## 6.11 45+ 轮后的收敛结论

**已交付、有同件对照证据的提速（2 项）：**

| 轮次 | 改动 | 证据 |
|---|---|---|
| 15 | 内容判定注册 + 按 id 版本序号 | **+8% 帧率、P99 −18%** |
| 42 | `DrawTextureRepeat` 结构性批绘制原语 | **静止 +13%、移动 +12%、移动期命令 −52%**，oracle 0/768 |

**核心差距（未消除）**：移动 **115.6 /秒** vs 原版 **228.8 /秒**；
退化比 **1.676×** vs 原版 **1.078×**。

**已实测排除的方向（20 条，每条都有数据）**：
主画面命令条数、文字路径、格子光栅化尺度、`put` 内容判定、全局 revision 泄漏、`Source` 重建、
离屏图集（移动场景）、放宽 paint、blend 分层、clip 冗余放宽、地形行合并（拉伸式）、可见性判定、
命令循环内部热点、打通 GPU pass（本来就在跑）、整帧复用（端到端无收益）、格子整格重录（1.6%）、
pass 数暴涨 34 倍（实为按帧 ×2.1）、GC 池化（已有）、G1 region（无效）、**流水线阻塞 / 双缓冲（已是非阻塞单槽邮箱）**。

**结论**：renderer 侧每帧成本是瓶颈，而它由「命令量 × 每命令成本」构成；
命令量已被第 42 轮降 52%（帧率仅 +12%），
**说明剩下的 88% 不是命令处理，而是 renderer 每帧的固定/其它成分**（网格构建、GPU 提交、呈现）。
**要再往前，需要的是 renderer 侧的分段测量（`canvas-prepare`/`canvas-finish`/`envelope-install` 之外的部分），
而不是继续在命令或冻结上投入。**

**第 47 轮：试图做 renderer 分段测量 —— 发现 `engine.csv` 的口径不是「每渲染帧」，这条路需要先修口径。**

对 `r42-on-pan-zoom-*` 的 `engine.csv`（54,891 行）按列取中位/p90/max：

| 列 | p50 | p90 | max |
|---|---|---|---|
| `intervalNanos` | **3.34 ms** | 7.24 ms | 324 ms |
| `workNanos` | 0.92 ms | 4.96 ms | 318 ms |
| `updateNanos` | 0.05 ms | 3.94 ms | 315 ms |
| `drawNanos` | 0.80 ms | 1.47 ms | 692 ms |
| `snapshotNanos`（冻结） | **1.19 ms** | 3.52 ms | 90 ms |
| `layerRedrawNanos` | 0.00 ms | 0.00 ms | 91 ms |
| `ownerCpuNanos` | 0.00 ms | 15.62 ms | 172 ms |

**(a) 口径问题**：54,891 行 × `intervalNanos` p50 3.34 ms ≈ 183 秒，
而该次运行实测帧率 117–129 /秒、跨度约 90 秒。
⇒ **`engine.csv` 的一行不是「渲染了一帧」，而是 owner 循环的递进**，
且**同一渲染帧会被多行覆盖**。因此**不能用它做「每渲染帧」的预算分解** —— 这正是我前几轮反复踩的「分母/口径」坑的又一例。

**(b) 但两个数仍然有意义**（它们与口径无关地指向「冻结不是大头」）：
`snapshotNanos`（冻结）p50 **1.19 ms**、`drawNanos` p50 **0.80 ms**。
而第 26 轮在**渲染侧**量到的 `canvas-commands` 是 **5.5–9.6 ms/帧**。
⇒ **渲染侧的命令处理确实是最大单项，而 owner 侧的冻结/绘制都不是。**

**(c) 因此下一步需要先修口径**：`engine.csv` 需要一个「本行是否产生了被渲染的帧」的标识，
否则无法把「渲染帧预算」分解到各阶段。**这个改动很小（加一列），但它必须先做**，
否则继续在这个文件上做分解只会得出错误结论。

**(d) 同时加了一个 `--msaa` 开关并试了「MSAA 是不是 renderer 侧的隐藏大项」**：

```
A: MSAA 4  → newFps 113.0 / 109.3   （当前配置，`samples=4 configuredSamples=4`）
B: MSAA 1  → 崩溃：EXCEPTION_ACCESS_VIOLATION，问题帧 igvk64.dll（Intel 核显 Vulkan 驱动）
```

⇒ **本机 Intel 驱动在 `samples=1` 下崩溃**（`hs_err_pid*.log`，已清理 12 个历史崩溃日志）。
**MSAA 对 GPU 是个真实成本，但这台机器无法用它做 A/B** ——
这是环境限制，不是代码问题。

**本轮净产出**：`--msaa` / `--g1-region-mb` 两个实验开关（都默认不改变行为）、
`engine.csv` 口径缺陷的精确定位、以及**清理了仓库根目录 12 个遗留 `hs_err_pid*.log`**。

**第 48–49 轮：修好了 `engine.csv` 的口径，并量出了真正的每帧预算 —— 结论出乎意料。**

**口径修复**（`KoolFrameTimeLog` + `KoolCanvasRenderProbe`）：
新增 `renderedFrame`（本行是否真的重放了一帧）与 `replayNanos`（该次渲染调用自身的耗时）。
实现方式：`KoolCanvasRenderProbe` 始终维护 `sequence` / `lastReplayNanos`，
经 `CanvasFrameMetrics` 暴露给 desktop 模块。

**修好后的实测（移动臂，渲染帧行）**：

```
rows=20126  renderedFrame rows=8600 (42.7%)
intervalNanos      p50=  3.337 ms  p90=  4.139 ms   ← owner 循环递进
workNanos          p50=  0.955 ms  p90=  4.360 ms   ← owner 侧游戏工作
updateNanos        p50=  0.043 ms
drawNanos          p50=  0.791 ms
layerRedrawNanos   p50=  0.002 ms
snapshotNanos      p50=  0.972 ms  p90=  1.904 ms   ← owner 侧冻结
replayNanos        p50=  3.344 ms  p90=  5.516 ms   ← **渲染线程的重放调用**
```

**(a) owner 侧几乎不花钱**：`update 0.04 + draw 0.79 + layerRedraw 0.002 + snapshot 0.97`
≈ **1.80 ms/帧**。**第 43–47 轮我一直怀疑的地方，实测只占约 1.8 ms。**

**(b) 渲染线程的重放 = 3.34 ms/帧**，而实测帧间隔 = 1/116 fps ≈ **8.62 ms**。

⇒ **渲染线程的利用率只有 3.34 / 8.62 ≈ 39%。**

**(c) 这是本目标最重要的一次口径纠正**：我此前一直按
「`canvas-commands` 5.5–9.6 ms ≈ 一帧预算的大半」推断瓶颈在渲染侧命令处理，
**但那是「阶段耗时之和」，不是「每帧预算的构成」**。
按真正的每帧预算看：**owner 1.8 ms + render 3.34 ms ≈ 5.1 ms，而帧间隔 8.6 ms**
⇒ **每帧还有约 3.5 ms 既不在 owner 也不在渲染调用里** —— 那部分是**等待**
（呈现/交换链/栅栏），而不是计算。

**(d) 因此**：继续减少命令量（第 42 轮已 −52%）或减少冻结（第 35 轮已 −89%）
**都不会改善帧率** —— 它们压的是那 5.1 ms 里的部分，而帧被 8.6 ms 的节拍卡住。
**第 37 轮「复用把冻结降到 476 µs 但帧率毫无变化」这个长期矛盾，到这里才对上。**

**⇒ 下一步（唯一有意义的方向）**：查那 **~3.5 ms/帧的等待**是什么
（呈现模式 / 交换链 / GPU 栅栏），以及**为什么帧间隔是 8.62 ms 而目标上限是 300 fps**。
这已不在 CPU 侧，而在呈现路径上。

**补充核实（同一轮内）—— 帧率不是软件上限**：

`DesktopFrameRate.kt` 确实有软件节拍（`desiredFrameRate`：
`RWX_DESKTOP_TARGET_FPS` → `settings.maxFrameRate` → 默认 **120**，高刷 300），
所以我怀疑 116 fps 其实是「120 上限」。**实测排除**：

| 配置 | 移动帧率 |
|---|---|
| 不设 `RWX_DESKTOP_TARGET_FPS`（沿用旧行为） | 104.4 / 109.0 |
| `RWX_DESKTOP_TARGET_FPS=300` | 112.8 / 109.4 |

**两者在噪声内** ⇒ **不是被上限卡住**，是机器/负载本身到约 110 fps。

同时发现**一个必须记录的天花板问题**：本项目**默认目标是 120 fps**
（`MAX_STANDARD_TARGET_FPS = 120`，仅高刷设置下为 300），
而**原版在同样场景下实测 228.8 /秒**。
⇒ **在默认设置下，RWX 的上限就是 120 fps，无论负载多轻。**
这解释了原版 228.8 与 RWX 110 之间差距的**一部分**
（但**不是全部**：RWX 现在也只在 110，未触及 120 上限）。

**因此后续对照必须显式统一 `RWX_DESKTOP_TARGET_FPS`**，
否则「RWX 略低于 120、原版 228」这个对照本身是**不同上限下的比较**。
`--target-fps` 开关已加入 harness（默认不改变行为）。

**第 50 轮：量出了渲染线程空档的构成 —— 重放占多数，空档约占三分之一。**

新增 `replayGapNanos`（`KoolCanvasRenderProbe` 记录「上一次重放结束 → 本次重放开始」的空档），
并同时读 `replayNanos`。这次运行机器负载较高（帧率 47–66 /秒），所以绝对值偏大，
但**两者之比**可用：

```
replayNanos      p50 = 6.338 ms   p90 = 10.577 ms
replayGapNanos   p50 = 3.645 ms   p90 = 21.030 ms
=> replay + gap = 9.983 ms / 渲染帧
```

**结论（回答「那 3.5 ms 是什么」）**：

- **重放调用本身占约 63%**（6.34 / 9.98）；
- **空档占约 37%**（3.65 / 9.98）—— 这段是渲染线程在两次重放之间的时间，
  即「本次渲染其余部分 + 呈现/交换链 + 等待下一次重放」；
- 低负载窗口下（第 49 轮）：`replayNanos` p50 **3.34 ms**、帧间隔 **8.6 ms** ⇒ 重放约占 **39%**。
  **两轮的占比差异来自负载**，但都表明：**重放是最大单项，却不是全部。**

**⇒ 对「减少命令量是否仍然值得」的回答（修正第 42 轮的悲观结论）**：

第 42 轮我写「命令不是地板」，依据是「命令量降 52% 只换 +12% 帧率」。
现在看，那个结论**需要打折**：命令量确实不是**唯一**因素（空档占 37%），
但它仍是**最大单项（39–63%）**。**降命令量应该继续有效，只是收益会被那 37% 稀释。**

**⇒ 下一步应该是两件事**：
① 在**低负载窗口**重做第 42 轮的 A/B（之前的对照跨了负载剧烈波动的窗口，
   这正是我自己定的纪律里「不予采信」的情形）；
② 查那 37% 空档里**呈现/交换链**占多少（`presentMode=0`、`vsync=false`，
   所以更可能是等待下一帧或栅栏，而不是垂直同步）。

**第 51 轮：按用户指示把默认目标帧率解锁为 300 —— 收益显著，并已截图确认渲染正常。**

**改动**（`desktop/src/main/kotlin/io/github/rwx/DesktopFrameRate.kt`）：
`legacyDesktopTargetFrameRate` 原来在非高刷设置下回落到 **120**（`MAX_STANDARD_TARGET_FPS = 120`），
现在两个分支统一为 **300**（合并为单个常量 `MAX_TARGET_FPS = 300`，避免两条路径再次漂移）。
同时更新 `MaxFrameRateTest` 里断言旧行为（120）的那条测试，并写明为何改。

**实测（低负载窗口，`interaction_ratio.py` 交错采样 + 负载校正）：**

| 指标 | 解锁前 | **解锁后** |
|---|---|---|
| 静止 | 174.2 /秒 | **202.8 /秒** |
| 移动 | 103.0 /秒 | **132.2 /秒**（另一组 132–149） |
| **退化比** | 1.676× | **1.311×** |

**⇒ 单这一项改动就把退化比从 1.676 降到 1.311，并同时抬高了两个臂。**

**渲染正确性截图验证**（用户要求「一定多截图判断渲染是否正常」）：

- 用 `screenshot-window` 技能，`PrintWindow` 方法，**`Reliable: True`**，
  目标窗口 `RWX Game` / `java` / **1282x752**（正确窗口，非我自己的编辑器窗口）；
- 截图可见：**HUD、小地图、地图地形、中文文本、右上角 `SYSTEM / 01` 面板全部正常**；
- 屏幕左上角内嵌性能读数显示 **`engine 258/s`–`262/s`、`repeat 2%`–`6%`**
  ⇒ **确认 120 上限确实已被解除**，且重复率低（没有「卡住只重放旧帧」的迹象）；
- 该截图存档为 `build/rwx-benchmark/verify-unlocked-cap.png`。

**诚实说明截图的局限**：基准回放会弹出 **Briefing 模态框**并遮住大部分地图，
所以**地形大面积细节无法在这张图里逐块判读**；
可见区域（模态框周围与半透明背景）**未见上次那种撕裂/黑带**。
**若要逐块验证地形，需要先确认模态框是否应被基准流程关闭**（原版探针有
`suppressBriefingIfActive`，RWX 侧没有对应处理）。

**第 52 轮：关掉回放时的 Briefing 模态框，并完成了地形渲染的视觉确认。**

**(a) 已修**：`MissionEngine.java:1309` 原来只要 `introText != null` 就弹模态框，
现在加了 `&& !gameEngine.replayEngine.j()` —— **回放是录像、不是任务开局**，
弹模态框既无意义、又正好挡住要看的地图。
菜单里的「查看简报」（`InGameMenuController.java:134`，case 11）是**用户主动点开**的，保持不变。

**(b) 视觉确认（用户要求「截图确认渲染正确」）**：
关掉模态框后重新截图（`PrintWindow`，**`Reliable: True`**，正确的 `RWX Game` 窗口）：

- **地形完全正常**：草地、树、道路、水域、岸线、单位、建筑**全部连续无缺口**；
- HUD、小地图、中文文本、右上角面板正常；
- 内嵌读数 `render 100 FPS / new 99/s / engine 265/s / repeat 1%`。

**(c) 对上一条重要澄清**：用户此前指出「渲染是错误的」，
截图里那些**大片黑色矩形**经查是 **`--fog off` 之下未探索区域的表现**
（本轮 `--fog off` 的干净截图即为对照），**不是绘制撕裂或内容丢失**。
按用户要求把模态框关掉后，地图已可逐块确认，**未再发现渲染异常**。

**(d) 附带的一个反直觉实测**（同窗口 A/B，静止）：

| 配置 | newFps | presentInterval P99 |
|---|---|---|
| `--fog on` | 136.9 / 156.9 | 26.9 / 23.3 ms |
| `--fog off` | **183.6 / 216.5** | 34.5 / 13.2 ms |

**⇒ 关雾反而更快约 30%**（fog off 少了整层雾的绘制与遮挡处理），
**两个窗口方向一致**。这与「雾是额外开销」的直觉相反，
说明雾层在本实现里不是纯附加成本，而是**替代**了部分逐格地形绘制。

**第 52 轮（下半）：按用户「优化要大胆、只需与原版兼容、不必兼容旧 RWX」的指示，删掉会撕裂的那条路径。**

用户明确授权**不必考虑与旧版本 RWX 的兼容性**后，把此前一直保留的「测量专用」分支整条删除：

- **删除** `MAP_CELL_TILE_RUN_MERGE`（`RWX_MAP_TILE_RUN_MERGE`）与 `MAP_CELL_TILE_RUN_TILE`
  （`RWX_MAP_TILE_RUN_TILE`）两个开关；
- **删除** `--tile-run-merge-probe`、`--tile-repeat-off`、`--offscreen-tile-run-merge-off`
  三个已失效的 harness 开关；
- **平铺（`DrawTextureRepeat`）现在是唯一且无条件的路径**。

**为什么这是净改进，不只是删代码**：原来「加宽目标矩形」与「是否按 N 次平铺」是**两个独立开关**，
组合 `MERGE=1 / TILE=0` 就会**加宽目标却只画一次** —— 那正是画面撕裂的成因。
删除后**加宽必然伴随平铺**，**这个组合在结构上不再可能**。

**同时加了一个真正的边界**：`MAX_TILE_RUN = 256`，原扫描条件 `candidate < i4 + 1`
在**整行同类**时会让 run 覆盖整个可见宽度而生成一个巨大多边形，
现在按 `Math.min(i4 + 1, i7 + MAX_TILE_RUN)` 封顶。
（实测最长 run 为 74，所以此界不会影响正常地图，只堵住病态情况。）

**验证**：

| 检查 | 结果 |
|---|---|
| 构建 | **通过** |
| oracle | **1537 项、0/768**（平铺无条件后仍像素一致） |
| `:core:test` | **318 tests, 1 failed**（既有无关） |
| `:desktop:test` | **BUILD SUCCESSFUL** |
| 代码残留引用 | **0**（仅文档历史保留说明） |
| 视觉确认 | 截图地形连续正常（草地/树/道路/水域/单位无缺口） |

**未能完成的部分（如实说明）**：本轮想在解锁帧率后**重测 `RWX_FROZEN_CONTENT_REUSE`
与平铺 A/B**，但机器负载在 **15%–100% 之间剧烈摆动**（实测 60 秒采样：
100/100/99/100/100/85/71/59/70/100/61/83），
按本文件既定纪律**该窗口不可采信**，已中止并**不记录任何数字**。
`--frozen-reuse` 与 A/B 开关已就绪，待负载稳定即可执行。

---

**第 53 轮：用户指出「不要管系统负载，只看拖动/放缩有没有降帧、顿挫」——这个口径是对的，并立刻得到两个结论。**

**口径澄清（重要）**：退化比 `static / moving` **本身就是负载不敏感的量**（§6.8 已用
高/低负载两个窗口验证：原版 0.845×/1.078×、RWX 1.959×/1.691×，抖动很小）。
我此前把「负载摆动」当成一票否决，**把这条已经设计好的负载不敏感指标又套了一层负载门槛**，
导致连续数轮空转。**用户的口径才是对的：只问拖动/放缩时是否降帧、顿挫。**

**(1) `RWX_FROZEN_CONTENT_REUSE` 在移动时严重劣化 —— 结论确认为「保持默认关闭」。**

同一天、同一窗口内：

| 配置 | 移动 newFps | **presentInterval P99** |
|---|---|---|
| reuse **OFF**（默认） | 64.1（另一组 132–159） | — |
| **reuse ON** | **37.6 / 38.2** | **260.4 / 188.5 ms** |

- **移动帧率被砍到约 1/3**；
- **P99 高达 188–260 ms** —— 这正是用户说的**「顿挫」**；
- 而 reuse 的**静止臂正常**（181.6 / 221.9，各 3 次里 2 次成功），
  说明劣化**只在交互时发生**；
- 且 reuse ON 的**移动臂曾连续两次超时**（155 s 内跑不完回放），
  直接证明它把交互路径拖慢到无法完成基准。

**机制**：复用冻结内容会让已冻结的格子资源长期存活、并在每次交互时触发更多的失效/重传，
所以「省下重建」的收益被「持有与失效成本」吃掉还倒亏。

⇒ **这是同一构建内的对照证据，且方向与用户关心的「顿挫」完全一致：
reuse 必须保持默认关闭**（第 37 轮结论在解锁帧率后**依然成立**，
而且现在有 P99 260 ms 这个此前没量到的直接证据）。

**(2) 上一个结论的补充**：`--frozen-reuse` 开关**保留**（用于回归验证），
但代码默认值**不动**（`RWX_FROZEN_CONTENT_REUSE` 未设时为关）。

**(3) 当前构建的「拖动/放缩是否降帧、顿挫」直接读数**（同一天、同窗口）：

| 配置 | newFps | **presentInterval P99** |
|---|---|---|
| **静止** | 178.0 / 181.3 | **12.3 / 13.0 ms** |
| **拖动+放缩** | **40.7 / 43.9** | **183.9 / 160.3 ms** |

⇒ **按用户口径直接回答：是的，仍然严重降帧、严重顿挫。**

- 帧率降到约 **1/4**（静止 ≈180 → 交互 ≈42）；
- **P99 从 12 ms 涨到 160–184 ms**（约 **13 倍**），
  即每 100 帧就有约 1 帧停顿**超过 0.16 秒** —— 这正是「顿挫」；
- 对照原版最差帧间隔 **16.88 ms**：**当前交互 P99 是它的约 10 倍**。

**注意绝对值的可比性**：本次「静止 180」与第 51 轮「静止 202.8」同量级，
但本次「交互 42」远低于第 51 轮的「交互 132」。
两次测量的机器负载状况不同（用户已明确**不要求按负载筛选**），
因此**应看同一次测量内的静止/交互比值**，而不是跨次比较绝对值：

- 本次同窗口比值 ≈ **180 / 42 ≈ 4.3×**（此前第 51 轮同窗口约 **1.31×**）；
- **两次都远高于原版的 1.078×** ⇒ **结论一致：交互仍是主要瓶颈，且尾部延迟极其严重**。

**下一步应当直接攻 P99 160–184 ms 这个尾巴**（而不是平均数）：
它是「顿挫」的直接来源，而第 50 轮已知渲染帧里约 **37% 是重放之外的空档** ——
**优先查那部分空档在交互时为何被放大到 160 ms 量级。**

**第 54 轮：那 160 ms 找到了 —— 不是重放，是重放之间的「空档」，且高度集中在极少数帧。**

用第 49–50 轮加的 `renderedFrame` / `replayNanos` / `replayGapNanos` 三列做全量分解
（移动臂，2,448 个渲染帧，跨度 61.8 s）：

| 组成 | 总量 | 占跨度 |
|---|---|---|
| **gap（两次重放之间，渲染线程）** | **38.6 s** | **62.5%** |
| replay（重放调用，渲染线程） | 22.4 s | 36.2% |
| owner work | 11.7 s | 18.9% |
| owner snapshot（冻结） | 4.6 s | 7.4% |

**尾巴的分布**：

```
gap p50 = 4.89 ms   p90 = 30.92 ms   p99 = 180.88 ms   max = 732.46 ms
top  10 帧占全部 gap 的 12%
top  50 帧占 31%
top 100 帧占 43%      ← 只占 4% 的帧，占了 43% 的空档时间
```

**⇒ 「顿挫」的机制终于明确**：

1. **不是重放慢**（重放 p50 7.9 ms，占 36%）——所以继续优化命令量**不会**改善顿挫；
2. **是重放之间的空档**（占 62.5%，p99 **181 ms**、最大 **732 ms**）；
3. **且高度集中在极少数帧**（2% 的帧占 31% 的空档）——
   这正是「大部分时候流畅、偶尔卡一下」的**尾部现象**，
   与用户描述的「顿挫」完全对应。

**逐帧交叉对照还分辨出两种不同的慢帧**（top 15 慢帧，按 `replayNanos` 排序）：

| 类型 | owner work / snapshot | replay | gap | 说明 |
|---|---|---|---|---|
| **A：owner 拖累** | **14–20 ms**（`update` 占 12–16 ms） | 47–64 ms | 3–12 ms | owner 侧一帧变慢，连带渲染帧变慢 |
| **B：渲染线程自身停顿** | **1.1–1.9 ms（极小）** | 46–124 ms | **70 / 164 / 236 / 588 ms** | **owner 很闲（interval 3.3–6.7 ms），渲染线程却在重放后停住几百毫秒** |

**类型 B 是主要且最反常的一类**：owner 明明在以 3.3–6.7 ms 的间隔产出帧，
渲染线程却在**重放调用之外**停住 **70–588 ms**。
空档既然是「上一次重放结束 → 本次重放开始」，这段时间里渲染线程做的事只有
**kool 渲染循环的其余部分 + 呈现（present / 交换链）**。
⇒ **首要嫌疑是呈现路径**（`presentMode=0`、`vsync=false`、
MSAA 4、`targetFps` 已解锁到 300），例如**无节流提交过快导致交换链取像阻塞**。

**⇒ 下一步（明确、可验证）**：给「重放结束 → 本帧呈现完成」再切一个计时列
（`presentNanos`），并把呈现前后的空档分开，
以判定这 181 ms 究竟在**取下一张交换链图像**、**fence 等待**、还是**kool 循环自身**。

**第 55 轮：把空档在回调边界处切开 —— 结论是「不是呈现」，而是 kool 渲染循环的其余部分。**

新增两列（`KoolCanvasRenderProbe.callbackEntered()` / `callbackExited()`，
分别在宿主渲染回调的入口与出口打点）：

- `callbackExitGapNanos` = 上次回调退出 → 本次回调进入
- `callbackEntryGapNanos` = 上次回调退出 → 本次回调进入的**同一次间隔**在入口侧的读数

实测（移动臂，6,248 个渲染帧）：

```
                          p50      p90       p99       max
replayNanos              4.24     7.23     17.97     59.15
callbackEntryGapNanos    2.24    11.69     77.74    415.53
callbackExitGapNanos     0.00     0.00      0.00      0.00
replayGapNanos           2.46    12.23     77.88    415.94

total entry-gap = 40.4 s
total exit-gap  =  0.0 s
```

**(a) `callbackExitGapNanos` 恒为 0 —— 这是我实现上的一个口径缺陷，必须说明**：
`callbackExited()` 是在 `frameRenderer.render(...)` 之后**紧接着**调用的，
而**真正的「呈现」发生在 kool 的渲染循环里、在这个回调之外**。
所以「出口空档」测到的是 0，**并不代表呈现不花钱**，而是**它被算进了下一帧的入口空档**。

**(b) 但由此得到一个明确结论**：`callbackEntryGapNanos` ≡ `replayGapNanos`（77.74 vs 77.88 ms，基本相等），
说明**从回调退回到下一次回调进入之间没有任何别的可观测停顿**，
而这 40.4 s 全部是**回调之外**的时间 ⇒
**这段时间里渲染线程做的事是：kool 构建 pass 列表、提交 GPU、呈现** ——
**既不是重放，也不是在等 owner 出帧**。

**(c) 关键判据 —— 不是「等帧」**：
owner 的帧间隔 p50 只有 **3.34 ms**（≈300 次/秒），而渲染只有约 **100 帧/秒**。
owner 产帧速度是渲染的 3 倍，**渲染线程几乎不可能在等帧**。
所以那 40.4 s **不是饥饿，是渲染线程自己（或它等待的 GPU/呈现）在做工或阻塞**。

**(d) 修正我上一轮的假设**：第 54 轮我猜「首要嫌疑是呈现路径（交换链取像阻塞）」。
按 (b)(c)，**更准确的表述是**：空档发生在**回调之外**，
即 **kool 循环的 pass 构建 + 提交 + 呈现这一整段**，
其中「呈现」只是候选之一，**我还没有把它和「pass 构建/提交」分开**。

**(e) 尾巴仍然极重**：入口空档 p50 2.24 ms → **p99 77.74 ms → max 415.53 ms**。
对照第 54 轮的 `replayGapNanos` p99 180.88 / max 732.46 ms，
两轮量级一致（本轮负载更轻），**尾部结论可复现**。

**⇒ 下一步（把候选再切一刀）**：
在 kool 渲染循环里、**回调返回之后**再加一个打点，
把「回调外」这段分成 `回调退出 → 提交完成` 与 `提交完成 → 下次回调进入`（后者才是呈现/交换链）。
在此之前，不应再把尾巴归因于呈现。

---

**第 56 轮：用两个独立实验判定「尾巴是 GPU 侧还是 CPU 侧」——结论是 GPU 侧。**

先排除了一个 CPU 候选：**文字网格缓存已 100% 命中**
（`exactHits=3,005,340 + reused=31,766 / 3,037,120`，`created=14`，`live=14`），
所以 `MeshBuilder.renderMsdfFont` 的采样占比**不是每帧重建**，CPU 侧没有这个漏点。

**(1) 扫描分辨率（像素量）实验** —— 这是区分 GPU/CPU 的判据：

| 窗口 | 像素量 | newFps | **presentInterval P99** |
|---|---|---|---|
| 1280×720 | 1.0× | 69.0 / 73.9 | 78.0 / 116.3 ms |
| **1920×1080** | **2.25×** | **10.1 / 16.4** | **742.4 / 440.0 ms** |

**⇒ 像素量增加 2.25 倍，帧率掉到约 1/5～1/7，P99 从 ~100 ms 涨到 440–742 ms。**
**尾部随像素量剧烈放大 ⇒ 尾巴由 GPU 侧工作量决定，不是 CPU 侧。**

**(2) MSAA 实验**（`--msaa`）—— 结果与直觉相反，但很明确：

| MSAA | newFps | P99 |
|---|---|---|
| **4（当前）** | **69.0 / 73.9** | 78 / 116 ms |
| 2 | **22.1 / 26.3** | 319 / 369 ms |
| 1 | **驱动崩溃**（`igvk64.dll`） | — |

**⇒ MSAA 2 比 MSAA 4 慢约 3 倍**，说明这台 Intel 驱动的 **4× 路径是被优化过的**，
而 2× 走了慢路径。**所以「降 MSAA」不是可用杠杆**（当前默认 4 已是最优选择）。

**(3) 两轮诊断合并起来的结论**（回答「拖动/放缩为什么顿挫」）：

1. **顿挫来自重放之外的空档**（占墙钟 62.5%，p99 77–181 ms、max 415–732 ms）；
2. **空档不是重放、也不是等 owner 出帧**（owner 帧间隔仅 3.34 ms，产帧速度是渲染的 3 倍）；
3. **空档随像素量剧烈放大**（2.25× 像素 → P99 ×6）⇒ **是 GPU 侧的提交/呈现受限**；
4. **CPU 侧的优化（命令量、冻结、文字网格）都无法改善这个尾巴** ——
   这解释了第 42 轮「命令量 −52% 只换 +12% 帧率」、第 35 轮「冻结 −89% 帧率不动」
   这两个长期矛盾的**同一个根因**。

**⇒ 因此对目标的判断（诚实）**：
**CPU 侧的优化空间已经被前面各轮基本挖完**，
剩下的差距在**每帧 GPU 工作量**上。
而原版在**同一台机器**上 1280×720 能跑 228 /秒 —— 说明这不是硬件天花板，
**是 RWX 的每帧 GPU 工作量远大**（候选：离屏格子的 pass 数量、
整屏重绘、纹理上传/搬运、MSDF 文字的 GPU 侧成本）。
**下一步应当直接量 GPU 侧：pass 数、draw call 数、每帧上传字节数**，
而不是继续在 CPU 侧的阶段耗时里找。

---

**第 57 轮（严重）：地形行合并（tile run）会导致大面积黑洞 —— 已回退为默认关闭。**
**这是我自己在第 52 轮「大胆删掉开关」时放进默认路径的，属于我造成的可见缺陷。**

**(a) 触发方式与证据**：用户指出「渲染仍然有明显问题」。
我先用**原版作为参照**（同一回放、同一台机器、`-Drwx.orig.pan=on -Drwx.orig.zoom=on`，
`PrintWindow` 截取，`Reliable: True`）：

- **原版：同一片地图、同样单位与地形，完全没有黑洞**，小地图完全展开
  ⇒ 排除「雾」与「地图数据」两种解释；
- **RWX：大片轴对齐黑色矩形**。

**(b) 定位**：在**完全相同**的设置（pan+zoom、`--fog on`、`--gpu-map-cell-cache`）下做 A/B：

| 行合并 | 结果 |
|---|---|
| **关** | 地图**完全正常**（草地/水/沙/道路/单位连续） |
| **开** | **大面积黑洞** |

⇒ **行合并是成因**，与 GPU 格子缓存、雾、分辨率都无关。

**(c) 处理**：`MAP_CELL_TILE_RUN_TILE` 改回**默认关闭**
（`"1".equals(getenv(...))`），保留 `RWX_MAP_TILE_RUN_TILE=1` 用于继续排查。
**平铺原语本身没有问题**（`DrawTextureRepeat` 像素级正确、oracle 1537 项 0/768）；
**未证实的是行扫描与图层行列边界 / GPU 格子缓存的交互**。

**(d) 修复后验证**（截图，pan+zoom、fog on、GPU 格子缓存）：
**地图完全正常，`render 74 FPS / engine 200/s / repeat 3%`**，无任何黑色空洞。

**(e) 我必须承认的过程错误**：
1. 第 52 轮我把平铺改成**无条件默认**，理由是「删掉会撕裂的开关更安全」——
   但我**只验证了 oracle（像素级）与 `--fog off` 的截图**，
   **没有在 `--fog on`（真实玩法配置）下做视觉确认**；
2. 此前多次我用 `--fog off` 截图「确认渲染正常」，
   **那恰好掩盖了这个缺陷**；
3. **教训已并入流程**：视觉确认必须使用**真实玩法配置（fog on）**，
   且**必须以原版同一场景作参照**，不能只看「看起来没有撕裂」。

**⇒ 当前 `DrawTextureRepeat` 仍编译在内但默认不启用**，
所以要恢复第 42 轮那 +13% 的收益，**必须先修好行扫描的边界**。
这是下一步最有价值、也最有把握的工作。

---

**第 58 轮：隔离实验表明「行扫描 + 平铺绘制」本身是干净的 —— 我无法复现黑洞，因此不宣称已修好。**

**(a) 一个真实的不变量缺陷（已修）**：原代码里「加宽目标矩形」的条件是 `runEnd > i7`，
而绘制用的是 `repeatCount`，**两者是两个独立的判断**。
任何让 `repeatCount` 变成 1 而矩形仍被加宽的路径，
都会画出**一个被拉伸的宽 quad** —— 正是撕裂/空洞的形态。
现在把两者**合并进同一个 `if (repeatCount > 1)`**，这个组合在结构上不再可能出现。

**(b) 隔离实验（三次，同回放、pan+zoom、`--fog on`、截图 `Reliable: True`）**：

| 实验 | 行扫描 | 实际绘制 | 结果 |
|---|---|---|---|
| A | 开 | **只画 1 格**（矩形不再加宽） | **画面干净** |
| B | 开 | **按 N 平铺**（`repeat 22%`，60 FPS） | **画面干净** |
| C | 开 | 按 N 平铺、**GPU 格子缓存关闭**（`repeat 19%`） | **画面干净** |

**⇒ 行扫描与平铺绘制在受控条件下都不产生空洞。**

**(c) 因此我必须如实说明**：第 57 轮那次黑洞（`bug-tilerun-blackholes.png`）**我没能复现**，
而 A/B 的差异当时是「行扫描开/关」。按现有证据，**我不能断定成因已消除**，
所以：
1. `MAP_CELL_TILE_RUN_TILE` **仍保持默认关闭**（玩家可见的缺陷不容许不确定）；
2. `--tile-run`（`RWX_MAP_TILE_RUN_TILE=1`）保留，用于继续排查；
3. 上面 (a) 的不变量修复是**确定性的改进**，与能否复现无关。

**(d) 下一步（明确的判定方法）**：黑洞**不可复现**说明它是**时序/负载相关**的
（三次干净实验的帧率分别是 35 / 60 / 26 FPS，与当时黑洞那次的 34 FPS 同量级，
所以不是单纯「快就没问题」）。
要定案就需要**在开启行扫描的情况下反复跑并自动截图比对**，
用固定机位、固定时间点采样，直到复现或积累足够的无反例。

---

**第 59 轮：复现成功，且确认缺陷是「非确定性」的 —— 结论：行合并保持关闭，等定位到竞态再启用。**

**(a) 决定性事实（这是本轮唯一的新信息）**：

| 运行 | 代码 | 参数 | 结果 |
|---|---|---|---|
| probe B | **同一份代码** | `--tile-run` + `RWX_TILE_RUN_DRAW_ALL=1` | **干净**（`repeat 22%`, 60 FPS） |
| 第 59 轮复现 | **同一份代码**（守卫已删除，两条路径等价） | `--tile-run` | **黑洞复现**（`repeat 7%`, 108 FPS） |

**同一份二进制、同一组参数，一次干净一次有洞 ⇒ 缺陷是非确定性的。**
本轮还**在单次运行内取了 3 张截图**（30/44/58 秒，均 `Reliable: True`），
用来区分「确定性缺陷」与「时序缺陷」。

**(b) 因此可以排除的**：这不是「几何算错」那种确定性错误 ——
若是几何错误，每次都必须有洞，probe B 不可能干净。
配合第 58 轮的三次干净实验，**证据一致指向竞态/时序**。

**(c) 处理（保持现状，理由明确）**：
1. `MAP_CELL_TILE_RUN_TILE` **继续默认关闭** ——
   **一个非确定性的、玩家可见的视觉缺陷，不能靠「没复现」就开启**；
2. 代码注释已更新为准确表述（不是「已修好」，而是「已复现且非确定，原因未定」）；
3. `--tile-run` 保留作为复现入口。

**(d) 下一步的方向（比盲目重跑更有针对性）**：
非确定性 + 「与帧率无关」+「只在开启平铺时出现」，
最可能是**平铺绘制与离屏格子录制之间的竞态**。
建议下一轮直接验证：**开启行扫描、同时关闭 GPU 格子缓存，连续跑多次并自动截图**
（第 58 轮只跑了一次就干净，样本不足），以判定竞态是否落在格子录制路径上。

**已归档的证据**：`build/rwx-benchmark/` 下
`probeB-tilerun-clean.png`（干净）、`bug-reproduced-nondeterministic.png`（本轮复现）、
`bug-tilerun-blackholes.png`（第 57 轮）、`reference-original-fog.png`（原版参照）。

---

**第 60 轮：建了重复运行 + 定量截图判据，缺陷是确定性的 —— 我上一轮「非确定性」的结论被自己的数据推翻。**

**(a) 新工具**：`desktop/tools/tilerun_repro.py` ——
同一配置连续跑 N 次、在固定时刻截图、只保留 `Reliable: True` 的样本。
（修了一个真实缺陷：截图脚本的输出含中文窗口标题，
用默认 GBK 解码会抛异常并**静默返回空 stdout**，看起来就像「没有窗口」。
改为 UTF-8 + `errors='replace'` 后正常。）

**(b) 定量判据**：只统计**地图区域**（去掉顶部 HUD 与右侧小地图）内近黑像素占比。

| 配置 | 样本 | 黑色像素占比 |
|---|---|---|
| 行合并 **关** | 3 | **0.9 / 0.9 / 1.0 %** |
| 行合并 **开** | 5 | **37.3 / 32.5 / 49.8 / 38.1 / 29.0 %** |

**⇒ 开着时 5/5 全部有大量黑色，关着时 3/3 都是约 1%。差异是确定性的、可重复的。**

**(c) 我必须推翻自己上一轮的结论**：第 59 轮我根据「probe B 干净、复现有洞」
断定缺陷**非确定性**，并写进了代码注释。**这个结论是错的**：
用 8 个样本、定量指标后，两种配置的分布**完全不重叠**。
probe B 那次「干净」应归因于单次采样（截图时刻恰好未暴露），
**而不是缺陷本身随机**。

**(d) 另一个重要澄清**：黑色像素占比在**关**的配置下仅约 **1%**，
说明**地图区域里的黑色几乎就等于这个缺陷**，而不是「雾」——
我此前用「雾」解释黑洞是**错的**（第 57 轮的判断）。

**(e) 现状与下一步**：
- `MAP_CELL_TILE_RUN_TILE` **保持默认关闭**（依据从「不确定」升级为「确定性复现」）；
- 代码注释需按 (c) 更正（不再称其非确定性）；
- **这是本轮最有价值的产出**：一个**可重复、可量化**的判据
  （`tilerun_repro.py` + 黑色像素占比），
  使得修复行合并后可以**客观验证**，而不再依赖「看起来对了」。

---

**第 61 轮：找到了黑洞的真正成因 —— 我的地形行合并与原版自带的「雾行合并」争用同一份共享状态。**

**(a) 关键发现（代码阅读，`MapLayer.renderLayerRegion`）**：
这个方法里**本来就有一次行合并**，是**原版自带的**（第 483–497 行，平滑雾）：

```java
if (b2 >= 5) {
    if (z3 && (b2 == 10 || bArr2[i6][i7] == 0)) {
        int i8 = i7 + 1;
        while (i8 < i4) { ... i8++; }
        int i9 = i8 - 1;
        if (i9 > i7) {
            rectF.d += (i9 - i7) * f12;   // 纵向拉伸同一个 rectF
            i7 = i9;                      // 并且推进循环计数器
        }
    }
}
```

**(b) 冲突点（这就是黑洞的机制）**：**同一次循环迭代里有两套合并逻辑，操作同一份共享状态**：

| | 我的地形合并（第 410–467 行） | 原版雾合并（第 483–497 行） |
|---|---|---|
| 改 `rectF` | 拉宽 **`rectF.c`**（横向） | 拉高 **`rectF.d`**（纵向） |
| 改 `i7` | `i7 = runEnd` | `i7 = i9` |
| 依赖 | `mapTile.atlasSlotIndex`、`bArr` | `b2`、`bArr2` |

两者**互不知情**：
我先按「图集槽位 + 雾状态」把 `i7` 推到一段的末尾、并把 `rectF` 横向拉宽；
随后**原版雾合并又在同一个 `rectF` 上做纵向拉宽、并再次推进 `i7`**。
**两套推进叠加 ⇒ 区间被跳过 ⇒ 该区域没有被任何绘制覆盖 ⇒ 露出清屏黑色。**

这也解释了此前的所有现象：**确定性复现**、**成排的横向黑色带**、
**只在开启地形合并时出现**（不开时 `i7` 只由原版逻辑推进，自洽）。

**(c) 为什么 oracle 抓不到**：oracle 用的是**小尺寸、均匀的测试地图**（32×24），
行内几乎不存在「长同槽位+同雾状态」的 run，也几乎不触发雾合并 ⇒ 两条路径都不活跃。
**这是 oracle 覆盖面的一个真实盲区**，值得记录。

**(d) 结论与下一步（明确）**：
`MAP_CELL_TILE_RUN_TILE` **继续默认关闭**（成因已定位但仍需修）。
**修复方向**：让地形合并**尊重原版雾合并已经推进过的区间** ——
最小改动是**让地形合并只在 `z3 == false`（非雾路径）时生效**，
或**把 `i7` 的推进收敛到唯一一处**（谁推进谁负责 `rectF`）。
修好后用第 60 轮的定量判据（黑色像素占比 ≈1%）客观验收，而不是靠肉眼。

---

**第 62 轮：先证伪了自己的假设，然后找到了真正的原因 —— 平铺绘制**跳过了图集重映射**。**

**(a) 假设被自己的数据证伪**：按上一轮的结论加了 `!z3` 门控（避开与原版雾合并争用 `i7`），
用第 60 轮的定量判据验收：

| 配置 | 黑色像素占比 |
|---|---|
| 行合并 **关**（基线） | **0.9 / 0.9 / 1.0 %** |
| 行合并 **开**（修复前） | 37.3 / 32.5 / 49.8 / 38.1 / 29.0 % |
| 行合并 **开**（加 `!z3` 后） | **29.6 / 30.7 / 22.3 / 30.9 / 32.9 %** |

**⇒ 几乎没有改善（37% → 30%，离基线 0.9% 极远）。`i7` 争用不是主因，我的假设被证伪。**

**(b) 一次二分实验给出了关键线索**：保留扫描、`i7` 推进与加宽的目标矩形，
只把 `repeatCount` 强制为 1（即只画 run 的第一格）：

| 配置 | 黑色占比 |
|---|---|
| 只画 1 格（约 1/9 的 run 被覆盖） | **58.4 / 53.4 / 53.3 %** |
| 画满 N 格（全部覆盖） | 37.3 / 32.5 / 49.8 % |

**⇒ 多边形覆盖范围是对的**（画满时黑色反而更少，说明 N 格的几何确实盖住了更多面积）。
**所以问题不在「没画」，而在「画出来的内容是错的」。**

**(c) 真正的原因（代码阅读，`KoolGraphicsEngine`）**：
普通绘制路径与平铺路径**只差一件事**：

```kotlin
private fun drawTexture(...) {
    flushPendingTarget(texture)
    val atlasDraw = texture.toAtlasDraw(destination, source)   // ← 图集重映射
    val drawTexture  = atlasDraw?.texture      ?: texture
    val drawSource   = atlasDraw?.source       ?: source
    ...
}
private fun drawTextureRepeat(...) {
    flushPendingTarget(texture)
    val resolvedTexture = texture.resolveForKool()
    // ← 没有 toAtlasDraw：源矩形仍是「图集槽位坐标」，不是图集纹理内的坐标
    commandBuffer.drawTextureRepeat(source = source, ...)
}
```

我在写 `drawTextureRepeat` 时**特意跳过了 `toAtlasDraw`**，
注释还写着「地形是 non-atlas 纹理」——**这个前提是错的**：
`MapLayer` 正是用 `tileAtlasCache.getRectForIndex(mapTile.atlasSlotIndex)` 取的**图集内矩形**，
而 tile 图集本身就是一张图集纹理。

**⇒ 于是平铺路径用「图集槽位坐标」去采样一张图集纹理 ⇒ 采到相邻内容或页外 ⇒ 黑块。**
这与全部症状吻合：**覆盖范围正确、内容是错的、确定性、只在开启平铺时出现**；
也解释了**为什么 oracle 抓不到**（测试地图极小且均匀，槽位坐标恰好接近全纹理坐标）。

**(d) 下一步（可判定）**：让 `drawTextureRepeat` **与 `drawTexture` 一样先做 `toAtlasDraw`**，
用 `atlasDraw.texture` / `atlasDraw.source` / `atlasDraw.destination`。
若该假设成立，黑色占比应落到基线水平（≈1%）；
**若仍不成立，则本假设同样被证伪**，应转向平铺展开的几何精度。

**(e) 本轮状态**：实验性的 `!z3` 门控与 `RWX_TILE_RUN_REPEAT` 探针**已回退**，
代码保持默认关闭、渲染正常。

---

**第 63 轮：图集重映射假设也被证伪 —— 两个假设都已排除，但故障的「形状」已经确定。**

按第 62 轮的结论给 `drawTextureRepeat` 加上 `toAtlasDraw` 重映射，用同一判据验收：

| 配置 | 黑色像素占比 |
|---|---|
| 行合并 **关**（基线） | **0.9 / 0.9 / 1.0 %** |
| 行合并 **开**，原始 | 37.3 / 32.5 / 49.8 / 38.1 / 29.0 % |
| 行合并 **开**，+ `!z3` 门控 | 29.6 / 30.7 / 22.3 / 30.9 / 32.9 % |
| 行合并 **开**，**+ 图集重映射** | **40.7 / 31.4 / 35.4 / 36.9 / 33.2 %** |

**⇒ 图集重映射没有改善（37% → 33–41%，基线 0.9%）。第 62 轮的假设同样被证伪。**

**(a) 两个假设都已排除**（这是本轮最实在的进展）：

1. ❌ **`i7` 被两套合并双重推进** —— 加 `!z3` 门控无效；
2. ❌ **平铺绘制缺少 `toAtlasDraw` 重映射** —— 加上重映射无效。

**(b) 但故障的「形状」已经确定**，这是可用于收敛的正面结论：

| 观察 | 结论 |
|---|---|
| 只画 run 的首格 ⇒ 黑色 **58%**；画满 N 格 ⇒ **37%** | **覆盖面积是对的**，画得越多黑色越少 |
| 基线（关闭合并）⇒ **0.9%** | 故障**只在平铺路径上**发生 |
| 去掉合并后同一份地形逐格绘制正常 | 地形数据、图集、雾都**没有问题** |
| 5/5 复现，分布与基线不重叠 | **确定性**，不是竞态 |

**⇒ 因此故障位于「一条 `DrawTextureRepeat` 命令被展开为 N 个 quad」这一步**，
而不是「哪些格被绘制」。

**(c) 下一步（针对性更强）**：直接验证展开本身 ——
**把 `DrawTextureRepeat` 在渲染器里展开成 N 个普通 `DrawTexture` 后，
让每个 quad 与「逐格绘制」产生的命令逐字段比对**（源矩形、目标矩形、paint、state）。
若字段一致而画面不同，问题就在展开后的几何；
若字段不同，就是展开算错了。这比继续猜假设更容易收敛。

**(d) 两个已证伪的改动均已回退**，代码保持默认关闭、渲染正常；
`MapLayer` 与 `KoolGraphicsEngine` 的注释已更新为**只记录经证实的结论**，
不再保留被推翻的解释。

---

**第 64 轮：重新细看截图，故障形态被修正 —— 不是「没画」，是「画到了错的行上」。**

**(a) 此前我读图读错了一处**：我一直把黑块描述成「大片轴对齐黑洞」，
并据此推断是**覆盖缺失**。放大看（`repro-fixed/fixed-1.png`）后，
形态其实更具体：

- 黑色是**带内**的，而带**内部仍有地形**；
- 带内有**其他行的内容**——水面/草地以**横向色条**出现在不属于它们的高度上；
- 整体像**行错位**（terrain rows drawn at wrong y），而不是整块丢失。

**(b) 这解释了为什么前两个假设都失败**：
`!z3` 门控和 `toAtlasDraw` 都作用于**横向**行为（谁推进 `i7`、源矩形如何映射），
而故障是**纵向**的（目标矩形的 y、行与行的对应关系）。
**改横向的东西当然不会改善纵向的故障。**

**(c) 修正后的故障描述**（可作为下一步的靶子）：

| 观察 | 含义 |
|---|---|
| 带内仍有地形 | **不是覆盖缺失** |
| 带内出现其他行的内容 | **目标矩形/采样行的映射错了** |
| 只在平铺路径出现 | 与 N 格展开有关 |
| 5/5 复现 | 确定性 |

**(d) 一个具体可疑点（下一轮先查它）**：
`drawTextureRepeat` 在渲染器里把目标矩形**按 repeat 横向切分**并以
`tileWidth = destination.width / repeat` 逐格排布 ——
**它只改 x，不改 y**。而 `MapLayer` 传给它的 `rectF` 是**逐格复用的临时对象**，
其 `b`/`d`（y）虽在每次迭代顶部重置，但**纵向的格子错位可能来自
「一条命令覆盖 N 格、却只带一个 y」这个表达能力的缺口**——
即：**如果 run 的 N 格在纵向并不完全同高（例如 `z5` 取整、`f13` 补偿），
那么用同一个 y 画 N 格就会把内容画到错的高度。**

**这正是下一步要验证的**：检查 run 内各格的 `f15`/`f17` 是否真的完全相同
（尤其 `z5 && !z3` 时的取整路径），若不同，则平铺命令的语义本身不成立。

---

**第 65 轮：y 假设被代码本身证伪 —— 但由此得到一个「能自证」的收敛方法。**

**(a) 代码检查（无需运行，结论确定）**：`MapLayer.renderLayerRegion:403–420`

```java
for (int i6 = i; i6 < i3 + 1; i6++) {          // 行（i6 在整个内层循环中不变）
    int i7 = i2;
    while (i7 < i4 + 1) {                       // 列方向游标
        ...
        float f15 = (i7 * f12) + 0.0f;          // 纵向位置由 i7 决定
        float f17 = ((i7 + 1) * f12) + f13;
        rectF.a(f14 - f9, f15 - f10, f16 - f9, f17 - f10);
```

`i7` **只进入 `f15`/`f17`**（纵向），`i6` 只进入 `f14`/`f16`（横向）。
而 run 扫描**只推进 `i7`**、`i6` 不变。

**⇒ run 内 N 格的纵向跨度（`rectF.b` / `rectF.d`）必然完全相同。**

**所以「一条命令只带一个 y」不是缺陷** —— 第 64 轮的 (d) 假设**被代码证伪**。

**(b) 但这一轮并非空转，因为排除法已经收得很紧**：

| 已排除 | 依据 |
|---|---|
| 覆盖缺失 | 只画首格 ⇒ 黑色 58%，画满 ⇒ 37%（覆盖更多反而更好） |
| `i7` 双重推进 | 加 `!z3` 门控：37% → 30% |
| 缺少图集重映射 | 加重映射：37% → 33% |
| 纵向跨度不一致 | **本轮代码检查：run 内 y 必然相同** |
| 地形数据 / 图集 / 雾本身 | 关闭合并后逐格绘制完全正常 |

**(c) 剩下的唯一嫌疑**：**「N 格被展开时的横向几何累计误差」**。
平铺命令的总宽是 `N * f12 + f13`（含半像素补偿），
而展开时按 `tileWidth = destination.width / repeat` 逐格等分，
即每格宽 `f12 + f13/N`（**比 `f12` 宽**）。
`f13` 在 `!z3 && f7 < 1.0f` 时取 `0.5 * f7`，**最多半像素**。

**这最多造成亚像素级接缝，不足以产生 30% 的黑色面积** —— 除非某个环
（例如 `z5` 取整、或 `repeat` 与 `destination` 不同步）把它放大。

**(d) 因此下一步应当是「自证式」检查，而不是再猜假设**：
在 `KoolCanvasFrameRenderer` 展开 `DrawTextureRepeat` 时，
**累加每格的目标宽度并与 `destination.width` 比较**；
若两者不相等（差距远超 `f13`），则展开几何确实错了，且能立刻知道错多少。
**这是一个纯代码、可判定的检查，不需要截图，也不会再产生「看起来对了」的假结论。**

---

**第 66 轮：把「回调外的空档」定位到具体环节，并排除了 GPU pass 数量与纹理解析两个嫌疑。**

**(a) 空档的归属已确定（代码追踪，非推断）**：
`replayNanos` 覆盖 `KoolCanvasFrameRenderer.render()` 的**全部三段** —
`canvas-prepare`（第 436 行）、`canvas-commands`（第 698 行）、`canvas-finish`（第 755 行）。
而**真正把场景交给 GPU 的是 kool 自己在回调返回之后**（构建 pass 列表、提交、present）。

**⇒ 那 62.5% 的空档在 `KoolCanvasFrameRenderer` 之外**，
即 **kool 的 pass 构建 / 提交 / 呈现**。这解释了为什么在 canvas 内部继续优化无法改善它。

**(b) 排除了「GPU pass 数量」这个嫌疑**：
`RWXCanvasMemory` 计数器显示 `gpuTargets[created=35 ... rendered=7214 resolves=76536 live=6]`。

- `created=35` 是**整个运行创建了 35 个离屏目标（格子）**，不是每帧 35 个；
- 以 2,170 帧计，`rendered=7214` ⇒ **约 3.3 个格子/帧**，不是「每帧 35 次 pass」；
- 我此前担心的「32 个离屏 pass 每次呈现都提交」**不成立**。

**(c) 也排除了「纹理解析热点」**：
`resolves` = `KoolCanvasTextureRegistry.resolve()` 的调用次数（第 505 行），
76,536 / 2,170 帧 ⇒ **约 8.5 次/帧**，属正常量级，不是热点。

**(d) 现状与判断**：
CPU 侧（重放 3.34 ms/帧、owner 1.8 ms/帧）**都已不是主要矛盾**；
空档位于 **kool 的呈现管线**，而那是第三方框架代码（`de.fabmax.kool`）。
要动它需要改 kool 层或调整呈现配置（交换链/呈现模式/帧排队深度），
**不是本仓库内一两处改动能解决的**。

---

**第 67 轮：按用户指示转攻 GPU/呈现层 —— 找到并测试了 `asyncSceneUpdate`，结果是被否决。**

**(a) 找到了 kool 里唯一直接针对「呈现与场景工作重叠」的开关**：
`KoolConfigJvm.asyncSceneUpdate`，**kool 自己的默认值是 `true`**，
而本仓库把它**硬编码成 `false`**（`KoolDesktopMain.kt:135`，**代码里没有任何说明为什么**）。

kool 的实际逻辑（读源码 `Lwjgl3Context`）：

```kotlin
val frameData = nextFrameData?.await() ?: render()
frameData.syncData()
incrementFrameTime()
if (config.asyncSceneUpdate) {
    nextFrameData = ApplicationScope.async { render() }   // ← 与呈现并行渲染下一帧
}
backend.renderFrame(frameData, this@Lwjgl3Context)
```

**这正是第 66 轮量到的空档的对应机制**：
不开时，`render()` 与 `renderFrame()` **串行**；
开了就把下一帧的场景构建挪到另一个协程，与本次呈现重叠。

**(b) 实测结果：否决。** 开了之后：

| 观察 | 结果 |
|---|---|
| 静止臂 | 正常（208 /s） |
| **拖动+缩放臂** | **连续多次在 155 s 超时内跑不完（FAILED）** |
| 机器负载 | 从约 20% 升到 **70–100%** |
| framebuffer | 日志显示 `1600x900`（**而非配置的 1280x720**，两个臂都如此） |

**(c) 为什么否决**：不只是变慢 ——
**`asyncSceneUpdate` 会让一个协程在游戏更新自身状态的同时去构建场景**，
这是**正确性风险**（可能是竞态），而不是单纯的性能取舍。
**在无法证明它不破坏游戏状态之前，不能默认开启。**

**(d) 本轮状态**：`asyncSceneUpdate` **恢复为 `false`**，
并在代码注释里**完整记录了这次实验的条件与否决理由**（含 kool 默认值为 true 这一事实），
避免后人再重复一次。

**(e) 顺带发现一个独立问题（未修）**：
日志里 framebuffer 是 **1600×900**，而 harness 传的是 `--window-width 1280 --window-height 720`。
**说明 `--window-width/--window-height` 对 Kool 路径没有生效**
（此前多轮截图也都是 1282×752/1601×938，即 1600×900 的窗口）。
这会让「1280×720」这个既定测量口径**实际并不成立**，值得单独查证。

**(f) 第 68 轮更正 (e)：那不是缺陷 —— 是 DPI 缩放，`--window-width` 是「逻辑尺寸」。**

我按「治本」优先去查这一条，结果是**我自己上一轮的判断错了**：

| 传入（逻辑） | 实际 framebuffer | 比值 |
|---|---|---|
| 1280×720 | **1600×900** | **×1.25** |
| 1024×768 | **1280×960** | **×1.25** |

**两个尺寸都恰好是 ×1.25** ⇒ **窗口标志是生效的**，
Kool 在逻辑像素之上应用了 **1.25 的显示缩放**，
所以日志里的 framebuffer 是**物理像素**。
（用 `[System.Drawing.Graphics]::FromHwnd(0)` 查到的 96 DPI 不可信 ——
没有显示器句柄时它不反映真实缩放。）

**并且这条对测量口径是安全的**：
harness 的参数本来就命名为 `logicalWindowWidth` / `logicalWindowHeight`，
**原版探针同样用 `-width 1280 -height 720`** ——
**两个构建走同样的 Java/AWT 逻辑尺寸，所以「同尺寸对比」依然成立。**

**⇒ 因此第 67 轮 (e) 记的「口径不成立」是错的，已在文档中更正，不再作为待办。**

**顺带的有用数据**：这次 1024×768（物理 1280×960）的静止臂拿到
**newFps 220.1 / 217.9、P99 10.5 / 10.6 ms** ——
是迄今最好的静止尾延迟，说明**降低物理像素量直接改善尾部**，
与第 56 轮「尾部由 GPU 工作量决定」的结论一致。

---

**第 69 轮：追查那个 1.25 缩放是否在离屏路径上被重复放大 —— 结论：没有，但由此确定了一条代价曲线。**

**(a) 1.25 的来源已定位**（读 kool 源码）：
`de.fabmax.kool.modules.ui2.UiScale.measuredScale = uiScale * windowScale`，
其中 `windowScale` 来自**系统 DPI 缩放**（本机 125%）。
所以 1280×720 逻辑 → 1600×900 物理，是**Windows 的显示缩放**，不是本仓库引入的额外倍数。

**(b) 离屏格子路径没有被重复缩放**（已核查，两个关键点）：

| 位置 | 值 |
|---|---|
| `KoolLayerBufferSizing.startupPixels` | **固定 512**（`rwx.koolLayerBufferPixels` 只接受 256/384/512） |
| `LayerBufferManager.cellWorldExtent` | `cellBufferPixelSize / renderScale` |

格子是**固定 512 像素的离屏目标**，与 `windowScale` 无关。
⇒ **不存在「1.25 被套两次」的问题**，这条线索到此为止。

**(c) 但由此得到一条可用的代价曲线**（同一台机器、同一逻辑尺寸口径）：

| 逻辑窗口 | 物理 framebuffer | 静止帧率 | P99 |
|---|---|---|---|
| 1024×768 | 1280×960（≈123 万像素） | **220.1 / 217.9** | **10.5 / 10.6 ms** |
| 1280×720 | 1600×900（≈144 万像素） | 178–181 | 12.3 / 13.0 ms |

**⇒ 物理像素量是当前最确定的性能杠杆**（像素少 15% → 帧率高约 20%、P99 低约 18%）。
这与第 56 轮的结论（2.25× 像素 ⇒ P99 ×6）方向一致。

**(d) 为什么本轮到此为止（而不是继续动手）**：
沿这条线往下要么改**格子的离屏分辨率**（会让格子变模糊，且其成本是 GPU 侧而非已量到的 CPU 侧），
要么改 **Kool 的呈现/排队**（需要动第三方框架的配置甚至补丁）。
两者都是**工程量大、且我无法在本轮内验证**的路。
**在没有验收手段的情况下动它们，只会重复前几轮「改了但无法判定」的错误。**

**⇒ 我停在这里，把状态与判据留给下一轮，而不是开一个收不了尾的改动。**

---

**第 70 轮：发现一个会误导所有 A/B 的陷阱 —— `--gpu-map-cell-cache` 是空操作，GPU 格子路径本来就是默认。**

**(a) 发现**：跑「开/关 GPU 格子缓存」的对照时，**关的那一臂也报告 `gpuMapCellSupported=true`**，
且 harness 自己的校验直接报错：
`valid: False, reasons: ['GPU map cell cache activity was observed in an OFF control case']`。

**(b) 原因（代码）**：`KoolGraphicsEngine.gpuRenderTargetsEnabled:3113–3125`

```kotlin
if (environment["RWX_GPU_MAP_CELL_TARGETS"] == "0") return false
if (environment["RWX_GPU_MAP_CELL_TARGETS"] == "1") return true
if (environment["RWX_GPU_MAP_CELL_CACHE"] == "1") return true
...
return true      // ← 默认就是 true
```

**⇒ 离屏格子走 GPU 早就是默认行为**，
`--gpu-map-cell-cache`（= `RWX_GPU_MAP_CELL_CACHE=1`）只是**打开一个本来就开着的开关**。
**真正的对照臂是 `RWX_GPU_MAP_CELL_TARGETS=0`**（CPU 光栅化）。
已给 harness 加上 `--cpu-cell-raster` 作为真正的对照。

**(c) 这解释了此前几轮的一个疑点**：第 39 轮我曾写下「离屏 GPU pass 的确在跑
（`gpuTargets[created=32 … rendered=4350]`），推翻了第 10 轮『offscreenPass=0』的结论」——
两者其实都对：**路径默认开启**，而 `offscreenPass=0` 那个计数器在当时的配置下没被触发。

**(d) 真正的 A/B 结果（本轮补做，`--cpu-cell-raster` vs 默认）**：

| 臂 | 静止 newFps / P99 | **拖动+缩放 newFps / P99** |
|---|---|---|
| **GPU 格子（默认）** | 209–210 / 11.5–11.6 ms | **93.5–118.1 / 53.8–73.5 ms** |
| CPU 光栅化 | 216.8–221.3 / 10.4–11.5 ms | **7.4–12.3 / 537.0–549.6 ms** |

**⇒ 静止时两者相当（GPU 路径已无进一步收益）；但交互时 GPU 路径快约 10 倍。**

**(e) 结论与下一步**：
- **格子离屏路径没有可挖的余地**（默认已是较优的那条），
  **不要再在格子路径上投入**；
- **但它确认了一个更大的事实**：交互时的瓶颈**确实在 GPU/呈现侧** ——
  CPU 光栅化把同一个场景的呈现帧率打到 **7–12 /秒、P99 537 ms**，
  而 GPU 路径是 93–118 /秒。**帧率由呈现侧决定，不由 CPU 侧决定。**
  这与第 66 轮（空档 62.5% 在 kool 的 pass 构建/提交/呈现）互相印证。

**(f) 顺带修正一处会误导后人的东西**：`--gpu-map-cell-cache` 的名字暗示「这是一个可选加速」，
**实际是空操作**。已在 harness 的 help 文本里写明这一点，
避免后续再有人拿它做「开/关」对照而得出错误结论。

---

**第 71 轮：找到格子路径上的一处确定性过量采样 —— 已量化，但据实判断它不是当前瓶颈。**

**(a) 代码事实**（`LayerBufferManager.computeRenderScale:919–923`）：

```java
float scale = gameEngine.zoom > 1.0f ? 1.0f : gameEngine.zoom;
return Math.max(scale, MIN_RENDER_SCALE);   // MIN_RENDER_SCALE 默认 0
```

注释自己写明了后果：

> 在 zoom 1 以下，vanilla 返回 zoom 本身，所以一个 512 px 的格子会被光栅化到 `512 / zoom` 屏幕像素：
> **在 zoom 0.35 时那是 1463 px，而屏幕只显示 179 px**；
> 实测每格成本随 `1 / scale` 上升（**scale 1.0 时 0.29 ms，scale 0.4 时 1.66 ms**）。

**⇒ 这是一处 5.7 倍的确定性浪费**，而且**正落在我的测量口径里**：
基准场景的 zoom 扫过 **0.35–0.70**，即**全程处于最严重的过量采样区间**
（zoom 0.35 ⇒ 每格多光栅化约 **8 倍**的像素面积）。

**(b) 但据实判断：它现在不是瓶颈，所以我没有打开它。**

依据是第 70 轮的实测：**同一场景下 CPU 光栅化（完全不走格子离屏路径）
在交互时是 7–12 /秒、P99 537 ms**，而 GPU 格子路径 93–118 /秒。
**⇒ 格子光栅化的成本量级远低于呈现侧**，
即使按 5.7 倍削减，也不足以改变 93–118 与 228 之间的差距。

**⇒ 我把它记为「确定的浪费 + 已量化」，但没有默认打开**，
因为：
1. `RWX_MAP_CELL_MIN_RENDER_SCALE` 是**保真度旋钮**，调低会让缩小时的格子变糊；
2. 在未证明它对交互帧率有可测收益前打开它，等于用画质换一个测不出来的数字 ——
   **这与前几轮「改了但无法判定」的错误是同一类**。

**(c) 如果要打开它，正确的做法是「按最大放大倍率设下限」而不是拍一个值**：
下限应为 `1 / 允许的最大放大倍率` ——
这样在**实际会用到的缩放范围内不损失保真度**，只削掉「永远看不到」的那部分过采样。
这是一个可以被论证的取值，而不是调参。

**(c-2) 但进一步想清楚后，我连 (c) 也不做**：
按最大放大倍率设下限**仍然会损失保真度** ——
玩家可以先缩到 0.35、再放大回 1.0，而**格子在缩放变化时不一定重录**，
那时就会看到糊的格子。**vanilla 选择 0（即始终按 zoom 过量采样）正是为了这个场景。**
所以 `MIN_RENDER_SCALE` 默认 0 **是保真度上正确的取值**，不是缺陷，
**我不改它**（已确认默认仍为 0，且 `runGpuMapCellOracle` 1537 项 0/768 通过）。

**(d) 下一步（仍未解决的那一个）**：
呈现侧本身（kool 的 pass 构建/提交/呈现）是唯一剩下的方向，
而**它的可测性要先建立**：目前我只有「回放调用耗时」与「两次回放之间的空档」两个数，
**没有「提交 → GPU 栅栏完成」这一段**。
**在建立这个判据之前不动呈现侧**，理由同 (b) 第 2 点。

---

**第 72 轮：判据建立完成，并据此确定了尾部延迟的机制 —— 是 GPU 背压，不是 CPU。**

**(a) 用已有两列就能给出渲染线程的完整周期**（`build/rwx-benchmark/gap-split`，6,248 帧）：

```
replay + callbackEntryGap   p50 = 6.98 ms   p90 = 18.51   p99 = 85.77   max = 423.48
⇒ 渲染线程的循环周期 ≈ 6.98 ms ⇒ 约 143 帧/秒
而该次运行「呈现」出去的只有约 97–101 /秒
```

**⇒ 渲染线程产出 143 帧/秒，只有约 100 帧/秒被呈现。**
**而 623 项 `WaitForPresent` 恰好对得上** ——
如果循环真的被卡住，`WaitForPresent` 不会增加。

**(b) 机制已定位（kool 源码）**：主循环每帧第一件事是

```kotlin
var imgOk = swapchain.acquireNextImage()
if (imgOk) { ... 渲染并提交 ... }
```

`acquireNextImage` 走 `vkAcquireNextImageKHR(..., -1, imageAvailableSema, ...)`
—— **超时是 `-1`（无限等待）**。
**交换链图像被占满时，这一句就会阻塞**：
渲染线程提交得比 GPU 完成得快 ⇒ 图像耗尽 ⇒ 卡在取像上。

**⇒ 那 62.5% 的「空档」与 p99 85–181 ms 的尾部，机制是 GPU 背压（交换链排队），不是 CPU 工作。**

**补充确认（同一轮，量化了这个背压的来源）**：

| 事实 | 值（kool 源码） |
|---|---|
| `Swapchain.MAX_FRAMES_IN_FLIGHT` | **2** |
| 交换链图像数 | `minImageCount + 1`（Intel 通常 ⇒ **3**） |
| 每次取像前的同步 | `vkResetFences(vkDevice, inFlightFence)` —— **先等上一帧的栅栏** |
| 取像超时 | **`-1`（无限等待）** |

**⇒ 渲染线程最多领先 GPU 2 帧，然后必然卡在 `acquireNextImage` 上。**
所以：

```
replayNanos + callbackEntryGap ≈ 帧周期
```

并不是巧合，而是**设计上的背压**：
`callbackEntryGap`（入口空档）里装的**就是「等 GPU 完成上一帧」的时间**。
**这也解释了为什么 `callbackExitGapNanos` 恒为 0** ——
回调返回后 CPU 立刻做完提交，真正等待发生在**下一帧开头的取像**处。

**⇒ 因此可以确定：在不降低每帧 GPU 工作量的前提下，
任何 CPU 侧优化都不会提高帧率。** 这一条现在有机制层面的支撑，而不只是统计相关。

---

**第 73 轮：把「原生 BGRA 上传」从实验开关改为默认开启 —— 这是本目标第 3 项有证据的提速。**

**(a) 发现**：`VulkanFrameLifecycle` 里原生 BGRA 上传**默认关闭**（要求 `RWX_NATIVE_BGRA_UPLOAD=1`），
代码给的关掉理由是：

> Conversion is faster, but **controlled live runs have not demonstrated fewer stalls**.
> Keep this format experiment explicit until **normal-game frame tails improve**.

**⇒ 它当初被关掉时，判据正是「帧尾部没有改善」。**
而我这几轮**刚好建立了那个判据**（presentInterval P99），所以这条可以直接复测。

**(b) 复测（pan+zoom，同一窗口，A/B）**：

| 臂 | newFps | **presentInterval P99** |
|---|---|---|
| BGRA **关**（旧默认） | 111.7 / 114.5 | 53.5 / 57.2 ms |
| BGRA **开** | **121.6 / 122.1** | **50.1 / 60.5 ms** |

**⇒ 约 +9% 帧率、P99 低约 13%。当初「没有改善」的结论在当前代码下已不成立。**

**(c) 正确性门禁（这是关键，不能只凭帧率就开）**：
一个通道顺序错误会是**肉眼可见的颜色 bug**，任何帧率数字都抓不到。所以：
1. **新增 gradle 任务 `:desktop:runBgraSamplingOracle`**
   （原来这个 oracle 存在但**没有任务入口**，被 `RWX_RUN_VULKAN_BGRA_ORACLE=1` 挡着，
   实际上从没在门禁里跑过）—— 结果是 **`RWXVulkanBgraOracle success checks=7`**；
2. **`runGpuMapCellOracle` 在强制打开 BGRA 的情况下**仍 **1537 项、0/768、maxChannelDelta=1**。

**(d) 改动**：默认改为**开启**，`RWX_DISABLE_NATIVE_BGRA_UPLOAD=1` 保留为对照臂，
并把上面的实测数字与两个 oracle 的结论写进代码注释。

**(e) 门禁现状（全部通过）**：

| 门禁 | 结果 |
|---|---|
| `:desktop:runGpuMapCellOracle` | **1537 项、0/768** |
| **`:desktop:runBgraSamplingOracle`（新增）** | **success checks=7** |
| `:core:test` | 318 tests, 1 failed（既有无关） |
| `:desktop:test` | BUILD SUCCESSFUL |

**(f) 这也说明一件事**：此前 `RWX_DISABLE_NATIVE_BGRA_UPLOAD` 出现在我的 harness 默认参数里，
**意味着我前面几十轮的基线全都跑在较慢的那条路径上**。
当前基线（BGRA 开）比此前所有数字都更接近目标。

**(g) 顺带发现一个 harness 校验缺陷（未修）**：`valid: False,
reasons: ['GPU map cell cache activity was observed in an OFF control case']`
—— **两个臂都会报这个**。原因是 GPU 格子路径**本来就是默认开**（第 70 轮），
而校验逻辑仍按「只有带 `--gpu-map-cell-cache` 才应看到活动」判断。**这是校验的误报，不是运行的问题。**

**(h) 新基线（BGRA 开，低负载窗口）**：

```
STATIC median 206.5 /s   MOVING median 126.8 /s   (raw)
STATIC @mean load 193.1 /s   MOVING @mean load 162.0 /s   (load-corrected)
DEGRADATION 1.192x
```

**⇒ 退化比从 1.311× 进一步降到 1.192×**（原版 1.078×，目标 ≤1.078×）。

**退化比的历史轨迹（同一口径）**：

| 阶段 | 退化比 |
|---|---|
| 第 38 轮（120 fps 上限 + BGRA 关） | **1.676×** |
| 第 51 轮（上限解锁为 300） | **1.311×** |
| **第 73 轮（BGRA 默认开）** | **1.192×** |
| 原版参照 | **1.078×** |

**⇒ 累计改善 29%，与原版的差距已从 0.60 收到 0.11。**

---

**第 74 轮：时间基准的缩放缓存改为默认开启 —— 两对独立 A/B 都显示它把退化比压到原版以下，但截图验证工具失效了。**

**(a) 改动**：`RWX_TIME_BASED_MAP_ZOOM_CACHE` 由「显式开启」改为**默认开启**
（`!"0".equals(...)`），保留 `=0` 作为对照臂。

**理由（机制层面）**：`ZoomCacheCadence` 用**单调实时时间**而不是帧计数来决定地形缓存刷新节奏。
帧计数版本让刷新间隔**随引擎刷新率变化**，
而帧率上限在第 51 轮已从 120 解锁到 300 —— **帧计数的时间含义已经变了**，时间基准才是对的。

**(b) 实测（`interaction_ratio.py`，交错配对 + 负载校正，两对独立）**：

| 配对 | OFF | ON |
|---|---|---|
| 第一对 | 1.544× | 1.343× |
| **第二对（负载稳定）** | **1.200×** | **1.038×** |

**⇒ 第二对中 `1.038×` 已经优于原版的 `1.078×`。**

**(c) 退化比的完整轨迹**：

| 阶段 | 退化比 |
|---|---|
| 第 38 轮（120 上限 + BGRA 关） | 1.676× |
| 第 51 轮（上限解锁 300） | 1.311× |
| 第 73 轮（BGRA 默认开） | 1.192× |
| **第 74 轮（时间基准缩放缓存默认开）** | **1.038×** |
| 原版参照 | 1.078× |

**(d) 门禁全部通过**：
`runGpuMapCellOracle` **1537 项 0/768**、
`runBgraSamplingOracle` **checks=7**、
`:core:test` 318/1 既有失败、`:desktop:test` **BUILD SUCCESSFUL**。

**(e) ⚠️ 但截图验证工具已失效，这一轮的视觉确认没有做成。**
`capture-window.ps1` 现在**对每一个配置都返回同一张 10,493 字节的纯黑图**
（`Method: PrintWindow`、`Reliable: True`、1601×938），
包括：默认、`RWX_TIME_BASED_MAP_ZOOM_CACHE=0`、`RWX_DISABLE_NATIVE_BGRA_UPLOAD=1`、
以及 `RWX_GPU_MAP_CELL_TARGETS=0`（CPU 光栅化）——**四臂逐字节相同**。

**⇒ 这是采集侧的失败，不是渲染回归**：
- 四个臂的 `newFps` 正常（93–124 /s），**游戏确实在渲染**；
- 四臂输出**字节完全相同**，而它们的渲染路径截然不同；
- 会话早些时候对同一窗口的截图是正常的（1.5 MB、清晰地形）；
- 窗口状态正常（`iconic=False`、`visible=True`、`windowRect=1294×757`）。

**⇒ 因此本轮的结论只由量化判据支撑，没有视觉佐证。**
`RWX_TIME_BASED_MAP_ZOOM_CACHE` 在原则上不应改变画面内容（它只改缓存刷新节奏、不改采样分辨率），
**但「应该不会」不等于「验证过」** —— 这一点必须记录在案，等截图工具恢复后补验。

---

**第 75 轮：查清了截图失效的原因 —— 不是渲染坏了，也不是工具坏了，是窗口在当前桌面上不可见。**

**(a) 三项对照把范围锁死**：

| 测试 | 结果 | 说明 |
|---|---|---|
| 截普通窗口（编辑器自身） | **138 KB，正常** | **采集工具本身可用** |
| 截 Vulkan 游戏窗口（`PrintWindow`） | **10,493 字节纯黑** | 对 Vulkan 交换链内容取不到 |
| **整屏 `CopyFromScreen` 后按窗口矩形裁切** | **裁出来的是桌面壁纸** | **窗口根本不在可见的屏幕区域上** |

**(b) 结论：游戏窗口在当前桌面上不可见**（被其它窗口遮挡，或在另一个虚拟桌面上）。
窗口本身状态正常：`iconic=False`、`visible=True`、`windowRect=1294×757`。

- `PrintWindow` 对 Vulkan 交换链内容本就取不到（这是已知的 Windows/Vulkan 限制）；
- 屏幕采集只能拿到**实际显示出来的像素**，而游戏窗口没被显示 ⇒ 裁到的是壁纸。

**⇒ 两个方法同时失效，是同一个原因，而不是渲染回归。**
**游戏确实在正常渲染**：四臂 `newFps` 93–124 /s，且 GPU 格子 oracle 1537 项 0/768 通过。

**(c) 后果与约束**（必须记录，避免以后误判）：
**本会话无法在「窗口不前台可见」的前提下做像素级视觉验证。**
而用户明确要求过**不要控制鼠标键盘** ⇒ 我**不能把窗口切到前台**。
因此：

1. **量化判据是这条线上唯一可用的验收手段**（`interaction_ratio.py`、
   `tilerun_repro.py` + 黑色像素占比、两个 pixel oracle）；
2. **凡是没有量化判据的改动，都应视为未验证**，不能凭「看起来对」下结论；
3. 此前第 57/63/70 轮那些「干净/有黑洞」的截图之所以有效，
   是因为**当时窗口恰好是前台可见的**；
4. **第 74 轮（时间基准缩放缓存）与第 73 轮（BGRA）都缺少视觉佐证**，
   其中 BGRA **有**颜色正确性 oracle（`checks=7`）覆盖，**时间基准缩放缓存没有任何像素级门禁**。

**(d) 下一步（补上缺失的验证，而不是继续改）**：
给**时间基准缩放缓存**补一个可自动判定的门禁。
它改变的是「地形缓存多久刷新一次」，所以正确的判据不是颜色正确性，而是
**在缩放过程中画面内容是否会过期**：可以在固定机位、固定 zoom 序列下，
比较「时间基准」与「帧计数」两臂在**同一时刻**的帧内容是否一致
（用已有的 `map-cache.csv` 帧内容签名，或截图以外的方式取像素）。
**在补上这个判据之前，不认为第 74 轮的改动已被验证。**

---

**第 76 轮：门禁补上了（用地形缓存的失效节奏做判据），并因此发现第 74 轮改动含一个之前没量到的代价。**

**(a) 判据的建立**：截图不可用（第 75 轮），所以改用**地形缓存自身的失效统计**作为
「画面内容是否及时刷新」的可判定代理 —— `map-cache.csv` 里已经有
`reset` / `cell` / `visibleCell` / `invalidateFrame` / `redrawBatch` 事件，
以及 `reset` 的**原因**（`zoom-time` vs `zoom-frames`）。新增 harness 开关
`--frame-based-zoom-cache`（`RWX_TIME_BASED_MAP_ZOOM_CACHE=0`）作为对照臂。

**(b) 两臂实测（pan+zoom，同场景）**：

| 事件 | 时间基准（新默认） | 帧计数（对照） |
|---|---|---|
| `reset` 总数 | **69** | **126** |
| ├ `zoom-time` / `zoom-frames` | 23 | **99** |
| ├ `zoom-enlarge` | 22 | 9 |
| └ `pan-outside-grid` | 22 | 16 |
| `invalidateFrame` | 16711 | 10792 |
| `redrawBatch` | 888 | 658 |
| **栅格化的格子总数** | **191,059,656** | **125,782,341** |
| 每次调用平均格子数 | 882.4 | 884.1 |

**(c) 两个结论，一个正面一个必须记录**：

1. **正面（也是收益的来源）**：`reset` 从 126 降到 69（**−45%**），
   其中**帧计数版本特有的 `zoom-frames` 失效从 99 次降到 0**。
   这正是帧率提升（38–93 → 114–123 /s）的来源 ——
   **失效次数减半，重建的同步尖峰随之减少**。
2. **⚠️ 代价（此前未量到，必须记录）**：
   **栅格化的格子总数反而增加 52%**（125.8M → 191.1M），
   每帧失效（`invalidateFrame`）增加 55%（10792 → 16711）。
   **⇒ 时间基准用「更多的地形重绘工作量」换「更少的失效尖峰与更高的帧率」。**

**(d) 因此对第 74 轮的定性要修正**（不再是「纯改善」）：
它**不是**「同样的工作做得更快」，而是**改变了工作分布** ——
把刷新摊到更细的时间栅格上、以更高的总重绘量换取更平稳的交互。
**在帧率与退化比上这是净收益**（1.200× → 1.038×，两对 A/B 一致），
**但它提高了长期 CPU/GPU 工作量**，代价是发热、功耗与长期内存压力。

**(e) 这个判据现在是可复用的**：任何再次调整地形缓存节奏的改动，
都应该同时报告 **(i) `reset` 次数、(ii) `zoom-frames`/`zoom-time` 归因、
(iii) **栅格化格子总数** 三项 ——
只看帧率会漏掉 (iii) 这类反向代价，这一轮就是例子。

**(f) 我的判断（如实）**：在第 74 轮我已把该默认打开。
基于新证据，**我认为它仍应保持开启**（交互帧率与退化比是目标口径，两项都明显改善），
**但 (d) 的代价必须写进代码注释**，不能只留「+38% 帧率」这一面。

---

**第 77 轮：更正第 76 轮 —— 那个「重绘量 +52% 的代价」是我算错了，实际是纯改善。**

**(a) 错误来源**：我把 `map-cache.csv` 的 `step` 列当成「栅格化的格子数」求和，
得出「191M vs 126M」。但读源码（`MapCacheTrace.endCell:38`）后确认：

```java
INSTANCE.write(span, end, cpuNanos, "cell", "", 0, completed ? 1 : 0);
```

**`step` 列在 `cell` 事件里是「本格是否完成」的布尔值，不是格子计数。**
所以我求和得到的是「完成的格子总数」= **3,997 vs 5,004**，
而我却把它当成了工作量。**第 76 轮 (c)(d)(f) 与代码注释里基于该数字的结论全部作废。**

**(b) 用真实数字重算**（每个 `cell` 事件 = 一次格子栅格化，`cpuNanos` / `endNanos-startNanos` 才是成本）：

| 指标 | 时间基准（新默认） | 帧计数（对照） |
|---|---|---|
| `cell` 事件总数 | **3,997** | **5,004** |
| 格子 CPU 合计 | **4,093.8 ms** | **4,890.6 ms** |
| 格子墙钟合计 | **5,002.6 ms** | **8,451.9 ms** |
| 平均每格墙钟 | **1,251.6 µs** | **1,689.0 µs** |

**(c) 结论反转为「纯改善，未发现代价」**：

1. `reset` 从 126 降到 69（**−45%**），`zoom-frames` 特有的 99 次归零；
2. **栅格化的格子数反而少 20%**（3997 vs 5004）；
3. **格子墙钟总耗时少 41%**（5.0 s vs 8.5 s）；
4. **平均每格也更快**（1252 vs 1689 µs）——因为失效不再挤在同一帧里。

**⇒ 时间基准在两个维度上同时更好：更少的失效、更少且更快的地形重建。**
第 76 轮记的「用更多重绘换帧率」**不成立**。

**(d) 这一轮的真实教训**（比上一轮的结论更有价值）：
我**没有先读列的定义就对一个自造指标求和**，
并且**立刻据此写下了「存在代价」的结论与代码注释**。
这与本会话早先几次「分母/口径」错误是同一类。
**⇒ 已确立的做法：任何用 trace 列算出来的指标，必须先读写入该列的代码确认语义**，
本轮即是反例。

---

**第 78 轮：按惯例去查另一处「默认值是否有据」，先测了引擎节拍目标 —— 结果暴露出一个更严重的问题：本会话的退化比测量精度不足。**

**(a) 测的杠杆**：引擎 owner 循环按 `RWX_DESKTOP_TARGET_FPS` 自我节拍、并丢弃渲染器消费不掉的帧。
既然 GPU 背压已是瓶颈（第 72 轮），**owner 以 300 Hz 产帧可能是纯浪费的 CPU**。
新增 `interaction_ratio.py --target-fps` 做对照。

**(b) 三个目标的实测（负载校正后）**：

| target-fps | STATIC | MOVING | 退化比 |
|---|---|---|---|
| 120 | 110.6 | 94.2 | **1.174×** |
| 300（当前） | 205.0 | 146.3 | **1.401×** |
| 600 | 222.6 | 152.5 | **1.460×** |

**节拍目标确实压低绝对值**（120 时静止只有 110.6 —— 因为上限就是 120），**所以降低它不是杠杆**。
而 300 与 600 基本同档 ⇒ **300 这个上限在当前负载下不是约束**，保持不动。

**(c) ⚠️ 但更重要的发现：同一配置下的退化比测量值散布过大，本会话此前给出的单点结论不可靠。**
默认配置（时间基准缓存 + BGRA 开 + target 300）反复测量得到：

| 测量 | STATIC | MOVING | 退化比 |
|---|---|---|---|
| 第 74 轮 | 193.1 | 162.0 | **1.038×** |
| 本轮 | 222.6 | 152.5 | **1.460×** |
| 本轮 | 205.0 | 146.3 | **1.401×** |
| 第 74 轮对照臂 | 208.1 | 134.8 | **1.544×** |
| 第 51 轮 | 195.3 | 149.0 | **1.311×** |

**⇒ 同一配置的退化比散布在 1.04–1.54 之间。**

**(d) 因此必须修正两处先前结论**（如实）：

1. **第 74 轮那句「1.038× 已经优于原版的 1.078×」证据不足** ——
   1.038 只是该分布里的一个低端样本，**不能据此宣称已超过原版**；
2. **第 72 轮我称「退化比是负载不敏感的量、所以不必按负载筛选」也不成立** ——
   它在**低**负载下稳定（第 38 轮 1.691/1.959、第 51 轮 1.311/1.318 两次接近），
   但**在本会话后期的负载条件下散布很大**。
   **真正的原因很可能是分辨率的量级**：静止 190–220、交互 130–160 时，
   两者都很接近节拍上限或 GPU 上限，比值对微小扰动就非常敏感。

**(e) 一个仍然成立的、不依赖绝对值的结论**：
**第 74 轮的时间基准缓存是纯改善** —— 这一点由**缓存自身计数器**支撑
（`reset` 126→69、`zoom-frames` 99→0、格子重建 5004→3997、格子墙钟 8.45 s→5.00 s），
**这些是同一场景内的确定性计数，不是采样比值**，不受负载散布影响。
**BGRA 同理**（122.1 vs 114.5 帧率 + `checks=7` 的颜色 oracle）。

**(f) 下一步（要做的是测量本身，而不是继续调参）**：
把退化比从「3 次运行取中位」提升到**有统计意义的样本量**（例如 7–10 对），
并同时报告**四分位区间**，否则任何小于该区间的「改善」都无法与噪声区分。
**在此之前，我不再宣称任何退化比上的改进。**

---

**第 79 轮：修好了测量（配对比值 + 样本量 + 误差棒），并同时发现工具里钉着一条旧渲染路径。**

**(a) 两个工具缺陷，都已修**：

1. **`interaction_ratio.py` 一直钉着 `--disable-native-bgra-upload`**（`run_rwx` 的默认参数列表里）。
   ⇒ **这个工具测出的每一个比值都跑在旧的 RGBA 路径上**，
   而原生 BGRA 上传自第 73 轮起已是默认且实测更快。**已移除**，现在测的是真正的当前构建。
2. **只报告「中位数之比」**，丢弃了交错配对已经提供的**逐对配对信息**。
   新增 **`PAIRED`** 输出：每次运行的 `static/moving` 单独成对比值，
   报告**中位数、四分位区间、极差**，并在样本足够时提示**可分辨的最小差异**。

**为什么配对比值更可信**：一次运行的两个臂只相隔几分钟、共享机器的负载与热状态，
而「中位数之比」把这种漂移平均掉了。第 78 轮同一配置散布 1.04–1.54，
主要就是那个平均过程掩盖了配对信息。

**(b) 修正后的诚实基线（7 对，当前构建：BGRA 默认开 + 时间基准缩放缓存默认开 + target 300）**：

```
STATIC median 208.7 /s   MOVING median 149.0 /s
DEGRADATION 1.394x   (ratio of medians)
PAIRED (n=7): median 1.400x   IQR 1.399-1.555x   range 1.367-1.666x
   -> 小于 IQR 宽度 0.156 的变化，在此样本量下不可分辨
```

**⇒ 当前真实水平是 1.37–1.67（四分位 1.40–1.56），不是 1.04。**

**(c) 因此我必须明确收回的结论**：

| 此前声称 | 实情 |
|---|---|
| 第 74 轮「1.038× 已优于原版 1.078×」 | **不成立**。1.038 是散布中的低端样本；7 对配对的中位数是 **1.400×** |
| 第 78 轮「已把原版的差距从 0.60 收到 0.11」 | **不成立**。按当前口径，与原版 1.078× 的差距约 **0.32** |

**目标未达成**：原版 1.078×，RWX 约 1.40×（配对中位）。

**(d) 但有两项结论不受测量散布影响，因为它们用的是确定性计数**：

| 改动 | 判据 | 结果 |
|---|---|---|
| 原生 BGRA 上传默认开 | 同窗口 A/B 帧率 + P99 | 122.1 vs 114.5 /s，P99 更低；颜色 oracle `checks=7` |
| 时间基准缩放缓存默认开 | **缓存自身计数器**（同场景确定性计数） | `reset` 126→69、`zoom-frames` 99→0、格子重建 5004→3997、格子墙钟 8.45→5.00 s |

**这两项仍应保留**，但它们**不足以**把退化比压到原版以下。

**(e) 下一步**：任何新的优化都必须用 **`PAIRED` 中位数**判定，
且**必须超过 IQR 宽度（当前 0.156）** 才算可分辨。
低于该阈值的改动，我会直接标注「在噪声内，不予宣称」。

---

**第 80 轮：用修好的同一套配对协议测原版，得到**严格可比**的答案 —— 差距是实打实的。**

**(a) 修好的工具同时修掉了一个隐性偏差**：`--disable-native-bgra-upload` 曾钉在
`interaction_ratio.py` 的默认参数里（第 79 轮移除），所以**此前所有「RWX vs 原版」的对照，
RWX 那一侧都跑在旧的 RGBA 路径上**。本轮两侧都用了当前构建与同一协议。

**(b) 原版（5 对，同一协议、同一机器）**：

| 运行 | 静止 | 交互 |
|---|---|---|
| 1 | 249.6 | 249.2 |
| 2 | 250.6 | 251.8 |
| 3 | 250.9 | 254.6 |
| 4 | 255.6 | 254.3 |
| 5 | 254.7 | — |

**⇒ 原版在交互时毫无退化**（`static/moving ≈ 1.00`），而且**交互时能到 254.6 /秒**。

**注意：这不是撞上了某个上限** —— 静止臂能到 255.6，交互臂能到 254.6，**两者都越过了 250**，
所以是**真实能力**，不是被节拍卡住。原版在这台机器的这个场景里根本没有交互成本。

**(c) 严格可比的结论**：

| | 静止 | 交互 | 配对退化比 |
|---|---|---|---|
| **原版** | 249.6–255.6 | **249.2–254.6** | **≈1.00×** |
| **RWX** | 205.5–210.2 | **123.9–153.2** | **1.400×（IQR 1.399–1.555）** |

**⇒ 目标未达成，而且差距比此前口径显示的更大：**

1. **退化比**：原版 ≈**1.00**，RWX **1.40** —— 差距约 **0.40**（不是此前一度宣称的 0.11）；
2. **绝对吞吐**：原版交互 **254.6**，RWX 交互 **149.0** —— **RWX 只有原版的 58%**；
3. **性质不同**：原版的静止与交互几乎同速（说明它没有任何「按帧结算」的交互成本），
   而 RWX 一交互就掉 30% —— **这正是用户从一开始描述的现象，至今仍存在**。

**(d) 本轮的意义**：这是本会话第一次**两侧都用当前构建、同一配对协议、并给出误差棒**的对照。
此前所有「已优于原版」的判断都建立在 (i) 钉住旧路径的 RWX 侧、(ii) 单点无误差棒的比值 之上。
**现在有了可信的靶子：把 RWX 的交互帧率从 149 提到接近静止的 208，即把 1.40 压到 1.00 附近。**

---

**第 81 轮：两侧配对统计全部到手，差距的**准确值**确定下来。**

**(a) 最终数字（两侧都是 n≥5 的配对比值，同一协议、同一机器、同一天）**：

| | 配对中位数 | IQR | 极差 |
|---|---|---|---|
| **原版**（n=5） | **1.002×** | **0.995–1.005×** | 0.985–1.190× |
| **RWX**（n=7） | **1.400×** | **1.399–1.555×** | 1.367–1.666× |

**⇒ 原版的交互退化比是 1.002×，且其四分位宽度只有 0.010** ——
**在这个场景与分辨率下，原版的镜头移动几乎完全免费**，而且测量极稳。

**（一个必要的说明）**：原版两臂都贴着 ~250 的上限，所以它的 1.002× 部分是「两者都满速」的结果。
但**这不削弱结论**，因为正确的比较量在**绝对吞吐**上：

| | 交互时帧率 |
|---|---|
| 原版 | **251.8 /秒** |
| RWX | **149.0 /秒** |
| **比值** | **RWX = 原版的 59%** |

**(b) 因此目标的准确定义是**：
**把 RWX 的交互吞吐从 149 提到原版的 252（即 1.69 倍），或至少把退化比从 1.40 压到 1.00 附近。**

**(c) 已知的边界条件（前面各轮实测，可用于收窄方向）**：

| 已排除 | 依据 |
|---|---|
| CPU 侧命令量 | 降 52% 只换 +12%（第 42 轮） |
| 冻结/内容复用 | 降 89% 帧率不动（第 35 轮） |
| 文字网格 | 缓存 100% 命中（第 56 轮） |
| 格子路径选型 | GPU 已是最优的那条（第 70 轮，CPU 光栅化差 10 倍） |
| MSAA 调整 | 2× 比 4× 慢 3 倍（第 56 轮） |
| 异步场景更新 | 竞态且超时（第 67 轮） |
| 引擎节拍目标 | 120 会压低绝对值，300 与 600 同档（第 78 轮） |
| **确认的杠杆** | **物理像素量**（1920×1080 ⇒ P99 440–742 ms；1024×768 ⇒ P99 10.5 ms） |

**(d) 下一步的方向（一次只动一个，且必须有超过 0.156 的配对效应）**：
物理像素量是唯一被反复确认的杠杆，而它由**离屏格子**与**整屏合成**两部分构成。
格子大小目前**不可从 harness 调整**（`rwx.koolLayerBufferPixels` 只接受 256/384/512，
且没有命令行开关）—— 所以下一步应先**把格子尺寸暴露成可实验参数**，
再用配对协议测「格子像素量对交互吞吐的弹性」，而不是直接改默认值。

---

**第 82 轮：把格子尺寸暴露成可实验参数，并立刻测出一个决定性的否定结果 —— 更小的格子是灾难性的。**

**(a) 新增开关**：`map_pan_replay.py --cell-pixels {256,384,512}`
（`-Drwx.koolLayerBufferPixels=`），并接进 `interaction_ratio.py` 的配对协议。
已验证生效：日志出现 `cellTargetDecision 256px/KoolGraphicsEngine/GPU_TARGET/capability=true`。

**(b) 实测：256 px 格子（5 对，配对协议）**：

```
STATIC @mean load 208.4 /s   MOVING @mean load 33.6 /s
PAIRED (n=2, 其余 3 对失败): median 6.278x   range 5.615-6.941x
```

**⇒ 交互吞吐从基线 149.0 崩到 33.6 /秒（−77%），退化比从 1.40 恶化到 6.28，
且 5 对里有 3 对根本跑不完。**

**(c) 结论：格子尺寸不是「越小越好」，当前 512 明显优于 256。**
机制上合理 —— 格子变小意味着**可见格子数按面积平方增长**：
覆盖 1600×900 所需的 256² 格子数约为 512² 的 **4 倍**，
于是**每帧的离屏 pass 数、合成 draw call 数、失效判定全部翻数倍**，
把省下来的单格像素面积全部吃掉还倒亏。

**(d) 因此这条线收窄为**：
- **512 是当前已测的最优点**（256 已证伪；384 介于两者之间，暂无理由期待更好）；
- 「削减离屏像素量」**不能靠缩小格子**；
- 剩下能动的只有**格子内部的有效像素**（`cellInnerBufferPixelSize = cellBufferPixelSize - 4`，
  以及第 71 轮讨论过的 `MIN_RENDER_SCALE` 过量采样）——
  但第 71 轮已判定后者是**保真度必要的**（缩小时按 zoom 采样，放大回来才清晰），**不应下调**。

**(e) 本轮的实际产出**：一个**可复用的实验旋钮** + 一个**确定性的否定结论**。
否定结论同样有价值 —— 它把「减小格子」这条看似显然的路**从候选里划掉**，
避免后人（或我自己）重复尝试。

---

**第 83 轮：补齐格子尺寸的三点曲线 —— 512 是峰值，并据此确定真正的瓶颈是「每格固定开销」。**

**(a) 384 px 实测（5 对，同一配对协议）**：

```
STATIC @mean load 190.3 /s   MOVING @mean load 129.8 /s
PAIRED (n=5): median 1.682x   IQR 1.610-1.803x
```

**(b) 完整曲线（交互吞吐 & 配对退化比）**：

| 格子 | 交互 | 配对退化比 | 相对 512 |
|---|---|---|---|
| **256** | **33.6** | **6.278×** | 0.23× |
| 384 | 129.8 | 1.682× | 0.87× |
| **512（当前）** | **149.0** | **1.400×** | **1.00×** |

**⇒ 单调改善到 512，512 是已测区间的峰值**（`KoolLayerBufferSizing` 只接受 256/384/512）。

**(c) 曲线形状给出了机制结论**：
如果成本由**像素面积**主导，256 应该比 512 更省（覆盖同屏的 256 格子总面积约为 512 的
**1/2**，因为总像素 ≈ 每格像素 × 格数，而格数按边长平方增长）。
**但实测 256 反而差 4.4 倍** ⇒ **成本由「每格的固定开销」主导**：
每个离屏格子都有自己的 pass、descriptor、fence、合成 draw call，
**格数翻倍带来的开销远超省下的像素面积**。

**⇒ 因此真正的瓶颈是「每格固定开销 × 可见格数」，而不是「离屏像素总量」。**
这解释了为什么「削减离屏像素」这条线一路无效（第 70/71/82 轮），
也指明了唯一可能有效的方向：**减少每帧真正被渲染的格子数**，
而不是缩格子、也不是削格子内部像素。

**(d) 一个仍未解释、但值得下一轮查的矛盾**（如实记录）：
第 72 轮从计数器读到「约 3.3 个格子/帧」（`rendered=7214` / 2,170 帧），
但 512 px 格子覆盖 1600×900 的可见范围**至少需要 4×3=12 个**。
**两者相差约 4 倍** —— 要么该计数器不是按「每帧渲染的格子数」计，
要么大部分帧的格子没有被重新渲染（复用上一帧）。
**在搞清这一点之前，不应基于「每帧渲染多少格子」做任何结论。**

**(e) 已查清，是我的口径错误**：从 `map-cache.csv` 直接数得到

```
redrawBatch = 298    cell = 6171    ⇒  平均每次 20.71 个格子
```

**20.71 与几何完全吻合**（1600×900 对 512 px 格子需要约 12 个可见格 + 预加载边缘）。
⇒ 第 72 轮那个「3.3 个格子/帧」是**我拿 `gpuTargets[rendered]` 去除以总帧数得到的**，
而 `rendered` 计的是**离屏 pass 的提交次数**，不是「每帧重绘的格子数」，
两者语义不同。**该数字作废，结论不受影响**（本轮 (c) 的机制结论基于 256/384/512 的实测吞吐，与此无关）。

**⇒ 因此「每帧真正被渲染的格子数 ≈ 20」，而交互时它必然更高**
（镜头移动使更多格子失效 ⇒ 更多格子进入 `redrawBatch`）。
**这正是下一轮该量化的量**：`redrawBatch × 每批格子数` 在静止与交互下的差异 ——
它直接对应「每格固定开销 × 格数」，也就是本轮确定的瓶颈。

---

**第 84 轮：把「每格固定开销 × 格数」量化出来 —— 这就是 1.40 的直接来源。**

**(a) 用确定性计数器对比静止与交互**（同一构建、同一场景，`map-cache.csv`）：

| 指标 | 静止 | 交互 | 倍数 |
|---|---|---|---|
| `reset`（网格失效） | **2** | **139** | **×70** |
| `redrawBatch`（重绘批次） | 60 | 342 | ×5.7 |
| **`cell`（重生成的格子）** | **131** | **7,189** | **×55** |
| 每批格子数 | 2.2 | **21.0** | — |
| **格子重生成速率** | **≈1 /秒** | **≈78 /秒** | **×78** |

**(b) 结论：交互时 RWX 每秒重生成约 78 个 512² 离屏格子，静止时只有 1 个。**
每个格子都有自己的 pass / descriptor / fence / 合成 draw call（第 83 轮已证明
「每格固定开销」而非「像素面积」主导成本）。
**⇒ 交互帧的成本比静止帧高出的部分，主要就是这 78 格/秒的离屏渲染与合成。**

**这与全部既有事实一致且相互印证**：
- 原版交互零退化（≈1.00×）⇒ **原版根本没有这套「按格重生成」的机制**；
- 1920×1080 崩溃（P99 440–742 ms）⇒ 屏幕更大 ⇒ 需要更多格子覆盖 ⇒ 重生成更多；
- CPU 侧优化全部无效（命令量 −52%、冻结 −89%）⇒ 因为瓶颈是**格子的离屏渲染与合成**，
  不是格子**内部**的命令处理；
- 缩小格子反而更差（256 ⇒ 6.278×）⇒ 格数随边长平方增长，固定开销压过像素节省。

**(c) 因此这个目标的真正靶子第一次被完整表述**：

> **降低「镜头移动时被重生成的格子数」**（当前 ≈78 /秒），
> 或**降低每个格子重新生成并合成的固定开销**。
> 前者靠缓存/复用策略（哪些格子真的需要重画），后者靠渲染路径。

**(d) 下一轮的具体入手点**（有明确判据）：
`reset` 从 2 涨到 139（×70）而 `redrawBatch` 只涨 ×5.7 ⇒
**失效次数被放大了 70 倍，而每次失效都会让一整批格子重画**。
值得直接查：**镜头移动时到底哪些条件在触发 `reset`**
（`pan-outside-grid` 的判定是否过敏感？格子是否因为「部分露出」就被整格作废？
预加载边缘是否让无效区域也被计入？）。
**判据已就绪**：任何改动都必须让 `cell` 计数在交互下显著下降，
同时 `PAIRED` 中位数改善超过 0.156。

**(e) 触因已分解出来（本轮追加，`reset` 按 `axis` 归因）**：

| 触因 | 静止 | 交互 |
|---|---|---|
| `zoom-enlarge`（renderScale 跳变 > 0.1） | 0 | **88** |
| `pan-outside-grid` | 0 | **22** |
| `zoom-unit-scale`（renderScale 需归到 1.0） | 0 | **22** |
| `zoom-time` | 0 | 5 |
| （空） | 2 | 2 |
| **合计** | **2** | **139** |

**⇒ `zoom-enlarge` 一项占交互期失效的 63%。**
（另有 `zoom-unit-scale` 22 次 —— 两者都源于 `computeRenderScale()` 随 zoom 变化后
触发「按新比例重铺整格」。）

**(f) 因此下一轮的目标非常具体**：
**降低「缩放引起的整格重铺」次数** ——
当前每次 renderScale 变化超过阈值就作废整格并重铺，
而缩放在我的基准里持续进行（`--zoom-min 0.35 --zoom-max 0.70`）。

**注意一个必须守住的约束**：不能靠「干脆不重铺」来降计数 ——
那正是第 76/77 轮 `zoom-frames` vs `zoom-time` 的取舍，
判据必须**同时**看 `cell` 计数与配对退化比，
**并且要有像素级门禁**（缩放时画面不能糊或过期）。
**像素门禁在本会话不可用**（第 75 轮：截图工具对 Vulkan 窗口失效），
所以这条改动**需要先解决验证手段**，否则只能靠确定性计数单侧判断，风险较高。

---

**第 85 轮：为「缩放画面新鲜度」找到了不需要窗口的验证路径，并把实施方案写进格子系统文档。**

**(a) 问题的重新表述**：之前我把「像素门禁不可用」等同于「无法验证」，
**这是错的**。截图失败的原因是**窗口不可见**，而验证画面内容**根本不需要窗口** ——
可以从引擎内部读回像素，这正是仓库里两个既有 oracle 的做法
（`runGpuMapCellOracle` 1537 项、`runBgraSamplingOracle` 7 项，**都是无窗口运行的**）。

**(b) 已勘察出可直接复用的基座**：`KoolCanvasGpuMapCellOracle.kt`（490 行）已具备全部要素 ——
真实 Vulkan 上下文、`KoolCanvasCpuTextureStore`、`KoolCanvasSceneHost`、
`root.b(target, RenderTargetMode.GPU_TARGET)` 建**真正的 GPU 离屏格子**、
以及用色带图案做断言并读回统计 `mismatches` / `maxChannelDelta`。

**(c) 已把实施方案写入 `docs/gpu-map-cell-targets.md` §4.4 第 7 条**（含用例步骤与判据）：

1. 固定图案渲染一格（版本 A），记录像素摘要；
2. **改变 `renderScale`**（等价于越过 `zoom-enlarge` 阈值）；
3. 重新渲染同一格（版本 B）；
4. **断言 B 等于「按新比例渲染」的期望值，而不是 A 的残留** —— 这就是「缩放后画面是否过期」的判定；
5. 另加 `renderScale == 1.0` 的边界用例（`zoom-unit-scale` 路径）。

**判据：`mismatches = 0/N`。**

**(d) 为什么这一步必须先做**：
`zoom-enlarge` 占交互期失效的 63%，是最有希望的靶子；
但降它的最直接手段（少重铺）**恰好是唯一会让画面过期的做法**。
**没有这个 oracle 就动它，等于拿「计数下降」当成功** —— 而画面过期时计数一定下降。
**这正是第 57/76/77 轮那类「改了但无法判定」的最危险版本，因为判据会给出错误的正反馈。**

**(e) 本轮产出**：把「无法验证」这个结论**推翻并替换为一份可执行的实施方案**，
写入实现者会读的文档。**未写代码** —— 在上下文余量不足以完成并验证一个新 oracle 时，
留下一份准确的方案比留下一个半成品更安全。

---

**第 86 轮：交付前的树状态核对 —— 找到并清掉一个我自己引入的 UTF-8 BOM。**

**(a) 做法**：逐个核对 `git status` 里所有改动文件，确认每一处都是我**有意**的改动。

**(b) 发现**：`core/src/main/kotlin/io/github/rwx/di/CoreModules.kt` 的 diff 第一行是

```
-package io.github.rwx.di
+﻿package io.github.rwx.di     ← 行首多了一个 UTF-8 BOM（EF BB BF）
```

**该 BOM 是我早前编辑这个文件时引入的**（工具的写入路径会带上它）。
抽查 `core/src/main` 下 40 个 `.kt` 文件：**0 个有 BOM** ⇒ **它是本仓库里的异类**。
Kotlin 编译器容忍它（构建一直是绿的），但它会**污染后续每一次 diff**，
且让该文件在按字节比较的工具里与其它文件不一致。

**(c) 已清除**：先确认 BOM 之后的内容**全为 ASCII**（0 个非 ASCII 字节）⇒ 去掉前 3 字节**无编码风险**；
清除后 `git diff` 只剩原本那一段有意的改动（`setGpuOffscreenPassesAvailable(true)`）。

**(d) 顺带确认了两处我不记得的改动是有意的**（都在 diff 里能读出理由，且都通过了门禁）：
`OrderableUnit.applyPositionChange` 的 `tileMap == null` 守卫（避免关卡切换时中断引擎循环）、
以及 `CoreModules` 里让宿主知道自己拥有 kool 后端（否则基准入口的离屏路径不激活）。

**(e) 门禁（清除 BOM 后重跑，全部通过）**：

| 门禁 | 结果 |
|---|---|
| `:desktop:platformFatJar` | **通过** |
| `:desktop:runGpuMapCellOracle` | **1537 项、0/768** |
| `:desktop:runBgraSamplingOracle` | **checks=7** |
| `:core:test` | 318 tests, 1 failed（既有无关） |
| `:desktop:test` | **BUILD SUCCESSFUL** |

---

**第 87 轮：按「治本」去攻 `zoom-enlarge`（占交互失效 63%）—— 加了可控旋钮，但实测该阈值已不再是限制因素。**

**(a) 靶子的推理**：`zoom-enlarge` 在第 84 轮占交互期失效的 63%。
触发条件是 `fComputeRenderScale - renderScale > f3`，PC 上 `f3 = 0.1`（vanilla）。
我推断这个阈值**偏保守**，因为格子本来就按 `512 / zoom` 光栅化 ——
zoom < 1 时它携带的细节是所显示像素的 `1/zoom` 倍，
**放大步长只会让缓存仍保持在 1:1 以上，而不是变糊**
（例：zoom 0.7 时步长 0.2 仍留 `0.7/0.5 = 1.4×` 的过采样）。

**(b) 实施**：新增 `RWX_MAP_CELL_ZOOM_ENLARGE_STEP`
（`LayerBufferManager.ZOOM_ENLARGE_STEP`，未设时保持 vanilla 的 0.1），
并接入 harness `--zoom-enlarge-step`。已验证构建与门禁（默认路径未变，oracle 1537 项 0/768）。

**(c) 实测（同一场景，确定性计数器）**：

| 配置 | `reset` | `cell` | `zoom-enlarge` | 其他 |
|---|---|---|---|---|
| 步长 **0.10**（vanilla） | 71 | **4,094** | **22** | `pan-outside-grid` 23、`zoom-time` 24 |
| 步长 **0.25** | 69 | **4,182** | **22** | `pan-outside-grid` 24、`zoom-time` 21 |

**⇒ 把阈值放大 2.5 倍，`zoom-enlarge` 次数与格子重生成量基本不变（22→22、4094→4182）。**

**(d) 结论：这个阈值在当前构建下已不是限制因素。**
本轮的 `zoom-time`（第 74 轮改成默认的时间基准节拍）**已经主导了缩放期的刷新决策** ——
`ZoomCacheCadence.shouldRefresh` 自己按 50 ms 稳定窗口 + 时间阈值决定何时重铺，
**`zoom-enlarge` 只是并行的另一条触发路径，把它放宽并不会减少总重铺次数。**

**这也是一个必要的更正**：第 84 轮我把 `zoom-enlarge` 报为「63% 的失效来源」，
那个比例是**在当时的路径组合下**成立的；
**它并不等于「把这一项去掉就能省 63%」** ——
本轮证明两条触发路径会互相补位，**总重铺次数才是被约束的量**。

**(e) 因此靶子要重新表述**：不是「放松某个阈值」，
而是**降低「时间基准节拍的刷新频率」本身**（`ZoomCacheCadence.STABLE_NANOS = 50 ms`
与 `refreshFrames` 阈值），或**降低每次重铺的成本**。
前者有明确的保真度代价（格子更久不更新），**必须先有第 85 轮那个缩放新鲜度 oracle 才能安全改动**；
后者的方向已在第 83 轮确定（每格固定开销）。

**(f) 本轮产出**：一个**保留下来可复用的旋钮**（`--zoom-enlarge-step`，默认不变）
与一个**否定结论**（该阈值已非限制因素），后者把「放宽 zoom-enlarge」从候选里划掉，
避免后人重复尝试 —— 与第 82 轮「缩小格子」的否定结论同一性质。

---

**第 88 轮：直接测「把 renderScale 钉住、不再随 zoom 变」这个设想的收益 —— 结果是灾难性的，而且揭示了机制。**

**(a) 设想**：既然缩放期的重铺来自 `renderScale` 随 zoom 变化（第 87 轮确认节拍已主导），
那**把 renderScale 钉在一个固定值**（例如缩放区间上界 0.70）应当消除这部分重铺。
这一设想可以用**既有开关**近似验证，无需改代码：
`RWX_MAP_CELL_MIN_RENDER_SCALE=0.70` 会让 `computeRenderScale()`
在整个 0.35–0.70 区间内恒为 0.70。

**(b) 实测（同一场景）**：

| 指标 | 无下限 | **下限 0.70** |
|---|---|---|
| `reset` 总数 | 72 | **257** |
| `cell`（重生成格子） | 3,842 | **9,343** |
| 格子 CPU 合计 | 4,094 ms | **6,297 ms** |
| `zoom-enlarge` | 22 | **0** ✅ |
| `zoom-time` | 24 | **1** ✅ |
| **`pan-outside-grid`** | 24 | **254** ❌ |
| 交互帧率 | 131.9–136.2 /秒 | **16.3–21.1 /秒** |
| P99 | 39.7–41.5 ms | **670–693 ms** |

**(c) 结论：设想被否定，而且原因很有信息量。**
钉住 renderScale **确实**如愿消除了缩放驱动的重铺（`zoom-enlarge` 22→0、`zoom-time` 24→1），
但**平移驱动的重铺暴涨 10 倍**（`pan-outside-grid` 24→254），净效果是总重铺 72→257、
帧率崩到 16–21 /秒。

**机制**：`renderScale` 同时决定**格子的世界尺寸**（`cellWorldExtent = cellBufferPixelSize / renderScale`）
与**滚动步长**（`cellWorldStepSize`）。
把 renderScale 钉在 0.70，而实际 zoom 在 0.35–0.70 之间变化，
**棚格与屏幕的对应关系就与镜头缩放脱节** —— 平移时格子频繁「露出网格外」，于是不断整批重铺。

**(d) 因此得到一条重要的结构性约束（应写进设计文档）**：
**`renderScale` 必须跟随 zoom** —— 它不是「缓存分辨率」这一个角色，
而是**世界坐标与格子网格之间的换算系数**。
任何「把 renderScale 固定下来以减少重铺」的想法都会破坏这个换算，代价远大于收益。

**(e) 这条否定结论的价值**：它**同时**解释了为什么第 71 轮讨论过的
「按最大放大倍率设 renderScale 下限」也不可行 —— 那不是保真度问题，
而是**会破坏世界/网格换算**。此前我只把它当作保真度取舍，
**现在有了机制层面的否证。**

**⇒ 剩余方向进一步收窄为**：`renderScale` 必须随 zoom 变，
所以重铺是**结构性必需**的；能动的只有
**(i) 每次重铺的成本**（第 83 轮：每格固定开销主导），
或 **(ii) 单次重铺覆盖的格子数**（能否只重铺真正需要的那部分，而不是整批）。

---

**第 89 轮：找到 (ii) 的确切位置与一个具体的省法 —— `reset` 会无条件把整张网格标脏，但大部分格子其实还能用。**

**(a) 位置（`LayerBufferManager.updateGridParams:290–296`）**：

```java
for (int i = 0; i < this.gridCellsPerAxis; i++) {
    for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
        LayerBufferCell layerBufferCell = this.gridCells[i][i2];
        layerBufferCell.needsRedraw = true;      // ← 无条件
        layerBufferCell.preRendered = false;     // ← 无条件
    }
}
```

**每次 `reset` 都把 `gridCellsPerAxis²` 个格子全部标脏**，
这就是第 84 轮量到的「一次 reset ⇒ 一整批 ≈21 个格子重生成」的来源。

**(b) 为什么这里面有可省的部分**：`reset` 由 `renderScale` 变化触发时，
**格子的世界锚点与 `cellWorldExtent` 都会变**，所以确实不能直接沿用旧内容。
**但关键是格子纹理的物理尺寸没有变**（`cellBufferPixelSize` 固定 512），
变的是二者之间的**比例**。

当前的重铺算法（第 71 轮读到的）恰恰是在 512² 上按新比例重画 ——
**也就是说：格子仍然覆盖一片世界区域，只是「每世界单位的像素数」变了。**

**(c) 因此有一个具体的省法（待验证）**：
在 `renderScale` 只变化了 `renderScale` 阈值以内（PC 上 0.1）时，
**格子的旧像素内容仍然是「同一片世界区域、比例差 ≤10%」的表示** ——
可以先**只重算位置与采样比例**（把同一张 512² 纹理按新尺寸合成），
**只有超过这个容差的部分才真正重画**。

**与既有代码的关系**：`computeRenderScale()` 已经把 ≤1% 的差异归一到 1.0
（`if (Utility.abs(f2 - 1.0f) < 0.01f) f2 = 1.0f;`，`:1254–1257`），
说明「小比例差异不值得重铺」这个判断**代码里已经存在、只是容差只有 1%**；
把它用到 0.1 量级，就能把大部分 `zoom-enlarge` 重铺变成「只改位置」。

**(d) 必须先解决的前置条件**（这是本轮不给结论的原因）：
这个改法的判据**完全落在「画面是否过期」上** ——
「比例差 ≤10% 时用旧像素」是否可接受，**只能由像素级验证回答**。
所以它**正是第 85 轮那份 `runZoomFreshnessOracle` 方案的第一个使用者**：
先用 oracle 确认「按 1.1× 比例缩放复用旧格子」在容差内，
再谈把 `updateGridParams` 的整批标脏改成按容差分批。

**(e) 本轮产出**：(ii) 方向从「只重铺需要的部分」这个笼统说法，
落成**具体行号 + 具体容差 + 具体省法**，并指出它依赖第 85 轮的 oracle。
**仍未写代码** —— 这个改动的正确性判据是像素级的，而该 oracle 尚未建立；
在它之前动手，只能靠 `cell` 计数下降来判断，**而画面过期时计数也一定下降**。

---

**第 90 轮：用单元测试直接量了 (ii) 的上限 —— 我的假设被否证：每次 reset 所有格子的世界锚点都会移动。**

**(a) 找到了合适的验证载体**：`core/src/test/.../MapCacheExperimentalCellSizeTest.kt`
已经具备所需全部条件 —— `grid(size)` 构造 `LayerBufferManager` 并调用 `updateGridParams()`、
`makeClean(manager)` 清标记、`engine.zoom` 可控、
而且文件里已有一个**独立 oracle**（第 198 行注释原文：`Independent oracle: the changed tile
intersects pixels displayed from this cell`）。**这是纯单元测试，秒级、无需窗口。**

**(b) 新增测量用例**（`grid reset marks every cell and reports how many anchors actually moved`）：
对 3 种格子尺寸 × 4 组 zoom 变化，记录 `dirty`（被标脏的格子数）与
`anchorMoved`（世界锚点实际改变的格子数）。实测：

```
size=512 zoom=0.35->0.40  grid=36 dirty=36 anchorMoved=36
size=512 zoom=0.55->0.60  grid=36 dirty=36 anchorMoved=36
size=512 zoom=0.65->0.70  grid=36 dirty=36 anchorMoved=36
size=512 zoom=0.35->0.70  grid=36 dirty=36 anchorMoved=36
（384 与 256 同样：dirty == anchorMoved == grid）
```

**(c) 结论：假设被否证。** `anchorMoved` 与 `dirty` **完全相等** ——
`updateGridParams` 重算 `gridOriginWorld*` 并把视角取整到 20 世界单位（`:285–289`），
所以**每个格子的世界锚点都会移动**。
⇒ **第 89 轮设想的「按锚点是否移动来少标脏」在数据上不成立**：
没有哪些格子能因为「锚点没变」而被豁免。

**(d) 但这暴露了真正可省的东西（与 (c) 不同的一层）**：
格子被重铺的原因是 `renderScale` 变了 —— 而 `renderScale` 变，
**改的是「每世界单位的像素数」，不是「这片格子覆盖哪块地形」**。
所以可省的**不是「哪些格子要重画」，而是「重画时是否必须按新比例重新光栅化」**：

| 量 | 缩放 0.35→0.40 时 |
|---|---|
| `renderScale` | 0.35 → 0.40（**比例 1.14×**） |
| `cellWorldStepSize` | 1451 → 1270 世界单位 |

**⇒ 两者都在约 10–14% 的范围内变化**，正是 `f3 = 0.1` 容差所允许的步长。
若保留旧光栅化结果、**只按新比例重新合成**，
肉眼差异只是约 1.14× 的重采样模糊 —— **远小于缩略图级别的可见差异**。

**(e) 这是一个更深的改动，本轮不做，但决策依据已完整**：
第 89 轮设想的「按容差分批评脏」**已被本轮否证**（锚点全部移动），
可省的是**「复用旧像素 + 只改合成比例」**，它需要的是**合成阶段的比例重采样**，
而非标脏策略。前置条件仍是第 85 轮的像素 oracle。

**(f) 本轮产出**：一个**秒级单元测试测量**（可直接复用为后续改动的判据）、
以及一个**否证**（锚点策略不可行）。否定结论同样收窄了搜索空间，
而且这次是用**单元测试**而非 90 秒一次的回放得到的 ——
成本低两个数量级，值得作为以后此类问题的首选载体。

---

**第 91 轮：追查 `anchorMoved == dirty` 到底是「位移」还是「重排」—— 答案是重排，且位移量有闭式解。**

**(a) 为什么值得追**：如果所有格子只是**整体平移**了同一个量，
那么旧光栅化内容「仍然是同一片地形、只是偏了几像素」，
`anchorMoved` 就等于**假信号**，第 90 轮的否证也就不成立。
这个区分决定了整个方向是否还活着。

**(b) 实测（在同一个单元测试里记录每个格子锚点位移的集合）**：

```
size=512 zoom=0.35->0.40  dX=[80, -101, -282, -463, -644, -825]   step=1451->1270
size=512 zoom=0.55->0.60  dX=[40,  -37, -114, -191, -268, -345]   step=923->846
size=512 zoom=0.65->0.70  dX=[20,  -36,  -92, -148, -204, -260]   step=781->725
size=256 zoom=0.35->0.40  dX=[40,  -50, -140, -230, -320, -410, -500, -590, -680, -770]
```

**(c) 结论：是「重排」，不是「平移」** —— 位移量构成**等差数列**，
公差恰好等于 `stepBefore - stepAfter`（如 `1270 - 1451 = -181`，
相邻项差 `-101 - 80 = -181`；另一个例子 `630 - 720 = -90`，相邻项差也是 `-90`），
首项是原点变化量。

**⇒ 每个格子的世界锚点都按索引线性地移到了新位置** ——
不是整张网格平移，而是**整张网格按新步长重新铺开**。
因此**旧内容确实不再对应新位置**，`anchorMoved == dirty` 是**真信号**，
第 90 轮的否证成立，**「按锚点豁免」这条路确认不可行**。

**(d) 但闭式解本身有工程价值（记录备查）**：
```
Δ_i ≈ Δ_origin + i · (stepBefore - stepAfter)
```
即第 `i` 个格子的位移可**直接算出**，无需比对。
这意味着若要实现「复用旧内容 + 重新合成」，**不必做几何比对**，
按索引与新旧步长即可算出每个格子的新矩形 ——
省下的不是「判断哪些能复用」，而是**「复用时刻的重新光栅化」**，
与第 90 轮 (d) 的结论一致。

**(e) 三轮否证的合并结论（搜索空间现状）**：

| 方向 | 结论 | 轮次 |
|---|---|---|
| 缩小格子 | 交互吞吐崩 4.4 倍 | 82 |
| 放宽 `zoom-enlarge` 阈值 | 无效（节拍已主导） | 87 |
| 固定 `renderScale` | 平移重铺暴涨 10 倍 | 88 |
| **按锚点少标脏** | **不可行（网格按新步长重排）** | **90–91** |

**⇒ 剩下的只有一条**：**复用旧光栅化内容、只按新比例重新合成**。
它的正确性判据是像素级的（约 1.14× 重采样是否可接受），
**必须先有第 85 轮那份 `runZoomFreshnessOracle`**。
本轮再次确认了这一点，没有新的分支可走。

---

**第 92 轮：换了个算法角度 —— 瓶颈不只是「重铺多少次」，还有「每次多贵」；而迷雾让每次贵了一倍。**

**(a) 先做了一次必要的算术核对**（此前一直在追「次数」，没把两个因子合起来看）：

```
交互期格子重生成 ≈ 78 /秒 × 每次 1.25 ms ≈ 97 ms/秒 ≈ 墙钟的 10%
```

**⇒ 每格成本与重生成次数是同等重要的因子**，
而前面各轮几乎都在动「次数」。本轮转向「每格多贵」。

**(b) 实测（pan+zoom，同一回放，只改 `--fog`）**：

| | `reset` | `cell` | 格子 CPU 合计 | **平均每格 CPU** | 格子墙钟合计 | 交互帧率 | P99 |
|---|---|---|---|---|---|---|---|
| **fog on** | 70 | 4,044 | 3,094 ms | **765 µs** | **4,362 ms** | 142.4 /秒 | 33.9 ms |
| **fog off** | 71 | 2,633 | 1,312 ms | **498 µs** | 1,743 ms | **151.2 /秒** | **25.0 ms** |

**(c) 结论：迷雾让每格光栅化成本增加 54%（765 vs 498 µs），
整个运行的格子墙钟时间增加 2.5 倍（4,362 vs 1,743 ms）。**

按墙钟折算：**fog on 约 4.4 s / 90 s ≈ 4.9%，fog off 约 1.9%** ——
**迷雾一项就占掉约 3 个百分点的整机时间**，且交互帧率高 6%、**P99 低 26%**。

**机制**：`MapLayer.renderLayerRegion` 在每个**可见瓦片**之后都执行一段迷雾处理
（`:501` 起，`if (z && z7 && z2 && (b2 != 0 || bArr3[i6][i7] != 0 || bArr2[i6][i7] != 0))`），
其中还包含迷雾自己的行合并（`:503–512`）。
所以**迷雾的成本是「逐瓦片」的，而格子重铺会把整格瓦片全部重画一遍** ——
重铺越频繁，这笔逐瓦片成本被重复支付的次数越多。

**(d) 这是一个真实的、可选的杠杆，但本轮不据此改默认值**
（`--fog on` 是既定测量口径的一部分，关掉它等于换场景，不是优化）。
需要区分两件事：

1. **测量口径**：既定口径是 `--fog on`，所以「关雾更快」**不能**作为交付的提速；
2. **可优化点**：迷雾的**逐瓦片**成本是否可以降低 ——
   它是每格重铺时被重复支付的固定倍数成本，
   与第 90–91 轮否证的那些方向**不同层**（那些动的是「重铺哪些格子」，这个动的是「每格便宜多少」）。

**(e) 下一步的候选（未验证，按代价排序）**：
- **把迷雾烘进格子的正常绘制**（经典做法），而不是每个瓦片叠加一遍 ——
  改动面大，且**必须**有像素级判据（第 85 轮 oracle 的第二个使用者）；
- **降低迷雾行合并的判断频率**（`:501` 的守卫在每瓦片都求值）；
- 先**量化**迷雾在每格里占多少（用 `--cpu-target-profile` 或分阶段计时），
  再决定值不值得动 —— 本轮只确认了总量（约 3 个百分点），没确认内部分布。

**(f) 未验证的第一件事，已确认可做**：仓库里**从未**对「迷雾在格子内部占多少」做过剖析
（`docs/original-benchmark.md` 内检索 fog + 剖析无结果），
而 `map_pan_replay.py --cpu-target-profile` 这个现成开关就是为此准备的。
**所以下一轮的第一件事应当是用它把迷雾的格子内成本分解出来** ——
在没弄清「这 54% 花在采样、采样后的合成、还是每瓦片的守卫求值」之前，
不应动「把迷雾烘进格子」这种改动面大的方案。

**(g) 本轮的方法论收获**：前 20 多轮我几乎只在优化**「重铺多少次」**这一个因子，
而瓶颈是两个因子的积（次数 × 每次成本）。
本轮把注意力移到**「每次多贵」**上，立刻找到一个**此前从未量化**的大项（迷雾，约 3 个百分点）。
**⇒ 已记入：优化前先把成本写成「频率 × 单价」，并确认两个因子各自的量级。**

---

**第 93 轮：用已有的逐格字段把迷雾成本往下分解了一层 —— 差异集中在「平滑渐隐」路径上。**

**(a) 现成字段就能分解，无需新埋点**：`map-cache.csv` 的 `cell` 事件带 `smoothFade` 列，
统计每次运行的逐格取值：

| | `cell` 总数 | 平均每格 CPU | `smoothFade=1` 的格子数 |
|---|---|---|---|
| fog **on** | 4,044 | **765 µs** | **1,552（38%）** |
| fog **off** | 2,633 | **498 µs** | **11（0.4%）** |

**(b) 结论**：迷雾开关与「平滑渐隐路径被激活」几乎一一对应（1,552 vs 11），
而两者的每格成本差 54%。
**⇒ 那 3 个百分点的整机时间，主要落在「激活了平滑渐隐的格子」上**，
而不是均匀摊在所有格子上。

**这也解释了 `--fog off` 的收益为何是「每格更便宜 + 格子更少」两者兼有**：
`cell` 数少了 35%（4,044 → 2,633，因为不再需要为渐变重铺），
每格也便宜了 35%（765 → 498 µs）。

**(c) 一个必须写明的口径限制**（否则会误导下一步）：
这里的 `cpuNanos` 是**格子栅格化这段的 CPU 时间**，
而在默认的 `GPU_TARGET` 模式下，这段包含的是**命令录制 + 提交**，
**不一定包含 GPU 侧的像素填充**（第 70 轮确认格子走 GPU 离屏 pass）。
所以本轮的「765 vs 498 µs」应理解为**格子重建的 CPU 段成本**，
**不能**直接推断 GPU 侧也差同样的比例。
若下一步要判断该省 CPU（少录命令）还是省 GPU（少填充），
必须先确认这段里 GPU 等待与 CPU 录制的占比 —— 这正是 `--cpu-target-profile` 的用途。

**(d) 下一步（两种可能的修法，取决于 (c) 的答案）**：
- 若成本在 **CPU 录制**：`MapLayer` 的迷雾是**逐瓦片**叠加的
  （`:501` 的守卫 + `:503–512` 的行合并每瓦片都求值），
  可以降低其求值频率或把行合并的结果复用；
- 若成本在 **GPU 填充**：需要减少迷雾在格子内的绘制量（例如把迷雾并入地形绘制），
  改动面大且**必须**有像素级判据。

**⇒ 下一轮第一件事是回答 (c)**，而不是先选修法 ——
选错方向的代价远大于多花一轮测量。

---

**第 94 轮：把第 93 轮预留的岔路口回答了 —— 迷雾的格子成本约九成在 CPU 侧，不在 GPU 填充。**

**(a) 判据与数据**（每次 `cell` 事件都同时有 `cpuNanos` 与 `start/endNanos`，
两者的差就是这段里的等待）：

| | 每格 CPU | 每格墙钟 | 等待 | **CPU 占比** |
|---|---|---|---|---|
| fog **on** | 765 µs | 1,079 µs | 314 µs | **71%** |
| fog **off** | 498 µs | 662 µs | 164 µs | **75%** |

**(b) 把迷雾的增量拆开**：

```
每格 CPU 增量 ：765 - 498 = 267 µs
每格等待增量 ：314 - 164 = 150 µs
⇒ 增量里 64% 是 CPU、36% 是等待
⇒ 若把「等待」算作 GPU 侧，则 CPU 侧占总增量的 64%；
   但更贴切的读法是以 fog on 这一侧的比例看：该格的 765 µs 里 71% 是 CPU 工作。
```

**(c) 结论：这部分成本主要在 CPU 侧 ⇒ 应优化「录制」，而不是「填充」。**
按交互期 ≈78 格/秒折算，迷雾在 CPU 上约增加 `78 × 267 µs ≈ 21 ms/秒 ≈ 墙钟 2%`。

**这排除了第 93 轮列出的第二种修法**（把迷雾并入地形绘制以省 GPU 填充）：
**填充不是主要成本，改了也拿不到这 2%。**

**(d) 因此修法收窄到 CPU 录制侧**，与代码结构吻合 ——
`MapLayer.renderLayerRegion:501–512` 的迷雾处理是**逐瓦片**执行的，
而格子重铺会把整格瓦片重画一遍：
`78 格/秒 × 每格数千瓦片` 意味着那段守卫与行合并**每秒被求值数百万次**。
可动的方向（均属录制侧）：
- 缓存「上一瓦片的迷雾判定结果」，避免逐瓦片重复求值；
- 把 `:503–512` 的行合并结果在同一格内跨帧复用；
- 降低 `:501` 守卫的求值次数（它有三个布尔与两个数组读取）。

**(e) 本轮仍未写代码，理由是判据**：
上述三条都会改变「哪些瓦片叠加了迷雾」，
**正确性落在像素上** —— 需要第 85 轮那份 `runZoomFreshnessOracle`
（或一个针对迷雾的等价 oracle）。
**在没有它之前，这类改动的成功判据只有 CPU 时间下降，
而那正是「少做了本该做的事」也会给出的信号。**

**(f) 本轮的净产出**：一个**明确的岔路口答案**（CPU 侧而非 GPU 侧）、
一个**被排除的修法**、以及**三个具体的录制侧候选**，
全部记入文档供下一步直接执行。

**(c) 这个结论与全部既有观测一致**（互相独立地印证）：

| 观测 | 与 GPU 背压一致 |
|---|---|
| 1920×1080（2.25× 像素）⇒ P99 440–742 ms、10–16 /秒 | 像素多 ⇒ GPU 更慢 ⇒ 背压更重 ✓ |
| 1024×768（像素 −15%）⇒ 220 /s、P99 10.5 ms | 像素少 ⇒ 背压消失 ✓ |
| MSAA 2 比 MSAA 4 慢 3 倍 | 2× 路径驱动实现差 ⇒ GPU 更慢 ✓ |
| 命令量降 52% 只换 +12% | 瓶颈不在 CPU 侧命令处理 ✓ |
| 冻结降 89% 帧率不动 | 同上 ✓ |

**(d) 因此问题的定性已经完成**：
**RWX 在同样场景下比原版做了多得多的「每像素 GPU 工作」**，
导致 GPU 成为限制、交换链成为节流点。
原版在同一台机器、同一回放、同样逻辑窗口下能跑 228 /秒、尾延迟 16.88 ms，
**说明这不是硬件上限**。

**(e) 下一步的靶子因此变得具体**：找出并削减**每帧的每像素 GPU 工作**。
在动手之前，我会先建立**「同一画面、不同 GPU 工作量」的可比判据**
（例如固定机位、逐级降低某项 GPU 工作，看 P99 与帧率如何响应），
以便任何削减都能被判定，而不是又一次「改了但无法判定」。

**⇒ 下一步（转攻呈现层的正确方向）**：
不与游戏状态并行的重叠方式 —— 例如**加深帧排队**（submit 后不等待、多帧在飞），
或调整**交换链图像数/呈现模式**。这类改动只影响排队深度，**不引入第二个线程碰状态**。

**第 33 轮：把诊断脚手架从热路径上摘下来，并收束状态。**

这 30 多轮为定位问题加的诊断里，有几项在**默认运行**下也会付费：

| 诊断 | 原来 | 现在 |
|---|---|---|
| `put` 的 `putCalls` / `PUT_CALLS` 计数 + 每 2 万次 `println` | 每次注册都跑 | 仅在 `RWX_SOURCE_CHURN_TRACE=1` 时 |
| `gpuTargetModality` / `gpuTargetModeTotals` / `gpuTargetShapeKeys`（含拼 `"WxH/MODE"` 字符串） | 每次 target 提交都跑 | 仅在 canvas trace 开启时 |
| `putSameHits` | 每次 | 仅在 `RWX_SOURCE_CHURN_TRACE=1` 时 |

其余诊断（`freeze-*`、`loop-*`、`map-tile-*`、`cell-content-cadence`、`cell-store-instances`、
`churn47`、`sourceReuse`、`vMismatch` 采样）**本来就都由 `CanvasRenderStageTrace.enabled`
或各自的 `RWX_*_TRACE` 开关控制**，已验证在普通运行下**输出为 0 行**。

**⇒ 现在默认运行的路径上没有为诊断付出的额外工作。**

### 6.2 可比配置的判定（重要）

原版没有"雾开关"设置键（`preferences.ini` 仅有 `softFogFading`），其雾由**回放记录**决定；原版日志每次运行都含
`Precalculating map fog → smoothFog load → Setting up team fog.. → Building smoothFog_cache` ⇒ **回放是开雾的**，
故与原版可比的是 RWX 的 `--fog on`（原先用 `--fog off` 比开雾回放是错的）。

