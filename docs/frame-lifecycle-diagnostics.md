# 帧生命周期因果诊断

`RWX_FRAME_LIFECYCLE_TRACE=/absolute/new/lifecycle.csv` 开启默认关闭的边界追踪。
它按实际 `generation/sequence` 记录 CPU 生产完成、SceneHost 选帧、成功的 Vulkan
画面提交及被接受的呈现；Vulkan 临时上传提交不作为画面提交。失败提交不创建成功边。
同一帧重复呈现保留各次提交身份，不能增加新画面数。

追踪预分配固定数组，热路径没有文件读写、CSV 格式化或逐条对象创建。
默认 262144 条记录，原始数组约 10 MiB；`RWX_FRAME_LIFECYCLE_EVENTS` 可在
16～1048576 之间调整。达到上限后保留原始前缀并累计丢弃数，不覆盖旧记录。
正常关闭或 JVM shutdown hook 才序列化、关闭文件；强制杀死进程可能没有完整记录。
文件使用 CREATE_NEW，必须每次给出新路径。

可同时使用已有 `RWX_INPUT_RESPONSE_TRACE=/absolute/new/input-response.csv`。
输入 CSV 保留原列和相机变化判断，但现在也只在关闭时序列化，移除了呈现回调的
同步 flush。`RWX_INPUT_TRACE_EVENTS` 默认 65536，范围同上；溢出明确写入标记。
等待呈现、等待相机变化及帧索引也有固定上限。

```sh
python3 desktop/tools/analyze_frame_lifecycle.py /absolute/new/lifecycle.csv \
  --input-response /absolute/new/input-response.csv \
  --output /absolute/new/lifecycle-analysis.json
```

无需输入分析时省略 `--input-response`。分析器只通过明确的帧身份、提交序号和
接受呈现时间连接事件，不借用相邻提交、不根据时间重叠补齐缺边。每条输入路径的
各段相加等于其 API 采样至对应画面的接受呈现延迟。应同时运行原有
`analyze_input_response.py`，验证拖动、方向键、滚轮都产生真实相机变化。

生命周期及新输入 CSV 必须有唯一末尾完成标记、计数一致且没有溢出；缺边、乱序、
重复使用 native submission、空输入捕获均不能通过。旧输入 CSV 没有完成标记时，
新分析将其明确标为 legacy/partial，不当作完整诊断；既有输入分析仍支持旧数据。
OpenGL 没有 native submission 边，报告中明确列出实际支持的边界。

这些是诊断边界，不是物理输入或显示器扫描测量。CPU 生产标记在 mailbox 发布之前；
生产至选帧包含交接和就绪等待，选帧至提交包含 CPU 工作及等待，提交至接受呈现也
不能当作 GPU 执行时间。诊断仍有内存及锁开销，必须与正式性能成绩分开，不能仅凭
一段边界时间给 CPU、GPU、驱动或系统负载定因。

`map_pan_live.py` 与 `map_pan_replay.py` 的 `--frame-lifecycle-trace` 会显式传入开关，
输出 `frame-lifecycle.csv`，并将运行标为 diagnosticOnly。现有 harness 会清理继承的
`RWX_*`，不能仅在外部设置环境变量后就假定追踪已经启用。

用户要求测试不影响正常工作。后续不得自动激活、置顶、恢复前台应用或抢占焦点，
也不得为了测量而反复把游戏切回前台。默认使用无窗口、低优先级、限并发的验证。
前台条件不成立的采样保留为无效记录，不能作为交互性能验收成绩。
