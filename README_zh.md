<div align="center">

![RWXX](logo.svg)

# RWXX

**基于 RWX，专注底层性能与稳定性优化。**

[![License](https://img.shields.io/github/license/wty-hub/RWXX?style=flat-square&color=blue)](./LICENSE)
[![Downloads](https://img.shields.io/github/downloads/wty-hub/RWXX/total?style=flat-square&color=e74c3c)](https://github.com/wty-hub/RWXX/releases)

简体中文 · [English](README.md) · [上游 RWX](https://github.com/yomi2539/RWX) · [下载](https://github.com/wty-hub/RWXX/releases)

</div>

RWXX 是基于 [RWX（Rusted Warfare eXtension）](https://github.com/yomi2539/RWX) 的优化分支，
工作重点是渲染链路、帧调度、内存与资源管理，以及跨平台运行的稳定性。
目标是在延续 RWX 现有游戏体验的基础上，减少底层开销，改善大规模对局中的操作响应与帧时间稳定性。

RWX 的 P2P 联机、区域控制、地图联通和 JVM 模组能力构成本项目的功能基础。
当前优化工作主要集中在 Windows、Linux 和 macOS 桌面端；项目同时保留 Android 构建支持。

## 优化重点

| 方向 | 当前实现 |
| --- | --- |
| 桌面渲染 | 游戏画面统一使用 Kool 渲染，移除旧 Slick 游戏画布及逐帧回读路径；Windows 默认 Vulkan，macOS 通过 MoltenVK 使用 Metal，Linux 默认 OpenGL。 |
| 引擎与呈现 | 游戏循环运行在独立线程，渲染端消费已经完成的画面；输入按顺序进入引擎，仿真计时独立于 GPU 呈现节奏。 |
| 地图绘制 | 地图层内复用不变的画笔状态，结合地图格缓存与 GPU 离屏绘制，减少重复准备和像素处理。 |
| 文字与纹理 | 复用文字网格、纹理元数据和不可变像素快照，减少重复创建几何数据及复制像素。 |
| 资源管理 | 复用像素与上传缓冲区，按 GPU 实际完成状态回收在途资源，控制闲置缓存与待处理画面的积压。 |
| 帧调度 | 协调引擎画面生产与呈现上限，默认采用休眠等待；失焦、隐藏或最小化时限制呈现频率，减少无效绘制和忙等。 |

这些改动以保持确定性仿真、游戏规则及既有网络、存档和回放行为为约束。
回放校验、像素对照、联机验证与性能测试分别记录验证范围，兼容性结论以对应记录为准。

## 当前状态

- **地图层预备绘制默认开启**：每层绘制只准备一次不变画笔状态，可用 `RWX_PREPARED_MAP_TILES=0` 关闭。
- **桌面自动档上限为 300 FPS**：保存的手动上限继续生效，VSync、省电模式和后台状态仍会进一步限制呈现频率；后台上限为 30 FPS。上限不代表实际帧率。
- **持续优化与验证中**：快速拖动、连续缩放、物理输入响应、长时间对局流畅性，以及历史 DirectBuffer 内存耗尽问题尚未完成整体验收。其他实验优化有各自独立的开关和默认状态。

性能结果与具体版本、硬件、地图、单位数量和设置有关。局部微基准的收益不能直接换算为整局帧率提升，
早期版本的数据也不代表当前默认配置。桌面端的优化结果不直接外推到 Android。
最新默认值与验收范围见 [当前优化状态](docs/NEXT-SESSION.md)。

## 实验联机协议

当前开发版本为 `2.2.0`。新模式的服务器、客户端 CLI 和大厅创建/加入入口使用候选协议 `7`；原版联机协议 `176` 作为独立模式保留。新模式由服务器运行完整仿真，只向各客户端发送其获准知道的时间线。

64 个客户端、128 个原生单位的本机 TLS 检查已通过。65,536 单位的实时性能目标尚未达标，容量、GUI 与正式发布整体验收仍未完成。详见 [协议概览](docs/authoritative-network-protocol.md)、[v7 逐字节规范](docs/authoritative-wire-v7.md)和[参考工具与向量](docs/protocol/v7/README.md)。

## 下载与运行

在 [Releases](https://github.com/wty-hub/RWXX/releases) 查看已发布版本，具体文件以对应版本提供的产物为准。
桌面端建议选择与系统和架构匹配的 `desktop.zip`，其中已包含 Java 运行时。

| 产物 | 运行要求 |
| --- | --- |
| `RWXX-<version>-<platform>-desktop.zip` | 自带 Java；完整解压后启动应用。 |
| `RWXX-<version>-<platform>.jar` | 对应桌面平台的 JAR，需自行安装 Java 25。 |
| `RWXX-<version>-all.jar` | 包含所有支持桌面平台原生库的通用 JAR，需自行安装 Java 25。 |

`<platform>` 为 `windows-x64`、`linux-x64`、`linux-arm64`、`macos-x64` 或 `macos-arm64`。
ZIP 完整解压后，Windows 启动 `RWXX/RWXX.exe`，Linux 启动 `RWXX/bin/RWXX`，macOS 打开 `RWXX.app`。
Android 安装包需要有效签名；自行构建的未签名 Release APK 不能直接安装。

macOS 从 1.4.4 起将个人设置保存在 `~/Library/Application Support/rwx/preferences.toml`，
替换或移动 `RWXX.app` 后仍会保留音量、画面、操作、快捷键、昵称和主题等设置。
首次启动时，如果用户目录还没有配置，会复制当前应用目录内的旧 `preferences.toml`，保留原文件；已有配置不会被覆盖。
从 1.4.3 及更早版本首次升级时，请先退出游戏，将旧应用内的 `RWXX.app/preferences.toml`
备份到上述用户目录，再替换应用；已经随旧应用删除的配置无法自动恢复。
手动指定 `-Dlaunch.dir=...` 时，配置继续保存在指定目录，便于便携运行和隔离验证。

## 从源码运行与构建

构建需要 **JDK 25**。Android 构建还需要 Android SDK 36，并通过 `ANDROID_HOME` 或 `local.properties` 配置 SDK 路径。
在仓库根目录执行以下命令；Windows 使用 `.\gradlew.bat` 替代 `./gradlew`。

```bash
# 从源码运行桌面版
./gradlew :desktop:run

# 当前平台 JAR 与自带 Java 的桌面应用 ZIP
./gradlew :desktop:platformFatJar :desktop:packageDesktopDistribution

# 包含所有支持桌面平台原生库的通用 JAR
./gradlew :desktop:multiPlatformFatJar

# Android Release APK
./gradlew :android:assembleRelease

# 核心与桌面单元测试
./gradlew :core:test :desktop:test
```

桌面 JAR 输出到 `desktop/build/libs/`，应用 ZIP 输出到 `desktop/build/distributions/`，
Android APK 输出到 `android/build/outputs/apk/`。
平台 JAR 可通过 `-PtargetPlatform=<platform>` 交叉构建；自带运行时的应用 ZIP 必须在对应系统和架构上打包。

Android 未配置完整发行签名时，`assembleRelease` 生成未签名 APK；
安装或发布前须配置发行签名。CI 的已签名 APK 以 `RWXX-<version>-` 为文件名前缀。
签名与发行流程见 [CI/CD 配置](.github/workflows/ci.yml)。

## 技术文档

- [当前优化与验收状态](docs/NEXT-SESSION.md)：最新默认值、已验证范围和待解决问题。
- [桌面性能优化说明](docs/desktop-performance.md)：渲染路径、帧调度和性能工作的演进。
- [引擎与 Vulkan 渲染分离](docs/vulkan-engine-separation.md)：线程、输入、画面交接及资源生命周期。
- [Windows 拖动与缩放优化记录](docs/windows-map-pan-optimization.md)：瓶颈排查、实验方案和对照结果。
- [性能测试工具](desktop/tools/README.md)：复现方法、采样指标与测试边界。
- [RWX 使用教程](https://rwx-docs.netlify.app/zh/tutorial/getting-started)：上游项目的基础使用说明。

部分技术文档保留了历史阶段和实验结果，默认行为以当前源码及最新状态记录为准。
已有的 `RWX_*` 环境变量、包名和存储目录继续保留，以兼容既有配置和数据。
Windows / Linux 可用 `RWX_KOOL_BACKEND=opengl` 显式选择 OpenGL；macOS 使用 Vulkan / MoltenVK。

## 反馈与贡献

欢迎通过 [Issues](https://github.com/wty-hub/RWXX/issues) 反馈问题，或提交 Pull Request 参与底层优化。
报告性能问题时，请附上版本、系统与硬件、地图或回放、复现步骤、帧率与 VSync 设置，以及相关日志。
性能改动请提供可复现的前后对照，并说明画面、输入、回放或联机一致性的验证范围。

## 致谢与免责声明

感谢 [RWX](https://github.com/yomi2539/RWX) 及 Rusted Warfare 的作者和贡献者。
项目许可证见 [LICENSE](LICENSE)。

本项目为 Rusted Warfare 的非官方项目，专注于基于 RWX 的底层优化。
本项目所使用的相关资产均归原作者所有。仅为学习研究目的，禁止商业用途。
