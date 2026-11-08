# 权威联机协议 v6：客户端规范

协议版本：6。文档修订：r1。日期：2026-11-07。

本文定义 TCP 字节流上的消息格式、状态重建、命令授权和恢复规则。客户端接收本席位获准知道的状态，不接收完整世界、游戏存档、地图脚本或单位配置文件。公开静态地图先按摘要进行一次缓存握手，再在连接中初始化一次；已验证的缓存命中时只传引用；世界状态与动作面板分别使用基线和差量，并对完整授权状态进行周期摘要检查。

v6 端口仅接受版本 6，不进行版本协商或自动降级；原版锁步兼容版本 176 属于独立模式，不保证跨模式识别或提示。

“必须”表示格式或互通要求；“本服务配置”表示运行条件和接入限额；“建议”表示客户端策略，不增加字段。除明确由存在位或目标标签省略的字段外，表中字段均须按顺序出现。后文所有范围均包含两端，明确写出的半开坐标区间除外。

## 1. 基本类型与通用校验

多字节数值一律大端；字段连续排列，无对齐、填充、变长整数或自动类型信息。

| 记号 | 字节数 | 编码 |
|---|---:|---|
| `i32` | 4 | 有符号二进制补码，−2³¹..2³¹−1 |
| `i64` | 8 | 有符号二进制补码，−2⁶³..2⁶³−1 |
| `u8` | 1 | 无符号整数，0..255 |
| `u16` | 2 | 无符号整数，0..65535 |
| `f32` | 4 | IEEE 754 binary32 |
| `f64` | 8 | IEEE 754 binary64 |
| `bool` | 1 | `00`=false，`01`=true；其他值非法 |
| `text(N)` | 4+L | `i32 L`，随后 L 个标准 UTF-8 字节；0≤L≤N |
| `bytes(N)` | 4+L | `i32 L`，随后 L 个原始字节；长度下限见具体字段 |

所有浮点字段必须有限，禁止 NaN 和正负无穷。正零、负零均是合法的零表示，但仍须满足字段范围：例如 `maxHealth>0` 不接受任何零。发送端可以使用正零；接收端不得把负零当成缺失值。

所有计数和长度必须先校验再分配。集合上限、地图上限与帧字节上限同时生效；合法的最大数量不保证任意字段长度组合都能装入一帧。长度、乘积、偏移相加须避免整数溢出。每个集合还必须能装入当前帧剩余字节；不能以限额通过代替检查截断。

序号和句柄使用完整 64 位整数精度；JavaScript/TypeScript 应使用 `BigInt`。本协议的序号全部从正数开始，0 不是合法序号。只有明确规定的选择清空字段允许句柄 0。

### 1.1 字符串

L 是 UTF-8 字节数，不是字符数。没有末尾 NUL，不使用 modified UTF-8。必须拒绝非法、截断、非最短的 UTF-8 和代理项编码，并拒绝 U+0000..U+001F、U+007F..U+009F 控制字符。不能替换非法字节、自动 trim 或删除字符串中的 BOM。

标为 `nonBlank` 的字段不能空或全为空白。排除已禁止的控制字符后，空白集合是 U+0020、U+00A0、U+1680、U+2000..U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。

`presentationKey` 和 `producedTypeKey` 另限 ASCII 字符 `[A-Za-z0-9_.-]`；前者长度 1..128 字节，后者长度 0..128 字节。普通单位 `typeName` 不适用这个 ASCII 限制。

## 2. 外层帧

下表偏移从完整帧开头起算，零基。

| 偏移 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | `payloadLength: i32` | 9..4,194,304，不包含自身 |
| 4 | 4 | `magic: i32` | `0x52575841`，字节 `52 57 58 41` |
| 8 | 4 | `wireVersion: i32` | 6，字节 `00 00 00 06` |
| 12 | 1 | `messageType: u8` | 下表定义的方向与类型 |
| 13 | payloadLength−9 | 主体 | 精确解析，不得有尾随字节 |

完整帧大小为 `4+payloadLength`。4 MiB 与 64 KiB 限制均指 payloadLength，包含 magic、版本、消息类型，不含长度前缀。

| 类型 | 十六进制 | 方向 | 名称 |
|---:|---|---|---|
| 1 | `01` | 客户端→服务端 | Hello |
| 2 | `02` | 客户端→服务端 | KeepAlive |
| 3 | `03` | 客户端→服务端 | Order |
| 4 | `04` | 客户端→服务端 | ActionPanelRequest |
| 5 | `05` | 客户端→服务端 | ActionIntent |
| 6 | `06` | 客户端→服务端 | ResyncRequest |
| 7 | `07` | 客户端→服务端 | MapReady |
| 8 | `08` | 客户端→服务端 | StateAck |
| 101 | `65` | 服务端→客户端 | Welcome |
| 102 | `66` | 服务端→客户端 | MapInit |
| 103 | `67` | 服务端→客户端 | Baseline |
| 104 | `68` | 服务端→客户端 | Delta |
| 105 | `69` | 服务端→客户端 | Rejected |
| 106 | `6A` | 服务端→客户端 | ActionPanelBaseline |
| 107 | `6B` | 服务端→客户端 | ActionPanelDelta |
| 108 | `6C` | 服务端→客户端 | MapCached |
| 109 | `6D` | 服务端→客户端 | MapOffer |
| 110 | `6E` | 服务端→客户端 | StateCheck |

| 消息 | payloadLength |
|---|---:|
| KeepAlive | 9 |
| ResyncRequest | 10 |
| Welcome | 17 |
| MapOffer、MapCached | 41 |
| MapReady、StateCheck、StateAck | 42 |
| ActionPanelRequest | 33 |
| ActionIntent | 51 或 59，取决于目标类型 |
| ActionPanelBaseline、ActionPanelDelta | ≤65,536 |
| 其余 | ≤4,194,304，并满足主体格式 |

未知类型、方向错误、版本错误、未知标签、未定义标志位、非法布尔或尾随字段均非法。不存在可跳过的扩展字段。

### 2.1 流读取与错误处理

TCP 不保留帧边界。一次读取可能只得到半帧，也可能包含多帧。必须先读满长度和固定头，完成限额校验，再按声明长度读满主体。所有字段读取限制在该帧内；不得依据 socket 当时已有字节数判断一帧是否完整。

非法帧、截断和语义不合法的状态须终止连接，不能扫描后续 magic 尝试恢复。只有第 10 节明确列出的差量基线失配、完整状态检查缺少状态或摘要不一致允许请求重同步。网络帧不接受长度 0。

## 3. 建立连接与认证

顺序为 TCP→TLS→Hello→Welcome 或 Rejected。认证通过后必须先完成 MapOffer→MapReady→MapInit/MapCached→Baseline 的地图握手；在得到首个完整有效世界状态前不得发送 Order、ActionIntent、ActionPanelRequest、ResyncRequest 或 StateAck；服务端不得在首个 Baseline 前发送 StateCheck。首个世界状态允许 tick=0，此时可能仍在等待另一席位。

Hello、Welcome、MapOffer 和 MapReady 每连接各最多一次。MapOffer 只提出地图摘要，不初始化地图状态。认证 token 决定席位，昵称不决定队伍，也不保证唯一。没有客户端选择 teamId、客户端证书登录或协议版本协商消息。

### 3.1 TLS 与邀请

邀请 URI 的规范形式为：

```text
rwx-authority://HOST:PORT/?v=6&fingerprint=HEX64#TOKEN
rwx-authority://[IPv6]:PORT/?v=6&fingerprint=HEX64#TOKEN
```

- scheme 必须为小写 `rwx-authority`，path 必须为 `/`，port 显式指定且为 1..65535。
- query 必须按顺序为 `v=6&fingerprint=HEX64`，无其他参数。HEX64 为 64 个小写十六进制字符，无冒号。
- TOKEN 为 fragment 原文，32..128 个 ASCII 字母、数字、`-` 或 `_`；不进行 Base64 解码或百分号解码。
- host 为 DNS 名、IPv4 或带方括号的 IPv6；解析后主机名长 1..253，不含空白或 `/ \ ? # % @`。不接受 userinfo、`0.0.0.0` 或 `::`。
- 邀请输入上限为 1024 个 UTF-16 代码单元；先检查长度，再去除整体首尾空白并解析。

邀请连接必须使用 TLS，即使地址是 loopback。只有明确配置的 loopback 明文测试端口可以省略 TLS；邀请 URI 不提供明文模式。不能先发送 Hello 再升级 TLS。

证书指纹为 `SHA-256(服务器叶证书完整 DER 字节)`，不是 PEM 或 SPKI 摘要。客户端必须检查证书有效期，并在发送凭据前核对邀请中的指纹；建议固定长度、常量时间比较。该认证方式不要求公共 CA 链或主机名匹配。TLS 版本和密码套件由 TLS 握手协商，不在应用帧中传输。

token 是私有席位凭据。日志不得输出 token 或邀请 fragment。格式失败可能直接断连，不保证收到 Rejected；客户端不能依赖某段错误文字进行认证判断。

## 4. 客户端消息

本节和后续主体表中的 `b` 为主体内零基偏移；整帧偏移等于 `13+b`。

### 4.1 Hello：类型 1

令 P、T 分别为昵称与 token 的 UTF-8 字节数。

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4+P | `playerName: text(128)` | nonBlank |
| 4+P | 4+T | `joinToken: text(512)` | 格式允许空字符串，认证须匹配有效 token |

主体 `8+P+T` 字节，payloadLength=`17+P+T`。Hello 内没有额外版本字段、缓存计数或缓存列表。本服务配置的有效 token 至少 16 字节，邀请格式的限制更窄。地图缓存通过第 6.2 节的单图握手确认。

### 4.2 KeepAlive：类型 2

主体为空，完整帧为：

```text
00 00 00 09  52 57 58 41  00 00 00 06  02
```

没有服务端 Pong。建议在无其他上行消息约 2 秒时发送，2 秒不是格式要求。

### 4.3 CommandTarget：目标结构

Order 和 ActionIntent 共用此结构。令 K 为目标结构长度。

| tag | 名称 | 标签后字段 | K |
|---:|---|---|---:|
| 0 | None | 无 | 1 |
| 1 | Point | `x:f32`、`y:f32`，均有限 | 9 |
| 2 | Unit | `handle:i64`，必须 >0 | 9 |

先写 `tag:u8`，再写该分支字段。None 没有占位坐标或零句柄；Point 没有单位句柄；Unit 没有坐标。`Point(0,0)` 与 None 不同。

目标格式只要求点为有限数；执行时点还必须在地图范围内。单位目标使用当前连接的视图句柄，不使用全局对象编号。

### 4.4 Order：类型 3

令 N 为选择数量，K 为目标结构长度。

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `sequence:i64` | >0，与 ActionIntent 共用严格递增序列 |
| 8 | 8 | `viewSequence:i64` | >0，引用有效世界状态 |
| 16 | 1 | `kind:u8` | 1..4，见下表 |
| 17 | 4 | `unitCount:i32` | 1..256 |
| 21 | 8N | `unitHandles:i64[N]` | 全部 >0，列表内无重复 |
| 21+8N | K | `target:CommandTarget` | 必须符合 kind |

主体 `21+8N+K` 字节。

| kind | 名称 | 唯一合法目标 |
|---:|---|---|
| 1 | MOVE | Point |
| 2 | ATTACK_MOVE | Point |
| 3 | STOP | None |
| 4 | ATTACK_UNIT | Unit |

Order 不包含建造、生产、类型名称或原生动作编号。所有建造、生产和其他能力操作使用 ActionIntent。

### 4.5 ActionPanelRequest：类型 4

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `querySequence:i64` | >0，本连接严格递增，独立于命令序列 |
| 8 | 8 | `viewSequence:i64` | >0，引用有效世界状态 |
| 16 | 8 | `unitHandle:i64` | ≥0，0 表示清空选择 |

主体固定 24 字节。一次查询一个己方可控单位。查询过期、未知、越权或不可控单位，以及清空选择时，返回形式统一为 `unitHandle=0`、空动作列表；不返回隐藏对象是否存在等原因。

### 4.6 ActionIntent：类型 5

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `sequence:i64` | >0，与 Order 共用严格递增序列 |
| 8 | 8 | `viewSequence:i64` | >0，引用有效世界状态 |
| 16 | 8 | `panelSequence:i64` | >0，引用有效面板 |
| 24 | 8 | `unitHandle:i64` | >0，面板所属单位 |
| 32 | 8 | `actionHandle:i64` | >0，来自该面板 |
| 40 | K | `target:CommandTarget` | 由面板 targetMode 决定 |
| 40+K | 1 | `flags:u8` | bit 0=queued，bit 1=cancel，其余位为 0 |

主体 `41+K` 字节，即 42 或 50 字节。payloadLength 为 51 或 59。

非取消操作必须使用面板指定的目标：NONE→None，POINT→Point，VISIBLE_UNIT→Unit。取消时必须为 None 且 queued=false，因此 flags=3 非法。取消的是该动作的一项真实排队任务；以 `canCancel` 提示用户是否可以取消，实际仍由服务端重新校验。显式提供的“取消施工”等动作按钮属于普通动作，不能根据名称自行设置 cancel=true。

本服务配置允许 queued=true 的种类为 BUILD、REPAIR、RECLAIM、LOAD_INTO、LOAD_UP、GUARD、PATROL。其他种类必须为 false。生产任务的工厂队列不依赖 queued=true。

### 4.7 ResyncRequest：类型 6

主体只有 `channels:u8`：bit 0=WORLD，bit 1=PANEL。合法值为 1、2、3；0 和其他位非法。此消息没有序号，不消耗命令或查询序列。它请求已有通道的完整基线，不请求重新初始化地图，不重新认证。具体恢复规则见第 10 节。

### 4.8 MapReady：类型 7

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 32 | `contentKey:32 bytes` | 必须精确等于本连接唯一 MapOffer 中的摘要 |
| 32 | 1 | `haveMap:bool` | 是否已校验并保留该完整地图 |

主体固定 33 字节，payloadLength=42，整帧 46 字节。此消息没有序号，不消耗命令或查询序列。仅在收到 MapOffer 后发送一次；不得提前发送、重复发送或改用其他 key。

haveMap=true 只表示客户端已持有完整、校验通过并钉在内存中的该公共地图；不表示拥有动态世界基线。缺失、损坏或无法读取的本地缓存均回复 false。详细握手和哈希范围见第 6.2 节。

### 4.9 StateAck：类型 8

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 1 | `channel:u8` | 1=WORLD，2=PANEL；不允许 0、3 或其他值 |
| 1 | 8 | `checkSequence:i64` | >0，回复所接收检查的序号 |
| 9 | 8 | `stateSequence:i64` | >0，回复该检查指定的世界或面板序号 |
| 17 | 16 | `digest:16 bytes` | 客户端对完整原始授权状态计算的摘要，无长度前缀 |

主体固定 33 字节，payloadLength=42，整帧 46 字节。StateAck 不消耗命令序列或查询序列；不能发起新的检查。channel、checkSequence、stateSequence 必须原样对应收到的 StateCheck；digest 必须是本地计算值，不能直接回显服务端摘要。没有该完整状态时不发送伪造或占位摘要，按第 10.5 节请求基线。

## 5. 服务端基础消息

### 5.1 Welcome：类型 101

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | `teamId:i32` | 0..63 |
| 4 | 4 | `ticksPerSecond:i32` | 1..1000 |

主体 8 字节，payloadLength=17。Welcome 没有额外版本字段。`tick/ticksPerSecond` 表示仿真时间；ticksPerSecond 不是网络发包频率、刷新率或播放速度倍率。客户端不得假定恒为 60。

### 5.2 Rejected：类型 105

主体为 `reason:text(1024)`，nonBlank。主体 `4+L` 字节。展示原因后关闭连接，没有数值错误码或可重试标志。无效 token 与席位已占用不作区分。不要依赖英文字符串匹配；格式、传输或限流错误可能直接断开。

### 5.3 StateCheck：类型 110

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 1 | `channel:u8` | 1=WORLD，2=PANEL；不允许 0、3 或其他值 |
| 1 | 8 | `checkSequence:i64` | >0，在本连接所有通道之间严格递增 |
| 9 | 8 | `stateSequence:i64` | >0，WORLD 为世界 sequence，PANEL 为 panelSequence |
| 17 | 16 | `digest:16 bytes` | 完整授权状态摘要，无长度前缀 |

主体固定 33 字节，payloadLength=42，整帧 46 字节。两个通道共用同一检查序列；不要求相邻检查序号恰好加一。它独立于命令、查询、世界和面板序列。相同状态可以多次检查，每次 checkSequence 仍须增加；每个通道被检查的 stateSequence 不得倒退。

StateCheck 描述按流顺序已完整发送的当前状态，不是未发送的新世界状态。检查帧不能推进世界 tick、世界 sequence 或 panelSequence，不产生回放帧。摘要规则、恢复顺序与运行频率见第 10.5 节。

## 6. 静态地图：MapInit，类型 102

每连接必须且只能以一个 MapInit 或 MapCached 初始化地图，并先于首个世界 Baseline。重连是新连接，重新进行地图初始化，可以再次命中缓存；同一连接的世界重同步不重复地图初始化。地图数据是对局开始时固定的公共状态，不随着隐藏实体、资源点占用或终局改变。

令 `C=width×height`。

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | `width:i32` | 1..4096，格数 |
| 4 | 4 | `height:i32` | 1..4096，格数 |
| 8 | 4 | `tileWidth:i32` | 1..4096，世界坐标尺寸 |
| 12 | 4 | `tileHeight:i32` | 1..4096，世界坐标尺寸 |
| 16 | 4 | `terrainCount:i32` | 必须等于 C，C≤1,048,576 |
| 20 | C | `terrain:u8[C]` | 每格 0..15 |
| 20+C | 1 | `hasVisual:bool` | 是否附带公开图块层 |
| 21+C | 0 或 12+4C | visual | 仅 hasVisual=true 时出现 |

无 visual 的主体大小为 `21+C`；有 visual 为 `33+5C`。

格子统一行优先：`index=y×width+x`。世界坐标原点左上，x 向右、y 向下；地图范围为 `[0,width×tileWidth)` 和 `[0,height×tileHeight)`。

terrain 是 bitmask：1=水，2=悬崖，4=资源池，8=阻止建造。允许组合，未知高位非法。它是静态地形提示，不是实时碰撞、完整寻路、可建造性或资源池空闲证明。

### 6.1 visual

存在时必须 tileWidth=tileHeight=20，且 C≤16,384。

| 项内偏移 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | `catalogVersion:i32` | 必须为 1 |
| 4 | 4 | `groundCount:i32` | 必须等于 C |
| 8 | 2C | `groundTileIds:u16[C]` | 每项 0..234 |
| 8+2C | 4 | `decorationCount:i32` | 必须等于 C |
| 12+2C | 2C | `decorationTileIds:u16[C]` | 每项 0..234 |

0 表示透明空图块。图块 ID 不是任意文件路径或地图对象编号。缺少 visual 时可按 terrain 使用颜色呈现；未知目录版本或 ID 是格式错误，不能尝试加载网络指定资源。

目录 1 的闭合映射如下。各格裁剪尺寸均为 20×20；表内文件名是固定本地资源标识，统一前缀 `tilesets/bitmaps/`。

| ID 范围 | 固定图集 | 图集尺寸 | 允许局部索引 |
|---|---|---|---|
| 1..80 | snow.png | 160×200 | 0..79 |
| 81..90 | ice.png | 100×40 | 0..9 |
| 91..96 | shallowwater.png | 60×40 | 0..5 |
| 97..99 | water.png | 60×20 | 0..2 |
| 100..120 | snow2ice_ridge.png | 60×140 | 0..20 |
| 121..141 | ice2shallowwater_ridge.png | 60×140 | 0..20 |
| 142..162 | shallowwater2water_flat.png | 60×140 | 0..20 |
| 163..183 | ice2ice_flat.png | 60×140 | 0..20 |
| 184..204 | ice2shallowwater_broken.png | 60×140 | 0..20 |
| 205..225 | stone2snow.png | 60×140 | 0..20 |
| 226..234 | misc.png | 79×160 | 0..8 |

对于表内起始 ID `B`，局部索引 `i=id−B`，列数 `columns=floor(图集宽度/20)`；裁剪左上角为 `(20×(i mod columns),20×floor(i/columns))`。misc 只有前 9 格合法，不能据图集尺寸自行扩充 ID。

建议绘制顺序为 ground→decoration→当前单位→迷雾。公共图块和 terrain 位不代替服务器对实体的披露判断。

### 6.2 地图摘要与两阶段握手

MapOffer（类型 109）的主体固定为 `contentKey:32 bytes`，没有内层长度、存在位或目录版本。payloadLength=41，整帧 45 字节。服务端在 Welcome 后发送一次 Offer，随后等待对应的 MapReady；Offer 自身不初始化地图，也不代替 MapInit 或 MapCached。

contentKey 定义为：

```text
SHA-256(MapInit 主体的精确字节序列)
```

哈希范围从 width 的第一个字节开始，到 hasVisual=false 的标志字节或 visual 的最后一个图块字节结束，包含各计数、hasVisual 和 catalogVersion；不包含网络长度、magic、wireVersion 或 messageType。线上 key 是 32 个原始摘要字节，不是 64 字符十六进制文本。

客户端收到 Offer 后只需查找该 key 对应的一张缓存，不必载入全部磁盘缓存。回复 haveMap=true 前必须：

1. 完整读取地图，校验 MapInit 的全部格式、地图尺寸、terrain、visual 目录和图块边界。
2. 按上述精确字节域重算摘要，确认与 Offer 相同；不能只信任文件名或缓存索引。
3. 将已验证的完整地图保留在当前连接可用的内存中，然后发送同 key 的 MapReady(true)。

没有可用缓存、缓存被删除、截断、格式错误或摘要不匹配时，发送同 key 的 MapReady(false)。缓存 I/O 失败不能伪报 true，也不需要新增 miss 消息或重同步通道。回复 true 后磁盘文件被删除不影响已钉住的内存地图。

双方只进行一次如下分支：

| MapReady | 服务端地图响应 | 后续 |
|---|---|---|
| haveMap=false | MapInit | 世界 Baseline |
| haveMap=true | MapCached，携带相同 key | 世界 Baseline |

MapInit 必须按同一字节域计算出 Offer 中的 key；MapCached 必须匹配 Offer 及已钉住的地图。初始化成功后，地图在本连接保持不变。提前或重复 Offer/Ready、错误 key、响应与 haveMap 分支冲突以及重复初始化均为连接错误，不能用 WORLD ResyncRequest 修补。

等待地图握手完成期间，上行只允许 KeepAlive 和首次合法 MapReady；Order、ActionIntent、ActionPanelRequest 和 ResyncRequest 均非法。等待 MapReady 时不发送动作面板或动态世界状态；服务器可以合并此时产生的中间世界版本，随后发送最新完整 Baseline。

本服务配置的地图握手有独立 10 秒绝对期限。服务端从开始写出 Offer 起计时，直到首个 Baseline 完整写出；客户端从接收 Offer 起计时，直到首个 Baseline 完整重建并接纳。双方各用自己的单调时钟，KeepAlive 不延长期限；无须同步双方系统时间。

### 6.3 MapCached：类型 108 与缓存范围

MapCached 主体固定为 `contentKey:32 bytes`，payloadLength=41，整帧 45 字节。它只在本连接已回复 MapReady(true) 时合法，代替 MapInit 完成唯一一次地图初始化，随后仍必须接收世界 Baseline。服务端不传缓存文件路径、文件名或资源请求。

仅公共静态地图可缓存。世界 sequence、fog、单位、credits、终局历史、动作面板、句柄和凭据均不属于地图缓存，不能因 contentKey 相同而复用上次连接的这些状态。客户端可将完整验证后的 MapInit 保存供以后连接使用。

磁盘缓存数量、文件格式和淘汰策略属于客户端本地设置，不是线上字段。关闭缓存的客户端仍使用同一握手，回复 haveMap=false。网络每次只确认当前对局所需的一个 key；没有缓存列表和一次载入全部缓存的要求。

## 7. 世界状态通道

世界状态由本连接初始化的静态地图和最近成功接纳的动态状态构成。Baseline 完整替换动态状态；Delta 只能应用于它指定的精确基线。必须先完整解析、重建并校验，再一次性发布新状态，不得显示半个补丁。

### 7.1 Baseline：类型 103

令 F 为 fogCount，U 为所有 UnitRecord 字节数之和，S 为 MatchStatus 大小。

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `sequence:i64` | >0 |
| 8 | 4 | `tick:i32` | ≥0 |
| 12 | 4 | `teamId:i32` | 0..63，必须等于 Welcome.teamId |
| 16 | 8 | `credits:f64` | 有限，允许负数 |
| 24 | 4 | `fogCount:i32` | 1..1,048,576，必须等于地图 C |
| 28 | F | `fog:u8[F]` | 每项 0..10 |
| 28+F | 4 | `unitCount:i32` | 0..65,536 |
| 32+F | U | `units:UnitRecord[]` | 句柄唯一 |
| 32+F+U | S | `matchStatus:MatchStatus` | 第 7.5 节 |

主体大小 `32+F+U+S`。Baseline 不包含 terrain、地图尺寸或 visual；它们来自 MapInit 或已验证的 MapCached 引用。

### 7.2 UnitRecord

令 T 为 typeName 的 UTF-8 字节数。每项 `46+T` 字节，最少 47 字节。

| 项内偏移 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `handle:i64` | >0，当前视图内唯一 |
| 8 | 4+T | `typeName:text(256)` | nonBlank，不透明公开类型 key |
| 12+T | 4 | `teamId:i32` | −2..63 |
| 16+T | 4 | `x:f32` | 有限，0≤x<width×tileWidth |
| 20+T | 4 | `y:f32` | 有限，0≤y<height×tileHeight |
| 24+T | 4 | `height:f32` | 有限 |
| 28+T | 4 | `heading:f32` | 有限，单位为度 |
| 32+T | 4 | `health:f32` | 有限且 ≥0 |
| 36+T | 4 | `maxHealth:f32` | 有限且 >0 |
| 40+T | 4 | `buildProgress:f32` | 0..1 |
| 44+T | 1 | `controllable:bool` | 当前控制提示 |
| 45+T | 1 | `upgradeLevel:u8` | 1..3 |

不要求 health≤maxHealth，不把 heading 限制在 0..360；height 可以为负。没有独立炮塔角度、动画帧、路径、订单、攻击目标、载员或敌方资源字段。显示插值不得产生新的实体信息，也不得使已撤销的单位继续成为操作目标。

### 7.3 Delta：类型 104

主体按下表顺序排列。偏移为“紧接上一项”，可选字段不存在时不占字节。

| 顺序 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 1 | 8 | `baseSequence:i64` | >0 |
| 2 | 8 | `sequence:i64` | >baseSequence |
| 3 | 4 | `tick:i32` | ≥0 |
| 4 | 4 | `teamId:i32` | 0..63，等于本席位 |
| 5 | 1 | `flags:u8` | bit 0=credits，bit 1=fog，bit 2=matchStatus；其他位 0 |
| 6 | 0 或 8 | `credits:f64` | 仅 bit 0；有限 |
| 7 | 0 或变长 | `fog:FogDelta` | 仅 bit 1 |
| 8 | 4 | `enteredCount:i32` | 0..65,536 |
| 9 | 变长 | `enteredUnits:UnitRecord[]` | 完整新披露单位 |
| 10 | 4 | `patchCount:i32` | 0..65,536 |
| 11 | 变长 | `unitPatches:UnitPatch[]` | 已披露单位的字段替换 |
| 12 | 4 | `removedCount:i32` | 0..65,536 |
| 13 | 8×removedCount | `removedHandles:i64[]` | >0 |
| 14 | 0 或变长 | `matchStatus:MatchStatus` | 仅 bit 2；位于所有单位操作之后 |

前 5 项固定 25 字节。令 Ec、Pc 为单位新增和补丁总字节数，R 为移除数，Bfog、Bstatus 为可选块实际字节数，则主体大小：

```text
37 + (存在 credits ? 8 : 0) + Bfog + Ec + Pc + 8R + Bstatus
```

三种单位操作的句柄必须共同唯一，不能同帧先删除再新增同一句柄。新增句柄必须不在基线单位集合中；补丁与移除句柄必须在其中。重建后的总单位数量仍不得超过 65,536。

缺失 credits、fog、matchStatus 表示保持原值。允许所有可选字段和单位操作均为空的 Delta，用于推进 sequence/tick；不得把这种消息误判为断线。

移除只表示该单位不再属于当前授权视图，不表示死亡。客户端须撤销其当前实体状态和操作句柄；可以保留此前合法观察到的历史记录，但不能把历史位置当成最新敌情或有效目标。

### 7.4 UnitPatch 与 FogDelta

UnitPatch 先写 `handle:i64`，再写 `mask:u16`，然后按位号从低到高写存在的字段。mask 必须为 1..0x07FF，不能为空或含未知位。字段校验与 UnitRecord 相同；缺失表示保留原值，不是清零。

| 位 | 十六进制掩码 | 字段 | 字节数 |
|---:|---|---|---:|
| 0 | `0001` | typeName:text(256) | 4+T |
| 1 | `0002` | teamId:i32 | 4 |
| 2 | `0004` | x:f32 | 4 |
| 3 | `0008` | y:f32 | 4 |
| 4 | `0010` | height:f32 | 4 |
| 5 | `0020` | heading:f32 | 4 |
| 6 | `0040` | health:f32 | 4 |
| 7 | `0080` | maxHealth:f32 | 4 |
| 8 | `0100` | buildProgress:f32 | 4 |
| 9 | `0200` | controllable:bool | 1 |
| 10 | `0400` | upgradeLevel:u8 | 1 |

每项大小为 `10+所有存在字段大小`，最少 11 字节。字段范围须在应用前校验，地图上界与组合后的完整单位约束须在重建后再次校验。

FogDelta 先写 `kind:u8`：

| kind | 名称 | 后续格式 | 总字节数 |
|---:|---|---|---|
| 0 | Replace | `count:i32`，随后 count 个 u8 | 5+count |
| 1 | Runs | `runCount:i32`，随后各 run | 5+Σ(8+runLength) |

Replace 的 count 必须等于地图 C，且 1..1,048,576。Runs 的 runCount 为 1..1,048,576；每个 run 为 `start:i32`、`length:i32`、`values:u8[length]`，length 为 1..1,048,576。

所有迷雾值均为 0..10。start 使用行优先一维索引，不是 x 坐标。各 run 必须按 start 排序、非空、不重叠，`start≥0`、`start+length≤C`；相邻 run 允许首尾相接，允许跨行。未覆盖格保持基线值。发送端不必使用最少数量的 run；接收端不能依赖某一种分段方式。

迷雾 10 表示未探索，5..9 表示已探索或视野边缘衰减但当前不可见，0..4 表示当前视野范围。格子小于 5 不保证所有位于该格的实体都会披露；实体列表仍是唯一授权来源。

### 7.5 MatchStatus

| 顺序 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 1 | 1 | `phase:u8` | 0=RUNNING，1=FINISHED |
| 2 | 4 | `winnerCount:i32` | 0..64 |
| 3 | 4W | `winnerTeamIds:i32[W]` | 各 0..63，列表内唯一 |
| 4 | 4 | `defeatedCount:i32` | 0..64 |
| 5 | 4D | `defeatedTeamIds:i32[D]` | 各 0..63，列表内唯一 |

大小 `9+4(W+D)`。两列表无交集；RUNNING 两者均空；FINISHED 至少一个列表非空，胜者列表可以为空。列表不是全部队伍排名。

FINISHED Baseline 必须没有单位。带 FINISHED 状态的 Delta 不得包含 enteredUnits 或 unitPatches，移除操作必须使重建后单位集合为空。即使 Delta 不携带状态，重建后的已结束状态仍须保持空单位集合。

一旦接纳 FINISHED，不得接受 RUNNING 或改变胜负列表的后续状态。终局不意味着全图观战：保留此前获准得到的迷雾，清除单位及操作能力；终局后新连接没有先前视野历史，不能借重连获得全图敌情。

## 8. 动作面板通道

动作面板独立于世界帧容量和更新节奏。它只包含一个所选单位及其服务端提供的能力，不要求为全部己方单位持续展开动作目录。

### 8.1 ActionPanelBaseline：类型 106

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `querySequence:i64` | >0，关联面板查询 |
| 8 | 8 | `panelSequence:i64` | >0，面板通道序号 |
| 16 | 8 | `viewSequence:i64` | >0，关联世界状态 |
| 24 | 4 | `teamId:i32` | 0..63，等于本席位 |
| 28 | 8 | `unitHandle:i64` | ≥0，0 表示空面板 |
| 36 | 4 | `actionCount:i32` | 0..128 |
| 40 | 变长 | `actions:ActionRecord[]` | 面板内动作句柄唯一 |

unitHandle=0 时 actionCount 必须为 0。有效选择也允许空动作列表。基线完整替换面板，包括动作顺序。

### 8.2 ActionRecord

| 顺序 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 1 | 8 | `handle:i64` | >0，面板内唯一 |
| 2 | 1 | `kind:u8` | 1..15，下表 |
| 3 | 1 | `targetMode:u8` | 0=NONE，1=POINT，2=VISIBLE_UNIT |
| 4 | 4+K | `presentationKey:text(128)` | 非空 ASCII key |
| 5 | 4+P | `producedTypeKey:text(128)` | 可空 ASCII key |
| 6 | 1 或 21 | `upfrontCost:ActionCost?` | 存在 bool 后可选 20 字节 |
| 7 | 1 或 21 | `streamingCost:ActionCost?` | 独立存在 bool |
| 8 | 1 | `readiness:u8` | 0=READY，1=BLOCKED，2=UNKNOWN |
| 9 | 4 | `queuedCount:i32` | −1..65,536 |
| 10 | 4 | `stockpileCount:i32` | −1..65,536 |
| 11 | 4 | `progress:f32` | −1，或 0..1；有限 |
| 12 | 1 | `canCancel:bool` | 真实排队任务的取消提示 |

每项大小 `34+K+P+20×存在费用块数量`，最少 35 字节。两个费用存在位互相独立，缺失费用不是零费用。−1 表示未知，0 表示已知为零；不能把 stockpileCount 当成排队任务数量。

READY 不是执行保证，BLOCKED 是已知当前不能开始，UNKNOWN 表示不发布精确可用性。UNKNOWN 不应被当成零成本或默认可执行；发出操作仍须经服务端授权和资源检查。

| kind | 名称 | 含义 |
|---:|---|---|
| 1 | BUILD | 在地图点建造 |
| 2 | PRODUCE | 生产单位或库存项 |
| 3 | UPGRADE | 升级或转换 |
| 4 | RALLY | 设置集结点 |
| 5 | REPAIR | 修理当前可见目标 |
| 6 | RECLAIM | 回收当前可见目标 |
| 7 | LOAD_INTO | 所选单位作为乘客，目标为运输器 |
| 8 | LOAD_UP | 所选单位作为运输器，目标为乘客 |
| 9 | UNLOAD | 开始卸载 |
| 10 | STOP_UNLOAD | 停止卸载 |
| 11 | TARGET_GROUND | 对地图点执行特殊动作 |
| 12 | TOGGLE | 切换或其他无目标动作 |
| 13 | GUARD | 守卫当前可见单位 |
| 14 | PATROL | 向地图点巡逻 |
| 15 | ATTACK_MODE | 切换攻击模式 |

客户端以收到的 targetMode 生成目标结构，不能从文字或类型名称猜测原生动作编号。presentationKey 供本地翻译和按钮识别，不是可执行代码或授权凭证。producedTypeKey 仅用于展示；客户端不能用它替换 actionHandle 发出生产命令。字段枚举值合法不代表任意组合都具有可执行能力。

ActionCost 的存在位为 false 时没有后续字段；为 true 时依次为：`credits:i32`、`energy:f32`、`hp:f32`、`shield:f32`、`ammo:i32`。三个浮点必须有限；所有五项允许负数，不应按无符号数读取或自行夹为零。

### 8.3 ActionPanelDelta：类型 107

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `basePanelSequence:i64` | >0 |
| 8 | 8 | `querySequence:i64` | >0 |
| 16 | 8 | `panelSequence:i64` | >basePanelSequence |
| 24 | 8 | `viewSequence:i64` | >0 |
| 32 | 4 | `teamId:i32` | 0..63，等于本席位 |
| 36 | 8 | `unitHandle:i64` | ≥0，必须与基线面板相同 |
| 44 | 4 | `updateCount:i32` | 0..128 |
| 48 | 变长 | `updates:ActionStatePatch[]` | 句柄唯一且已存在于基线 |

unitHandle=0 时 updateCount 必须为 0。允许空 updates 推进序号或查询关联。差量不添加、删除或重排动作，不改变 kind、targetMode、展示 key、产出 key 或费用；这些变化必须用新的 ActionPanelBaseline。

ActionStatePatch 为 `handle:i64`、`mask:u8`、按位号递增排列的存在字段。mask 为 1..31，不能空或含其他位。

| 位 | 十六进制掩码 | 字段 | 字节数 |
|---:|---|---|---:|
| 0 | `01` | readiness:u8 | 1 |
| 1 | `02` | queuedCount:i32 | 4 |
| 2 | `04` | stockpileCount:i32 | 4 |
| 3 | `08` | progress:f32 | 4 |
| 4 | `10` | canCancel:bool | 1 |

字段约束与 ActionRecord 相同。每项大小 `9+所有存在字段大小`，最少 10 字节；缺失字段保留原值。

## 9. 序号、授权与运行配置

### 9.1 序号与句柄

Order 和 ActionIntent 共用命令 sequence，必须严格递增；允许跳号。ActionPanelRequest 使用独立、严格递增的 querySequence。世界 sequence 和 panelSequence 分属两个服务端状态通道，客户端不能混用。

所有序号均须 >0，建议首个值为 1。命令即使因目标、资源或授权不足没有执行，也不能重用其 sequence。没有逐命令 ACK 或结构化执行结果消息；以后续状态判断变化，不能推测网络发送成功等于动作完成。

世界状态和面板可以跳过未发送的中间版本。Delta 的 baseSequence 必须指向该接收者已经接纳的精确世界版本，不是 `sequence−1`。面板差量同理。不能按收到的帧数推导仿真 tick 或授权有效期。

单位句柄、动作句柄属于当前连接；不同连接中的相同数值没有关联。它们是不透明标识，不承诺保密或不可猜，也不能替代席位凭据与服务端授权。移出视野、控制变化、选择变化、升级、运输和能力变化都可能撤销旧句柄。重连必须丢弃旧选择、旧基线和旧命令计数器。

### 9.2 操作权限

发送基础命令的单位须存在于所引用世界状态中，并且当前仍归本席位、可控、已披露。目标单位须同时属于引用状态与服务器当前授权视图。持有旧句柄不保证目标仍可见。点命令可以进入战争迷雾，但点必须在地图范围内。

能力操作还须引用面板所属单位及其动作句柄，并满足当前能力身份、目标模式、资源、队列和其他规则。当前可见不等于可修理、可回收或可装载；相应动作仍有归属、容量、类型和状态限制。

本服务配置保留最近至多 32 个世界授权版本、16 个面板授权版本，且各引用年龄必须在 0..180 个仿真 tick。版本可以提前撤销；收到新世界状态不能延长旧面板授权，收到新面板也不能延长旧世界授权。建议使用最新完整世界状态和最近有效面板。

### 9.3 本服务配置与建议

本服务配置为双人固定席位对局，使用动态战争迷雾，无共享控制；编码中的 teamId 范围不代表可加入 64 人对局。每个有效 token 同时只允许一个连接。

| 项目 | 本服务配置 |
|---|---|
| 上行总帧数 | 每连接每秒窗口最多 120 |
| Order 与 ActionIntent 合计 | 每连接每秒窗口最多 60 |
| ActionPanelRequest | 每连接任意连续一秒最多 8 |
| 重同步响应请求 | WORLD、PANEL 分别至多每秒接纳一次；更密显式请求可合并，摘要失败确认可触发恢复 |
| 完整状态检查 | 有效 WORLD、PANEL 各约每秒一次，使用单调时钟，独立于仿真 tick |
| 待确认检查账本 | 每连接最多 32 条，两个通道合计 |
| 连续状态恢复 | 每通道最多 3 次；第 4 次恢复关闭连接，正确摘要确认清零该通道计数；显式恢复也计入 |
| 状态摘要确认期限 | 每个有效通道 10 秒，独立于保活和读写期限 |
| 客户端摘要恢复期限 | 摘要失败后等待基线最多 10 秒，使用绝对期限 |
| 认证握手期限 | 10 秒 |
| 地图握手绝对期限 | 10 秒，KeepAlive 不续期 |
| 无完整上行消息期限 | 10 秒 |
| 持续写出阻塞期限 | 10 秒 |

这些是运行限额，不是消息中的字段。突发积压或慢连接也可能被关闭。建议面板查询不超过 5 Hz，空闲上行约 2 秒发送 KeepAlive。建议不反复请求已有完整状态，不把 ResyncRequest 当成轮询。

世界推送没有承诺固定 Hz，与 ticksPerSecond 不同。客户端应按状态中的 tick 渲染和插值，不在每个渲染帧重发命令。终局后可能停止世界推送，但已有状态的周期摘要检查继续；无新世界帧本身不证明连接断开。若继续保持连接，仍需满足上行保活要求。

## 10. 状态重建与重同步

### 10.1 正常世界流

1. 保存 Welcome 的席位和 ticksPerSecond。
2. 接收唯一 MapOffer，按第 6.2 节检查该地图缓存并回复 MapReady，再接收、校验唯一 MapInit 或 MapCached，建立固定地图。
3. 接收首个 Baseline，校验地图长度、坐标上界、席位、单位唯一性和终局约束，发布完整世界状态。
4. 后续 Baseline 正常情况下 sequence 必须大于最后接纳值，tick 不得倒退；完整替换动态状态。
5. 后续 Delta 的 sequence 必须大于 baseSequence 和最后接纳序号，tick 不得倒退；baseSequence 必须精确等于当前可用基线序号。
6. 以私有副本应用差量，校验所有新单位、补丁、移除和迷雾区间，再校验重建后的完整状态，全部成功才提交。

tick 可以相同，sequence 仍须递增。不能因坐标、血量等字段没有变化而忽略较新序号，否则下一差量的基线将不匹配。

### 10.2 世界缺基线

若 Delta 本身格式和身份、时间规则合法，但本地无可用基线或 baseSequence 不匹配：

- 不应用该 Delta，不更新最后成功的序号、tick 或终局结论。
- 清除可操作的世界状态和当前动作面板，进入同步中状态；停止发送 Order 和 ActionIntent。
- 发送 WORLD 的 ResyncRequest。保留已初始化的静态地图，不请求或接受第二次 MapInit/MapCached。
- 同步期间不能把 Delta 拼到不存在的基线上；等待完整 Baseline。合法的更大 sequence Baseline 可以恢复，不必等同序号重发。

已明确请求 WORLD 基线时，允许一次 sequence 等于最后成功序号的 Baseline，其 tick 必须也相等。它恢复当前状态，不是新的仿真事件，不应再次触发同序号录制或事件。成功接纳基线后结束该同序号例外；未请求时的重复 Baseline 非法。小于最后成功序号始终非法，终局不变规则始终有效。

### 10.3 面板缺基线与可用性

面板正常基线的 panelSequence 必须增加，querySequence 不得倒退。面板 Delta 须满足 panelSequence 大于 basePanelSequence 和最后面板序号，querySequence 不得倒退；basePanelSequence 和 unitHandle 必须匹配当前面板。

缺少对应面板基线或其选择不匹配时，丢弃该差量、清除面板并请求 PANEL。WORLD 仍可用时不必停止合法基础命令。已请求 PANEL 时允许一次与最后 panelSequence 相同的完整面板；它不表示新的面板事件。序号更大的合法完整面板也能恢复。

面板只有在世界状态有效且 RUNNING、面板 viewSequence 不大于当前世界 sequence，并且 owner 是当前已披露的己方可控单位时才可操作。unitHandle=0 的空面板可作为清空选择结果。合法但不再适用的面板不能继续授权动作。

WORLD 恢复不自动恢复旧单位选择。服务端可以接续发送仍适用的完整面板；客户端也可以重新查询所选单位或请求 PANEL，需要两个通道时可发送 channels=3。无论由哪种途径得到面板，都必须重新核对当前 owner 可用性，不得重新授权已离开当前视图的单位。

### 10.4 不能重同步掩盖的错误

以下错误必须拒绝，不得作为普通缺基线处理：错误席位、非正序号、时间倒退、重复地图初始化、未请求的重复基线、未知或重复单位补丁句柄、同帧冲突操作、迷雾区间重叠或越界、重建单位超限、地图坐标越界、非法终局以及任何字节格式错误。

客户端可以记住已合法获知的信息；必须停止接收和生成隐藏对象的新位置、生命、生产、载员等变化。全局命令流、完整存档或客户端完整世界仿真不属于此状态通道。

### 10.5 完整状态摘要与恢复

周期检查覆盖整个已经获准接收的状态，不只覆盖最近一个补丁。按完整原始数值重新编码计算；不得以渲染插值、屏幕位置、显示文本、四舍五入结果、隐藏世界或上次缓存的摘要替代。

定义 `H(x)=SHA-256(x)`，`first16` 为输出字节 0..15，`||` 为字节串连接。摘要在线上是 16 个原始字节，不是 32 字符十六进制文本。

**WORLD（channel=1）**：

```text
digest = first16(H(domainWorld || mapBody || baselineBody))
domainWorld = 52 57 58 41 2D 57 4F 52 4C 44 2D 36 00
```

域为 ASCII `RWXA-WORLD-6` 加一个 `00`，共 13 字节。`mapBody` 严格使用第 6 节 MapInit 主体，从 width 起，到最后的地图字段止；即使网络使用 MapCached，也必须从完整已校验地图生成该主体。`baselineBody` 严格使用第 7.1 节 Baseline 主体，但先将完整单位集合按正数 handle 的整数升序排列；单位字段、迷雾、资源和结果按原值编码。

**PANEL（channel=2）**：

```text
digest = first16(H(domainPanel || panelBody))
domainPanel = 52 57 58 41 2D 50 41 4E 45 4C 2D 36 00
```

域为 ASCII `RWXA-PANEL-6` 加一个 `00`，共 13 字节。`panelBody` 严格使用第 8.1 节 ActionPanelBaseline 主体，动作必须保持面板中的原始顺序，不能按句柄或名称重排。

三个主体均不含外层 payloadLength、magic、wireVersion、messageType，也不在主体间插入长度或分隔符。WORLD 的 mapBody 和 baselineBody 分别须满足各自合法帧主体容量，不能因合并后超过一个帧的上限而截断摘要输入。PANEL 仍满足面板主体容量。所有字符串、存在位、集合计数、迷雾、静态地形、图块层、单位字段、升级等级、胜负列表、面板描述、价格、动态状态以及这些主体中的状态序号均参与摘要。checkSequence 与 StateCheck/StateAck 的外层字段不另行加入摘要。

浮点数使用收到并重建的原始 IEEE 754 编码；`-0` 和 `+0` 必须产生不同的摘要输入。不要改变列表顺序，WORLD 单位排序是唯一额外规范化。不能只计算地图 key、差量字节或部分字段的摘要。

若本地完整状态已违反字段格式约束或无法计算摘要，必须终止连接，不能用占位摘要继续确认；本节的摘要不一致恢复不保证修复任意形式的损坏状态。

收到 StateCheck 后按以下顺序处理：

1. 校验检查序号在整个连接中严格增加，且该通道 stateSequence 不倒退。若本地存在完整有效通道状态，其序号必须精确等于检查指定的序号；有状态但序号不符是协议错误，不能给另一个状态计算确认。
2. 若缺少完整通道状态，先标记该通道等待基线并撤销相应可操作状态，再请求相应通道的 ResyncRequest；不发送 StateAck，不复制服务端摘要作为确认。WORLD 缺失同时清除面板，PANEL 缺失只清除面板。
3. 有完整状态时重新计算上述摘要。相等则发送 StateAck，三个身份字段与检查一致，digest 使用本地计算值。
4. 摘要不同则**先**进入相应通道等待基线的状态、撤销操作许可，再发送携带本地实际摘要的 StateAck。该 ACK 是这次恢复的唯一请求，**不另发相同恢复的 ResyncRequest**。不得先发 ACK 再标记恢复状态，否则可能拒绝快速返回的同序号基线。
5. 摘要恢复进行期间，该通道缺少基线的 Delta 或新的检查不触发重复 ResyncRequest；没有完整状态仍不发送伪造 ACK。WORLD 摘要恢复同时覆盖被清除的面板。若不是正在等待摘要恢复，普通缺基线仍按第 10.2、10.3 节发送显式请求。
6. 摘要不同的 StateAck 对服务端本身就表示该通道需要恢复。可接纳一次已请求的同序号完整基线，或合法更大序号基线。恢复 WORLD 不重复初始化地图；PANEL 恢复不阻止仍有效的基本 Order。

一次 WORLD 撤销同时清除面板，并允许其完整基线恢复。客户端必须立即停止依赖被撤销状态的新操作；已经排队、依赖旧授权状态的操作也必须撤销，即使新基线很快恢复，旧操作也不能重新获得发送资格。PANEL 撤销仅阻止能力操作，不阻止仍有效的基本 Order。收到恢复基线不重复记录相同世界序号。

本服务配置在首个有效世界基线后开始周期检查，按单调时钟为每个有效通道约每秒发送一次；检查不依赖仿真 tick 推进。WORLD 在静止或终局后仍检查；没有有效面板时不发送 PANEL 检查，面板失效时撤销其待确认记录。调度、计算或网络阻塞可能推迟发送，不承诺硬实时到达间隔。

每连接的待确认账本最多 32 条，超限淘汰最早记录。已确认、被撤销或被淘汰检查的迟到 ACK 忽略；未来未签发的 checkSequence，或仍在账本中的检查被 ACK 用错误 channel/stateSequence 引用，必须断连。ACK 不推进世界或面板状态。

服务端为每个有效通道维护独立的 10 秒确认期限：从该通道首个尚未正确确认的检查发出时起计时，接纳仍在账本中、身份和摘要都正确的 ACK 后结束这一等待。之后发出的检查可以开始下一段期限。更多检查、KeepAlive、错误摘要 ACK、ResyncRequest、发送恢复基线或淘汰账本记录均不能延长当前期限；到期必须断连。面板本身已经失效时撤销其检查与期限，之后出现新的有效面板再开始检查。保活、读写和地图握手期限仍独立生效。

客户端在摘要不一致时另启动该通道 10 秒绝对恢复期限；继续发送 KeepAlive，或收到 Delta、更多检查，都不能延长等待。须在期限内接纳有效完整基线，否则断连。WORLD 恢复覆盖已清除的面板；仅恢复 PANEL 时，如果后续合法世界状态已撤销该面板所属单位的资格，结束该面板恢复等待，不复活旧面板。普通缺基线仍按显式重同步规则处理。

每个通道连续最多接纳 3 次恢复，第 4 次恢复关闭连接；隐式摘要恢复和显式 ResyncRequest 都计入，尚未发出的同一恢复可以合并。接纳该通道正确的摘要 ACK 后清零连续恢复计数，仅发送基线或保活不能清零。面板已经失效时撤销该面板的恢复计数。摘要检查用于发现状态重建不一致，不证明恶意客户端诚实，也不授予未披露信息或新的动作权限。

## 11. 本人视角回放容器

本节定义只读状态录制文件，不改变网络帧。文件仅容纳静态地图和本席位世界基线/差量，不容纳 MapOffer、MapReady、MapCached、StateCheck、StateAck、动作面板、控制命令或全世界存档。文件必须自包含，不依赖网络握手或外部地图缓存。

文件头固定 20 字节，均为大端 i32：

| 偏移 | 字段 | 值 |
|---:|---|---|
| 0 | magic | `0x52575856`，字节 RWXV |
| 4 | containerVersion | 2 |
| 8 | wireVersion | 6 |
| 12 | teamId | 0..63 |
| 16 | ticksPerSecond | 1..1000 |

随后是完整网络格式的 MapInit、Baseline、Delta 帧；允许以新 Baseline 代替更大的 Delta。非空录制必须先有一个 MapInit 和首个 Baseline。文件中的静态地图仍只出现一次。空录制可只有文件头与结束标记；只写了地图却没有首个世界状态不是完整录制。

正常结束写入大端 `i32 0`，之后必须立刻 EOF。该 0 是文件结束标记，不是网络消息。缺少结束标记、尾随字节、截断、错误席位、序号或 tick 倒退、终局回退、缺少基线及旧容器/旧协议版本都必须报错；文件不能请求网络重同步。不要把不完整文件标为正常结束。

播放时间使用文件头 ticksPerSecond。重建结果遵循同一世界状态规则，不能从录制启动游戏仿真、恢复隐藏世界或发送操作。相同 sequence 的网络恢复基线不作为新回放帧重复记录。

## 12. 便携参考附件

[参考附件目录](protocol/v6/README.md)提供消息向量与离线 Python 编解码、摘要工具。附件不替代本规范中的连接顺序、授权和状态恢复规则；离线字节互证不等于联网互通或权限验证。
