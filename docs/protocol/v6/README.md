# RWXX v6 客户端协议资料

协议版本：6。日期：2026-11-07。

入口：[逐字节规范](../../authoritative-wire-v6.md)。

| 文件 | 用途 |
|---|---|
| wire_v6.py | Python 3 标准库参考编解码器、完整状态摘要与离线校验工具；只接受 v6 |
| vectors.json | 78 个帧向量：49 个合法帧、29 个拒绝例，覆盖全部 18 类消息；另含 11 个完整状态摘要示例 |
| test_wire_v6.py | 独立的字段、容量、版本、状态摘要及符号零检查 |
| terrain-catalog-v1.json | 0..234 地形 ID 的固定图像裁剪表，不包含图像文件 |
| verification.json | 附件摘要和离线格式检查结果 |

在本目录运行：

```sh
python3 -B wire_v6.py vectors.json
python3 -B -O wire_v6.py vectors.json
python3 -B -m unittest test_wire_v6.py
python3 -B -O -m unittest test_wire_v6.py
```

向量校验的预期输出：

```text
OK: 49 accepted vectors, 29 rejected vectors; all 18 messages, byte re-encoding, map keys and complete state digests match
```

边界测试在每种模式下运行 9 项。

`encode_frame(direction, message_name, fields)` 和 `decode_frame(direction, bytes)` 处理一帧完整消息。方向常量是 `CLIENT` 和 `SERVER`。工具只操作内存，不建立网络连接。联网客户端仍须完成 TLS 证书 pin、席位认证及 MapOffer→MapReady→MapInit/MapCached→Baseline 握手。

`map_content_key(map_definition)` 返回 MapInit 主体的 SHA-256 小写十六进制文本；MapOffer、MapReady 和 MapCached 在线上使用 32 个原始摘要字节。哈希不包含长度、magic、版本或消息类型。

`state_digest(channel, state)` 返回 16 个原始字节。WORLD（1）接收完整玩家视图，逐次读取全部静态地图与动态状态，按单位句柄升序编码；PANEL（2）接收完整面板，保留动作顺序。分别以 ASCII `RWXA-WORLD-6` 或 `RWXA-PANEL-6` 加一个 NUL 字节为域前缀，计算 SHA-256 并取前 16 字节。摘要包含当前状态序号，StateCheck 的检查序号不属于摘要输入。负零与正零的编码不同。

StateCheck 和 StateAck 都是 46 字节完整帧，检查序号在每条连接中跨频道严格递增。StateAck 必须携带客户端对自身完整状态重新计算的摘要；收到的摘要不能直接回显。缺少对应基线时请求 Resync，不发送伪造的确认。具体恢复和超时语义见规范。

返回字典的 i64 字段为十进制字符串，以保留超过 2⁵³ 的整数。比较或加一前须转成精确整数，不能按字符串排序或先转浮点数。可选补丁字段用 `None` 表示缺席。摘要的 JSON 表示为 16 个 0..255 整数；线上没有数组长度前缀。

向量的 `accepted=true` 只表示帧及字段格式合法。示例 token、句柄和地图不构成有效授权。负例包括故意声明超大长度的短输入，`declaredPayloadBytes` 不一定等于实际字节数减四。

此工具不实现连接状态机、定时校验、重同步、地图缓存淘汰、跨帧授权、动态世界重建或渲染；它只为完整状态计算摘要。精确基线、席位一致性及已披露目标等跨帧约束仍须按规范验证。离线检查通过不代表联网或授权验收。
