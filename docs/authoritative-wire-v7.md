# 权威时间线协议 v7：客户端规范

协议版本：7。文档修订：r1。日期：2026-11-07。状态：设计候选。

服务器执行游戏仿真、审核命令并生成各席位获准接收的时间线。客户端保存对象、属性曲线和事件，按观看时间采样；实时显示、暂停、回看、倒放及多窗格使用同一种数据。客户端不通过重放玩家指令重新执行战斗。

本文定义自有线格式，不声明与其他产品的私有协议兼容。版本 7 的连接只接受本规范；原版 176 属于独立兼容模式。下文“必须”为互通要求，“建议”为客户端策略。

## 1. 数值、时间与编码

固定宽数值均为大端，无填充。`u8/u16/u32/u64` 分别占 1/2/4/8 字节；`i64` 为 8 字节补码。所有 u64 字段上限为 2⁶³−1。字符串使用标准 UTF-8，禁止非法、截断、非最短编码、代理项及 U+0000..001F、U+007F..009F。长度按字节计算，不带 NUL。布尔只接受 00、01。

没有浮点线类型。几何、角度和进度使用第 7 节规定的整数刻度。不得把 64 位整数先转换成 binary64；JavaScript 客户端须用 BigInt 处理时间、序号及句柄。

### 1.1 紧凑整数

`vuint` 为最短形式的 unsigned LEB128，范围 0..2⁶³−1：每字节低 7 位为数据，最高位表示有后续字节，低位组在前。最多 9 字节；禁止冗余最高零组、截断和溢出。例如 0=00、127=7F、128=80 01。

`vsint` 先对 −2⁶²..2⁶²−1 做 ZigZag，再写 vuint：非负 n 编成 2n，负 n 编成 −2n−1。0=00、−1=01、1=02、−2=03。它不是 signed LEB128。

`vbytes` 为 vuint 字节数加原始字节。没有“读到流结束”的字段。所有长度、计数、偏移和乘积必须在分配前检查，同时检查当前容器剩余字节。

### 1.2 时间域

`time` 的线类型为 u64 或明确标注的 vuint，单位是一个仿真 tick 的 1/1024。欢迎消息给出 ticksPerSecond；一秒为 `1024*ticksPerSecond` 个时间单位，不能把网络包频率当作 tick 频率。时间 0 合法。

`FOREVER=2⁶³−1` 仅可作为区间右端点或恒值段的终止锚点，不能作为当前时间、区间起点或事件发生时间。普通时间上限为 FOREVER−1。所有区间采用 `[lo,hi)`；右端点不属于区间。

仿真时间与事务修订号是两个维度。修订号只增不减；新事务可以修正尚未封口的过去，或撤回已发送的预测。观看游标属于客户端，不推进服务器时间、不改变复制状态，也不延长命令授权。

## 2. 帧与可扩展记录

TCP 字节流可拆分、合并任意帧。必须读满声明长度，不能依据一次 socket 读取推断消息边界。

| 整帧偏移 | 字节数 | 字段 |
|---:|---:|---|
| 0 | 4 | payloadLength:u32，8..1,048,576，不含本字段 |
| 4 | 4 | magic:u32=0x52575854，ASCII RWXT |
| 8 | 2 | wireVersion:u16=7 |
| 10 | 2 | messageType:u16，见第 3 节 |
| 12 | payloadLength−8 | TLV 主体 |

非法头、未知消息类型、方向错误、非法字段或尾随数据均须断连。禁止扫描后续 magic 试图恢复。网络没有长度 0 的帧。

### 2.1 TLV

记录是连续的字段，不另带字段计数。记录末尾由外层长度确定。每个字段：

| 字段内偏移 | 字节数 | 内容 |
|---:|---:|---|
| 0 | 2 | fieldId:u16，1..32767 |
| 2 | 1 | flags:u8，bit0=critical，其余必须为零 |
| 3 | 1 | kind:u8 |
| 4 | 4 | length:u32，仅 value 字节数 |
| 8 | length | value |

字段按 fieldId 严格升序，同一 ID 不得重复。一个记录最多 256 个字段。根记录深度为 1，嵌套最多 8 层。

| kind | 名称 | value 格式 |
|---:|---|---|
| 1 | BOOL | 1 字节，只接受 00/01 |
| 2 | U32 | 4 字节 u32 |
| 3 | U64 | 8 字节 u64，≤2⁶³−1 |
| 4 | I64 | 8 字节 i64 |
| 5 | TEXT | 原始 UTF-8，无内层长度 |
| 6 | BYTES | 原始字节，无内层长度 |
| 7 | RECORD | 一个 TLV 记录 |
| 8 | RECORDS | count:u32，随后 count 组 length:u32 + TLV 记录；count≤65,536 |

未知 critical 字段必须拒绝。未知 optional 字段可以不参与呈现，但持久记录中必须保留它的完整规范字节，并参与完整摘要。未知 kind 仅在未知 optional 字段中允许作为不透明字节保留；已识别字段的 kind 必须匹配表格。不能把未知的状态操作当作可忽略消息。

持久 TLV 仅指 canonicalMapRecord、对象 descriptor（包含内嵌 ability）和曲线 metadata；它们的未知 optional 字段全部保留。网络握手、事务信封、状态答复及诊断中的未知 optional 字段不构成持久状态，可以丢弃，不得影响授权或重建。已知 kind 的未知 optional 字段仍须递归验证格式、UTF-8 和限额；只有未知 kind 的 optional 值按不透明字节保留。

下文消息表中的字段均为 critical=1、必须出现，除非标注“可选”。可选的协议字段仍为 critical=1；“可选显示扩展”才使用 critical=0。语义变化或新的必需能力不能仅依靠 optional 字段实现。

### 2.2 通用限额

| 项目 | 上限 |
|---|---:|
| 单帧 payload | 1 MiB |
| 单个地图或事务逻辑字节串 | 64 MiB |
| 单个传输块 data | 65,536 字节 |
| 单连接时间线流 | 4，至多 1 个 LIVE |
| 每流同时有效单位观察 | 65,536 |
| 每流保留对象 | 262,144，含结束的观察及动作对象 |
| 每流全部曲线段及脉冲合计 | 2,000,000 |
| 每流规范持久状态 | 64 MiB，不含单独持有的静态地图 |
| 每事务操作 | 2,097,152 |
| 单操作 bodyBytes | 1 MiB，不含 opcode 和长度前缀 |
| 单个 splice 段或事件 | 4096 |
| 普通对象属性 | 128；ROOT 允许 8192 |
| 单次命令选择 | 256 个单位 |
| 同时展示的动作能力 | 128 |

数量、字节和时间窗口限制同时生效，不能仅检查其中一项。超限事务不得部分应用。完整基线同样分块传输，不要求装进一帧。

## 3. 消息表与接入

| 类型 | 方向 | 名称 |
|---:|---|---|
| 1 | C→S | ClientHello |
| 2 | C→S | MapHave |
| 3 | C→S | OpenTimeline |
| 4 | C→S | CloseTimeline |
| 5 | C→S | Command |
| 6 | C→S | Select |
| 7 | C→S | Ping |
| 8 | C→S | CheckAck |
| 9 | C→S | Restore |
| 10 | C→S | SetBudget |
| 101 | S→C | ServerHello |
| 102 | S→C | Reject |
| 103 | S→C | MapOffer |
| 104 | S→C | MapChunk |
| 105 | S→C | MapComplete |
| 106 | S→C | TimelineBegin |
| 107 | S→C | TimelineChunk |
| 108 | S→C | TimelineCommit |
| 109 | S→C | StateCheck |
| 110 | S→C | TimelineStatus |
| 111 | S→C | Pong |
| 112 | S→C | StreamEnd |

跨机器连接必须使用 TLS，在发送席位凭据前验证服务器证书指纹。本协议不定义明文升级 TLS。已固定的 SHA-256 指纹指服务器叶证书完整 DER 字节摘要，原始 32 字节；不能用 PEM 文本或 SPKI 摘要代替。明确配置的本机 loopback 测试端口可省略 TLS。

邀请 URI：`rwx-timeline://HOST:PORT/?v=7&fingerprint=HEX64#TOKEN`。IPv6 host 必须带方括号；端口 1..65535；HEX64 为小写十六进制；TOKEN 为 32..128 个 ASCII 字母、数字、`-`、`_`。无 userinfo，无其他 query，path 固定 `/`，不做百分号解码。日志不得输出 TOKEN。

连接顺序：TCP → TLS → ClientHello → ServerHello → MapOffer → MapHave → 可选 MapChunk → MapComplete → OpenTimeline。认证失败返回 Reject 后关闭。ServerHello、MapOffer、MapHave、MapComplete 在每次连接中各最多一次；客户端在 MapComplete 前不得打开时间线或发送命令。

### 3.1 ClientHello（1）

| ID | kind | 字段 | 约束 |
|---:|---|---|---|
| 1 | U32 | profileId | 1 |
| 2 | TEXT | playerName | 1..128 字节，不能全为空白 |
| 3 | TEXT | seatToken | 32..128 字节，字符集同邀请 TOKEN |

profileId=1 固定本规范的属性、刻度和能力语义。昵称不决定席位。

### 3.2 ServerHello（101）

| ID | kind | 字段 | 约束 |
|---:|---|---|---|
| 1 | U32 | profileId | 1 |
| 2 | U32 | teamId | 0..63 |
| 3 | U32 | ticksPerSecond | 1..1000 |
| 4 | U32 | subdivisions | 1024 |
| 5 | BYTES | matchId | 16 字节，同一场比赛不变 |
| 6 | BYTES | sessionId | 16 字节，每次认证后新建 |
| 7 | U64 | serverTime | 普通 time |
| 8 | U64 | historyFrom | 该席位可请求历史的下界，≤serverTime |
| 9 | U64 | maxPredictionTime | 正数，最多一秒的时间单位 |
| 10 | U32 | liveWindowSeconds | 1..120，建议 30 |
| 11 | U32 | renderDelayHintMs | 0..500，仅呈现建议 |
| 12 | BYTES | accessId | 16 字节，本次历史授权身份；同一授权重连可保持 |
| 13 | U64 | joinedAt | 该授权开始时间；historyFrom≥joinedAt |
| 14 | U32 | admission | 1=首次中途加入，2=恢复既有授权 |

sessionId、流 epoch、streamId 一起隔离迟到消息和旧命令，不跨重连继承操作许可。

席位分配与加入批准由房间服务控制，客户端不能自行选择 teamId。首次加入和原授权重连必须区分；把席位交给另一位玩家时建立新 accessId。新授权不得借用该席位前任的历史。所有地图及时间线记录均受本授权约束。

### 3.3 Reject（102）

ID1 U32 `code`；ID2 TEXT `reason`，1..1024 字节。code：1=版本或 profile 不支持，2=认证或席位失败，3=资源限额，4=请求不合法，5=服务不可用。认证失败不得区分无效 token 与席位已占用。收到畸形头不保证能收到 Reject。

### 3.4 Ping（7）与 Pong（111）

Ping：ID1 U64 `nonce`，正数，本连接严格递增、不复用；ID2 U64 `clientStamp`，客户端自定单调时间值。

Pong：ID1 U64 `nonce`；ID2 U64 `clientStamp`，逐位回显；ID3 U64 `serverTime`。

Pong 用于估计 RTT 和显示时钟，不改变时间线水位或摘要。客户端不能假定双方系统时钟同步。建议空闲时每秒 Ping 一次；任意合法完整上行消息均满足连接保活。

## 4. 公共静态地图与缓存

地图不含动态迷雾、单位、资源、命令、能力、凭据、脚本或战局历史。`mapKey=SHA-256(canonicalMapRecord)`，其中 canonicalMapRecord 是下述 TLV 记录的完整字节，不含网络帧头。

### 4.1 canonicalMapRecord

| ID | kind | 字段 | 约束 |
|---:|---|---|---|
| 1 | U32 | mapSchema | 1 |
| 2 | U32 | width | 1..1024 |
| 3 | U32 | height | 1..1024，width*height≤1,048,576 |
| 4 | U32 | tileWidth | 1..4096 |
| 5 | U32 | tileHeight | 1..4096 |
| 6 | BYTES | terrain | width*height 字节；每格 bit0 水、bit1 崖、bit2 公共资源点、bit3 禁建，其余零 |
| 7 | U32 | visualCatalog | 1 |
| 8 | BYTES | ground | 每格 u16be，0..234；长度 2*width*height |
| 9 | BYTES | decoration | 同上，0 表示无装饰 |
| 10 | BYTES | resourcePools | count:vuint≤1,048,576，随后按 y、x 升序的 x:vuint,y:vuint；不得重复或越界 |

terrain、ground、decoration 均按行优先存放，格子 `(x,y)` 的下标为 `y*width+x`。ground 和 decoration 的该格字节偏移为 `2*(y*width+x)`。

目录 1 的图块定义见附件 `terrain-catalog-v1.json`。地图大小与 tile 尺寸还必须保证几何量化坐标不超出 vsint 范围。地图哈希包含全部字段及保留的 optional 扩展，不能只计算地形数组。

resourcePools 必须与 terrain 的 bit2 格子集合完全一致，不能为同一地图提供两套冲突的公共资源点。

### 4.2 地图消息

MapOffer（103）：ID1 BYTES `mapKey`=32 字节；ID2 U64 `totalBytes`，1..64 MiB。

MapHave（2）：ID1 BYTES `mapKey`；ID2 BOOL `have`。客户端只查找本次提出的 key，验证完整记录和摘要并钉在内存后才可回复 true。不能发送整个缓存目录。

MapChunk（104）：ID1 BYTES `mapKey`；ID2 U64 `offset`；ID3 BYTES `data`，1..65536 字节。have=false 时，从 offset=0 连续发送，不能重叠、留洞或越过 totalBytes。

MapComplete（105）：ID1 BYTES `mapKey`。have=true 时不得发送 MapChunk；have=false 时所有字节必须先到齐。客户端在此时验证完整结构与 SHA-256，成功后才能初始化地图；重复或晚到的地图消息非法。

缓存数量由客户端配置，0 表示关闭，建议默认 16、范围 0..256；这不是线上字段。缓存文件只能包含这一份静态地图记录。未命中、损坏或被淘汰时正常回复 false；握手中途磁盘文件消失不影响已经钉住的内存地图。

## 5. 时间线流与事务

每条流只承载认证席位的观察。LIVE 流持续接收当前观察及有界历史；HISTORY 流承载已封口的指定历史窗口，完成后保持只读。多个摄像机可共享同一流，多种观看时间可分别使用不同流。不能通过请求参数切换到其他席位或获得全图历史。

### 5.1 OpenTimeline（3）

| ID | kind | 字段 | 约束 |
|---:|---|---|---|
| 1 | U32 | requestId | 正数，本连接严格递增、不复用 |
| 2 | U32 | mode | 1=LIVE，2=HISTORY |
| 3 | U64 | fromTime | LIVE=0；HISTORY 为请求下界 |
| 4 | U64 | untilTime | LIVE=0；HISTORY 为排他上界，>fromTime |
| 5 | U32 | budgetBytesPerSecond | 16,384..16,777,216，服务端可降低接纳值 |

LIVE 的实际保留窗口由状态答复确定；HISTORY 单次请求最多 120 秒，不保证该区间均有归档。请求失败不能默默返回另一席位、另一时间或残缺的连续区间。

### 5.2 TimelineStatus（110）

ID1 U32 `requestId`；ID2 U32 `status`：0=接纳，1=区间不可用，2=限额，3=请求非法；ID3 U32 `streamId`：成功时正数且本连接不复用，失败时 0；ID4 U32 `mode`；ID5 U64 `fromTime`；ID6 U64 `untilTime`；ID7 U32 `budgetBytesPerSecond`；ID8 U64 `historyFrom`；ID9 U64 `historyUntil`。

成功 HISTORY 答复必须精确覆盖请求区间；fromTime/untilTime 不得悄悄改变。该流此后所有 RESET 和 UPDATE 的 retainedFrom/retainedUntil 必须分别等于已接纳的 fromTime/untilTime；需要另一区间时须发起新的 OpenTimeline。失败时 ID5..7 为 0，ID8..9 给出可请求范围。成功 LIVE 的 ID5..6 给初始有界窗口，后续事务可显式推进它。每个 requestId 只答复一次。

### 5.3 TimelineBegin（106）

| ID | kind | 字段 | 约束 |
|---:|---|---|---|
| 1 | U32 | streamId | 已接纳的流 |
| 2 | U64 | epoch | 正数；完整恢复时新建，不能复用 |
| 3 | U32 | kind | 1=RESET，2=UPDATE |
| 4 | U64 | revision | 正数 |
| 5 | U64 | baseRevision | RESET=0；UPDATE=上一提交 revision |
| 6 | U64 | observedThrough | 服务器已观察到的当前 time |
| 7 | U64 | sealedThrough | 其前的历史已封口 |
| 8 | U64 | retainedFrom | 在线库保留区间起点 |
| 9 | U64 | retainedUntil | 在线库可采样的排他上界 |
| 10 | U64 | controlEpoch | LIVE 正数；HISTORY=0 |
| 11 | U64 | operationBytes | 完整操作串字节数，1..64 MiB |
| 12 | BYTES | operationSha256 | 操作串 SHA-256，32 字节 |
| 13 | U64 | objectHighWater | 本 epoch 已分配的最大非 ROOT 观察句柄 |

必须满足 `retainedFrom≤sealedThrough≤observedThrough<retainedUntil≤observedThrough+maxPredictionTime+1`。HISTORY 的整个区间必须已封口，因而 `retainedUntil≤sealedThrough`；这一条件替代前式中的 `observedThrough<retainedUntil`，并且其 observedThrough 可以晚于所请求窗口。

RESET 的 revision=1，以空库为基础建立完整当前窗口。每流初次 epoch=1，后续 RESET 的 epoch=旧 epoch+1，禁止跳回或复用，耗尽时结束该流；不需要无限保存旧 epoch 墓碑。恢复 RESET 同时使 LIVE 的 controlEpoch 严格增加；旧命令、ACK 和事务失效。UPDATE 的 epoch 不变，revision=baseRevision+1，三个水位、controlEpoch 和 objectHighWater 不回退；retainedUntil 不必严格增加，但不得截掉已封口且仍声明保留的历史。

LIVE 即使没有属性变化，也必须至少每秒提交一个可含零操作的 UPDATE，增加 revision、生成新收据，并在其后发 StateCheck。只有取得新的真实观察时才能推进观察水位和可用窗口；终局结束标记之后保持第 7.2 节规定的固定水位。Pong 不能代替这一事务。客户端渲染游标必须小于 retainedUntil。

封口与实时更新分别调度。默认封口延迟目标约 5 秒；采用 100 ms 发布间隔时，在 4.9 秒触发，为下一次发布留出余量。较短的 LIVE 保留窗口应相应缩短这一间隔。发布线程停顿时，这不是墙钟时间上限；客户端必须以收到的 sealedThrough 判断历史是否稳定。在事件尚未齐全时不得伪称封口。终局和结束归档应立即确认已经取得的观察，不必等满一个批次；这不会延长非恒值预测的一秒时域，也不会降低每秒完整校验的频率。

操作按第 6 节解析，但在完整验证并收到 Commit 前，不得对外发布新库、执行脉冲、改变选择或推进可见水位。每流只允许一个暂存事务，不同流的块可交错。

先用旧 sealedThrough 验证并应用所有操作，再验证新水位。新封口区间内必须已经具有完整授权观察；所有与 `[retainedFrom,新 sealedThrough)` 相交的 flags=1 段，必须通过 splice 被真实观察确认、修正或裁除。不能仅推进水位便把未经验证的预测变成不可修改历史。原锚点即使在有效区间外，也必须来自已经获准披露的数据；裁剪有效区间不能隐藏锚点字节中的秘密。

### 5.4 TimelineChunk（107）、TimelineCommit（108）

Chunk：ID1 U32 streamId；ID2 U64 epoch；ID3 U64 revision；ID4 U64 offset；ID5 BYTES data，1..65536 字节。offset 从零连续推进，直到 operationBytes，没有重复、空洞或越界。

Commit：ID1 U32 streamId；ID2 U64 epoch；ID3 U64 revision。此时全部字节必须到齐且 SHA-256 匹配；随后事务在私有暂存库中完成全部操作、完整结构和限额验证，再一次替换当前库。RESET 也如此。

同一流的事务帧不得乱序。重复 Commit、无 Begin 的 Chunk、身份不符、非法操作或修改封口历史均属协议错误。只有已知流缺失 baseRevision 才允许 Restore；不能用恢复掩盖畸形操作。发送端只在 Commit 完整写出后将该版本计为已提交。

缺少 base 时仍须接收、限额检查并校验此事务的分块顺序、总长、SHA-256 和全部自描述字节语法；不能应用需要旧库的对象语义。消费其 Commit 后只发送一次 Restore 并进入等待 RESET。期间已在途的同 epoch UPDATE 也按相同结构规则消费并丢弃，不能推进库或反复请求恢复。新 RESET 必须在上一事务 Commit 之后开始；未知 opcode、非法 TLV/整数、块身份错误及超限仍须断连。

接收端必须保留声明的 mode/valueType 才能对普通操作进行完整语义校验；缺库时至少检查 opcode/长度、可独立解析的头和 TLV，带类型值的未知上下文部分按有界 bodyBytes 保留并丢弃，不能据此确认同步。RESET 必须包含足够声明，不得依赖被丢弃的上下文。恢复等待期间接收结构合法字节不延长绝对期限。

### 5.5 CloseTimeline（4）、StreamEnd（112）、SetBudget（10）

CloseTimeline：ID1 U32 streamId。服务端答 StreamEnd 后不得再为它发送新事务。

StreamEnd：ID1 U32 streamId；ID2 U64 epoch；ID3 U32 reason：1=客户端关闭，2=历史窗口发送完成，3=不可继续提供数据，4=比赛归档结束；ID4 U64 historyFrom；ID5 U64 historyUntil。HISTORY reason=2 后，客户端仍可采样已提交数据，但不再向该流发送 Restore 或命令。缺失区间必须明确显示不可用。

历史窗口完成只对该 streamId 当前 epoch 中已通过完整校验的当前 revision 生效。旧 epoch 的 StreamEnd 不得结束新 epoch，也不得把新库标为已完成。完成状态必须随当前库身份一起保存；不能仅凭 streamId 或曾经校验成功就认定完成。

reason=1 或 3 可以在完整帧边界中止尚未 Commit 的暂存事务；接收端必须丢弃该流全部暂存块，不发布半状态，不影响其他流。已提交库可继续只读显示。reason=2 或 4 不得中止事务，必须在完整提交及所需摘要确认后结束。客户端请求关闭 LIVE 后即停止产生该流命令，不必等待网络往返才撤销本地操作资格。

结束流的迟到 CheckAck 或重复 CloseTimeline 可以丢弃；Restore、Select、Command 不得恢复该流或授予权限。未知且从未分配的 streamId 属协议错误。streamId 递增分配、不复用，耗尽 u32 时须关闭连接；仅保留分配高水位和活动流即可，不需要无限保存关闭墓碑。

SetBudget：ID1 U32 streamId；ID2 U32 budgetBytesPerSecond，范围同 OpenTimeline。预算只约束传输调度，不能授权更大误差、删掉事件、隐式改窗口或泄露新信息。服务端不能为降低流量而仅保留“当前活对象”；发送间隙内出现后结束的观察及事件也属于历史。

## 6. 对象、曲线与操作串

操作串：`operationCount:vuint`，随后逐项为 `opcode:u8 | bodyBytes:vuint | body`。operationCount 可为 0，但串本身至少包含 00。bodyBytes 只计 body 的字节，不包含 opcode 或 bodyBytes 自身的 vuint 编码，最大 1 MiB。每项精确消费 bodyBytes；操作没有可跳过的未知 opcode。

对象句柄属于本席位、本流 epoch 的观察身份。0 专供 ROOT，其余为正 vuint。句柄不是引擎全局 ID，结束后不得复用；再次发现敌军使用新观察身份，不能通过句柄把隐藏区间自动连接起来。

UPDATE 新建句柄必须严格大于旧 objectHighWater，按操作顺序递增；事务最终 objectHighWater 恰为旧值与新建最大值之较大者，不能因隐藏实体或未披露操作而增长。RESET 可恢复不超过所声明 objectHighWater 的既有句柄，仍须唯一；旧的已裁剪句柄由高水位禁止复用，不需要无限保存墓碑。

| opcode | 名称 |
|---:|---|
| 1 | ObjectCreate |
| 2 | CurveDeclare |
| 3 | CurveSplice |
| 4 | PulseSplice |
| 5 | ObservationClose |
| 6 | RetainFrom |

RESET 使用 ObjectCreate、CurveDeclare 和完整曲线内容恢复窗口；不能携带依赖旧 epoch 的 expectedRevision。RESET 中每条曲线初始 revision=0，首次 splice 后为 1。窗口起点之前开始的观察可被纳入，但只附足以独立采样当前窗口的数据，不能借基线补出此前未获准知道的历史。

RESET 的旧库为空、旧 sealedThrough=0，旧封口限制仅适用于 UPDATE。RESET 先重建全部对象和曲线，再按 Begin 的完整水位、关闭边界和授权上下文验证；不能先套用新 sealedThrough 而拒绝恢复已有历史。曲线修订号按此 epoch 实际执行的 splice 次数从零建立，不继承被合并或旧 epoch 的计数。

### 6.1 ObjectCreate（1）

主体：`handle:vuint | objectKind:u8 | observedFrom:vuint | descriptor:vbytes`。

objectKind：0=ROOT，1=UNIT，2=PANEL，3=ACTION，4=VISUAL。ROOT 必须恰好一个、handle=0，其他 kind 不得用 0。observedFrom 是本席位首次获准观察的时间，不是隐藏对象的真实出生时间。

observedFrom 必须为有限普通 time，且不晚于事务 observedThrough。普通 UPDATE 不能在旧 sealedThrough 之前新建观察；RESET 可恢复窗口所需的既有观察。任何曲线段及事件的有效区间必须处于所属观察内；已关闭对象不能再次注入关闭时间及以后的内容。每次 Commit 都重新检查这一关系。

descriptor 为 TLV：ID1 TEXT `presentationKey`（可选显示扩展，critical=0，≤128 字节）；ID2 U64 `subjectHandle`（PANEL/ACTION 必有，指向本流的当前 UNIT）；ID3 U64 `panelHandle`（ACTION 必有，指向 PANEL）；ID4 RECORD `ability`（ACTION 必有，第 9 节）；ID5 BYTES `selection`（PANEL 必有，第 9 节）；其他 kind 不得携带 ID2..5。描述不因普通曲线更新而变化；身份或能力描述变化时关闭旧观察、建立新对象。

### 6.2 CurveDeclare（2）

主体：`handle:vuint | attributeId:vuint | mode:u8 | valueType:u8 | flags:u8 | eventHighWater:vuint | metadata:vbytes`。

attributeId 为 1..2³¹−1，同一对象内唯一。flags 仅 bit0=criticalAttribute，其余零；已定义属性必须使用登记值。eventHighWater 非 PULSE 必须为 0；UPDATE 新建 PULSE 也为 0，RESET 可恢复其已分配事件 ID 高水位。

metadata 是 TLV：PULSE 必有 ID1 U64 `maxTailTime`，0..10 秒，表示一个事件可能持续呈现的最长时间；其他 mode 禁止 ID1。其余只允许 optional 显示注释，不能改变登记属性的刻度、权限或采样规则。未知 optional PULSE 属性也依赖 maxTailTime 保留跨窗口效果，不需要理解其私有载荷。

| mode | 名称 | 允许 valueType |
|---:|---|---|
| 1 | STEP | 全部 |
| 2 | LINEAR | SINT、VEC3、UINT |
| 3 | ANGLE | UINT，数值 0..65535 |
| 4 | PULSE | 全部 |

| valueType | 名称 | 紧凑值编码 |
|---:|---|---|
| 1 | SINT | 一个 vsint |
| 2 | VEC3 | x、y、z 三个 vsint |
| 3 | UINT | 一个 vuint |
| 4 | BOOL | 一个 u8，严格 0/1 |
| 5 | BLOB | vbytes，≤65536 字节；登记属性另有格式要求 |

未识别且 criticalAttribute=1 的属性须拒绝。未识别且为 0 的属性也必须按通用类型保存、修订、裁剪和校验，只允许呈现层忽略它。未知 mode 或 valueType 不能作为不透明曲线跳过。

### 6.3 CurveSplice（3）

主体：`handle:vuint | attributeId:vuint | expectedRevision:vuint | from:vuint | until:vuint | segmentCount:vuint | segments`。

属性必须已声明且不是 PULSE；expectedRevision 必须等于库中的曲线修订号。成功后修订号恰好加 1，不允许回绕。from<until。段编码如下，均采用绝对 time，而非前段的相对时间：

| 顺序 | 编码 | 字段 |
|---:|---|---|
| 1 | vuint | lo |
| 2 | vuint | hi，lo<hi |
| 3 | u8 | flags，0=普通，1=预测，其余非法 |
| 4 | value | STEP 的值；STEP 段至此结束 |
| 4 | vuint | LINEAR/ANGLE 的 anchor0Time |
| 5 | vuint | LINEAR/ANGLE 的 anchor1Time |
| 6 | value | value0 |
| 7 | value | value1 |

LINEAR/ANGLE 须满足 `anchor0Time≤lo<hi≤anchor1Time`、`anchor0Time<anchor1Time`。STEP 及两端值相等的恒值段可以持续到 FOREVER；它表示最后已知值保持，并不承诺未来游戏事实。非恒值预测端点不能超过当前 observedThrough+maxPredictionTime。

非恒值 flags=0 段的 anchor1Time 必须≤observedThrough；flags=1 段的 anchor1Time 必须≤observedThrough+maxPredictionTime，并且有效区间不得进入新封口历史。登记属性仅 position、heading、buildProgress 和 ACTION.progress 允许预测；STEP、离散敏感属性以及登记的其他属性禁止 flags=1。本 profile 没有 optional 预测能力协商，所有未知 optional 属性也禁止 flags=1。

所有 STEP 段的 lo 必须≤observedThrough；不得以 flags=0 预置尚未发生的资源、控制、库存或比赛状态变化。终局 matchStatus 的 finishedAt 必须≤其 STEP 段 lo，不能在更早的历史宣称已经终局。

segments 按 lo 严格升序、互不重叠，并完全位于 `[from,until)`；允许空列表或段间留空。splice 先移除该区间中的旧内容，再装入新段。与边界相交的旧段只裁剪有效 lo/hi，必须保留原 anchor0Time、anchor1Time、value0、value1 和 flags。不能用舍入后的边界值重拟合旧段，否则会改变已经封口的历史。

同一事务可多次修改一条曲线，但必须逐次匹配并递增修订号。UPDATE 的任何编辑区间不得与 `[retainedFrom,旧 sealedThrough)` 相交；仅改变未来锚点而间接改变封口区间同样非法。新事务可以将已确认的预测段标为普通段，但也必须使用明确 splice，不能由客户端时钟隐式改变 flags。

UPDATE 的 from 必须≥本事务 retainedFrom，不能通过修改保留窗口之前的 carry-in 数据间接改写窗口内效果。RESET 恢复必要 carry-in 事件及原锚点不受这一 UPDATE 编辑下界限制。

### 6.4 PulseSplice（4）

主体同 CurveSplice 的前五项，随后 `eventCount:vuint` 和事件列表；属性必须为 PULSE。

每个事件：`time:vuint | eventId:vuint | value`。eventId>0，在该曲线整个观察生命周期内不复用。列表按 `(time,eventId)` 严格升序，时间必须位于 `[from,until)`。先移除区间内事件再装入新事件，修订和封口规则同 CurveSplice。

所有 PULSE（包括 optional 属性）事件 time 必须≤事务 observedThrough。未来公开预告应使用明确的显示状态或连续曲线，不能把尚未发生的事件写成已经发生的脉冲。

同一曲线、同一 time 最多 4096 个事件，而且该组事件的完整紧凑编码总和不得超过 1,048,522 字节，为 PulseSplice 六个紧凑头字段各预留最多 9 字节。独立基线可按时间组及操作字节上限拆成多个 PulseSplice，但不得把同时间组拆成会互相覆盖的两个区间。

UPDATE 中新事件的 ID 必须大于该曲线旧 eventHighWater，按新事件出现顺序递增，高水位相应推进。原区间内同 ID、同 time、同 value 的事件可以逐位原样重列，但不能用旧 ID 修改事件或复活已删除事件。修改事件须分配新 ID。RESET 的整个初始化阶段可通过多个 PulseSplice 装入不超过声明 eventHighWater 的既有事件，跨操作仍须唯一并验证范围；不能因单操作 4096 项上限而遗漏事件。RESET 不降低或从现存事件重新推导该高水位。事件高水位保留到对象被回收，不能因事件裁剪而降低。

### 6.5 ObservationClose（5）

主体：`handle:vuint | untilTime:vuint | closeKind:u8`。handle≠0；只允许关闭一次；untilTime 为有限普通 time，observedFrom≤untilTime≤事务 observedThrough，不得早于旧 sealedThrough。closeKind=1 表示观察结束、实际命运未知，2 表示已经获准确认销毁或结束。UNIT 的隐藏死亡不能发送 closeKind=2。

关闭原子地截断 untilTime 及以后的所有曲线和脉冲；已经发生的脉冲可按其已披露寿命继续呈现。与边界相交的段保留原锚点，只裁剪有效区间。已经获准的历史仍留在库中，直至显式保留水位越过它。客户端可用明确的“最后已知”样式显示记忆，但不得把它当作当前位置或当前可攻击目标。

关闭不隐式改写 ROOT.selection、PANEL、ACTION 或它们的曲线修订号。发送端必须在同一事务中明确更新选择、关闭相关能力对象并提高 controlEpoch。提交时所有当前有效 PANEL/ACTION 的 subjectHandle 必须指向当前有效且可控的 UNIT，ACTION.panelHandle 必须指向匹配 PANEL；否则整个事务拒绝。历史引用可以指向已经关闭但尚保留的对象，不能悬空。

### 6.6 RetainFrom（6）

主体：`newFrom:vuint`，必须等于 Begin 的 retainedFrom，且 `旧 retainedFrom≤newFrom≤旧 sealedThrough`。当 UPDATE 提高 retainedFrom 时，必须恰好有一个此操作，且为操作串第一项；不提高时不得出现。RESET 不使用此操作。

删除 hi≤newFrom 的段；跨界段只裁剪 lo，保留原锚点。事件只有在 `time<newFrom 且 time+maxTailTime≤newFrom` 时才可删除，因此保留窗口内的瞬时事件，以及窗口开始前发生、但仍可能持续的效果。相加须检查溢出。

已经关闭的对象仅在 `untilTime+对象最大 maxTailTime≤newFrom`、没有仍保留的对象引用它时删除；否则保留其必要身份。一次回收反复删除满足条件且无引用者的对象，直到不再变化，按 handle 升序执行每轮；声明对象关系只允许 ACTION→PANEL/UNIT、PANEL→UNIT，不得成环。ROOT 与仍在观察中的对象不得删除。不得以本地观看游标、缓存压力或墙钟自行裁剪参与摘要的库。

ObservationClose 和 RetainFrom 的确定性裁剪不改变 curveRevision；只有 CurveSplice/PulseSplice 使其递增。整个库仍由事务 revision 和完整摘要识别，不能仅依靠单曲线修订号判断内容是否变化。

## 7. 属性登记与采样

属性登记是 profileId=1 的一部分。下表属性均 criticalAttribute=1。未列的 1..65535 属性为保留值，不论 flags 都必须拒绝。65536..2³¹−1 只可用于 criticalAttribute=0 的 optional 显示扩展；它们不得授予控制、引用隐藏对象或改变已登记语义。fogPage 超过实际地图所需页数同样非法。

| 对象 | ID | 名称 | mode/type | 值 |
|---|---:|---|---|---|
| ROOT | 1 | credits | STEP/SINT | 1/1024 资源单位 |
| ROOT | 2 | matchStatus | STEP/BLOB | 第 7.2 节 |
| ROOT | 3 | selection | STEP/BLOB | vuint 数量＋升序单位句柄，≤256 |
| ROOT | 4096..8191 | fogPage | STEP/BLOB | 第 7.3 节，页号=ID−4096 |
| UNIT | 1 | position | LINEAR/VEC3 | x,y,height，1/256 世界单位 |
| UNIT | 2 | heading | ANGLE/UINT | 65536 对应一圈，线上 0..65535 |
| UNIT | 3 | health | STEP/SINT | 1/256 血量单位，允许负值 |
| UNIT | 4 | maxHealth | STEP/SINT | 正数，1/256 血量单位 |
| UNIT | 5 | buildProgress | LINEAR/UINT | 0..65535，满值=1 |
| UNIT | 6 | ownerTeam | STEP/SINT | −2=无主，−1=中立，0..63=玩家队伍 |
| UNIT | 7 | appearanceKey | STEP/BLOB | 1..128 字节 UTF-8 单位显示类型 key |
| UNIT | 8 | techLevel | STEP/UINT | 1..3 |
| UNIT | 9 | controllable | STEP/BOOL | 仅表示当前操作资格，不是永久权限 |
| PANEL | 1 | open | STEP/BOOL | 是否当前显示 |
| ACTION | 1 | readiness | STEP/UINT | 0=不可用，1=可用，2=UNKNOWN |
| ACTION | 2 | queuedCount | STEP/SINT | −1=未知，其余 0..65536 |
| ACTION | 3 | stockpileCount | STEP/SINT | 同上 |
| ACTION | 4 | progress | LINEAR/UINT | 0..65535 |
| ACTION | 5 | canCancel | STEP/BOOL | 是否提示可取消一项队列 |
| VISUAL | 1 | events | PULSE/BLOB | 第 7.4 节 |

ROOT 必须具备 credits、matchStatus、selection；每个已观察的 UNIT 必须具备全部 UNIT 登记属性。它们须完整覆盖观察区间与 `[retainedFrom,min(observedThrough+1,retainedUntil))` 的交集，不能留洞。未来预测区间没有完整属性时只能显示陈旧或不可用，不能伪造完整未来单位。HISTORY 窗口的起点需要相应前值或跨界段，不能依赖之前未加载的渲染帧。不能只因 ownerTeam 等于本人就跳过控权审核。

当前开放 PANEL 的 selection 必须为 ROOT 当前选择的同一单个 subjectHandle；ACTION 的当前有效区间不得超出其 PANEL/UNIT 的当前资格。历史引用按历史时刻审核，不要求其目标现在仍在观察。

### 7.1 精确采样

STEP 在 `[lo,hi)` 内恒为该段值，边界右连续。LINEAR 对每个分量计算

`N=value0*(anchor1Time-t)+value1*(t-anchor0Time)`，`D=anchor1Time-anchor0Time`，结果为 N/D 舍入到最近整数，恰好半数时远离零。

必须对完整 N/D 舍入，不能先对增量舍入再加 value0。中间乘积使用足够宽的整数，至少覆盖所给范围的 128 位运算或任意精度。

ANGLE 先令 delta 为 `(value1-value0)` 对 65536 的最短有符号差，范围 −32767..32768，恰好半圈取 +32768；再对 `value0+delta*(t-anchor0Time)/D` 按上述完整有理数规则舍入，最后取非负模 65536。

恰好 t=hi 时使用下一段，不使用前段。没有覆盖段则该属性在该时刻不可用，不能跨空白区间自动插值或外推。恒值保持也须由显式段表示。呈现层可再用浮点绘图，但不能用绘图结果产生摘要、扣费、命中或能力判定。

写入几何刻度采用同一“最近、半数远离零”规则；进度还须在写入前验证处于 0..1，不能用 clamp 掩盖非法值。位置 x/y 原始值先验证位于地图半开边界，然后在合法整数集合中选择最近值，分别为 `0..width*tileWidth*256−1`、`0..height*tileHeight*256−1`；贴近上界的合法值取集合内最后一点。height 可为负。这个域内量化规则不能用于接纳原本越界坐标。降采样不得让显示曲线越过地图边界。

### 7.2 比赛状态

matchStatus BLOB：`phase:u8 | finishedAt:vuint | winnerCount:vuint | winners:vuint[] | defeatedCount:vuint | defeated:vuint[]`。phase=0 进行中，此时 finishedAt=0、winners 为空；phase=1 终局，此时 finishedAt≤observedThrough。队伍 ID 0..63，各列表严格升序、无重复、不相交。终局一旦封口不能恢复进行中；终局不自动授予全图或过去隐藏信息。

终局发生于 F 时，先提交从 F 开始的终局状态，再以 F+1 作为最后观察结束标记，封口至 F+1。这里的 1 是一个 subTick；仿真、finishedAt 和已关闭观察的边界仍固定在 F。这样排他归档区间能够包含 F 时刻的终局结果。结束标记只产生一次，此后冻结时间线，由空事务和 StateCheck 保持连接；不能每秒继续增加这个标记。

### 7.3 迷雾页

地图按行优先展平，每页 256 格，最后一页可不足 256。未声明、或在采样时没有覆盖段的 fogPage 属性表示整页值 10（未知），这是本 profile 的显式缺值默认；只有 ROOT 可以声明 fogPage。每格范围 0..10，0..4 表示本时刻可见，5..9 表示记忆或边缘，10 表示未知；实体披露仍以观察身份为准。

BLOB 首字节 encoding：0=原始，随后恰好该页格数个 u8；1=RLE，随后 `runCount:vuint`，每段为 `length:vuint>0 | value:u8`，长度之和恰好等于该页格数，禁止相邻同值。必须选择总字节数更短的编码，相等选原始；从而同一页内容只有一种规范编码。每个关键值是独立完整页，不是依赖上一帧的累计补丁。

### 7.4 可见效果事件

events BLOB 为 TLV：ID1 U32 `effectKind`（1=开火、2=命中特效、3=可见爆炸）；ID2 BYTES `position`（三个 vsint）；ID3 U64 `duration`（≤10 秒）；ID4 U32 `seed`；ID5 TEXT `presentationKey`（1..128 字节，可选显示扩展、critical=0）。无攻击者、目标、载员、寻路或隐藏对象引用。

普通前进时，对 `(上次观看时间,本次观看时间]` 内事件按时间及 ID 触发一次。seek、倒放和重新加载时间片不重复执行声音、扣费、命令或统计副作用。需要历史效果时，按事件时间、寿命和 seed 纯计算视觉状态；到达同一时刻的结果不能取决于之前从何处播放。

duration 不得大于该曲线 metadata.maxTailTime。普通游戏效果不得预发未来事件；time 必须不晚于提交事务的 observedThrough。归档独立时间片必须携带窗口开始前、尾效仍可能进入该片的事件及必要对象描述。

## 8. 披露、预测与封口

服务器先产生获准披露的观察，再从这些观察生成曲线。不能把完整世界曲线交给客户端再靠渲染层隐藏。

1. 观察区间之外不新增该敌军的移动、死亡、升级、生产、运输、改属或目标信息。关闭观察不等于确认死亡；隐藏期间发生什么，不得影响该观察的后续曲线修订、事件或历史答复。
2. 首次看见敌军只能获得当前观察开始后的内容，不能同时获取其真实出生时间、此前轨迹或生产历史。再次出现使用新观察身份；不能跨隐藏区间连接曲线。
3. 已经获准的历史保留供回看；撤销当前操作不能删除过去的观察。当前视野也不能追溯授权过去不可见的区域。
4. 预测必须标识 flags=1、有界，并且只依赖已获准披露的数据。可根据过去已披露的速度生成运动预测；不得预发由完整寻路、隐藏碰撞或隐藏目标推算出的真实未来路线。己方预测同样不能把隐藏世界编码进去。
5. 已发送的预测端点无法真正收回。后续修正只能基于仍然获准披露的信息；观察关闭后，不能因为隐藏实体偏离旧预测而再发送修正。公开事件可以预告，秘密攻击不能借预告泄露。
6. 资源、控制权、库存、能力和胜负使用实际权威变化，不使用预测提前生效。连续进度可预测显示，但不能由预测完成时刻解锁操作。
7. 已封口历史不可修改。封口前的曲线替换必须保留原始锚点对封口区间的影响；客户端不能简单按“修改的关键帧时间在未来”放行。

服务器必须在仿真观察或事件产生处记录短暂披露、短生命对象及脉冲，不能仅通过低频“当前单位列表”快照生成全部历史。封口表示这些事件已经齐全；不能为提早封口而丢掉两次发送间发生的事实。

## 9. 选择与权威命令

历史数据不构成当前能力。命令没有“执行于过去”的时间字段，不能通过倒放改变战斗。历史窗格只读；其他 LIVE 窗格可以继续操作。地面坐标即使取自历史画面，也只能作为现在执行的新地面命令。

### 9.1 选择 Select（6）

ID1 U32 liveStreamId；ID2 U64 epoch；ID3 U64 queryId（正数递增）；ID4 U64 seenRevision；ID5 BYTES selection（vuint 数量及升序、无重复单位句柄，0..256）。

服务端审核当前真实所有权、可控性和存活状态，更新 ROOT.selection，建立 PANEL/ACTION 观察。无效、越权、过期或清空选择统一产生空能力列表，不解释隐藏对象是否存在。选择变化、类型变化、失控、装载、终局或恢复时，必须撤销旧能力并提高 controlEpoch。

PANEL descriptor.selection 格式同上，其 subjectHandle 为本次能力所属的单个 UNIT；多选时可保留基本群体命令，但本 profile 仅对单个己方单位建立能力面板。ACTION 的 subjectHandle、panelHandle 必须与 PANEL 一致，不能跨流引用。

### 9.2 ability 描述

ability 是 TLV：

| ID | kind | 字段 | 约束 |
|---:|---|---|---|
| 1 | U32 | actionKind | 1..24，见下表 |
| 2 | U32 | targetMode | 0=NONE，1=POINT，2=VISIBLE_UNIT |
| 3 | BOOL | queueable | 能否追加队列 |
| 4 | TEXT | titleKey | 1..128 字节，ASCII [A-Za-z0-9_.-] |
| 5 | TEXT | producedTypeKey | 0..128 字节，同字符集 |
| 6 | I64 | creditsCost | −1=未知，其他为非负、1/1024 资源单位 |
| 7 | U32 | hotkeySlot | 0..9，0=无快捷键 |
| 9 | RECORD | upfrontCost | 可选，critical=0；缺省为未知，格式见下文 |
| 10 | RECORD | streamingCost | 可选，critical=0；缺省为未知，格式见下文 |

两个成本 RECORD 内均必有 ID1 `credits`、ID2 `energy`、ID3 `hp`、ID4 `shield`、ID5 `ammo`，各字段均为 I64、critical=1。每个值使用 1/1024 对应资源单位，−1 表示未知，其他值须非负；不能用 0 代替未知。upfrontCost 出现时，其 credits 必须与外层 ID6 creditsCost 完全相同。已知可选字段即使 critical=0，kind、内层必填字段、数值和一致性仍须严格校验。两个 RECORD 内未识别的 optional 字段按通用 TLV 规则保留，不影响已登记成本语义。

actionKind：1 BUILD、2 PRODUCE、3 UPGRADE、4 REPAIR、5 RECLAIM、6 SET_RALLY、7 LOAD、8 UNLOAD、9 STOP_UNLOAD、10 GROUND_ATTACK、11 PATROL、12 GUARD、13 TOGGLE、14 CONVERT、15 TRANSFORM、16 SPECIAL_POINT、17 SPECIAL_UNIT、18 SPECIAL_NONE、19 CANCEL_CONSTRUCTION、20 SELF_DESTRUCT、21 STOCKPILE、22 SELECT_SLOT、23 ATTACH_MODULE、24 DETACH_MODULE。这些值用于输入呈现，不是引擎动作字符串或可自行构造的授权。

对未经证明只依赖己方或公开信息的动态条件，readiness 必须为 UNKNOWN。不能把动态 tooltip、禁用原因、目标身份或完整私有订单作为能力描述发给客户端。最终执行仍由服务器复核，不能信任 cost、canCancel 或 readiness 的显示值。

### 9.3 Command（5）

| ID | kind | 字段 | 约束 |
|---:|---|---|---|
| 1 | U32 | liveStreamId | 当前唯一 LIVE 流 |
| 2 | U64 | epoch | 当前流 epoch |
| 3 | U64 | commandId | 正数，与该连接所有命令共用严格递增序列 |
| 4 | U64 | seenRevision | 客户端已完整提交的 LIVE 修订 |
| 5 | U64 | controlEpoch | 当前控制代际 |
| 6 | U32 | commandKind | 1 MOVE，2 ATTACK_MOVE，3 STOP，4 ATTACK_UNIT，5 ABILITY |
| 7 | BYTES | selection | 1..256 个升序、无重复当前 UNIT 句柄 |
| 8 | U64 | actionHandle | ABILITY 为当前 ACTION 句柄，其他必须为 0 |
| 9 | RECORD | target | 见下文 |
| 10 | U32 | flags | bit0 queued，bit1 cancel，其余零 |

target：ID1 U32 tag，0 NONE、1 POINT、2 UNIT。NONE 不带其他字段；POINT 另有 ID2 I64 x、ID3 I64 y，单位 1/256 世界单位；UNIT 另有 ID4 U64 handle>0。不同分支禁止带占位字段。

MOVE/ATTACK_MOVE 要求 POINT，STOP 要求 NONE，ATTACK_UNIT 要求 UNIT。基本命令 actionHandle=0、cancel=false；queued 仅对 MOVE/ATTACK_MOVE 允许。ABILITY 必须单选且与 ACTION.subjectHandle 一致；非取消操作 targetMode 必须匹配；cancel=true 时 target=NONE、queued=false，并且服务器当前真实队列允许取消。

seenRevision 必须属于该连接已提交且仍可接受的 LIVE 收据；服务端须保留最近 2 秒的收据，按该修订提交的服务端单调时刻计龄，超过 2 秒的命令必须拒绝执行。静止时仍按第 5.3 节推进空事务，不能让所有收据过期。selection、实体 target 和 actionHandle 必须同时属于所引用收据的披露/能力集合；controlEpoch 必须同时等于该收据与当前值。不能猜中当前句柄后配上任意旧合法修订。

断连、恢复、选择及能力变更后，旧控制代际即使仍在收据窗口内也不得执行。客户端须在排队时记录 epoch/controlEpoch，在实际写出前再次核对；恢复前排队的旧输入不能在新基线后重生。Select 的 seenRevision 同样检查时效、披露集合和当前控制资格。

服务器只在当前仿真中检查所有权、时效、目标可见性、资源、科技和排队条件，再执行命令。迷雾内地面移动和攻击移动合法；实体攻击要求当前仍获准看到该目标。历史对象、最后已知记忆或预测出来的位置不授予目标能力。

当前比赛已经终局或本席位已被权威判定淘汰时，必须拒绝执行全部 Command，包括基本 MOVE/STOP；Select 不再授予新能力。末态单位及历史曲线可继续显示，不构成继续操作资格。

## 10. 每秒完整时间线校验与恢复

校验对象是双方保存的完整时间线副本，包括所有保留历史和获准预测；不是任一渲染帧，也不是客户端重新运行仿真所得的结果。完整时间线一致不表示量化或预测位置逐位等于服务器原始浮点位置。

每个活动 LIVE 流约每秒发起一次完整检查，按单调时钟调度，独立于仿真推进。静止、暂停观看和终局后仍检查。HISTORY 流在完整 RESET 提交后检查一次，确认成功后才可发 reason=2 的 StreamEnd。所有摘要均逐次遍历完整保留库，不能用仅检查修订号或局部脏字段替代。

### 10.1 规范状态串

所有 vuint、段、值及 TLV 使用本规范的唯一编码。拼接顺序：

1. ASCII `RWXT-STATE-7` 加一个 00 字节。
2. matchId 原始 16 字节、accessId 原始 16 字节、mapKey 原始 32 字节、`mapBytes:vuint` 和完整 canonicalMapRecord。不能只用 mapKey 代替地图内容扫描。
3. `profileId:vuint | teamId:vuint | ticksPerSecond:vuint | streamId:vuint | epoch:vuint | revision:vuint | observedThrough:vuint | sealedThrough:vuint | retainedFrom:vuint | retainedUntil:vuint | controlEpoch:vuint | objectHighWater:vuint`。
4. `objectCount:vuint`；按 handle 升序依次写对象：`handle:vuint | kind:u8 | observedFrom:vuint | closePresent:u8`，若关闭则接 `until:vuint | closeKind:u8`；随后 `descriptor:vbytes | curveCount:vuint`。
5. 对象内按 attributeId 升序写曲线：`attributeId:vuint | mode:u8 | valueType:u8 | flags:u8 | eventHighWater:vuint | metadata:vbytes | curveRevision:vuint | recordCount:vuint`，随后按时间顺序写原始段或事件。段使用第 6.3 节完整编码；事件使用第 6.4 节编码。

不得按语义相同而合并段、改写锚点、删除空属性、重排事件或丢掉未知 optional 字节。规范串描述实际提交的库，而不是最小化后的等价函数。曲线修订号、关闭标记、保留水位和授权预测都参与摘要；客户端观看时间、RTT、字体、动画缓存和待提交事务不参与。

digest 为上述串 SHA-256 的前 16 字节。完整 canonicalMapRecord 和全部对象、曲线、段、事件均须重新读取参与计算。

64 MiB 持久状态限额精确计算为上述第 3、4、5 项的规范编码字节数之和；另独立限制第 2 项中的地图记录≤64 MiB。事务暂存副本、工作队列及归档缓存还须有独立有界预算，不能用这一个持久字节数宣称总进程内存有界。

### 10.2 StateCheck（109）与 CheckAck（8）

StateCheck：ID1 U32 streamId；ID2 U64 epoch；ID3 U64 revision；ID4 U64 checkId；ID5 BYTES digest，16 字节。checkId 为连接内跨流严格递增正数。检查必须位于该 revision 的 Commit 之后、同流下一 Begin 之前；服务器计算的是最后成功发送并提交的版本。

客户端在读取下一同流事务前，对自己的完整提交库重算摘要，不能直接回显收到的 digest。CheckAck：ID1 U32 streamId；ID2 U64 epoch；ID3 U64 revision；ID4 U64 checkId；ID5 U32 status（0=有完整库，1=缺库）；ID6 BYTES digest，status=0 时恰好 16 字节，status=1 时为空。status=0 并不表示已匹配，服务器必须比较实际摘要。

校验成功仅证明该次检查指定的 epoch 和 revision。任何后续成功提交，包括同一 epoch 的 UPDATE，均不能沿用上一 revision 的校验结果来完成 HISTORY；必须重新校验当前库。恢复、RESET 或摘要不匹配也使先前的校验结果失效。

摘要不匹配或缺库时，客户端立即撤销该 LIVE 流操作并等待新 epoch RESET；这一份 ACK 已构成恢复请求，不再重复发送 Restore。历史播放可保留最后已确认画面并明确标为陈旧，不能继续发命令。

每流最多保留 32 个待确认检查。第一个未正确确认检查开启独立 10 秒期限；正确确认仍在账本中的当前 epoch 检查后结束等待。更多检查、Ping、错误 ACK、恢复或账本淘汰不能延长期限；到期断连。新的 RESET 不使旧检查合法，也不允许用循环 RESET 续命。

### 10.3 Restore（9）

ID1 U32 streamId；ID2 U64 epoch；ID3 U64 lastRevision（可为 0）；ID4 U32 reason：1=缺少 base，2=本地完整库失效。

恢复只发送全新的曲线时间线 RESET，不发送逐帧完整单位视图作为旁路。每次恢复提高 LIVE 控制代际，撤销尚未写出的旧命令，保留地图内存和独立归档。新 epoch 完整验证提交前，不得恢复操作。

客户端等待 RESET 有独立 10 秒绝对期限；Ping、后续 UPDATE 或更多检查不续期。每流最多连续恢复 3 次，第 4 次断连；正确的完整摘要确认才重置预算。迟到旧 epoch ACK/块/命令不得改变新库或新许可。

## 11. 归档、回放与任意时间采样

在线窗口有界，整局历史由独立归档及索引承载。服务器须保存各席位当时获准的观察；不能按现在的视野重算过去。重连、补历史、录制和终局均不自动扩大授权。

归档的基本单位是已经封口的时间片，不记录客户端输入作为重新仿真的依据。曲线和事件不构成完整服务器存档，不能据此承诺原样恢复整局权威仿真。

### 11.1 自包含文件

| 文件偏移 | 字节数 | 内容 |
|---:|---:|---|
| 0 | 4 | ASCII RWXR |
| 4 | 2 | containerVersion:u16=3 |
| 6 | 2 | wireVersion:u16=7 |
| 8 | 4 | headerLength:u32，随后头记录字节数，≤65536 |
| 12 | headerLength | TLV 头记录 |

头字段：ID1 U32 profileId=1；ID2 U32 teamId，0..63；ID3 U32 ticksPerSecond，1..1000；ID4 U32 subdivisions=1024；ID5 BYTES matchId=16 字节；ID6 BYTES mapKey=32 字节；ID7 BYTES accessId=16 字节；ID8 U64 joinedAt；ID9 U64 historyFrom，≥joinedAt。随后为 `mapLength:u64 | canonicalMapRecord[mapLength]`，mapLength≤64 MiB，必须核对摘要。所有字段均为 critical 且必须出现。

之后依次为记录：`recordLength:u32 | recordType:u8 | payload`。recordLength 含 type、不含自身，1..64 MiB。type=1 为已封口时间片，type=2 为索引，type=3 为完成标记。记录类型没有可选扩展标志，未知 recordType 一律拒绝。

### 11.2 封口时间片（recordType=1）

payload 是 TLV：ID1 U64 fromTime；ID2 U64 untilTime；ID3 U64 observedThrough；ID4 U64 controlEpoch（录制保留，但永远不授予回放操作）；ID5 BYTES operations；ID6 BYTES sha256，32 字节；ID7 U64 objectHighWater。

operations 为可从空库独立建立 `[fromTime,untilTime)` 的 RESET 操作串。文件中对象观察 ID 可稳定跨片，但每片独立包含必要对象、声明和跨界原锚点，以及窗口内全部事件和 `event.time<fromTime 且 event.time+maxTailTime>fromTime` 的 carry-in 事件及必要对象；不能依赖前片的内存。该片 sealedThrough=untilTime，不能含会在片内被未来事务修正的预测；跨界锚点只允许为准确采样该片保留必要端点，不能带入未授权历史。

文件重建不是网络 HISTORY 流：片内 controlEpoch 可保留原始正值，但任何文件来源都永久只读；它不受网络 HISTORY 的 controlEpoch=0 编码限制，也不能据此向网络发送有效命令。

sha256 覆盖 `fromTime:u64 | untilTime:u64 | observedThrough:u64 | controlEpoch:u64 | objectHighWater:u64 | operations`，不含 TLV 头。必须满足 joinedAt/historyFrom 所界定的授权、`fromTime<untilTime≤observedThrough`、完整对象/曲线约束，以及 `objectHighWater≥片内所有句柄`。时间片按 fromTime 严格升序、不重叠；缺口必须在索引中显式表现，不可连成连续可播放区间。每片最多 120 秒，并受逻辑字节上限约束。

### 11.3 索引与完成

type=2 payload：`entryCount:u32`，随后每项 `from:u64 | until:u64 | fileOffset:u64 | recordBytes:u32 | sha256[32]`；按 from 升序，指向实际 type=1 记录。recordBytes 包含 4 字节 recordLength，sha256 是整条记录的 SHA-256。entryCount≤1,048,576，索引自身仍受 64 MiB 限制。

type=3 payload：`indexOffset:u64 | indexRecordSha256[32]`，必须恰好 40 字节，并为文件最后一条记录；此后必须 EOF。没有完成标记的文件只可被明确标为不完整的恢复工具读取，不能作为正常完整回放。

文件必须恰好一个最终索引，且位于所有时间片之后、完成标记之前；每个实际时间片必须被索引恰好引用一次。索引项的时间范围、偏移、长度及摘要都须匹配记录；不能重叠指向、遗漏时间片、索引其他类型或忽略未索引数据。正常读取前必须验证头部、地图、全部记录边界、最终索引和完成标记，之后才能按需懒加载片内数据。

seek 到 T 时先查含 T 的已封口片，再独立加载并采样。不能简单读到“服务器时间≥T”的事务就停止，因为更晚事务可能修正 T。文件不允许联网补图、不初始化战斗仿真、不执行 Command；暂停、反向播放与多窗格只改变各自采样游标。

## 12. 调度、误差与接入时限

仿真、观察、传输和渲染分别调度。建议仿真 60 Hz、曲线批次最高 10 Hz，但短暂事件必须完整记录；稳定曲线不要求每次发送批次都产生新段。客户端可按显示刷新率采样。时间线和命令不等待每秒检查才生效。

profileId=1 对已确认历史的拟合误差上界：位置各分量 1/4 世界单位，朝向 1 度，连续进度 1/65535。服务器在授权范围内监测预测与实际观察的差异，超过上界便生成修正；对尚未收到修正的预测显示，不承诺相同误差上界，须单独统计其峰值和持续时间。网络延迟不能被该数字掩盖。曲线值的采样不能决定实际路径、伤害、资源或命令结果。带宽不足时只能在已确认历史误差上界内调整关键帧密度，不能无声降低语义准确度。

非恒值预测最多一秒；丢失后续更新时，客户端在显式有效区间结束后停止推进该属性，不无限外推。可以显示陈旧标记。低带宽连接不能拖慢其他席位的仿真；服务端应限速、背压或明确结束无法维持的流，而不是丢掉未归档历史。

| 时限或速率 | 规则 |
|---|---|
| 认证 | 10 秒绝对期限 |
| 地图握手 | Offer 开始至 Complete 验证，各端 10 秒绝对期限 |
| 单事务 | Begin 至 Commit 验证，各端 10 秒绝对期限 |
| 无合法完整上行消息 | 10 秒断连 |
| 连续写阻塞 | 10 秒断连 |
| 完整检查确认 | 每流独立 10 秒 |
| 客户端恢复等待 | 每流独立 10 秒 |
| 上行消息 | 每连接每秒窗口最多 120 |
| Command | 每连接每秒窗口最多 60 |
| Select | 每秒最多 8 |
| 显式 Restore | 每流每秒最多接纳一次，可合并 |

长历史必须拆成小于限额的独立窗口和时间片，不能用保活延长某一事务的绝对期限。预算不足以在期限内传完最小有效窗口时，须明确拒绝或结束该流，不能提交半个基线。

## 13. 互通与验收要求

客户端至少应验证：分段读写、非法 TLV、最短整数、跨零舍入、半圈角度、封口裁剪保留原锚点、STEP 边界、同时间多脉冲、未知 optional 状态保留、完整库摘要、分块原子提交、旧 epoch 撤销，以及任意 seek 不依赖先前播放。

信息隔离检查必须覆盖整条可见历史：构造授权观察相同、隐藏出生/死亡/目标/资源不同的两场仿真，比较曲线追加、修正、关闭、pulse、历史答复、重连和摘要。不能只比较当前画面。

带宽与延迟检查须使用相同地图、授权单位及命令样本，计入地图、声明、段、修正、迷雾、能力、完整校验、恢复、历史请求及传输层开销，并分别报告启动、稳定运动、转向碰撞和战斗。没有实测时，不承诺固定压缩比或跨互联网延迟上限。

### 13.1 四项发布门槛

| 要求 | 必须具备的机制 | 通过条件 |
|---|---|---|
| 大规模对局 | 64 席位、65,536 个同时活动世界单位的容量设计；空间兴趣索引、共享不可变数据及有界工作量 | 在公布的硬件、地图和目标仿真步频上，真实运行并记录仿真、观察、复制、全库校验、归档和呈现成本；格式或合成数组通过不能代替真实容量验收 |
| 慢客户端隔离 | 每连接独立预算、队列和写入调度；仿真不等待输入、ACK、网络写入、归档 I/O 或哈希任务 | 正常客户端旁加入限速、停读、延迟、丢包重传及恢复连接；正常端和仿真不被其阻塞，所有队列和内存保持有界 |
| 中途加入 | 新授权与重连分开；从确定时刻的当前检查点初始化，再追赶增量；世界持续运行 | 战斗中加入/重连不暂停、不重载、不等全员；地图缓存命中/未命中均通过，权限和观察起点正确 |
| 自由回看 | 独立完整授权归档、封口时间片及索引；超出 LIVE 窗口按需加载 | 任意合法时间随机 seek、暂停、跨片倒放及多窗格得到一致结果；历史读取不污染 LIVE 状态、控制或摘要 |

这些数字是设计容量与待完成的发布验收目标，不是仅凭格式即保证的运行性能。目标硬件、步频、误差与持续时间须在测试前固定，不能在失败后降低标准仍宣称同一目标通过。

### 13.2 中途加入与慢连接处理

新授权开始后，服务器即采集其获准观察。首次 LIVE RESET 应优先包含足以恢复当前状态的短窗口与必要原锚点，而不是先下载整局历史；更早授权历史通过 HISTORY 按需加载。初始化的检查点固定在某一已知修订，传输期间世界继续，提交后从该检查点追赶后续观察，不能遗漏短暂事件。

已开始的比赛不得因席位暂时未连接而停止推进。新席位的队伍、初始单位、资源和胜负参与规则由房间服务及当前仿真规则建立；不能让新客户端自行构造，也不能复用仅支持固定双人出生点的假设。原授权重连可取得其过去获准历史，新的 accessId 只得到 joinedAt 之后的授权内容。

每连接待发送队列必须设字节上限与事务数上限。建议最多两个待发送事务，网络缓冲合计不超过 8 MiB；大 RESET 应从有界只读快照逐块发送，不把全部块无限排队。合并待发送更新时，revision 和各 curveRevision 按实际向该连接提交的事务重新计算，不继承已经丢弃的待发送版本号。

慢连接落后于可追赶窗口时，可对该连接撤销操作并发送新 epoch 检查点，或明确结束无法维持的流；不能暂停全局仿真。仍在保留或归档范围内的短生命对象、事件和曲线历史不得因“只保留最新包”而丢失。检查点重置只改变该连接的在线副本，不删除独立历史归档。

仿真观察和归档之间也必须有有界队列。归档存储长期无法跟上时，须明确报告归档失败及缺失区间，或按预定故障策略结束服务；不得无限阻塞仿真，也不得静默丢历史并宣称完整回看通过。该场次不能通过完整历史发布门槛。

### 13.3 网络劣化验收

带宽、单向延迟和抖动可通过仅作用于测试连接的字节流代理注入，必须分别报告上下行速率及单向延迟；一次往返延迟不能被误写成单向值。

丢包、重复、乱序和重传须在隔离网络命名空间中对真实 IP/TCP 流量注入，记录真实 TCP 重传计数、队列丢弃和端到端字节完整性。删除 TCP 应用字节仅会破坏协议，不能作为丢包模拟。测试不得改动宿主机网卡、路由、防火墙或其他程序连接。

建议矩阵：RTT 0/50/100/200/400 ms，丢包 0/0.1/1/3/5%，上下行 32/128/512 KiB/s，并覆盖抖动、突发丢包、停读和重新连接。发布记录必须区分网络工具自身验证与真实 v7 游戏验收，分别报告端到端响应 P50/P95/P99、仿真步频、追赶时间、队列/内存峰值、完整校验结果及归档完整性。
