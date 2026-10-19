# GPT 交接文档：听歌统计

> **最新准备：v0.1.0 首个公开预发布版。** 用户要求仅创建 GitHub 草稿 Release，暂不公开。基于开发版 0.1.35 整理，不改统计或 UI 逻辑。发布说明见 [releases/v0.1.0.md](releases/v0.1.0.md)，验证与附件见 [releases/PREPARATION-v0.1.0.md](releases/PREPARATION-v0.1.0.md)。
>
> 最终对外名称统一为 **听歌统计**，入口为“打开听歌统计”，安装包为 `听歌统计-v0.1.0.spmod`。三张实际效果图只放 README；Release 简短且不放图，附件仅安装包，不发布 `SHA256SUMS.txt`。保留“椒盐经典”推荐，插件 ID 保持。
>
> **0.1.32：** 跳页输入框数字居中、34dp 高，分页按钮/箭头/跳页框统一 8dp 圆角。
>
> **2026-10-19 更新：当前源码 0.1.21。** 控件底色及宿主字体接入见 [UI-0.1.21.md](UI-0.1.21.md)。用户实机反馈已证明 0.1.20 的字体继承不足；当前通过读取宿主字体策略和实际字体资源修正，视觉待用户核对。

> **2026-10-19 更新：0.1.20。** 已处理审查发现的六项问题，当前变更及验证状态以 [BUGFIX-0.1.20.md](BUGFIX-0.1.20.md) 为准；下文 0.1.19 版本、提交和检查数字保留为上一轮背景。仍不能把离线回归视为真实播放或宿主字体/交互验收。

> 这是给接手 GPT 的**单一入口文档**。先读本文件，再按需读 `docs/NATIVE-IMPLEMENTATION.md`、`docs/HANDOFF.md`、`docs/UI-0.1.22.md`。
> 最后更新：2026-10-19；首个公开版本 **0.1.0（预发布）**；分支 `codex/listening-stats`。此前 0.1.35 等编号是开发阶段版本。

## 0. 一句话状态

一个给 Salt Player for Windows（SPW）1.19.0 用的听歌统计模组，已经完成：宿主内容区内嵌页、真实听歌数据读取、50% 计次、跨日/跨小时分配、持久化、封面、导入导出、SalT UI 接入、主题跟随 SPW。当前进入**用户视觉验收和收尾阶段**，真实播放/暂停/seek 仍需在正常 Steam 启动中做最终验收。

## 0.1.22 当前增量（优先于下文旧状态）

- **封面性能**：后台生成 44dp 方形缩略图、12MiB LRU、并发 4（基线 3）、相同请求合并、稳定行 key、组件自身 30 秒重试；移除全局滚动重组源。隔离实测 25 首：wait 0–355ms、API 多为 3–15ms（个别 244ms）、decode 21–160ms、UI 约 300–562ms；瓶颈是并发排队。**未测滚动 FPS、内存、缓存命中返回**，不能声称“不卡顿已解决”。
- **日期/菜单**：`DateRangeDialog` 起始/结束两张独立卡片，各自年月选择+箭头，左只改开始、右只改结束；不自动交换，起>止提示并禁用确定；底部已选天数含首尾；窄宽上下排列。`SelectBox` 自绘 Popup，菜单宽按最长选项测量、最小 144dp，外框 6dp 留白、选项背景内缩、文字 10dp、行高 34dp，年份菜单限高滚动。
- **排行底栏**：删除标题下摘要；底部固定“共 xx 首 · 听满单曲 50% 计一次”，数量随筛选；右侧分页。
- **顶部入口**：验证结论为**当前无安全插入点**。`MainTitleBar` 只有 `(MainTitleBarType, PlaybackChromeVisibilityState, MusicVideoPlaybackUiState, Modifier)`，内部 `MainTitleBarContent` 构建操作区；afterComposable 不能插入内部 Row，replace 无原调用委托，appendContent 不是任意 lambda。未做固定坐标 overlay。
- 详细：`docs/UI-0.1.22.md`。

## 1. 项目位置与产物

- 仓库：<https://github.com/Jethuit/SPW-Listen-Stats>
- 分支：`codex/listening-stats`
- 最新代码提交：本文档随后提交（0.1.22；上一提交 `1387748` 是 0.1.21）
- 构建产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.22.spmod`
- 产物约 17.15 MB，`.spmod` 内 129 项；`lib/` 含：  - `sqlite-jdbc.jar`
  - `salt-ui-desktop-3.0.0-beta02.jar`
  - `salt-core-desktop-3.0.0-beta02.jar`
- 当前只推送了代码，**没有创建 GitHub Release，也没有上传构建产物**。是否发布要问用户。

## 2. 用户明确决策（不能擅自推翻）

1. **不要独立窗口、不要浮层、不要置顶、不要拖动窗口。**
   - 统计页通过宿主 `AppNavigation` hook 内嵌在右侧内容区。
   - 宿主侧栏和底部播放栏保留。
   - 曾经的 `JDialog` / 右下角 Popup pill 已被否决；不要恢复。
2. **入口只保留模块管理页的插件配置按钮“打开听歌统计”。**
   - 不要恢复主界面浮动入口。
3. **UI 优先使用 SaltUI。**
   - 当前已打包 SaltUI **3.0.0-beta02**，宿主显示的是 SaltUI 3.0.0-SNAPSHOT / CMP 1.12.1。
   - 已使用 `SaltTheme`、`verticalScroll`、`Text`、`Card`。
   - 按钮、分段控件、热力图等仍未全部迁移；继续迁移要逐步做，不要一次重画。
4. **做决定前先问用户；多汇报。**
   - 不要自行选择架构、SaltUI 版本、窗口形态、是否发布。
   - 有多个合理方案时先给选项。
5. **不要用会跨机器失效的固定 dp 布局。**
   - 目前仍有固定 34dp/96dp/1080dp 等设计值；响应式替换是未完成项。
   - 后续优先 SaltUI `ControlSize` / `saltDimens` / `BoxWithConstraints` / 比例约束。
6. **不伪造 Acrylic/Mica，不写未验证为已完成。**
   - 详见第 6 节。

## 3. 当前功能状态

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 宿主内容区内嵌页 | 完成 | `AppNavigation` afterComposable；无独立窗口 |
| 配置页入口 | 完成 | 模块管理 → 插件配置 → 打开听歌统计 |
| 概览/排行切换 | 完成 | 概览、排行 |
| 真实听歌记录读取 | 完成（离线回归） | 只读宿主 `spw.db` 的 `Listen` 表 |
| 50% 计次 | 完成（离线回归） | 每遍实际听满 50% 计一次 |
| 跨日/跨小时分配 | 完成（离线回归） | 见 `DataRegressionTest` |
| 持久化/备份 v2 | 完成（离线回归） | 兼容 v1，保守合并 |
| 封面 | 完成但受限 | Workshop API 内嵌封面；失败 30 秒冷却重试；无内嵌封面保持占位 |
| 导入/导出 | 完成 | 插件配置/页面按钮 |
| SaltUI 接入 | 部分完成 | 主题/滚动/Text/Card 已接；按钮/分段/热力图未全迁 |
| 主题跟随 SPW | 已实现但未系统验收 | `AppConfig.lightDarkTheme`；Light/Dark 直接跟随，FollowSystem 回退系统 |
| 椒盐经典页底色 | 已实现 | 页面根 #F8F8F8 / #232323；排行行跟随 |
| Acrylic/Mica/云母 Alt | 未实现 | 回退经典色；见限制 |
| 真实播放/暂停/seek | 未验收 | 必须正常 Steam 启动中测试 |
| 每页 25 首 + 第 3 页 | 已修复 | 共享 `RANKING_PAGE_SIZE=25` / `rankingTotalPages` / `rankingSafePage` |

## 4. 架构与入口

### 4.1 宿主内嵌

- `MainPlugin` 注册 hook：
  - `WorkshopApi.hookRegistrar.afterComposable(...)`
  - 当前绑定 SPW 1.19.0 的 `com.xuncorp.voxzen.ui.navigation.AppNavigationKt.AppNavigation`。
- `StatsOverlay` 在 hook 后判断 `StatsHostState.visible`，直接渲染统计页。
- 没有 `StatsWindow.open()` 调用；`StatsWindow` 里的旧 JDialog 代码是**未使用遗留代码**，清理前要确认没有引用。
- `StatsHostState`：全局可见状态，`open()` / `close()` 在 EDT 调用。

### 4.2 配置入口

- `preference_config.json` 提供按钮；`MainPlugin.openStats()` 设置 `StatsHostState.visible = true`。
- 没有 host 原生路由，页面只是内容区叠加；侧栏仍可点击宿主原页面。

### 4.3 页面与数据

- `ListenStatsApp`：页面外壳、主题、滚动、分页、页脚。
- `OverviewPage`：概览、热力图、时长分布、小结。
- `RankingPage`：搜索、排行、25 条/页、底部单行说明 + 分页。
- `TrendChart` / `YearHeatmap`：canvas 绘制；文字使用 SaltUI 宿主字体族。
- `CoverStore`：封面加载/失败冷却。
- 数据层：`PlaybackTracker`、`StatsStore`、`LiveStore`、`DataFiles`、`BackupService`。
- 页面每 5 秒刷新一次。

## 5. 主题、字体和颜色

### 主题

- 三档：**跟随 SPW / 浅色 / 深色**。
- `ThemeMode.System` 的显示语义已改成“跟随 SPW”，存储值仍是 `"system"`，兼容旧配置。
- `HostAppearanceReader` 读取：
  - `com.xuncorp.voxzen.util.AppConfig.lightDarkTheme`
  - `com.xuncorp.voxzen.util.AppConfig.spwWindowStyle`
- 每秒轮询一次；宿主 Light/Dark 直接采用，FollowSystem 回退系统主题；异常回退，不崩溃。
- 这属于**宿主内部 API**，不是 Workshop 公共 API；绑定 SPW 1.19.0，宿主更新可能失效。失效时应回退，不要静默崩溃。

### 字体

- 正文使用 SaltUI `Text` + `SaltTheme`，继承宿主字体。
- canvas 文字（图表/热力图）使用 SaltUI `LocalTextStyle.current.fontFamily`，字号仍用 sp。
- 字体缩放靠 sp；布局尺寸仍有固定 dp，响应式未完成。

### 页面颜色（当前）

- `StatsColors.pageBackground`：
  - 浅色 **#F8F8F8**
  - 深色 **#232323**
- 页面根 `Surface` 用 `pageBackground`；排行 `SongRow` 也用 `pageBackground`。
- 卡片/表格行仍用 `background`（白/深色）；控件用 `subtle`。
- 这组值来自用户截图**最顶部**：浅色 y=0–50、深色 y=0–35。
- 不要擅自改成 #F3F3F3/#1E1E20；那是之前误取的内容区颜色。

## 6. Acrylic / Mica 的真实边界（重要）

**插件无法真正实现 Acrylic/Mica，也无法直接透到桌面。**

原因：

- Acrylic/Mica 是宿主窗口的 Windows DWM 效果，属于宿主窗口级能力。
- 统计页是 `afterComposable` 叠加在宿主内容区上的；宿主下面的歌曲页仍在绘制。
- 把统计页根设透明，透出的是宿主页面，不是桌面壁纸；把宿主窗口设 DWM 也不是插件该做的事。
- 真正可行的路线：宿主官方提供“插件内容区透明/使用窗口背景”的 API，或 SPW 作者实现。

当前实现：

- 读取 `AppConfig.spwWindowStyle`，但只做**椒盐经典**适配。
- `WindowsAcrylic` / `WindowsMica` / `WindowsMicaAlt` 暂时回退经典页底色。
- 没有透明、没有假模糊、没有 DWM hack。
- 不要再尝试“55% 色调混合”这种几乎看不出的假样式；用户已经否决。

如果用户要真正的 Acrylic/Mica：先说明上述限制，再建议向宿主提 API/功能请求，不要自己上 native hack。

## 7. 构建和测试

### 构建

```powershell
python tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

- 工具链缓存在 `build/tools/`（JDK 25、Kotlin 2.4.20、SaltUI jar、sqlite-jdbc、宿主 fat jar）。
- `build/` 全部不入 Git。
- 宿主 fat jar：`app/ffmpeg-x64.dll`；插件编译时作为 classpath，用来匹配混淆签名。

### 测试

```powershell
python tests/run-data-tests.py data
python tests/run-data-tests.py ui
python tests/spmod-structure.py
```

最近结果（0.1.19）：

- 13 组数据回归通过
- 4 组 UI 几何回归通过
- `.spmod` 110 项结构检查通过
- `lib/` 三个 jar 校验通过
- 产物约 17,079,006 字节

UI 回归新增的排行分页检查：

- 72 首 = 3 页
- 第 3 页不会被夹回第 2 页
- 越界页夹到末页
- 25 首 = 1 页

### 安装

- 创意工坊“本地导入” `.spmod`。
- 权限只声明：`library-read`、`class-transform`。
- 入口：模块管理页 → 插件配置 → 打开听歌统计。

## 8. 已验证 / 未验证

### 已验证（本地、隔离环境）

- 构建、包结构、数据回归、UI 几何回归。
- 宿主内嵌页截图：页面在右侧内容区，无浮层 pill，宿主侧栏/播放栏保留。
- 滚轮可滚动到图表和听歌小结。
- 分页 25 条/页的逻辑回归。
- 旧配置路径/恢复逻辑的离线回归。

### 未验证 / 需要用户验收

- 正常 Steam 启动中的真实播放、暂停、seek、切歌。
- 宿主 `spw.db` 落库与实时会话去重。
- 配置页入口的点击链路（用户日常在用，但没有自动化测试）。
- 主题跟随 SPW 在宿主中是否实时生效（`AppConfig` 内部 API 可用性）。
- 0.1.19 的页底色/列表底色视觉效果。
- SaltUI 字体/组件在真实宿主中的最终视觉。
- 正常 Steam 启动是否仍出现宿主 `java.util.prefs` 崩溃报告。
  - 已知堆栈是宿主 `PlaybackService` 写注册表 `HKCU\Software\JavaSoft\Prefs\com\xuncorp\voxzen` 时 `Access denied`，不是插件堆栈。
  - 该问题在 DSH 受限沙箱中可复现；不要在插件里尝试提权或改宿主注册表。

## 9. 已知限制 / 坑

1. **Hook 绑定 1.19.0**：宿主类名/方法名变化会导致入口失效，需要重新定位。
2. **宿主内部 AppConfig**：主题/样式读取是内部 API，不是 Workshop 公共契约；必须 try/catch 回退。
3. **没有原生路由**：页面只是内容区叠加，不是宿主 ScreenRoute；侧栏仍可点击。
4. **透明不可用**：不要用透明解决 Mica；会露出宿主底层页面。
5. **封面限制**：只支持宿主内嵌封面；外链封面不会加载；失败 30 秒冷却，避免每次刷新打爆 API。
6. **宿主历史缺少暂停片段**：小时分布/实时匹配仍是近似。
7. **固定 dp**：1080 页宽、96dp 底部 padding、96dp 播放次数列、34dp 控件等仍存在；窄窗口/大字体需继续响应式。
8. **SaltUI 组件未全迁**：按钮/分段/热力图仍是自绘/Compose 原语；不要声称“已全部 SaltUI”。
9. **旧 JDialog 遗留代码**：`StatsWindow` 文件中仍有未使用的窗口实现；删除前检查引用。
10. **启动崩溃报告**：上面已说明；不要在插件中尝试修宿主注册表。
11. **不要提交**：真实听歌历史、宿主数据库、私有备份、`build/`、SaltUI jar、JDK/Kotlin 工具链、SPW 安装文件。

## 10. 推荐下一步（按优先级）

1. **用户视觉验收 0.1.19**：椒盐经典页底、排行行底、列表、主题、分页 25。
2. **真实播放回归**：播放/暂停/seek/切歌，确认 50% 计次、跨小时、持久化和宿主落库。
3. **SaltUI 组件迁移**：按钮、分段控件、热力图；每步截图给用户确认，不要一次全换。
4. **响应式尺寸**：用 SaltUI `ControlSize`/`saltDimens` 和比例约束替换固定 dp；先问目标最小窗口/缩放。
5. **背景样式**：如果用户仍要 Acrylic/Mica，先给宿主 API 方案，不要 native hack。
6. **清理**：删除未使用 JDialog、未使用 `HostAppearance.windowStyle`（当前只做经典色）等；清理前确认引用。
7. **发布**：只有用户明确要求才创建 GitHub Release / 上传 `.spmod`。

## 11. 关键文件速查

```text
plugin/src/main/kotlin/com/jethuit/spw/listenstats/
  MainPlugin.kt                入口/开放统计页/停止清理
  PlaybackTracker.kt           播放事件/50%/跨日分配
  StatsStore.kt                宿主历史读取/统计
  LiveStore.kt                 实时会话持久化
  DataFiles.kt                 数据目录/版本/source id
  BackupService.kt             导入导出/备份 v2
  data/CoverStore.kt           封面加载与失败冷却
  ui/StatsHostState.kt         内嵌页可见状态
  ui/StatsOverlay.kt           hook 后的内嵌入口
  ui/StatsWindow.kt            ListenStatsApp + 旧 JDialog（遗留）
  ui/Theme.kt                  主题/StatsColors/pageBackground
  ui/HostAppearance.kt         宿主 Light/Dark 与窗口样式读取
  ui/OverviewPage.kt           概览
  ui/RankingPage.kt            排行/25 条分页/搜索
  ui/TrendChart.kt             时长分布 canvas
  ui/YearHeatmap.kt            热力图 canvas
  ui/Components.kt             SaltUI 文本/Card、控件、页头
  ui/Icons.kt                  图标 path
  ui/UiGeometry.kt             UI 几何回归辅助

tools/prepare_toolchain.py     下载 JDK/Kotlin/依赖/SaltUI
tools/build_plugin.py          编译/打包 .spmod
tests/DataRegressionTest.kt    数据回归
tests/UiRegressionTest.kt      UI 几何/分页回归
tests/run-data-tests.py        测试运行器
tests/spmod-structure.py       包结构检查

docs/GPT-HANDOFF.md            本文件
docs/HANDOFF.md                历史交接（最新 0.1.19）
docs/NATIVE-IMPLEMENTATION.md  实现说明
docs/UI-FIXES.md               UI 修订记录
preview/                       用户确认的视觉基准
```

## 12. 给接手 GPT 的起始提示

可以把下面这段直接作为新会话的起始说明：

```text
你在接手 听歌统计（Salt Player for Windows 1.19.0 的听歌统计模组）。
先读 docs/GPT-HANDOFF.md，再读 docs/HANDOFF.md 最新一节和 docs/NATIVE-IMPLEMENTATION.md。
当前版本 0.1.19，分支 codex/listening-stats，产物 build/libs/plugin-com.jethuit.spw.listenstats-0.1.19.spmod。

硬约束：
- 不要恢复独立窗口、Popup pill、置顶或拖动窗口；页面必须内嵌在宿主内容区。
- 入口只保留模块管理页插件配置按钮。
- UI 优先 SaltUI，但迁移要渐进并截图确认。
- 改架构/版本/发布前先问用户；多做阶段汇报。
- 不要伪造 Acrylic/Mica，不要写未验证为已完成。
- 不要提交 build/、宿主文件、真实听歌数据、工具链或 SaltUI jar。

当前已知边界：
- 主题跟随 SPW 已实现，但内部 AppConfig API 未在正常宿主中系统验收。
- 椒盐经典页底取色 #F8F8F8 / #232323；Acrylic/Mica 回退经典色，真实 DWM 插件做不到。
- 真实播放/暂停/seek、宿主落库去重仍需用户验收。
- 固定 dp/响应式、SaltUI 按钮/分段/热力图迁移仍未完成。

下一步先问用户 0.1.19 的视觉验收结果，再决定改哪里；不要重复重写整个 UI。
```
