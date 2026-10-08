# 当前优化与验收状态（2026-10-08）

目标尚未达成。不能用平均帧率或交互/静止退化比代替验收，也不能把诊断运行计入正式对照。

## 最新进展（本轮仍在实施）

- 已修复冻结复用缓存的两处治本问题：缓存命令复用时恢复完整传递资源闭包；依赖记录从当前目标起算，并让引用 memo 命中也贡献后代依赖，去重后不携带无关前序兄弟。循环／缺失闭包不复用，所有后代源版本仍逐项核验。`RWX_FROZEN_CONTENT_REUSE` 仍默认关闭。191 项画布测试在开关打开时全部通过，实际 MapLayer 3841／迷雾 4608／字体 3072 像素 GPU 检查通过（`cure-frozen-closure-gpu-gates.log`）。
- `cure-live-frozen-closure-20261008a-1`：12 次捕获、112 格／29360128 格子像素与 33177600 合成对照像素零差异；完整资源恢复、共享叶子先解析、后代更新、旧 lease、延迟出现和循环引用单测通过。独立 A/B/B/A `cure-frozen-closure-abba-20261008a`：新画面中位数 169.07 → 195.56／秒，P99 23.70 → 18.06 ms，P99.9 90.27 → 59.87 ms，>16.667 ms 事件率 3.8625 → 2.475／秒；最大 635.68 → 320.05 ms，未达标。
- 三项冻结工具、1000 MiB 堆独立四窗口筛选已完成：地图批量中位 172.93 → 204.20／秒、P99 22.20 → 15.48 ms、长帧率 3.35 → 1.60／秒，峰值工作集 3604.4 → 1683.3 MiB，但仍有 597.02 ms 事件；材质复用中位 159.57 → 165.55／秒、峰值工作集基本不变；紧凑固定网格 164.99 → 170.34／秒、工作集基本不变，最大 576.89 ms。全部长帧保留；汇总 `build/cure-independent-candidate-summary-20261008a.json`，均非原版对照／最终验收。
- `cure-live-material-source-ref-20261008a-1` 纯材质复用实景检查完成：12 次／82 格／21495808 格子像素与 33177600 合成对照像素零差异。`cure-live-closure-main-combo-20261008a-1` 冻结修复、地图／迷雾批量、单次格子绘制、精灵增量／投影、文字标签及材质、渲染短等待组合实景检查也通过。两者均诊断堆 2000 MiB，不计入正式性能。
- 新一轮同口径原版逐模式初筛 `cure-closure-main-four-modes-pair-20261008a` 仍未合格，只有 1 对。静止原版 299.90／秒，RWX 引擎产出 296.98、渲染 298.04、新画面 283.31／秒，重复率 4.94%，40 秒内无 >16.667 ms；拖动原版约 299.77，RWX 新画面 282.29，40 秒内也无超限；缩放原版 298.25，RWX 267.82、最大 61.46 ms／30 次超限。不能用两个短窗口零超限代替十分钟验收。
- 默认关闭的 `RWX_STABLE_FRAME_DEADLINES` 已编译并通过相关桌面测试：小幅唤醒漂移沿原计划时刻推进，错过半个间隔或刷新率变化时重新起算；owner 的模拟 delta 仍来自实际墙钟。独立静止 A/B/B/A `cure-stable-deadlines-static-abba-20261008a` 新画面中位数 282.17 → 283.63／秒，重复画面仍约 5%，不能视为已解决。
- 新增默认关闭 `RWX_WAIT_FOR_FRESH_FRAME`：渲染等待后、场景收集前读取已发布相机的代数／序号，最多等待 1.5 ms；不跨线程调用引擎、不改变模拟时钟。相关编译与桌面测试通过。`cure-fresh-frame-wait-static-abba-20261008a` 1000 MiB 堆、冻结工具的四轮静止初筛全部有效：新画面中位数 283.53 → 298.41／秒，P99 7.11 → 4.89 ms，P99.9 9.10 → 8.01 ms，最大 14.31 → 11.82 ms，四轮 40 秒窗口零 >16.667 ms；重复率约 5.2% → 0.28%。仍略低于原版约 299.9，输入响应及其他模式尚未通过。
- 上述逐模式初筛组合完整数据：原版组合约 298.45／秒，RWX 270.43、P99 8.58 ms、最大 60.07 ms、29 次 >16.667 ms。所有四模式性能 gate 均未通过，不能默认启用候选。
- 新增 `pinned_input_comparison.py`，冻结工具、运行 jar 与回放，独立比较原版与 RWX 正常回调输入路径，显式保留物理 OS 输入尚未核验的边界。`cure-fresh-frame-input-pair-20261009a` 两端均 150 次输入／70 次相机响应，trace 有效，但未通过：拖动 P99 原版 4.30／RWX 11.48 ms，方向键 13.84／14.83 ms，滚轮 7.76／11.68 ms。部分发布至呈现等待约 7–11 ms，不能用新画面吞吐改善代替输入 gate。
- 默认关闭 `RWX_POST_INPUT_FRAME_WAIT` 已编译：前置 fresh wait 在此候选下关闭，`currentFrame()` 在正常输入转发后短等最多 1.5 ms，条件包含输入已由 owner 采用且已随帧发布；票据处理保留 FIFO 转换，乱序 ticket 不能提前确认缺口。2 项票据回归与 owner／刷新率／绘制队列合计 13 项测试通过（`cure-post-input-stage-gates-build.log`）。冻结原版/RWX `cure-post-input-frame-pair-20261008a` 全部 trace 有效，但仍未通过：拖动 P99 4.17／15.42 ms（RWX 最大 17.95）、键盘 13.95／14.49 ms、滚轮 9.32／9.12 ms。纯短等没有消除拖动尾部，仍默认关闭；RWX 慢拖动的 owner 采用约 0.1 ms、采用至发布约 1.6–1.8 ms，发布至呈现约 13.7–16 ms，应继续查渲染段。
- `cure-fresh-frame-zoom-deep-20261008a/run` 是独立深诊断：284.14 新画面／秒，P99 7.68 ms、P99.9 21.40 ms、最大 59.35 ms、19 次 >16.667 ms。已按多点 JFR 时钟与改名后的 backend 线程归因，最大事件没有长 fence／acquire／present；仍含未细分的 CPU 阶段。新增 prepare-pipelines／execute-passes／backend-frame 和 native graphics/compute pipeline create 的阶段时间，编译完成，下一轮诊断需复测，不能把未知事件归咎外部负载。
- `frozen_replay_run.py` 也冻结单次诊断／像素检查的工具、jar 和回放。JFR 导出入口修正为读取冻结工具目录中的 `CompactJfr.java`，此前 live exporter 路径证据保留。
- 一代旧缓存组合真实欧洲图 `cure-live-zoom-generation-fresh-20261008a/run`：12 次捕获、86 格／22544384 格子像素与 33177600 合成对照像素零差异；该运行 2000 MiB 诊断堆、包含回读，不计入性能，仍需原版／十分钟／正常游戏和死亡视野验证。
- 默认关闭 `RWX_SHARE_MAP_TEXTURE_MATERIALS` 已编译：一个地图 target manager 的 slots 共享不可变 instanced 材质，保持独立 mesh/view 数据；键使用实际 Texture2d、blend mode、预乘与 RGB 乘 alpha 语义。每个 renderer 的本地缓存持有引用，纹理替换创建新材质，旧绑定与 native pipeline 继续由 mesh ownership／GPU fence 保留。跨 renderer 共用、独立换代、最后引用退休回归与全部 192 项画布测试通过（`cure-shared-map-material-gates-build2.log`）；首次编译缺失 private blend enum 的失败日志保留，已改为真实管线 blend key。独立实景 `cure-live-shared-map-material-20261008a/run` 12 次捕获、109 格／28573696 格子像素与 33177600 合成对照像素均零差异。正在执行独立纯缩放 A/B/B/A `cure-shared-map-material-zoom-abba-20261008a`，1000 MiB 堆、工具／jar／回放冻结，旧代缩放候选不混入。

- 新增 `RWX_REUSE_INSTANCED_TEXTURE_SHADERS` 默认关闭候选：按真实 Texture2d 对象、混合方式及预乘语义共享不可变材质。纹理换代切换 shader，禁止修改旧共享纹理绑定；各网格的矩阵、实例和绘制组独立。跨排序屏障的两个同材质网格、另一纹理、换代后旧绑定保留单测通过（`cure-material-order-gates.log`）。与紧凑网格、单次绘制组合的真实 MapLayer／迷雾／字体 GPU 像素检查通过（`cure-material-quad-pixel-oracle.log`），尚未作为独立性能证据。
- `cure-live-material-20261008a-1` 正确性诊断捕获前五帧零格子差异，但直接内存达到 1000 MiB 上限并崩溃；保留日志，不计通过。独立格子参考原先每次也创建整页图集，现改用源纹理、零网格保留期。数据型图集在 fence 退休后清空 uploadData，避免冷纹理被材质引用时仍持有整页缓冲；新增回归证明 fence 前不解绑、完成后才释放（`cure-atlas-retirement-gates.log`）。新一轮纯材质候选实景正确性使用 2000 MiB 诊断堆，不能纳入 1000 MiB 性能对照。
- 紧凑网格与字体键已编译；字体家族／样式／派生对象身份回归及 paint / freeze 回归通过（`cure-native-memory-candidates-gates.log`）。仍须分别筛选。
- 冻结工具后的 `cure-freeze-share-abba-20261008a` 四窗口完整：中位新画面 168.10 → 172.87／秒，P99 24.46 → 22.92 ms，P99.9 107.36 → 63.57 ms；>16.667 ms 事件率 4.0125 → 4.0375／秒、最大 435.12 → 557.49 ms，不能据此启用或宣布达标。
- 原版探针的 ASM 类也改为固定 zip 时间戳；`cure-probe-deterministic2-20261008a/summary.json` 两次打包 SHA 相同。此前第一轮探针可复现检查失败，历史保留。

- 最终合成 `cure-final-screen-selected-20261008a-1` 发现地图黑块；候选与控制组 33177600 像素一致不能证明共享参考路径正确。新增 `--real-scene-cell-oracle`，逐个读取真实地图附件并用相同冻结命令独立重绘。`cure-real-live-cells-20261008a-1`、`cure-live-cell-collection2-20261008a-1` 均发现几乎整格缺失，不能通过正确性验收。
- `cure-live-cell-draw-20261008a-1` 完成 12 次捕获，同时出现实际附件缺失与独立参考格子缺失。全部 25763 次进入 Vulkan bind 的命令均绑定成功，变换数据正常，但部分地形绘制组没有进入 bind。查出 Kool DrawQueue 删除空组后未重置 `prevQueue`，返回组可把命令加入已脱离 `orderedQueues` 的容器。真实 DrawQueue / DrawCommand 回归在修复前失败（`cure-queue-repro2.log`），已加入版本核验的依赖覆盖补丁，修复后单测及实际地图检查正在进行。不能再将这些漏画归因于冷网格未就绪。
- 分配采样显示画笔快照与非纹理冻结命令大量短命分配。新增 `RWX_REUSE_PAINT_SNAPSHOTS`（逐项核对归一化旧接口取值，不能仅信任 revision）和 `RWX_SHARE_IMMUTABLE_COMMANDS`（仅共享冻结无需修改的不可变命令）。两项回归通过：直接字段/覆盖 getter 的变化不污染旧帧，冻结容器脱离原可变列表并保持只读。候选均默认关闭，独立性能筛选尚未完成。
- 修复 `map_pan_replay.py` 默认 GPU 地图目标的激活校验：未指定已废弃的 `--gpu-map-cell-cache` 不能当成 GPU 关闭对照；实际关闭条件是 `--cpu-cell-raster`。此前因该口径错误标记无效的运行保留，不能重写历史报告。
- 队列修复后的 `cure-live-cell-queue-fixed-20261008a-1`：12 次捕获，84 个实际地图格子／22020096 像素、候选画面 33177600 像素均零差异；25366 个实际绑定命令全部成功，按 Kool 收集后先递增时钟再执行的口径对照，收集组零丢失。最大长帧仍未合格。新增默认关闭的 `RWX_SINGLE_MAP_CELL_RENDER` 去掉新网格四次绘制补偿，独立真实 MapLayer／迷雾／字体 GPU 检查已通过（`cure-single-cell-pixel-oracle.log`）；欧洲地图全格验证正在进行。
- 单次格子绘制欧洲地图 `cure-live-cell-single-20261008a-1`：12 次捕获，82 个实际格子／21495808 像素与候选画面 33177600 像素均零差异。早期默认路径 A/B/B/A 未证明稳定收益，仍关闭。默认未批量路径工作集约 3.5 GB、可用物理内存最低约 0.5 GB；须继续查 native／direct／GPU 与网格存储，不能只调整 JVM 堆或归咎系统负载。
- 新增默认关闭的紧凑固定网格 `RWX_COMPACT_QUAD_STORAGE`（四顶点／六索引预分配 8 项，旧默认 1024）与不可变字体键 `RWX_REUSE_TYPEFACE_KEYS` 候选，尚未编译／验证。字体对象只保留既有工厂 API 和缓存语义。
- 新测量入口冻结全部 Python 工具及原版 Java 探针源码，保存 tooling manifest／SHA，子进程从快照加载工具。整组比较拒绝脚本指纹缺失或变化；原版探针打包时间固定，使相同源码产物可复核。早期 `cure-single-cell-abba-20261008a` 与 `cure-paint-reuse-abba-20261008a` 期间工具仍被修改，只作调查线索，不得作为冻结工具后的正式对照。全部历史窗口与长帧原样保留。

- 新增增量精灵图集 `RWX_INCREMENTAL_SPRITE_ATLAS`，只上传新占用区域；保留原有 texel，使用同队列屏障、帧 staging 和 fence 退休。独立 GPU 检查 9216 像素／12 个版本，实际追加 5816 字节。组合诊断中 203 个新增精灵只产生 315 次小区域上传，初始完整图集上传仅两次。
- 新增嵌套世界精灵批量路径 `RWX_PROJECTED_SPRITE_ATLAS`。旋转裁剪曾出现 21 像素差异，已统一为原路径先裁剪目的矩形及 UV 再变换的语义。
- 新增整段文字实例 `RWX_INSTANCED_TEXT_LABELS`，不可变浮点几何纹理按代退休；保留字形、字体、混合和裁剪。修正 Vulkan sampled image 屏障仅覆盖片段着色器的问题，加入顶点着色器阶段。
- 真实欧洲回放诊断 `cure-live-isolate-20261008a-1` 查出文字网格复用层级错误：静态实例顶点一直非空，不能用 `geometry.isEmpty()` 判断首次使用。精确命中与重用两个入口均改用空实例列表判断并更新绘制组；新增跨帧背景层级变化的 GPU 回归。`cure-text-order-gates3.log` 的 9216 像素／12 版本通过。
- `cure-live-order-fixed-20261008a-1` 在同一冻结快照上分别比较精灵、文字及组合：12 个 1280×720 真实画面，共 33177600 像素，全部一致。这仅证明候选与既有渲染一致；仍需独立检查参考路径本身的黑条、缺块、迷雾和死亡视野。该运行包含回读，最大长帧 563.81 ms，不能作为性能证据。
- 正常输入记录补充 `expectsCamera`、后续 `camera-effect` 与超时事件，避免仅记录首个确认画面掩盖滚轮晚生效。滚轮必须改变 zoom，拖动和方向键必须改变 XY；比较器逐类比较实际响应尾部。新工具五项测试已通过，原版与 RWX 新格式实测仍待重跑。
- `cure-combo-deep-20261008a-1` 是诊断数据：231.41 新画面／秒、P99 9.31 ms、最大 68.32 ms，未达标。短等待总体多耗中位数 0.677 ms，最大 61.30 ms（最大等待发生在预热）；正式窗口内也有请求 1.616 ms 实际 17.777 ms 的等待。`RWX_GUARD_SHORT_RENDER_PARK` 独立候选正在 A/B/B/A 验证，300 上限不变。
- 长帧归因工具曾只用第一个 JFR 时钟偏移；本机约 50 秒可漂移 216 ms，导致归因错误。已复用现有 `ClockMap` 多点校准，分别映射事件起止并补回归测试；旧单点归因不能作证据。
- 测量前清理本任务自己的空闲 Gradle/Kotlin 构建进程，释放约 3 GB，避免本任务构建后台污染。不得停止其他用户进程。正式运行不并行构建或 GPU 正确性回读。
- 以上候选仍默认关闭，未达到整体验收不得宣布完成或根据单次帧率默认启用。
- 渲染短等待 A/B/B/A：`cure-render-guard-ab-20261008a-*`，对照 230.74／230.22 新画面每秒，候选 270.17／271.62；候选 P99 中位数 8.32 ms，对照约 9.09 ms，但最大间隔仍不合格。owner 短等待 A/B/B/A（`cure-owner-guard-ab-20261008a-*`）波动大，未证明稳定收益。
- 正常输入两端均证实第一次拖动位移只激活拖动并重设锚点（GameUI），反向位移才推动相机。探针将前者显式记为 `pointer/activation`，保留确认延迟，并拒绝把实际发生位移的事件豁免。新分析六项测试通过；旧第一次拖动超时记录保留，不能伪造后续输入的响应归因。
- `cure-live-fog-20261008a-1` 诊断探针在回读后继续绘制已释放租约的旧节点而崩溃，属于测试生命周期错误。已在每次回读后先禁用离屏通道，再 fence 退休；`cure-live-fog-fixed-20261008a-1` 12 次对照／33177600 像素通过。仍未覆盖死亡视野和正常局全部边缘。
- 原生窗口截图对嵌入 Vulkan 画面返回黑色，不能当作缺块证据；已结束本任务的截图 JS 会话，改用 Kool 自有 ScreenPass copy 读取实际可见的最后场景。已得到上述真实黑块证据。
- 发现 RWX 工具原先固定 `-Xmx2g`，原版工具为 `-Xmx1000M`；RWX 进程一轮可增长到约 1.8 GB，当前可用物理内存会降到约 1.2 GB。新增 `--heap-mb`，测量默认 1000 MiB 对齐原版，并纳入配置指纹；产品启动选项未改。旧 2 GiB 数据保留，后续必须在统一堆限制下重新对照。
- 轻量帧窗口指标文件改为持久缓冲写入并在关闭时完整刷新，消除渲染线程每五秒打开文件与强制刷写；帧计数测试通过。这是诊断开销修正，不能单独声称产品加速。

## 固定测量

- 原版目录：`C:\Users\daerh\Downloads\Rusted Warfare1.15电脑版\Rusted Warfare`。
- `game-lib.jar` SHA-256：`8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`。
- 当前机器 WTY-THINKPAD，Intel Iris Xe；测试物理窗口 1280×720。RWX 基准 JVM 固定 uiScale=1，产品 DPI 设置保持不变。
- 固定四种模式 static / pan / zoom / pan-zoom，20 秒双轴往返路径，4 秒 0.35–1.5 缩放周期。固定运行文件、回放、配置与探针指纹，每次新目录。
- 原版回放观察者没有迷雾数据，原版/RWX 对照必须迷雾关闭。迷雾开启的压力运行单独记录；正常游戏有迷雾场景尚待同口径对照。
- 原版呈现记录在 Display.update(boolean) 成功返回后；RWX 在成功提交呈现且采用新快照时记录。两者均不等同于显示器扫描完成。
- 原版入口已改为 Root.loadReplay 单次载入，移除重复载入与六秒等待。原版/RWX 都要记录并核对模拟 tick 与游戏时间区间。

入口 `desktop/tools/pinned_interaction_comparison.py`（`interaction_ratio.py` 为兼容入口）。默认七组交错对照，保留全部窗口和每个 >16.667 ms 事件。禁止负载校正、剔除启动后的测量事件或重用历史输出。

## 已实施

- GPU 地图目标、BGRA、原有时间基准缩放缓存保持原有默认。已修复测试工具强制关闭 BGRA 的问题。
- 紧凑瓦片/矩形数值批次，发布后只读并复用录制缓冲；逐瓦片矩形、顺序、裁剪与混合保持原语义。`RWX_MAP_TEXTURE_BATCHES=1` 为独立候选。
- 迷雾黑色矩形与纹理遮罩按原顺序混合批次，保留精确 alpha 与 UV。`RWX_MAP_FOG_BATCHES=1` 为独立候选。
- 修正原有实例纹理快路径误接收黑色/透明 paint 的画面错误；快路径必须白色满 alpha。
- 字体本地模板、实例字形、同字体材质管线复用是独立关闭的候选。对应 `RWX_TEXT_GEOMETRY_TEMPLATES`、`RWX_INSTANCED_TEXT_GLYPHS`、`RWX_TEXT_SHADER_REUSE`。
- 一代旧网格按原世界范围重采样。尺度比限制 1.15，目标稳定 50 ms 后精确刷新，旧内容覆盖区域按 2 ms 录制预算分摊，250 ms 后不得继续延后。缺失、失效区域立即重画。`RWX_MAP_ZOOM_GENERATION=1` 为关闭的候选。
- 实际格子测试发现旧缓存全屏底图在完全重建帧多画地图边缘一列，已改为仅采样尚未重建的格子目的矩形。新方案仍须通过旧代实际 GPU 合成检查。
- 普通 Session 输入路径已记录采样、owner 采用、对应相机画面的呈现延迟；这还不是物理设备/OS 输入延迟的完整验证。
- 每次长帧关联 owner、录制/冻结、几何、Vulkan 原生阶段、JFR。fence 等待记录其实际等待的提交序号；GPU 查询可能属于较早提交，不能误归因。

## 当前证据及限制

`build/cure-fog-gates4.log`：实际 MapLayer/目标最终合成 3841 像素检查、混合迷雾 4608 像素检查通过。
`build/cure-shader-gates.log`：共享字体管线 3072 像素检查通过，另含上述地图与迷雾检查。
`build/cure-zoom-gates.log`：旧网格元数据、覆盖失效与 50 ms 稳定时钟四项测试通过。

四次 A/B/B/A 短时迷雾压力对照：控制新画面帧率中位数约 187.82，迷雾批次候选约 207.22；P99 窗口中位数约 18.25 ms → 11.73 ms。仍有百毫秒长帧；仅是候选筛选证据。其第三次运行曾有额外诊断 JVM 负载，保留全部事件，不得当正式验收。

深诊断 `cure-fog-deep-20261008a-1` 最长间隔 209.32 ms，其中 fence 等待 203.06 ms；owner 最大工作约 12.54 ms，无对应地图重载。必须继续查明等待提交的执行/驱动/资源原因，不能直接说是地图或外部系统抢占。

原版最新几何修正后短时运行约 299.53 新画面/秒，P99 约 5.00 ms、最大 11.49 ms，没有 >16.667 ms；当时尚未新增模拟区间证据，因此不是最终配对结果。

历史 `original-benchmark.md` 与 `gpu-map-cell-targets.md` 中旧退化比、逻辑尺寸、负载校正及“已排除文字 CPU”等结论只作调查线索，不能用作当前证明。每格光栅与屏幕采样比例由 zoom/renderScale 决定；旧“低 zoom 必然超采样”推论不成立。

## 剩余验收

1. 新旧地图网格、平滑迷雾、观察队伍/死亡视野、真实欧洲地图与边缘长期画面正确性。
2. 候选独立测量，然后组合测量；只有通过后才默认启用，保留开关。
3. 至少七组交错原版对照，四模式新画面 fps 中位数不低于原版，P99 和长帧率不高于原版。
4. 每种交互模式至少连续十分钟；验收窗口不能有任何 >16.667 ms 新画面间隔。记录 P99/P99.9/最大和 >16.667/33.333/50/100 ms 全部事件。
5. 原版正常输入路径对照与 OS 输入采样范围；长期内存、资源退休、在途帧租约/fence 验证。

正确性入口：`runRealMapCellOracle`、`runMapGridOracle`、`runTextGlyphOracle`、`runBgraSamplingOracle` 及相关 core 测试。正确性读回与深入诊断不得与正式性能测量同时运行。
既有 `ReplayTimelineIndexTest` 在仓库缺少完成回放样本时失败，已有证据；不得用修改模拟或回放格式掩盖。

- 最新工具口径修正：map_pan_live.py 曾在报告 GPU 默认开启的同时强制 CPU，现改为仅 --cpu-cell-raster 关闭 GPU；local comparison 默认双方 GPU，candidate-only GPU 比较显式给 baseline CPU 开关。live/replay 的禁用 GPU pass reuse 仅拒绝显式 CPU。22 项 Python 相机／协议／实际计数测试通过，旧结果不重写。cure-zoom-generation-fresh-abba-20261008a 正在独立筛选一代旧缓存，公共 11 项配置不含材质共享／post-input wait。

- 旧代缓存独立纯缩放 cure-zoom-generation-fresh-abba-20261008a 四臂有效但退化：新画面中位 280.9736 → 271.6273／秒、P99 7.9040 → 13.0938 ms、P99.9 21.4196 → 25.4728 ms、>16.667 ms 事件率 0.6875 → 1.125／秒，最大 69.3499 → 70.8262 ms。继续 OFF。
- 新 backend 分段深诊断 cure-zoom-backend-stages-deep-20261008a/run：279.129 新画面／秒，P99 8.5065 ms、P99.9 20.9717 ms、最大 64.8701 ms，共 29 个 >16.667 ms 事件。native pipeline create 正式窗口没有独自解释全部长尾；缩放重建还有 KSL 元数据遍历／native 分配，最大事件叠加约 13.011 ms GC。全部事件的归因保存在 fresh-stall-attribution.json，未知间隙尚未归因外部。
- 新默认 OFF 的 RWX_CACHE_CANVAS_SHADER_TEMPLATES：仅复用不可变 CPU shader code／顶点布局／绑定布局，每个材质仍创建独立 DrawPipeline、独立 texture/MVP binding 存储并沿原 mesh/fence 退休。按 backend identity、shader kind、alpha语义、config及精确 buffer layout 分键，64 项上限；无 native handle 共享。独立绑定／旧 pipeline 退休／alpha 分歧／原 factory control 回归及193项画布测试通过。cure-shader-template-pixel-gates2.log 实际地图3841、迷雾4608、字体3072像素通过；第一次 GPU task 名误用导致选择失败，日志保留。
- cure-live-shader-templates-20261008a/run：12 次／99格／25952256格子像素与33177600合成像素零差异；control和reference cell创建shader时明确关闭template，候选创建4模板、复用2098次。仍为2000MiB诊断，不计正式吞吐；正在跑1000MiB冻结四窗口 cure-shader-templates-zoom-abba-20261008a。
- 新诊断导出保留embedded map stream位置及CREATE_NEW写出，不改回放：5项ReplayPanBenchmark测试通过；cure-europe-embedded-map-export-20261008a/run 导出2836022字节地图，SHA256 a5e811bacbb522409155f11483962360c27160b9fbf079e07a8060ebdc37b848。该2秒窗口因不足两次GPU计数采样整体测量无效，只有独立map export指纹确认。新 frozen_live_run.py 与 map-file资产隔离（2项Python回归通过）正补欧洲图正常游戏入口，未完成正常游戏/fog/death gates。

- lazy shader V2 cure-lazy-shader-templates-zoom-abba-20261008a: all four arms valid; fresh median 286.7889 -> 291.7621/s; P99 7.0112 -> 6.3191 ms; P99.9 18.9704 -> 15.9675; >16.667/s .4375 -> .325; max 49.9436 -> 34.7884. Still below original, OFF. V1 old ABBA includes unlocated 1086.833 ms event, retained, not external-load attribution.
- normal Europe fog-on cure-europe-normal-fog-shader-templates-20261008a/run: retained native map units and AI, moving and combat observed; 12 captures / 100 cells / 26214400 cell pixels / 33177600 composite pixels zero mismatch. Diagnostic heap 2000, not formal performance. No edge/death pass yet.
- lazy shader deep cure-lazy-shader-templates-zoom-deep-20261008a/run: P99 7.6611 ms, P99.9 19.1777, max 57.0948; several events coincide with 7-15 ms GC. JFR sampled allocation weight identifies repeated font derive, text dimensions, template and mesh keys, plus owner paint/commands. Allocation weights are samples, not a byte census; native VMA/zeroing also remains.
- prepared text OFF candidate: private bounded derived-font views, detached mutable lookup probe, cached library metrics, one template/slice preparation per label. 196 canvas tests + real Vulkan map/fog/text and 9216 whole-label pixel checks pass. cure-prepared-text-zoom-abba-20261008a all valid, median289.2796 ->289.8492/s, P99 6.7395 ->6.7885, P99.9 16.9334 ->16.3010, events/s .35 ->.30, max42.2261 ->45.1417; no stable win, remains OFF.
- cure-europe-normal-nofog-prepared-text-20261008a failed CLI selection before launch, retained; b real scene pixels pass but whole case INVALID because native map fog remained on. Do not call this a fog-off pass. Diagnostic local-map room fog application is being corrected before warmup; replay behavior untouched.
- new OFF text-mesh-key candidate retains detached canonical keys in mesh maps/used sets, only reuses an unstored lookup probe; native mesh/order/fence ownership unchanged. New edge-jump and player-death diagnostic paths being built; no coverage claim until actual gates pass.

- New native Europe visibility/edge gate cure-europe-native-death-edges-20261008a/run PASSED (diagnostic only): ordinary map/AI retained, native fog ON; markForDeath of 48 local units at20s -> native wiped-out and zero hidden fog cells, both states captured. 12 captures show all4 true corners; 72cells/18874368 cellpixels +33177600 compositepixels zero mismatches. Both engine windows sampledEdgeMask15; no observer-state forgery. Owner publishes detached visibility snapshots; renderer never reads live fog/units.
- Fixed diagnostic local-map fog before warmup by applying existing singleplayer fog rules and recording actual room/native flags. cure-europe-normal-nofog-text-keys-20261008a/run PASSED: ordinary map/AI, injectedUnitFixturefalse, roomFogMode0, mapFogEnabledfalse, maintenancefalse; 12 captures/103cells/27000832cellpixels +33177600 compositepixels zero mismatches. Historical failed nofogb remains invalid.
- text key independent cure-text-mesh-keys-zoom-abba-20261008a valid: median289.3879->290.5271/s,P996.8572->6.6085ms,P99.9 16.6865->16.3596,>.3125->.3000/s,max34.4959->62.603ms. Max remains unlocated; no overall win/acceptance, OFF. Extra order/clip/HUD/material differential test8PASS; benchmarkOptions7PASS; actual map/fog/font/whole-label tests PASS.
- Texture paint no-op copy candidate OFF: preserve same immutable paint only if effective team effect unchanged; all values/old commands unchanged, effect replacement still detached. Canvas tests +Vulkanmap/fog/font/label gates build successful; not yet screened.
- Direct GPU-target texture candidate OFF: source sampling bypasses per-slot sprite atlas. IMPORTANT: existing numeric DrawTextureBatch already samples sources directly; candidate affects remaining ordinary offscreen draws/atlas traversal only, benefit is unproven. Europe fogON+panzoom 12captures real-scene/cell pixels pass; independent zoom ABBA running.
- New frozen reference metadata candidate being implemented: bounded one reference/identity entry perSource, used only AFTER resource closure/version/dependency restoration; no pixel/native/lease sharing, missing/cycle paths still traverse. Not built or qualified yet.

- Latest frozen-reference candidate gates PASS: full canvas/resource-closure regressions and Vulkan map/fog/font pass. Normal Europe fogON cure-europe-frozen-reference-20261008a/run PASSED, 12captures/82cells/21495808cellpixels +33177600compositepixels zero differences. Independent cure-frozen-references-zoom-abba-20261008a all valid but FAIL improvement: median286.8958->286.9535/s,P997.2456->7.3237ms,P99.9 16.4597->18.8406,events/s .3125->.3875,max34.0326->39.7934. Remains OFF.
- Direct-target independent cure-direct-target-textures-zoom-abba-20261008a all valid: median286.7179->289.6590/s,P997.2146->6.8222ms,P99.9 16.4385->17.3239,events/s .325->.3375,max44.3777->40.458. Overall win not established, remains OFF.
- Root allocation evidence now keeps full stacks and calibrated clocks in new analyze_render_resource_samples.py, tested for zero-duration sample semantics and full-stack retention. cure-lazy-shader-resource-stacks-20261008a: the two sampled persistent-staging allocations occurred OUTSIDE the measured window; VMA inside the33.6/35.7ms gaps was geometry/instance buffer creation, not staging. Do not label a zero-duration sample as a measured blocking call.
- Added diagnostic-only per-native-buffer creation stages to pinned MemoryManager overlay (staging/uniform/vertex/index/other). Actual pixel map/grid/font gates plus desktop test PASS, build cure-buffer-allocation-diagnostic-gates-build-20261008a. Deep baseline cure-map-mesh-allocation-baseline-deep-20261008a/run: 40s has8790vertex,8760uniform,4696index creations, zero staging creations. The largest individual creation is0.3296ms; a57.3555ms fresh gap samples a0.1238ms UBO creation, also overlaps11.466ms GC and30.75ms owner draw. Resource churn is real but is NOT proven to account for the whole gap.
- New OFF RWX_REUSE_MAP_MESH_SLOTS / --reuse-map-mesh-slots candidate: only offscreen map renderers re-key compatible cleared meshes unused in this render; preserve CPU instance capacity/native geometry/pipeline. Same sampler/alpha/blend required. Independent bindings follow existing version-rebind path; shared immutable materials require identical resolved texture. Current runs never merge; drawGroup re-applied; target slot lease/fence acquisition unchanged. Three new order/clip/alpha/capacity/rebinding/root-exclusion tests PASS. First test build failed fixture Texture2d constructor (preserved); repaired build b PASS. Full canvas/Vulkan gates running, no real-Europe or formal candidate qualification yet.
