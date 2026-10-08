# 地图格改用 GPU 离屏 pass

2026 年 10 月，本文件记录把地图 layer buffer 格子从"CPU 光栅化成 `IntArray` + 整块上传"改为
真正的 Vulkan 离屏渲染 pass 的实现。前因与已验证数据见
[Windows 快速拖图优化实现记录](windows-map-pan-optimization.md) 与
[原版引擎与 Vulkan 渲染分离](vulkan-engine-separation.md)。

## 1. 定位到的最根本问题

不是"多做了什么"，而是**同一格像素由谁来画**。

RWX 桌面端所有离屏目标（地图格、瓦片图集、迷雾图集、小地图）一律走
`RenderTargetMode.IMMEDIATE`：

- `LayerBufferManager` 用 `IMMEDIATE` 创建每个格子的离屏引擎
  （`core/src/main/java/com/corrodinggames/rts/game/map/LayerBufferManager.java` 的
  `initMissingLayerBufferImages`）。
- Kool 把 `IMMEDIATE` 实现成 CPU 位图语义：`KoolGraphicsEngine.flushTargetTextureFrame`
  对 `IMMEDIATE` 调 `rasterizeTargetFrame`，把整格命令逐条 CPU 执行成 `IntArray`，再登记成纹理上传
  （`core/src/main/kotlin/io/github/rwx/render/canvas/KoolGraphicsEngine.kt`）。
- 那个光栅器是手写 Kotlin 标量循环：线性采样每像素 4 次取纹 + 3 次 `interpolateColor`，每次内部逐通道
  浮点插值、`roundToInt`、`coerceIn` 与变量除法（同文件 `interpolateColor` /
  `interpolatePremultipliedChannel` / `tintColor` / `renderTargetSourceOver`）。
- 原版同一个格子是 `Bitmap` + `android.graphics.Canvas`，也就是 **Skia**：原生 C++、SIMD、定点插值、
  没有逐像素变量除法（原版 `gameFramework/m/fh.java` 的 `b(e)` 里 `new Canvas(); canvas.setBitmap(...)`，
  blit 落在 `gameFramework/m/h.java` 的 `Canvas.drawBitmap`）。

于是"格子重建"在原版是原生光栅化，在 RWX 是 JVM 标量光栅化，实测约 10.4 ms/格
（416 ms 单帧 = draw 223 ms + 可见格补绘 156 ms / 15 格）；而缩放会把整个 7×7 网格标脏
（`LayerBufferManager.updateGridParams`），可见格又在发布画面**之前**同步画完
（`KoolDesktopGameSession.engineFrame` 调 `renderVisiblePendingRedrawsNow`），所以这 10 倍直接变成卡顿。

**根本问题：Kool 桌面路径没有"渲染一次、多处采样"的离屏 pass。** 图层缓冲的本意就是画一次、被采样多次，
但 Kool 只有两条路——CPU 光栅化后上传，或者把被引用 frame 的命令**在每个采样点展开一遍**
（`KoolCanvasFrameRenderer.addFrameTexture` → `addProjectedFrameTextureCommands`，Θ(采样点 × 命令数)）。
`desktop-performance.md` §11.4 试过后者，`--replay=双桥` 掉到 3.4–5.0 fps，画布重放 235–317 ms/帧。
那次失败的原因不是"GPU 目标不好"，而是**展开方式**：瓦片图集 frame 有几百条命令，而 36 个格子每帧各采样一次。

所以正确的修法正是该节自己列出的前提：**frame 要变成真正的离屏渲染 pass（渲染一次、多处采样），而不是
每个引用点展开命令树。**

## 2. 实现

新增 `RenderTargetMode.GPU_TARGET`：语义上仍是"离屏目标"，但**内容只被后续绘制采样，从不读回 CPU 像素**。
有真实 GPU 渲染目标的后端把它做成离屏 pass，其他后端（Android、headless、测试）等价于 `IMMEDIATE`，
行为完全不变。

数据流：

1. 引擎侧仍然把格子的绘制命令录进命令缓冲，行为与原版一致；`LayerBufferManager` 只在
   `backendCapabilities().supportsGpuRenderTargets` 为真时选择 `GPU_TARGET`
   （其他后端继续 `IMMEDIATE`）。
2. 提交时 `KoolGraphicsEngine.flushTargetTextureFrame` 对 `GPU_TARGET` **不做 CPU 光栅化**，改为调用
   `KoolCanvasTextureStore.registerGpuTargetFrame`，把录制好的 frame 作为
   `FrozenCanvasResource.GpuTarget`（带稳定 `logicalId` 与尺寸）交给租约。
3. 引擎侧 `frame(id)` 对 GPU 目标返回 null，所以 `KoolCanvasFrameRenderer.addFrameTexture` 不会命中，
   绘制会走 `addTexture` → `textureStore.resolve(...)`。
4. 渲染线程 `FrozenCanvasGpuResources.install` 把 `GpuTarget` 交给 `KoolCanvasGpuTargetPasses`：
   取一个池化 slot，用 `KoolCanvasFrameRenderer` 把该版本的 frame 重放进 slot 自己的 `Scene`，再让
   `OffscreenPass2d` 渲染一次到它的颜色附件。
5. 该附件通过 `KoolCanvasTextureRegistry.registerGpuTargetTexture` 以**版本 id** 登记；`resolve` 优先返回它。
   于是主画面只是采样一张普通纹理。

关键文件：

| 文件 | 作用 |
|---|---|
| `core/.../gameFramework/graphics/GraphicsEngine.kt` | `RenderTargetMode.GPU_TARGET`；`GraphicsBackendCapabilities.supportsGpuRenderTargets` |
| `core/.../render/canvas/KoolCanvasGpuTargetPasses.kt` | 新增：渲染线程的离屏 pass 所有者、slot 池、内容版本与 fence 退役 |
| `core/.../render/canvas/KoolCanvasFrameEnvelope.kt` | `FrozenCanvasResource.GpuTarget`、`registerGpuTargetFrame`、install/release 钩子 |
| `core/.../render/canvas/KoolCanvasResources.kt` | 版本 id → pass 附件纹理；Linear/Nearest 采样视图；`resolve` 优先命中 |
| `core/.../render/canvas/KoolCanvasSceneHost.kt` | 把 pass 所有者接到根 scene，并安装 install/release 钩子 |
| `core/.../render/canvas/KoolGraphicsEngine.kt` | 提交分支：GPU 目标不光栅化、改登记 frame |
| `core/.../game/map/LayerBufferManager.java` | 由后端能力决定格子目标模式 |

### 为什么不重蹈 §11.4

- **每个内容版本只渲染一次**：pass 在 `onAfterCollect` 里立即把自己关掉，kool 只在那一帧执行它；
  内容没变的格子既不重渲染也不重上传（文档里的 40 倍是每个采样点展开命令树，这里每个采样点只绑一张纹理）。
- **不做跨版本图像复用**：租约按内容版本分别记账，正在飞行中的画面永远采样它构建时的那张图；
  被取代的版本只有在采样它的那一帧 GPU fence 完成后，才通过 `KoolCanvasGpuRetirement` 回到池里。
- **采样语义不变**：Vulkan 的 sampler 取自被绑定的 `Texture` 对象（`BindGroupDataVk` 用
  `binding.sampler ?: tex.samplerSettings`），所以一个附件配两个 `Texture2d` 视图（Linear 附件本体 +
  Nearest 采样视图，共享同一张 image）就能同时满足两种滤镜，而不必改共享的 `Texture.samplerSettings`。
- **清屏语义跟随原逻辑**：`KoolCanvasFrameRenderer.render` 把 frame 开头 `Clear` 折进 `scene.clearColor`，
  install 再把它赋给 pass 附件的 `clearColor`，与主画面走同一条规则。
- **Y 朝向**：Canvas 重放已经把 Y 翻转折进实例坐标，所以 pass 用 `isMirrorY = false` 与
  `VulkanSpriteAtlasOracle` 的离屏读回用例一致；重复镜像会上下翻转。

### 兜底

`GPU_TARGET` 只在渲染线程确实装了所有者时才走 GPU 路径
（`FrozenCanvasGpuResources.gpuTargetInstaller != null`）。headless、单元测试、其他后端都会自动回落到
CPU 光栅化，功能不会坏，只是没有收益。所以这个开关不会因为"跑在奇怪环境里"而变成错误画面。

## 3. 开关

| 控制项 | 作用 |
|---|---|
| `RWX_GPU_MAP_CELL_TARGETS=1` 或 `-Drwx.kool.gpuMapCellTargets=true` | 启用 GPU 地图格目标 |
| `RWX_GPU_MAP_CELL_TARGETS=0` | 强制旧 CPU 光栅路径，优先级高于上面的属性，用于同包 A/B |

**当前默认关闭。** 在下面"尚未验证"的项目通过之前，默认值不改，生产行为与改动前一致。

## 4. 验证状态

### 4.1 真实 Vulkan：控制组通过，本实现崩溃（未解决）

本机为 Windows 11 + Intel Iris Xe（i5-1145G7），`igvk64.dll`。

| 运行 | 结果 |
|---|---|
| 未改动的 `VulkanSpriteAtlasOracle`（同样 `scene.addOffscreenPass(OffscreenPass2d)`，`numSamples=4`） | **通过**：`success checks=148 ... retirementsCompleted=609` |
| 新增 `KoolCanvasGpuMapCellOracle`（`./gradlew :desktop:runGpuMapCellOracle`） | **原生崩溃**：`EXCEPTION_ACCESS_VIOLATION` at `igvk64.dll+0x1309df`，栈为 `RenderBackendVk.renderFrame` → `PassEncoderState.ensureRenderPassInactive` → `vkCmdEndRenderingKHR` |
| 同上 `RWX_GPU_MAP_CELL_ORACLE_NO_READBACK=1`（完全不创建回读 pass） | **同样崩溃**，同一 PC → 崩溃来自生产 GPU 目标路径，不是回读 |
| 同上 `RWX_GPU_MAP_CELL_PRIME_SLOTS=1`（只预热 1 个 pass） | **同样崩溃**，同一 PC → 与一帧内离屏 pass 数量无关 |

结论：kool 的离屏 pass 在本机可用（控制组通过），因此**问题在本实现**，不是驱动、也不是 kool 本身。崩溃点在帧末结束动态渲染作用域，属于原生层非法命令或悬空资源。

**下一个假设（下一轮首要验证）**：每个 slot 的 `OffscreenPass2d` 用一个**嵌套 `Scene`** 作 draw node
（`KoolCanvasGpuTargetPasses.createSlot` 里的 `Scene("rwx-gpu-target-scene")`）。这是与仓库内所有可用用法
（draw node 是普通 `Node`，或像 sprite oracle 那样直接用根 `Scene`）唯一结构性的差异：嵌套 `Scene` 自带
`mainRenderPass` 与 `lighting`，而它的 `mainRenderPass` 永远不会被 collect，`Scene.update` 却仍会执行
`lighting.onUpdate`。`KoolCanvasFrameRenderer.render(scene: Scene, frame)` 目前只接受 `Scene`
（它用 `scene.camera` 与 `scene.clearColor`），所以要换成普通 `Node`，必须先把渲染器泛化为
「`Node` + 显式相机 + 显式清屏色」。同时应把 `isReleaseDrawNode` 显式设为 `false`（与
`VulkanSpriteAtlasOracle` 一致），避免 pass 释放时连带释放 draw node。

诊断开关（仅用于定位，不影响默认行为）：`RWX_GPU_MAP_CELL_ORACLE_SINGLE=1`、
`RWX_GPU_MAP_CELL_ORACLE_NO_READBACK=1`、`RWX_GPU_MAP_CELL_PRIME_SLOTS=N`。

### 4.1b 修正：崩溃是 MSAA=1 组合，不是嵌套 Scene

上一节的"问题在本实现 / 嵌套 `Scene` 是嫌疑"已被后续单变量实验**否证**，保留原文作为记录：

1. 把 slot 的 draw node 从嵌套 `Scene` 换成普通 `Node`（渲染器新增 `renderInto(node, camera, frame, setClearColor)`，
   根路径仍是 `render(scene, frame)`），并在所有几何被置空的条件下重跑：**依旧在同一 PC 崩溃**。
   → 嵌套 `Scene` 不是原因。普通 `Node` 的改法仍然保留：它是 kool 的惯用形态，
   而且嵌套 `Scene` 会带上永不被 collect 的 `mainRenderPass` 与 `lighting`。
2. 唯一剩下的差异是 oracle 自己的 `numSamples`：通过的 sprite oracle 用 **4**，我的 oracle 用 **1**。
   只改这一项（`numSamples = 1 → 4`）后，同一进程**连续渲染 93 帧无崩溃**。
   → 触发条件是 **MSAA=1 + 离屏 pass** 的组合（Windows/Intel `igvk64.dll`），与地图格实现无关。
   生产桌面默认就是 4，所以真实对局不受这条影响；但仓库里 `-Drwx.kool.msaaSamples=1` 这个诊断选项目前
   与离屏 pass 不兼容，应在动这条路径前先复核。

修正后的实测状态（`./gradlew :desktop:runGpuMapCellOracle`，MSAA 4，含回读）：

| 观察 | 结果 |
|---|---|
| install / 冻结 / 注册链路 | `registered=true imageCreated=true gpuTargets[active=1 pooled=12 limit=12]` ✔ |
| 附件清屏色是否按录制帧的 `Clear` 生效 | 读回 `0xff101820`，与第一版内容的 clear 色**逐位一致** ✔ |
| 相机基、1:1 读回与 Y 朝向约定 | 清屏色能在 (0,0) 正确读回，说明映射成立（几何部分尚未验到） |
| 格子的绘制几何 | **未出现**：整幅只剩清屏色（期望 (0,0) 为顶部红带 `0xffff0000`） |

因此当前唯一的未解问题是：**目标 pass 里只应用了清屏，`DrawRect`/`DrawTexture` 重放出的几何没有进入附件**。
下一轮的第一步是加最小插桩：在 `install` 里打印 `recorded.commands.size`，以及 `renderInto` 之后
`slot.node.children.size` 与各 mesh 的 `isVisible`/几何顶点数，据此判断是"命令没到"、"几何没建"还是
"建了但被剔除/不可见"。

诊断开关新增：`RWX_GPU_MAP_CELL_ORACLE_MSAA=N`（默认 4）、`RWX_GPU_MAP_CELL_SKIP_GEOMETRY=1`、
`RWX_GPU_MAP_CELL_KEEP_PASS_ENABLED=1`（均为定位用，默认关闭）。

### 4.1c 几何缺失已定位到"渲染器产出的 Mesh 不出像素"

`RWX_GPU_MAP_CELL_DIAGNOSTICS=1` 在 `install` 里打印实测（32×24 目标）：

```
commands=5 types={Clear=1, DrawRect=4} nodeChildren=2 visibleChildren=2
children=rwx-kool-canvas-primitives-source:Mesh:visible=true:verts=12|rwx-kool-canvas-primitives:Mesh:visible=true:verts=4
```

结论分三步：

1. **命令到了**：录制帧含 `Clear=1 + DrawRect=4`（顶部带、底部带、绿块、半透明白块），与 oracle 画的内容一致。
   注意第 5 条 `DrawTexture`（stamp）**没有进帧**——`cell.b(stamp, null, STAMP_RECT, null)` 传 null 源矩形时似乎不产生命令，
   所以纹理采样这条用例当前根本没被覆盖，需单独修。
2. **几何建了且可见**：节点下 2 个 Mesh，`visible=true`，合计 16 顶点 = 4 个矩形。
3. **附件里只有清屏色**：整幅 768 个 texel 全是 `0xff101820`
   （`actualTop=0xff101820x768`；期望是 448 clear + 96 红 + 96 蓝 + 48 绿 + 48 混合灰）。

关键反证：`downloadFull` 里**手写**的贴图四边形能在同一个离屏 pass 类型里正常渲染——否则读回会是读回 pass 自己的
黑色清屏色，而不是目标的 `0xff101820`。所以"离屏 pass + 正交相机 + 1:1 读回 + Y 朝向"这条链是好的，
**只有 `KoolCanvasFrameRenderer` 产出 Mesh 的那条路不出像素**。

已排查并排除：

- 自适应可见性：`KoolCanvasAdaptiveVisuals.shouldDraw` 在 `visualStats == null`（格子帧就是）时恒为 true。
- `renderTarget` 过滤：`KoolGraphicsEngine` 从不调用 `setRenderTarget`，格子命令的 `state.renderTarget` 恒为 null，
  不会被 `renderSurface` 里的 `renderTarget == null` 守卫丢掉。
- 渲染器全文**没有** `markDirty`/`isDirty`；它靠原始缓冲写入加 `geometry.clear()`/`addVertex` 隐式置脏。
  这条路在根画布上有效，因此需要进一步确认格子 pass 里脏标记与 `GeometryVk` 上传是否真的发生。

下一轮的两步（都在 oracle 里做，不动生产代码）：

1. **同节点内对照**：在一个离屏 pass 的节点里同时放"手写四边形"和"`renderInto` 产出的 Mesh"，
   读回后用 `topColours` 看哪一半出像素,把范围锁到渲染器的 mesh 产出/上传。
2. **打印 mesh 的 GPU 侧状态**：`drawGroupId`、`geometry.isDirty`、`shader.createdPipeline` 是否为空、
   以及 `pipeline.capturedPipelineData` 是否绑定了 view 数据；据此判断是"没上传"、"没出管线"还是"被剔除"。

### 4.1d 像素验证通过（真实 Vulkan）

> 让几何真正上屏的多帧资源延迟定位与修复见 4.1f。

`./gradlew :desktop:runGpuMapCellOracle`（MSAA 4，32×24 目标，两个内容版本各 768 texel 对照手工推导的期望值）：

```
first-version : actualTop=0xff101820x448|0xffff0000x96|0xff0000ffx96|0xff00ff00x48|0xff888c90x48
                expectedTop=0xff101820x448|0xffff0000x96|0xff0000ffx96|0xff00ff00x48|0xff878b8fx48
                mismatches=0/768  maxChannelDelta=1  corners=[红,红,蓝,蓝]
second-version: mismatches=0/768  maxChannelDelta=1  corners=[橙,橙,蓝,蓝]
RWXGpuMapCellOracle success checks=1537
```

覆盖并通过：清屏（逐位一致）、`Source`（`BLEND_DISABLED`）不透明矩形、`SourceOver` 半透明合成、纹理采样
（`DrawTexture` 走 `argbTexture` 上传）、Y 朝向（独立断言：左上/左下不同色）、内容版本更新
（第二版把顶带由红改橙，读回随之改变）、以及 fence 后旧版本注册被释放。

过程中修掉两个 oracle 自身的缺口：`b(stamp, null, dst, null)` 传 null 源矩形**不产生任何命令**
（导致纹理采样这条用例一直没被覆盖，改成显式源矩形后覆盖）；以及在 `freezeFrame` 之前取 texture id
会拿到逻辑 id 而非内容版本 id。

### 4.1e 性能 A/B：工具自带校验契约（下一步）

用项目自带的同包 A B B A：

```powershell
python desktop/tools/map_pan_live_comparison.py `
  --baseline $jar --candidate $jar --java $java --output $out `
  --candidate-gpu-map-cell-cache `
  --disable-native-bgra-upload --no-perf-window-log `
  --window-width 1280 --window-height 720 --fog off --camera-mode pan-zoom --camera-period-seconds 1
```

本轮实测（`--camera-mode pan --zoom-mode none`）：两侧都跑完并产出指标
（基线 `newFps 228.4/230.2`、`P99 11.36/10.84 ms`；候选 `227.4/229.3`、`11.78/10.96 ms`），
但该场景 `actualZoomMinimum=actualZoomMaximum=1.0`，**没有缩放周期**，因而完全不触发格子重建，
不是卡顿场景，也不能当作 A/B 结论。必须用 `pan-zoom`。

候选侧被工具判为**无效**：
`GPU map cell cache requires actual Vulkan support and growing hit counters for created cells inside measured windows`。

工具要求的数据契约（见 `desktop/tools/map_pan_builtin_comparison.py:105-141` 与
`desktop/tools/test_map_pan_camera.py:131`）：

1. 一行能力日志：`RWXGpuMapCellCache requested=<b> supported=<b> enabled=<b>`；
2. **每帧指标记录**（`RWX_FRAME_METRICS` 的 jsonl）里带 `gpuMapCellCache` 对象，累计计数键为
   `created`、`hits`、`retired`、`live`、`pending`，且要求：窗口内 `hits` 有增长、最后一个采样点 `created > 0`、
   计数单调不减。

所以下一步是给 `KoolCanvasGpuTargetPasses` 补上这两项诊断（`created` = 新建 pass 数、
`hits` = 复用已有 pass/版本次数、`retired` = 退役数、`live` = 活动版本数、`pending` = 等待 fence 数），
接进既有 `CanvasFrameMetrics` 采样。这样既是 A/B 的入场券，也正好是"渲染一次、多处采样"的量化证据。

### 4.1g 性能 A/B 首次实测：基线 P99 188–230 ms，候选侧崩溃（未通过）

诊断已按工具契约接好（`created/hits/retired/live/pending` 写进每 5 秒帧指标记录，
能力行 `RWXVulkanConfiguration gpuMapCellSupported=true`，能力行在第一个 pass 真正创建后打印）。
运行命令（同包 A B B A，仅候选侧启用 GPU 格子，1280×720，`--camera-mode pan-zoom` 才触发格子重建）：

```powershell
python desktop/tools/map_pan_live_comparison.py `
  --baseline $jar --candidate $jar --java $java --output $out `
  --candidate-gpu-map-cell-cache `
  --disable-native-bgra-upload --no-perf-window-log `
  --window-width 1280 --window-height 720 --fog off --camera-mode pan-zoom --camera-period-seconds 1
```

| 侧 | 结果 |
|---|---|
| 基线（CPU 光栅格子） | 两个窗口跑完：`newFps 129.5 / 118.2`，`freshSnapshotIntervalP99Ms 187.76 / 230.45`，`actualZoom 0.350–1.5`（缩放周期确实在跑），500 单位 |
| 候选（GPU 格子） | **运行崩溃，未产出窗口**：工具报 `live-pan timed out`（每轮 155 秒上限） |

候选侧日志里的未捕获异常：

```
RustedWarfare: java.lang.IllegalStateException:
  Texture2d:rwx-canvas-argb-legacy-texture-114/snapshot-1172/cpu-1-version-3590-Nearest-623 is already released.
```

即**画布 ARGB 快照纹理被提前释放后仍被使用**。名字形态表明这是一张经
`snapshotFrameDependencies`/`snapshotPixels` 产生的**嵌套目标像素快照**（`/snapshot-N/` 前缀，`-Nearest` 说明来自
`argbTexture(id, filter)` 的惰性 ARGB 上传所有者），随后又被 `releaseRetiredTextures`/释放路径回收，
而仍有在途帧引用它。

初步判断：这条快照生命周期是为 **CPU 光栅化**路径设计的（快照让 CPU 重放能读到嵌套目标的像素，
并由 `retainFrameSnapshot`/`releasePendingImmediateFrameSnapshots` 配对管理）。GPU 目标不需要 CPU 重放，
但 `flushTargetTextureFrame` 仍对 GPU 目标调用了 `snapshotFrameDependencies`，于是引入了这条不再有人正确配对的
快照生命周期。下一个候选修法：**GPU 目标不再生成嵌套像素快照**，直接把 `commitSnapshot.frame` 交给
`registerGpuTargetFrame`（渲染线程通过普通注册表解析嵌套纹理即可），然后重跑 A/B。

结论：**像素一致性已通过，但性能验证未通过**——不是慢 10%，而是候选侧直接崩溃、拿不到窗口数据。
因此开关必须继续保持默认关闭，且不能据此宣称卡顿已解决。

### 4.1f 已定位并修复：kool 的多帧资源延迟

按 4.1c 的第一步做了**同节点二分**，并拿到了决定性数据：

| 实验 | 结果 |
|---|---|
| oracle 自建离屏 pass + `KoolCanvasFrameRenderer.renderInto` 一个普通 canvas 帧 | **完全正常**：`top=0xff00ff00x768` → 重放渲染器的 mesh 产出没问题 |
| `RWX_GPU_MAP_CELL_KEEP_PASS_ENABLED=1`（目标 pass 每帧都渲染） | **几何全部出现**：`actualTop=…0xffff0000x96|0xff0000ffx96|0xff00ff00x48|0xff888c90x48`，角点=红/红/蓝/蓝（Y 朝向正确） |
| 逐帧追踪（`RWX_GPU_MAP_CELL_DIAGNOSTICS=1`） | pass **确实渲染了两帧且两帧都带完整几何**（`children=2 verts=16`），但附件里只有 SourceOver 那个 mesh |
| 每版本渲染 **4** 帧 + 读回等 8 帧 | **几何完整出现**，`mismatches=16/768` |

结论：**kool 新建 mesh 的几何/管线要跨若干帧才真正生效**（`-source` 批次的两个 mesh 之一在第一、二帧都没上屏）。
此前生产代码"每个内容版本只渲染一帧"因此必然丢几何。修法：`KoolCanvasGpuTargetPasses` 增加
`Slot.renderFramesRemaining`，每版本渲染 `RENDER_FRAMES_PER_VERSION = 4` 帧再关闭；
额外帧复用已建好的几何与管线，相对它替代的 CPU 光栅化代价很小。

**当前像素状态（真实 Vulkan，MSAA 4，32×24 目标）**：

```
actualTop  =0xff101820x464|0xffff0000x96|0xff0000ffx96|0xff00ff00x48|0xff888c90x48
expectedTop=0xff101820x448|0xffff0000x96|0xff0000ffx96|0xff00ff00x48|0xff878b8fx48
mismatches=16/768  corners=[红, 红, 蓝, 蓝]
```

- 清屏、两条色带、绿块、半透明块的**数量与颜色全部对上**（混合色 0x88/0x8c/0x90 与解析值 0x87/0x8b/0x8f 差 1，在容差内）。
- Y 朝向由独立断言确认（左上红、左下蓝）。
- **剩余 16 个 texel** 恰好是绿块 `Rect(4,8,12,16)` 与半透明块 `Rect(8,12,16,20)` 的 4×4 重叠区，
  实测清屏色比期望多 16 个（464 vs 448），即该重叠区没有按"绿底 + 半透明"合成，`maxChannelDelta=172`。
  两个待查候选：(a) `-source` 批次（`BLEND_DISABLED`）与 Alpha 批次的绘制顺序在该区被 kool 的
  `drawGroupId` 排序改变；(b) RWX 的 `patchDrawPipeline` 改了 3 处 `dstAlphaBlendFactor`
  中的 2 处，可能在"两层合成"时改变 alpha 结果。下一轮只需打印重叠区首个 texel 的实测值即可分辨。

### 4.1h 修复后再测：候选侧首个窗口 P99 从 ~150 降到 ~36 ms，但第二次候选仍崩溃

本轮修掉四件事：(1) 录制期不再把嵌套目标的像素/帧快照烘进 GPU 重放目标
（`freezeTextureForOffscreenDraw` 对 `gpuReplayTarget` 短路——租约本身已钉住源像素版本）；
(2) `Slot.release()` 先 `isEnabled=false` 再释放 pass（`Scene.collectScene` 在 `extraPasses` 报告变更前
仍会遍历旧的 `sortedPasses`）；(3) 几何改为在 pass 的每帧 `onUpdate` 里重建，使每个渲染帧都重新解析纹理，
不再复用第 1 帧的 `Texture2d`（否则注册表退役该 ARGB 纹理后，kool 会绑定已释放纹理）；
(4) GPU 目标提交不再走 `snapshotFrameDependencies`。

同包 A B B A（1280×720，pan-zoom，500 单位，fog off）：

| 轮次 | newFps | renderFps | 新画面间隔 P99 (ms) |
|---|---|---|---|
| 01 基线（CPU 光栅格子） | 115.89 / 128.68 | 233.3 / 235.5 | **149.70 / 160.49** |
| 02 候选（GPU 格子） | 166.30 / 158.90 | 182.3 / 176.6 | **36.23 / 39.97** |

单看这两轮：新画面率 +23%~+43%，**P99 长帧尾部从 ~150–160 ms 降到 ~36–40 ms（约 4 倍）**，
正是用户抱怨的那种连续缩放卡顿所在的位置。

**但协议未完成，不能当结论**：03（第二个候选）崩溃，
`IllegalStateException: OffscreenPass2d:rwx-gpu-target-296 is already released`，栈为

```
GpuPass.update (GpuPass.kt:50) → RenderPass.update → GpuPass.collect
  → Scene.collectScene (Scene.kt:97) → KoolContext.render
```

即该 pass 在本帧 `passData` 构建时仍然有效（`isEnabled` 为真，已进 `passData`），
但在它被 `collect` 之前就被释放了。`Scene.collectScene` 的顺序是
`onRenderScene` → 排序 → 构建 `passData` → 逐个 `collect`，
所以释放发生在"构建 `passData`"与"collect"之间。两个待查方向：

1. `VulkanUploadState.retainUntilFrameComplete` 的排空点是否可能落在该窗口内
   （生产会话是否真的装了 fence sink，还是走了 `KoolCanvasGpuRetirement.retire` 的即时分支）；
2. `passData`（`ResettableDataList`）是否复用了上一帧残留的条目。

兜底修法（下一轮）：池化上限提到能容纳"活动版本 + 待退役版本"（例如 32），
并且**释放一个 pass 至少推迟一整帧**（先 `removeOffscreenPass`，下一帧确认它已不在 `sortedPasses` 后再 `release()`），
从而彻底消除"已进 `passData` 又被释放"的窗口。

结论：**像素一致性通过、崩溃缺陷减少到一类、并且出现了明确的正向性能信号；
但 A/B 尚未跑通完整协议，开关必须继续默认关闭。**

### 4.1i 顿挫口径与实测：迷雾关闭下 P99 342→31 ms

判据是**顿挫**（画面停止更新的时长），不是平均帧率。现有工具只报"呈现间隔"分位与 5 秒粒度的新画面分位，
**会掩盖单次长冻结**；而新画面间隔必须按"两次 sequence 不同的 accepted present 之间的间隔"来算。

分析口径见 `build/analyze_fresh_picture_tail.py`（应移入 `desktop/tools/` 并纳入回归）：
只统计 `map_pan_builtin_comparison.py` 自己认定的测量窗口（`sampleStartNanos`..`sampleEndNanos`），
输出 P50/P95/P99/最大间隔与超过 33/50/100 ms 的次数。预热**不能按墙钟排除**：迷雾开启时引擎时间比真实时间
慢约 2.4 倍，按墙钟 25 秒剪裁会把大段预热算成一次巨大顿挫（曾得出错误的 13.6 倍结论）。

同包 A B B A、1280×720、`pan-zoom`、500 单位、**迷雾关闭**（`validComparison: true`）：

| 指标 | 基线（CPU 光栅格子） | 候选（GPU 格子） | 变化 |
|---|---|---|---|
| 新画面张数（窗口内） | 3872 | 6913 | +79% |
| P50 | 5.06 ms | 4.34 ms | −14% |
| P95 | 14.65 ms | 12.17 ms | −17% |
| P99 | 342.12 ms | **31.16 ms** | **−91%** |
| 最大冻结 | 643.97 ms | **101.01 ms** | −84% |
| >100 ms 冻结 | 53.0 | **0.5** | **−99%** |
| >50 ms 冻结 | 62.0 | 19.5 | −69% |

即：GPU 格子路径把 CPU 光栅化造成的长冻结基本清零。对照原版（`铁锈代码`，Skia）：RWX 基线与原版使用
**同一套格子缓存算法**，唯一劣势是光栅化器（JVM 标量 vs Skia），而基线的顿挫恰好全部来自那部分冻结；
GPU 路径把光栅化移出帧内，因此这一项不只是"不弱"，而是把原版赖以取胜的环节从关键路径上删掉。
（本机无法运行原版 Android 构建，故这是机制论证 + RWX 基线实测，不是原版实测数字。）

**迷雾开启仍未达标**：基线在窗口内只有 668 张新画面、P50 26.26 ms、P99 777.87 ms、最大 997.02 ms、
56 次 >100 ms 冻结。候选侧此前因单轮 155 秒上限跑不完协议（迷雾开启时引擎时间约为真实时间的 1/2.4，
协议需要 60 秒引擎时间 ≈ 160 秒墙钟，刚好越界），故**没有有效的对照**。
为此给 `map_pan_live.py` / `map_pan_live_comparison.py` 增加了 `--run-timeout-seconds`（默认仍是 155）。

### 4.1j 正确性与性能的取舍：自适应渲染帧数

"每版本渲染几帧"直接对撞正确性与性能（同包实测）：

| 策略 | 像素 | renderFps |
|---|---|---|
| 固定 4 帧 | 正确 | 196→73（−62%） |
| 固定 2 帧 | **不正确**（256/768 失配，Source 批次 mesh 未上屏） | +9% |
| **自适应**：仅当本帧**新建了 mesh** 才补到 4 帧，否则 1 帧 | 正确（0/768 失配） | −6% |

配合本轮其他修复：pass 释放**推迟到帧首**（`beginFrame` → `drainPendingReleases`，
杜绝"已进 `passData` 又被释放"的 `OffscreenPass2d ... is already released`）、
闲置池 12→32 且每帧最多释放 4 个、几何每帧重建（每帧重新解析纹理，不复用第 1 帧的 `Texture2d`）、
以及录制期不再为 GPU 重放目标生成嵌套像素快照。

### 4.1k 迷雾开启跑不完的真正原因：引擎线程在 tick 中途读到空地图

候选侧的迷雾运行此前总是超时，根因不是渲染，而是**引擎 owner 线程死掉**：

```
[RWX-engine-owner] ERROR Desktop - RWX engine owner stopped after a failed loop
java.lang.NullPointerException: Cannot read field "tileScaleX" because "tileMap" is null
  at OrderableUnit.applyPositionChange(OrderableUnit.java:962)
  at OrderableUnit.update → CustomUnit.update → GameLogic.update → GameLogic.gameLoop
  at KoolDesktopGameSession.runGameLoop(KoolDesktopGameSession.kt:458)
  at KoolDesktopGameSession.engineFrame(KoolDesktopGameSession.kt:259) → EngineOwnerLoop.run
```

即关卡结束/重载时 `gameEngine.tileMap` 被释放，而主循环仍在该 tick 内更新单位。引擎线程一停，
协议窗口永远凑不齐，harness 只能超时（日志末尾的 `Now playing: music/starting/...` 说明已回到主菜单）。
该 NPE 在 30 个历史运行中只出现在这一处，属于会话生命周期的**潜在缺陷**，与 GPU 格子无关，
但会掩盖迷雾配置的性能结论。

两处兜底（都已编译通过、像素 oracle 复测未回归）：

1. `KoolDesktopGameSession.engineFrame`：入口处 `runGameLoop` 也加上 `hasLoadedLevel && tileMap != null`
   判断，与紧邻的 layer-buffer 两处判断一致；
2. `OrderableUnit.applyPositionChange`：解引用前判空返回——地图是在 tick **内部**被释放的，
   只守循环入口不够。

效果：迷雾开启的候选侧**首次产出测量窗口**（n=4113）。

迷雾开启的**不完整**对照（只有 01-基线 与 02-候选，第二次候选仍超时）：

| 指标 | 基线 | 候选 |
|---|---|---|
| P50 | 4.49 ms | 4.20 ms |
| P95 | 21.71 ms | 23.68 ms |
| P99 | 272.53 ms | **94.67 ms** |
| 最大 | 891.05 ms | 895.39 ms |
| >33 ms | 84 | 136 |
| >50 ms | 69 | 94 |
| >100 ms | 43 | **34** |

即迷雾开启下候选侧 P99 与 >100 ms 冻结更好，但 >33/>50 ms 计数与最大值更差——**迷雾这条顿挫源
没有被本改动解决**，它是独立子系统（迷雾图集维护与逐格迷雾读取），协议也仍未跑通。
开关必须继续默认关闭。

### 4.1l 迷雾开启的真正阻塞点：直接内存耗尽（已排除本条路径的附件与注册表）

修掉 `tileScaleX` 后暴露出的第二条崩溃是**级联**：`CustomExceptionHandler.java:38` 会把
`gameEngine.musicManager` 置空，于是后续 tick 在 `GameLogic.runGameLoopFrame:1407` 抛
`musicManager is null`，引擎 owner 线程停止。用 `rwx.log` 里第一条 ERROR + stdout 首条异常找到首因：

```
RustedWarfare: java.lang.OutOfMemoryError: Cannot reserve 4096 bytes of direct buffer memory
               (allocated: 2147481444, limit: 2147483648)
```

**候选侧单独运行**（开始时系统可用内存 4306 MiB，与连续四轮无关）同样在约 57 秒内耗尽 2 GiB 直接内存。
注意该上限同时覆盖 `FileChannel.map`，即 Windows 上 Vulkan host-visible 内存的常见分配方式。

新增诊断（`RWX_CANVAS_MEMORY_DIAGNOSTICS=1`，或测量运行时自动开启，因为 harness 会构造最小子进程环境）
打印 JVM 直接内存、堆内存、本路径计数器与注册表规模。实测**排除**了本条路径的嫌疑：

```
gpuTargets[created=43 live=6..16 pooled=18..30 pending=0 retired=9]
textures[argb=5..6 argbImages=17..55 registered=6..16 gpuTargets=6..16 retiring=0]
heapMb 207→512→829→911→982→618→781→830→690→799（振荡）
```

即 pass 附件、`argbTextures`、`registeredTextures`、`registeredArgbImages`、`gpuTargets` 全部有界且稳定；
泄漏在 **JVM 直接/mapped 缓冲**，即 Kool Vulkan 侧的缓冲/管线 churn。首要嫌疑：
(1) 离屏路径每帧调用 `renderInto`，而 mesh 淘汰阈值是"60 个渲染序号"，离屏调用会让该序号增长极快，
导致 mesh（及其动态几何缓冲）被反复创建/释放；
(2) 每个 slot 一个 `KoolCanvasFrameRenderer`，其 mesh/管线缓存按 slot 累积。
迷雾开启时格子每 tick 全部失效 → churn 速率最高，所以只有迷雾配置能触发；关闭迷雾时同一路径稳定跑完。

`directMb` 目前打印为 `null`（JMX 池名匹配失败），下一轮应先修好该读数（同时读 `direct` 与 `mapped` 两个池），
再与 mesh/管线创建计数对照定位。

### 4.1m 直接内存的真正关系：网格累计创建量（释放不归还）

本轮把关系测准了，并纠正了 4.1l 的归因。诊断加了待释放队列长度（core 侧
`KoolCanvasGpuRetirement.pendingRetirementCount()`，desktop 侧 `VulkanFrameLifecycle.pendingRetirements()`，
经 `KoolCanvasSceneHost.setGpuRetirementPendingCounter` 注入；单独 setter 以免尾随 lambda 绑错参数）：

```
direct=17MB    meshes[cached=0]      retirements[pending=0]
direct=398MB   meshes[cached=3284]   retirements[pending=43]
direct=1964MB  meshes[cached=17943]  retirements[pending=0]
→ OutOfMemoryError: Cannot reserve 40960 bytes of direct buffer memory
```

三条结论：

1. **缓存网格数与直接内存严格成比例**（≈109 KB/网格）。
2. **`pending=0`**：fence 退役队列没有积压，释放是及时执行的。
3. 但把保留期设为 0（`cached` 降到 336、无 OOM）时直接内存**仍有 1.6 GB**，且运行掉到 2 fps。

因此 **Kool 的几何缓冲在 mesh 释放后并不归还**（只增不减），直接内存取决于**累计创建量**而非存活量；
"尽早淘汰"不仅无效，还会因反复重建而放大创建量。唯一有效的方向是**减少网格创建**。

创建量来自 `TextureMeshKey.textureIdentity`：`meshTextureIdentity(id)` 在没有
`frozenPixelIdentity` 时**回退为 `Version(textureId)`**，而版本 id 每次内容提交都变，于是每个格子版本
都会新建整套网格。迷雾开启时格子每 tick 全部失效 → 约 300 网格/秒 → 约 33 MB/秒的不可回收直接内存
→ 约 60 秒撞上 2 GiB 上限。关闭迷雾时 churn 低得多，所以只有迷雾配置会死。

下一轮：先计数每秒网格创建量，并用 harness 现成的 `--disable-frozen-pixel-mesh-reuse` /
`--disable-immutable-pixel-snapshot-reuse` 各跑一次做对照；确认后把 `textureIdentity` 对"图像对象稳定"的纹理
做成**与版本无关**，改为重绑 `shader.colorMap`（渲染器已有"对象变了就重绑"的路径）而不是新建网格。

### 4.1n 稳定网格身份：迷雾开启首次跑完协议，但内存仍在边缘

`frozenPixelIdentity` 的语义（见 `KoolFrozenPixelMeshReuseTest`）是**版本无关**的稳定身份：
`(录制 owner, 逻辑 id)`，同帧多版本由 slot 区分，渲染器据此**复用同一网格并重绑 `colorMap`**
（`KoolCanvasFrameRenderer.kt:1029-1030` 等）。而它此前只在资源是 `FrozenCanvasResource.Pixels` 时才赋值，
所以凡是"没有 CPU 像素快照"的纹理（嵌套 frame、GPU 目标，以及 Asset）都退回 `Version(textureId)`：
**每个内容版本都新建整套网格**。关键不对称是**只有 GPU 目标会重放帧**（CPU 光栅路径不重放），
所以这份代价只有本路径付。

把身份扩展到所有非空资源后（`KoolCanvasFrameEnvelope.texture`）：

| 迷雾开启候选侧（单跑） | 改前 | 改后 |
|---|---|---|
| 单轮结果 | 5.8 fps，约 60 秒 OOM | **跑完协议**（`valid: True`），无 OOM |
| newFps | 5.85 / 5.15 | **98.15 / 117.32** |
| 新画面间隔 P99 | 1282 / 1613 ms | **97.37 / 83.38 ms** |
| 网格缓存峰值 | — | 仍 ~17,000 |
| 直接内存峰值 | 2 GiB（OOM） | 仍 ~2.0 GiB（未触发 OOM） |

`pixels[argb]`/`gpuTargets` 等注册表全程有界（6~16），`retirements[pending]` 基本为 0。
单元测试 318 项（仅无关失败）与像素 oracle（1537 项、0/768 失配）均通过。

**仍未解决**：网格缓存仍有 ~17,000 条 ≈ 2 GiB 直接内存，只是这次没触发分配失败。
原因已明确：身份的第二个分量是**每个格子的录制 owner**，而 slot 在不同格子间轮换，
于是同一 slot 为不同 owner 反复新建网格，缓存按 (slot × owner) 累积。
下一轮应把 **offscreen 渲染器的身份去掉 owner 分量**（一个 slot 在一次渲染调用里只渲染一个格子，
去掉 owner 不会同帧撞车；根画布渲染器保留 owner），预期把缓存压到数百个网格（~70 MB）。

### 4.1o 治本方向：直接内存的持有者是网格的 CPU 侧顶点数据

按用户要求改为治本策略，先定位持有者而非继续调池子大小。Kool 侧读码结论：

- `MemoryManager`（`build/kool-src/desktopMain/.../vk/MemoryManager.kt`）里 `mapped` 缓冲是 **映射显存**
  （`MemoryUtil.memByteBuffer(allocInfo.pMappedData(), size)`，`VkBuffer.mapped`），属原生内存、不计入 JVM 直接内存；
- `VmaMemManager.freeBuffer` 的延迟释放 lambda **只捕获 `handle`/`allocation` 两个 long**，
  不持有 `VkBuffer`，所以"待释放队列"不是持有者（也已被 `retirements[pending]≈0` 实测排除）；
- `ObjectRecycler(maxSize, factory)` 有上界，排除；
- 真正按容量计入 JVM 直接内存的是 **`Buffer.desktop.kt:84` 的 `ByteBuffer.allocateDirect(capacity)`**，
  即 Kool 的 CPU 侧数据容器 `Buffer`，而每个 `Mesh` 的顶点数据（`VertexList`）持有它。

这与实测的 **~110 KB/网格**完全吻合（≈2 GB / 18,960 网格），也解释了"释放网格后内存不降"：
`mesh.release()` 走的是 GPU 侧释放，CPU 侧顶点数据仍可达。

治本方案（下一轮实施，按优先级）：

1. 让渲染器丢弃网格时同时**释放 CPU 侧顶点数据**（清空 `VertexList`/`Buffer` 引用），
   而不只是 `mesh.release()`；这样 reclaim 交给 GC 即可，且不需要牺牲网格复用。
2. 把网格缓存的上界从"条数"改为**总字节预算**（例如 256 MB），超过时按 LRU 丢弃，
   使缓存无论 slot 多少都有确定的内存上界。
3. pass 侧同样给出字节预算（实测约 5 MB/pass，远高于 512² RGBA 的 1 MiB 附件，需一并查清）。

### 4.1p 迷雾开启瓶颈的完整归因（第 24–29 轮）

用 harness 的 `--canvas-stage-trace`（长表 `startNanos,endNanos,stage,value0..2`）在迷雾关闭/开启下各测一次，
逐一排除嫌疑后，瓶颈落在**每次格子重放的批次数**上。

已排除（都有实测数字）：

| 嫌疑 | 实测 | 结论 |
|---|---|---|
| GPU 目标安装路径 | `envelope-install` **0.05 ms/次**，雾关总计 811 ms（占 0.5%） | 可忽略 |
| 纹理解析缓存被整帧冲垮 | `resolve` 调用：雾关 47,210 / 雾开 45,976，而命令约 2,400 万条 | **命中率 ≈99.8%，排除** |
| 迷雾图集上传 | `argb-pack` 雾开总计 90 ms | 排除 |
| 网格保留窗口过大 | 60 → 8 后每槽 580 → ~50，直接内存 2 GB → 1.6 GB，**协议首次跑完** | 已修 |
| 版本虚增（`visualStats` 计入内容键） | 改为只看 `commands`+`viewport` 后 versions 4,142 → 4,206 | 中性；连续缩放的内容**确实**每 tick 都变 |

瓶颈定位（迷雾开启，跨槽求和）：

```
cached=7255   meshMapsSum[primitive=3421 texture=3628 instanced=206]
```

除以保留窗口 8 得 **≈907 批/次重放**；迷雾关闭同口径为 **≈56 批/次重放**（`cached≈450`）。
即 `cached ≈ 保留窗口 × 每次重放的批次数` —— **网格并不跨重放复用**，每次重放都新建/淘汰整批网格。
这同时解释：每次格子重放 4 ms（雾关 0.79 ms）、直接内存 1.6 GB（雾关 424 MB）、以及 1.18 s 的尖峰。

`activeBatchIndex` 在每次重放序言里**确实复位**（`KoolCanvasFrameRenderer.kt:436`），所以不是计数器泄漏。
根因是网格键里含**批次序号**（有序绘制需要它来保证绘制顺序，`drawGroupId = batchIndex + 1`）：
序号只在内容完全一致时可复现，于是内容一变（连续缩放、迷雾刷新）就必须换键、换网格。

**下一步（尚未实施）**：降低每个格子的批次数，而不是继续调缓存。
迷雾按 tile 交替 blend/layer，把批次切碎到约 900 个；两类可选做法：
(1) 把迷雾作为**独立一层**在采样/合成阶段叠合，批次数回落到约 56 —— 但会让地图语义偏离原版
（原版把迷雾烘进格子光栅），必须先做视觉对照；
(2) 把网格键里的**全局批次序号**换成**按材质的序号**，并继续用 `drawGroupId` 维护绘制顺序 ——
不改变语义，但属于渲染器热路径的深层改动，需要完整回归。

### 4.1q 施工依据：迷雾让纹理绘制走有序路径（含 file:line）

第 32 轮确认（纯代码级，无需运行）：

- 纹理绘制的有序判定：`KoolCanvasFrameRenderer.kt:1991`
  `val ordered = frameProjectionDepth == 0 && orderingBarrier`；
  文本/图元路径同理（`:2823`、`:2930`、`:3148`，由 `requiresOrderedTextBatch()` /
  `requiresOrderedPrimitiveBatch()` 决定）。
- 引擎侧迷雾是**按 layer、按格子烘进区域渲染**的：
  `LayerBufferManager.java:561-612`，`groundLayer / groundDetailsLayer / groundDetails2Layer /
  mapLayer` 各以 `tileMap.isFogDisplayEnabled()` 为参数调用 `renderLayerRegion(..., fogDisplay, ...)`。
- 迷雾叠层要求有序 alpha ⇒ 置起 `orderingBarrier` ⇒ 纹理绘制 `ordered = true` ⇒
  **网格键带上全局 `batchIndex`**（`keyBatchIndex = batchIndex.takeIf { ordered }`）。
  内容一变（迷雾刷新、连续缩放）批次序号整体错位，键全部失效——这正是
  4.1p 测到的"每次重放新建约 907 个网格（`cached ≈ 保留窗口 × 批次数`）"。

因此修法**收敛为不改地图语义的第 ② 条**：迷雾继续烘进格子（与原版一致），只把网格键里的
**全局批次序号**换成**按材质的序号**，绘制顺序继续由 `placeBatchNode` 设置的
`drawGroupId = batchIndex + 1` 承担。补丁步骤与验证标准见 4.1p 末尾。

### 4.1r 结构性天花板与默认启用阻塞点（第 35–41 轮）

**已落地并验证的三处键稳定性改动**（第 35–37 轮）：网格键里的三个全局量
（`batchIndex`、`activeOrderingSegment`、`activeFrameProjectionBatchId`）改为**按材质的批次序号**
（新增 `materialBatchOrdinals`，每次重放清空；`placeBatchNode` 仍用全局序号写 `drawGroupId`，绘制顺序语义不变）。
每步都以像素 oracle 0/768 失配为门禁，并以雾开/雾关双配置单跑验证：

| 迷雾开启 | 原始 | 第 36 轮 | 第 37 轮 |
|---|---|---|---|
| newFps | 101.9 / 95.8 | 149.4 / 149.4 | 148.7 / 147.6 |
| 新画面 P99 | 112.9 / 144.9 ms | 60.6 / 54.1 ms | 53.3 / 62.5 ms |

**天花板是结构性的**：一次格子重放约有 **737 个批次运行**（同一材质因迷雾交错重复约 15 次），
而 Kool 的 `drawGroupId` 是**网格级**属性、一网格 = 一绘制组 = 一次绘制调用，故一组一网格。
评估过的"合并不透明批次"**不安全**：`Source`（blend 关闭）是**覆盖写**，两个重叠的不透明批次合并为同一
绘制组后相对顺序未定义，而 oracle 的矩形互不重叠、抓不到该回归；引擎的 `requiresOrderingBarrier`
只声明 alpha 顺序无关，不含覆盖顺序。因此进一步下降只能靠 Kool 层"一网格多绘制区间"改造，属独立立项。

**默认启用的阻塞点（实测暴露，勿直接翻开关）**：把 `gpuRenderTargetsEnabled` 的兜底改为 `true` 后，
`KoolCanvasSceneHostFailureTest` 的两项立即失败——**无 kool 上下文/失败路径的宿主会被卷进 GPU 目标初始化**
（预建 pass 池并挂载）。同时 `KoolGraphicsEngine.kt:2924` 的 `supportsGpuRenderTargets` 就是该开关本身
（`supportsGpuRenderTargets = GPU_RENDER_TARGETS_ENABLED`），所以用它做门禁是循环论证；需要一个
**独立的后端在场信号**。开关因此保持默认关闭，函数处已留注释指向本节。

**最终对照（同机、同判据、均经完整 A B B A 校验）**：

| 指标 | 原版 PC（探针） | GPU 格子·雾关 | GPU 格子·雾开 |
|---|---|---|---|
| 新画面 / 秒 | 253 | 215.4 | 148.6 |
| 新画面 P99 | 8.14 ms | **16.98 ms**（基线 178.20） | **60.80 ms**（基线 196.42） |
| >100 ms 冻结 | 15 | **0**（基线 76.5） | **29.5**（基线 74.5） |
| 相对基线 | — | P99 −90%、新画面 +55% | P99 −69%、新画面 +22% |

注：候选侧"呈现调用间隔"确实更差（雾关 p95 6.54 vs 5.53 ms），但那是**呈现更少、每次皆新**的结果
（`repeatRatio` 0.42–0.50 → 0.03–0.04）；用户可感的"画面停止更新时长"两套配置都大幅改善。

### 4.2 已通过的静态与单元验证

- `:core:classes`、`:desktop:classes` 编译通过；`:core:test` 318 项中仅 1 项失败且与本改动无关
  （`ReplayTimelineIndexTest` 读取仓库 `replays/` 夹具）；`:desktop:test` 通过。
  （`ReplayTimelineIndexTest` 读取仓库 `replays/` 夹具）；`:desktop:test` 通过。
- 新增 `KoolCanvasGpuTargetTest`（4 项）覆盖：GPU 目标提交不产生 CPU 像素也不发布可重放 frame；
  录制内容以 `GpuTarget(logicalId, size, frame)` 到达 install 钩子；父 frame 引用的是内容版本 id；
  内容不变复用同一版本、多个租约只安装一次、最后一个租约才释放；被取代的版本仍是可采样纹理、
  且随自己的租约分别释放；Linear/Nearest 采样视图选择与附件图像缺失时回落到占位纹理；
  无渲染线程所有者时的 CPU 兜底（产生正确像素）。
- 原有 `KoolGeneratedTargetInitializationTest` 遍历 `RenderTargetMode.entries`，现在同样覆盖
  `GPU_TARGET` 的提交契约（无渲染线程所有者时与 `IMMEDIATE` 一致）。

实现过程中修掉的一个真实缺陷：`flushTargetTextureFrame` 的**空提交**分支原先无条件调用
`registerFrame(id, previous.frame)`，对 GPU 目标会把已发布的、可采样的版本换回成可重放 frame，
从而把渲染器推回"每个采样点展开命令树"的路径。该分支现在对 GPU 目标直接返回，保持版本不变，
并由 `KoolCanvasGpuTargetTest` 的"被取代版本"用例锁定。

### 4.3 仍未验证

1. ~~像素一致性~~ **已通过**（见 4.1d）：两个内容版本各 768 texel、0 失配、`maxChannelDelta=1`，
   覆盖清屏、Source/SourceOver、纹理采样与 Y 朝向。注意它验证的是 32×24 的受控目标，
   不等于真实对局里 512² 格子的逐像素验收。
2. **性能 A/B**：未跑通同包 A B B A（工具自带校验要求 GPU 计数诊断，见 4.1e），因此**不能**声称卡顿已解决。
3. **有界内存**：slot 池上限 12 个空闲 pass（512² RGBA 约 12 MiB），且每版本渲染 4 帧，需长跑观察。
4. **地图语义边界**：迷雾、焦痕、回放视野、`renderFancyWater`、格子尺寸变化未逐项看。

### 4.4 状态更新（2026-10，性能 A/B 已完成）

4.3 的第 2 条「性能 A/B 未跑通，因此不能声称卡顿已解决」**现在可以推进了**，结论如下
（全部实测，方法见 [original-benchmark.md](original-benchmark.md) §6.7 第 70–84 轮）：

1. **默认值已是开启**。`KoolGraphicsEngine.gpuRenderTargetsEnabled` 的兜底返回 `true`，
   所以 `--gpu-map-cell-cache` 是**空操作**；真正的对照臂是 `RWX_GPU_MAP_CELL_TARGETS=0`。
   harness 新增 `--cpu-cell-raster` 作为该对照（`map_pan_replay.py`）。
2. **GPU 路径远优于 CPU 路径，且只在交互时体现**：
   静止时两者相当（209–210 vs 217–221 /秒），
   **交互时 GPU 路径 93.5–118.1 /秒、P99 54–74 ms，CPU 路径 7.4–12.3 /秒、P99 537–550 ms**。
   所以这一项**不是**当前的短板，格子路径选型可以结案。
3. **卡顿尚未解决，而且瓶颈已换了位置**。与原版同机同协议的配对测量：

   | | 静止 | 交互 | 配对退化比 |
   |---|---|---|---|
   | 原版 | 249.6–255.6 /秒 | 249.2–254.6 /秒 | **1.002×**（IQR 0.995–1.005） |
   | RWX | 205.5–210.2 /秒 | 123.9–153.2 /秒 | **1.400×**（IQR 1.399–1.555） |

   **原版交互时零退化**（它靠 Skia 做格子重建，见 §1），而 RWX 掉 30%。

4. **瓶颈是「每帧重生成的格子数」，不是格子内部的光栅化成本**。
   同一场景下静止与交互的确定性计数：

   | 指标 | 静止 | 交互 | 倍数 |
   |---|---|---|---|
   | `reset` | 2 | 139 | ×70 |
   | `cell`（重生成的格子） | 131 | **7,189** | ×55 |
   | **格子重生成速率** | **≈1 /秒** | **≈78 /秒** | **×78** |

   交互期失效的触因分解：**`zoom-enlarge` 88 次（63%）**、`pan-outside-grid` 22、
   `zoom-unit-scale` 22、`zoom-time` 5。

5. **格子尺寸曲线证明成本由「每格固定开销」主导**（每格都有 pass / descriptor / fence / 合成 draw call）：

   | 格子 | 交互吞吐 | 配对退化比 |
   |---|---|---|
   | 256 | 33.6 /秒 | 6.278× |
   | 384 | 129.8 /秒 | 1.682× |
   | **512（当前）** | **149.0 /秒** | **1.400×** |

   若成本由像素面积主导，256 应更省（覆盖同屏的总像素约为 512 的一半）；
   实测反了 4.4 倍 ⇒ **格数随边长平方增长，其固定开销压过省下的像素**。
   由此可加一条实验旋钮：`--cell-pixels {256,384,512}`（`-Drwx.koolLayerBufferPixels`）。

6. **因此后续方向（按 §5 的取舍标准记录）**：
   **降低镜头移动/缩放时被重生成的格子数**（当前 ≈78 /秒），而不是缩格子、也不是削格子内部像素。
   最容易入手的是占 63% 的 `zoom-enlarge`（`renderScale` 跳变 > 0.1 就作废整格重铺）。

   **动手前必须解决的约束**：不能靠「干脆不重铺」降计数 —— 那属于 §4.3 第 4 条的语义边界。
   判据必须同时覆盖 **(i) `cell` 计数、(ii) 配对退化比、(iii) 缩放时的画面新鲜度**。
   而 (iii) 的**像素级验证在本会话不可用**：截图工具对 Vulkan 窗口取不到内容
   （`PrintWindow` 返回全黑，整屏采集只能拿到被遮挡后的桌面）。
   **所以先补齐 (iii) 的验证手段，再动 `zoom-enlarge`。**

7. **补齐 (iii) 的具体做法（已勘察，可直接实施）**：
   不需要截图窗口 —— **把画面内容从引擎里读出来即可**，这正是既有 oracle 的做法。

   **基座**：`desktop/src/main/kotlin/io/github/rwx/kool/vulkan/KoolCanvasGpuMapCellOracle.kt`（490 行）
   已经具备所需全部要素：

   - `createContext(...)` 起一个真实 Vulkan 后端；
   - `KoolCanvasCpuTextureStore()` + `KoolCanvasSceneHost(...)` + `host.createScene()`；
   - `KoolGraphicsEngine(textureStore = store)`，`root.b(WIDTH, HEIGHT, true)` 建目标，
     `root.b(target, RenderTargetMode.GPU_TARGET)` 建**真正的 GPU 离屏格子**；
   - 逐帧驱动并用 `Content(clear=..., topBand=...)` 这样的**色带图案**做断言，
     读回后统计 `mismatches` / `maxChannelDelta`。

   **要新增的用例**（建议命名 `runZoomFreshnessOracle`，与 `runGpuMapCellOracle` 并列注册）：

   1. 用固定图案渲染一格（版本 A），记录读回的像素摘要；
   2. **改变 `renderScale`**（等价于缩放越过 §4.4 第 6 条的 `zoom-enlarge` 阈值，
      直接驱动 `LayerBufferManager` 或改 Kool 侧对应状态）；
   3. 重新渲染同一格（版本 B）；
   4. **断言版本 B 的像素摘要等于「按新比例渲染」的期望值**，
      而不是版本 A 的残留 —— 这正是「缩放后画面是否过期」的判定；
   5. 另加一条**边界用例**：`renderScale` 恰好等于 1.0（`zoom-unit-scale` 路径）。

   **判据**：`mismatches = 0/N`。这样 `zoom-enlarge` 的任何改动都能在**无窗口、无前台**的前提下被
   客观判定，与门禁里已有的两个 oracle 同一形态（`runGpuMapCellOracle` 1537 项、
   `runBgraSamplingOracle` 7 项）。

   **这应当先于任何格子重铺策略的改动完成** —— 否则改动只能靠 `cell` 计数单侧判断，
   而「计数下降但画面过期」正是这类优化最容易犯的错。

8. **一条已实测的结构性约束：`renderScale` 必须跟随 zoom，不能钉住。**
   `renderScale` 不只是「缓存分辨率」，它同时决定
   `cellWorldExtent = cellBufferPixelSize / renderScale` 与滚动步长 `cellWorldStepSize`，
   也就是**世界坐标与格子网格之间的换算系数**。

   实测（`RWX_MAP_CELL_MIN_RENDER_SCALE=0.70` 把它在 0.35–0.70 区间内钉成常数）：

   | 指标 | 无下限 | 下限 0.70 |
   |---|---|---|
   | `reset` | 72 | **257** |
   | `cell` | 3,842 | **9,343** |
   | `zoom-enlarge` | 22 | **0**（如愿） |
   | **`pan-outside-grid`** | 24 | **254**（暴涨 10 倍） |
   | 交互帧率 | 132–136 /秒 | **16–21 /秒** |
   | P99 | 40–42 ms | **670–693 ms** |

   缩放驱动的重铺确实消失了，但**平移驱动的重铺涨了 10 倍** ——
   棚格与屏幕的对应关系与镜头缩放脱节后，平移会不断让格子「露出网格外」。

   **⇒ 不要把「固定 renderScale」当作减少重铺的手段**（包括按最大放大倍率设下限）。
   剩余可行方向只有：**(i) 降低每次重铺的成本**（每格固定开销）、
   或 **(ii) 只重铺真正需要的那部分格子，而不是整批**。

9. **(ii) 的具体位置与省法（已勘察，待 oracle 就绪后实施）**：
   `LayerBufferManager.updateGridParams` 在每次 `reset` 时无条件把整张网格标脏
   （`:290–296` 的双重循环 `needsRedraw = true; preRendered = false;`），
   这就是「一次 reset ⇒ ≈21 个格子重生成」的来源。

   可省之处在于：`reset` 由 `renderScale` 变化触发时，
   格子纹理的**物理尺寸没变**（`cellBufferPixelSize` 固定 512），
   变的是与 `cellWorldExtent` 之间的**比例**。
   当比例变化在小容差内（PC 上 `f3 = 0.1`）时，
   旧像素内容仍表示**同一片世界区域、比例差 ≤10%** ——
   可以先只重算位置与合成比例，只对超出容差的部分真正重画。

   佐证：`computeRenderScale()` 已经把 ≤1% 的差异归一到 1.0
   （`if (Utility.abs(f2 - 1.0f) < 0.01f) f2 = 1.0f;`），
   说明「小比例差异不值得重铺」这个判断代码里已存在，只是容差极小。

   **前置条件**：正确性完全落在「画面是否过期」上，必须先用第 7 条的
   `runZoomFreshnessOracle` 确认「按约 1.1× 比例复用旧格子」在容差内，
   再改这个双重循环。**不要用 `cell` 计数下降来当作这个改动的成功判据** ——
   画面过期时该计数也一定下降。

   **⚠️ 更正（同日实测）**：上面「按容差分批评脏」的设想**已被否证**。
   在 `MapCacheExperimentalCellSizeTest`（纯单元测试，秒级）里测量 `dirty` 与 `anchorMoved`：

   ```
   size=512 zoom=0.35->0.40  grid=36 dirty=36 anchorMoved=36
   size=512 zoom=0.65->0.70  grid=36 dirty=36 anchorMoved=36
   size=256 / 384 同样：dirty == anchorMoved == grid
   ```

   `updateGridParams` 会重算 `gridOriginWorld*` 并把视角取整到 20 世界单位，
   因此**每个格子的世界锚点都会移动** —— 没有格子能因为「锚点没变」而豁免。

   **而且不是整体平移，是按新步长重新铺开**：记录每个格子的位移集合后可见其构成等差数列，
   公差恰为 `stepBefore - stepAfter`：

   ```
   size=512 zoom=0.35->0.40  dX=[80, -101, -282, -463, -644, -825]  step=1451->1270
                                相邻项差 = -181 = 1270 - 1451
   ```

   即 `Δ_i ≈ Δ_origin + i · (stepBefore - stepAfter)`。
   旧内容确实不再对应新位置，所以 `anchorMoved == dirty` 是**真信号**。

   **真正可省的在另一层**：重铺的原因是 `renderScale` 变了，而它改的是
   「每世界单位的像素数」，不是「这片格子覆盖哪块地形」。
   所以能省的不是「哪些格子要重画」，而是**「重画时是否必须按新比例重新光栅化」** ——
   保留旧像素、只按新比例重新合成。缩放 0.35→0.40 时比例只差 1.14×，
   且 `cellWorldStepSize` 1451→1270 也在 `f3 = 0.1` 所允许的步长内。
   上面那个等差数列**正好可用于算出每个格子的新矩形**，无需几何比对。

   这需要动**合成阶段的比例重采样**，不是标脏策略；前置条件仍是第 7 条的 oracle。

   **同一批实验里被否证的其他方向**（不要再走）：缩小格子（交互吞吐崩 4.4 倍）、
   放宽 `zoom-enlarge` 阈值（节拍已主导，无效）、固定 `renderScale`（平移重铺暴涨 10 倍）。

## 5. 后续

> **2026-10 更新**：本节第 1–3 条已完成，结论见 §4.4。剩余方向已改为
> 「降低每帧重生成的格子数」，并列出其前置验证约束。以下保留原文作为当时的计划记录。

1. 加 `KoolCanvasGpuMapCellOracle`（真实 Vulkan 离屏 pass + 读回对照）。
2. 用既有欧洲回放连续缩放流程做同包 A/B，包含 `RWX_GPU_MAP_CELL_TARGETS=0/1`。
3. 对照通过后再评估把默认值改为开启，并把实测数字补回本文与
   [Windows 拖图验证数据](verification/windows-map-pan-optimization-2026-10-04.json) 同级的记录。
4. 若像素对照发现 TileAtlas 仍是 CPU 目标导致格子内部还残留 CPU 相关工作，再单独评估图集；
   图集命令数只增不减，属于 §11.4 明确警告过的那一类，不要顺手一起切。
