# 桌面端卡顿优化说明

2026 年 10 月 4 日的 Windows 快速拖图、内存池、文字网格及等待策略记录见
[Windows 快速拖图优化实现记录](windows-map-pan-optimization.md)。本文保留较早的测试数据与架构演进；
涉及游戏循环内联于 Kool 回调的描述属于历史阶段，当前使用独立引擎所有者线程。

本文记录 RWXX Linux 桌面端从「选中圈卡、操作不跟手」到「约 2000 单位仍能稳住 60fps」的改动。目标是：**只改显示和平台层，仿真与原版铁锈 bit-exact，可以联机。**

约束见 `.cursor/rules/rw-compatibility.mdc`：不改确定性仿真、网络包、存档/回放、地图模组解析结果、校验和、游戏规则。`core/src/main/java/com/corrodinggames/rts/**` 里凡走逻辑路径的行为必须与 `/home/wty/code/TuanHun/铁锈代码` 一致。性能工作放在显示与平台层（`desktop/.../kool` 与纯显示代码）。旧的 `desktop/.../slick` 与 `slick2d-lwjgl3` 已删除，见第 11 节；下文提到它们的段落是当时的实测记录。

## 1. 问题是怎么定位的

用 JFR 在游戏线程上采样，卡顿分三层，不是单一热点。

| 阶段 | 体感 | 主要证据 |
|---|---|---|
| 选中单位就卡 | 圈和精灵各画一次就 `glBegin` | `drawArc` → `glVertex2f` 约占游戏线程样本 42% |
| 圈和批处理后操作发黏 | 点一下要等十几毫秒才动 | JAWT/AWT 锁等待约 5856 次、平均约 19ms。Kool 覆盖层 `swapInterval=1` 在锁里等到垂直同步（约 17ms） |
| 800～2000 单位开始掉帧 | 画面还能动，但帧间隔顶满 16.6ms | 更新约 42%、绘制约 44%。绘制侧是队伍着色 shader 和阴影/身体换贴图把批次冲成「每次 4 个顶点」 |

对局里 Kool 覆盖层藏起来之后，锁等待从约 12ms 降到约 0.06ms；小地图上回放能到约 300fps。说明锁和呈现才是跟手问题，不是仿真算错。

## 2. 帧预算

60fps 要求 **update + draw ≤ 16.6ms**。桌面循环拆成：等画布锁、游戏工作（update + draw）、swap、帧同步。环境变量 `RWX_PERF_LOG` 按这个拆分打日志（见第 7 节）。

沙盒回放 `[sandbox]Crossing Large (10p)` 约 2450 单位时：

| 版本 | 帧率 | 每帧工作 | 绘制 | 更新 | 锁等待 |
|---|---|---|---|---|---|
| 优化前 | 30–37 | 17–25ms | 13–17ms | 5–10ms | 5–11ms |
| 锁/节奏 + 裁剪/快路径 | 63–67 | ~15ms | 10–11ms | ~4ms | ~0.4ms |
| 多贴图批次 + 队伍着色进批次 | 120–150 | 5–8ms | ~4ms | 2–4ms | ~0.2ms |

最后一版 p50 帧间隔大约 6ms，相对 16.6ms 预算有余量。回放校验和与基线一致（Crossing Large 4199 tick、水吊 2999 tick、小块地 1824 tick）。

## 3. 显示层：少画、合成批

### 3.1 选择圈不再用 50 段 immediate 椭圆

原 Slick `drawOval` 每圈约 50 段 `glVertex2f`。选中一大团单位时，圈比精灵还贵。

- `Graphics.drawEllipseOutlineBatched` 把闭合折线推进 `QuadBatch`
- `SlickGraphicsEngine` 按屏幕半径把段数收到 12～50

只改怎么画，不改哪些单位算被选中。

### 3.2 `QuadBatch`：同状态几何一次提交（旧 Slick 路径，文件已删除）

文件：`slick2d-lwjgl3/.../QuadBatch.java`。

早期是每个精灵、每段线一次 `glBegin`/`glEnd`。批次收集带颜色的顶点，相同 GL 状态用一次 `glDrawArrays`。改颜色不拆批；改裁剪、FBO、读回、立即模式绘制必须先 `flush()`。`Graphics.setCurrent` / `predraw` / 释放贴图时都会冲批。

贴图切换时只 `submit()`（画出当前顶点、客户端数组保持开着），不要每次都拆掉数组状态。真正回到 Slick 立即模式时才 `flush()` 并 `TextureImpl.unbind()`。

### 3.3 屏幕外裁剪和普通精灵快路径（旧 Slick 路径，文件已删除）

文件：`desktop/.../slick/SlickGraphicsEngine.kt`。

- 变换后的目标矩形在视口外则直接返回
- 没有自定义 shader、没有特殊混合时走纯贴图四边形，不经过 `withSlickShader`
- `applyClip` / `j()`：裁剪矩形没变就不重设 clip（重设 clip 会冲掉精灵批）

### 3.4 多贴图批次 + 桌面侧队伍着色

2000 单位团的剩余绘制成本来自**每个单位两次冲批**：

1. 阴影一张贴图、身体一张贴图，交替提交 4 个顶点
2. 身体是 `TeamColorTexture`，走 `TeamColorShader`；阴影没有。开关 shader 再冲一次

不能把「先全部阴影、再全部身体」重排，否则后面单位的影子会画到前面单位身体下面，和原版不一致。

做法：`QuadBatch` 一次 draw 最多绑 8 张贴图，顶点里带贴图下标和队伍着色模式。片元逻辑与 `assets/shaders/pureGreenTeamColor.frag`、`hueAddTeamColor.frag`、`hueShiftTeamColor.frag` 对齐。`SlickGraphicsEngine` 对 `TeamColorShader` 不再走 `withSlickShader` 逐精灵上传 uniform，而是把队伍色写进顶点。

同队一团单位（阴影 + 身体 + 炮塔，通常 2～3 张贴图）可以合成很少几次 `glDrawArrays`。绘制从约 11ms 降到约 4ms。

这是显示替换，不改 `TeamColorShader.java` 给仿真或原版客户端用的路径。

## 4. 平台层：别在 AWT 锁里等垂直同步

桌面是双画布：Kool 透明覆盖层 + Slick 游戏画布，共用 X11 JAWT/AWT 锁。谁拿着锁谁就能卡住另一边的输入和绘制。

### 4.1 Kool 覆盖层去掉 vsync、自己控节奏

文件：`PacedSwingWindowSubsystem.kt`、`SwingKoolHost.kt`。

Kool 原来 `swapInterval=1`，在 `KoolGlCanvas.render()` 里等垂直同步，整段持锁约 16～17ms。Slick 的 `syncFrame` 也曾在锁里。结果是操作发黏。

- Kool 和 Slick 都 `swapInterval=0`
- `PacedSwingWindowSubsystem` 在 EDT 上跑 Kool 帧，**等下一帧的 sleep 在锁外**
- Slick `syncFrame` 也移到锁外（`EmbeddedSlickGameContainer`）

锁等待从平均约 19ms 降到接近 0。体感是「操作跟手了」。

### 4.2 对局隐藏覆盖层时不 present

对局里如果没有 HUD 覆盖层，Kool 窗口是隐藏的。XWayland 上对隐藏窗口 `swap` 即使 interval=0 也会阻塞到超时，并且一直占着 AWT 锁。

隐藏时走 `renderWithoutPresenting`：Kool 仍跑一帧（UI 状态、协程、会话），画进后缓冲，**不 swap**。锁占用从约 12ms 降到约 0.06ms。

## 5. UI 文本（显示缓存）

- `TextUtils.wrapLines`：按 `(文本, 字号 bits, 行宽)` LRU 256
- 字体按码点加载，不再用 30 条字符串的 LRU 整串缓存

不改变排版结果，只避免每帧重复测宽和加载字形。

## 6. 仿真：唯一动过的逻辑热点

文件：`UnitSpatialIndex.querySoftCollisionCandidates`，由 `OrderableUnit.updateAllUnitCollisions` 调用。

原 `b()` 把格子里所有单位丢进列表，再在 `resolveSoftCollisionWithUnit` 开头丢掉自己、碰撞组不匹配、互为 target 的单位。格子遍历顺序和插入顺序必须保持，否则软碰撞结果会变。

预过滤是 `b()` 的拷贝，只是在**同一格子、同一插入顺序**下跳过原函数本来就会立刻 return 的单位。不改 `nearbyUnits` 的排序和写入。

约 2400 单位时 update 已经约 4ms。`CustomUnit.update` / `OrderableUnit.update` 没有再改：再动仿真风险大于收益。

## 7. 怎么验证「没改规则」

环境变量（只读游戏状态，默认关闭）：

| 变量 | 作用 |
|---|---|
| `RWX_PERF_LOG=1` 或文件路径 | 每约 5 秒一行：fps、单位数、lock/work/update/draw/swap/sync 的 avg/p50/p95 |
| `RWX_CHECKSUM_LOG=/path/file` | 每 tick 一行：原版 `GameStateChecksum` + 每个可命令单位浮点 bit 的更严 FNV |
| `--replay=` / `RWX_REPLAY_SPEED` | 窗口版自动播回放，方便对校验和 |
| `RWX_CANVAS_PERF=1` | 每 2 秒一行：Kool 画布重放（`KoolCanvasFrameRenderer.render`）的 avg/peak，见 11.5 |
| `RWX_DESKTOP_TARGET_FPS=N` | 覆盖设置的 `maxFrameRate`（兼容旧的 `RWX_SLICK_TARGET_FPS`），设很大等于关掉节流 |
| `RWX_TEXTURE_DEBUG=1` | Kool 画布每次 ARGB→RGBA 转换按纹理 id 计数，用来找"每帧重传哪张纹理" |

不需要窗口时用 `:desktop:headless`。它走同一条 `gameLoop`，不创建 OpenGL。`--checksum=` 和 `RWX_CHECKSUM_LOG` 写的是同一种文件。

```bash
./gradlew :desktop:headless --args='--replay=22.25.33 --ticks=3000 --checksum=build/replay.txt'
./gradlew :desktop:headless --args='--map="maps/skirmish/[p2]Small_Island (2p).tmx" --ticks=600 --checksum=build/map.txt'
```

- `--replay=` 可以用文件名里一段不重复的文字，和窗口版一样。
- `--map=` 走单人地图加载，必须带 `--ticks=N`。回放省略 `--ticks` 时播到结束。
- `--args` 里如果参数含空格，再加一层引号。
- 正常结束退出码 0；加载失败 1；回放读到一半出错 2。
- 已安装的 RWXX 也可以：`--headless --replay=...`。完全退出再启动后才会用到新 jar。

回放必须和优化前 jar 的 checksum 文件逐 tick 比对。沙盒 Crossing Large、水吊、小块地均与基线 IDENTICAL。同一段回放的前 30 tick，无头和窗口版校验和逐行相同。

不要用 16p Mc 劫掠那类回放当 2000 单位基准：它会卡在 tick 1～3（寻路/对话框/等待），测不到团战。

## 8. 中文输入（优化后的回归）

不是性能项，但是同一批桌面窗口改动带出来的。

中文输入法必须挂在**主 `JFrame` 里那个 1×1 隐藏 `JTextField`** 上。Kool 覆盖层是透明 `JWindow`，输入法不会挂上去。覆盖层 `toFront()`、对局里把焦点还给游戏画布，都会打断拼音组字，结果只剩字母。

处理：输入时覆盖层只负责画面（`focusableWindowState=false`），游戏画布在 `PlatformTextInputBridge.isEditing()` 期间不再抢焦点。输入法组字仍不进 Kool 文本（带 `ComposedTextAttribute` 的标记文本会剥掉）。

## 9. 文件对照

| 区域 | 主要文件 |
|---|---|
| 变换、裁剪、快路径、队伍色入口 | `core/.../render/canvas/KoolGraphicsEngine.kt` |
| 贴图注册与像素外扩 | `core/.../render/canvas/KoolCanvasResources.kt` |
| Kool 节奏、隐藏不 present | `PacedSwingWindowSubsystem.kt`、`SwingKoolHost.kt` |
| Kool 帧循环与游戏循环内联 | `desktop/.../kool/KoolDesktopGameSession.kt` |
| 帧时间日志 | `desktop/.../kool/KoolFrameTimeLog.kt` |
| 校验和 | `core/.../diagnostics/GameStateTrace.kt` |
| 无头仿真 | `desktop/.../HeadlessMain.kt`、`headless/HeadlessGameSession.kt`、`headless/HeadlessGraphicsEngine.kt` |
| 碰撞预过滤 | `UnitSpatialIndex.java`、`OrderableUnit.updateAllUnitCollisions` |
| 折行缓存 | `core/.../ui/TextUtils.java` |
| IME | `DesktopTextInputController.kt`、`SwingKoolHost.kt` |

> 上表已去掉旧的 Slick 实现（`slick2d-lwjgl3/.../QuadBatch.java`、`desktop/.../slick/SlickGraphicsEngine.kt`、
> `EmbeddedSlickGameContainer.kt`、`SlickFrameTimeLog.kt`、`SlickCanvasHost.kt`）：这些文件连同整条 Slick 渲染路径
> 已删除，见第 11 节。

## 10. 明确没做的事

- 没有改单位 AI、伤害、寻路结果、命令顺序
- 没有改网络协议或包内容
- 没有把影子改成「先画完所有影子再画身体」（观感会和原版不一致）
- 没有为了快而改 `CustomUnit.update` 控制流
- 没有重打完整发行 zip；只替换桌面 jar

联机仍应与原版铁锈互为主机各测一轮 1000+ 单位房间。仿真路径未改，但最终以实机联机为准。

## 11. Kool 桌面渲染器：唯一的桌面渲染路径

`desktop` 现在只有一条渲染路径：`KoolDesktopGameSession` 在 Kool 渲染循环里内联跑 `gameLoop`，把
`KoolGraphicsEngine.snapshot()` 交给 Kool 画布，`SwingKoolHost` 提供唯一的窗口表面。macOS 走 Kool 的
Vulkan 后端（LWJGL 自带的 `libMoltenVK.dylib`，即 MoltenVK → Metal）；Windows 默认使用原生 Vulkan，Linux 默认使用 Kool 的 OpenGL 后端。可用 `RWX_KOOL_BACKEND=opengl` 显式选择 OpenGL。

历史上还有第二条路径，现已全部删除（`desktop/.../slick`、`slick2d-lwjgl3` 模块以及 `-Drwx.desktop.renderer` 开关）：

- macOS 的 OpenGL 已废弃，Apple 的实现本身就是 Metal 之上的一层垫片（崩溃栈里的 `AppleMetalOpenGLRenderer`）。
  那条路径里 Slick 画布是离屏 CGL FBO，每帧 `glReadPixels` 整帧回读再上传给 Kool 覆盖层，
  `hs_err` 已经崩在 `gldReadFramebufferData` 里；3800×1782 时这一趟 CPU 拷贝本身也要几毫秒。
- Linux / Windows 上它是「Slick AWT 画布 + Kool 分层覆盖窗」的双窗口结构：实测整屏纯黑（可见画面全部来自
  覆盖层，Slick 画布没有可见输出），并且会破坏进程堆，最终以 `0xC0000374`（`STATUS_HEAP_CORRUPTION`）被系统终止。
- 旧路径用到的 OGG 解码（`jogg` / `jorbis`）保留下来了，现在直接放在 `desktop/libs`。

没有可直接切换的 Metal 后端：LWJGL 3.3.6 没有 Metal 绑定，Kool 0.19.0 JVM 只有 `gl` / `vk` 两个后端，
而 macOS 上 Kool 的 GL 后端根本起不来（要求 3.3，系统给 2.1）。所以在 macOS 上只能落到 Vulkan。

- 因此进程里不再有 `OpenGL.framework` / `GLEngine` / `AppleMetalOpenGLRenderer`，也没有逐帧回读。

### 11.1 必须让「alpha 外扩」按需执行

第一次实测只有约 11.5fps（p50）。JFR 采样显示热点全在
`KoolGraphicsEngine.registerTexturePixels → KoolCanvasTextureRegistry.registerArgb → bleedTransparentRgb`：
图层缓冲/迷雾叠加这类**后端自己生成的纹理每帧都会重新注册**，而每次注册都对 3800×1782
做一遍 O(pixels) 的洪水填充外扩，加上克隆与打包，单帧要几百毫秒。

处理：外扩只对**解码出来的图片**有意义（它们透明像素的 RGB 是任意的，线性过滤会把黑边渗进精灵边缘），
后端生成的像素不需要。于是：

- `Texture.alphaBleedRequired`（显示用标志，`clone` / `a(width,height,copyPixels)` 会继承）
- `KoolCanvasTextureRegistry.registerArgb(..., alphaBleed = false)`：不复制、直接接管调用方数组
- 只有 `KoolGraphicsEngine.createLegacyTexture(argbPixels != null)`（解码图片）置 `true`

### 11.2 实测（MacBook Pro M 系列，`--replay=双桥`，3800×1782）

| 版本 | p50 帧率 | p90 | OpenGL 框架 |
|---|---|---|---|
| Kool 渲染器（外扩未按需） | 11.5 | 12.8 | 未加载 |
| Kool 渲染器（本提交） | 56.5 | 59.9 | 未加载 |
| Slick 画布 + 逐帧回读（旧 macOS 路径） | 约 24 | — | `AppleMetalOpenGLRenderer` |

回放 checksum 全程 matching（仿真未改）；`lsof` 中只有 `libMoltenVK.dylib` 与 `AGXMetal*`。
启动/载图瞬间仍有 8fps 级别的掉帧，属于资源准备阶段。

### 11.3 帧率被"提交计数器"挡住：一次误判的脏检查

11.2 的 p50 56.5fps 是在 3800×1782 上测的，但同一台机器、同一个回放、1280×720 窗口下，Kool 路径
只有 34.8fps，而 Slick 画布是 119fps。用 `RWX_PERF_LOG`（Kool 侧埋点见 `KoolFrameTimeLog`）拆开看：
每帧游戏 work 只有 4.2ms，帧间隔却是 26.8ms——时间不在仿真/绘制，在 Kool 画布的纹理重上传。

JFR 采样给出了具体位置（steady-state，渲染线程）：

| 占比 | 方法 |
|---|---|
| 41.1% | `KoolCanvasTextureRegistry.argbImageData`（ARGB→RGBA 逐像素转换） |
| 11.2% | `HashMap.putVal`（注册表 map churn） |
| 7.6% | `de.fabmax.kool.pipeline.backend.vk.TextureLoaderVk.copyTextureData` |
| 6.1% | `java.nio.Bits.setMemory`（`IntArray` 克隆） |

分配侧 89.65% 的压力来自 `Texture.getArgbPixelsCopy()`，而临时埋点显示每帧重转的是 8 张
`legacy-texture-*` 512×512（图层缓冲格子），调用链固定为
`drawTexture → registerTexturePixels → registerArgb → refreshCachedArgbTextures → argbImageData`。

根因不在"图层缓冲每帧都变"，而在这行脏检查：

```kotlin
pixelRevision = (e * 31) + getPixelRevision()   // e 是提交计数器
```

`Texture.e` 会被 `flushPendingTarget` **每次提交**加一，于是格子即使一个像素都没改，
注册签名也每帧都变，触发一次 512×512 的转换 + 整张上传。改法：

- `Texture.pixelRegistration` 只用 `getPixelRevision()`（真正写像素才加：`setCommittedArgbPixels`
  / `p()` / `a(x,y,color)` / `v()`），提交计数器不再参与
- `KoolGraphicsEngine.flushTargetTextureFrame`：新光栅化的像素与上一提交逐字节相同时，跳过重注册
- `argbImageData` 的通用分支改成 `packArgbPixelsToRgba`（BIG_ENDIAN `asIntBuffer` +
  `Integer.rotateLeft`，一像素写一个 int），替掉每像素 4 次带 `getOrElse` 的逐字节写
- 上传缓冲对所有 ARGB 纹理按 `(id, filter, slot)` 复用两块 direct buffer
- `registerTexturePixels` 用新增的 `Texture.argbPixelsRef`（不 clone）
- Kool 的 Vulkan swapchain 不再用 vsync 当节流（`KoolConfigJvm.isVsync = false`），改由
  `Lwjgl3Context.maxFrameRate` 按设置的 `maxFrameRate` 节流（`desktopTargetFrameRate`，
  可用 `RWX_DESKTOP_TARGET_FPS` 覆盖）

实测（M5 MacBook Air，`--replay=双桥`，1280×720 窗口 = 2560×1440 像素，tick 400-2600 同窗口）：

| 版本 | fps 均值 | 帧间隔 p50 | 游戏 work p50 |
|---|---|---|---|
| Kool 渲染器（修复前） | 34.8 | 26.8ms | 4.16ms |
| 关掉 vsync 天花板 + 帧率节流 | 43.0 | 21.2ms | 3.87ms |
| 打包/复用上传缓冲/不 clone | 50.3 | 18.4ms | 1.36ms |
| 脏检查修复 | 95.2 | 10.4ms | 0.15ms |
| 同上，稳定段 | 111-117 | 8.34ms | 0.15ms |
| Slick 画布基线 | 119.1 | 8.33ms | 0.62ms |

`RWX_TEXTURE_DEBUG=1` 的逐 id 计数、JFR 记录与 `RWX_PERF_LOG` 原始输出留在 `/tmp/rwxperf/`。

### 11.4 为什么没有把 IMMEDIATE 目标改成 frame-backed（实测反而慢 40 倍）

11.3 之后还剩一个"理论上更干净"的做法：`RenderTargetMode.IMMEDIATE` 在 Kool 桌面后端意味着
CPU 位图（Android 的语义），图层缓冲/Fog 图集/小地图/瓦片图集这些"画完再采样"的目标于是每帧都走
CPU 光栅化 + 上传。把它们改成 Kool 的 frame-backed 目标（`DEFAULT`，把绘制命令记成 frame 由画布重放）
看起来能彻底去掉这条路径，于是加了一个后端能力开关试了一遍：

- `GraphicsBackendCapabilities.usesGpuRenderTargets`（默认 `false`，Android 保持 `IMMEDIATE`）
- Kool 后端置 `true`，十几处 `b(texture, RenderTargetMode.IMMEDIATE)` 改成按能力取模式

结果全量切换后 `--replay=双桥` 掉到 **3.4-5.0fps**（帧间隔 p50 199-293ms），而游戏 work 仍是
0.2-0.5ms。新加的 `RWX_CANVAS_PERF`（见 11.5）把账算清了：慢的不是 present、也不是遮挡节流，
而是画布重放本身——`KoolCanvasFrameRenderer.render` 每帧要 **235-317ms**。

**逐个单独切换都是好的**，所以这是组合效应：

| 切换成 frame-backed 的目标 | 画布重放 avg |
|---|---|
| 都不切（CPU + 脏检查，现状） | 1.0-1.4ms |
| 图层缓冲（bufferLayer + cell + fadeOut） | 2.5-2.9ms |
| 小地图三层 | 2.2-2.4ms |
| TileMap（fog 图集 + textureB） | 0.8-0.9ms |
| 瓦片图集（TileAtlasCache） | 0.9ms |
| 图层缓冲 + 小地图 | 3.5ms |
| 图层缓冲 + TileMap | 1.9ms |
| **图层缓冲 + 瓦片图集** | **196-228ms** |
| 全量 | 235-317ms |

原因是 frame 纹理的消费方式：`KoolCanvasFrameRenderer.addFrameTexture` 把 frame 里的命令
**投影展开**回场景，**每个引用点展开一次**。瓦片图集的 frame 里每个已分配槽位一条绘制命令
（`blitWithPadding`，最多 400 条，且只在建图时 clear，所以只增不减），而 6×6 个图层缓冲格子
每帧都要采样这个图集一次：36 × 数百条命令，每帧重新建 mesh，量级就上来了。
单独留着图集（0.9ms）或单独留着格子（2.5ms）都不成问题，是因为没有这个乘法。

结论：Kool 桌面路径继续用 CPU 目标 + 11.3 的脏检查。frame 目标只有在两个前提下才值得重估：

1. frame 改成真正的离屏渲染 pass（渲染一次、多处采样），而不是每个引用点展开命令树；
2. 只给"命令数有界、且采样点少"的目标用 frame（例如 post 图），不要给只增不减的图集用。

### 11.5 量不到的那一段：画布重放

`RWX_PERF_LOG` 的 work 只统计 gameLoop（update + draw），而 `KoolCanvasFrameRenderer.render`
跑在 Kool 的 scene render 里、在会话从 `updateFrame` 返回之后，两条日志都看不见它。
`RWX_CANVAS_PERF=1` 按 2 秒窗口打印 `canvas frames=N replay[avg=..ms peak=..ms]`，
11.4 的定位就是靠它把 200ms 从 present/遮挡的怀疑里摘出来的。

### 11.6 已知风险：游戏循环与 present 耦合

本节记录拆分前的历史风险。当前已完成引擎与呈现分离，见
[原版引擎与 Vulkan 渲染分离](vulkan-engine-separation.md)；下文“仍未做的部分”仅描述当时状态。

Kool 桌面路径把 gameLoop 内联在渲染回调里（`KoolDesktopGameSession.updateFrame` ← Kool
`onRender`），并且 Kool 的 swapchain 用无限超时等待（`vkWaitForFences(..., -1)`、
`vkAcquireNextImageKHR(..., -1, ...)`，见 kool-core 的 `Swapchain.acquireNextImage`）。因此
任何 present 侧阻塞（显示器休眠、surface 丢失、驱动挂起）都会**停住整个游戏循环**，而不是掉几帧画面；
`swing` 循环本身只看 `isMinimized`/`isVisible`，不判断遮挡。

实测补充：**遮挡和显示器休眠都不会**触发这个停。把游戏窗口用别的窗口完全盖住（CoreGraphics 覆盖度
1.000）后仍然 106-116fps；`pmset displaysleepnow` 睡 18 秒期间帧照跑（tick 604→1054，110-115fps），
swapchain 重建次数 0。也就是说这条风险在本机是"理论上会、实测触发不到"。

**已做的部分（a）**：`KoolVulkanOverlayPatchTask` 现在把
`Swapchain.acquireNextImage` 的 `vkAcquireNextImageKHR(..., -1, ...)` 超时改成 100ms，并在
`when` 里把 `VK_TIMEOUT`(2) 和 `VK_ERROR_OUT_OF_DATE_KHR` 归为同一支（返回 `false`）——
掉到超时不再抛 `IllegalStateException`，而是让 Kool 跳过该帧并重建 swapchain，游戏循环继续跑。
前面的 `vkWaitForFences(..., -1)` 故意保持无限：它等的是自己上一帧的提交，超时后复用可能仍在
in-flight 的缓冲更危险。

验证边界（照实说）：超时分支是**静态验证**的（javap 反汇编确认 `2 → iconst_0`、acquire 超时为
100000000l），正常运行零重建、无异常；但没法在这台机器上真正触发一次超时——遮挡、显示器休眠、
甚至把超时临时改成 0ns 都拿到 drawable（0 重建、0 异常、113fps），所以"真卡住时会怎样"还没有实机证据。

**仍未做的部分（b）**：把 gameLoop 从呈现循环里拆出来（Slick 那样离屏渲染 + 自己节流）。
另外 `recreateSwapchain` 里还有 `device.waitForIdle()`，GPU 真挂起时它同样会阻塞，这条没动。

### 11.7 Windows 默认 Vulkan 与通用批次优化（2026-10-03）

Windows 现在默认选择 Vulkan；macOS 继续使用 Vulkan / MoltenVK，Linux 继续默认 OpenGL。显式的
`RWX_KOOL_BACKEND=opengl` 或 `-Drwx.kool.backend=opengl` 仍可选择 OpenGL。最终 Windows 包已验证
无后端参数时实际使用 Vulkan，OpenGL 显式选择也完成了可见交换和新画面产生的启动烟测。

通用优化复用连续精灵批次的材质及网格查找结果，每批次设置一次图集纹理，并直接写入仿射坐标、
翻转 UV 和裁剪值，减少临时对象。命令顺序、队伍染色、透明度和算术顺序保持一致。
独立的 JFR / 画布诊断窗口中，2250 单位场景的画布重放加权平均从 **14.01 ms 降到 9.78 ms**，
约减少 **30.2%**。该诊断开启了额外采样，不能直接用它的帧率替代正式性能对照。

正式对照在 Intel Iris Xe / i5-1145G7 上进行，交流供电、1920×1080、4 倍 MSAA、关闭垂直同步、
300 帧上限，友军闲置综合混编且全部选择。每进程预热 30 秒，随后连续采样 90 秒。所有进程使用
同一份设置，实际存活数为 741 或 2250（请求 661 / 2000 时会产生附属单位）。以下均保留整段采样及异常帧：

| 场景 / 版本 | 新画面率（次/秒） | 接受呈现（次/秒） | 呈现间隔 p95 / p99（ms） |
|---|---:|---:|---:|
| 2250，旧版 | 61.70 | 63.55 | 21.62 / 24.14 |
| 2250，通用优化首轮 | 65.33 | 67.06 | 20.11 / 23.82 |
| 2250，Windows 同步候选 | 62.97 | 64.85 | 20.75 / 23.80 |
| 2250，通用优化复测 | 59.77 | 61.73 | 20.83 / 26.24 |
| 2250，最终包默认启动复测 | 67.08 | 69.01 | 19.61 / 22.70 |
| 741，旧版 | 121.57 | 125.77 | 10.96 / 12.42 |
| 741，最终包默认启动 | 107.31 | 111.00 | 10.79 / 14.16 |
| 741，旧版复测 | 96.15 | 100.85 | 13.63 / 15.74 |

2250 场景首轮和最终复测分别提高约 5.9% / 8.7%，但一次复测的中间窗口出现 256.9 ms p99，
741 新版本也出现长帧；同一旧包复测则下降约 20.9%。因此可以报告 CPU 重放开销减少，
**目前不能宣称整机帧率有稳定提升**。接受呈现和新画面率不等于 60 Hz 显示器的物理扫描帧率。

Windows 专用候选仅缩小纯顶点 / 索引缓冲的上传依赖，保留先前读取与连续 transfer 写入的依赖，
混合用途和其他平台仍用原同步。正式结果没有稳定优势，已撤回，最终包保留原有 Vulkan 同步。

兼容性验证中，优化前后 **1203 个 `com/corrodinggames/` 引擎类字节完全一致**。最终 42 项画布测试、
91 项桌面测试及 3 项 Python 分析测试通过；177 种单位变体的构造 / 受损 / 死亡 / 绘制命令烟测通过。
实际录制回放的 120 外层帧 / 123 tick / 24 命令完整状态对照通过，涵盖步长变化、暂停及倍速。

完整核心测试为 133 / 134 通过，剩余一项缺少本地回放样本。存档恢复对照及无 GPU 的会话相机
夹具也仍失败，旧包复现同样的初始状态差异 / 重设视口等待超时；本次没有修改这些夹具或原版逻辑。
不能据此宣称所有存档、网络互通和 GPU 图像等价场景都已验收。退出仍存在基线已有的保留资源清理警告。

完整原始指标、包 SHA256、所有重复采样及失败记录见
[Windows Vulkan 优化验证数据](verification/vulkan-optimization-windows-2026-10-03.json)。
复测命令和工具见 [Windows 后端对照工具](../desktop/tools/README.md#windows-后端及优化前后对照)。
