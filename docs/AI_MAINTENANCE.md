# SPW-Listen-Stats AI 维护指南

本文是面向后续 AI 代理、自动化代码审查和长期维护工作的项目入口。它把当前仍然有效的产品约束、代码结构、数据不变量、验证边界和性能证据集中起来。

本文不能替代用户确认的需求。发生冲突时，优先遵循用户当前指令，再遵循 `AGENTS.md`，最后参考本文和历史发布说明。历史发布说明用于解释变更背景，不能覆盖 `docs/REQUIREMENTS.md` 的当前要求。

## 先读什么

开始工作前按以下顺序读取：

1. 根目录 `AGENTS.md`：协作、版本、文件和验证限制。
2. `docs/REQUIREMENTS.md`：当前已经确认的产品行为和统计口径。
3. `plugin/README.md`：构建、宿主兼容性和运行限制。
4. 与改动直接相关的 Kotlin 源码和测试。
5. 需要追溯历史时再读取 `docs/releases/` 和 Git 历史。

`README.md` 由用户维护。除非用户明确要求，不要修改它。

## 项目定位和当前状态

SPW-Listen-Stats 是 Salt Player for Windows 的 Kotlin / Compose 模组，统计真实观察到的收听片段，并通过宿主 Hook 将统计页嵌入播放器内容层。

版本判断以 `tools/build_plugin.py` 的 `PLUGIN_VERSION` 和实际构建产物为准，不以文档标题中的旧版本文字为准。当前正式包版本是 `0.3.4`；开发构建必须使用没有发布过的独立版本，例如 `0.3.5-dev.1`。禁止用相同版本号覆盖正式安装包、发布附件或 Git 标签。

当前工作区可能包含用户未提交的功能修改、测试素材和调查产物。开始工作时先运行 `git status --short --branch`，保留这些修改，不要 reset、checkout、清理或覆盖不属于本次任务的文件。

## 数据流和主要模块

```text
播放回调
  -> PlaybackTracker / PlaybackAccumulator
  -> LiveStore（实时会话和精确片段）

宿主 Listen / Track 数据库
  -> HostDatabase（只读连接、data_version、rowid 增量读取）

imported.json
  -> BackupService / BackupCodec

HostDatabase + LiveStore + BackupService
  -> ListeningEvents.union / aggregate
  -> StatsData
  -> StatsSnapshotSession / StatsSnapshots
  -> StatsWindow / OverviewPage / RankingPage / 图表
```

主要职责：

- `MainPlugin.kt`：插件生命周期、权限、播放记录初始化、宿主 UI 接入和释放。
- `PlaybackTracker.kt`：把播放状态、位置和单调时钟转换为实际收听片段。
- `LiveStore.kt`：保存实时会话，维护宿主记录关联、别名和拒绝关联。
- `HostDatabase.kt`：读取宿主 SQLite，处理文件身份、`data_version`、追加游标和一致性回退。
- `BackupService.kt` / `BackupCodec.kt`：导入、导出、校验、备份合并和旧格式兼容。
- `ListeningEvents.kt`：事件 Union、精确片段与近似宿主快照合并、日／小时聚合和有效播放次数。
- `StatsStore.kt`：将宿主、实时和备份数据合并成统计快照。
- `StatsSnapshots.kt`：一次启用会话内的预读、最近成功快照和失败保留。
- `HostBytecodeIndex.kt` / `HostUiDiscovery.kt` / `HostUiIntegration.kt`：宿主结构发现和 Hook 生命周期。
- `StatsWindow.kt` / `RankingPage.kt` / `OverviewPage.kt`：统计页面和排行榜展示。
- `CoverStore.kt`：封面缓存、并发解码和失败冷却。

## 不能破坏的数据不变量

修改统计或合并逻辑时必须保持以下规则：

- 统计的是实际观察到的收听时间；暂停、停止、跳转和未观察到的启用前时段不能被补算。
- 片段按本地自然日和小时拆分，跨日、跨小时和时区边界必须保持原有结果。
- 达到歌曲时长 50% 才计一次有效播放；每遍播放最多计一次。
- 同一会话的精确片段使用区间并集；宿主近似时长只能补足精确证据未覆盖的区间，不能覆盖精确位置。
- 实时会话、宿主记录和备份事件的身份不能仅凭歌名合并；来源 ID 和会话 ID 必须保留。
- 本机已拒绝的宿主关联在备份导入、聚合和导出前都必须继续过滤。
- 当前曲库的有效元数据优先；实时和本机信息次之；备份只补缺失字段。
- 明确存在路径且文件已删除的歌曲从统计视图隐藏；没有路径的旧数据仍保留。不能删除宿主数据库、实时记录或备份。
- 导入和导出必须在独立文件对话框线程运行，备份读写使用 IO 调度器；异常要写入诊断日志，但不能记录备份内容。

任何“更快”的实现都必须先证明这些结果与原实现一致。不能用汇总值、歌名或文件时间替代会话身份和精确片段。

## 宿主 Hook 和生命周期约束

当前设计要求每次启用在后台重新读取宿主文件并进行结构发现，不沿用上次扫描结果。缓存 Hook 目标会改变这一已确认需求，除非用户先确认需求变更，否则不要实现 Issue #7 的持久化目标缓存。

结构发现的当前约束：

- 目标和调用点必须唯一；核心接入不完整时整组撤销。
- 标题栏入口可以单独降级，配置入口和公开播放记录保持独立。
- 停用时取消扫描、忽略过期结果、释放本次反射对象和 Hook，不等待扫描线程。
- 安装失败必须回滚；不能在回滚失败后继续重复注册 Hook。
- 真实窗口、AOT、DWM 材质和多插件共存仍需要实机验证，静态发现通过不能宣称这些场景已完成。

## 当前 P0 快照复用

`StatsStore` 当前缓存合并后的 `StatsData`。缓存命中需要同时满足：

- 宿主 `StatsData` 对象仍是同一个本次启用会话快照；
- `BackupService` 的导入文件版本没有变化；
- `LiveStore` 的实时会话版本没有变化；
- 宿主、实时和导入事件引用的歌曲文件可见性指纹没有变化。

相关代码：`StatsStore.kt`、`BackupService.kt`、`LiveStore.kt`、`TrackVisibility.kt`。P0 只复用无变化结果，不改变统计口径。

P0 的合成检查已经覆盖歌曲文件可见性、实时会话、备份文件、宿主元数据和跨日实时会话。备份版本目前依赖路径、修改时间、文件大小和文件身份；如果外部替换文件后这些属性全部保持不变，当前指纹可能无法识别。

## 已测得的性能事实

性能埋点由 `PerformanceProbe.kt` 提供，默认关闭。分析测试是 `tests/StatsStorePerformanceAnalysis.kt`，运行：

```powershell
py -3.12 tests/run-data-tests.py analysis
```

合成数据包含 1 万、10 万和 50 万条宿主记录，覆盖无变化刷新、单条新增、批量新增、历史记录修改和删除。当前结果：

- 无变化刷新约 `0.35–0.56ms`，P0 可以跳过合并和 Union。
- 50 万条单条新增约 `37.8s`；`LiveStore.merge()` 约 `37.7s`。
- 50 万条单条新增中，`LiveStore.buildHostEvents()` 约 `37.2s`，是主要瓶颈。
- 同场景 `ListeningEvents.union()` 约 `0.28s`，`aggregate()` 约 `0.45s`，排行榜索引约毫秒级。
- 历史修改和删除会让 `HostDatabase.load()` 回退到约 `0.85–1.04s` 的全量读取，但总耗时仍由 `LiveStore.merge()` 主导。

这些数据来自单一合成曲目形状，排行榜耗时不能代表大量不同歌曲的场景。实时会话匹配的潜在平方级风险也尚未在大量同曲实时会话数据上实测，不要把它写成已验证瓶颈。

堆峰值是采样的 JVM 已用堆；线程累计分配量不是实际内存占用；GC 数据来自 `GarbageCollectorMXBeans`。阶段计时可能嵌套，不能直接相加。

P0-A 已验证并修复了宿主事件构造中的 `libraryId` 默认参数重复调用：`LiveStore.merge()` 现在显式传入已取得的 `source`。在启用相同调用计数埋点的 A/B 中，10 万条记录的调用次数从 100,004 降到 3，50 万条从 500,004 降到 3；随后关闭埋点的复测中，50 万条单条新增约 0.89 秒。修复没有改变事件 ID、片段、近似标记、身份隔离或备份格式。

P0-A 之后，`buildHostEvents()` 已不再是 37 秒级瓶颈；剩余成本主要在通用 `ListeningEvents.union()` / `aggregate()` 路径。暂时不要实施大规模增量聚合、改变 Union 规则或改变备份去重逻辑。

## 验证和构建

轻量验证按改动范围选择，并集中在一轮完成：

```powershell
# 编译源码但不覆盖正式包
py -3.12 -c "import tools.build_plugin as b; j=b.find_jdk(); k=b.find_kotlinc(); h=b.ensure_host_jar(b.DEFAULT_SPW_DIR); b.compile_kotlin(j,k,h,b.BUILD / 'plugin-classes')"

# 数据和宿主回归
py -3.12 tests/run-data-tests.py data
py -3.12 tests/run-data-tests.py host
py -3.12 tests/run-data-tests.py cache

# 性能分析
py -3.12 tests/run-data-tests.py analysis
```

需要构建安装包时，先确认版本不是已发布版本，再按 `plugin/README.md` 的工具链流程构建。构建缓存、宿主安装文件、真实听歌历史、私人备份和机器配置不提交 Git。

## AI 修改规则

每次修改前：

1. 检查工作树和当前版本，不覆盖用户修改。
2. 读取需求和相关模块，先确认数据流和生命周期。
3. 如果存在会改变产品行为的多种方案，先询问用户，不静默选择。
4. 先建立可复现证据，再声称修复或性能收益。

每次修改后：

1. 说明修改了什么、为什么、哪些结果已验证、哪些仍未验证。
2. 运行与改动相称的一轮检查，不把离屏测试写成实机验收。
3. 不生成或覆盖正式版本安装包。
4. 保留用户的未提交修改和未跟踪研究素材。

如果当前代码、需求文档和历史发布说明出现矛盾，暂停扩大改动范围，并把冲突位置和影响写给用户。
