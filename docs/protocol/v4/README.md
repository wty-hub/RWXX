# RWXX v4 客户端资料

文档修订：r2。日期：2026-11-06。协议版本仍为 4。

入口：[逐字节规范](../../authoritative-wire-v4.md)。

| 文件 | 用途 |
|---|---|
| vectors.json | 24 个完整帧样例，含十六进制、字段值和绝对偏移；22 个合法帧、2 个 NUL 拒绝例 |
| wire_v4.py | 参考编解码器及向量校验脚本；只依赖 Python 3 标准库 |
| terrain-catalog-v1.json | 0..234 地形 ID 的固定图像裁剪表，不包含图像文件 |
| verification.json | 附件 SHA-256、大小和格式检查结果 |

在当前目录运行：

```sh
python3 wire_v4.py vectors.json
```

预期输出：

```text
OK: 22 accepted vectors, 2 rejected vectors; decode, encode and offsets match
```

Python 模块的入口：

```python
from wire_v4 import encode_frame, decode_frame, CLIENT

packet = encode_frame(CLIENT, "Hello", {
    "protocolVersion": 4,
    "playerName": "Player",
    "joinToken": "使用房间邀请中的 token",
})
name, fields = decode_frame(CLIENT, packet)
```

这里只构造内存帧，不会连接服务器。实际联网前按规范完成 TLS 和证书 pin；token 不能使用上面的说明文字或测试向量中的占位值。

该模块不提供完整客户端。TLS、输入队列、场景状态、渲染和服务端授权语义由使用者实现。测试向量中的 `accepted` 只表示字节格式合法。

返回字典中的 i64 是十进制字符串，便于与 JSON 向量对照。做序号比较或加一时，先转为 Python `int` 或其他语言的精确 64 位整数；不要按字符串排序。

校验记录只描述本资料包的附件完整性及格式检查，不代表联网认证、服务端授权或图像验收。
