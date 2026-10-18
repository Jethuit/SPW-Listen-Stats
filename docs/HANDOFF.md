# 新聊天交接

## 最新：0.1.4 按 preview 布局对齐

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
- 构建 `tools/build_plugin.py`，版本 0.1.4
- 产物 `build/libs/plugin-com.jethuit.spw.listenstats-0.1.4.spmod`
- 数据回归 `tests/DataRegressionTest.kt`、`tests/run-data-tests.py`（data / ui 两个 suite）
- 结构检查 `tests/spmod-structure.py`
- 详细设计和限制 `docs/NATIVE-IMPLEMENTATION.md`

## 集中验证

本轮集中检查已通过：30 个 Kotlin 文件编译成功；13 组数据回归通过；4 组 UI 几何回归通过；`.spmod` 结构检查通过（106 项，仅 library-read / class-transform 权限）。包大小约 14.67 MB。0.1.4 未在宿主中渲染，0.1.2 的统计页渲染/点击已在独立配置中截图核对；新版 UI 需用户核对。

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
