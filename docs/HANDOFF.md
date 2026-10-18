# 新聊天交接

更新时间：2026-10-18。当前 0.1.1 为接手后的数据正确性修订。旧版 0.1.0 曾由先前代理构建并在 SPW 1.19.0 测试环境中渲染；本轮不把交接文字或旧截图当作新版宿主验收证据。

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
- 构建 `tools/build_plugin.py`，版本 0.1.1
- 产物 `build/libs/plugin-com.jethuit.spw.listenstats-0.1.1.spmod`
- 数据回归 `tests/DataRegressionTest.kt`、`tests/run-data-tests.py`
- 结构检查 `tests/spmod-structure.py`
- 详细设计和限制 `docs/NATIVE-IMPLEMENTATION.md`

## 集中验证

本轮集中检查已通过：29 个 Kotlin 文件编译成功；10 组数据回归通过；`.spmod` 结构检查通过（106 项，仅 library-read / class-transform 权限）；`git diff --check` 通过。包大小 14,656,830 字节。本轮没有启动或安装到 SPW。

本机 `python` 命中 WindowsApps 空别名，实际使用 `py -3.12` 完成检查；其他机器可用正常的 Python 3 命令。运行方式：

```powershell
py -3.12 tools/build_plugin.py
py -3.12 tests/run-data-tests.py
py -3.12 tests/spmod-structure.py
```

测试用临时数据只在 `build/tests/data-regression-*/`，不接触用户曲库、不启动 SPW。原来的 `build/tests/PlaybackTrackerTest.kt` 是旧代理的临时测试，不适用于当前单调时钟实现。

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
