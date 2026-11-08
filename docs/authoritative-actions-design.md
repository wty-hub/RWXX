# 服务端权威动作目录：实现与验收范围

本文保留协议 4 的历史设计、源码审查与固定产物验收证据，不是当前 v6 的逐字节格式或已通过验收的声明。当前客户端以[协议 v6 规范](authoritative-wire-v6.md)为线上依据；下方旧 DTO（含坐标参数、Intent 和 View）、建议接口及 v4 结果保持原样。

**历史状态：协议 4 标准动作服务端与只消费视图的客户端已接入；最终产物无界面验收及本阶段必需的原生 GUI 验收均已通过。** 本文保留 2026-11-06 的审查依据；标为“建议”的条目是设计原案，以当前 Kotlin 实现为准。
独立动作消息、单 owner 私有能力目录、120 配置身份表及编译引用闭包已接入。最终 v3 盘点 152 个加载类型，未支持动作及闭包失败均为 0。
证据索引见[本阶段验证记录](verification/authoritative-actions-phase4-2.0.0-2026-11-06.json)：v3 已通过 340 项真实检查，拆分为 71 项动作、192 项升级、39 项连续重连/终局/录制与 38 项基础协议检查。
v3 固定为 `27d72f62…c2a3ea`，core 716 项（跳过 1 项）/desktop 234 项均无失败；实际 JAR、120 秒三 JVM 与重新生成的实际交付应用原生入口均有该指纹的独立通过记录。
后续[原生验收记录](verification/authoritative-native-acceptance-2.0.0-2026-11-06.json)补齐同一产物的真实 Kool 动作、只读回放及菜单 host/join API 桥接；先前被阻碍和失败的历史 JSON 保持原样。
目录闭合和这些具体场景不构成全部单位组合或完整安全认证。
早期 BUILD `{landFactory,turret,extractor}`、PRODUCE `{tank,builder}` 及六类单位图像的历史验证记录保持原样。

目标是让客户端使用服务器给出的己方动作目录，覆盖标准建造、生产、升级、取消、集结、维修、回收、运输及导弹操作。客户端仍不加载游戏引擎、TMX、INI、地图脚本或原版同步状态。既有联机兼容模式独立保留。

| 最终 v3 证据 | 实际范围 |
|---|---|
| 71 项动作 | 原生队列、建造/生产/取消退款、运输双向、模块父面板代理及核弹迷雾坐标实际伤害等 |
| 192 项升级 | 13 条链、23 次升级/退款；工厂、提取器双分支、fabricator、炮塔/AA 分支、机甲工厂与前哨站；真实费用、完成与新视图等级 |
| 39 项重连 | 两 TCP 客户端、确定入队的旧代际意图丢弃、能力轮换、新能力真实生产/取消、终局再次重连及严格本人视图录制读取 |
| 120 秒三 JVM | 独立服务器和两个无引擎客户端，双方各 1201 帧/tick 7200，实际移动且初始隐藏敌军未披露 |
| 实际交付原生入口 | 重新生成的工作区 `RWXX.app` 2.0.0 内含同一 v3 JAR；精简运行时无 java/keytool，强制 headless 运行全部 340 项、TLS、自 fork、父管道 EOF；未安装且不是 GUI 证明 |
| 原生 Kool 与回放 | 两客户端各 1380 帧并各完成选择/生产/取消三次 UI 动作；停服后只读回放 570 帧、零输入；全部 Vulkan、无 GameEngine、正常退出 |
| 菜单 API 桥接 | 真实 launcher 自动席位/TLS/邀请、独立房主/访客/服务器；房主 2310 帧、访客 1080 帧并均完成三次动作，正常关房停服；未点击大厅按钮或测试系统剪贴板 |
| 聊天 emoji GPU 补验 | 实际 MessageManager 录制与无窗口 Vulkan/MSAA4 回读；九种样例、7,734 个不透明采样点最大 RGB 误差 1，最终图集/字体/lease 清理通过；不等于联网聊天完整窗口验收 |
| 旧模式等价 | 原版互联基线与 v3 的 1217 个现存引擎类和两个窗口宿主类逐字节相同，四个新增只读桥接类单列；未重跑 v3 长时 GUI |

v2 运行记录保留原样，其动作/升级/重连夹具已经使用完整目录，但基础世界投影夹具尚未切为生产五参数构造；v3 的 340 项重跑已覆盖这个修正，不能倒写到 v2 证据中。

## 1. 接入接口与修改面

本节记录的历史 wire version 为 4，当时消息、字段和枚举见 `AuthoritativeActions.kt`、`AuthoritativeProtocol.kt`。以下是当前 DTO 摘要；没有在 version 3 内静默改变格式。应用版本与协议版本分别维护。

```kotlin
enum class ViewActionKind {
    BUILD, PRODUCE, UPGRADE, RALLY, REPAIR, RECLAIM,
    LOAD_INTO, LOAD_UP, UNLOAD, STOP_UNLOAD, TARGET_GROUND, TOGGLE,
    GUARD, PATROL, ATTACK_MODE
}
enum class ActionTargetMode { NONE, POINT, VISIBLE_UNIT }
enum class ActionReadiness { READY, BLOCKED, UNKNOWN }

data class ViewActionCost(
    val credits: Int = 0,
    val energy: Float = 0f,
    val hp: Float = 0f,
    val shield: Float = 0f,
    val ammo: Int = 0,
)
data class ViewAction(
    val handle: Long,
    val kind: ViewActionKind,
    val targetMode: ActionTargetMode,
    val presentationKey: String,
    val producedTypeKey: String,
    val upfrontCost: ViewActionCost?,
    val streamingCost: ViewActionCost?,
    val readiness: ActionReadiness,
    val queuedCount: Int,     // -1 表示未知
    val stockpileCount: Int, // -1 表示未知
    val progress: Float,     // -1 表示未知
    val canCancel: Boolean,
)
// ActionPanelRequest / ActionIntent 是 ClientMessage 的分支。
// querySequence 独立于移动/动作 Intent 的 sequence，二者各自严格单调。
data class ActionPanelRequest(
    val querySequence: Long,
    val viewSequence: Long,
    val unitHandle: Long,
)
data class PlayerActionPanel(
    val querySequence: Long,
    val panelSequence: Long,
    val viewSequence: Long,
    val teamId: Int,
    val unitHandle: Long,
    val actions: List<ViewAction>,
)
// 单次动作先绑定一个 owner，避免不同工厂的动作语义被同一原 ActionId 混用。
data class ActionIntent(
    val sequence: Long,
    val viewSequence: Long,
    val panelSequence: Long,
    val unitHandle: Long,
    val actionHandle: Long,
    val x: Float = 0f,
    val y: Float = 0f,
    val targetHandle: Long = 0,
    val queued: Boolean = false,
    val cancel: Boolean = false,
)
```

`presentationKey` 和 `producedTypeKey` 来自封闭的标准内容目录；不是 INI 路径、原 ActionId 或任意引擎类型字符串。图标也由客户端的固定本地资源表决定。文字使用静态本地化 key，禁止把运行时 tooltip 直接打包。

不要对任意 wire 字符串调用 `ActionId.intern`：`core/src/main/java/com/corrodinggames/rts/game/units/actions/ActionId.java:14/23` 使用全局驻留 HashMap，且 ID 比较依赖对象身份。服务器应只使用已解析、已授权真实 action 自身持有的 ActionId，避免无权限操作与无界字符串驻留。

首版成本 DTO 只表达五种已知数值。原 `UnitPrice` 还含 flag 需求/变更和任意 `StoredResources`；不得直接序列化。遇到审核内容确实需要其他己方资源时，必须先增加封闭 resource key 和对应本人状态，不能把 `UnitPrice.toString()` 当扩展协议。字段对应关系见 `core/src/main/java/com/corrodinggames/rts/game/units/custom/price/UnitPrice.java:186`：`b=credits,c=energy,d=hp,e=shield,f=ammo`，其后 `g/h/i/j` 是 flag 操作/需求。

已接入的独立引擎适配模块：

1. `StockUnitCatalog`：服务器加载完成、首 tick 前建立；保存审核的 UnitType/CustomUnitConfig 身份、稳定 public type key、允许的动作描述、构建变体、升级目的类型及静态展示资料。不接受客户端字符串解析类型，不在每次快照时重新扩大名单。
2. `EngineActionCatalog`：每个连接独享，维护 `actionHandle -> (owner identity, owner type/config identity, real action identity, execution kind, target mode, descriptor)`。句柄随机起点，来源不得是世界创建计数、ActionId 序号或目录全局索引。只为已授权的本人动作分配句柄，输出按句柄排序。

`EngineViewProjector` 调用上述模块生成本人目录并执行意图；保留 owner-thread 要求。协议/codec 增加有界动作消息和严格 enum/数值/目标组合校验；服务器发布边界对所有新增集合做深复制。客户端仅做目录展示和输入，不再把键盘操作硬编码为少数 typeName。本人回放目前只录制 View；首版不必录制瞬态选择面板。若后续要求回放重现动作面板，须显式扩展 replay 消息格式、Reader/Writer 和 Recorder 深复制，不能让新消息悄悄绕过现有录制格式约束。

动作句柄在单位死亡、失去控制、装载隐藏、类型转换、当前动作列表撤销、连接代际变更及终局时失效。升级后相同 engine entity 可能保留，但旧动作能力必须撤销。接收意图时再次从真实 actor 的 `getAvailableActions()`/`validateActionId()` 解析，不能仅相信旧快照里出现过。保留现有单调 sequence、viewSequence 新鲜度、目标当前可见性检查。

### 容量与选择语义

当前限制分别为 `AuthoritativeProtocol.MAX_FRAME_BYTES = 4 MiB`、`AuthoritativeWireCodec.MAX_UNITS = 65,536`、`AuthoritativeProtocol.MAX_SELECTED_UNITS = 256`（对应源码 `AuthoritativeProtocol.kt:6/7`、`AuthoritativeWireCodec.kt:29`）。这些是独立上限，不保证任意最大值的笛卡尔积能放入一帧。不能在每个快照中为所有己方单位展开完整动作：即使每单位只有 16 个动作、每动作仅 48 bytes，65,536 单位也会增加 **48 MiB**；字符串、费用和原视图还未计入。256 个选择单位按此下限也需 192 KiB，接近满 4 MiB 的视图未必有这部分余量。

推荐首版明确采用 **单 owner 动作面板请求 + 独立有界响应帧**，不把动态动作状态添加到整个世界视图：

1. 保留现有世界单位数上限、4 MiB 视图帧上限和 256 单位群体移动/攻击移动。单 owner ActionIntent 与这些群体移动意图并存，不通过降低最大单位数来绕开容量问题。
2. `ActionPanelRequest` 只携带一个已披露本人 unit handle 和 fresh viewSequence。服务器验证当前仍可见/可控、receipt 包含该 handle，才建立选择状态；该查询不新增任何实体披露。选择为空、越权、过期或丢失时清空面板能力，不能将请求当成探测隐藏单位的接口。
3. 静态描述按审核的 type/action set 去重，使用小型闭合 catalog 和 descriptor id。动态响应仅发送连接私有 action handle、descriptor id、readiness、己方 queue/stockpile/progress/cancel 状态；不重复文字、路径或整套单位元数据。descriptor id 是公开内容索引，不是可执行权限；ActionIntent 仍必须带私有 handle。
4. 建议首版硬上限：一个连接只保留一个 active owner panel，每面板最多 128 动作，动态 ActionPanel payload 最多 64 KiB；静态 catalog 使用有界分块，每块最多 64 KiB，按已审核清单检查总条目数/总字节数，不接收任意资源请求。如果实际标准内容超出 128，先实现显式分页/分类再宣称覆盖完整内容，不能静默截断动作。
5. 响应带 panelSequence、基准 viewSequence 和 owner handle；action receipt 绑定这三者及实际对象身份。意图同时满足 fresh view 和有效 panel receipt，换选择/换代际/终局即撤销。网络写队列合并过时面板、限速请求；不允许每次 hover 产生无界队列。
6. 后续若加入最多 256 个己方选择的汇总面板，仍先逐 handle 验证，不扩大视图；按 type/action set 展示共同动作，操作时每个 owner 都独立解析自己的真实 action。不要把一个工厂的 handle 用于另一工厂，也不要将 256 份完整目录重新塞进每个 View。

该消息拆分已在开发中的 protocol 4 使用独立有界面板实现；当前面板直接携带最多 128 个完整静态描述，不是已实现静态 catalog 分块或汇总多 owner 面板。
`AuthoritativeWireCodecCapacityTest` 实际往返 65,536 个唯一句柄及 1–3 级字段；通过实测 padding 生成恰好 4 MiB payload，随后独立面板仍能正确解码。
65,537 单位与多出 1 byte 的帧在读写两端拒绝，写拒绝保留之前完整帧且不输出半帧；256 群体意图与动作帧分离。此处证明格式容量边界，不是 65,536 单位实时仿真/绘制性能证明。

## 2. 标准单位身份与 18 个替代

`UnitTypeEnum` 不等于最终实际类型。`CustomUnitConfig.c(UnitType)` 读取 `unitTypeOverrides`（`CustomUnitConfig.java:1911`）；`CustomUnitConfigParser.java:824` 遍历 activeConfigs，`:841` 写注册映射。**INI `core.overrideAndReplace` 被反编译成 `CustomUnitConfig.image_shadow` 字段**（parser `:1248`），不要再用名为 `overrideAndReplace` 的另一个 Java 字段证明身份。

以下是仓库中 18 个非 `NONE` 替代声明。路径列是 `configPath` 审核后缀，实际加载前缀可能是资源目录或打包路径；大小写需与资源一致。

| 原 UnitTypeEnum | 注册 config.name | 审核 configPath 后缀 |
|---|---|---|
| tank | c_tank | units/tanks/tank.ini |
| artillery | c_artillery | units/tanks/artillery.ini |
| airShip | c_interceptor | units/interceptor/interceptor.ini |
| helicopter | c_helicopter | units/helicopter/helicopter.ini |
| laserTank | c_laserTank | units/laser_tank/laser_tank.ini |
| mammothTank | c_mammothTank | units/mammoth_tank/mammoth_tank.ini |
| experimentalTank | c_experimentalTank | units/experimental_tank/experimental_tank.ini |
| extractor | extractorT1 | units/extractor/extractor.ini |
| fabricator | fabricatorT1 | units/fabricator/fabricatorT1.ini |
| AntiNukeLaucher | antiNukeLauncherC | units/nukes/antinuke_launcher.ini |
| NukeLaucher | nukeLauncherC | units/nukes/nuke_launcher.ini |
| antiAirTurret | c_antiAirTurret | units/turrets/turret_antiair.ini |
| antiAirTurretT2 | c_antiAirTurretT2 | units/turrets/turret_antiair_t2.ini |
| turret | c_turret_t1 | units/turrets/turret_t1.ini |
| turretT2 | c_turret_t2_gun | units/turrets/turret_t2_gun.ini |
| turretT3 | c_turret_t3_gun | units/turrets/turret_t3_gun.ini |
| turret_artillery | c_turret_t1_artillery | units/turrets/turret_t1_artillery.ini |
| turret_flamethrower | c_turret_t2_flame | units/turrets/turret_t2_flame.ini |

对替代类型必须同时检查 `CustomUnitConfig.c(originalEnum) === config`、`modInfo == null`、精确 name、审核路径，无 `.`/`..` 路径段，并冻结实际对象身份。名字/路径相同的另一个对象不得获得权限。注册对象改变后应拒绝或结束该安全房间，不能静默扩大名单。

非替代的 stock 单位不能用 `c(enum)` 验证；应在 `activeConfigs` 中按 manifest 的精确 name+path 选择唯一对象并冻结。不要直接把 `findConfigByName` 的结果当授权：该函数还接受 `autoTriggerAction` 别名（`CustomUnitConfig.java:1258`）。`UnitTypeEnum.getUnitTypeByNameWithBoolean` 也最终接受任意 active custom name（`:2166`），因此不能接收客户端 type 字符串后直接调用它。

18 个替代只是入口，不是完整标准 roster。服务器 manifest 还要覆盖标准生产出来的非替代单位、转换目的类型和必要附件，并记录来源。不能用“modInfo 为 null”放行全部配置，也不能只允许 18 个入口后让升级产物从视图消失。

## 3. 已核实的升级闭包

以下仅列本次逐项核实的核心经济/炮塔链，不能据此宣称其他 stock 转换都已覆盖。箭头是 INI 中真实 `convertTo`；全部在服务器执行，不由客户端指定目的配置。

| 类别 | 转换链 | 源码证据 |
|---|---|---|
| 提取器 | extractorT1 → extractorT2 → extractorT3；T3 → overclocked / reinforced，两者均可 refund 回 T3 | `assets/units/extractor/extractor.ini:16`、`extractorT2.ini:14`、`extractorT3.ini:14/25`、`extractorT3_overclocked.ini:20`、`extractorT3_reinforced.ini:25` |
| 资金制造机 | fabricatorT1 → fabricatorT2 → fabricatorT3 | `assets/units/fabricator/fabricatorT1.ini:31`、`fabricatorT2.ini:17` |
| 地面炮塔 | c_turret_t1 → c_turret_t2_gun → c_turret_t3_gun | `assets/units/turrets/turret_t1.ini:16`、`turret_t2_gun.ini:17` |
| 炮塔分支 | T1 → c_turret_t1_artillery → c_turret_t2_artillery；T1 → c_turret_t2_flame；T1 → c_turret_t1_lightning → c_turret_t2_lightning | `turret_t1.ini:29/42/55`、`turret_t1_artillery.ini:21`、`turret_t1_lightning.ini:19` |
| 防空炮塔 | c_antiAirTurret → c_antiAirTurretT2 → c_antiAirTurretT3；T1 → antiAirTurretFlak | `assets/units/turrets/turret_antiair.ini:42/54`、`turret_antiair_t2.ini:24` |
| 机甲工厂 | mechFactory → mechFactoryT2 | `assets/units/mech_factory/mechFactory.ini:62` |
| 前哨 | outpostT1 → outpostT2 | `assets/units/outpost/outpost.ini:31` |

新增闭包节点的精确路径如下；表中名称的大小写不是可随意归一化的显示名称。

| config.name | configPath 后缀 |
|---|---|
| extractorT2 | units/extractor/extractorT2.ini |
| extractorT3 | units/extractor/extractorT3.ini |
| extractorT3_overclocked | units/extractor/extractorT3_overclocked.ini |
| extractorT3_reinforced | units/extractor/extractorT3_reinforced.ini |
| fabricatorT2 | units/fabricator/fabricatorT2.ini |
| fabricatorT3 | units/fabricator/fabricatorT3.ini |
| c_turret_t2_artillery | units/turrets/turret_t2_artillery.ini |
| c_turret_t1_lightning | units/turrets/turret_t1_lightning.ini |
| c_turret_t2_lightning | units/turrets/turret_t2_lightning.ini |
| c_antiAirTurretT3 | units/turrets/turret_antiair_t3.ini |
| antiAirTurretFlak | units/turrets/turret_antiair_flakgun.ini |
| mechFactory | units/mech_factory/mechFactory.ini |
| mechFactoryT2 | units/mech_factory/mechFactoryT2.ini |
| outpostT1 | units/outpost/outpost.ini |
| outpostT2 | units/outpost/outpostT2.ini |

防空 T2→T3 使用旧式 `[core] action_1_convertTo`，只扫描 `[action_*] convertTo` 会漏掉。`copyFrom`、`@copyFrom_skipThisSection` 和继承的 `overrideAndReplace: NONE` 也必须按引擎实际加载结果处理，不能只用逐文件正则推断可用动作。

原生工厂升级走原生 action/queue，例如 `LandFactoryUpgradeAction`、`AirFactoryUpgradeT2`、`SeaFactoryT2Upgrade`；不是更换 enum 的通用操作。具体 actor 升级后动作集变化，以 `getAvailableActions()` 为准。`CustomUnit.getAvailableActions()` 可能读取 `factoryUnitConfig` 而不是 `unitConfig`（`CustomUnit.java:2921`），重解析时必须覆盖这一点。

完整标准玩法还涉及非升级的形态转换与模块，例如两栖喷气机水下/过渡态、机甲部署/收起、登陆形态、运输舰、modularSpider 附件。这些不属于上表已核实闭包。应从所有审核 stock actor 的实际建造/生产/convertTo/暂时转换/附件生成结果建立可达清单，再逐类补视觉和能力；不可把它们当作“普通升级 DTO 已完成”的附带效果。

## 4. 命令执行与权限预检

下文 Java 路径均以 `core/src/main/java/com/corrodinggames/rts/` 为前缀。

短文件名的位置索引：`Command`、`CommandController` 在 `gameFramework/`；`GameUI` 在 `gameFramework/ui/`；`BaseUnit`、`OrderableUnit`、`UnitTypeEnum` 在 `game/units/`；`CustomUnit`、`CustomUnitConfig`、`CustomUnitConfigParser` 在 `game/units/custom/`；`CustomAction`、`ConfigurableCustomAction` 在 `game/units/custom/logic/`；`FactoryQueueManager`、`FactoryQueueInterface`、`NukeLauncher` 及原生工厂升级 action 在 `game/units/buildings/`。以下行号按本次源码审查时的位置记录。

统一流程：验证当前本人 actor 与有效 view receipt → 解析连接私有 action handle → 核 actor 当前 type/config/action 身份 → 核目标模式/能力/资源 → 建立 Command → `prepareAndValidateCommand()` → 加入 `pendingCommands`。`CommandController.createCommand():51` 不自动入队；`createCommandForTeam():67` 会按模式入队，不能调用后再额外 add。

`Command.prepareAndValidateCommand():729` 只检查共享控制掩码、部分旧版建造类型和 isForceMove，不是完整语义检查。`executeCommand():650` 在 `isValidNewWaypoint(:662)` 前已经可能清理旧命令，而 `OrderableUnit.isValidNewWaypoint():3612` 对非 build 的校验有限。必须在入队前拒绝无权、错误方向或不适用的命令，避免恶意无效动作清空合法命令。

| 操作 | 服务器执行接口 | 入队前必要检查 |
|---|---|---|
| 建造 | `Command.setBuildTarget(x,y,effectiveType,variant):396` | actor 当前真实 build action；`findActionForUnitTypeWithQueueSize(type,variant,false)`；variant 来自 action，非客户端；类型为 manifest 中有效替代；费用/锁/可用性；目标为合法地图点 |
| 生产 | `Command.setActionId(action.getQueueId()):438` | 当前真实 QueueUnitAction 或已审核 stock CustomAction；产出属于闭包；本人 actor 具备能力；库存/队列上限与费用由真实 action/queue 处理 |
| 升级/转换 | 同上，或经审核动作使用 `setActionTarget` | 当前升级 action；不能直接改 techLevel/unitConfig；转换目的及临时转换已审核；完成后撤销旧 action handles |
| 取消一项 | 同一 queueId，`command.stopCurrentAction=true` | `action.canPlayerCancel(actor,true)`；真实 queue 中该 action 有条目；不要要求 canAfford；不直接删除 queue、返还资源 |
| 集结 | `Command.setRallyPoint(PointF):455` | actor 当前 advertise `ActionType.setRally`/SetRallyAction 且可用；只检查 FactoryQueueInterface 不够，CustomUnit 也实现它 |
| 维修 | `Command.setRepairTarget(target):402` | `actor.canRepairTarget(target)`；目标当前可见、有效、非本人 actor、本人/允许盟友、受损或未完成；排除资源型目标 |
| 回收 | `Command.setReclaimTarget(target):420` | `actor.canReclaimTarget(target)`；可见且有效；非资源实体限定本人，资源实体按原能力规则；不能因为 server 是本地仿真而允许回收敌人 |
| 乘客进入运输器 | `Command.setLoadIntoTarget(carrier):426` | selected actor 是乘客；`carrier.d(actor,false)`；双方当前合法可见且符合本人控制范围 |
| 运输器装载乘客 | `Command.setLoadUpTarget(passenger):432` | selected actor 是运输器；`actor.d(passenger,false)`；方向不能互换 |
| 开始/停止卸载 | 真实 unload/stop-unload action → `setActionId(action.getQueueId())` | `getUnloadActionId()`/当前动作目录解析；actor 能力与状态；不要硬编码 109/110，也不要直接修改容器 |
| 核弹/其他地面动作 | `Command.setActionTarget(action.actionId,PointF(x,y),null):443` | manifest 确认 targetGround；本人已完成 actor、弹药/费用/冷却；有限地图点；公开射程规则；不能要求目标点有视野 |

必须区分 action 的不同查询：

- actor availability 使用 `action.b(actor)` 的权威路径，结合 `!isNotAvailable(actor)` 和执行时 `canAfford(actor,false)`；`CustomAction.isAvailable(actor)` 可能走 LagHiding UI 路径，不应拿 UI 预测替代权威校验。
- `isAvailableAndVisible()` 不是统一的 actor 可用性 API。维修/回收 UI 会把 **target** 传入（`gameFramework/ui/GameUI.java:1963/1985`）。按操作单独使用，不要全部传 actor。
- `findActionForUnitTypeWithQueueSize` 在匹配项不可用时仍可能返回最后一个匹配项（`OrderableUnit.java:3415`），非 null 不是授权。`getQueueSize()` 对 PlaceBuildingAction 实际是构建 variant，不是当前排队数。
- `CustomAction.isDisplayOnly()` 固定 true（`custom/logic/CustomAction.java:66`）；父类 `isQueuable()` 默认 false；不能用这些反编译后的名字粗暴过滤真正的 stock 升级动作。目标类型应由审核语义和真实 `ActionType` 决定。
- Command 动作执行分支只重新查 action 并调用 `b(actor)`，然后 `actor.a(action,cancel,point,target)`（`Command.java:678`）；不会统一替服务端做费用、目标或能力预检。

队列细节：`FactoryQueueManager.java:430` 负责正常支付/入队，`:190` 从队尾取消一项，`:442` 处理取消退款。`GameUI.java:3468` 的正确模板使用 `action.getQueueId()`，不是所有情况都直接 `getActionId()`。`AbstractUnitAction.getQueueId():154` 默认相同，但 WrapperUnitAction 有覆盖。取消证明应读取 `FactoryQueueInterface.a(action.actionId,false)>0`，不能用 `CustomAction.getActiveCount()`：后者在 displayRemainingStockpile 模式返回弹药等库存（`CustomAction.java:220`）。正常 queue 数量/进度可参考 `PopupQueueAction.java:21/31`，只下发本人、已审核条目，不序列化底层 queue 对象或它的 target 引用。

维修/回收细节：能力 API 在 `OrderableUnit.java:3386/3391`、`CustomUnit.java:3648/3657`；能力方法本身不完整检查 team。`OrderableUnit.java:2197` 的维修分支排除资源；`:2225` 的回收分支有 single-player/盟友例外。新服务器 `legacy networkGameActive=false`，不应把这个实现细节解释成客户端有权回收任意阵营。

运输细节：正确方向模板在 `GameUI.java:3905/3921`，执行检查在 `OrderableUnit.java:2062/1676`；`d(unit,false)` 包含容量、阵营、类型和运输状态。`GameUI.issueAttackCommand():3657` 名字误导，实际发 loadUp。卸载入口见 `land/HovercraftUnit.java:435` 和 `:471`；CustomUnit 也使用真实 action。可以下发本人 cargoCount/容量/卸载状态，但不要把容器内部对象、原 ID 或未披露乘客引用带入目录。

核弹细节：原生 `NukeLauncher.java:336` 检查弹药和最小距离，`:441` 接受目标点；stock nuke 是 CustomAction，不能硬编码原生 142/143。stock `assets/units/nukes/nuke_launcher.ini:42` 的 buildNuke 消耗 credits，`:78` 的 launchNuke 消耗 ammo，并是有建造时间的动作。`CustomUnit.a(action,x,y):3021` 会检查射程/terrain filter，但内部可能调用 `gameUI.showMediumPriorityMessage`（`:1246`、`:3033`）；headless adapter 不能未经处理直接调用这些带 UI 的拒绝分支。应抽出或实现等价的无 UI 校验，最终执行仍交原 Command/queue；仅依赖公开地形/距离的限制可以返回普通拒绝。

地面移动、attack-move、建造点和核弹点继续允许进入战争迷雾。不要为隐藏敌占位提供每点精确的即时失败原因或探测 API。实体目标请求必须使用当前可见句柄；旧的最后已知位置不是访问隐藏对象的授权。目标在入队后隐去时，原引擎可继续处理已经合法接受的命令，但视图不得泄漏实时 target 引用或隐藏坐标。

回收需覆盖当前 LOS 下的活资源实体与本人可回收单位，使用连接私有句柄。原引擎 GameUI 取目标时排除 isDead（:3687）；OrderableUnit 回收流程对死亡资源目标重新寻找活资源，对死亡非资源目标直接移除航点（:2159）。因此不为“完整回收”额外披露原引擎本不允许回收的死亡残骸，也不能下发全局死亡单位表。

## 5. 动作目录的隐私边界

生产路径使用冻结的完整 stock 目录和编译引用闭包。单位级 `CustomUnitConfig.isVisible` 非空即在捕获时拒绝（即便是常量谓词），
投影视图和本人供视野路径也拒绝该配置，不调用谓词。冻结的 `unitStats` 身份与 `isVisibleToEnemies` 变化会使目录失效。
这项实体披露规则不能与下面动作级 `isVisible/isActive` 的 UNKNOWN 策略混用；不允许通过执行任意单位可见性谓词决定 wire 中有无实体。

己方 action 不等于所有 action getter 都只读取己方公开状态。`self.attacking`、`self.customTarget1` 等仍能引用隐藏敌人。每个允许动态状态输出的动作需有审核的依赖策略；不能仅按字段名称含 self 认定安全。

| 危险入口 | 精确位置 | 风险与处理 |
|---|---|---|
| canAfford / isNotAvailable / availability | `game/units/custom/logic/CustomAction.java:97/116/175/187` | 执行 `isActive`、`isLocked/Alt`、`isVisible` 的 LogicBoolean；它们可依赖全图。未经审核不输出 bool/禁用原因 |
| 普通 build/queue 包装动作 | `game/units/custom/logic/ConfigurableCustomAction.java:53/115/169`；`game/units/actions/AbstractUnitAction.java:211/241/261` | UnitAction 包装也可执行上述逻辑；不能只检查 action 不是 CustomAction 就放行 |
| 动态标题/描述 | `CustomAction.java:244/281/296` | LocalizedText、textAddUnitName、descriptionAddFromUnit、descriptionAddUnitStats 可查询引用实体；后者可格式化真实血量等 stats |
| 格式化文字表达式 | `game/units/custom/LocalizedText.java:134/153/165` | `%{...}` 是 LogicBoolean，不是纯文本；禁止直接透传运行时 tooltip/锁定文字 |
| UI 预览引用 | `CustomAction.java:461` | unitShownInUI 返回真实引用/共享原型；不能序列化成真实 unit 信息 |
| 额外图标/闪烁 | `CustomAction.java:446/482` | 图标是否存在和闪烁状态也可能依赖 LogicBoolean。`isTargetingGround()` 实际读 isGuiBlinking，不表示目标模式 |
| 最近/全局对象查询 | `game/units/custom/logicBooleans/UnitReference.java:607/709` | 直接查 unitSpatialIndex 或全局 bE 列表；无当前玩家 fog 过滤 |
| 敌方数量查询 | `game/units/custom/logicBooleans/LogicBooleanGameFunctions.java:1648` | 聚合敌队数量/空间范围内数量；无 LOS 过滤 |
| 己方对象中的攻击/记忆引用 | `game/units/custom/logicBooleans/UnitReference.java:39/40/41/42`、`:984` | self.attacking/customTarget/lastDamagedBy 可保留隐藏敌目标，不得通过标题、图标或 availability 迂回暴露 |
| UI 聚合帮助函数 | `game/units/actions/AbstractUnitAction.java:335` | `d()` 读取全局 gameUI.selectedUnitsList，不是纯服务器 actor 查询，headless 也不能依赖 |

可落地的规则：

1. 静态 descriptor 和允许动作集合由已审核 stock manifest 决定。self/team/public-only 状态在明确证明后才可输出，例如本人真实队列数、费用、ammo、已审 flag 开关、公开房间 noNukes。
2. 缺少依赖证明的动态状态输出 `UNKNOWN`；使用固定 presentationKey，不输出动态描述、颜色、图标存在性或细化错误原因。不应为了构造目录先执行一遍任意 Getter 再“忽略结果”，因为 getter 也可能涉及 UI、副作用或昂贵全图查询。
3. stock 核弹的 `self.ammoIncludingQueued`（`nuke_launcher.ini:49`）、反核弹库存（`antinuke_launcher.ini:48/69`）与模块蜘蛛选槽的精确 HasFlagBoolean 可以分别审核为本人状态。
   电塔文件中的 turnOn/turnOff 位于 comment_action 段，不能把它们当实际动作证据。mechFlyingTakeoff 的 isOverOpenLand 会读取动态寻路阻挡，必须保持 UNKNOWN，不能据字段含 self 推断安全。
4. 隐藏 action 不能导出成可调用按钮。parser `CustomUnitConfigParser.java:2745` 将 hiddenAction 的 isVisible 固定为 false；manifest 必须记录玩家可发起入口，自动触发动作保持服务器内部。
5. 执行时仍用真实服务器能力与资源规则。不要新增把隐藏世界条件的 true/false 返回客户端的即时探测响应。当前 server 不向客户端回传 projector.apply 的细化结果，应继续区分“请求已收到”与“模拟已完成”；实际模拟对本人状态产生的可观察结果仍照常展示。

安全保证是语义信息隔离：在授权可见状态、己方可披露状态、公开地图、tick 等保持相同时，任意隐藏对象变化不应改变下发目录及快照。该设计不承诺消除所有网络时序侧信道，也不能否认正常游戏结果本身允许的推断。

## 6. 实现审查清单与后续扩展

下面保留按依赖组织的实现/审查清单。A–D 的服务端实现已接入，上表列出本轮实际执行的范围；清单中的每种组合不因目录枚举成功而自动成为已验收场景。

### A. 冻结标准内容与升级闭包

- 新建 manifest，枚举上述 18 个替代及本阶段支持链；从实际加载 registry 验证身份、唯一性、name/path/mod 信息，冻结结果。
- 对产出、转换/暂态和相关已审核附件进行闭包检查。缺失节点必须在开房前报告明确不支持内容，不能等单位生成后默默隐藏本人单位。
- 测试：native fallback、正确 stock 替代、同名同路径不同对象、modInfo 伪装、错误后缀/路径遍历、重注册、继承清除 override、旧式 action_1_convertTo、升级后所有新类型仍有安全视图和客户端资源定义。

### B. 本人动作目录及建造/生产/升级/取消

- 接入私有 handle、描述目录和严格目标组合；服务器动作 adapter 先以单 actor 操作实现，不修改原版 Command 的共享接口。
- build 使用 action 的 effective type/variant；queue/upgrade 使用真实 queueId；取消使用真实队列而非库存计数，并保持资源不足时可取消。
- 测试：跨 owner/seat/connection 句柄复用拒绝；旧 view/action 被撤销后拒绝；同 ActionId 不同 actor 配置不能串权；取消队尾一项并正确退款；stock 提取器/各炮塔分支升级、产出可见；所有新增 owner-boundary 集合深复制。
- 扩充真实引擎验收：至少原生 land/air/sea 工厂升级、stock 炮塔多分支、提取器 T1→T2→T3、fabricator 升级；分别核队列、实际实体/级别、费用和快照。仅 apply 返回 true 不能证明完成。

### C. 目标动作与运输

- 补维修、回收、集结、装载/卸载与导弹动作的专用语义校验，避免无效命令清掉已有 waypoint。
- 测试：看不见的真实目标和随机不存在 handle 返回相同外部语义；合法地面迷雾点仍可发令；repair 不能借能力接口操作敌方；回收不借本地仿真的盟友例外扩大跨队权限；两种 load 方向正确；满载/错误单位类别拒绝；unload 不泄漏 cargo 内部 ID。
- 核弹验收覆盖造弹扣费、库存增加、发射消耗弹药、合法迷雾点、距离边界及 headless 无 UI 调用；禁止伪造原 ActionId 绕过动作能力。

### D. 完整 roster 与隐私回归

- 在此前接口稳定后扩展剩余 stock 非替代类型、形态转换和模块附件。每类明确新增字段、可见规则和素材，而不是无条件放行全部 CustomUnitConfig。
- 增加 noninterference 测试：保持授权输入一致，随机改变隐藏敌单位数量、位置、血量、全局列表顺序、敌方资源以及己方保留的隐藏 target 引用；归一快照序号后比较 wire bytes，动作 handle、排序、状态、文本和图标标识均不变。
- 特设恶意 LogicBoolean fixture：nearestUnit、globalSearchForFirstUnit、numberOfUnitsInEnemyTeam、动态 descriptionAddUnitStats 均不能通过目录输出差异。审核的 ammo/queue/flag 真正变化时，允许且测试对应本人目录更新。
- 终局和重连测试：旧动作全部失效；新连接只能获得新代际的本人能力；回放仍仅包含本人视图及公开静态资料。transport/recorder 深复制、帧上限、过量意图限流与旧模式回归分别验证。

本阶段完成标准应写为具体“已接入动作+已覆盖单位/闭包+真实执行结果”，不能用增加一个通用 action 消息代替完整玩法验收。

## 7. 本阶段原生验收与延期功能

本阶段必需的两个真实 Kool 客户端、只读回放和菜单 API 桥接虚拟屏 GUI 检查已通过，原始结果分别为
`desktop/build/authoritative/kool-phase4-final-v3-v1/result.json` 与 `desktop/build/authoritative/menu-phase4-final-v3-v2/result.json`。
原生动作探针通过已呈现 revision 的普通 UI 输入选择本人基地、生产 builder、观察真实队列与扣费、Ctrl 数字取消并观察退款；四个联网 GUI 进程均完成三次动作。
只读回放在服务器已停止后运行，所有输入探针标志为 false、动作计数为 0。地形纹理和呈现进度检查通过，但不代表像素基准图对照。
菜单本轮通过的是实际 host/join API 桥接与两个子 GUI，没有点击真实大厅按钮或覆盖系统剪贴板；正常房主关闭已停服，强杀/崩溃及 EOF 因果仍与先前 headless 专项证据分开。

先前 `desktop/build/authoritative/kool-phase4-v2/result.json` 因主屏 inactive 和已知残留 `(vendor=29561, serial=807753347)` 拒绝创建，历史记录保留。
本次只读预检先确认主屏恢复且精确身份消失；成功运行的虚拟屏 51、53 均已移除，最终 postcheck 仅保留物理主屏 1，所有原始显示字段与测试前完全一致。
五个 GUI 进程均从创建前绑定虚拟屏，事件/累积保护未发现激活、焦点或越界；启动/退出配置静音、已知泄漏图像及字体错误诊断通过。保护日志不是独立 WindowServer 持续观察，也不是完整堆/GPU 内存审计。
首次菜单运行失败及清理证据保留；重跑 `identityResamples=[]`，因此首次错误仍不能实证归因为 exec 采样竞态。完整离线 Python 检查 67 项通过。

完整动画、弹道特效、Android、模组、观战/共享控制、房主迁移及回放暂停/跳转是延期功能，与已经通过的本阶段必需 GUI 检查分开记录。
