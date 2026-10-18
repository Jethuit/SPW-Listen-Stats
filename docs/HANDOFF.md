# 新聊天交接

更新时间：2026-10-18。用户要求先暂停后续开发，由另一代理接手。当前工作区、Git 状态和构建产物是唯一事实来源；不要根据旧聊天里的“预览阶段”描述判断进度。

## 一句话状态

第一版可安装 `.spmod` 已完成并在 SPW 1.19.0 中加载、渲染真实数据；实时播放状态机、持久化和宿主记录去重已实现，并通过 JVM 模拟测试。**尚未在真实音频播放中验证暂停/seek/切歌/跨日的完整链路，也尚未逐项人工点击验证所有前端交互。** 用户已明确要求暂停，下一步是用户在本机真实播放中验收，或继续做宿主级交互验证。

## 仓库与分支

- 工作目录：`E:\Code\spw status`
- GitHub：<https://github.com/Jethuit/SPW-Listen-Stats>
- 当前分支：`codex/listening-stats`，已与 `origin/codex/listening-stats` 同步，工作区干净。
- 最近提交：
  - `721b2a5` fix: stop late tracker resolutions during plugin shutdown
  - `c46c8ba` feat: track live playback sessions and merge them safely
  - `154540f` docs: add the first running overview screenshot
  - `c403d42` style: fill the yearly heatmap width and mark selected weeks
  - `c028112` feat: implement first installable listening stats mod
- 构建产物（被 Git 忽略）：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.0.spmod`，约 14.6 MB。
- 预览基准提交仍是 `4e25ed1`；`preview/` 不应被重新设计。

## 先读这些

1. [AGENTS.md](../AGENTS.md)
2. [正式实现说明](NATIVE-IMPLEMENTATION.md)：架构、数据口径、已验证证据、限制。
3. [需求](REQUIREMENTS.md) 与 [API/环境核查](API-FEASIBILITY.md)。
4. `preview/index.html`、`preview/index.html#ranking`：已确认的视觉基准。
5. 本文件后面的“当前未完成”和“给接手代理的建议”。

## 当前文件结构

```text
plugin/
  README.md
  src/main/kotlin/com/jethuit/spw/listenstats/
    MainPlugin.kt            生命周期、AppNavigation UI Hook、配置页动作
    PlaybackExtension.kt     PF4J 播放扩展点，转发到 PlaybackTracker
    PluginLog.kt             文件日志
    PluginState.kt           插件路径/ID/SPW 版本
    Prefs.kt                 主题、图表类型、粒度偏好
    data/
      StatsModels.kt         数据模型
      HostDatabase.kt        只读 spw.db Listen/Track
      StatsStore.kt          组合宿主、实时会话、备份
      StatsCalculations.kt   汇总、连续天数、热力图周、图表分桶
      PlaybackTracker.kt     播放事件状态机
      LiveStore.kt           实时会话 JSON、宿主去重合并
      TimeSplitter.kt        跨小时/跨日拆分
      CoverStore.kt          Workshop API 内嵌封面
      BackupService.kt       v1 备份、校验、保守合并
    ui/
      StatsWindow.kt         无边框 JDialog、JScrollPane、页面导航、偏好接线
      StatsOverlay.kt        主界面右下角入口浮层
      OverviewPage.kt        累计统计、热力图、时长分布、听歌小结
      RankingPage.kt         50 首分页、搜索、真实封面
      YearHeatmap.kt         日/周热力图
      TrendChart.kt          柱状/折线/曲线、平均线、悬停
      Theme.kt               浅/深/跟随系统
      Components.kt、Drawing.kt、Icons.kt、Shapes.kt
  src/main/resources/
    preference_config.json   配置页按钮
    META-INF/extensions.idx  PF4J 扩展索引
tools/
  prepare_toolchain.py       下载 JDK 25、Kotlin 2.4.20、sqlite-jdbc，复制宿主 jar
  build_plugin.py            编译、打包 .spmod / install 目录
tests/
  spmod-structure.py         产物结构、Manifest、权限检查
  chart-geometry.test.cjs    预览几何回归
  heatmap-layout.test.cjs    预览热力图布局
  song-pagination.test.cjs   预览排行分页
docs/
  NATIVE-IMPLEMENTATION.md   正式实现与限制（最重要）
  screenshots/overview-light.png
```

`build/`、`.references/`、`.gradle/`、`.tools/`、`node_modules/`、`*.spmod`、`__pycache__/` 均在 `.gitignore` 中，不要提交。测试用 `build/test-appdata/` 是独立 `APPDATA` 副本，也不提交。

## 构建与检查

宿主目录示例：`E:\SteamLibrary\steamapps\common\Salt Player for Windows`。

```powershell
python tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tests/spmod-structure.py
```

产物：

```text
build/libs/plugin-com.jethuit.spw.listenstats-0.1.0.spmod
build/install/spw-listen-stats/          # PF4J 插件目录形式
```

预览检查（Node 无依赖）：

```powershell
node --check preview/app.js
node tests/chart-geometry.test.cjs
node tests/heatmap-layout.test.cjs
node tests/song-pagination.test.cjs
```

构建要点：

- 不使用 Gradle。宿主 `app/ffmpeg-x64.dll` 实际是约 110 MB 的 fat jar，包含宿主、Compose、Material3、PF4J、Workshop API 和 sqlite native；以它为编译类路径可以锁定 1.19.0 的真实 JVM 签名。
- 编译器是 JDK 25 + Kotlin 2.4.20；Kotlin 标准库放在宿主 jar 之前，避免 against 宿主 stdlib 时的 inline 编译器崩溃。
- `sqlite-jdbc.jar` 会被放进 `.spmod` 的 `lib/`；不要改为 compileOnly。
- 宿主 UI/Salt UI 部分类被混淆，`Modifier.background`、`Canvas`、`LazyColumn`、`clickable`、`TooltipArea` 等公开 API 在编译时不可解析；实现使用 Material3 + 自定义 `DrawModifier` + `JScrollPane`。不要为了“用 Salt UI”把这些符号硬接回去。

## 安装与权限

在 SPW 创意工坊“本地导入”选择 `.spmod`，启用并授权：

- `library-read`：曲库元数据、封面、当前曲目查询；
- `class-transform`：注册主界面 UI Hook。

授权后主界面右下角出现“听歌统计”入口。未授权/ Hook 失败时从插件配置页“打开听歌统计”打开同一页面。

调试日志：

```text
%APPDATA%\Salt Player for Windows\workshop\data\com.jethuit.spw.listenstats\status.log
```

实时会话 JSON 同目录 `live-sessions.json`。

## 数据与实时链路

- 宿主数据库：`%APPDATA%\Salt Player for Windows\spw.db`，Room schema 34；只读 `Listen` / `Track`。
- `Listen` 行的 `duration` 与 `Track.duration` 比较，≥50% 计一次；测试库 `Track.playCount` 全为 0，不能用。
- 实时链路：`PlaybackExtension` → `PlaybackTracker`。
  - 播放中相邻 `position` 差在 0–3 秒内才累计；暂停、seek、循环回跳不计。
  - 会话每 10 秒、暂停、切歌、Ended/Idle、插件停用时写 `live-sessions.json`。
  - 会话开始时间按“当前墙钟 - 已播放位置”估算，用于和宿主 `Listen.startTime` 对齐。
- 合并：`StatsStore.load()` = 宿主 DB + 实时会话 + `imported.json` 备份。
  - 同 `trackId` 且开始时间差 ≤10 秒的宿主行会抑制实时会话，避免重复计数。
  - 未匹配的实时会话加入每日/每小时与每首次数。
  - 页面每 5 秒重新加载；不是逐帧刷新。
- 备份：`BackupService` 版本 1 JSON，带 SHA-256；导入校验后按日期/小时/次数取较大值合并，不是替换。

## 已验证（有证据）

- SPW 1.19.0，独立 `APPDATA` 副本；PF4J 加载、Manifest、`extensions.idx`、`lib/sqlite-jdbc.jar` 均正常。
- `-Djdk.attach.allowAttachSelf=true` 时自附加 agent 成功，`afterComposable` 注册并组合 `StatsOverlay`；未设置时宿主会停止插件。
- 主界面右下角入口可见；点击打开与宿主主窗口同尺寸（1541×890）的无边框页面。
- 真实数据：累计 4 小时 4 分、2 个听歌日、最长/当前连续 2 天；排行 53 首达到 50% 计次；真实内嵌封面；浅/深主题；JScrollPane 滚动；概览高度 1575 px、排行 5187 px。
- 宿主回调实际收到 `Idle / Buffering / Ready`、`isPlaying=false`、当前曲目解析（标题/294 秒）。
- 用动态代理模拟宿主 API 的状态机测试：60 次位置回调累计 60 秒、50% 计次、关闭 finalize、`live-sessions.json` save/load、宿主行去重全部通过。测试代码只在 `build/tests/`（未提交），当时通过 `kotlinc` + JDK 25 + `build/plugin-classes` + 宿主 jar 运行；如果需要可让新代理重写为仓库内测试。

## 当前未完成 / 阻塞

1. **真实音频播放的端到端验证未完成。** 测试环境的宿主队列可以恢复当前曲目（`playNormalQueue.queue.size = 653`），但调用 `WorkshopApi.playback.play()` 没有产生 `isPlaying=true`；随后发现沙箱拒绝了 `java.util.prefs` 的注册表访问，弹出“崩溃报告”模态框，进一步阻断 UI。不要把这个当成插件播放状态机的结论。
2. **不要在未获用户批准时用 `danger-full-access` 启动 SPW。** 本轮尝试过一次以获得真实注册表访问，用户拒绝了该提权；用户随后要求先停下并准备交接。不要再尝试绕过。
3. **用户没有在真实播放中验收。** 需要在用户正常启动的 SPW 中播放/暂停/seek/切歌，检查 `status.log`、`live-sessions.json` 和概览/排行是否按预期变化。
4. **前端交互没有逐项人工点击验证。** 主题循环、日/周热力图、点击日期联动、图表类型、范围/粒度下拉、搜索、分页、导入/导出都只是“编译 + 渲染 + 代码路径”级别，没有真实鼠标验收。
5. **入口仍是浮层 + 独立窗口**，不是宿主原生 `ScreenRoute`；Hook 目标 `androidx.compose.ui.ۄ` 绑定 1.19.0，宿主升级可能失效；失败时配置页入口仍可用。
6. **导入策略是保守合并**，不能替换/回滚历史；尚未测损坏文件、超大文件、中断。
7. **未打包 Gradle 工程**；构建依赖本机 SPW 安装目录。
8. **窗口不能拖动/缩放**，无原生标题栏；Esc 或右上角 × 关闭。

## 给接手代理的建议

1. 先读 [NATIVE-IMPLEMENTATION.md](NATIVE-IMPLEMENTATION.md)，不要重复设计 UI。
2. 先让用户在其正常 SPW 环境安装、授权、播放并反馈；不要自己启动未授权的 `danger-full-access` 宿主。
3. 如果用户允许继续自动化测试：
   - 用隔离 `APPDATA`；启动器必须带 `-Djdk.attach.allowAttachSelf=true`。
   - 若在沙箱中运行，`java.util.prefs` 会因注册表拒绝产生崩溃弹窗；这不是插件 bug，应改用用户正常会话或明确申请权限。
   - 真实播放验证重点：播放/暂停是否 `isPlaying=true/false`、seek 是否不计入、切歌是否 finalize 并新建会话、跨日小时分配、宿主 `Listen` 落库后是否去重。
4. 优先修复“真实播放无法启动/无 `isPlaying` 回调”的宿主环境问题，再谈实时精度；不要在没有真实事件的情况下宣称实时统计已验收。
5. 如果宿主提供稳定的原生页面入口，评估把 `JDialog` 页面迁入宿主导航；否则保留当前入口并在交付中继续说明。
6. 导入/导出若要继续，先和用户确认“合并 vs 替换”的产品口径。
7. 任何新的宿主版本适配都要重新确认 Hook 目标类名、Workshop API 版本和 Compose/Salt UI 签名。

## 测试环境残留（均不提交）

- `build/test-appdata/`：独立 `APPDATA` 副本，含测试 `spw.db`、`workshop/plugins`、`enabled.txt`、`permissions.json`、实时会话/日志。
- `build/test-localappdata/`：独立 `LOCALAPPDATA` 副本。
- `build/reg-spw-backup.reg`：本轮为可能的真实注册表测试导出的 `HKCU\Software\JavaSoft\Prefs\com\xuncorp\voxzen` 备份。**本轮提权被拒绝，没有执行会修改注册表的启动；如后续有提权测试，结束后用 `reg import` 恢复此文件并确认。**
- `build/tests/`：临时 Kotlin 状态机/合并测试源码与输出，未提交。
- `build/tools/`：JDK 25、Kotlin 2.4.20、sqlite-jdbc、`spw-host.jar` 缓存。

## 可直接复制给新代理的开场

```text
继续 SPW-Listen Stats 正式模组，工作目录 E:\Code\spw status，分支 codex/listening-stats（已推送）。
先读 AGENTS.md、docs/HANDOFF.md、docs/NATIVE-IMPLEMENTATION.md、docs/REQUIREMENTS.md、docs/API-FEASIBILITY.md，再检查 git status、git log、build/libs 下的 .spmod。
当前第一版已构建、安装、渲染真实 Listen 数据，实时 PlaybackTracker/LiveStore 已实现并有 JVM 模拟测试；但真实播放/暂停/seek/切歌和前端鼠标交互还没有在用户环境中验收。
用户刚要求暂停并让另一个代理接手；不要未经允许用 danger-full-access 启动 SPW。沙箱内 java.util.prefs 会因注册表拒绝弹崩溃报告，这不是插件 bug。
下一步先请用户在正常 SPW 中安装、授权、播放并反馈；再按 docs/HANDOFF.md 的“当前未完成”逐项处理。preview/ 是定稿基准，不要重新设计。
```
