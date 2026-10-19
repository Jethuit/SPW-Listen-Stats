# 新聊天交接

> 给接手 GPT 的单一入口文档见 [`GPT-HANDOFF.md`](GPT-HANDOFF.md)；本文件保留逐版本修订记录。

## 最新调查：顶部统计入口

- 用户最新指定彩色快捷按钮左侧的红框。候选改为 `CaptionButtons` 内 `DebugCaptionButton` 调用之后、Quick 之前；原 Quick 之后位置保留为备选，详见 [TOPBAR-ENTRY.md](TOPBAR-ENTRY.md)。这修正了早期仅检查整个标题栏后得出的“无安全插入点”结论。
- 一轮只读探针通过：宿主 UI Hook 解析器接受目标；整个调用类仅一处匹配；调用位于按钮 Row 内。尚未注册、渲染或进行真实窗口点击验收。
- 生产源码、版本与已发布 v0.1.1 均未改动；检查脚本在 `tests/probe-titlebar-hook.py`。

## 最新：v0.1.1 已正式发布

- 用户于 2026-10-19 授权更新 GitHub，v0.1.1 已正式发布并设为 Latest：[Release](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.1)。源码和 README 已同步到 main，正文采用用户提供的四条更新日志。
- 附件仅 `SPW-Stats-v0.1.1.spmod`，17,180,687 字节；标签对应提交 `9547491dda859cc193d60e572cd870b6dc61ca6e`。发布回执在本机 `build/releases/published-v0.1.1.json`。
- 最新修正自定义日期年月导航：左右月箭头固定 34×34dp，年份 92dp、月份 76dp，避免右侧按钮挤压；新增实际卡片宽度下的四组年月渲染检查。
- 导航联动、鼠标返回与 SaltUI 原生返回箭头修复均保留。箭头使用宿主 56dp 标题行、12dp 左边距和垂直居中。
- 移除正文顶部留白的宽度断点；返回行独立于正文滚动区域，正文裁切在其下方。热力图缩放逻辑保持。
- 常听时段保持单行与固定数值行高；排行底部在窄屏分行，避免说明与跳页控件挤压。详见 [UI-v0.1.1.md](UI-v0.1.1.md) 和 [BUGFIX-0.1.1.md](BUGFIX-0.1.1.md)。
- 最新检查：37 文件构建、7 组导航回归、139 项包结构通过；离屏验证 14 个宽度的正文起点、滚动后导航区像素不变、4 种时段列宽及 5 种分页宽度。未启动 SPW，完整交互仍由用户实机核对。

## v0.1.1 导航修复的初次记录

- 用户反馈统计页不能随侧栏切页或鼠标侧键返回关闭。新增 SPW 1.19.0 `Navigator` 导航钩子，切页先关闭统计页；统计页可见时返回只关闭本层，保留底层宿主历史。
- 构建、7 组导航回归与包结构检查通过；本地包为 `build/releases/v0.1.1/SPW-Stats-v0.1.1.spmod`。未启动宿主，实体侧键及侧栏交互待用户实机核对。
- 当前公开 Release 仍为 v0.1.0，本轮未发布新版本。详见 [BUGFIX-0.1.1.md](BUGFIX-0.1.1.md)。

## v0.1.0 首次正式发布

- 最终名称统一为 **听歌统计**，入口为“打开听歌统计”，安装包为 `SPW-Stats-v0.1.0.spmod`；不再发布 `SHA256SUMS.txt`。三张实际效果图只放 README，Release 保持简短；推荐使用 SPW“椒盐经典”主题样式。插件 ID 保持。

- 用户于 2026-10-19 授权公开，随后明确转为正式版。`v0.1.0` 已移除预发布标记并设为 Latest：[Release](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.0)。仓库已补充简短简介。基于开发版 0.1.35 整理，历史记录保留。
- 对外正文见 [releases/v0.1.0.md](releases/v0.1.0.md)，材料与验证记录见 [releases/PREPARATION-v0.1.0.md](releases/PREPARATION-v0.1.0.md)。
- 安装包加入项目与第三方许可证；`tools/prepare_release.py` 只整理安装包。`main` 已快进同步发布源码与最新 README；发布标签对应 `88de79f`，仓库 topic 为 `salt-player-for-windows`。此次仅核对发布材料与远端状态，未启动 SPW。

## 0.1.35 API 取消后的封面重试

- API future 自己取消时，先检查当前读取协程是否仍有效；仍有效则返回 `null`，进入正常 30 秒失败冷却与行重试流程。真正的调用方取消、最后等待者离开或 clear 仍传播取消。
- 封面回归新增行加载循环检查，避免仅测试手动重载而漏掉自动重试中断。验证结果见 [BUGFIX-0.1.35.md](BUGFIX-0.1.35.md)。本轮不安装或启动宿主。

## 0.1.34 审查修复

- 封面请求按等待者共享，最后一个等待者离开时取消，不写失败冷却；`clear()` 取消旧任务并隔离旧结果。失败冷却从失败完成时以单调时钟计时。
- 当前页未加载成功的封面持续每 30 秒重试；离开行后停止。
- 日期弹窗初始聚焦取消、Esc 走正确焦点路径，Enter 由当前控件处理；焦点组阻止跳到底层页面；高度不足时内容可纵向滚动。
- 构建、回归结果与用户核对事项见 [BUGFIX-0.1.34.md](BUGFIX-0.1.34.md)。未安装或启动宿主验收。

## 0.1.33 分页箭头透明

- 分页上一页/下一页按钮 `Surface` 底色改 `Color.Transparent`；图标仍 muted、禁用降 alpha，点击区 34×34。
- 页码胶囊/跳页框仍 controlBackground；日期上下月按钮不变。
- 详见 [UI-0.1.33.md](UI-0.1.33.md)。

## 0.1.32 跳页数字/圆角

- 跳页输入框数字居中、34dp 高、8dp 圆角。
- 分页数字选中背景、箭头、页码胶囊、跳页框统一 8dp 圆角。
- 详见 [UI-0.1.32.md](UI-0.1.32.md)。

## 0.1.31 分页居中/跳页/底部保持

- 切页前在底部则在布局后恢复 `maxValue`；不在底部保持原偏移。
- 页码按钮居中；右侧“共 x 页 · 跳转至 [x] 页”，Enter 跳转并 clamp。
- 截图 `build/screenshots/pager_center_bottom.png`。详见 [UI-0.1.31.md](UI-0.1.31.md)。

## 0.1.30 下拉箭头截断修复

- `SelectBox` 的固定 `width(minWidth)` 改为 `widthIn(minWidth)`；年份/月份按钮不再裁掉右侧箭头。
- 三个箭头图标统一 24dp（keyboard_arrow 路径），日期/分页/下拉一致。
- 截图 `build/screenshots/arrows_24.png`。详见 [UI-0.1.30.md](UI-0.1.30.md)。

## 0.1.29 keyboard_arrow 图标

- 日期、分页、下拉菜单改用 Material Symbols `keyboard_arrow_left/right/down`，不再旋转 ExpandMore。
- 统一 18dp、muted、34dp 按钮/8dp 圆角；AppIcon 支持 24 viewBox 正坐标。
- 截图 `build/screenshots/keyboard_arrows.png`。详见 [UI-0.1.29.md](UI-0.1.29.md)。

## 0.1.28 分页不跳顶/箭头统一

- 排行切页不再 `scrollTop()`；页面切换（概览/排行）仍回顶。
- 日期上下月、分页前后箭头改用与下拉菜单同一 `ExpandMore` 路径，分别旋转 ±90°；16dp、muted、按钮 34dp/8dp。
- 截图 `build/screenshots/arrow_consistency.png`。详见 [UI-0.1.28.md](UI-0.1.28.md)。

## 0.1.27 日期弹窗 hover/箭头高度

- 去掉白色卡片的 clickable Surface hover 层和日期格 HoverColor；透明点击拦截层只消费空白点击，不显示悬停色。
- 年月与箭头统一 34dp 高、8dp 圆角。
- 截图 `build/screenshots/date_hover_fix.png`。详见 [UI-0.1.27.md](UI-0.1.27.md)。

## 0.1.26 分页箭头分离

- `‹` / `›` 各自独立 34×34 按钮；页码数字单独一个胶囊；间距 7dp。
- `第 x / y 页` 指示保留；0.1.25 的大页数窗口规则不变。
- 详见 [UI-0.1.26.md](UI-0.1.26.md)。

## 0.1.25 排行分页

- 每页 25 首；600 首 = 24 页，末页 16 首。
- 右下角增加 `第 x / y 页`；分页按钮使用首尾 + 当前邻域 + `…`，不会平铺 24 个按钮。
- 搜索后分页总数按筛选结果重算；空结果/单页隐藏分页，底部说明仍显示。
- 详细：[UI-0.1.25.md](UI-0.1.25.md)。

## 0.1.24 热力图近一年与最小窗口单行

- 热力图年份菜单新增“近一年”，使用滚动 365 天网格（含跨年月标签），不改变榜单/时长分布筛选。
- 范围预设“自定义日期”改为“自定义”；自定义范围按钮不再显示具体起止日期，直接显示“自定义”。
- `SectionHeading` 移除窄窗口上下排列和 `FlowRow` 换行，标题/操作区始终同一行；1000×900 宿主截图确认热力图和时长分布均不换行。
- 截图 `build/screenshots/min_layout_final.png`、`heatmap_menu_final.png`、`heatmap_rolling_final.png`。详见 [UI-0.1.24.md](UI-0.1.24.md)。

## 0.1.23 独立双日历卡片与菜单收窄

- 日期弹窗按最新要求覆盖 0.1.22 的联动方案：起始/结束两张等宽独立卡片，各自年月选择+箭头；左卡只改开始、右卡只改结束；切换年月只浏览；打开各定位已选月份；不自动交换，起>止提示并禁用确定；底部已选天数含首尾；响应式上下排列。
- `SelectBox` 改为自绘 Popup：菜单宽度按最长选项+文字留白测量、最小 144dp；外框 6dp 留白、选项背景在内、文字 10dp、行高 34dp；年份菜单限高 320dp 可滚动。
- 排行标题/搜索操作区 12dp 上下留白；旧 18dp 间距已移除。
- 封面分段耗时和顶部入口结论沿用 0.1.22；未新增性能结论。
- 卡片截图 `build/screenshots/cards_independent_final.png`；年份选项仅 2025–当前；菜单最终宽度取按钮宽度与自然宽度的较小值。最终滚动菜单尚未重新截图。详见 [UI-0.1.23.md](UI-0.1.23.md)。

## 0.1.22 封面/日期/底部/顶部入口验证

- 封面：后台按 44dp 显示尺寸生成方形缩略图（Skia raster 强制解码）、12MiB LRU、并发 4（基线 3）、相同请求合并、稳定行 key、自身 30 秒重试；移除全局滚动重组源，图表各自读滚动 bucket。隔离实测 25 首：wait 0–355ms、API 多项 3–15ms（个别 244ms）、decode 21–160ms、UI 约 300–562ms；**主要耗时是排队，不是 API/解码；未测滚动 FPS、内存和缓存命中返回**。
- 日期：页面内 Compose 双月日历（0.1.23 已被独立双卡方案覆盖）。
- 排行：标题/搜索操作区外层 12dp 上下留白，移除 PageNav 旧 18dp；删除标题下摘要；底部固定“共 xx 首 · 听满单曲 50% 计一次”，数量随筛选；单页/空结果也显示。
- 顶部入口：源码级验证 `MainTitleBar` 无 action slot，`MainTitleBarContent` 内部构建；afterComposable 无法插入内部 Row，replace 无原实现委托，appendContent 不是任意 lambda。**未实现占位，也未用固定坐标 overlay。**
- 详见 [UI-0.1.22.md](UI-0.1.22.md)。

## 0.1.21 控件底色和字体

浅色控件底色轻微加深；显式读取宿主字体策略，加载宿主内置 MiSans 或自定义字体，再传入插件 SaltTheme。修复 0.1.20 只继承插件样式、未接到宿主字体的问题。细节与验证见 [UI-0.1.21.md](UI-0.1.21.md)。

## 0.1.20 审查修复

已修复会话恢复误合并、时钟校正导致备份失败、内嵌滚动悬停未清理、文字样式丢失字体继承、搜索丢失原排名、测试依赖运行日期。细节与验证范围见 [BUGFIX-0.1.20.md](BUGFIX-0.1.20.md)。以下版本记录是历史，不覆盖此节。

## 0.1.19 排行行底色

- 0.1.18 只改了页面根 Surface，排行 `SongRow` 仍用 `colors.background`（白/深色），所以列表看起来没变。
- 0.1.19 把 `SongRow` 常态背景改为 `colors.pageBackground`（#F8F8F8 / #232323），悬停仍 `subtle`；表头透明，跟随页面。
- 封面占位、分页按钮等控件背景未改；概览卡片仍白色。

## 0.1.18 重新取顶部颜色

- 用户指出 0.1.17 取错：取了页面内容区（#F3F3F3 / #1E1E20），应取截图**最顶部**色。
- 重新采样：浅色 y=0–50 为 **#F8F8F8**；深色 y=0–35 为 **#232323**。
- `pageBackground` 已改为 #F8F8F8 / #232323；卡片/行仍用 `background`（白/深色）。
- Acrylic/Mica 继续回退经典色；主题跟随 SPW。

## 0.1.17 椒盐经典直接取色

- 不再对 `StatsColors.background` 做 55% 色调混合（那让四种样式都近似白）。
- 新增 `StatsColors.pageBackground`：浅色取宿主截图侧栏/经典页的 **#F3F3F3**，深色 **#1E1E20**；页面根 Surface 用它，卡片/表格行继续用白色 `background`。
- 用户要求先只做“椒盐经典”；Acrylic/Mica/云母 Alt 暂回退经典页底色，不做透明、不做真实 DWM，也不假装支持。
- 主题仍跟随 SPW，三档保留。
- 宿主 `SpwWindowStyle` 读取代码保留在 `HostAppearanceReader`，但当前不用于页面底色。

## 0.1.16 近似背景样式

- 新增 `HostAppearanceReader`，读取宿主 `AppConfig.spwWindowStyle` 与 `lightDarkTheme`；失败时返回空值安全回退。
- `StatsTheme` 按 `WindowsAcrylic` / `WindowsMica` / `WindowsMicaAlt` 对 `StatsColors.background` 做 55% 色调混合；`None`/椒盐经典保持原色。**没有透明/DWM 模糊**，避免透出宿主底层歌曲页。
- 用户确认：保留“跟随 SPW / 浅色 / 深色”三档；背景自动跟随宿主，不增加插件样式选择器。
- 读宿主字段仍属于内部 API，绑定 SPW 1.19.0；运行时是否成功、色调是否可接受需要用户在宿主中验证。

## 0.1.15 主题跟随 SPW

- 主题循环第一档从“跟随系统”改为“跟随 SPW”：`StatsTheme` 每秒读取宿主 `com.xuncorp.voxzen.util.AppConfig.lightDarkTheme`；Light/Dark 直接采用，FollowSystem 回退本机系统主题。
- 宿主内部 API 失败时捕获并回退系统，不让插件崩溃；日志/可见性尚未在宿主中验证。
- 0.1.14 的 25 条/页和第三页修复继续保留。
- **背景样式（椒盐经典/亚克力/云母/云母 Alt）尚未实现。** 宿主的 `SpwWindowStyle` 是 Windows DWM 窗口背景效果，插件只能读取枚举；真正的 Acrylic/Mica 由宿主窗口设置，插件页无法自行创建。可行方案是让页面根透明/半透明以继承宿主背景，或只模拟颜色（无真实模糊）；需用户选择，不能默认改。

## 0.1.14 第三页跳转 bug

- 根因：`RankingPage` 已改为 25 条/页，但 `StatsWindow` 仍用旧公式 `(rankCount + 49) / 50` 计算 `safePage`；点第 3 页时 `rankPage=3` 被夹回 2。
- 修复：新增 `RANKING_PAGE_SIZE=25`、`rankingTotalPages(count)`、`rankingSafePage(count,page)`，`RankingPage` 和 `StatsWindow` 共用；删除 `rowsOnPage` 旧公式。
- 回归：`UiRegressionTest` 新增 72 首=3 页、第 3 页不被夹回、越界夹到末页、25 首=1 页。
- 0.1.13 的底栏、头部按钮、热力图标题等继续保留。

## 0.1.13 底栏/头部/每页数

- 排行底栏去掉说明末尾的“·”和虚线引导线，只保留“听满单曲 50% 计一次”与右侧 `‹ 页码 ›` 按钮组。
- `TextAction`（导入/导出）和主题 `IconAction` 统一：按钮 34dp、圆角 8dp、图标 19px、图标色 `muted`；文字仍用正文色。
- 排行 `PAGE_SIZE` 从 50 改为 **25**。
- 概览标题“年度足迹”改为“听歌热力图”。

0.1.13 只做构建/离线回归，未在宿主中截图；头部图标尺寸、每页 25 首和标题需用户核对。

## 0.1.12 排行底栏与图注

- 排行底部改为单行：左侧“听满单曲 50% 计一次 ·”，中间虚线 leader，右侧 `‹ 1 2 ›` 分组按钮；删除排行页原来的独立分页行、重复说明和 `PageFooter` 状态行（`PageFooter` 只保留概览页）。
- 分页按钮放进 `subtle` 圆角组，外组 8dp、内部 5dp，无描边；上一页/下一页用 `‹`/`›`，页码仍最多 7 个并用省略号。
- 排行搜索框去掉描边和聚焦边框，背景改 `subtle`、8dp 圆角；`TextAction`/`IconAction` 也统一 8dp。
- `SectionHeading` 桌面分支从固定高度改为 `heightIn(min=headingHeight)`，图二/图三的灰色说明不再被 42dp 固定高度裁掉。
- 0.1.11 的 18sp 排行标题、8dp 控件圆角继续保留。

0.1.12 只做构建/离线回归，未在宿主中截图；底栏布局、图注和搜索框需用户核对。

## 0.1.11 标题字号与圆角

- 排行页“歌曲播放次数”通过 `SectionHeading(titleStyle=...)` 从 16sp 放大到 18sp，行高 24sp；标题行仍 34dp。
- `SegmentedControl` 外框和 `SelectBox` 统一为 **8dp** 圆角；分段内部 active 按钮仍 5dp（嵌套状态）。
- 0.1.10 的 34dp 控件高度、排行标题行 34dp、导航下 18dp 留白继续保留。

未在宿主中截图，需用户确认 18sp 标题和 8dp 圆角。

## 0.1.10 控件等高与留白

- `ControlHeight = 34dp`：`SegmentedControl` 外层固定 34dp（内边距 3dp，按钮 28dp），`SelectBox` 固定 34dp 且内容垂直居中；图一图表类型/日期、图二每天每周/年份高度一致。
- 排行页标题行 `SectionHeading` 高度从 42dp 收到 34dp；`PageNav` 在排行页下方间距从 30dp 收到 18dp，减少红框上方空白。
- 图表类型按钮 tooltip 已在 0.1.9 删除；本次保留。

0.1.10 只做构建/离线回归，需用户实际确认控件等高和留白。

## 0.1.9 提示块与标题行对齐

- 图表类型按钮的“柱状图/折线图/曲线图”黑色悬停提示删除；`SegmentedControl` 仍保留 `contentDescription`，只是不再显示黑色 tooltip。
- 排行页标题/搜索框/摘要所在标题行改为固定 42dp，两个子区域分别在 42dp 内 `CenterStart`/`CenterEnd`，修正上下不居中。
- 之前的 0.1.8 修复继续有效：顶部重复分页删除、播放次数列 96dp 居中、封面 30 秒冷却重试、canvas 使用宿主字体、主题/返回 tooltip 删除。

0.1.9 只做构建和离线回归，未在宿主中截图；标题行居中和按钮提示需要在正常 SPW 中确认。

## 0.1.8 排行榜/封面/字体修复

用户对当前宿主内嵌页提出 5 项，已修：

- 排行页顶部页码删除，只在列表底部保留分页。
- 播放次数列固定 96dp，表头和数值居中；其余列按剩余宽度 56%/44% 分配。
- 字体：正文已经走 SaltUI `Text`/`SaltTheme` 的宿主字体；图表和热力图 canvas 文字改用 SaltUI `LocalTextStyle.current.fontFamily`，配合原有 sp 字号，随 SPW 字体和缩放走，不再固定系统字体。
- 封面：`CoverStore` 不再缓存失败/空结果；失败后进入 30 秒冷却，页面每 5 秒刷新时重试，成功后再缓存。仍只能读取宿主内嵌封面；无内嵌封面的曲目保持占位图。
- 主题按钮的“跟随系统…”黑色 tooltip 删除（`tooltip=null`）；返回键此前已删除 tooltip。黑色提示不再出现。
- 单日横轴 4 小时标签、纵轴“时长/h”、底部 96dp、热力图取消选择等 0.1.6 修复继续保留；右侧 SelectBox 描边已在 0.1.7 删除。

0.1.8 只做了构建、13 组数据回归、4 组几何回归、107 项包结构检查；没有宿主截图。分页位置、封面重试、字体和黑色提示需要在正常 SPW 中核对。

## 0.1.7 选择框去描边

用户补充：左侧“每天/每周”分组没有描边，右侧年份/日期选择框也应无描边。0.1.7 删除 `SelectBox` 的 1px border，只保留 `subtle` 背景，与左侧分组一致。

0.1.6 的 5 项修复继续保留：右侧背景统一、热力图再次点击取消选择、纵轴“时长/h”/单日 4 小时横轴、底部 96dp 预留、返回箭头去 tooltip 并改带横杆样式。

0.1.7 只做了构建和离线回归；需要在正常宿主中确认右侧选择框是否完全不描边，以及之前 0.1.6 的各项是否仍符合预期。

## 0.1.5 宿主内嵌页 + SaltUI

用户明确否定了 0.1.4 的独立窗口/浮动 pill：

- 当前页面通过 `AppNavigation` hook 直接注入宿主右侧内容区，不再创建 `JDialog` 或 Compose `Popup`；宿主侧栏和底部播放栏保留，页面占据中间内容区。无独立窗口、无拖动、无置顶。
- 右下角浮动 pill 已删除；唯一入口是 **模块管理页 → 插件配置 → 打开听歌统计**（`preference_config.json` 的按钮）。`MainPlugin.openStats` 现在设置 `StatsHostState.visible`。
- SaltUI 版本由宿主“更多”页确认为 **3.0.0-SNAPSHOT / CMP 1.12.1**；Maven 对应的发布版是 **3.0.0-beta02**。已下载并打包 `salt-ui-desktop-3.0.0-beta02.jar`、`salt-core-desktop-3.0.0-beta02.jar` 到插件 `lib/`，使用 `SaltTheme`、SaltUI `verticalScroll`、`Text` 和 `Card`。
- 滚动改为 SaltUI `rememberScrollState`/`verticalScroll`，宿主内嵌页可滚动到图表与听歌小结。
- 当前关闭/返回按钮是内容区左上角的返回箭头，和宿主“更多”页的返回位置一致；点击回到宿主原页面。

隔离配置实测（截图 `build/screenshots/embed_proto.png`、`embed_scrolled.png`，不提交）：页面确实嵌入宿主内容区，侧栏/播放栏未被遮挡；滚轮可滚动，图表 tooltip、听歌小结正常。**仍未在用户正常 Steam 启动中验收，且配置页入口的点击链路尚未由用户确认。**

SaltUI 已用于主题、滚动、文本和概览/错误卡片；热力图、部分按钮/分段控件和固定尺寸仍待迁移。后续按“逐步换成 SaltUI 组件”的确认继续，不再默认改布局。

## 0.1.4 按 preview 布局对齐

用户指出当前 UI 仍偏离示例，要求严格按 `preview/` 的布局实现。已按 CSS 逐项修一轮：

- 页面上边距按预览媒体查询：≥1450 为 68dp，>900 为 56dp，>600 为 34dp，其余 26dp。
- 排行搜索改为自定义 190×34 外框：10dp 内边距、15dp 图标、7dp 间距、148dp 输入区；不再使用 M3 `leadingIcon` 槽。实现受宿主混淆限制，使用 `TextFieldState` + `OutlinedTextField` 的 `contentPadding`，仍需真实渲染核对占位符和输入。
- 表格列宽改为预览的 48dp 排名 + 44% 歌曲 + 30% 专辑 + 剩余播放次数；表头上下 10dp、次数表头右对齐。
- 分页按钮高度 30dp、最小宽度 30dp；表头 section 最小高度 42dp；控制间距默认 12dp，时长分布为 9dp。
- 导航恢复预览的仅文字/图标悬停变色，不再出现灰色 Surface hover 块。
- 折线/曲线图改为预览的坐标：左 `max(52,label/2+6)`、右 `max(10,label/2+6)`、上 10、下 30；线/曲线首尾贴绘图区，柱状/小时仍用 banded 中心；移除逐点圆点，区域填充改为纯 `accentSoft`，补 4px 横轴刻度、平均线标签加粗。
- 排行榜/概览标题文字、主题图标、导入导出字号按预览调整；关闭按钮移到窗口右上角，页面 header 与预览一样只到“导出”。
- 听歌小结补 27dp 顶部 padding；常听时段数字恢复 26sp；热力图星期标签 10sp、选中框 1px、悬停更接近预览。
- 选的日期/自定义范围写回 Prefs，重开窗口不再只显示“所选日期”而丢失具体日期。

**0.1.4 的 UI 尚未在宿主中重新截图核对；上一轮实际渲染的是 0.1.2。** 本轮只做了构建、13 组数据回归、4 组几何回归、106 项包结构检查。搜索框是风险最高的改动，请用户在真实 SPW 中优先看它。

## 0.1.3 稳定性修复

在 0.1.2 基础上做了三项接手修复：

- `PlaybackAccumulator` 在插件异常退出后重新观察到同一首歌时，恢复 6 小时内未完成会话，不再为重启前后的片段各计一次；正常 stop/finalize 的会话不会恢复。该恢复不能区分“崩溃后继续同一遍”和“崩溃后重新开始同一首歌”，极端情况下可能合并两遍，需用户真实播放验证。
- `DataFiles.sourceId()` 遇到损坏的 `source-id.txt` 会重新生成，不再让每个 `ListeningEvent` 构造失败。
- `tests/run-data-tests.py` 修复了之前的临时输出目录/权限问题；新增重启恢复、load 后 open 状态、损坏 source-id 三组回归。

集中检查：构建通过，`tests/run-data-tests.py data` 13 组通过，`tests/run-data-tests.py ui` 4 组通过，`tests/spmod-structure.py` 通过。最新包为 `build/libs/plugin-com.jethuit.spw.listenstats-0.1.3.spmod`。

另外，本轮在独立 `APPDATA` 中实际启动了 0.1.2 统计页并截图核对：浅/深主题、概览/排行页签、日/周热力图、热力图日期选择、图表类型切换、页面滚动都能操作；这些是可见宿主渲染证据，但仍不是完整的鼠标回归，也没有验证真实播放、搜索、分页、导入导出。

## 0.1.2 UI 修订

用户提供真实 UI 截图，已针对提示框坐标、重复刻度、日周切换缓存、跨年空槽、按钮尺寸、日期标签、DPI 与滚动高度修订。详见 [UI-FIXES.md](UI-FIXES.md)。0.1.2 包为 `build/libs/plugin-com.jethuit.spw.listenstats-0.1.2.spmod`，此前 0.1.1 数据修订说明保留在下方。

0.1.2 已完成一轮检查：30 个 Kotlin 文件编译通过、4 组 UI 几何回归通过、包结构检查通过。包大小 14,661,972 字节。未重复扩大已通过的数据测试范围。

更新时间：2026-10-18。当前 0.1.4 为按 preview 的布局对齐；0.1.3 为接手后的稳定性修订；0.1.1 为数据正确性修订。旧版 0.1.0 曾由先前代理构建并在 SPW 1.19.0 测试环境中渲染；本轮不把交接文字或旧截图当作新版宿主验收证据。

## 用户确认

- 实际收听时间累计，每遍最多计一次；拖回重听可累计；暂停继续同一遍，自动循环/重新从头播放开始新一遍。
- 备份必须合并并去重。v1 缺会话 ID，用户同意兼容并提示可能少计；v2 使用会话 ID。
- 保持 `preview/` 基准，文件分类、轻量验证，一次完成后集中检查；Git 保存并推送当前分支。

## 本轮修复

1. 重写 `PlaybackTracker`：可独立测试的 `PlaybackAccumulator`，单调时间、实际收听片段、串行回调、异步查询代际保护。暂停/seek/休眠间隙不补记。
2. `LiveStore` v2：片段持久化、旧 v1 迁移、原子保存；宿主对应改一对一并保存别名；损坏原文件不覆盖。
3. 新增 `ListeningEvents`、`DataFiles`、`BackupCodec`：来源 UUID、事件快照幂等合并、v2 备份、必需校验和、32 MB 限制、输入校验；多次导入保留此前数据，写前备份、原子替换。
4. v1 汇总按每小时/每首次数取较大值，独立留在 v2 的 legacy 区域；不能宣称它精确去重。
5. 修复只有实时/导入数据时的空状态、排行翻页回顶、导入回第一页和同名同次数顺序；停用关闭窗口与封面缓存。
6. 宿主 DB 只读连接不再回退到可写连接；数据查询检查 library-read 权限。

## 路径

- 正式源码 `plugin/src/main/kotlin/com/jethuit/spw/listenstats/`
- 构建 `tools/build_plugin.py`，版本 0.1.19
- 产物 `build/libs/plugin-com.jethuit.spw.listenstats-0.1.19.spmod`
- 数据回归 `tests/DataRegressionTest.kt`、`tests/run-data-tests.py`（data / ui 两个 suite）
- 结构检查 `tests/spmod-structure.py`
- 详细设计和限制 `docs/NATIVE-IMPLEMENTATION.md`

## 集中验证

本轮集中检查已通过：30 个 Kotlin 文件编译成功；13 组数据回归通过；4 组 UI 几何回归通过；`.spmod` 结构检查通过（107 项，权限仅 `library-read` / `class-transform`，`lib/` 含 sqlite、SaltUI core/UI）。包约 17 MB。0.1.5 的内嵌页已在隔离配置中实际截图并滚轮滚动；配置页入口和正常 Steam 启动仍需用户验收。

## 启动崩溃报告

我们在沙箱测试启动时见过宿主弹出“崩溃报告”，堆栈是宿主 `PlaybackService` 写 `java.util.prefs` 时 `WindowsPreferences.openKey` 对 `HKCU\Software\JavaSoft\Prefs\com\xuncorp\voxzen` 返回 Access denied；**插件代码不在这个堆栈里**。这是测试启动器（受限 DSH 沙箱）导致的宿主环境问题，不是插件主动写注册表。正常 Steam 启动是否也会出现尚未确认；如果用户在本机正常启动也遇到，请先禁用插件复测，并把崩溃报告/时间发给新代理。不要为绕过它去申请更高权限或改宿主注册表。

本机 `python` 命中 WindowsApps 空别名，实际使用 `py -3.12` 完成检查；其他机器可用正常的 Python 3 命令。运行方式：

```powershell
py -3.12 tools/build_plugin.py
py -3.12 tests/run-data-tests.py data
py -3.12 tests/run-data-tests.py ui
py -3.12 tests/spmod-structure.py
```

测试用临时数据只在 `build/tests/`，不接触用户曲库、不启动 SPW。原来的 `build/tests/PlaybackTrackerTest.kt` 是旧代理的临时测试，不适用于当前单调时钟实现。

## 明确未完成

- 本轮未安装/启动 SPW 验收新版。真实播放、暂停、seek、循环、切歌和宿主写入后的去重必须在正常宿主中检查。
- 实时与宿主匹配仍依赖“同歌曲、估算开始相差 10 秒”，中途启用或此前暂停/拖动可能漏配/误配。当前来源 UUID 不能解决同一曲库删除重建后的 ID 变化。
- 宿主旧 Listen 仅开始时间/总时长；历史小时分配是近似。较长宿主记录取代实时快照时也可能失去精确片段分布。
- 新状态机在暂停/seek 后第一条回调建立基线，可能少计最多约一个回调周期；超过 3 秒的空档保守跳过。未知歌曲时长不推断次数。
- UI 仍为浮层 + 无边框独立窗口；Hook 硬编码 1.19.0；不是原生导航页，无法拖动/缩放。视觉与全量鼠标交互待用户核对。
- 旧代理交接提及拒绝启动宿主的经历，本轮没有通过另一启动方式继续该尝试；后续宿主操作应先确认用户希望如何验收。

## 下一步

优先在用户正常 SPW 中验收本版，并核对 live-sessions 和 Listen 对应关系；若发现开始时间无法可靠匹配，应研究宿主会话身份或清晰区分历史/新记录的数据来源，不能只扩大容差。不要重复改预览或把模拟检查写成真实播放通过。

`build/`、`.references/`、工具链、播放器安装文件、数据库、真实听歌记录和私人备份保持 Git 忽略。
