# API、环境与待验证限制

> 本文前半部分保留最初只读核查的历史结论，不能作为当前开发进度。当前已有 0.1.1 源码与构建产物；JDK/Kotlin 缓存在 build/tools。最新数据实现、验证范围与剩余限制以 [正式实现说明](NATIVE-IMPLEMENTATION.md) 和 [交接](HANDOFF.md) 为准。0.1.1 仍未在真实播放中完成验收。

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

## 本轮实测补充（2026-10-18）

以下结论来自实际构建与 SPW 1.19.0 运行测试，详见 [正式模组实现说明](NATIVE-IMPLEMENTATION.md)。

1. **宿主包。** `app/ffmpeg-x64.dll` 实际是约 110 MB 的 ZIP/fat jar，包含全部宿主类、Compose、Material3、PF4J、API 与 sqlite native；`MainKt` 只是入口。直接以它为编译类路径可以锁定 1.19.0 的实际 JVM 签名。
2. **Host 数据库已有听歌历史。** `%APPDATA%\Salt Player for Windows\spw.db`（Room，schema 34）含 `Listen(id, trackId, audioHash, year, month, day, startTime, duration)` 与 `Track(...)`；测试库有 57–58 条 `Listen`。`Track.playCount` 全为 0，因此排行次数必须由每次 `Listen` 的 `duration` 与 `Track.duration` 比较（≥50%）得出。原先“历史无法补回”的判断只适用于 Library API；宿主内部 `Listen` 表在保留期内可以提供真实历史，但该表不是公开稳定 API，插件需容错并明确说明。
3. **Hook 入口可用但绑定版本。** 1.19.0 中 `AppNavigationKt.AppNavigation` 的唯一调用点在混淆类 `androidx.compose.ui.ۄ`；`afterComposable` 注册成功并实际组合。宿主自附加 agent 需要启动参数 `-Djdk.attach.allowAttachSelf=true`（官方启动器已设置），缺失时宿主会停止插件。宿主升级后类名可能变化，必须保留配置页入口。
4. **Salt UI 被混淆。** 宿主内 `com.moriafly.salt.ui` 的公开组件类名/顶层函数大量不可从 Kotlin 元数据解析（例如 `Modifier.background`、`Canvas`、`LazyColumn`、`clickable`、`TooltipArea` 均无法直接引用）。正式实现改用 Material3 + 自定义 `DrawModifier`/`JScrollPane`，避免依赖被混淆的 Salt UI 符号。
5. **插件安装/授权。** PF4J 3.14.1；插件目录为 `workshop/plugins/<目录>/classes`，`enabled.txt` 按插件 ID 启用，`permissions.json` 保存 reviewed/granted。权限 ID 为 `library-read`、`class-transform`、`key-bindings`。
6. **SQLite 访问。** 插件自带 `sqlite-jdbc` 只读打开宿主 DB 可行；不要写入宿主数据库。需要保留 DB 缺失、schema 变化、WAL 读取失败时的错误/空状态。
7. **实时性。** 当前版本以宿主已写入的 `Listen` 行为准；`onPositionUpdated` 等扩展点可作为后续实时叠加状态机的来源，但暂停/seek/跨日拆分仍需产品级口径确认。


