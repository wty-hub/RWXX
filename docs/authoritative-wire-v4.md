# RWXX 权威联机协议 v4：客户端规范

协议版本：4。文档修订：r2。日期：2026-11-06。

本文定义 TCP 上的字节格式和客户端收发规则。消息不包含游戏引擎对象、存档、TMX 或单位 INI。

v4 是实验协议。本文的服务配置为 Small Island 双人对局，编码允许的字段范围另列。不承诺后续协议版本向后兼容；协议版本独立于应用版本和原版锁步兼容版本 176。连接失败时不切换到旧协议。

本文中“必须”表示格式或互通要求；“本服务配置”表示接入限额和运行条件；“建议”表示可选客户端策略，不增加线上字段。所有字段都必须发送，只有明确的存在标志可以省略后续内容。

配套文件：

- [测试向量](protocol/v4/vectors.json)：完整帧的十六进制、预期字段和逐字段偏移。
- [参考编解码器及向量校验脚本](protocol/v4/wire_v4.py)：仅依赖 Python 3 标准库。
- [地形目录](protocol/v4/terrain-catalog-v1.json)：数字 ID 对应的固定图块裁剪信息。
- [校验记录](protocol/v4/verification.json)：附件摘要和格式检查结果。

## 1. 基本类型

多字节数值一律使用大端序。字段连续排列，没有对齐、填充、变长整数或对象序列化。

| 记号 | 字节数 | 编码 |
|---|---:|---|
| `i32` | 4 | 有符号二进制补码，−2³¹..2³¹−1 |
| `i64` | 8 | 有符号二进制补码，−2⁶³..2⁶³−1 |
| `u8` | 1 | 无符号整数，0..255 |
| `u16` | 2 | 无符号整数，0..65535 |
| `f32` | 4 | IEEE 754 binary32，大端 |
| `f64` | 8 | IEEE 754 binary64，大端 |
| `bool` | 1 | `00` 为 false，`01` 为 true；其他值非法 |
| `text(N)` | 4 + L | `i32 L`，随后 L 个标准 UTF-8 字节；0 ≤ L ≤ N |

本文所有浮点字段必须有限，禁止 NaN 和正负无穷。`+0.0` 和 `−0.0` 均按零处理；仍须满足字段范围，例如 maxHealth>0 不接受任何零。发送端通常使用 `+0.0`。

序号和句柄必须保留 64 位整数精度。JavaScript/TypeScript 使用 `BigInt`，不要经过 `Number`。配套 JSON 用十进制字符串表示 `i64`；这个约定只属于 JSON，线上仍是 8 字节整数。

### 1.1 字符串

`text(N)` 的 L 是 UTF-8 **字节数**。不是字符数、UTF-16 长度，也不是 modified UTF-8。字符串没有末尾零字节。

必须拒绝：

- 非法或截断的 UTF-8、代理项编码、非最短编码。
- 控制字符 U+0000..U+001F、U+007F..U+009F。包括 NUL、制表符、回车和换行。
- L 为负数、超过字段上限或超过当前帧剩余字节数。

标为 `nonBlank` 的字段不能为空或全为空白。忽略已禁止的控制字符后，空白集合为 U+0020、U+00A0、U+1680、U+2000..U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。其余字段允许空字符串，除非后文另有限制。

解析器不得自动 trim、替换非法字符或删除字符串中的 BOM。

## 2. 外层帧

以下偏移从完整帧的第一个字节起算，十进制，零基。

| 偏移 | 字节数 | 字段 | 值 |
|---:|---:|---|---|
| 0 | 4 | `payloadLength: i32` | 9..4,194,304，不包含自身 4 字节 |
| 4 | 4 | `magic: i32` | `0x52575841`，即 `52 57 58 41`（RWXA） |
| 8 | 4 | `wireVersion: i32` | 4，即 `00 00 00 04` |
| 12 | 1 | `messageType: u8` | 见下表 |
| 13 | `payloadLength − 9` | 消息主体 | 按消息类型解析 |

完整帧大小是 `4 + payloadLength`。4 MiB 和 64 KiB 限制都指 `payloadLength`，包含 magic、版本和消息类型，不包含长度前缀。

| 类型值 | 十六进制 | 方向 | 消息 |
|---:|---:|---|---|
| 1 | `01` | 客户端 → 服务端 | Hello |
| 2 | `02` | 客户端 → 服务端 | Intent |
| 3 | `03` | 客户端 → 服务端 | KeepAlive |
| 4 | `04` | 客户端 → 服务端 | ActionPanelRequest |
| 5 | `05` | 客户端 → 服务端 | ActionIntent |
| 101 | `65` | 服务端 → 客户端 | Welcome |
| 102 | `66` | 服务端 → 客户端 | View |
| 103 | `67` | 服务端 → 客户端 | Rejected |
| 104 | `68` | 服务端 → 客户端 | ActionPanel |

其他类型值、方向不符、版本不符或尾随字段均非法。v4 没有可跳过的扩展字段。

特殊长度：

| 消息 | `payloadLength` |
|---|---:|
| KeepAlive | 必须为 9 |
| ActionPanelRequest | 必须为 33 |
| ActionIntent | 必须为 67 |
| ActionPanel | 最大 65,536 |
| 其他消息 | 最大 4,194,304；主体还须满足各自格式 |

### 2.1 TCP 读取

TCP 不保留消息边界。一次 read 可能拿到半帧、多帧或少量字节。先读满 4 字节长度，再读满 9 字节固定头，检查 magic、版本、方向和特殊长度，最后读主体。

主体解析必须限制在当前帧内，且解析后剩余字节数必须为零。应根据长度前缀和累计接收字节数判断是否读满，不能依据 socket 某一时刻已有的字节数判断帧完整性。

不能从错误帧中搜索下一个 magic 继续解析。遇到非法帧或截断，关闭连接。网络帧不接受 length=0；`.rwxview` 文件的零长度结束标记不属于本协议网络流。

## 3. 建立连接

顺序如下：

1. 解析邀请，得到 host、port、证书指纹和席位 token。
2. 建立 TCP，按邀请完成 TLS 和证书检查；邀请指向 loopback 也使用 TLS。
3. 客户端发送一次 Hello。
4. 服务端返回 Welcome 或 Rejected。
5. 收到 Welcome 后进入对局收发状态。首个 View 可能是 tick=0，表示服务器正在等待另一个席位。

服务端不接受重复 Hello。Welcome 也只能出现一次。没有客户端证书、账号登录或协议版本协商消息。

本端口不识别或协商原版锁步协议，也不保证原版客户端显示“不兼容”提示。原版明文输入可能在 TLS 握手阶段失败；在明确的明文本机测试端口上，不符合 RWXA 头的输入直接断开。Rejected 仅能被已经使用合法 v4 外层帧的客户端理解，不能用它向任意旧协议客户端传递原因。

### 3.1 TLS

邀请连接必须使用 TLS，不按 host 是否为 loopback 猜测传输模式。只有明确配置的 loopback 明文测试监听器才省略 TLS，邀请 URI 没有明文模式。不能先发明文再升级 TLS。

指纹计算：`SHA-256(服务器叶证书的完整 DER 字节)`，结果为 32 字节。不是 PEM 文本摘要，也不是公钥/SPKI 摘要。

客户端必须检查叶证书的有效期，并将收到的证书摘要与邀请中的摘要比较。建议使用固定长度、常量时间比较。此认证方式不要求公共 CA 信任链或主机名匹配；凭据发送前必须完成证书 pin 检查。TLS 版本和密码套件由连接双方在 TLS 握手中协商，应用帧内没有相关字段。

### 3.2 邀请 URI

规范形式：

```text
rwx-authority://HOST:PORT/?v=4&fingerprint=HEX64#TOKEN
rwx-authority://[IPv6]:PORT/?v=4&fingerprint=HEX64#TOKEN
```

| 部分 | 规则 |
|---|---|
| scheme | 必须为小写 `rwx-authority` |
| host | DNS 名、IPv4 或在 URI 中用方括号包围的 IPv6；解析得到的 host 值不含方括号 |
| port | 显式指定，1..65535；建议端口为 5124 |
| path | 必须为 `/` |
| query | 必须按此顺序为 `v=4&fingerprint=HEX64`，不接受其他参数 |
| HEX64 | 64 个小写十六进制字符，无冒号 |
| TOKEN | fragment 原文，32..128 个 ASCII 字母、数字、`-` 或 `_`；不是需要解码的 Base64 数据 |

不接受 userinfo、百分号编码的凭据、通配监听地址 `0.0.0.0` 或 `::` 作为可连接地址。邀请输入上限为 1024 个 UTF-16 代码单元，先检查长度，再去除整体首尾空白并解析；主机名长度为 1..253，不能含空白或 `/ \\ ? # % @`。

邀请中的 token 标识一个固定席位，不是公开房间号。日志应省略整个 token 和邀请 fragment。Hello 的 token 字段上限比邀请格式宽，见第 4 节。

## 4. 客户端消息

本节及后续主体表中的偏移 `b` 均从消息主体起算：`b=0` 对应整帧偏移 13。

### 4.1 Hello，类型 1

令 `P` 为 playerName 的 UTF-8 字节数，`T` 为 joinToken 的 UTF-8 字节数。

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | `protocolVersion: i32` | 客户端发送 4 |
| 4 | 4 | playerName 字节数 | 1..128，且 nonBlank |
| 8 | P | playerName UTF-8 | 第 1.1 节字符串规则 |
| 8+P | 4 | joinToken 字节数 | 0..512 |
| 12+P | T | joinToken UTF-8 | 使用邀请 fragment 原文 |

主体大小 `12 + P + T`；payloadLength 为 `21 + P + T`。服务端配置的有效 token 至少 16 个 UTF-8 字节；长度不足或内容错误属于认证失败。昵称不选择队伍，也不保证唯一。

外层 wireVersion 不为 4：直接断连。外层为 4、Hello.protocolVersion 不为 4：服务端发送 Rejected，再关闭连接。

### 4.2 Intent，类型 2

用于群体移动、攻击移动、停止和攻击单位。基础建造/生产类型仍保留；完整标准动作使用 ActionIntent。

令 `N=unitHandles.size`，`T=unitTypeName` 的 UTF-8 字节数。

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `sequence: i64` | ≥0；首条命令建议用 1 |
| 8 | 8 | `viewSequence: i64` | ≥0，引用收到的 View.sequence |
| 16 | 1 | `kind: u8` | 见下表 |
| 17 | 4 | `unitCount: i32` | 1..256 |
| 21 | 8×N | `unitHandles: i64[N]` | 每个 >0，不重复 |
| 21+8N | 4 | `x: f32` | 有限 |
| 25+8N | 4 | `y: f32` | 有限 |
| 29+8N | 8 | `targetHandle: i64` | 见下表 |
| 37+8N | 4 | unitTypeName 字节数 | 0..256 |
| 41+8N | T | unitTypeName UTF-8 | 见下表 |

主体大小 `41 + 8N + T`。

| kind | 名称 | x/y | targetHandle | unitTypeName / 选择 |
|---:|---|---|---|---|
| 1 | MOVE | 世界坐标点 | 必须为 0 | 必须为空 |
| 2 | ATTACK_MOVE | 世界坐标点 | 必须为 0 | 必须为空 |
| 3 | STOP | 必须为零 | 必须为 0 | 必须为空 |
| 4 | ATTACK_UNIT | 必须为零 | 必须 >0 | 必须为空；当前可见目标 |
| 5 | BUILD | 建造坐标点 | 必须为 0 | nonBlank，且 N=1 |
| 6 | PRODUCE | 必须为零 | 必须为 0 | nonBlank，且 N=1 |

本服务配置的基础 BUILD 仅接受 `landFactory`、`turret`、`extractor`，基础 PRODUCE 仅接受 `tank`、`builder`。其他建筑、升级和特殊动作不要往此字段塞类型字符串，使用服务端面板中的动作句柄。

实体目标必须同时存在于引用的 View 和服务器当前授权视图。点命令可以指向迷雾；坐标必须在 `[0,mapWidth×tileWidth)`、`[0,mapHeight×tileHeight)` 内。

### 4.3 KeepAlive，类型 3

主体为空。完整帧固定为：

```text
00 00 00 09  52 57 58 41  00 00 00 04  03
```

没有服务端 Pong。建议在发送队列空闲约 2 秒后发送此帧；2 秒是推荐间隔，不是帧字段或固定发送周期。

### 4.4 ActionPanelRequest，类型 4

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `querySequence: i64` | ≥0，本连接严格递增 |
| 8 | 8 | `viewSequence: i64` | ≥0，引用有效 View |
| 16 | 8 | `unitHandle: i64` | ≥0；0 表示清空选择 |

主体固定 24 字节；payloadLength=33，整帧 37 字节。只查询一个己方单位，不是多单位汇总面板。

查询越权、过期、不可控单位或清空选择时，响应形式统一为 `unitHandle=0`、`actions=[]`。不能根据拒绝原因得到隐藏单位信息。

### 4.5 ActionIntent，类型 5

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `sequence: i64` | ≥0；与 Intent 共用一个递增计数器 |
| 8 | 8 | `viewSequence: i64` | ≥0，引用当前有效 View |
| 16 | 8 | `panelSequence: i64` | >0，引用有效 ActionPanel |
| 24 | 8 | `unitHandle: i64` | >0，必须是该面板的 owner |
| 32 | 8 | `actionHandle: i64` | >0，来自该面板 |
| 40 | 4 | `x: f32` | 有限 |
| 44 | 4 | `y: f32` | 有限 |
| 48 | 8 | `targetHandle: i64` | ≥0 |
| 56 | 1 | `queued: bool` | 是否追加到命令队列 |
| 57 | 1 | `cancel: bool` | 是否取消该动作的一项真实排队任务 |

主体固定 58 字节；payloadLength=67，整帧 71 字节。

按面板 `targetMode` 填写：

| 模式 | x/y | targetHandle |
|---|---|---|
| NONE | 必须为零 | 必须为 0 |
| POINT | 世界坐标点 | 必须为 0 |
| VISIBLE_UNIT | 必须为零 | 必须 >0，且当前可见 |

若 `targetHandle>0`，格式要求 x/y 为零。取消时必须 `x=0,y=0,targetHandle=0,queued=false`，不再填写该动作原本的目标。只对 `canCancel=true` 的真实队列项提供取消操作。 若面板本身提供 `cancelConstruction` 等取消施工动作，激活该动作仍是普通 ActionIntent，使用 `cancel=false`；不能仅凭显示名称设置队列取消标志。

`cancelConstruction` 等显式目录动作仍按普通按钮执行，发送 `cancel=false`。不要根据显示 key 中的 cancel 字样设置取消标志。退款额度以服务端后续资源变化为准。

`queued=true` 仅用于 BUILD、REPAIR、RECLAIM、LOAD_INTO、LOAD_UP、GUARD、PATROL。其他动作发送 false。生产任务自身的工厂排队不依赖 queued=true。

## 5. 服务端消息

### 5.1 Welcome，类型 101

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | `protocolVersion: i32` | 必须为 4 |
| 4 | 4 | `teamId: i32` | 0..63；本服务配置使用 0 和 1 |

主体 8 字节；payloadLength=17。席位由 token 决定，客户端不能要求另一个 teamId。

### 5.2 Rejected，类型 103

主体为 `reason: text(1024)`，必须 nonBlank。没有数值错误码。认证阶段的两种提示为：

| reason | 含义 |
|---|---|
| `Unsupported view protocol version` | Hello.protocolVersion 不为 4 |
| `Unable to join this room` | token 缺失、错误或席位已占用；三者不区分 |

展示 reason 后关闭连接。不要靠匹配这两段英文来实现安全逻辑；其他协议/网络错误可能直接断连，没有 Rejected。

### 5.3 View，类型 102

View 是此玩家当前完整视图，**不是差量包**。每次替换 fog、terrain、units、matchStatus、mapVisual。缺少某单位表示它不再属于当前视图，应撤销其当前实体状态和操作目标。

令：

- `C = mapWidth × mapHeight`。
- `U = Σ(46 + 每个单位 typeName 的 UTF-8 字节数)`。
- `A = 52 + 2C + U`，即 matchPhase 的主体偏移。
- `W=winnerCount`，`D=defeatedCount`。

| b | 字节数 | 字段 |
|---:|---:|---|
| 0 | 8 | `sequence: i64` |
| 8 | 4 | `tick: i32` |
| 12 | 4 | `teamId: i32` |
| 16 | 8 | `credits: f64` |
| 24 | 4 | `mapWidth: i32` |
| 28 | 4 | `mapHeight: i32` |
| 32 | 4 | `tileWidth: i32` |
| 36 | 4 | `tileHeight: i32` |
| 40 | 4 | `fogCount: i32`，必须等于 C |
| 44 | C | `fog: u8[C]` |
| 44+C | 4 | `terrainCount: i32`，必须等于 C |
| 48+C | C | `terrain: u8[C]` |
| 48+2C | 4 | `unitCount: i32` |
| 52+2C | U | units，逐项按第 5.4 节读取 |
| A | 1 | `matchPhase: u8`，0=RUNNING，1=FINISHED |
| A+1 | 4 | `winnerCount: i32` |
| A+5 | 4W | `winnerTeamIds: i32[W]` |
| A+5+4W | 4 | `defeatedCount: i32` |
| A+9+4W | 4D | `defeatedTeamIds: i32[D]` |
| A+9+4W+4D | 1 | `hasMapVisual: bool` |
| A+10+4W+4D | 0 或 12+4C | mapVisual，见第 5.5 节 |

注意：线上 **terrain 在 units 前面**。

校验规则：

| 字段 | 合法值 |
|---|---|
| sequence | 0..2⁶³−1 |
| tick | 0..2³¹−1，仿真 tick，不是时间戳 |
| teamId | 0..63，且必须等于 Welcome.teamId |
| credits | 有限 f64，允许负数 |
| mapWidth/mapHeight/tileWidth/tileHeight | 各 1..4096 |
| C | ≤1,048,576，先用宽整数计算再分配 |
| fogCount/terrainCount | 均严格等于 C |
| fog 元素 | 0..10 |
| terrain 元素 | 0..15 |
| unitCount | 0..65,536，还必须装入当前帧剩余字节 |
| W/D | 各 0..64；各 ID 0..63；列表内无重复；两个列表无交集 |
| RUNNING | W=0 且 D=0 |
| FINISHED | W>0 或 D>0，且 unitCount=0；W 可以为 0 |

hasMapVisual=false 的主体大小为 `62 + 2C + U + 4(W+D)`；true 时再加 `12 + 4C`。这些上限相互独立，不能据此认为任意最大值组合都能放进 4 MiB。

### 5.4 UnitRecord

令 T 为 typeName 的 UTF-8 字节数；每项大小为 `46 + T`，最少 47 字节。

| 项内偏移 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `handle: i64` | >0，同一 View 内唯一 |
| 8 | 4 | typeName 字节数 | 1..256，nonBlank |
| 12 | T | typeName UTF-8 | 不透明的公开类型 key |
| 12+T | 4 | `teamId: i32` | −2..63 |
| 16+T | 4 | `x: f32` | 有限，0 ≤ x < mapWidth×tileWidth |
| 20+T | 4 | `y: f32` | 有限，0 ≤ y < mapHeight×tileHeight |
| 24+T | 4 | `height: f32` | 有限 |
| 28+T | 4 | `heading: f32` | 有限，单位为度 |
| 32+T | 4 | `health: f32` | 有限且 ≥0 |
| 36+T | 4 | `maxHealth: f32` | 有限且 >0 |
| 40+T | 4 | `buildProgress: f32` | 0..1 |
| 44+T | 1 | `controllable: bool` | 当前是否可控制 |
| 45+T | 1 | `upgradeLevel: u8` | 1..3；这是 v4 字段，不可省略 |

格式不要求 health≤maxHealth，也不把 heading 限制在 0..360。heading、height 不得按整数读取。没有独立炮塔角度、动画帧、路径、订单、攻击目标、载员列表或敌方资源字段。

世界坐标原点在地图左上，x 向右，y 向下。heading 表示地图平面角度，height 表示离地高度。显示时可按本地素材的默认朝向修正旋转，并将高度投影为 `(x,y−height)`；这是呈现方式，不改变命令使用的地图平面 x/y。

只对当前本队、controllable=true 的单位提供操作。`controllable` 本身是显示字段；服务端会重新检查归属、控制权和建造完成状态。

### 5.5 MapVisual

只有 hasMapVisual=true 时出现。此时 `C≤16,384`，且 tileWidth=tileHeight=20。

| 项内偏移 | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | `catalogVersion: i32` | 必须为 1 |
| 4 | 4 | `groundCount: i32` | 必须等于 C |
| 8 | 2C | `groundTileIds: u16[C]` | 每个 0..234 |
| 8+2C | 4 | `decorationCount: i32` | 必须等于 C |
| 12+2C | 2C | `decorationTileIds: u16[C]` | 每个 0..234 |

两个图层均按行排列。0 表示空图块。先画 ground，再画 decoration，再画单位和迷雾。hasMapVisual=false 表示本帧没有这份原画数据，不能解释为“沿用上次原画”的差量标记。

## 6. ActionPanel，类型 104

| b | 字节数 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | `querySequence: i64` | ≥0，回显请求序号 |
| 8 | 8 | `panelSequence: i64` | >0，本连接严格递增 |
| 16 | 8 | `viewSequence: i64` | ≥0，回显查询引用的 View |
| 24 | 4 | `teamId: i32` | 0..63，必须等于 Welcome.teamId |
| 28 | 8 | `unitHandle: i64` | ≥0，选中的 owner；0 表示清空/不可用 |
| 36 | 4 | `actionCount: i32` | 0..128 |
| 40 | 可变 | actions | 逐项按第 6.1 节读取 |

unitHandle=0 时 actionCount 必须为 0。unitHandle>0 时 actionCount 可以为 0。面板是完整替换，不是增量。每个 action.handle 必须 >0，同一面板内唯一。

面板包括固定显示信息和当前状态，不另有静态 descriptor/catalog 分块消息。payloadLength 最大为 65,536；空面板主体 40 字节，完整帧 53 字节。

### 6.1 ActionRecord

令 K 为 presentationKey 的 UTF-8 字节数，P 为 producedTypeKey 的 UTF-8 字节数；F、S 分别表示 upfrontCost、streamingCost 是否存在，存在为 1，否则为 0。

| 项内偏移 | 字节数 | 字段 |
|---:|---:|---|
| 0 | 8 | `handle: i64` |
| 8 | 1 | `kind: u8` |
| 9 | 1 | `targetMode: u8` |
| 10 | 4 | presentationKey 字节数 |
| 14 | K | presentationKey UTF-8 |
| 14+K | 4 | producedTypeKey 字节数 |
| 18+K | P | producedTypeKey UTF-8 |
| 18+K+P | 1 | `hasUpfrontCost: bool` |
| 19+K+P | 20F | upfrontCost |
| 19+K+P+20F | 1 | `hasStreamingCost: bool` |
| 20+K+P+20F | 20S | streamingCost |
| 20+K+P+20(F+S) | 1 | `readiness: u8` |
| 21+K+P+20(F+S) | 4 | `queuedCount: i32` |
| 25+K+P+20(F+S) | 4 | `stockpileCount: i32` |
| 29+K+P+20(F+S) | 4 | `progress: f32` |
| 33+K+P+20(F+S) | 1 | `canCancel: bool` |

每项大小 `34 + K + P + 20F + 20S`，最少 35 字节。

| 字段 | 约束 / 含义 |
|---|---|
| presentationKey | 1..128 字节，只能含 ASCII `A-Z a-z 0-9 _ . -` |
| producedTypeKey | 0..128 字节，同一字符集；空字符串表示没有该显示 key |
| queuedCount | −1..65,536；−1 表示未知，不是队列长度为零 |
| stockpileCount | −1..65,536；独立库存/弹药量，不是排队任务数 |
| progress | 有限，且恰为 −1 或位于 0..1；−1 表示未知；(−1,0) 非法 |
| canCancel | 是否提供取消排队操作，不表示整台单位可以撤销 |

key 只用于本地显示、图标和分类。不得将其当作可执行 ID、资源路径或本地代码入口。即使两个动作的 key 一样，也必须使用各自的 action.handle。

### 6.2 CostRecord

每个存在标志必须发送。false 只占 1 字节，后面不跟费用字段；true 后面紧接 20 字节：

| 项内偏移 | 字节数 | 字段 |
|---:|---:|---|
| 0 | 4 | `credits: i32` |
| 4 | 4 | `energy: f32` |
| 8 | 4 | `hp: f32` |
| 12 | 4 | `shield: f32` |
| 16 | 4 | `ammo: i32` |

三个浮点字段必须有限。整数和浮点费用都允许负数，不能统一当作无符号数。空费用不等于“五项为零”的费用。

### 6.3 枚举

Action kind：

| 值 | 名称 | 含义 |
|---:|---|---|
| 1 | BUILD | 建造 |
| 2 | PRODUCE | 生产 |
| 3 | UPGRADE | 升级/转换 |
| 4 | RALLY | 集结点 |
| 5 | REPAIR | 维修 |
| 6 | RECLAIM | 回收 |
| 7 | LOAD_INTO | 此单位进入目标运输单位 |
| 8 | LOAD_UP | 此运输单位装载目标单位 |
| 9 | UNLOAD | 卸载 |
| 10 | STOP_UNLOAD | 停止卸载 |
| 11 | TARGET_GROUND | 地面目标动作，例如核弹发射 |
| 12 | TOGGLE | 开关/切换/模块选择 |
| 13 | GUARD | 护卫 |
| 14 | PATROL | 巡逻 |
| 15 | ATTACK_MODE | 攻击模式切换 |

| 枚举 | 值 |
|---|---|
| targetMode | 0=NONE，1=POINT，2=VISIBLE_UNIT |
| readiness | 0=READY，1=BLOCKED，2=UNKNOWN |

其他枚举值非法。执行目标以 targetMode 为准，不按 kind 猜目标格式。

UNKNOWN 表示服务端没有公开求值结果，不等于禁用。客户端可允许尝试，由服务端执行时复核。READY 也不是执行承诺；资源、位置或可见性可能已经变化。取消操作单独看 canCancel，不应因资金不足或 readiness=BLOCKED 而一律禁止取消。

## 7. 序号、授权和客户端状态

### 7.1 序号

| 计数器 | 产生方 | 使用范围 |
|---|---|---|
| command sequence | 客户端 | Intent 和 ActionIntent 共用，严格递增；首条建议用 1 |
| querySequence | 客户端 | 仅 ActionPanelRequest，独立严格递增，建议从 1 开始 |
| View.sequence | 服务端 | 本连接世界视图序号，收到的值必须严格递增 |
| ActionPanel.panelSequence | 服务端 | 本连接面板序号，收到的值必须严格递增，首个 >0 |

两种服务端序号之间不能比较大小。

允许跳号，不要求加一。服务端合并未发送的旧 View 和旧 ActionPanel，两个通道互不覆盖。不是每次查询都能收到一一对应的响应。

帧格式允许 sequence=0；本服务配置不会执行序号 0 的命令，因此首条命令建议用 1。首条也可用更大的正整数。命令被语义拒绝也会消耗序号；重试必须换新序号。不得回绕为负数。

### 7.2 视图和面板引用

本服务配置保留最近 32 个世界视图 receipt 和最近 16 个有效面板 receipt。两者都要求服务器 tick 年龄在 0..180 内。

这里的 receipt 表示服务端认可的视图或面板引用，不是额外的网络回执。客户端只回传 viewSequence/panelSequence，不构造或发送 receipt 对象。

ActionIntent 的 viewSequence 和 panelSequence 分别授权“当前实体/目标”和“该 owner 的动作能力”。二者必须各自有效，但不要求 viewSequence 等于面板中的 viewSequence。客户端可以用较新的已收到 View 配合仍有效的旧面板。

不要把 180 tick 固定换算成网络超时；若仿真按 60 tick/s 推进，约为 3 秒，但 tick 年龄不等于墙钟时间。收到新 View 或新面板后应及时更新输入依据。

### 7.3 句柄

unit.handle 和 action.handle 都是本连接私有的不透明正整数，不是全局实体 ID。句柄可能连续，不保证不可猜测；授权必须依赖有效引用、所有权和当前状态。

- 单位离开视图后，撤销当前实体与目标句柄；重新出现可能得到新句柄。
- 换选择、死亡、失控、装载、升级/转换、动作撤销、终局或重连会使相关动作能力失效。
- 同一 action.handle 只能用于它所属的 unitHandle 和有效 panelSequence。
- 模块子单位的动作可由父单位面板代理，不能据此构造子单位 world handle。

客户端应将按钮绑定到实际显示的 owner、panelSequence、action.handle。切换选择后清掉旧按钮和待选目标，不能按新面板中的相同下标重解释旧点击。

### 7.4 没有命令 ACK

v4 没有成功/失败应答、命令回执 ID 或队列完成事件。合法格式但无权、过期、目标失去视野、资源不足、地形不允许等输入可被静默忽略。

执行结果通过后续 View 和重新查询的 ActionPanel 观察。移动看坐标，生产看真实 queuedCount/资源变化，队列取消看队列及资源变化；退款额度不由成功写入命令保证。不要把“socket write 成功”当成“服务器已执行”。

### 7.5 重连和终局

断线后清空全部本地句柄、选择、面板和待发送命令。用同一席位 token 重新握手；没有 resume 消息或自动重放。旧连接未退出时，同 token 可能因席位占用被统一拒绝。

本服务配置首次等待全部席位接入再推进仿真；已经开始的对局会跨断线继续运行。新连接重建序号与句柄空间。

收到 FINISHED 后停用游戏命令，清空单位和动作。终局 tick 和已经获准的本人状态被冻结；服务端不再周期推送 View，但保留监听器供重连取得结果。终局重连得到空单位、全未探索 fog，不会成为全图观战。

因此，已握手客户端不能仅因一段时间没收到 View 就认定断线；若保留终局连接，仍须发送 KeepAlive，满足服务端 10 秒完整输入帧期限。

## 8. 接入限额与推荐策略

| 项目 | 本服务配置的接入限额 |
|---|---:|
| 完成 TLS/Hello 的总握手期限 | 约 10 秒 |
| 后续客户端完整输入帧间隔/阻塞读取 | 10 秒；缓慢分片不能无限延长连接 |
| 单次阻塞写入 | 约 10 秒 |
| 全部输入帧，包括 KeepAlive | 每连接每计数窗最多 120 |
| Intent + ActionIntent | 共用每计数窗最多 60 |
| ActionPanelRequest | 任意滑动 1 秒内最多 8 |
| 待处理请求（Intent、ActionIntent、查询；不含 KeepAlive） | 每连接最多 64，全局最多 1024 |
| 席位 | 编码范围最多 64；本服务配置仅两个 |

120/60 的计数窗在 Welcome 后初始化。后续完整帧到达时，若距窗口起点已满一秒，先将起点设为当前时刻、计数清零，再计入此帧；不是后台定时器每秒清零。查询使用滑动一秒队列。达到限额的额外输入、非递增序号或队列溢出会断连。

建议客户端空闲约 2 秒发送 KeepAlive，面板查询不高于约 5 Hz，给查询限额留出余量。这些是推荐策略。服务端发布频率不属于帧格式；客户端不能假设每 100 ms 必到一帧，或由接收间隔推断仿真 tick。

对第三方客户端，持续读网络、串行写完整帧、限制本地发送队列。不要让渲染线程或慢 UI 阻塞网络读取。

## 9. 地图和显示

### 9.1 数组和迷雾

所有地图数组按行排列：`index = y * mapWidth + x`。不是按列排列。

| fog | 显示含义 |
|---:|---|
| 0..4 | 当前可见 |
| 5..9 | 已探索或视野边缘衰减，当前不可见 |
| 10 | 未探索 |

实体披露取决于本人视野及其他可见性条件；fog<5 也不表示该格所有敌军都必然下发。迷雾蒙版不承担客户端保密职责。客户端可能保留历史标记，但不能把离开视图的句柄当作当前目标。

terrain 是公开静态位掩码，可以按位组合：

| 位值 | 含义 |
|---:|---|
| 1 | 水面 |
| 2 | 悬崖 |
| 4 | 资源池 |
| 8 | 阻止建筑放置 |

这些位不包含单位占位、完整寻路规则或全部建造条件。建造是否合法仍由服务端判断。

### 9.2 地形目录版本 1

图块固定为 20×20 像素。路径是本地约定，不在线上传输。ID 0 为空，其余如下：

| ID 范围 | 本地图像（`tilesets/bitmaps/` 下） | 图像宽×高 | 格数 |
|---|---|---:|---:|
| 1..80 | snow.png | 160×200 | 80 |
| 81..90 | ice.png | 100×40 | 10 |
| 91..96 | shallowwater.png | 60×40 | 6 |
| 97..99 | water.png | 60×20 | 3 |
| 100..120 | snow2ice_ridge.png | 60×140 | 21 |
| 121..141 | ice2shallowwater_ridge.png | 60×140 | 21 |
| 142..162 | shallowwater2water_flat.png | 60×140 | 21 |
| 163..183 | ice2ice_flat.png | 60×140 | 21 |
| 184..204 | ice2shallowwater_broken.png | 60×140 | 21 |
| 205..225 | stone2snow.png | 60×140 | 21 |
| 226..234 | misc.png | 79×160 | 仅前 9 格 |

对该行起始 ID 为 B 的图像，`localIndex=id−B`，`columns=floor(imageWidth/20)`，裁剪左上角为 `((localIndex % columns)*20, floor(localIndex/columns)*20)`，宽高均为 20。

目录 JSON 已逐项列出裁剪矩形，不需要读取 TMX/TSX。配套包不包含图像字节；没有对应素材时可以先画颜色地形。没有识别的单位 typeName 或显示 key 可以用本地占位图形，不能按网络字符串读任意文件。

## 10. 最小客户端流程

```text
TLS 和证书 pin 成功
发送 Hello(4, playerName, token)
读取 Welcome，保存 teamId
commandSequence = 1
querySequence = 1

持续读取：
  View：检查 teamId、严格递增 sequence，完整替换世界视图
  ActionPanel：检查 teamId、严格递增 panelSequence；关联查询记录，
               仅在回显 query/view 与仍有效的当前选择一致时应用面板
  Rejected / 非法帧 / EOF：关闭连接，清空句柄和待发送输入

移动：
  从最新 View 选择本人可控单位
  发送 Intent(commandSequence++, view.sequence, MOVE, handles, x, y, 0, "")

选择单位：
  记录 querySequence → (所选 owner、请求引用的 View、选择代次)
  发送 ActionPanelRequest(querySequence++, view.sequence, unit.handle)

点击无目标动作：
  发送 ActionIntent(commandSequence++, latestView.sequence,
                   panel.panelSequence, panel.unitHandle, action.handle,
                   0, 0, 0, false, false)

取消该动作的一项任务：
  同上，但 cancel=true；仍使用原动作句柄，不另造“取消动作 ID”

空闲（推荐策略）：
  约每 2 秒发送 KeepAlive
```

输入线程、读取线程、渲染线程之间传递完整快照或不可变副本。不要在解析到一半时更新屏幕状态。

快速从 A 选到 B 时，迟到的 A 面板不能覆盖 B 的 UI；空面板也要先关联查询，不能无条件清空新的选择。响应的 querySequence/viewSequence 对应请求，非空 unitHandle 应等于请求 owner；空响应 unitHandle 为 0。切换选择时立即作废旧选择代次。

查询记录应有数量或时间上限。服务端可能合并响应，不能等每个查询都有回包后才释放本地记录。

## 11. 十六进制示例

### 11.1 Welcome，teamId=0

```text
00 00 00 11   # payloadLength = 17
52 57 58 41   # magic
00 00 00 04   # wireVersion
65            # Welcome
00 00 00 04   # protocolVersion
00 00 00 00   # teamId
```

### 11.2 Hello，playerName="A"，token="0123456789abcdef"

```text
00 00 00 26   # payloadLength = 38
52 57 58 41
00 00 00 04
01
00 00 00 04   # protocolVersion
00 00 00 01   # name: 1 byte
41
00 00 00 10   # token: 16 bytes
30 31 32 33 34 35 36 37 38 39 61 62 63 64 65 66
```

这只说明字节格式。该 token 不是可用的公开凭据。

### 11.3 校验编解码结果

配套 `vectors.json` 覆盖九种消息、六种 Intent、全部十五种 action kind、费用存在/缺席、终局、无 MapVisual、UTF-8、负零及超过 2⁵³ 的句柄。每个字段注释都给出从长度前缀起算的绝对偏移和实际字节。

JSON 的 `accepted=true` 只表示编解码格式合法，不表示 token 可认证、句柄已经披露或命令有执行权限。特别是较短 token、任意大句柄和测试地图，都不能直接拿去对正式房间发命令。

NUL 负例必须被读写两端拒绝。验证时依次检查：

1. 从 hex 解码的字节数等于 frameBytes，长度前缀等于 payloadBytes。
2. 按 direction 和消息格式解码，与 expected 比较，i64 保留精度。
3. 将 expected 重新编码，得到完全相同的字节，包括负零。
4. 一帧解析完毕必须没有剩余字节。

在本文件旁的配套目录执行：

```sh
python3 protocol/v4/wire_v4.py protocol/v4/vectors.json
```

参考校验脚本检查帧解码、预期字段、逐字节重编码和注释偏移。它只处理帧和字段，不处理 TLS、席位认证、服务端语义授权或渲染，也不是完整客户端。

## 12. 不包含的消息

v4 没有聊天、Pong、命令 ACK、房间成员列表、换队、观战、共享控制、房主迁移、地图上传、存档同步或资源下载消息。不要向现有消息尾部附加这些字段。

原版锁步、原版回放和 `.rwxview` 录制容器是其他格式，不能直接发送给此 TCP 端口。

## 13. 版本变更规则

更改消息字段顺序、字段宽度、枚举编码或既有线上语义时，必须发布新的协议版本和测试向量；不能在版本 4 内静默修改。

文档修订号只标记说明文字或附件元数据的修订，不改变线上 wireVersion。
