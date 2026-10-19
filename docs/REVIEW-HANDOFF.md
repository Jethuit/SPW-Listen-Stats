# 0.1.33 Bug 审查交接

更新时间：2026-10-19。用户要求暂停当前审查，换用 6.1-Sol 接手。本文记录审查进度，不是已完成的缺陷报告。

## 用户当前要求

- 功能基本完成，请审查 Bug；当前只审查，不修改正式代码。
- 用户关注额度消耗，避免重复读取整份历史文档、重复构建或扩大测试范围。
- 继续遵守 AGENTS.md：轻量验证，相关工作集中进行一轮；视觉效果交由用户核对。

## 仓库状态

- 工作目录：`E:\Code\spw status`
- 分支：`codex/listening-stats`
- 当前代码 HEAD：`1d80f55`，`fix: make the pagination arrows transparent`。
- 当前版本：0.1.33。
- 用户有未跟踪文件 `preview/ai_studio_code (2).html`，不要删除或擅自提交。
- 本次审查未修改正式代码，未启动 SPW，未执行构建或回归测试，未创建复现脚本。

## 已读取的范围

已读 AGENTS.md、HANDOFF.md 最新版本记录、GPT-HANDOFF.md 开头，以及相对 `1387748`（0.1.21）的变更概览。旧交接有过时版本号，当前状态优先看源码和最新提交。

已重点读过：

- `plugin/src/main/kotlin/com/jethuit/spw/listenstats/ui/DateRangeDialog.kt`：双卡独立日期选择、焦点、键盘、响应式布局。
- `ui/RankingPage.kt`：搜索、稳定行 key、封面重试、底栏分页及跳页。
- `ui/Components.kt`：自绘 SelectBox Popup、宽度、位置与菜单项。
- `ui/StatsWindow.kt`：数据刷新、分页滚动保持、日期弹窗挂载。
- `ui/OverviewPage.kt`、`ui/YearHeatmap.kt`、`ui/Drawing.kt` 相关逻辑。
- `data/CoverStore.kt` 全文、`data/StatsCalculations.kt` 增量。
- `tests/UiRegressionTest.kt`：仍主要覆盖字体、分页数量、提示坐标、刻度、年度汇总、午夜标签，未覆盖新的日历交互及封面并发取消。

以上 ui/、data/ 简写均相对 `plugin/src/main/kotlin/com/jethuit/spw/listenstats/`。

## 优先核实的问题（尚未运行复现）

### 1. 取消封面请求会污染失败冷却——高可信源码问题

`CoverStore.load()` 最外层 `catch (failure: Throwable)` 不区分 CancellationException，一律写入 `failedAt[trackId]`，并把共享 promise 完成为 null。

`fetchAndDecode()` 虽然重抛取消，但随后仍被外层捕获。翻页、离开排行榜、搜索过滤或 DPI 变化取消行组件的 LaunchedEffect 时，正常取消可能被标记为读取失败。返回原页后，同一曲目在 30 秒内直接返回 null。这可能解释用户感知的封面加载慢。

建议最小复现：模拟 WorkshopApi.Library.getCoverById 返回未完成 CompletableFuture；启动 load，等待进入 API 后取消协程；立即再次 load 同一曲目，检查 API 调用次数是否仍为 1。无需真实曲库、图片解码或启动 SPW。

相关接口已通过本地 javap 确认：`WorkshopApi.Library.getCoverById(String): CompletionStage<byte[]>`。可参考 DataRegressionTest 内已有的 WorkshopApi 动态代理做法。

### 2. 封面 clear 未隔离旧请求——待独立核实

`CoverStore.clear()` 只清空 cache/cacheBytes/failedAt，没有取消或作废 inflight，也没有 generation 保护。正在运行的旧请求可能在 clear 后再次写缓存或失败冷却，新请求也可能加入旧 promise。

需要结合 MainPlugin.stop 的调用顺序确认实际影响；不要未经复现就宣称发生了资源泄漏。

### 3. 封面只自动重试一次——源码明确，影响需描述准确

`RankingPage.Cover()` 中只有 `image == null && retry == 0` 才延迟 30 秒并递增 retry。第二次仍失败后，不会继续自动重试，除非组件重新进入组合或 key 改变。这与交接中“自身每 30 秒重试”的表述不同。

暂时失败超过一次时，用户停留当前页可能一直看到占位图。与第 1 项可合并评估，避免重复报告同一用户影响。

### 4. 日期对话框键盘焦点/Enter 处理——高可信结构问题，未实机验证

`rootFocus` 绑定到遮罩 Surface，但 Esc/Enter 的 onPreviewKeyEvent 放在其后一个兄弟 Box 上。初始焦点若落到遮罩，事件不经过兄弟 Box，可能无法按 Esc 关闭。

焦点进入卡片内部后，上层预览事件又会无条件把 Enter 转为 submit。因而焦点在“取消”、日期格或翻月按钮上时，Enter 可能直接确认范围，抢走控件本来的键盘操作。还应检查是否存在模态焦点约束，避免 Tab 跑回底层页面。

### 5. 窄窗口/大字体切成上下双卡后没有纵向滚动——高可信布局风险

DateRangeDialog 在内容宽不足 560dp 或 fontScale > 1.15 时将两张卡片上下排列；每张固定绘制六周。弹窗内容 Column 没有滚动，也没有基于可用高度的布局处理。在高度较小或高 DPI 环境中，下方摘要、取消/确定可能被挤出或裁切。

应在受限高度下验证按钮是否始终可达，不能只验证普通宽屏并排状态。

## 次要线索（不要未核实就写成确定 Bug）

- SelectBox 使用 `minOf(triggerWidthDp, naturalMenuWidth)`，菜单可能被较短触发文本限制，导致较长选项截断。需要结合实际字体测量确认；目前没有证据证明特定默认选项必然截断。
- DateRangeDialog 年份下拉仅 `2025..当前年`，但翻月箭头没有范围限制，日期格也没有禁用未来日期。先核对用户后续是否明确要求这个年份范围，再评价，不能把旧方案建议直接当已确认需求。
- 分页底部保持使用 `delay(60)` 后 scrollTo(maxValue)，不是明确等待布局完成；在较慢渲染时可能有竞态，但尚未复现。
- `ViewportRevisionSource` 以滚动位置除以 48 分桶，小于一个桶的滚动不会清理旧提示。实际影响需核对，不要仅凭分桶就断言严重卡顿。
- `SelectBox` 调用 KeyboardArrowDown 时未显式传入 AppIcon 的 24 viewBox/正坐标参数，而日期箭头有传；需要核对 Icons 路径格式再下结论。

## 下一步建议

1. 优先对封面取消冷却做一条隔离复现，合并评估重试和生命周期问题。
2. 针对日历的键盘事件与受限高度形成具体触发步骤；区分源码判断与实机证据。
3. 按需看尚未覆盖的数据边界，不重复审阅已稳定且本轮未改的数据模块。
4. 若运行检查，集中执行一轮并清楚说明覆盖范围；不要为审查而默认构建、安装或操作用户播放器。
5. 最终按严重程度给出可操作发现、源码位置和复现条件；用户未要求修复前不要直接改正式代码。

本机使用 `py -3.12`；`python` 可能命中 WindowsApps 别名。隔离检查产物放 `build/tests/`，正式检查脚本放 `tests/`。工具链在 `build/tools/`，宿主 jar 为 `build/tools/spw-host.jar`。不要提交工具链、宿主文件、真实历史或私人备份。
