# API、环境与待验证限制

核查日期：2026-10-18。本轮只做只读核查，没有改动 SPW 安装、运行时、播放器数据库或设置。

## 本机环境

| 项目 | 本次核查结果 |
| --- | --- |
| 工作目录 | `E:\Code\spw status` |
| SPW 安装目录 | `E:\SteamLibrary\steamapps\common\Salt Player for Windows` |
| SPW 版本 | 1.19.0，来自 `app/.jpackage.xml` 与 `app/Salt Player for Windows.cfg` |
| SPW 自带 Java | `runtime/release` 标注 25.0.4.1，属于宿主运行环境 |
| 系统 Java / javac | 21.0.9，本次未安装 JDK 25 |
| 已有工程 | HTML/CSS/JS 预览和 Node 检查脚本；没有正式 Kotlin 插件工程或 `.spmod` |
| Git | 已使用，现有工作分支 `codex/listening-stats` |

安装包采用 `voxzen.aot` 等宿主文件，不是可直接当源码工程使用的目录。不能因为内置 Java 25 就认定已有完整 JDK 25 构建工具。

## 上游来源与版本

- [Workshop API 仓库](https://github.com/Moriafly/spw-workshop-api)，官网入口由仓库 README 指向 [开发入门](https://saltplayer.com/workshop/getting-started)。本次网页工具未能直接读取官网正文，因此接口结论以 API 源码为依据。
- 核查到上游 `main` 提交 `cbf20375b95b42d95c3fb16f1ceb53f4befc205c`。本地 `.references/spw-workshop-api` 是较早的 `863475b58b60dcad0272ed389733d9c2eb721f45`，不应当作随时最新。
- 本次读取上游 `example/gradle/libs.versions.toml`：Workshop API **0.1.0-dev22**、Kotlin **2.4.20**、Compose **1.12.0**。本地 API 工程依赖 Salt UI **2.9.0-alpha02**，Gradle Wrapper **9.8.0**，JVM target / toolchain **25**。
- `.references/salt-ui` 为另一份参考源码，标注 Salt UI **3.0.0-beta02**、Compose **1.12.1**，不能直接认为与 SPW 宿主二进制兼容。
- 不沿用旧聊天中未经本次核实的 dev23 等版本说法。正式开发开始时要重新核对，并锁定能够与宿主实际运行的依赖组合。
- `.references/` 被 Git 忽略，不随 GitHub 克隆提供；下一轮需重新拉取或使用本机已有目录。

## 已在源码中确认的接入点

| 需求 | 已确认接口 | 限制／开发动作 |
| --- | --- | --- |
| 播放状态 | `PlaybackExtensionPoint.onStateChanged`、`onIsPlayingChanged` | 需要将播放、暂停、缓冲、停止纳入同一状态机 |
| 播放位置 | `onPositionUpdated(position)`、`onSeekTo(position)` | 注释为每秒更新，不承诺无延迟、逐毫秒精度；拖动位移不能直接累计为收听 |
| 当前歌曲 | `WorkshopApi.playback.getCurrentMediaItem()` | 同步数据库查询，建议后台调用；要求 `LIBRARY_READ`；快照不自动更新 |
| 曲库查询 | `library.getTrackById`、`getAllTracks`、`getTracks(afterId, limit)` | 返回 `CompletionStage`，分页以 ID 而非排行次数排序；需自行管理统计排名 |
| 专辑封面 | `library.getCoverById(id)` | 仅承诺音频文件内嵌封面原始字节；无封面／不可读时可返回 null |
| 基本存储入口 | `manager.createConfigManager()`、`getConfig(fileName)` | 不等于完整统计数据库；数据规模、原子写入、迁移与备份需要自己设计 |
| 插件生命周期 | `SpwPlugin.start/stop/delete/update`、`PluginContext` | 停用、卸载、权限撤销及异常退出要正确清理与落盘 |
| 原生 UI Hook | `HookRegistrar.afterComposable/replaceComposable/appendContent`、`ComposableHook.Content` | 要求 `CLASS_TRANSFORM`，实验 API；依赖宿主真实类名／方法名／签名，版本或混淆改变会失效 |

源码位置为 `api/src/main/java/com/xuncorp/spw/workshop/api/`。上游示例主类与 `PlaybackExtensionExample` 展示 PF4J 扩展和生命周期，但不构成听歌统计实现。

## 必须明确的能力边界

1. **主页展示未验证。** 没有在已读的公开 `Ui` 接口中看到稳定的主页卡片／页面注册方法；公开 UI Hook 提供了可能性，必须找到 SPW 1.19.0 中实际可用且不会破坏布局的位置。不能用猜测的类名冒充已接入。
2. **历史统计不能保证补回。** 已读 Library API 暴露曲目与封面，没有完整历史听歌会话／时长记录查询。新装插件之前的热力图、连续天数与 50% 计次，不能由曲库元数据恢复。
3. **实时精度存在回调边界。** 每秒位置回调加播放／暂停／seek 事件，可作为记录基础；切歌通知、暂停尾段、休眠、缓冲、回调丢失以及当前曲目识别都需实测。建议使用单调时钟、串行状态处理和检查点，不能把位置差无条件当实际时长。
4. **歌曲 ID 并非跨设备永久标识。** `MediaItem.id` 文档明确不保证曲库删除重建或跨设备后保持不变。备份恢复和路径改变时的合并策略需确定，不按同名歌曲自动合并。
5. **封面只保证内嵌来源。** 不能承诺宿主里所有封面来源都会由 `getCoverById` 返回；默认图和页内懒加载／缓存必须保留。
6. **Salt UI 不能直接运行 HTML/CSS。** 要按参考重新实现 Compose 界面与图表。功能和视觉尽量一致；任何实质性偏差都先解释，不能先退化为另一个设计。未编译和运行前，不保证全部像素、字体和原生窗口行为完全相同。
7. **权限不是声明即授权。** 实际读取曲库或注册 UI Hook 之前要核对宿主授权，处理未授权／撤销；不要申请未使用的曲库写入或全局快捷键权限。

## 正式开发顺序建议

先建立可加载、可停止的最小 `.spmod` 并验证兼容依赖；同时确认 UI 入口和真实播放事件。再做独立的统计状态机和本地存储，接入原生界面、分页与封面，最后实现正式备份及完整宿主验证。依赖下载、首次构建和 SPW 运行测试尚未在本轮执行。

