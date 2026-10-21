# GPT 交接文档：听歌统计

> **2026-10-21 导航与动画源码已实现，未打包/发布。** 顶部横排采用原生侧栏风格的圆角浅底；用户后续指定蓝条位于按钮底部中央，最新宽度为按钮的 80%、高度 3dp，取代 20dp 短条及左侧方案。深色原生样例底色 `#313232`、蓝条 `#53C4FF`。整体入口上浮淡入、概览/排行仅主体左右切换，标题导航固定。42 文件编译、8 组 UI、7 组导航与浅深两套离屏动画检查通过；底部蓝条调整后仅集中重编译及离屏检查一次，通过。详见 [NAVIGATION-MOTION-2026-10-21.md](NAVIGATION-MOTION-2026-10-21.md)。真实宿主观感待核对，公开版本及既有安装包仍为 v0.1.5。

> **2026-10-21 存在正式 v0.1.5 之后的修复源码，未打包、未发布。** 用户授权修复审查确认问题；细节和限制见 [REVIEW-FIXES-2026-10-21.md](REVIEW-FIXES-2026-10-21.md)，最新状态见 [HANDOFF.md](HANDOFF.md)。41 文件编译、83 组回归及格式/Manifest 检查通过；只保存本地源码，不更改版本或既有正式包，不上传/安装，不宣称真实宿主已验收。首次换曲仍保留身份确认，宿主自附加 Agent 路径问题未修复。

> **当前正式版 v0.1.5 已发布并设为 Latest。** [发布页面](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.5)，Release ID `418360748`，标签目标 `51265cc88460384049745709ec2085543c329b48`。唯一附件 `SPW-Stats-v0.1.5.spmod`（17,253,804 字节），GitHub 摘要与本地一致。main／工作分支和 README 已同步，回执 `build/releases/published-v0.1.5.json`。已确认原 v0.1.4 保持用户设置的预发布状态，标签与原附件均未替换；下方禁止发布／待发布均为历史记录。未运行真实 SPW。

> **v0.1.5 正式发布准备完成。** 用户最终决定独立升至 v0.1.5，不替换原 v0.1.4；GitHub v0.1.4 原标签／附件／预发布状态均应保留。正式包 `build/releases/v0.1.5/SPW-Stats-v0.1.5.spmod`（17,253,804 字节、162 项），41 文件编译、包结构／ZIP 完整性通过，与已通过 45 组回归的 dev.1 仅 manifest 版本不同。README 和发布说明已更新，公开结果以 `build/releases/published-v0.1.5.json` 为准。

> **最新开发版 v0.1.5-dev.1，禁止把本轮修复视为已发布。** 用户转交 Astra 四项反例，已修复并在本地构建。Lord Howe 分桶恢复严格前进；崩溃恢复同会话精确片段求并集并保存 preciseSpans；宿主关联重新评估并撤销错误 aliases／防 stale upsert 复活；暂停后生命周期分片利用持久化播放位置、生命周期 ID 与兼容推断匹配。41 文件编译、新增 9 组及既有 18+11+7 共 45 组通过，包结构通过。包 `build/libs/SPW-Stats-v0.1.5-dev.1.spmod`（17,253,807 字节、162 项）。未安装、未读取真实历史、未上传／发布。详见 [BUGFIX-v0.1.5.md](BUGFIX-v0.1.5.md) 和 `tests/AstraRegressionTest.kt`；公开版仍为 v0.1.4，离线身份不明及已丢原始证据的旧错误备份不能保证自动修复。

> **v0.1.4 已正式发布并设为 Latest。** [发布页面](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.4)，Release ID `418260534`，标签目标 `de9d8522e9b784e64fda920c002c65d5142d70cf`，GitHub `main` 与工作分支已同步。唯一附件 `SPW-Stats-v0.1.4.spmod`（17,242,562 字节），GitHub SHA-256 与本地一致，回执 `build/releases/published-v0.1.4.json`；无需再执行下方发布准备。未重新操作真实宿主。

> **v0.1.4 正式发布准备完成。** 用户授权发布；正式包 `build/releases/v0.1.4/SPW-Stats-v0.1.4.spmod`（17,242,562 字节、159 项）。41 文件编译、包结构／ZIP 检查通过，逐项比对 dev.11 仅 manifest 版本不同，沿用既有验证。README 与发布说明已更新。发布目标为 GitHub 正式 Latest，公开结果以回执 `build/releases/published-v0.1.4.json` 为准。

> **最新 dev.11 已构建。** 包 `build/libs/SPW-Stats-v0.1.4-dev.11.spmod`（17,242,566 字节、159 项）；椒盐经典三榜悬停按原版取样改为浅色 `#E8E8E8`、深色 `#363637`，图表圆点改为实心蓝点，取代之前空心透明要求。41 文件编译、8 组 UI、包结构／版本／ZIP 完整性及实心标记源码检查通过。未安装／运行真实 SPW、未上传或发布；最终观感由用户核对。

> **dev.10 已构建并检查。** 最新包 `build/libs/SPW-Stats-v0.1.4-dev.10.spmod`（17,242,558 字节、159 项），含下列曾未打包的浅色云母／云母 Alt 悬停、透明圆点、配置说明和分钟四舍五入。41 文件编译、8 组 UI、新构建二进制的格式边界检查、包结构／ZIP 完整性及文案检查通过。未安装或运行真实 SPW，未上传、未正式发布。下方“未打包”均为构建前历史记录；实际观感待用户验收。

> **最新补充：时长显示按分钟四舍五入，未打包。** 用户明确不足 30 秒舍去、满 30 秒进位，不能当作每 30 秒计一分钟。统一在最终显示总量上舍入：1 分 29 秒→1 分钟，1 分 30 秒→2 分钟，59 分 30 秒→1 小时。Format.kt 三种时长显示共用规则；记录、图形高度、颜色档位及次数继续使用原始数据。轻量格式回归入口为 `py -3.12 tests/run-data-tests.py format`；先前“提示按整分钟截断”的说明已被本次要求替代，用户仍要求不构建 `.spmod`。

> **dev.9 后续源码，尚未编译或打包。** 浅色云母／云母 Alt 的列表悬停改为 8% 黑色透明覆盖，对照原版截图分别约为 `#E1E1E1`／`#DADADA`；图表圆点去掉实心背景，只保留蓝色描边。模组配置摘要补充每日热力图颜色梯度的作用说明。用户要求先不构建，旧 dev.9 包不含本次修改。每小时没有 59 分钟上限；提示按整分钟截断，满 60 分钟显示“1小时”，未调整计时或舍入规则。

> **dev.9 已构建并检查。** 包 `build/libs/SPW-Stats-v0.1.4-dev.9.spmod`（17,242,459 字节、159 项），41 文件构建、6 组设置回归、8 组 UI、同一实际热力图默认／6 小时门槛动态重绘合成和包结构／配置 JSON 检查通过。输入默认 `2.25` 小时，只有每日颜色受影响；模拟配置检查覆盖刷新、重初始化、非法值及不覆盖其他设置。未运行真实 SPW，原生配置输入框实际显示与操作需用户核对，未上传或发布。

> **最新源码 v0.1.4-dev.9：新增每日热力图时长配置。** 用户确认按最高档门槛的 1/6、1/2、完整值分档，默认门槛为 `2.25` 小时；输入 6 时分界 1／3／6 小时。配置页新增宿主 edittext “每天听多久算多（小时）”，保存键 `dailyHighHours`，支持小数，非法输入按默认值显示。每日热力图使用配置重绘，其他数据与周视图不变。页面初始化和约 5 秒刷新时重读宿主配置；Prefs 保存其他选项前先重载，防止缓存覆盖用户新设置。目标包 `build/libs/SPW-Stats-v0.1.4-dev.9.spmod`，未上传或发布，详见 [HEATMAP-v0.1.4.md](HEATMAP-v0.1.4.md)。

> **dev.8 构建完成。** 包 `build/libs/SPW-Stats-v0.1.4-dev.8.spmod`（17,238,859 字节、158 项），含三种深色材质校准。40 文件构建、8 组 UI、3 张深色实际卡片合成、24 张四材质排行布局及 9 张深色实际指针悬停合成、包结构检查通过；参考背景上卡片／边框／悬停与原版每通道误差 ≤1。无分隔线和蓝色提示继续保留。未运行真实 SPW，未上传、未发布，最终观感由用户实机核对。

> **当前源码 v0.1.4-dev.8：已补上深色云母 Alt，本轮统一构建。** 用户这次原版截图中，云母 Alt 背景 #272727、卡片 #444444、边框 #616163、列表悬停 #515153，与上一轮深色云母取样相同；已接入三种深色材质共用覆盖层。新包目标 `build/libs/SPW-Stats-v0.1.4-dev.8.spmod`，包含前一轮未打包的深色亚克力／云母校准。浅色、经典、无分隔线布局和蓝色提示保持现状，仍始终跟随 SPW，未上传或正式发布。

> **dev.7 之后有未打包源码：深色亚克力／云母。** 用户提供八张深色原版／插件对比图，要求先不构建，下一轮云母 Alt 改完再统一构建。深色 WindowsAcrylic／WindowsMica 已用半透明覆盖校准卡片与控件、边框、文字、空白热力格和排行悬停；在参考背景 #505050／#272727 上，卡片目标 #5F5F5F／#444444，悬停 #676769／#515153。页面仍透明、无分隔线、蓝色提示保留；浅色、经典与云母 Alt 未改。只做源码及取样合成数值检查，未编译、渲染、安装或打包；现有 dev.7 包不含该改动。

> **最新源码：v0.1.4-dev.7，移除灰色分隔线。** 用户撤回之前的底部分隔线和 `#B1B1B1` 方案，要求四材质的概览／排行删除灰色线条，包括截图中的标签栏下横线，保留蓝色选中提示线。已去除导航／排行表头／排行末尾／概览页脚横线和统计／小结卡片内部竖线，原间距、蓝色提示线、卡片外框与图表网格保留。separatorLine 字段及覆盖配色已移除；始终跟随 SPW、材质与统计逻辑保持现有实现。新包 `build/libs/SPW-Stats-v0.1.4-dev.7.spmod`，未上传或发布；下文旧线色与保留底线要求均已撤回。

> **当前已构建 v0.1.4-dev.6，未上传、未发布。** 用户授权编译当前源码，收录下列原先未打包的云母／云母 Alt `#B1B1B1` 分隔线及概览内部竖线修改。`#E1E1E1` 只是取样问答，未被要求替换到源码。构建 40 文件、8 组 UI、24 张四材质／浅深／三排行实际组件合成及包结构检查通过。包 `build/libs/SPW-Stats-v0.1.4-dev.6.spmod`，17,239,739 字节、158 项。保留始终跟随 SPW、无行间线／仅列表末尾线和所有数据修复；未安装真实宿主，观感待用户核对。

> **最新未打包补充：云母 Alt 及概览分隔线。** 用户确认云母 Alt 也使用 `#B1B1B1`，概览分隔线一并调整。浅色 WindowsMica／WindowsMicaAlt 共用独立分隔色；页面导航、页脚、排行表头／末尾及累计统计／听歌小结卡片内部竖线已接入。卡片外框、图表网格、深色主题与其他材质不改。仍仅源码修改和差异检查，未编译、渲染或打包，原 dev.5 安装包不含这些改动。

> **当前存在 dev.5 之后的未打包源码修改。** 用户明确选择原版云母拖放框虚线的 `#B1B1B1` 灰色（另一选项是卡片边框 `#E4E4E4`），用于浅色云母水平分隔线。新增 separatorLine，页面导航／排行表头／排行末尾／概览页脚线使用它；卡片、按钮边框、图表网格、深色云母与其他材质不受影响。按用户要求未编译、未打包，仅源码检查；现有 dev.5 安装包不包含此改动。云母 Alt 尚未修改，等待用户下一步参考。

> **最新源码：v0.1.4-dev.5，未上传、未发布。** 用户最新要求取代下文旧主题约定：删除手动浅／深切换与按钮，永远跟随 SPW 宿主。已移除 ThemeMode、图标、旧主题偏好读取／保存及启动属性覆盖；宿主明确浅／深优先于系统，宿主跟随系统时才用系统色。四种材质的歌曲／歌手／专辑排行统一无行间线，只在当前页列表末尾、分页统计栏前加一条全宽分隔线，原行距保留。包 `build/libs/SPW-Stats-v0.1.4-dev.5.spmod`，继续包含所有前序修复；最终观感仍需用户实机核对。

> **最新源码：v0.1.4-dev.4，未上传、未发布。** 亚克力三种排行去掉行间分隔线、保留表头线与行距；浅色行悬停匹配用户原版失焦截图的 `#C2C2C2`（背景 `#DDDDDD`），用独立的 12% 黑色覆盖保留聚焦变化。深色亚克力只去行线；其他材质／手动主题沿用上一版。40 文件构建、8 组 UI、三种排行六张正常／实际指针悬停合成及包结构检查通过。开发包 `build/libs/SPW-Stats-v0.1.4-dev.4.spmod`，17,247,365 字节，最终实机观感由用户核对。保留下列开发版数据修复与材质功能。

> **当前源码：v0.1.4-dev.3，未上传、未发布。** 用户要求先逐项校准亚克力，另外两种材质随后处理。dev.3 按用户的原版聚焦／失焦实机图校准浅色亚克力卡片、边框、文字、控件和空白热力格；失焦卡片目标 `#E2E2E2`、边框 `#C7C7C7`，使用低透明度覆盖自然继承宿主失焦背景。云母、云母 Alt、深色亚克力沿用 dev.2，手动浅／深保持纯色。包 `build/libs/SPW-Stats-v0.1.4-dev.3.spmod`，保留 dev.1 两项数据修复和 dev.2 原导航包装层。dev.2 的真实截图已显示背景及失焦变化，但新颜色仍待用户实机核对，不能把合成检查当作 DWM 验收。详情见 [MATERIAL-v0.1.4.md](MATERIAL-v0.1.4.md)。

> **v0.1.4-dev.1 数据修复记录。** 已按用户转来的两项 P2 报告复现并修复：观察到短暂 B 即结束旧 A 会话，再单独判断位置采样；本地身份判断覆盖主 ID 与宿主别名，避免连续导入旧备份后丢失曲库归属。旧版二进制已复现两例；修复后 39 文件构建、18 组数据＋11 组完整性＋7 组排行共 36 组回归及包结构检查通过。包 `build/libs/SPW-Stats-v0.1.4-dev.1.spmod`（17,233,781 字节）。使用模拟宿主，未运行真实 SPW，未操作真实数据；不自动拆分缺少可靠分界的旧版历史。详情见 [BUGFIX-v0.1.4.md](BUGFIX-v0.1.4.md)。

> **最新发布：v0.1.3，已正式公开并设为 Latest。** [Release](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.3)（ID `417903045`）已按用户授权发布，标签目标 `e2600782f59b215ded1fb66942daa84509edc8d4`；源码与 README 已同步 `main` 和工作分支。附件仅 `SPW-Stats-v0.1.3.spmod`，17,233,390 字节，GitHub 摘要与本地一致。正式包与 dev.4 逐项比对，只有插件版本描述变化，代码、资源、依赖相同。README 不放更新日志或开发信息；简短日志见 [releases/v0.1.3.md](releases/v0.1.3.md)。发布回执为本机 `build/releases/published-v0.1.3.json`。

> **v0.1.3 开发阶段验证记录。** dev.4 歌手封面改用该歌手最高计次专辑封面（专辑内歌曲计次相加）；三榜统一 48dp 封面、约 66dp 行距和 12dp 图文间距，参考用户原版截图。39 文件构建、7 组排行回归及 12 张原生预览尺寸检查通过，包结构检查通过（17,233,394 字节）；实际 SPW 封面读取与视觉待用户核对。 dev.3 新增歌曲 / 歌手 / 专辑三种排行，按用户选择汇总有效播放次数，合作歌曲分别计入每位歌手；复用原有分组按钮并适配搜索、分页和窄屏。 dev.2 将单天时长分布纵轴固定为 0–60 分钟，三种图形和单位说明保持一致，多天自动刻度不变。继续包含 dev.1 的四项数据修复：生命周期片段去重、回调身份校验、有效元数据保留、来源 UUID 恢复和旧别名迁移（dev.1 的 18 组原数据回归＋9 组新增完整性回归全部通过）。已同步 `main` 的用户 README 提交 `139e1b0`。本地包 `build/libs/SPW-Stats-v0.1.3-dev.4.spmod`；未运行真实 SPW 播放，也未操作真实数据。详情与近似匹配/丢弃不确定采样的边界见 [BUGFIX-v0.1.3.md](BUGFIX-v0.1.3.md)。开发阶段当时公开最新版为 v0.1.2。

> **历史发布：v0.1.2。** 用户已在 dev.2 验收后恢复发布授权，[Release](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.2) 已完成（ID `417473230`）。标签目标 `e1b6406fd25c24dda1253062cb62f5ed032e845a`；`main` 与工作分支已同步。附件仅 `SPW-Stats-v0.1.2.spmod`，17,193,719 字节，远端摘要与本地一致。代码、资源和依赖均与已确认 dev.2 一致，仅转换版本描述。功能为顶部入口与官方卡片/提示框配色；说明见 [UI-v0.1.2.md](UI-v0.1.2.md)。本机回执 `build/releases/published-v0.1.2.json`。

> **历史发布：v0.1.1。** 用户于 2026-10-19 授权更新 GitHub，[Release](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.1) 采用其提供的四条更新日志。源码与 README 已同步 main；附件仅 `SPW-Stats-v0.1.1.spmod`，17,180,687 字节；标签对应 `9547491dda859cc193d60e572cd870b6dc61ca6e`。导航、返回箭头、自定义日期按钮与窄屏布局修复统一纳入此版本。技术说明见 [UI-v0.1.1.md](UI-v0.1.1.md) 和 [BUGFIX-0.1.1.md](BUGFIX-0.1.1.md)。

> **v0.1.1 验证边界：** 构建、导航回归、离屏尺寸与渲染、安装包结构检查通过；完整宿主交互的实机检查仍以用户反馈为准，发布状态不代表所有宿主场景均已实测。

> **首发记录：v0.1.0。** 用户于 2026-10-19 授权公开，随后明确转为正式版。[首发 Release](https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v0.1.0) 保留；当前 Latest 为上方 v0.1.2。仓库 topic 为 `salt-player-for-windows`，已补充简短简介。首发基于开发版 0.1.35 整理。发布说明见 [releases/v0.1.0.md](releases/v0.1.0.md)，验证与附件见 [releases/PREPARATION-v0.1.0.md](releases/PREPARATION-v0.1.0.md)。
>
> 最终对外名称统一为 **听歌统计**，入口为“打开听歌统计”，安装包为 `SPW-Stats-v0.1.0.spmod`。三张实际效果图只放 README；Release 简短且不放图，附件仅安装包，不发布 `SHA256SUMS.txt`。保留“椒盐经典”推荐，插件 ID 保持。
>
> **0.1.32：** 跳页输入框数字居中、34dp 高，分页按钮/箭头/跳页框统一 8dp 圆角。
>
> **2026-10-19 更新：当前源码 0.1.21。** 控件底色及宿主字体接入见 [UI-0.1.21.md](UI-0.1.21.md)。用户实机反馈已证明 0.1.20 的字体继承不足；当前通过读取宿主字体策略和实际字体资源修正，视觉待用户核对。

> **2026-10-19 更新：0.1.20。** 已处理审查发现的六项问题，当前变更及验证状态以 [BUGFIX-0.1.20.md](BUGFIX-0.1.20.md) 为准；下文 0.1.19 版本、提交和检查数字保留为上一轮背景。仍不能把离线回归视为真实播放或宿主字体/交互验收。

> 这是给接手 GPT 的**单一入口文档**。先读本文件，再按需读 `docs/NATIVE-IMPLEMENTATION.md`、`docs/HANDOFF.md`、`docs/UI-0.1.22.md`。
> 最后更新：2026-10-19；首个正式版本 **0.1.0**；发布分支 `main`，工作分支 `codex/listening-stats`。此前 0.1.35 等编号是开发阶段版本。

## 0. 一句话状态

一个给 Salt Player for Windows（SPW）1.19.0 用的听歌统计模组，已经完成：宿主内容区内嵌页、真实听歌数据读取、50% 计次、跨日/跨小时分配、持久化、封面、导入导出、SalT UI 接入、主题跟随 SPW。当前进入**用户视觉验收和收尾阶段**，真实播放/暂停/seek 仍需在正常 Steam 启动中做最终验收。

## 0.1.22 当前增量（优先于下文旧状态）

- **封面性能**：后台生成 44dp 方形缩略图、12MiB LRU、并发 4（基线 3）、相同请求合并、稳定行 key、组件自身 30 秒重试；移除全局滚动重组源。隔离实测 25 首：wait 0–355ms、API 多为 3–15ms（个别 244ms）、decode 21–160ms、UI 约 300–562ms；瓶颈是并发排队。**未测滚动 FPS、内存、缓存命中返回**，不能声称“不卡顿已解决”。
- **日期/菜单**：`DateRangeDialog` 起始/结束两张独立卡片，各自年月选择+箭头，左只改开始、右只改结束；不自动交换，起>止提示并禁用确定；底部已选天数含首尾；窄宽上下排列。`SelectBox` 自绘 Popup，菜单宽按最长选项测量、最小 144dp，外框 6dp 留白、选项背景内缩、文字 10dp、行高 34dp，年份菜单限高滚动。
- **排行底栏**：删除标题下摘要；底部固定“共 xx 首 · 听满单曲 50% 计一次”，数量随筛选；右侧分页。
- **顶部入口实现（2026-10-19）**：用户指定 Quick 左侧红框并提供 SVG，随后授权实现。`StatsTitlebarEntry` 在 `DebugCaptionButton` 调用后追加，局部反射桥复用原生 `BasicIconCaptionButton`（40×32dp），SVG 20×20dp，点击 `StatsHostState.open()`；`MainPlugin` 单独持有/清理句柄，注册失败保留主功能。测试版 `0.1.2-dev.1` 已构建，包在 `build/libs/`；38 文件编译、3 种缩放原生离屏渲染/点击/移除再启用、7 组导航回归及包检查通过。脚本 `tests/check-titlebar-entry.py`；详见 [TOPBAR-ENTRY.md](TOPBAR-ENTRY.md)。尚未实际安装/注入完整 SPW 验收；未上传、未发布，v0.1.1 未改。
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
| Acrylic/Mica/云母 Alt | dev.2 初步接入，待实机 | 仅跟随 SPW，手动浅深保留经典色；见 MATERIAL-v0.1.4.md |
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
