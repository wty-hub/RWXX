# RWXT v7 离线参考工具

本目录提供 [v7 规范](../../authoritative-wire-v7.md) 的离线参考工具，包括帧、规范 TLV、整数采样、22 类消息、事务字节与完整规范状态摘要。工具不连接服务器，也不声称兼容 Planetary Annihilation 的私有二进制协议。

## 范围

- 帧：`payloadLen:u32be | "RWXT" | version:u16be=7 | type:u16be | body`。长度仅计算从 magic 到 body 末尾的字节，范围为 8～1,048,576；完整帧比该长度多 4 字节。
- `body` 为规范 TLV：`id:u16be | flags:u8 | kind:u8 | length:u32be | value`。id 为 1～32767，严格递增、不得重复；flags 仅允许 bit 0 表示 critical。
- 八种已定义 TLV 值：BOOL、U32、U64、I64、TEXT、BYTES、RECORD、RECORDS。U64 上限是 `2^63−1`。TEXT 是严格 UTF-8，禁止 U+0000～001F 和 U+007F～009F，不执行 Unicode 归一化。
- 根 RECORD 深度为 1，最多 8 层；每条 RECORD 最多 256 个字段，每个 RECORDS 最多 65,536 条记录；单个编码块最多 64 MiB。帧仍遵守更小的 1 MiB payload 限额。
- canonical ULEB128 范围为 `0..2^63−1`，最多 9 字节。所谓 ZigZag63 是先将 `−2^62..2^62−1` 映射为非负数，再编码为 ULEB128，**不是标准 signed LEB128**。
- 曲线值为 SINT、VEC3、UINT、BOOL 或 BLOB，曲线 BLOB 最多 65,536 字节；它与普通 TLV 字节块的容器限额不同。没有浮点值类型；传入浮点数，包括 `−0.0`，均拒绝。整数只有一个零编码。
- STEP、LINEAR、ANGLE 段和 PULSE 事件编解码；LINEAR 仅支持 SINT/VEC3/UINT，ANGLE 仅支持 UINT 且角度为 0～65535。

`wire_v7.py` 的结构帧解析只要求 type 为 1～65535；`messages_v7.py` 在其上严格限定 C→S 1..10、S→C 101..112 共 22 类消息，校验方向、必填字段、critical 标志、类型及不依赖连接上下文的数值条件。两层用途不同，处理协议消息时不能只调用结构帧解析。

**这些工具不是完整客户端或权威状态机。** 它们不验证凭据、握手顺序、速率、墙钟期限、连接收据、当前控制资格、观察授权、跨事务封口历史或全部 profile 属性覆盖。通过离线向量不等于通过联网互操作验收。

## 扩展字段

`decode_tlv` 保留所有未知可选字段，包括未知 kind 的原始字节；重新编码须逐字节相同，不能为方便显示而将它们丢弃。未知 critical kind 立即拒绝。字段是否已知由 `validate_schema` 检查：未知 critical id、已知字段 kind 或指定的 critical 标志不符、缺少必填字段均拒绝。

消息及完整规范状态编码保留未知 optional 字节。不得把“可忽略显示效果”理解为“可丢弃已复制状态后仍确认同步成功”。

## 曲线采样

段覆盖 `[lo, hi)`；区间之外返回无值，不跨越空白自动外推。LINEAR/ANGLE 的原锚点满足 `a0 ≤ lo < hi ≤ a1` 和 `a0 < a1`。裁剪只改变 lo/hi，保留原锚点时间和值。

LINEAR 对整体有理数取最近整数，恰好居中时远离零：

```text
roundAway((v0 × (a1 − t) + v1 × (t − a0)) / (a1 − a0))
```

不能先对增量取整再加 v0。VEC3 各分量使用同一规则。ANGLE 先取 `delta ∈ [−32767,32768]`，恰好半圈取正方向，再对 `v0 + delta × (t−a0)/(a1−a0)` 的整体有理数取整并模 65536。全部计算使用精确整数，不经过浮点数。普通时间、采样时间和事件时间最大为 `2^63−2`；`2^63−1` 是 FOREVER 端点哨兵。LINEAR/ANGLE 的后一锚点取 FOREVER 时，两端值必须相同。PULSE 的 eventId 必须大于 0。

时间刻度、服务器 tick 频率和预测窗口由完整消息规范定义，不能由基础采样器自行猜测。

## 消息、事务与状态摘要

[messages_v7.py](messages_v7.py) 提供 `encode_message(frame, direction)`、`decode_message(data, direction)`，方向字符串为 `C2S` 或 `S2C`。已登记 target 分支字段不能作为 optional 占位字段混入其他分支；真正未知 optional 字段仍原样保留。ability 的可选成本 RECORD 也会严格校验类型、五个必填分量与 credits 一致性。

`validate_operations` 校验六种操作的紧凑字节语法。Declare 立即向后续 Splice 提供类型；已有曲线类型通过 `initial_formats` 显式传入。`allow_missing_context=True` 只供缺少基线时检查有界操作外壳，未知 opcode、非法独立头和 TLV 仍拒绝；通过不表示该操作可应用。

`TransactionAssembler` 最多同时暂存四条流，并在 Begin 时预留声明的总字节预算。Chunk 必须连续、身份一致；只有总长、SHA-256 与操作语法通过的 Commit 才返回完整字节。reason=1/3 的 StreamEnd 可中止单流暂存，其他流不受影响。它没有网络计时器或持久世界库；调用者遇到错误须结束当前解析会话，不能继续扫描 magic 试图恢复。

[canonical_v7.py](canonical_v7.py) 的 `canonical_chunks` 按规范第 10.1 节流式重读完整地图和所有对象、曲线、事件；`state_digest` 返回 SHA-256 前 16 字节。对象和属性按 ID 排序，原始段、锚点、事件、关闭信息、修订号和 optional 字节不做语义合并。持久部分与地图各自限额为 64 MiB。输入 JSON 的大整数可用规范十进制字符串，禁止浮点、负零和带前导零的整数字符串。

摘要函数会编码传入的 mapKey，同时重新读取地图字节；地图内容损坏即使未改变缓存的 mapKey 也会改变摘要。地图准入时应另行核对 `SHA-256(canonicalMapRecord)==mapKey`。规范编码检查不能替代库的完整语义和授权校验。

| 附件 | 覆盖 |
|---|---|
| [vectors.json](vectors.json) | 140 个基础案例：65 个正例、75 个负例，含 15 个有效整数采样 |
| [message-vectors.json](message-vectors.json) | 22 类消息；24 个正例、59 个负例，包括方向、旧头和跨分支字段拒绝 |
| [transaction-vectors.json](transaction-vectors.json) | 全部六种操作；4 个正例、11 个负例，包括立即声明上下文与缺基线外壳 |
| [state-fixtures.json](state-fixtures.json) | 三组完整规范字节、持久长度和独立摘要计算输入 |
| [terrain-catalog-v1.json](terrain-catalog-v1.json) | 公共静态地形目录版本 1 |

第一组 365 字节状态样本特意仅用于规范串结构，缺少部分 profile 必有属性，不能作为合法世界基线。其余两组包含必需 ROOT/UNIT 属性，后一组还含选择、PANEL/ACTION 和可选成本。附件验证的是所列字节与摘要，不据此宣称完整状态应用、权限或联网验收已经完成。

## 离线运行

Python 3.10 或更新版本，无第三方依赖。在本目录运行：

```sh
python3 wire_v7.py --verify vectors.json
python3 -O wire_v7.py --verify vectors.json
python3 messages_v7.py --verify message-vectors.json
python3 messages_v7.py --verify-transactions transaction-vectors.json
python3 canonical_v7.py --verify state-fixtures.json
python3 -m unittest discover -p 'test_*v7.py' -v
python3 -O -m unittest discover -p 'test_*v7.py' -v
```

[vectors.json](vectors.json) 包含有效字节、非法字节、整数输入拒绝和精确采样结果。整数结果使用十进制字符串，避免 JSON 使用者丢失 64 位精度。有效字节须同时满足解码值相等与重新编码逐字节相等；负例必须被拒绝。巨量容量超限向量使用短小的声明长度；曲线 BLOB 另包含完整 65,536 字节正例和完整 65,537 字节负例，区分容量拒绝与截断拒绝。测试另覆盖恰好 1 MiB 帧与超出 1 字节的边界。

[test_wire_v7.py](test_wire_v7.py)、[test_messages_v7.py](test_messages_v7.py) 和 [test_canonical_v7.py](test_canonical_v7.py) 分别覆盖基础字节、消息/事务、规范状态摘要。所有拒绝路径使用显式检查，运行 `-O` 不会跳过这些校验。
