## 2026-11-05 发布交付内容：完整授权的1.4.3 macOS ARM64包，15项新默认及安装身份/踢出房间修复

用户明确要求“打包一个macos版，提交推送github”，并随后明确“纳入本次发布”安装身份和被踢出房间界面两组改动。
因此最终发布副本包含全部本批源码，不再排除下文历史选择中的11个文件；保留原工作区并在managed release副本构建。
gradle.properties与AppMetadata.VERSION_NAME均1.4.3，同批不再递增，原版联机/回放/协议兼容常数未改。
最终完整源码core535总/1skip、desktop159（nativeFullscreen明确排除）0fail；.github脚本15和desktop/tools130通过。
干净checkout暴露测速单测依赖未入库preferences，已仅修测试临时seed，真实测速配置校验不变，旧129tests/52errors摘要保留。
macOS app/ZIP与随包JVM JNI120tick/120checksums验收26门禁全过，native架构静态11库通过；未执行GUI launcher或声称公证。
ZIP SHA256 6598127e1359cf0a8ee8d6031b098f69aae0d57c964da6253a0378ffbda1d3be，162265359bytes。
最终corefe425254…f387、desktopac208249…cb44；全部326canvas+338desktop class与先前GPU/实景通过版本byteexact。
5个其他core class仅日志编译变体和外部InnerClasses synthetic元数据差别，独立保守CFG/序列化Code核对无执行/协议差异。
交付记录 docs/verification/release-macos-2026-11-05.json；包保留在原仓库build/release-20261105/artifacts/。
GitHub远端提交、tag与Release是后续发布状态的权威来源；安装应用、Steam和系统音量不改。
完整性能目标仍active，尚未证明稳定领先原版50%或拖缩零长帧，不能因本次发布而标完成。

---

## 2026-11-05 历史：用户要求将已证实有收益且兼容的改进全部默认开启；已落实15项，准备隔离发布1.4.3

用户明确“只要有优化的，不影响兼容性的改进，都默认开启，包括之前探索的结果”；不再以整体未追平原版
作为单项默认采纳门槛。新增默认ON（均env0回退）：UNION_OPAQUE_POINTS、LAZY_CANVAS_SIGNATURE、
CACHE_CANVAS_SHADER_TEMPLATES、REUSE_MAP_ATLAS_SCRATCH、GUARD_SHORT_RENDER_PARK、WAIT_FOR_FRESH_FRAME、
AVOID_TEXTURE_PAINT_COPIES、SHARE_IMMUTABLE_COMMANDS、REUSE_TEXT_MESH_KEYS、REUSE_TYPEFACE_KEYS、
PREPARE_CANVAS_TEXT、MAP_TEXTURE_BATCHES（既有RECT子开关随之生效）、MAP_FOG_BATCHES、
RESET_ONLY_ACTIVE_CANVAS_MESHES、SINGLE_MAP_CELL_RENDER。shader模板只共享CPU不可变描述，独立native pipeline/binding。
负收益/未证实/真实不兼容的候选继续OFF；clock integer-ms仍是独立兼容工作，默认未改。
详见 docs/verification/default-optimizations-2026-11-05.json，所有历史OFF建议与失败原始记录保留。

最终V3 core535总/1skip、desktop159（明确排除交互nativeFullscreen）0fail；显式全部回退0的39项通过。
V2三项失败是新map批次表示遇到旧测试强cast，已完整归档；测试改为比较完整按序raw floatbits/paint/state，
共享纹理仍single command且batch须singlequad，独立复核无断言削弱。新默认GPU Map3844/Fog4608/Text3072检查过，
CPU模板created3/hits76、single/reset真实true。Sprite148checks/608资源退休过，但v1 controller误要求未加载的template日志，
raw validfalse保留且未重跑，独立解释已落盘；继承readback默认1sample/容差3，不冒称实际4sample全exact。
opaque专用oracle另有明确4sample22pairs/77627RGBApixels exact0/44fence全部通过。

默认组合真实pair：同b357010e…6ef6b包、display38/create20，AI0/1x/初始10000、完整20s panzoom、1792×1008/MSAA4/cap300、
静音、实际inactive。control15flag全0，对defaults15键全部缺省：fresh118.239749→119.693924（+1.229853%）、
P9911.431167→9.754333ms、max56.632959→21.996667ms，>16.667ms11→2，独立93checks全过。
该较旧240Hz记录低的有效结果完整保留；仅一pair，不推断headroom/稳定性、不与旧原版计算倍率。
两边clock明确1仅保持测试条件，不能称产品clock默认已验证。create20已TERM/exit0/removedtrue/originalUnchangedtrue，无目标残留。

用户新增明确要求“打包一个macos版，提交推送github”，覆盖此前不提交约束。当前main/origin main为3e6e371，
原工作区有11文件无关安装ID/踢出房间UI改动，必须保留且不混入性能发布：AndroidPlatformBridge/DesktopPlatform/NetworkEngine、
InstallationIdentity及其core/desktop tests、App.kt/BattleRoomCloseController及test/BattleRoomLaunchControllerTest/MultiplayerExitStateTest。
PlatformBridge只有VERSION_NAME同步1.4.3，应包含；AI房规/静音/perf及测试工具/验证记录属于本批。
正在创建managed release-1-4-3-performance隔离副本，基于origin/main，仅拷本批改动重打包和验收，
不能把已含上述无关源码的b357包冒充发布commit产物。版本仍同批1.4.3，不再递增；/Applications和Steam不改。
完整性能目标仍active，尚未宣布稳定领先原版50%或拖缩零长帧。

---

## 2026-11-05 历史：driver ABBA无收益不采纳；实证小地图10000方点热点，精确opaque几何并集实现中

driver同步/异步已完成Sprite和map-cell GPU正确性，以及真实fullapp ABBA：
build/stress-scenes-20261105/driver-queue-motion-v1，fatf6e1…、display35/create17、AI0/1x/integer1/initial10000、
1792×1008/MSAA4/vsyncOFF/300、warm2/sample20完整panzoom，frame候选全0，唯一MVK_SYNC1/0。
pooled sync fresh241.406619/P9910.751625/max53.123000 vs async240.955991/10.824250/54.288625，
吞吐−0.1867%、两pair方向相反、tail交叉，不采纳async，不改driver默认。独立128checks全部通过，
raw global initial/shutdown mode1/0、native SHAe2ed/FFM+LWJGL vkGetInstanceProcAddr同地址/同文件都实际确认。
fullapp外部wrapperv2 sourceeb52a812…/class078e08c1…，所有arm初始先绑定VK native但不建instance/device，
原main(args)转发和stack512保持；shutdown重复initial binding metadata、global getter真读回，不代表backend已teardown。
create17已TERM正常exit0/removedtrue/originalUnchangedtrue，无game/JVM/GPU/manager存活。下一create18。
tracked报告 docs/verification/driver-queue-motion-2026-11-05.json；原版所有146.05/152.6/142.2保留，不挑分母。
driver Sprite两模式148checks/608retire，map-cell两模式3844checks/cold1/5×768pixels maxDelta1/fenced均过，
scope只是对应GPU正确性，不替代全游戏/跨平台/长期验收。

下一证据：whitepatch混合sprite/rect方案已否决，现有run表明98.85%root pass约5条untyped run，最长中位9612/P9910000；
DrawRect录制分配采样权重98.1%出自Minimap→LineDrawer，不是逐单位body/health/sprite交错，也不等于10000drawcalls。
证据 build/whitepatch-batching-review-20261105/analysis-v1.json (0e98a4f8…aaa1)。
真正可减少的是小地图同批大量重叠、同色、不透明整坐标方点的冗余几何，而非shader/material切换。

owner_default_package现在仅源码实现默认OFF RWX_UNION_OPAQUE_POINTS：KoolGraphicsEngine.drawPoints整批严格验证后，
对原rect(x,y,x+size,y+size)构有界整数覆盖并集，逐行非重叠spans，输出若>=输入点数一半则整批旧fallback。
只root/Identity singleton/clipnull/Generic/SourceOver/rgba alpha255且alphaMultiplier1/无effect/整数size1..8、
finite整数点且负零fallback、±2^20、viewport<=65536、至少64点、grid<=512²。保留原start/endInclusive奇异边界、
同批/队伍颜色顺序，不跨call融合；不丢单位点、不像素近似、不降频，unsupported完整旧行为。构造ABI不变。
nov4_evidence_review独立数学/Minimap alpha坐标/shader/opacity/MSAA审，并给真实GraphicsEngine→freeze→SceneHost
OFF/ON像素验证方案；两agent均禁止build/JVM/GPU，等source冻结交根执行。**此新候选尚未完成/构建/验收，不可采纳**。
产品仍1.4.3同批、默认静音、未commit/install/改Steam，完整目标稳定+50%且拖缩不掉帧继续active。

---

## 2026-11-05 rect四arm负收益不采纳；driver异步Sprite已过，地图与fullapp读回准备中

用户最近问“拖缩最长帧是否优于原版”“你做了什么优化”，已如实答：本轮same display34原版v3
max52.741625/P9917.068333/fresh142.2；rect关闭control两次39.633416/46.880500，P99 11.569625/11.460084，
所以本轮最长帧更短，但此前same-display32 RWXX出现65.9~70.4ms，不能说稳定优于或不掉帧。
默认已有owner累计deadline、frozen content reuse、共享纹理注册/map atlas像素、prepared map tiles；
最近packed/paint/rect实验全未采纳，不能把OFF轮次的好成绩归功于新候选。

rect真实ABBA已有效完成：build/stress-scenes-20261104/direct-rect-real-motion-v1/，fatf6e1ae59…76bba。
fresh238.492372/213.167801/235.081920/235.387418，P99 11.569625/17.517375/11.316625/11.460084，
max39.633416/42.399958/50.992500/46.880500；pooled fresh236.94→224.12(−5.4087%)、P99 11.57→15.62，
>16.667ms35→73、>50ms0→1，保持OFF。独立delivery ffc61b70…34c3，tracked verification新增
docs/verification/direct-rect-real-motion-2026-11-04.json。same-display原版v3按全原设置再跑，142.2参考与旧146.05/152.6
都保留，禁止挑最有利分母宣称稳定+50%。display34/create16已TERM正常0/removedtrue/originalUnchangedtrue，现无UI运行。

下一集中方向是 native measuredQueueSubmit：实际帧已一条main submit，上传已并frame，没证据要去掉fence或逐纹理submit。
本机MoltenVK1.2.11默认同步，可测试官方 MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS=0 移交serialGCD编码。
driver试验仅build外部wrapper/环境，产品源码未改默认，fence/lease/barrier不动。源码/官方v1.2.11证据在
build/queue-submit-review-20261104/{preparation-v1,review-v1}.json；getter FFM独立静态审 e277876b…77b。
DriverSpriteOracle.java source064bb298…e845，已javac成功，class49a55950…5da2（compiled-wrapper-v1）。
sync1/0顺序headless实际均valid exit0（14.87/12.90秒），native actualMoltenVK1.2.11/Vulkan1.2.296，
config184bytes、before/after global synchronousQueueSubmits实际1/0、prefill0/maxBuffers64，0setter；
Sprite148综合checks+608资源退休，64×64/MSAA4，无FPS结论。根未启动真实driver FPS比较。

nov4_evidence_review正准备独立map-cell-v1 wrapper/runner（保留Sprite源不动），用于driver sync1/0地图GPU正确性。
owner_default_package正准备外部DriverGameMain（不改产品main），同bytes native显式固定、global初始+shutdown读回，
再反射进入KoolDesktopMain；Arena shared全进程保留，不能把shutdown读回冒充backend teardown后/instance快照。
两agent只准备不运行/编译，根顺序统一执行，先地图pixel/fence通过再fullgame四arm。当前无live JVM/GPU/manager。
下一未用virtual文件create17（最近list仅main1active）；用户持续授权必要后台测试，失败立即停，不问时段，不抢焦点。
所有声音从启动全0，未改Steam/安装应用/系统音量，仍1.4.3同批，未commit，goal继续active。

---

## 2026-11-04 新rect候选已过CPU19与headlessGPU OFF/ON，实景性能包准备中

RWX_DIRECT_RECT_VERTEX_WRITES=1 已实现并冻结，默认OFF；仅strict identity filled rect run，
writer保留原capacity不足时4次addVertex fallback、原索引API、10次modCount及共享view游标、buffer position/bounds。
source准备 build/direct-rect-vertex-writes-20261104/preparation-v1.json（e3447fb1…3665），
19项定向全部通过（newrect4、packed12、directsprite3），targeted-results-v1保留XML。
冻结core1fcb100d5678186b7a17c41c806fdbf7165b9e8d05d549c525270f8add76efd1，
desktop503990d18616f8cecb65f0769eb2657225db1cc584b0f3558d63e1baf5df400b，在cpu-inputs-v1。
新core的headless Sprite GPU OFF/ON均valid exit0，13.97/12.89秒，actual日志directRectVertexWrites false/true；
148checks/608资源退休的细项正在nov4_evidence_review只读审，仍只64×64/MSAA4正确性，非FPS。
run_rect_gpu_v1.py、gpu-runtime-v1、gpu-rect-off-v1/on-v1原始记录完整，不重跑覆盖。
comparison_protocol正在替换旧859 fat的全部core/desktop模块并审构造ABI和字节scope，尚未启动新实景。
根可用已审新fat准备真实10k panzoom ABBA，仅新增rect0/1，其余paint/packed/directsprite/lazy全0、clock1。
下一未用虚拟屏create16；当前无live JVM/GPU/window/manager，最后readonly list仅main1active。

归因措辞纠正：JFR叶Std140.structSize确在generic IndexedVertexList.checkBufferSize调用链，但实际fat PNColor
与Maven0.19 byteexact且使用TightlyPacked40B；原因未定，不能声称实际PNC为Std140或精确消除了Std140。
确定待测的是每rect4次generic checkBufferSize/view/成员写入成本。独立公开API审查通过（direct-rect-contract-readonly-v1）。

---

## 2026-11-04 画笔复用也无稳定收益，下一项是strict矩形顶点写入

真实profile已独立归因：packed-profile/profile-analysis/analysis.json SHA cce15e02…bae5；
实际addPackedAtlasSprite leaf136，Std140.structSize多数来自 filled identity rect primitive（108/123），
不是sprite writer。完整pass prepare median/P99 .040/.206ms、commands .979/4.197ms、freeze .407/2.330ms。
最大fresh gap74.566ms中的canvas重放只覆盖.864ms；粗epoch锚跨度.940ms，附近main samples落vkQueueSubmit，
仅诊断关联，不能断言全部GPU或精确GC因果。Paint分配加权约60%直接toCanvasPaint，40%effect.copy。

现有 RWX_REUSE_PAINT_SNAPSHOTS 单开已筛（avoid copies0、packed0、其余与上轮相同）：
build/stress-scenes-20261104/paint-real-motion-v1，display33/create15、ABBA/20秒panzoom：
control232.274722/P99 12.556834/max45.042625；paint230.124924/14.412875/32.119917；
paint-repeat215.874151/15.372/41.489625；control-repeat213.780389/15.200750/38.823250。
两组平均fresh几乎相同，P99无稳定改善，保持OFF。四arm有效，comparison_protocol正只读复算。
开关ON的5项Paint定向兼容测试已实跑通过，compat-tests归档（全core524历史XML在packed integrated-v4-results）。
create15已TERM、manager正常exit0/removed=true/originalDisplaysUnchanged=true，无game/JVM/manager存活。
不要把这组与display32原版假称同屏；若描述跨游戏单次参考，必须保留屏次/时段限制。

owner_default_package开始实施默认OFF RWX_DIRECT_RECT_VERTEX_WRITES，仅既有strict identity filled rect run，
缓存布局写4顶点/6索引、保留公共API增长/count/dirty/bounds契约，旧clip/transform/stroke完整fallback、ctor不变。
nov4_evidence_review独立只读核Kool API；根不改这些文件。**当前尚未完成/构建/验证，不得打包或宣称通过**。
仍同批1.4.3，所有新实验默认OFF，继续目标+50%和拖缩长帧，不能只用平均FPS完结。

---

## 2026-11-04 公平10k拖缩ABBA和同屏原版有效，+50%及长帧目标均未完成

本轮已完成真实同包859a5f791988502c28b3480b0099e6240578eaae71f8892c12f9a2eac749b8f5、
同display32/create14、MediumAI0/整数ms1/1x/初始10000/1792×1008/MSAA4/vsyncOFF/cap300，
warm2/sample20秒的完整panzoom（.35..1.5/20秒平移），启动全静音，实际BG从未active或越界。
build/stress-scenes-20261104/packed-real-motion-v1/，A=packed0，B=packed1，其余direct/lazy等OFF：
ABBA分别 fresh220.157782/227.178725/220.244925/223.069015；
P99 13.009959/14.105209/14.532583/13.245458ms；
max70.374208/45.470625/50.904083/65.941792ms。四arm均有效，但B均值只比A约+0.95%，
P99约差9%，不能默认采纳packed。旧6秒Hard对照不可用于跨游戏收益，本轮已修复Medium并有实际日志/最终prefs。

同一display32随后原版 fresh152.6/P99 14.420208/max45.611042ms，run-moving-panzoom-v2；
官方core8a55+既定compatTemurin17x64/OpenGL、同20秒轨迹/普通AI0/1x/300及全静音。
所以本轮 +50% 门槛应是228.9，四个RWXX均未达，不能继续引用旧146.05分母宣布成功。
不同产品runtime、观察器放置及独立普通战局随机种子限制仍在；shouldDraw不等于实际GPU逐单位可见证明。

真实 packed-profile 另行完成：JFR(profile,max64m)+RWX_CANVAS_TRACE，loopSplit/commandTimingOFF，
performanceMeasurementsInvalid=true，不纳入性能比较；已导出profile-compact.ndjson，owner_default_package正只读归因。
profile的snapshot约221.62/P99 15.87/max74.57仅诊断，不能混进ABBA。create14 manager已TERM正常exit0，
removed=true/originalDisplaysUnchanged=true，复核只剩main1active；无游戏/JVM/manager存活。旧31也已确认消失。

房规fixture已修至独立8项通过；全core524（1skip）通过；desktop全160中MacNativeFullscreenTest
在显示休眠时原生窗口回调超时（v4原始失败保留，这个交互测试不应混入后台回归）。之后临时init强制headless
并明确exclude该1项，desktop159项通过；Python50项通过。不能宣称该原生全屏项已验证。
GPU修复是既有oracle误把owner当page，严格改为(owner,pageSerial)，3个CPUtests覆盖，不削弱两页/像素断言。
GPU v2 control/packed均148 checks、608/608资源退休通过；packed实际74lists/451rows/318selected、materialized0。
仅64×64/MSAA4正确性，非真实场景FPS。独立review SHA06ff385b…febe。
整包review 97项全解释，全部旧constructor ABI保留，82个绘制/生命周期类与GPU已验core0a byteexact；
core5dfa8dc11580150b9ef0867f4bac51b55e09625099067f6cdc8420888a608389、desktop8da003…。
产品所有实验候选仍OFF，默认静音和房规Bug修复已在源码，同批1.4.3，未commit/install/改Steam。

---

## 2026-11-04 历史：公平房规修复及 packed GPU 验证进行中，拖缩目标未达成

用户追加“并且还要保证拖动、缩放不掉帧”。共同目标仍是兼容性、相对原版至少 +50% 真实有效帧率、
持续拖动/缩放的长帧稳定性；不要用静态平均帧率代替，不要再次询问已授权的后台优化。默认静音，
不改 Steam/安装应用、不抢焦点；同批仍是 1.4.3，未 commit/install。

原版正式静态对照已成功：build/original-10000-background-20261104/run-jre17-300-silent-v2，
官方 core8a55、兼容 Temurin17 x64（非 RWPP 引擎）、独立 native/guard-v3，1792×1008/MSAA4/cap300，
普通 Medium AI0/1x/初始10000单位，启动即 -nosound -nomusic。fresh92.5Hz，P99 21.459ms/max58.799ms。
原版持续 panzoom 也有效：run-moving-panzoom-v1，20秒，fresh146.05Hz/P99 16.001ms/max50.062ms；
zoom .35..1.5、实际可见1342..9986，不能称全程10000可见。该场景 +50% 门槛是219.075 fresh/s。

发现此前 RWXX 记录实际是 Hard AI1，而原版是 Medium AI0。旧静音 V3/control/lazy/direct 与 clockpair 的
原始数值保留，但不能据其计算公平跨游戏收益；comparability-errata-v1 已记录。最新 RWXX panzoom
build/stress-scenes-20261104/motion-medium-v1/panzoom 因实际 AI-Hard 判整体无效，不能据其宣布差距。
问题根源是冷启动读设置早于引擎初始化，以及服务器重置后没有应用完整 config.options。
房规产品修复和8项回归已写，首次 integrated-v3 的 core524 项中7项新房规 fixture 失败，
首因 replayEngine=null 触发真实加载 NPE；保留 first-integrated-failure，nov4_evidence_review 正修 fixture。

integer-ms 游戏 delta 候选 RWX_ORIGINAL_INTEGER_MS_GAME_DELTA=1 默认OFF，匹配原版整数毫秒及
0.060000002f，修正旧每帧计时截断；desktop156 tests通过，实际 ON 6秒游戏时间5997ms、fallback0。
临时 fat f55ac2fcce03976e16a4b9656cf6d9d565c84db2e26af965c7669382786cd6c2，单对不能证明收益。
精确固定缩放基准已写：--camera-mode pan --zoom-mode fixed --zoom-min .45；Python50项通过，
Kotlin 待新房规 fixture 修复后的统一回归。

packed root 候选 RWX_PACKED_ROOT_TEXTURE_COMMANDS=1 默认OFF：freeze 输出仅 root DrawTexture
转512行 immutable数值块，旧 List 惰性兼容，生产 atlas/adaptive/renderer/withSurfaceClear 原生读取。
CPU12项packed测试通过，含真实renderer/fallback/slot顺序/host clear/线程及精确float；冻结 core
build/packed-unit-sprites-20261104/cpu-inputs-v2/core.jar，SHA0a788124691d06d7f61f368b950cc563db9fa0c28c99fc77078123858bc5bc26。
只读静态复核通过，不等于 GPU/FPS 通过。无窗口 Sprite桥已编译；control0 的首次 gpu-sprite-control-v1
7.69秒 exit1，既有 oracle 断言“两大纹理未强制新增 Atlas页”，packed1 尚未运行。失败原始记录保留，
owner_default_package 正查桥配置；不得弱化断言或重跑覆盖同目录。

CGVirtualDisplay create13/display31 在系统显示服务睡眠时自然到期，manager exit1；两屏均 inactive，
removed=false/originalDisplaysUnchanged=false，31仍列出，清理尚未证实。没有 manager/JVM/game存活。
继续 CPU/headless 检查，不唤醒/改真实显示器；只读检查恢复后先确认 owned31清理再创建新屏。
下一次未使用的 manager 目录 create14。所有后续实际测试显式从启动静音，公平验证实际 AI0后才算有效。

---

## 2026-11-04 历史进度：双候选已真实跑300；静音默认已改；原版premain manifest修复中

用户现在要求共同满足：兼容性、比原版性能高50%、拖动/缩放不掉帧，并明确“开始自动优化，不要再问问题”。
又新增“注意不要有声音/声音默认关掉”：所有后续测试从启动即静音，不改系统音量。
产品SettingsEngine/GameSettingsRepository/SettingsModel已统一新安装/缺key/初始model enableSounds=false、musicVolume=0；
masterVolume保留，已有明确用户设置保留。全core504(1skip)+desktop148二次回归0fail/0error，
desktop/tools公共isolated_seed现也强制enableSoundsfalse且master/music/game/interface各0，13个Python工具tests通过。
产品同批1.4.3未再升级、未安装、未改Steam。当前core faa896cebbaa0354247b3b47b2ed2531382e62bbb6c23cfe96ebc743a73adf98。

两个默认OFF的生产候选已写/14定向+全回归过：RWX_DIRECT_SPRITE_INSTANCE_WRITES=1、RWX_LAZY_CANVAS_SIGNATURE=1。
未静音的临时fat包44258febbe7e9f78501f741bc3b13f40e2e63515ce69108b47655920ce94c7bb，core4ea424…3384。
包review通过：43entries差别含FrameRenderer/CommandBuffer/既有mapNode族、等价RenderProbe编译变体，
33nested仅SMAP/line、旧3constructor ABI保留，allcore2606entries精确绑定，Host/owner不变。
basic-gpu-v1在同fat两candidate均ON，真实cold primitive/texture2versions1540checks通过，tolerance3/maxDelta1，
正常exit0/fenced，非整场景exact也非FPS证据。

真实10k同map09232、1792×1008/MSAA4/vsyncOFF/双方目标cap300的RWXX三arm已完成有效：
build/stress-scenes-20261104/unit-render-candidates-v2/，V8 fe722f09…bac973f，samevirtual27，warm2/sample6。
control fresh47.969/accepted113.759/P9984.177/max133.598，owner50.02Hz/301frames但仅+155tick，
sample游戏时间3.266s/6wall；日志4次pathfinding等待，启动CustomUnits1416ms/Loading2277ms。
direct ON fresh126.794/accepted190.608/P9924.014/max60.208，owner128.43；lazy ON fresh120.819/accepted175.146/
P9921.403/max38.738，owner124.24；后两者没有pathpause、启动305/665ms及296/630ms。
独立91checks通过（build/unit-render-candidates-20261104/independent-v2-review/delivery.json SHA028fc2a7…4381d）。
不能把单个异常低control差值当+164%收益；不能因它慢就删/invalid，也不能断言候选与pathfinding无关，需静音反序复验。
三arm旧音效开/音乐0，仅历史记录，不再用其直接采纳；未证明drag/zoom稳定或领先原版50%。
display27/create9自然600s清理exit0/配置恢复。V1control在V6硬120premain失败-6/0.16s无窗口，旧记录保留；
新V8仅放行120|300，HOST/RATE/helpers byteexact同V6、9CPUfixtures+ASM通过，真实V2已证premain/input/Vulkan皆300。

原版native预检终于通过：native-preflight-jre17-exit-v2，display28/create10，guard-v3 ecef78c8…e6941，
Temurin17x64+独立lwjgl只改JAWT loadcommand (__TEXT text完全同)，create即虚拟屏1x/1792×1008/MSAA4/inactive，
130swaps后走正式System.exit退出，三stage exit0/shutdown/bindings全部过。旧Oracle8崩溃、显式destroy退出挂起均保留。
纯官方8a55引擎已发现原生highRefreshRate=true→300cap，无需改Sync；300probe ddd18e…915c4。
静音runner run_jre17_300_silent.py从启动-nosound -nomusic，runtime-silent/prefs全0；必须NullSoundFactory日志，
正式副本不含RWPP engine/CP，Java来源是已有兼容JRE，明确不是stockOracle8表现。
正式run-jre17-300-silent-v1在premain失败-6/0.99s，无窗口/无地图：agent addTransformer(...,true)，
probe300 manifest缺Can-Retransform-Classes能力，comparison_protocol正仅修manifest/保持classbytes exact，
并将先真正headless -javaagent -version验证，再新runner freshv2，不覆盖v1。尚无有效原版10k分数。
create10 manager当前仍持有display28，root session95940，PID69353，最近检查elapsed3m36；不得让600s到期后复用。
preflight已绑定同display28，若过期必须新display+新预检；正式失败后已停止目标，无Java进程，不播放声音。

新静音core faa 对4ea多8classes：3预期音频类+5 AppKt/BattleRoomController/MultiplayerConnectionController/
WarmupController/GameSession编译logger变体；root scopegate先拦住fat创建，owner正独立做保守CFG规范化。
silent-inputs/compiled-core.jar已拷，silent fat尚未创建；不能把旧44258当已包含默认静音。
后续实际测试必须显式静音prefs（project和launch sandbox均核），新fat/静音fresh反序/control及组合，
再做同设置原版对照和持续pan/zoom各项，不得只测静态10k便标完整goal complete。

---

## 2026-11-04 自动优化进行中：真实一万单位 RWXX 基线有效；原版后台定位预检待修

用户最新明确要求“开始自动优化，不要再问问题”。继续自主实施兼容性优化及必要后台对照，
此前“允许后续必要后台对照，失败立即停”仍有效，不重复询问时段；不激活/抢焦点、不改 Steam 或安装应用。
随后用户将目标提高为“要比原版性能高出50%”。按同地图、同实际单位负载、同画质下≥1.5倍真实有效吞吐，
同时检查长帧定义验收；双方120cap无法证50%，先完成当前原版对照，再加入双方相同高cap/足够headroom测试。
不承诺未实证目标；兼容性仍是前提。完整目标仍未完成，不能用产帧率或一次场景测试代替验收。

一万单位真实完整应用基线已通过：build/stress-scenes-20261104/full-app-background-baseline-v2/，
正式 candidate 平台1bcc1f8d…3758e2，map09232b22…e0373，1792×1008/MSAA4/cap120/vsyncOFF，
owner默认ON，其余实验开关OFF；warm2秒/sample6.009秒，普通AI/1x、中心固定/zoom约.45。
644 fresh/688 accepted，107.170 fresh/s、114.492 accepted/s，重复6.395%；fresh P99 18.882ms/max43.799ms，
18次>16.667ms、1次>33.333ms、0次>50ms。实际存活9848..10000，minimum shouldDraw 9848，
最多624正在移动，694/700帧存在弹丸；不是10000单位同时寻路。截图已实际查看为细节坦克阵列及前线交火。
独立审计19项通过，delivery SHA f93bdc31…e1e9d6。v1因controller误读V6 frame数组失败，保留、不计有效。

新map node候选RWX_MAP_NODE_MODEL_UPDATES_ONLY=1已实施（默认OFF）：专用私有Slot跳过完整Node.update，
每次renderInto后仍updateModelMatRecursive，保留绘制、纹理、MVP及资源fence语义。
11个core tests通过；真实自动owner GPU 8cells/2097152pixels exact0，最终seq=fence2625、lease释放/retire0。
基础GPU cold oracle1540checks通过，容差3/maxDelta1。临时性能包bec17680…a681c仅作试验，不是发布包。
同包同屏正常replay OFF fresh115.011/P9917.788/max28.163，ON114.388/P9917.873/max29.052，
两次有效、没有性能收益，因此保持OFF，不据CPU/GPU正确性宣称优化成功。

用户上一条要求再测纯官方一万单位。独立官方8a550a37…deec9副本已准备，正常地图入口、无单位/命令注入，
使用官方自带Oracle8 x86/OpenGL，对照RWXX JDK25 arm64/Vulkan；双方完整应用均无ActiveProcessorCount限制。
原版runtime native-preflight-v1的Java-version和AWT通过，LWJGL首次show前native guard发现越界即exit79，
全程actual inactive/key=false、尚未启动官方游戏。初始窗口落在主屏2x；不能把Java setMode顺序当已证原因。
comparison_protocol正在查实际Display/native位置路径并修正pre-show定位；旧失败保留，之后跑全新v2预检。
guard dylib d85532ab…2a51fadd2a（完整指纹见native-review.json），不得放宽边界或show后移动。
最后display21/create-3已TERM清理、exit0、原配置恢复。没有live JVM/GPU/虚拟屏；下一次manager用create-4。
run_preflight_root.py已补信号和owned进程清理；官方run_once须去掉人为强制G1以保持Oracle8默认collector。
生产版本仍同批1.4.3，旧验收版本/指纹均保留，未commit/install。

本轮新增两个独立、默认OFF的生产候选：
1. RWX_DIRECT_SPRITE_INSTANCE_WRITES=1：atlas sprite固定layout直接写24float，capacity不足仍走原Kool增长，
   finish统一发布dirty；原generic分支保留，constructor ABI不改。3项tests包含raw float bits/增长/清空重填/
   实际atlas transform+team3modes+clip+blend+排序+projected sentinel。
2. RWX_LAZY_CANVAS_SIGNATURE=1：CommandBuffer只在signature被读取时增量hash，保留原多项式/顺序/overflow，
   clear/remove和beginFrame复位；主画面snapshot不读取签名，可以跳过每条命令的无用hash。5项tests覆盖
   中途读取后append/clear/clearAlpha/各batchflush4096增长/旧snapshot不变。无constructor变化。
两候选14项定向tests通过（含activeReset/mapNode回归）；全core504（1skip）+desktop148，0fail/0error。
core编译指纹4ea424d3cad07dd48ba9867b3119c512314f2e763cef762ff8378cbbf7523384，
备份build/unit-render-candidates-20261104/inputs/compiled-core.jar。临时fat包尚未建成，gate发现额外RenderProbe
编译变体先拦截，owner正独立核规范化语义与构造ABI；不能据tests宣称FPS收益/采纳，下一步同包真实场景独立A/B。

新增实际一万单位短JFR/engine+canvas+vk诊断：build/stress-scenes-20261104/full-app-background-diagnostic-v1，
warm2/sample10、真实后台门禁有效，display23/create5已清理恢复。sample主线程457个JFR samples，
addAtlasSprite119leaf/156inclusive；canvas-prepare1206.8ms、canvas-commands3321.1ms、engine-freeze2092.9ms。
这些属于并行线程/嵌套span，不能累加；freeze-detail/nested等复用同duration尤其不能当额外耗时。
Std140.structSize主要primitive IndexedVertexList，不是sprite。DrawTexture.hashCode经CommandBuffer.note为生产路径，
不是诊断专有开销（root早期错误推断已由实际栈纠正）。独立审计37项通过：build/nov4-independent-review/delivery.json。

原版native-preflight-v3已修到真正创建即virtual24/1x/1792×1008/MSAA4/始终inactive，
但Oracle8u141在约0.6秒后AppKit Thread的CoreAnimation/CFRunLoop timer→objc_msgSend崩溃exit-6。
无guard failure、无init/destroy现场，不能唯一归因guard或说VM必然不可用（旧有效FG原版确实同VM/native）。
失败保留native-preflight-v3和hs_err_pid58537，原版游戏仍未launch；display24/create6清理exit0/配置恢复。
下一步纯官方8a55引擎使用本机已有Temurin17 x64兼容JRE（须明示非stockOracle8性能）；
comparison正准备独立JRE/native副本，只改lwjgl loadcommand到对应JAWT并核__text不变，防跨VM混装。
不改Steam/原runtime，预检通过才启动一次正式原版10k。当前无live JVM/GPU/虚拟屏，next manager create7。

---

## 2026-11-03 当前交付：10000真实单位地图已实际加载验证；底层active mesh候选仍OFF

用户新授权针对瓶颈做底层优化，兼容前提下可改架构；随后明确要求构建1万单位场景。
场景构建已完成：build/stress-scenes-20261103/ten-thousand-tanks-v2/
[p2]Ten_Thousand_Tanks_64cols_(5000v5000).tmx，SHA09232b22…e0373。
双方各5000官方内置tank（引擎canonical解析为c_tank，无MOD，官方INI指纹绑定），
平地4480×2880、64列×79行/队、间距24、中央200；推荐1792×1008/zoom.45/中心2240,1440。
真正headless加载v3 exit0/所有门禁true：10000存活、各team5000、0越界/0重叠/0重复ID，
正常速度1tick约1.53s，不注入单位、不改unitCap/速度。完整1178资产独立沙盒。
场景包build/stress-scenes-20261103/Ten_Thousand_Tanks_5000v5000.zip。未验证GPU实际可见10000或FPS/交战不卡。
首次checker误按legacy enum，二次checker把configPath相对路径误当assets绝对前缀；原始failed记录v1/v2均保留，
root仅修相对路径literal成verifier-v3后真正新进程load-v3全部通过，不能把前失败删掉。
用户问为何停滞，已直说验证流程拖长并修正，不再扩新验证框架阻碍场景交付。

底层优化实际已写FrameRenderer reset previous-used/finish current-used，flag RWX_RESET_ONLY_ACTIVE_CANVAS_MESHES=0/1。
49相关core tests通过，root修构造器ABI后新增3语义tests再过。新candidate ABI-v2：
platform1bcc1f8d…3758e2 / ZIP974c9b1f…3799a2 / corea124…13be1，36package+11post通过，
旧904三个constructor含Kotlin default保留、旧7caller字节恢复；RenderProbe唯一额外编译变体359条规范化等价。
候选仍默认OFF，owner默认ON；source renderer eaae9328…7302a4，test2b7293ea…daab8。
首有效同包pair OFFfresh110.815/P9923.569/max31.951；ON111.876/P9921.676/max39.095，混合小收益，未采纳。
反序复验仅ON成功；因create15 display18在root等待期间600s到期cleanup，OFF预检拦截且未消费attempt/未启动游戏。
所以不能称ABBA完成；按失败停保留，不盲重跑，暂无live GPU/CGVirtualDisplay。

V7实际归属已完成：sample1324native创建，map1038/root286，全部name+hash owner无重复/多pass变体观测；
不是同owner兼容pass-key为主（该窄方案暂停）。collect同时包括pass.onUpdate/canvas重放/Node工作，不能纯当场景遍历。
未来②可实验专用map Slot永久skip Node.update+每次renderInto后updateModelMatRecursive，
不能直接一行关闭（primitive初始ModelMatrixData dirty问题），必须有actual GPU cell exact/referenceNodeUpdateON/leasefence门禁；尚未实施。
原Owner GPU main当前强制shaderFull模式，若做②应显式加prepared-cells模式，保留旧strictshader失败。
完整流畅性/原版parity目标仍active，不能以单项场景构建完成来标整个goal complete。

正式记录：[一万单位场景](verification/stress-scene-10000-2026-11-03.json)。

---

## 2026-11-03 最新：map mesh复用真实对照无收益；已核对原版绘制短路径

上轮是有效进展：owner默认ON真实包+background default验收完成，严格shader覆盖失败并保持OFF；
本轮再次完成同904061包、同display15的mapmeshOFF/ON两次20warm20sample，均exit0/BGgate通过。
OFF fresh114.914/P9917.947/max33.306ms，ON113.379/P9918.075/max41.859ms，真实reuseConfirmed；
平均、重复率、P99和max更差（P99.9改善保留），继续默认OFF。不为此扩GPU像素main、不从CPU减分配推导实机收益。
display15 create-12已TERM，exit0/removed/完整原配置恢复；没有active GPU或虚拟屏。独立审计进行中。

用户追问“仍不如原版、原版好在哪里”。官方8a55字节码证实u.render→Slick Graphics/Image→SGL顶点；
已有Image field、atlas版本命中、program复用。Slick内部也buffer+flush、也有离屏与cold shadercompile。
差别是没有RWXX的immutable跨线程snapshot/version/lease/mailbox→再次解释成Kool Scene/Mesh等转换。
结合独立43.696ms stall的20.454ms collect+11.970ms backend(含8.314ms/87pipelinecreate)，
剩余热点在绘制准备与创建集中，但87无法从旧CSV确定是root还是map。不能将新GPUtarget版本直接归咎新root key：
现有FrozenPixelIdentity(owner,logicalId)+history slot已使多数版本复用同identity。下一步先给pipeline创建
补只读pass/pipeline identity定位，再选实际热路径修改，避免继续猜开关。

原版背景只读计划确认：仅UIElement+Display位置不保证native首次不激活；缺早于AWT/Sys的nativeguard，
所以未launch纯官方背景，不修改旧FG原版记录。仍可继续本项目优化，不是整体goal被阻断。

[map真实对照](verification/map-mesh-real-background-2026-11-03.json)；
[剩余原版差距](verification/remaining-original-render-gap-2026-11-03.json)。
完整目标继续active，未宣称追平。产品版本1.4.3/904061平台/f526ZIP/core5d77保持，安装与Steam未动。

---

## 2026-11-03 最新停止点：shader严格覆盖门槛未通过，按约停止；owner默认交付保持

严格shader GPU v1实际运行27.586s后exit1，无timeout/signal/controllerError/owned残留；未重跑。
真实最终全画布seq2632/tick666没有可证“不同visible mesh共享同一shader”，因此helper failclosed，
fullCanvas pending passed=false/pixelsPassed=false/rounds[]，没有最终result。不是观测到像素错误，
也不能宣称全画布/最终lease/retirement通过。此前6个实际cell1,572,864像素maxDelta0、67GPU reference meshes
只作部分证据；pre-reference owner停止/fence2632/pending0不替代最终清理成功门禁。
shader仍默认OFF。收益只有单pair小幅混合，不继续为过门禁制造draw或扩假覆盖，优先已测批量map mesh/pipeline重建。
原始：build/shader-reuse-20261103/strict-owner-gpu/candidate-on-v1/；source/class/command已冻结保留。

这不影响904061/f526 owner默认包的独立成功验收。用户问119.9是否可称原版水平，已明确答复：
119.9是owner产帧；实际fresh113.6、重复约3.3%、两次>33ms，还不能宣称整体追平。
原版历史120fresh/s并非同一虚拟屏重测，仍需区分。无正在运行GPU/虚拟屏，下一步原版背景计划只读审查中，
NSWindow首次makeKey/NSApp activation是待解决点，不能假设UIElement或现有AWT observer已覆盖。

正式记录：[shader真实场景覆盖](verification/shader-reuse-real-scene-coverage-2026-11-03.json)。

---

## 2026-11-03 当前交付：owner 默认 ON 的真实新包与后台默认值验收完成

当前canonical 1.4.3 ZIP为f526e394…33c753、platform904061f3…fb4470a、core仍5d77…703d9；
旧5703/64a96完整保留pre-owner-default。32/32 package、8/8 post检查通过，33,602 shipped classes
仅DesktopFrameRateKt改变。六例实际bundledJVM构造器确认缺省ON/显式owner0及global0回退，renderer缺省OFF，
owner线程均关闭；120tick通过。未安装、未动Steam。全量core490(1skip)+desktop148已过。

新单次后台owner-default-v1/default无owner/global override，实际owner stable=true/render stable=false、G1；
exit0、窗口始终虚拟屏inactive、门禁通过。产帧119.885、present117.417、fresh113.568Hz，
freshP9918.066ms/max34.418ms，>33.333ms2次、>50ms0。不是新旧成对性能试验，不能跨轮相减宣称额外收益。
display14(manager create-11)已TERM，cleanup exit0/removed/完整恢复原配置；没有正在跑的游戏/虚拟屏。
仍未达到原版稳定120fresh/s。后续OFF测试必须显式owner0，缺少CLI不再代表OFF。

shader pair独立审计也通过：单pair小幅混合收益不足采纳，仍默认OFF。下一步现有TEST main接入
严格同真实冻结快照、MSAA4、OFF/ON整帧exact读回和自然crossmesh reuse验证；只改test、不扩产品。
comparison_protocol agent正在准备/最小编译，不自行启动GPU；主agent统一launch，失败保留不盲重跑。

正式记录：[owner默认交付](verification/owner-default-adoption-2026-11-03.json)。
完整流畅性目标继续active；前文各时间点的pending/defaultOFF是历史状态，保留原指纹与结果。

---

## 2026-11-03 最新：后台三组探索结束；owner 默认 ON 已入源码，实际新包验收中

持续后台授权仍有效（失败立即停），不要重复询问时段。临时屏用本地 CoreGraphics
`CGVirtualDisplay` manager-v5，1920×1080 实际1×，请求60Hz、软件cap120/vsyncOFF；
未测物理刷新率。shader pair最后display13已TERM移除，cleanup exit0、原显示配置完整恢复。

owner-only 背景对照已证实产帧100.47→119.77Hz、fresh99.87→112.01Hz，但P99/max曾变差；
结合此前CPU ABBA和真实自动owner GPU exact像素/资源验收，本批只把owner phase校正默认改为ON。
仅解析默认改变，显式owner0仍可退回；renderer/runloop/actual delta未变，版本仍1.4.3。
core490(1skip)+desktop148全部0失败。新默认尚未算实际包验收通过；打包agent正在保留旧5703/64a96
文件到pre-owner-default并构建/验收新包。旧冻结inputs不动。后续OFF测试必须显式owner=0，不能再以
缺少CLI当OFF。原背景ownerpair记录保留当时defaultAdopted=false，不重写历史。

其余三组均冻结64a96、owner1、renderer初始OFF，同屏各一次20warm+20sample：
- renderer stable ON：fresh115.92→112.31、P9916.847→20.053ms，继续默认OFF。
- G1→ZGC：fresh114.03→112.86、P9919.583→21.384ms、max略好，继续默认G1。
- instanced shader reuse ON：fresh112.66→114.46、P9918.908→17.888ms、max39.715→42.884ms。
  单pair小幅混合收益，继续默认OFF，独立审计进行中，不能据此宣称像素兼容或采纳。
三组target exit0、background gate均有效；不把不同pair合并算累计收益。

独立instrumented stall诊断具体找到无GC的43.696ms gap：scene collect20.454ms、backend11.970ms，
其中87次pipeline create合计8.314ms（嵌套不能相加），owner期间有6个快照，native等待都短。
另有GC重叠和acquire等待的不同长帧。下步针对mesh/shader/pipeline批量重建，不能一概归罪异步架构。
同步trace有扰动，只作定位，不作FPS采纳。原版尚未在同一虚拟屏复测，完整目标未完成。

正式记录：[后台渲染探索](verification/background-render-optimization-exploration-2026-11-03.json)。

---

## 最新授权与恢复（2026-11-03）

用户已明确答复 **“允许后续必要后台对照，失败立即停”**，之后曾pause，最新又设goal为active。
该后台对照授权持续有效；不要再重复询问时段。权限记录：
`build/background-display-20261103/standing-background-authorization.json`。
失败立即停止并保留记录；可修复测试工具后进行新的必要后台对照，不盲目重跑同一失败配置。
物理显示/scanout验收不要求；不主动激活窗口、不抢焦点、不修改Steam和已安装应用。
中断重入必须先检查实际进程/已消费attempt，不能因无新输出就重启。当前修正pair-v2已开始control，
新manager create-6临时display9，具体进度以execution-result/background-gate及root session为准。

---

## 2026-11-03 当前：用户要求改后台虚拟屏验证；首个后台OFF因回主屏无效，已停且待新确认

用户已批准过OFF/ON，随后明确“后台再开一个显示器”“不要管真实显示器”，并在UI将goal设为active。
本轮未启动旧visible-rwxx-ab-v1任何arm；改用新background协议，物理显示/scanout不再是性能验收条件。
临时CGVirtualDisplay已实际创建active ID8，1920×1080逻辑＝实际CG pixels；privatehiDPI getter2、
publicCopyDisplayMode空值均如实记录，不当作已测物理刷新率。软件cap120/vsyncOFF、应用实际framebuffer
是独立gate。当前临时屏已TERM清理、原配置完整恢复，无Java/manager正在运行。

`background-display-20261103/rwxx-pair-v1/control-off`游戏本身exit0/full20s数据完整，但首show后
回主屏Display3，sample也在主屏，所以background observer失败、整个control无效，ON完全没开。
raw99.199fresh/s、117.290present/s只作诊断，不用于背景A/B/候选效果。全程frame未active，无主动抢焦点。
按用户原“失败后停止、不自动重跑”约定未复测。

已准备外部agentV4：JFrame构造器绑定virtual GraphicsConfiguration，初始位置不再默认主屏居中；
实际shown越界/激活立即flush+halt79。公共agent仅解除后台30fps限制（也恢复owner120目标），
不改actualActivity/owner-pacing/render-pacer；两arm共同适配且仅owner开关不同。只编译/ASM验证，
修正后真实GUI行为尚未验证，不可称已经成功固定虚拟屏。新wrapperV2补同agent/wrapper/display绑定、
sample可见inactive/实际framebuffer/MSAA/target/owner、PG超时与信号清理；未运行新pair。
具体futurecommands位于`build/background-display-20261103/background-pair-v2-plan/future-commands.json`。

新确认卡已发出：允许后续必要后台对照（失败立即停）／仅这次OFFON／暂不运行。尚无答复，
不要复用已失败control或把旧授权当自动重测许可。用户若选后续后台对照，授权持续生效以减少重复询问。
当前1.4.3产品默认和64a96包、Steam与安装均未改。完整目标继续active，未追平原版。
正式记录：[后台显示尝试](verification/background-display-attempt-2026-11-03.json)。

---

## 2026-11-03 补充：真实自动 owner 与 GPU 并发正确性通过；可见 A/B 仍待时段答复

本轮是有效进展，没有启动可见窗口，也没有改产品代码／默认／安装或 Steam。上轮已发出的
约6分钟前台时段问题尚无答复；`visible-rwxx-ab-v1` 无新授权、无 attempt／run，不得复用旧时段。
候选仍1.4.3、默认OFF；ZIP570327…／platform64a96…／core5d77…保持原指纹。

新增TEST入口 `OwnerReplayWindowlessGpuAcceptance`：真实KoolDesktopGameSession自动owner120，
真实host先安装GPU资源所有者，memory-only headless Vulkan consumer60；普通1x replay从tick0、
warm2＋sample20、pan20／zoom4，无手步／单位注入／假installer／人工present ack。固定nice19、
512MiB、SerialGC、1 processor；修正后v2实际27.79s正常exit0，无timeout／signal／残留owned组。
全程2631个freeze事件；1279个host提交＝unique adopted＝unique fence-completed，最终seq2631到达
真实native提交及fence。最终8个实际地图块、2,097,152像素与独立源纹理renderer参考exactmaxDelta0；
67个真实GPU reference mesh、多色非透明输出，font真上传、owner停止、session关闭、最终CPU lease释放、
pending retirements0。所有raw lifecycle计数／像素维度及7个实测owner相关class与64a96包独立复核一致。
这是并发资源／最终map-cell正确性，**不是屏幕FPS、全App像素、鼠标延迟或追平原版**。

首轮 `automatic-owner-gpu/candidate-on` exit1原样保留：旧atlas-sharing专用oracle在像素枚举／读回前
因reuseHits0拒绝当前prepared-map路径。修正为独立 `verifyPreparedMapCells` 入口；旧四参verify的
atlas capability／reuse／pages／meshes／uploads／GPU mesh门禁全部保持。新入口直接渲染冻结源纹理、
核对同front/lease/slot/provenance/readback，再由main强制maxDelta0。v2独立目录，没有把首轮失败算通过。
当前prepared-map测试atlas intern/upload实际0，不能称测到了共享图集。JVM日志加载了macOS AWT
库；这不是OS窗口／焦点遥测，未进行独立OS监控。headless adapter没有GLFW／激活／输入路径。

另对旧CPU ABBA全部107条>50ms间隔只读复算：world hook后到freeze中位0.054ms；重叠hook跨度
owner CPU中位53.275ms、占wall中位85%，且固定zoom档位集中，支持缩放相关CPU工作，不能归为纯park。
地图cache reset／CPU raster是源码支持的假设，缺逐cell trace，不能外推到可见GPU99Hz或改画质阈值。

正式记录：[自动owner GPU兼容](verification/owner-gpu-compatibility-2026-11-03.json)。
原始v2：`build/owner-policy-20261103/automatic-owner-gpu/candidate-on-v2/`。
所有新test源码／classpath均冻结归档；只运行testClasses编译，没有重复全量JUnit／打包。
下一步等待已有可见时段问题的明确答复，再执行已冻结同包OFF→ON各一次；完整流畅性目标继续active。

---

## 2026-11-03 最新：owner-only 候选已打包，默认仍 OFF；真实 CPU 生产者证据通过，可见 A/B 待新授权

当前1.4.3新增独立 `RWX_STABLE_OWNER_DEADLINES=1` 候选，只影响 engine owner；两开关缺省时仍
OFF，旧 global fallback 保留，renderer pacing class 与旧 f0e… 包同字节。没有安装或启动新可见窗口。
新 ZIP `5703274c5409c608f70234adcf1088cb066285fda4006bd05d44d190e7e900b4`，
platform JAR `64a96fa4fbe66a0f814a46930eae4d6eb69117ea9a31b48ad143ecc3fe00d206`，
core仍 `5d77d01450aa956655abd8c37eede7f6f0b88391bfa426db31cd5d3fa1e703d9`；
旧516d… ZIP／f0e… JAR完整保存在 `build/real-release-20261103/pre-owner-policy/`，历史记录不改。

真实 `KoolDesktopGameSession` 自动 owner、自然 CPU raster fallback、无窗口／GPU的 replay ABBA：
固定cap120、20s warm＋20s sample、owner0/1/1/0、global unset，同冻结classpath。
完成freeze产出中位 **83.5→111.225Hz（+33.2036%）**，P99 **62.154→53.654ms**；这是CPU生产者，
不能外推旧99.43新画面/s或宣称新窗口FPS。**>50ms仍27／26／27／27次，>66.67ms每轮9次**，
长停顿未消；第二对max反而+2.33%。Owner CPU按实际hook跨度中位+3.08%，不能称免费提速。
完整lifecycle计数独立重算，poll数不当producer数；world hook在HUD/freeze前，lastDelta不是outer dt。

修正后的OFF／ON兼容各14项通过，core490（1skip）／desktop148／Python33通过。首次旧harness的
stableOFF resize60s timeout原样保留：它误读必须真实present才有的 `cameraSnapshot()`；只把该CPU
benchmark谓词改为 `currentFrameEnvelope()?.camera`，没有伪造present ack或放宽产品门禁。
新包CRC／codesign／96核心class／7个实测owner相关class／1178资产及随包JVM120tick JNI通过；
这些是包与headless验收，不是GPU／物理焦点遥测或可见流畅性验收。

新的同包RWXX-only OFF→ON上下文 `build/owner-policy-20261103/visible-rwxx-ab-v1/` 已materialize，
59 tools `72d0f036…`、两命令与关键输入／旧AWT脚本同字节独立核对无阻塞；controller最终
`fa465eb294637ebc88708c51a2bb2918ea22438af1fc62c88efe318e485c23bb` 的7项mock、
普通无授权／`-O`拒绝及str/Path hash检查通过，realPopen=0。两轮各一次，四共享旗ON、
nativeBGRA ON、normal-input-probe OFF；不重跑原版，旧官方v2有效基线继续保留。
**新的约6分钟时段问题卡已发出，尚未答复，仍未授权；旧时段已用完**。
（通常两轮约2min，outer160×2＋清理约330s，非硬实时承诺），不激活／修复焦点／自动重跑。
正式记录：[owner deadline 候选](verification/owner-deadline-candidate-2026-11-03.json)。

下方正文保持原样；其中早期“未改源码／包”等是当时的历史阶段状态，以本节当前候选状态为准。

---

# 当前优化与验收状态

用户最新明确：新下载到指定 Steam 目录的是**官方原版《铁锈战争》，不是 RWPP**。
当前对照使用根目录官方 PC 1.15 `game-lib.jar`（SHA256 `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`），
排除并存的旧 RWPP／generated_lib／模组残留。完整官方素材、字体、库及原配 Java 8 已可用，
已复制到 `build/original-comparison-20261102/runtime`，没有改写 Steam 安装。
下方“资源缺失”“只优化 RWXX”“待恢复授权”均为历史阶段状态，不能作为当前阻塞。
已取得一次有效的官方同机**产品新画面提交节奏**对照（11-02实测、11-03分析），详见下段；
尚未达到原版水平，也未证明严格像素／画质等价、物理scanout或鼠标输入延迟。
不抢焦点、不自动激活／置顶／恢复前台的约束继续生效。

## 2026-11-03：首次有效官方原版对照完成，明确新画面产出与限速节奏是下一优先项

用户已回答新的5分钟前台时段“现在开始”；v2官方和RWXX各运行**一次且已用完**，均正常
exit0、无timeout／supervisor signal／残留owned进程。官方sample含左边界display active/visible
全true；RWXX既有whole-run AWT gate实际exit0、valid=true。没有强制激活、恢复焦点或自动重跑。
原版不再是“没有实测数据”；下方未取得／等待本次授权的文字全部是历史阶段。

同一M5／同一真实双桥replay／1280×720窗口设置／cap120／20s warm＋20s sample／pan-zoom。
官方PC1.15 **8a55…**＋修正启动probe **ba14…**，RWXX1.4.3 **f0e351…jar／5d77…core**：

| 当前样本 | 官方原版 | RWXX |
| --- | ---: | ---: |
| accepted presents/s | 120.00 | 117.42 |
| 新画面/s | 120.00 | 99.43 |
| 重复画面比例 | 0% | 15.33% |
| 新画面间隔P99 | 15.40ms | 20.26ms |
| 最大新画面间隔 | 18.93ms | 36.87ms |
| >33.333ms次数 | 0 | 3 |

RWXX新画面−17.1456%、P99+31.5394%；追120需要在99.43上再提升约20.69%，不能把这两种
百分比混用。RWXX3个长间隔33.720667／34.55975／36.872209ms，>50／100ms均0；
没有JFR，不归因GC/native。原版2400fresh/2400accepted，RWXX1989fresh/2349accepted，
RWXX实际样本20.004969459s；完整保留左锚点及未闭合tail age，没有删掉慢帧。
原版sample tick604–1203、gameTime19745–39512，RWXX605–1205、19778–39578，正常1x行为。
RWXX living23–38/maxmoving23、无fixture，camera span1489.76/911.22对原版1496.83/911.94，
实际zoom两边约.45–1.5；窗口内地图GPU缓存created增长0/hits2112，确实在复用。

**重要限界**：这是各一轮产品present-cadence对照；原版fresh定义是completed render后present，
RWXX是new snapshot后present。官方Display尺寸＋density1不直接证明GL native framebuffer，
actual fog像素／画质及MSAA等价未独立证明；x64Java8/OpenGL与arm64Java25/Vulkan MSAA4不同，
不能当纯renderer效率／物理scanout倍率或鼠标延迟。CUA列到Main但id/name绑定无效，未截图／激活。
所有原始trace独立重算、fatal/进程检查、frozen输入验证通过；本次duplicateJavaLaunchHelper
warning仍存在但没有启动崩溃。实际主对照报告：
[官方可见对照](verification/official-visible-comparison-2026-11-03.json)。

**下一主线改为owner调度／新画面交接**：RWXX engineProduced100.295Hz，与fresh99.425接近，
但render117.42Hz；engine intervalP9910.426ms。source没有100cap，highRefresh=true/env120
请求8.333ms，实际log hybrid=false/stableFrameDeadlines=false，fullremaining park后每帧从
actual pre-tick start重新设deadline。原版实际链b.updateAndRender→Display.sync→Sync.sync，
累计nextFrame=max(previousNextFrame+period,now)，并有sleep1/yield平均等待策略；结构上能抵消小迟到。
这只是已确认实现差异，**不是本次游戏100Hz的已证唯一原因**。

`build/owner-cadence-20261103`用随包f0e351实际EngineOwnerLoop class、固定外部JDK25，
无窗口单JVM8组ABBA scheduler诊断28.19s，未加载GUI/图形音频库。idle stableOFF约86.60Hz
→ON119.74Hz；2ms tickCPU work92.16→120.00Hz，支持孤立scheduler的wake-drift累积。
2ms ownerCPU约568→735ms/3s，提频有更多tick工作；P99仍约11–12ms、idle max未改善，
不能称均匀120、真实游戏修好或默认ON。未修改生产源码／默认／包，**无新可见授权**。
真实候选验证必须保留实际elapsed delta、输入FIFO、reset/close/no-catchup及兼容性，再另取时段。

其他只读候选已归档而未实施：DrawTexture引用同一快路径无合法命中；逻辑TextureId已缓存；
泛型value-class装箱可单独评估registeredTexturePixelRevisions私有map改String键，见
`build/texture-id-boxing-20261102/review.json`与未应用patch。先解决上述实际cadence主线。

## 2026-11-02：默认移除空依赖集合迭代器分配，新1.4.3包验收通过；prefix候选仍OFF

**用户最新追问主要差距／原版为何不卡、为何还无原版对照。** root已明确承认原版基线仍缺，
此前原版带旧probe启动崩、无probe只是启动控制未测FPS；RWXX另未过whole-run AWT门禁。
内部A/B不能充当原版性能差距，当前不能宣称追平或给原版速度比。已优先补齐独立新对照：
`build/original-comparison-20261102/final-1.4.3-comparison-v2-guard/comparison-ready.json`，
官方8a55／初始391byte prefs f429／修正启动probe ba14／59工具5a58原样冻结，新RWXX为516d/f0e/5d77。
仅thin wrapper替换build_probe为固定ba14复制，原main／exit0／sample／focus门禁不变；wrapper4个
离线copy/拒绝校验通过（evaluation/wrapper-offline-validation.json），没有启动目标。
未来按official/future-command.json → rwxx/future-command.json → evaluation/future-foreground-check-command.json。
再次向用户发出**约5分钟、两臂各一次的前台时段确认**，问题卡已提交；截至本段写入未收到答复。
这是遵守用户此前不干扰工作、不抢焦点、可见运行另确认时段的要求；旧35秒授权已用完，
不得当作本次授权。无新答复不可启动；失焦／失败保留记录，不恢复焦点、不自动重试。
两侧viewport/HiDPI/迷雾/1x/tick跨度/质量仍需实际运行后核查，不能因准备好就接受为可比。

本轮progress：实现／验证保守prefix候选，保留未归因143ms长帧所以不默认采用；进一步从
真实JFR找到空集合iterator热点，并完成低风险guard、真实ABBA、像素及新包验收。
同批版本仍1.4.3，完整流畅性目标active，尚未取得官方同机有效FPS／物理拖缩无掉帧验收。

最新发布ZIP **516d9580fd6603c3af9d3d563caaf11071ee8e03b8146b88b318c5c6719d12ea**，
路径`desktop/build/distributions/RWXX-1.4.3-macos-arm64-desktop.zip`；
platform jar **f0e3515879c564f29331b977eae82c6057a0b508a1ff14ad1ff32cdb2c4d7f66**，
core **5d77d01450aa956655abd8c37eede7f6f0b88391bfa426db31cd5d3fa1e703d9**。
上个7e3…zip／86d3…jar原样保存在`build/real-release-20261102/pre-empty-guard/`，历史指纹不改。
CRC／codesign／96个核心class（六组family）与当前core、headless入口、随包JVM120tick均通过，
`build/real-release-20261102/empty-guard-mac-jni/acceptance.json` valid=true。
没有执行GUI launcher／安装应用／动Steam；再次只读核实`/Applications/RWXX.app`仍1.4.2。

guard仅在`releasePendingImmediateFrameSnapshots`的forEach外加isNotEmpty，后三个clear仍
无条件执行，不能改成整个函数early return。既有非空unregister顺序、异常语义和缓存释放不变。
490 core（1跳过）＋145 desktop tests 0失败；其后仅将guard换单行，完整javap -p -c
可执行listing与已测多行版完全一致（format-only-proof.json）。
与固定prefix corea015…相比，最终5d77…JAR逐entry只有KoolGraphicsEngine.class不同。
四轮顺序独立JVM A/B/B/A复用同冻结helper／classpath，prefix0/0、shader0/0，
真实seek6000/fogON/warm120/sample1200、60Hz同步test producer、actual nice15。
目标iterator JFR权重67.86/87.70MiB→0/0采样；CPU中位−4.654%，但**总allocation权重+1.614%、
P99+0.372%且方向混合**，不称总分配／P99／FPS普遍改善。Raw JFR全stack与compact、clock
anchors／timings均独立逐项验证，所有长帧保留。guard以空分支无副作用的工作删除接受。
最终**产品默认**512MiB真实GPU验证：300 generated=adopted=fenceCompleted、retirements0，
6个cell共1,572,864像素maxDelta0、scratchReuseHits0，pool关闭并释放scratch。
它不等于root／App UI／物理屏幕验收。详见[默认空迭代优化](verification/empty-dependency-iteration-2026-11-02.json)。

prefix实验保留`RWX_REUSE_MAP_ATLAS_SCRATCH=1`才ON；shader／shared materials／direct也仍OFF。
Page只有guarded blit写字节，记录末guard行完整prefix；COW先用旧prefix清donor尾、copy旧prefix，
才扩大新write bound，避免窄矩形列洞。5个新test覆盖反例和边界。真实blank复用显式fill范围
480MiB模型→10.3125MiB（−97.85%），不代表总内存写流量；真实COW requests=0，只由unit覆盖。
prefix ABBA CPU中位−2.795%、P99−5.598%，但b1最长143.692625ms/CPU15.84ms/GC overlap0，
**原因未定且不剔除**，继续OFF。corea015与全部测量classpath实体已不可变归档，未因后续构建丢失。
详见[prefix候选](verification/atlas-prefix-candidate-2026-11-02.json)。

原版退出只读审计另确认：timer System.exit，Root.exit也最终System.exit且实际b.destroy不清AL；
可考虑timer volatile request→render只Root.exit排队→原版队列退出，不能直接在同步probe回调
System.exit（shutdown hook同monitor可能死锁），也不能承诺修好OpenAL。该方案**未实现／未实跑**，
不得放宽exit0/fatal/focus/sample原门禁；审计在`build/original-comparison-20261102/graceful-exit-readonly-v1/`。

## 2026-11-02：未发布图集数组单槽复用候选完成两组真实 ABBA，默认继续关闭

本轮是progress：新增受控候选并取得真实分配／GPU像素／CPU证据，没有启动可见窗口。
同批版本仍1.4.3，`RWX_REUSE_MAP_ATLAS_SCRATCH=1`才开启；当前发布7e3…包未重建，
安装目录和官方prepare-only比较上下文未改。目标仍active，未宣称拖缩无掉帧／追平原版。

pool最多持有一个16MiB scratch，仅Page逐字节intern命中后放弃的、从未发布过的mutable
loser可进入；Page先换canonical和标immutable，再归还旧数组。generic intern不消费调用方，
canonical／过期Page／上传所有者不能回收。checkout先取走单槽，同长度才借，blank全清零，
COW完整copy覆盖，close立即封存并丢scratch（早于其他可能抛错的owner清理）。
未实现dirty-prefix／稀疏清理。强引用最多一页，与旧canonical weak引用相区别。

`build/atlas-scratch-20261102`顺序独立JVM A/B/B/A，固定60Hz真实无窗口同步producer：
opening seek0/fogOFF/warm1200/sample1200，以及battle seek6000/fogON/warm120/sample1200。
各臂2个JVM，唯一语义flag为scratch0/1/1/0，shader复用始终OFF。
opening整段Page分配4→2；battle整段含预热Page分配32→2，实际复用30次。
后者采样窗generated121–1320/tick6060–6660的Page JFR权重272.70/289.35MiB→0/0，
**不能写成采样窗精确512→32MiB**。JFR总分配权重中位1843.14→1617.06MiB（−12.27%）仍是统计采样。
但battle CPU中位+1.60%、P99+2.50%且单对方向混合、max+4.97%，不接受为默认性能改进。
opening CPU+11.51%主要出现在首次Page操作前1061帧，不能归因于full-zero/COW；
资源／target callback也有少量运行差异，不能假定每帧work完全相同。

最终core7009f112…上485 core（1跳过）＋145 desktop tests全通过；512MiB后期真实回放
300帧generated=adopted=fenceCompleted，6个512²cell共1,572,864像素maxDelta0，
19次Page请求中2次分配、17次真实scratch命中；owner close后pool.closed且scratch=null，retirements0。
它只验证cell attachment，不是root／App UI／物理屏幕／FPS验收。
最终源／core／相关classes已保存在`build/atlas-scratch-20261102/full-zero-final-state/`。
opening用4feddf…core（关闭顺序调整之前）未及时留独立jar；原始每轮SHA一致记录保留，
不得伪称已归档。battle／pixel使用已归档7009f112…core；差异只在owner shutdown关闭顺序。

结论默认OFF；下一可用实验是保守dirty-prefix清零／复制，仍需单独实现与验证。
详见[图集scratch候选诊断](verification/atlas-scratch-candidate-2026-11-02.json)。

## 2026-11-02：完成着色器复用真实开局 ABBA，维持候选默认关闭

当前只做诊断，没有新增生产改动、重复升级版本、启动可见窗口或替换已安装应用。
`build/shader-reuse-20261102` 四个顺序独立JVM按0/1/1/0切换
`RWX_REUSE_INSTANCED_TEXTURE_SHADERS`，同core8773…、同命令／flags（仅输出路径不同），
真实opening replay普通1x、显示雾OFF、60Hz、1200预热＋1200样本；四轮exit0，
timings与JFR事件逐行精确对应，各1200真实native submissions，tick605–1205。

两轮中位数：JFR总allocation sample weight582.27→519.89MiB（−10.71%），
明确包含createInstancedTextureShader栈的权重62.09→20.71MiB（−66.65%）；
这些是统计采样权重，**不是精确分配量**。线程CPU合计3328.56→3292.69ms（−1.08%），
P99为31.02→30.39ms（−2.05%），但首对更差，最大帧中位数50.18→52.83ms（+5.27%）。
两臂各仅2个JVM，用户并发工作／OS调度不受控；不能把2400帧当2400独立实验。
结论：可保留“减少该场景分配的候选”，**默认继续OFF**，不接受为拖缩掉帧修复。
尚未完成root全画布RGBA与native shared shader bind验收，旧map-cell oracle不足以覆盖root。
全画布helper仍只是build/scratch草稿，未编译、未运行、未接入src。
详见[着色器复用诊断](verification/instanced-shader-reuse-2026-11-02.json)。

原版用户授权的无agent启动控制已消耗且日志保留；未重复执行。新修probe／最终1.4.3
比较上下文prepare-only。当前没有新的可见运行授权，不能自动启动／抢焦点／重测。
后续候选正只读审查：intern命中后复用未发布的16MiB Page数组，尚未实现或接受。

## 2026-11-02：捕获真实慢帧，移除共享图集整页哈希峰值；原版启动控制有新证据

上一轮是progress：共享CPU＋上传内容已默认开启、真实pixel及包验收通过。本轮继续沿完整
流畅性目标推进，没有把内存诊断／单次JFR当作“已追平原版”。同批应用仍 **1.4.3**。

新增**测试专用**可选frame observer，围住真实overlay renderFrame（包含原pacer、collect、
submit），不修改生产窗口／pacer；有界primitive列、hook内无文件IO、结束写timings。
JFR事件`rwx.WindowlessGpuFrame`与nanoTime开始／结束分别夹取，并校验同线程CPU有效性。
诊断模式只降低详细scene JSON到每60cycle，每帧missing／target／lease／fence检查完整保留。
首轮smoke43行与43个JFR事件精确对应；两个完整诊断分别2425／2437行，各与同数JFR事件精确对应，无丢失或错误。
phase是状态快照，sample必须按generatedAfter>warm且<=total且比before增加选，不能只看phaseAfter。

真实双桥opening replay seek0、普通1x、fogOFF、1200帧预热＋1200帧采样，固定60Hz、
G1／Xms512m／Xmx1000m／2CPU／nice15，仅开JFR＋VKtrace，关闭高量CanvasTrace。
两个运行采样tick均605–1205，各实际native submission1200；同步测试producer、限帧、
诊断开销、并发用户应用均限制结论，不能视为desktop线程架构／物理显示FPS／原版比较。

首轮最长57.37／56.22ms，线程CPU41.84／43.39ms，JFR采样落在pool.intern→ArraysSupport.hashCode；
另有GCPhasePause约21–24ms与慢帧重叠。修改Page在原padded blit中用实际ARGB、位置／尺寸
维护append-history fingerprint，只给pool选桶；**每次复用仍完整contentEquals**。
未共享的root／rollback路径跳过fingerprint。不同历史最多漏复用，碰撞不能错共享；UV／像素／
shader／GPU图片和fence不变。新增不同source IDs同payload布局（含换行）复用及显式碰撞检查。

修正后pool hash采样4→0，两轮相同intern4／reuse3与upload4／reuse3。
观测最大帧57.37→49.60ms、>50ms帧2→0，但**P99反而29.97→30.66ms**，每侧仅一个JVM，
不可宣称一致P99／FPS收益。修正后GCPhasePause仍5次、最大23.06ms；只统计stop-the-world
phase，不把G1Old并发74ms全算暂停。旧可见185.62ms未在此复现，也没有因此被归因或证明消除。
最终另加“非共享路径不计算无用fingerprint”的小清理未重测40秒分布，单独完成最终pixel与回归。

最终6个真实512²格子共1,572,864像素与独立旧atlas参考精确一致、maxDelta0，GPU atlas覆盖／
版本／frame／registry／attachment检查通过；core480项（1跳过）＋desktop145项0失败。
最新macOS ARM64包仍在`desktop/build/distributions/RWXX-1.4.3-macos-arm64-desktop.zip`：
SHA256 **7e3c22b1cc16154cf68007eaa522e3f454ccd817161dcd6d37ef247a9998bcc5**。
CRC／codesign／95core family＋13headless类一致、随包JVM120tick验收通过；不含.app GUI launcher验收。
上轮44996…zip／6ace…jar已原样保留在`build/real-release-20261102/pre-fingerprint/`。
测量用b94 core ZIP在归档前已被新构建替换；保留原始SHA与已发布6ace runtime，不伪称完整独立runtime归档。

用户又明确授权一次35秒的**无agent原版启动控制**。独立副本存活到28.0056秒TERM，
28.5159秒回收，进入主菜单背景／MissionEngine／music；关闭时仍出现OpenAL cleanup之后的
PC0 SIGSEGV，非clean exit、未测FPS、单次不能定因。CUA能列出Main，但getApp无效，未截图／激活。
定位到真实早期回调：Display.create→initContext→Display.update，在create返回前probe就无条件
Class.forName(GameEngine)；现只在完成过game render后驱动，且replay开始后才清briefing。
23Python tests及Java8target离线fixture／exact官方Display/u transform通过，旧probe的提前初始化失败保留。
新probe **ba14f2162b519d2567928f2609c26efc68d46c4aaed75be2096ec3089e34b2e1** 只prepare-only，
尚未真实启动验证，不能说原生崩溃已修复；首次render也可能仍是loading画面。不得复用旧8df frozen指纹。

观察时用户运行`/Applications/RWXX.app` **1.4.2**（jar23b33f37…），未包含这些1.4.3优化；
收尾复查时该PID已结束，安装目录仍1.4.2。没有由本代理终止或替换用户应用。原目标仍active，尚缺有效官方同机／新可见包／物理输入／连续性与剩余GC优化。
证据见[慢帧诊断与修正](verification/render-stall-diagnostics-2026-11-02.json)。

## 2026-11-02：共享不可变图集像素与上传内容默认开启，保持原采样结果

同批版本仍为 **1.4.3**。新增 `RWX_SHARE_MAP_ATLAS_PIXELS`，默认ON，设`0`回退。
它只在同一GPU target owner内，对字节完全相等的atlas CPU数组与direct上传内容去重；
hash碰撞后仍逐字节比较，弱引用不延长数据生命周期，图集增长先copy-on-write。
每个Texture仍有独立只读Buffer view／cursor／limit／fence；readonly view不进入可写freeUploads。
图集packing／UV／尺寸／shader／filter／blend以及GPU image、上传流量均保持原路径。
`RWX_DIRECT_TARGET_TEXTURES`继续默认OFF，没有用放宽像素阈值接受旧direct候选的Nearest差异。

真实同回放、普通1x、tick6000、迷雾ON、120帧预热＋240帧、请求512MiB堆的诊断：
旧atlas路径Java heap OOM；只共享CPU数组时又定位direct buffer OOM；共享CPU＋上传内容后，
候选及**不传任何候选环境变量的产品默认**都完成360 generated＝adopted＝fenceCompleted，
最终退休队列0。默认实际CPU intern24次／命中23次，upload view24次／命中23次，各只新建一份
16MiB payload。这是对应存储的分配／复用证据，不是总RSS／GPU显存或FPS减少的测量。
GPU texture、image数量与上传量仍独立；失败／成功运行墙钟不能用作速度对比。

候选300／360末帧及最终默认300末帧各比较6个512²实际地图格附件，与独立不共享atlas
参考**共4,718,592像素全部精确一致、最大通道差0**；版本／完整frame／registry／attachment
身份及参考真实GPU atlas上传／绘制覆盖均检查。比较的是这些最终地图格，不是所有UI／模组。
产品默认地图格fixture3844项通过，无leaked-image警告；上下文MSAA4、实际cell pass1。

新增5项回归覆盖hash碰撞、跨store同ID、guard／UV／layout、late及普通追加、旧upload owner、
成功incremental sink与fallback、独立readonly cursor和写拒绝。默认ON完整core479项（1跳过）、
desktop145项0失败，相关Python工具28项通过。live/replay新增`--share-map-atlas-pixels`，
省略时受控环境显式0，请求ON必须同时看到CPU与upload实际复用标记。

新macOS ARM64包`desktop/build/distributions/RWXX-1.4.3-macos-arm64-desktop.zip`：
SHA256 **449968e7dc5a897e7a3ae3a1c6c19729e8472b7ac7b98c6de3e330ef0ab966fb**。
CRC／codesign／95个core family＋13个headless入口类等价验证通过，随包JVM真实运行120tick。
旧eb93…zip及0df…jar原样保存在`build/real-release-20261102/pre-shared-atlas/`，旧验证记录不改写。
这次smoke不包括原生.app launcher、焦点、GPU或FPS；真实GPU证据是上面的独立测试。

**目标仍active，未证明追平原版或消除185.62ms长停顿**。原版隔离instrumented Java8启动
根因尚未证明，下一步无agent控制／新JVM等都只是未执行方案，没有再弹可见窗口。
新增池仍有dirty page hash／精确比较／COW成本，尚无新版本可见FPS／输入／十分钟或跨平台验收。
完整记录见[图集共享验证](verification/atlas-page-sharing-2026-11-02.json)。

## 2026-11-02：已获时段授权并完成一次可见尝试，原版比较仍无有效结果

用户对“现在顺序运行两款游戏，约3分钟”的提问回答**现在可以开始**。两个尝试均已终止，
没有自动激活／恢复焦点／反复重试。官方独立 instrumented Java8u141 x64 启动8.7秒后
发生原生 SIGSEGV，JVM abort为-6，栈在libdispatch worker的`os_unfair_lock_lock`；
未进入回放或采样。这不证明用户通过原有官方启动器运行也会崩溃，更不代表原版帧率低。

RWXX当前发布包0df…完成20秒预热＋20.0066665秒采样，实际framebuffer1280×720、
MSAA4、cap120、VSync关、普通1x回放tick605–1205、23–38存活单位／最多23移动、缩放0.45–1.5。
原始CSV独立复算：2330accepted、2292fresh、38repeat；accepted116.46/s、fresh114.56/s，
fresh间隔P9513.36ms／P9917.43ms／P99.924.56ms／最大185.62ms，唯一一次超过33.333ms。
这些是native接受新snapshot的节奏，不是显示器扫描或物理输入延迟。

现有分析器给valid=true，但预先设置的保守**整程AWT门禁失败**：预热中曾失焦3.412秒后恢复，
没有删除数据或放宽门禁；故上述只保留为诊断，不能作为严格对照通过。
Python monotonic/epoch桥条件映射显示采样前约16.455秒已复焦，但没有JVM端同时钟锚及
可证误差界，只作为posthoc线索，不能把整体资格改成通过。185.62ms帧尚未归因，
没有GC/JFR/native-stage/ownertrace能证明是GC或GPU。关闭时4张image及37个buffer清理警告保留。

原版启动只读诊断和保留atlas采样的CPU页去重候选仍在推进；当前`RWX_SHARE_MAP_ATLAS_PIXELS`
候选默认OFF，尚未写入发布包。下一次可见重试不在本轮自动执行范围内。证据见
[可见尝试记录](verification/official-visible-attempt-2026-11-02.json)，独立复算在
`build/original-comparison-20261102/rwxx-context/posthoc-independent-audit.json`。

## 2026-11-02：官方资源重新备妥，真实无窗口 GPU 路径已执行

当前正式对照固定 **官方 PC 1.15**，不是 RWPP；原版和 RWXX 均使用独立测试副本。
官方准备阶段 `prepare-only` 已通过；随后可见启动失败，仍未取得原版帧率，见上节。原版 macOS 默认启动遵循
官方脚本，**不加 `-XstartOnFirstThread`**；显式参数才加入，下面历史记录中的自动参数已修正。
当前 RWXX 发布配置仍为 1.4.3，冻结复用／共享注册开启，`RWX_DIRECT_TARGET_TEXTURES` 关闭。
正式可见对照需遵守此前用户不抢焦点的要求，不能自行激活／置顶／恢复前台。

新增测试入口通过内存 WindowSubsystem 和 `VK_EXT_headless_surface` 真正执行 MoltenVK，
无 GLFW／AWT／NSWindow 创建路径，并调用 Kool 原有含 60Hz 限制的 renderFrame。
这不是显示帧率测试，也没有独立 OS 焦点／窗口遥测。生产地图格 fixture 的现有默认与
显式 direct 两条路径均通过 3844 检查（3840 像素及生命周期），超阈值差异 0，最大通道差 1。
上下文／swapchain MSAA 为 4，**实际离屏地图格 pass 为 1**，不能混称地图格四倍采样。

真实双桥回放按普通 1x 预推进到 tick 6000，迷雾开启，连续拖动缩放 120 帧预热＋240 帧：
512 MiB 堆下当前 atlas 路径在 `KoolCanvasSpriteAtlas.Page` 的 2048² RGBA 分配处 OOM；
仅显式开启 direct 后完成 360 帧 generated＝adopted＝fenceCompleted，168 次 target 安装，
411 次 target callback，364 次实际 native submission，退休队列最终为 0，无 leaked-image 警告。
每个 pooled slot 的 renderer 可保留独立 16 MiB atlas 页，是真实场景下的内存优化线索；
不能把失败／成功进程耗时对比称为 FPS 收益。该场景只有 90–93 单位、最多 2 个移动单位。

进一步严格比较最终 6 个实际地图格附件与独立 atlas 参考，共 **1,572,864 像素**；
完整版本／frame／registry／attachment 身份检查通过，但 **215 像素超过通道阈值 3，最大差 64**。
全部差异定位到爆炸痕迹的 Nearest 整数源 texel 边界，底图一致；这仍不是像素全等通过。
没有放宽阈值、加入全局 UV bias 或默认开启 direct。首轮反射 String／boxed inline ID 检查错误
及修复后真实差异均保留；第二份计划中的 360 末帧差分未执行。内存候选继续单独验证。

现已构建 macOS ARM64 1.4.3 包：`desktop/build/distributions/RWXX-1.4.3-macos-arm64-desktop.zip`，
SHA256 `eb93e9a4aa36de2a543e6f8a189ee4b7aa96dac9d344b59096b677061ca32fa4`。
包的 CRC／版本／关键 class 等价／codesign 验证通过，实际随包 JVM 在仓库外默认提取素材并运行
120 tick headless 地图；这不包括原生 GUI launcher、显示 FPS 或联机验收。旧验收中误把
macOS headless 正常加载 LWAWT 判为失败的原始记录保留，限定复核报告解释了该差异。
原目标仍 active，尚不能宣称追平原版。原始命令、保留失败与边界见
[真实无窗口 GPU 记录](verification/windowless-gpu-2026-11-02.json)。

## 2026-11-02：真实回放证明 CPU 收益，两项缓存优化默认开启

同批版本仍为 **1.4.3**。以真实游戏场景决定取舍，不再用共享源图每帧改写的极端
合成负载否决优化。现默认开启 `RWX_FROZEN_CONTENT_REUSE` 与
`RWX_SHARE_TEXTURE_REGISTRATIONS`，各自设为 `0` 可回退。live／replay 工具未传对应
参数时仍显式设 0，保留受控 OFF／ON 的历史含义。

新增手动 test main `RealReplayCanvasCapture`：真实双桥回放、真实素材和字体度量，
在原样 1x 预推进到 tick 6000 后开启绘制；迷雾使用正常回放显示开关。录制顺序与生产
一致，不创建窗口、KoolContext 或 GPU 上传，安装守卫调用数必须为 0。
正常活动片段 90–93 单位、最多 2 个移动单位；拖动缩放时 31–92 可见，实际缩放
0.45–1.5、4–16 个 GPU target、2794–15777 条嵌套命令。它不是密集战斗验收。

静止／拖动缩放各进行顺序独立 JVM A/B/B/A，两开关均 OFF 对均 ON；每轮 120 帧
预热、240 帧采样。仅统计当前引擎线程的 update/record、visible redraw、snapshot/freeze、
offscreen background 四阶段，图统计与结束后的 JSON/IO 单独记录：

| 实景 CPU 诊断 | 冻结 CPU | 冻结分配 | 四阶段 CPU 合计 | 四阶段分配 |
| --- | --- | --- | --- | --- |
| 静止 | −63.10% | −78.60% | −22.90% | −52.87% |
| 连续拖动缩放 | −69.28% | −77.19% | −25.83% | −51.41% |

每臂只有两个进程，其均值的中位数即两者平均，不是稳健估计或置信区间。名义等待为
60Hz，实际采样间隔均值 19.20–19.64 ms，**不能称实测 60 FPS**。同模式四轮 tick／
镜头／缩放／单位／迷雾标量逐行一致；真实时间缓存预算仍使少量命令图不同，不能称
跨进程绘制图完全等价。CPU 诊断不能换算 GPU、输入、帧尾或官方原版差距。

独立正确性运行对同一真实录制帧比较复用与完整冻结，360／360 Frame（含 visualStats）
与完整资源 Map 精确相等，无归一化。候选实际命中 2579 次，control 复用尝试始终 0，
旧 lease 检查通过、Missing 0、GPU 安装 0；该运行的性能数据全部无效。此差分验证
冻结复用，共享注册另由已有 true／false 的动态像素、透明处理、尺寸、回退与 lease 测试覆盖。

切换默认后强制 headless 的 core 474 项（1 跳过）、desktop 145 项无失败；Python
工具 26 项通过。实际清空候选环境的产品默认及显式双 OFF 两份短录制均通过。版本号
与协议没有在该 CPU 阶段另行升级；该阶段当时没有新安装包，后续产物见上节。完整原始记录、编译目录／依赖指纹、
独立 runtime 归档、所有保留失败和解释边界见
[真实回放验证记录](verification/real-replay-cpu-2026-11-02.json)。

**原目标仍 active，未宣称追平原版**。该 CPU 阶段用户选择先不恢复、只优化 RWXX 实景，
没有改写 Steam 目录；其后用户重新下载官方原版，当前对照准备状态见上节。GPU 呈现、物理输入、正常联机与十分钟连续性
仍待后续验收；不抢焦点、不自动激活／置顶／恢复前台的约束继续生效。

## 2026-11-02：以真实游戏为主，定位纯官方 macOS 对照

用户最新明确：优化真实游戏场景，“共享源图每帧改写”边界负载可忽略，不作为性能硬门槛。
主要对照应覆盖正常对局里的拖动、连续缩放、迷雾和单位移动。

指定 Steam 目录 `~/Library/Application Support/Steam/steamapps/common/Rusted Warfare` 的
launcher 和 `generated_lib/game-lib.jar` 均含 RWPP，用户随后选择**纯官方版**。本地六份
PC 核心的 SHA256 均为 `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`，
与既有 `original_interaction_benchmark.py` 固定官方 1.15 基线完全一致。已在
`build/original-macos-1.15-baseline-20261102` 隔离准备核心、PC 白名单库、Mac 备份原生库及回放；
没有改写 Steam 安装目录。服务器素材中的 tank.png 为全透明占位图，已排除，不能用于测速。

原版工具新增显式 `--original-root`、`--java`、`--javac` 与 `--prepare-only`，保留 Windows
默认；Mac 参数使用 `-XstartOnFirstThread` 和平台 classpath。实际准备成功，记录
`prepareOnly=true / measurementPerformed=false`；没有启动游戏或测到帧率。五项新 mock 测试通过。

当前 Steam 目录已完整备份到 `build/steam-rwpp-backup-20261102`，515 项逐项校验通过。
完整官方图像资源仍缺失；恢复会写游戏目录，已向用户请求通过 Steam 校验／安装恢复的明确授权，
**本段记录时尚未获答，不得自行执行恢复**。恢复授权与可见窗口性能测试的焦点约束分别处理，
既有不抢焦点、不自动激活游戏的约束继续生效。原目标仍 active、未完成。
完整身份、来源、备份、准备命令及局限见
[官方基线准备记录](verification/original-macos-baseline-2026-11-02.json)。

## 2026-11-02：动态候选筛选与资源版本修复，原版流畅性目标仍未完成

继续使用同批版本 **1.4.3**。默认修复两项显示资源正确性问题：纹理注销后重新注册不再
复用旧版本号，避免在途旧帧与新资源串用；像素注册戳纳入 `alphaBleedRequired`，保持
透明边缘处理、预乘和尺寸变化的区别。前者新增两项回归在修复前均失败，修复后通过。

新增 `RWX_SHARE_TEXTURE_REGISTRATIONS=1` 候选，让根、分离引擎及子目标共享像素注册戳，
**默认关闭**；`RWX_FROZEN_CONTENT_REUSE` 也继续默认关闭。冻结依赖 memo 的新优化仅在后者
开启时生效：同一帧遍历中相同引用只重复贡献一次依赖，兄弟／嵌套遍历使用独立 scope；
首次解析、传递资源闭包、旧 lease 与 GPU fence 的所有权规则保留。

无窗口、低优先级、192 MiB 堆、顺序独立 JVM A/B/B/A 先筛选了 32 条单候选记录，再测
8 条共享注册原型记录。始终共享注册的原型在 frozen reuse 关闭时 CPU **增加 31.03%**，
开启时降低 80.51%，因此最终改为单独控制的默认关闭开关。最终同一 core 的 12 条记录
比较“两开关均关闭”与“两开关均开启”，每臂 2000 次预热、4000 次测量：

| 局部冻结场景 | 每帧分配变化 | 每帧 CPU 时间变化 |
| --- | --- | --- |
| 固定 32 格、每格 128 次绘制 | −87.00% | −78.82% |
| 每帧重录一格、每 16 帧更新源纹理 | −63.06% | −58.36% |
| 每帧更新源纹理（仅诊断） | +104.21% | +72.81% |

这些是合成 CPU 路径的局部筛选，不能换算实际 FPS、P99 或原版差距。用户最新明确以
真实游戏优化为目的，**“共享源图每帧改写”仅保留为诊断，不再作为默认开关或真实游戏
性能的硬门槛**。候选暂未默认开启是因为尚缺真实游戏与官方原版对照验收，不能因这个
极端合成场景否决，也不能因其他合成收益宣称追平。renderer 的网格／材质／紧凑存储
筛选也只覆盖 CPU 构建，没有 GPU 执行或呈现；各候选仍保持原有默认状态。

最终强制 headless 的 core 474 项（1 跳过）、desktop 145 项无失败，相关 Python 工具
25 项通过。固定同一份双桥回放，改前／最终默认／最终候选三个 1000 tick 校验和逐行一致。
普通单机 600 tick 对照则有 **168 行不一致**，首次在 tick 55 出现路径与位置差异；现有
`Path.a()` 允许单机立即读取异步结果，联机／回放须等待 `isTimedOut`，这支持时序差异解释，
**尚未证明该解释是唯一原因，也不能把普通单机对照记为一致**。

完整协议、三个阶段各自的产物指纹、全部记录、修复前后回归、校验和差异和保留失败见
[候选验证记录](verification/canvas-parity-candidates-2026-11-02.json)。初版
[冻结 fixture 失败](../build/parity-optimization-20261102/freeze-smoke.log)、
[renderer fixture 失败](../build/parity-optimization-20261102/render-smoke.log)、
[首次回归验证 NPE](../build/parity-optimization-20261102/lifetime-fix-validation.log) 与
[Gradle init 失败](../build/parity-optimization-20261102/shared-registration-before.log) 均保留，
没有计入性能通过结果；之前的冻结分配报告及 10 月 30 日历史记录保持原样。

**原目标继续进行中**：用户选择以纯官方游戏作为对照。当前指定 Steam 目录含 RWPP 注入，
不能当作未修改原版基线；下一步先定位未修改的官方游戏包，再做真实游戏对照。
本轮尚未完成新原版同机对照、GPU 像素／组合候选验收、物理输入或十分钟连续拖动缩放
验收，也未生成新安装包。继续遵守无窗口、低优先级、不激活／置顶／恢复前台的约束。

## 2026-11-02：继续减少冻结路径分配，版本 1.4.3

本轮仅修改显示快照路径：单次冻结的 frame identity memo 纳入既有有界 scratch 池，
归还时清空键和值，超过 8192 项丢弃；关闭阶段诊断时不再创建十张统计表；同一次冻结中
纹理引用 memo 命中时先返回，默认路径省去重复的源表查询与 value-class 装箱。
开启 frozen-content reuse 时仍按原规则记录依赖。已发布帧、资源 lease、GPU fence、
地图失效节奏、绘制顺序、仿真、存档与协议均保持原路径；没有开启此前待验收的实验开关。

无窗口、低优先级、192 MiB 堆、顺序独立 JVM 进程 A/B/B/A 冻结微基准：主画面分配中位数
12053.82→9189.09 字节／帧（−23.77%）；32 格、每格 128 条命令为
343493.83→274565.85（−20.07%）；128 格、每格 32 条为
326261.83→321797.84（−1.37%）。CPU 时间分别降低约 19.26%／15.57%／7.92%，
但并行用户工作未受控，这些只说明局部冻结路径，不能换算整局 FPS 或交互 P99。
基准断言真实冻结了 GpuTarget 与预期嵌套命令，未把 CPU 回退当成递归冻结。

core／desktop 强制无窗口验证、诊断开关对照和 600 tick 基线／候选校验和记录见
[验证数据](verification/canvas-freeze-allocation-2026-11-02.json)。首次桌面全量测试误运行
原生全屏用例并因系统回调缺失失败；该失败保留，后续强制 headless，原生全屏行为未验收。
后续继续遵守无窗口、不抢焦点的测试约束。拖动／缩放无顿挫、原版同机对照与长期整局
流畅性仍未完成验收；本轮没有生成新的安装包。

## 2026-10-30：RWXX 统一命名与默认设置更新

用户明确要求默认开启本轮优化、默认帧率限制 300，并打包 macOS。地图层预备绘制现默认
开启，`RWX_PREPARED_MAP_TILES=0` 可关闭；桌面自动档上限改为 300 FPS，中英文设置文案同步。
手动保存的帧率、省电、VSync、失焦／隐藏上限继续生效。其他实验开关保持各自原有默认。
测试工具显式设置此开关的 0／1，避免产品默认变化污染对照。下节保留切换默认前的验证历史。

发行、CI 产物、窗口／菜单／关于文案和项目名称统一为 RWXX。兼容性包名、已有环境变量、
协议与签名域、存储目录和诊断分析标记保留，避免破坏联机、旧配置及历史证据。

默认切换由用户决定，不代表性能门槛已经通过。拖动／缩放不掉帧、物理输入、长时间整局
流畅性和历史 DirectBuffer OOM 尚未完成验收；不得把无窗口构建／启动检查当作上述结论。
本轮平台构建、打包和无窗口启动范围见
[平台验证记录](verification/release-platforms-2026-10-30.json)。后续继续遵守不影响正常工作的测试约束。

最终 macOS arm64 ZIP 与 Linux x64 ZIP 已重新生成，两者原生启动器均使用包内 Java／地图完成
120 tick 无窗口运行。Mac 签名为 ad-hoc，严格完整性检查通过，未做 Developer ID／公证。
Windows x64、Linux ARM64、macOS x64 仅完成平台 JAR 交叉构建及原生库架构检查；通用 JAR
五平台各十个必需 LWJGL 模块检查通过，macOS 另含 MoltenVK。Android Release／Debug 构建和
名称／版本／ABI 检查完成，Release 未签名、无设备验收；Debug 保留 Ktor 调试检测器的两个
management 类型引用，不能将静态检查当作设备兼容性结论。

新默认值下完整 core 测试 409 项（1 跳过）、desktop 137 项均无失败，Python 工具 96 项与
发行脚本 15 项通过。包内生产入口另行验证默认优化开启、显式 0 关闭、显式 1 开启及自动档
300 FPS／手动档保留。所有最终产物 SHA、验收日志与缺口已写入上述平台记录；尚未发布。

## 2026-10-30：地图层预备绘制候选；测试不得干扰用户工作

用户要求之后测试不影响正常工作。不得自动激活、置顶、切换或恢复前台应用，也不再
反复把测试游戏切回前台。默认使用无窗口、低优先级、限并发的验证。历史前台脚本
保留用于审计，不得自动重跑。

新增默认关闭的 `RWX_PREPARED_MAP_TILES=1`（工具 `--prepared-map-tiles`）：每个同步地图层
绘制范围只归一化一次不变 paint；可批量时直接记录原始坐标。每瓦片的待提交目标、纹理
注册及版本引用仍逐次处理；非图集、特殊纹理和其他后端沿用原入口。批次关闭时仍可单独
启用，不混入既有批次候选的收益。

实际 MapLayer 差分覆盖八个缩放值、三个偏移、透明图集、迷雾开关及瓦片变更；默认与
批次开启两种配置均通过。受影响 core 测试 257 项（1 跳过）、desktop 测试 137 项通过。
真实 Vulkan 地图／迷雾／字体检查通过，地图 3844 项检查、两个内容版本及三种迷雾状态
均无超出既有通道容差 3 的差异；新路径实际录制标记已确认。首次 macOS oracle 缺少
`-XstartOnFirstThread` 的失败保留，已修正该任务的启动参数。

新增默认关闭的帧生命周期关联诊断，见 [说明](frame-lifecycle-diagnostics.md)。输入诊断
不再在呈现线程逐帧格式化 CSV 和同步 flush，改为有界缓冲、关闭时输出。该改动只降低
诊断自扰动，不能当作默认游戏提速。完整原版对照、物理输入及长期流畅性仍未验收，
候选继续默认关闭。前台尝试中的失焦和 DirectBuffer OOM 记录全部保留，不用于性能结论。

无窗口、低优先级、128 MiB 堆的局部录制微基准，按同包 OFF/ON/ON/OFF 各运行两种批次
配置；每次 64 层预热、128 层测量、每层 1024 次绘制。批次关闭时每次绘制分配约
268.29→140.29 字节（−47.71%），批次开启时约 240.34→48.34 字节（−79.89%）。这些数字
包含 scope、snapshot 与 hash，不能当作整局帧率或尾延迟改善；并行的用户工作使墙钟
不受控。跨 JVM 的普通 hashCode 含枚举身份，只作防消除的结果消费，正确性由同进程
差分和 GPU 像素验证独立确认。完整范围见 [本轮验证记录](verification/prepared-map-tiles-2026-10-30.json)。

## 2026-10-13 状态

目标尚未达成。不能用平均帧率或交互/静止退化比代替验收，也不能把诊断运行计入正式对照。

## 2026-10-13：视口外文字候选，未通过性能验收

- 新增默认关闭的 `RWX_CULL_OFFSCREEN_TEXT=1`，live/replay 工具使用 `--cull-offscreen-text`，并验证实际发生剔除。仅处理单行、非性能 HUD、非嵌套投影文字；按 Kool 实际字形四边形、斜体、字距、等宽数字及取整基线计算边界，保留一像素余量。可见文字仍走原绘制路径，不改仿真或输入。
- 第一版借完整文字模板计算可见性，默认配置尾延迟变差，已替换为第二版：renderer 私有、最多 2048 条的边界/对齐宽度缓存，不为可见性创建顶点数组、图集或网格。历史三组筛选全部保留，不能把第一版在组合配置中的吞吐收益当作第二版或默认配置的结论。
- 第二版画布测试 208 项（207 通过、1 跳过），桌面测试 119 项通过；Python 工具 80 项通过。真实 Vulkan 字形、整段文字、MapLayer 和格网检查通过。欧洲正常游戏/AI、迷雾开启、连续拖动缩放的 12 次捕获通过：74 格、19398656 格子像素、33177600 合成对照像素，在原 oracle 的通道容差 3 内没有超限差异。该实景检查使用 2000 MiB 诊断堆，不能计入性能。
- 第二版默认配置独立 A/B/B/A：`text-visibility-default-zoom-20261013v2a`，1000 MiB 堆、四臂有效、工具/jar/回放均冻结。新画面中位数 **107.87→141.33/s**，P99 **55.90→56.41 ms**，P99.9 **181.11→162.24 ms**，>16.667 ms **7.75→6.69/s**，最大 **638.92→572.77 ms**。系统资源波动明显，四次全运行最低可用物理内存分别为 427.7/489.9/356.0/1024.6 MiB；保留所有窗口，不作负载校正或剔除。**没有建立稳定的端到端提速证据，继续 OFF**。
- 默认控制/候选输入对照 `text-visibility-input-v2-*-20261013a` 不通过：控制 150 次输入有效，候选 703 次输入、有一次拖动未观察到相机响应，保留为无效比较；额外输入来源未归因。独立复测 `20261013b` 两侧记录有效，拖动 P99 **17.36→14.23 ms**、方向键 **18.77→17.48 ms**、滚轮 **17.67→18.88 ms**，仍未通过所有输入尾部不增加的门槛。这是正常 Session 回调到画面的诊断，不是物理 OS 输入验证，也不是原版对照。
- 结构化证据、所有第一版/第二版窗口与指纹见 [文字可见性验证数据](verification/text-visibility-2026-10-13.json)。未做本轮原版七组对照或十分钟连续性验收，不能宣称整体目标完成；后续不要再从第一版单项吞吐推断默认体验。
- 补齐上一轮未写回的网格复用状态：`cure-map-mesh-slots-zoom-abba-20261008a` 四臂有效，组合基线新画面 **282.93→292.73/s**，P99 **7.55→6.08 ms**，最大 **98.25→28.44 ms**；已有完整画布/Vulkan检查和实景通过，仍未达总体门槛、默认关闭。它不是普通默认配置的对照。

## 2026-10-08 进展记录（历史状态，最新结论以上节为准）

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
