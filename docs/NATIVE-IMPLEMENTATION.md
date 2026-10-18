# 正式模组实现说明

更新时间：2026-10-18。本文件记录第一版可安装 `.spmod` 的实现方式、已验证范围与剩余限制。前端基准仍是 `preview/` 与 `docs/REQUIREMENTS.md`。

![听歌概览实际运行截图](screenshots/overview-light.png)

截图来自 SPW 1.19.0 独立测试配置，数据为测试库中的真实 `Listen` 记录。

## 当前状态

已建立独立正式模组目录 `plugin/`，可构建、安装、启动，并在本机 SPW 1.19.0 中读取真实听歌记录、显示概览与排行。产物：

```text
build/libs/plugin-com.jethuit.spw.listenstats-0.1.0.spmod
```

本版不是最终完成版：宿主入口以浮层入口 + 独立页面窗口实现；播放事件会实时累计并每 5 秒刷新页面，宿主写入 `Listen` 后按开始时间去重；导入采用保守合并策略。具体差异见“限制与未完成”。

## 构建

宿主安装目录以 `E:\SteamLibrary\steamapps\common\Salt Player for Windows` 为例。

```powershell
python tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

`prepare_toolchain.py` 只在 `build/tools/` 下载 JDK 25、Kotlin 2.4.20 编译器、sqlite-jdbc，并复制宿主 `app/ffmpeg-x64.dll` 作为 `spw-host.jar`。这些文件都不提交 Git。

`build_plugin.py`：

1. 用 JDK 25 运行 Kotlin 2.4.20 编译器；
2. 以宿主自身 fat jar 为编译类路径，避免 Kotlin / Compose / Workshop API 版本错配；
3. 启用 Compose compiler plugin，编译 `plugin/src/main/kotlin`；
4. 复制资源、写入 `META-INF/MANIFEST.MF` 与 `META-INF/extensions.idx`；
5. 把 `sqlite-jdbc.jar` 放入 `lib/`，生成 `.spmod`；
6. 同时生成可直接放入 PF4J 插件目录的 `build/install/spw-listen-stats/`。

没有使用 Gradle：宿主 1.19.0 的 Compose / Salt UI / 部分 androidx 类经过混淆，用常规 Maven 依赖会把错误版本编进插件。直接以宿主 jar 编译可以锁定实际运行签名。构建脚本的局限是依赖本机安装目录，因此把 SPW 路径做成参数。

## 安装与权限

在 SPW 创意工坊“本地导入”选择 `.spmod`。插件声明两个权限：

- `library-read`：读取曲库元数据与内嵌封面；
- `class-transform`：注册主界面 UI Hook。

必须授权 `class-transform` 才会出现主界面右下角“听歌统计”入口。未授权或 Hook 失败时，可从插件配置页“打开听歌统计”打开同一个页面。

测试时使用独立 `APPDATA` 副本，未修改用户真实插件目录；`E:\Code\spw status\build\test-appdata` 下保留了测试配置与数据库副本，均被 Git 忽略。

## 架构

```text
plugin/src/main/kotlin/com/jethuit/spw/listenstats/
  MainPlugin.kt             插件生命周期、UI Hook、配置页动作
  PlaybackExtension.kt      PF4J 播放扩展点，转发到 PlaybackTracker
  PluginLog.kt              打包后无控制台时的文件日志
  PluginState.kt            插件路径与版本
  Prefs.kt                  主题、图表类型、粒度等本机偏好
  data/
    StatsModels.kt          统计模型、日期范围、排行项
    HostDatabase.kt         只读读取宿主 spw.db 的 Listen/Track
    StatsStore.kt           定位数据库并合并实时会话、备份
    StatsCalculations.kt    汇总、连续天数、热力图周、图表分桶
    PlaybackTracker.kt      播放事件状态机：位置差、暂停、seek、切歌
    LiveStore.kt            实时会话持久化与去重合并
    TimeSplitter.kt         单次收听跨小时/跨日拆分
    CoverStore.kt           通过 Workshop API 读取内嵌封面
    BackupService.kt        版本化备份、校验、保守合并
  ui/
    StatsWindow.kt          无边框页面窗口、页面导航、滚动、偏好接线
    StatsOverlay.kt         主界面右下角入口浮层
    OverviewPage.kt         累计统计、年度足迹、时长分布、听歌小结
    RankingPage.kt          50 首分页、搜索、真实封面
    YearHeatmap.kt          日/周热力图与悬停/点击
    TrendChart.kt           柱状/折线/曲线、平均线、悬停
    Theme.kt                浅色/深色/跟随系统主题
    Components.kt           按钮、分段控件、下拉、提示框
    Drawing.kt              绘制修饰符、文字、图标
    Icons.kt                Material Symbols 路径数据
```

## 数据口径

- 数据源：宿主 `%APPDATA%\Salt Player for Windows\spw.db` 的 `Listen` 与 `Track` 表，只读打开。
- 实时会话：`PlaybackExtension` 的 `onIsPlayingChanged`、`onPositionUpdated`、`onSeekTo`、`onStateChanged` 驱动 `PlaybackTracker`。只在播放中用相邻位置差累计（0–3 秒）；暂停、seek、循环回跳不计入。会话每 10 秒、暂停、切歌、结束和插件停用时落盘到 `workshop/data/<id>/live-sessions.json`。
- 去重：若宿主 `Listen` 存在同一 `trackId` 且开始时间相差 ≤10 秒的记录，以宿主记录为准并跳过实时会话；否则把实时会话加入统计。因此播放事件先提供实时数据，宿主落库后自动切换到权威记录。
- 每日/每小时：用 `Listen.startTime` 与 `duration`（实时会话用会话开始时间与累计时长）按本地时区拆分到小时，再汇总到日。小时边界、跨日边界分别累计。
- 每首播放次数：仅当该条 `Listen.duration` 或实时会话累计时长 `>= Track.duration × 50%` 时计一次。`Track.playCount` 在测试库中全部为 0，因此不使用。
- 概览统计为全部记录；排行不受年份/日期筛选影响。
- 热力图日分档：0、<30 分钟、<75 分钟、<135 分钟、≥135 分钟；周视图以本年度单周峰值为基准分四档并画最多 7 个自下而上的方格。
- 时长分布按日期范围分桶；单日固定 24 小时，2–13 天按日，14 天以上可选按日/按周；周分组从范围起点每 7 天一组。
- 平均线只用于按日/按周，单日按小时不显示；平均值包含零收听区间。
- 每日平均收听按有记录的日期计算；“最常听歌时段”按全部小时累计。

## 备份格式

`BackupService` 导出版本 1 JSON：

```json
{
  "format": "spw-listen-stats-backup",
  "version": 1,
  "exportedAt": "2026-10-18T10:00:00Z",
  "timeZone": "Asia/Shanghai",
  "records": [{"date": "2026-10-18", "ms": 123, "hours": [24 个数值]}],
  "tracks": [{"id": "...", "title": "...", "artist": "...", "album": "...", "durationMs": 0, "count": 1}],
  "checksum": "sha256"
}
```

导入会校验格式、版本、日期、小时和校验和；失败不写入。成功写入插件数据目录的 `imported.json`，与宿主记录按“日期/小时取较大值、每首次数取较大值”合并，避免重复计数，也意味着导入不能降低已有统计。该策略是保守方案，不是“完整替换”备份。

## 已验证

- 宿主版本 1.19.0，独立 `APPDATA` 副本，真实 `spw.db`（57–58 条 `Listen`）。
- 插件由 PF4J 加载，`Plugin-Permissions` 解析正确；`enabled.txt`/`permissions.json` 测试配置生效。
- 自附加 agent 成功：`-Djdk.attach.allowAttachSelf=true` 时 Hook 注册并实际组合 `StatsOverlay`；未设置时宿主停止插件。
- 主界面右下角“听歌统计”入口可见；点击后打开 1541×890 无边框页面，尺寸与宿主主窗口一致。
- 概览页：累计 4 小时 4 分、2 个听歌日、最长/当前连续 2 天；真实热力图、折线图、平均线、听歌小结（3 小时 43 分 / 2 小时 2 分 / 15:00–16:00）。
- 排行页：53 首达到 50% 计次，真实封面加载，前 50 首分页、排名/搜索/次数布局。
- 浅色与深色主题均渲染；页面滚动通过 JScrollPane 生效，概览高度 1575px、排行 5187px。
- 宿主 `Listen` 表可被 sqlite-jdbc 只读读取；插件包内含 native SQLite。
- 播放扩展点实际收到 `Idle / Buffering / Ready`、`isPlaying=false` 并解析出当前曲目（含标题与 294 秒时长）。
- 用动态代理模拟宿主 API 的状态机测试：播放中 60 次位置回调累计 60 秒、50% 计次、插件关闭时 finalize、`live-sessions.json` 落盘和重新加载均通过；同一开始时间的宿主 `Listen` 会抑制实时会话，避免重复计数。

## 限制与未完成

1. **主页入口是浮层 + 独立窗口，不是宿主原生导航页。** 宿主未提供公开的插件页面注册 API；本版用 `afterComposable` 在 `AppNavigation` 后追加右下角入口，页面为宿主主窗口上的无边框 `JDialog`。视觉接近预览，但不是宿主 `ScreenRoute` 的一页。
2. **Hook 目标与版本绑定。** 1.19.0 的目标调用类是 `androidx.compose.ui.ۄ`（混淆名），已硬编码；宿主升级后可能失效。失败时配置页入口仍可用。
3. **实时链路未在真实播放中完整跑通。** 位置回调、状态回调、当前曲目解析已在宿主中确认；状态机与持久化用代理 API 测试通过。但测试环境的宿主队列虽有当前曲目，`play()` 没有产生 `isPlaying=true`；随后发现沙箱拒绝 `java.util.prefs` 的注册表访问并弹出“崩溃报告”模态框，进一步阻断播放/UI。不要把这个环境问题当成插件状态机的结论。真实暂停、seek、切歌、跨日与宿主落库去重仍需在正常 SPW 会话中逐项确认。页面每 5 秒刷新，实时数据不是逐帧刷新。
4. **数据库是宿主内部格式。** 表缺失/升级时显示错误与空状态，不做猜测或伪造历史。插件不写入宿主数据库。
5. **导入是保守合并**，不删除/回滚现有统计；导出包含已合并的统计。
6. **封面仅内嵌来源。** 外部图片、读取失败、无封面显示占位图；没有做本地缓存持久化（仅内存）。
7. **窗口行为。** 无原生标题栏，不能拖动/缩放；Esc 或右上角 × 关闭。搜索框使用 Material3 默认高度，比预览略高。
8. **未做宿主级自动化测试。** 鼠标点击/滚轮/悬停通过 Compose 指针事件与 Swing 滚动实现，已验证程序化滚动与窗口渲染；最终鼠标交互需用户在真实 SPW 中确认。
9. **未打包 Gradle 工程。** 构建依赖本机 SPW jar；后续如需发布源码，可补 Gradle 包装脚本并锁定宿主 jar。

## 后续优先级

1. 在真实用户配置中安装、授权并逐项点击验证：真实播放/暂停/seek/切歌、主题循环、日/周热力图、悬停提示、点击日期联动、图表类型、范围/粒度下拉、搜索、分页、导入/导出。
2. 根据真实播放验证结果调整位置差阈值、宿主落库去重容差和会话 finalize 时机；补暂停尾段、休眠、跨日与循环播放测试。
3. 如果宿主提供稳定页面入口，把 `JDialog` 页面迁移到宿主导航；同时维护 1.19.0 之外的 Hook 候选。
4. 备份导入选择“合并/替换”策略；补导出文件大小与异常中断测试。
5. 按真实用户反馈微调字号、间距、热力图尺寸与搜索框高度。
