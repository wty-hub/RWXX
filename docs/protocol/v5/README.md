# RWXX v5 客户端协议资料

协议版本：5。日期：2026-11-06。

入口：[逐字节规范](../../authoritative-wire-v5.md)。

| 文件 | 用途 |
|---|---|
| wire_v5.py | Python 3 标准库参考编解码器及离线向量校验器；只接受 v5 |
| vectors.json | 59 个向量：45 个合法帧、14 个拒绝例，覆盖全部 16 类消息及字段补丁、边界值 |
| test_wire_v5.py | 可独立运行的字段、容量、目标、摘要及版本边界检查 |
| terrain-catalog-v1.json | 0..234 地形 ID 的固定图像裁剪表，不包含图像文件 |
| verification.json | 附件摘要和离线格式检查结果 |

在本目录运行：

```sh
python3 -B wire_v5.py vectors.json
python3 -B -O wire_v5.py vectors.json
python3 -B -m unittest test_wire_v5.py
python3 -B -O -m unittest test_wire_v5.py
```

两次向量校验的预期输出：

```text
OK: 45 accepted vectors, 14 rejected vectors; all 16 messages, byte re-encoding and map keys match
```

接口示例：

```python
from wire_v5 import CLIENT, SERVER, encode_frame, decode_frame, map_content_key

packet = encode_frame(CLIENT, "Hello", {
    "playerName": "Player",
    "joinToken": "使用房间邀请中的 token",
})
name, fields = decode_frame(CLIENT, packet)
```

这段代码只构造内存帧，不连接服务器。联网客户端还需完成 TLS 证书 pin、席位认证及 MapOffer→MapReady→MapInit/MapCached→Baseline 握手。Hello 不携带地图缓存列表；缓存命中只确认服务器提出的一张地图。

`map_content_key(map_definition)` 返回 MapInit 主体的 SHA-256 小写十六进制文本；MapOffer、MapReady 和 MapCached 在线上使用 32 个原始摘要字节。哈希不包含外层长度、magic、版本或消息类型。

返回字典的 i64 字段为十进制字符串，以保留超过 2⁵³ 的整数。进行比较或加一前须转成精确整数，不能按字符串排序，也不能先转浮点数。可选补丁字段用 `None` 表示缺席；负零须保留到重新编码。

向量的 `accepted=true` 只表示帧及字段格式合法。示例 token、句柄和地图不是有效授权。负例包括被拒绝的完整帧和故意声明超大长度的短输入；`declaredPayloadBytes` 是线上前缀值，不保证等于实际文件中的帧字节数减四。

此工具不实现连接状态机、重同步、地图缓存淘汰、跨帧授权、动态世界重建或渲染。地图边界、精确基线、席位一致性及已披露目标等跨帧约束仍须按规范验证。离线检查结果不代表联网或授权验收。
