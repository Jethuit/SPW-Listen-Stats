# 正式模组实现说明

更新时间：2026-10-18。当前源码版本 0.1.3；视觉基准仍为 `preview/`。0.1.2 针对用户截图修复提示坐标、刻度、日周切换、DPI 和页面高度，详见 [UI 修订](UI-FIXES.md)；0.1.3 补了异常退出后的会话恢复、source-id 自愈和回归脚本修复。下方保留 0.1.1 数据实现说明；旧版运行截图不能证明新版已经在宿主中验收。

![0.1.0 历史运行截图](screenshots/overview-light.png)

## 构建和安装

```powershell
python tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

产物 `build/libs/plugin-com.jethuit.spw.listenstats-0.1.3.spmod`。在 SPW 创意工坊“本地导入”，启用并授权 `library-read` 和 `class-transform`。使用宿主 1.19.0 的 fat jar 作编译类路径；JDK 25 / Kotlin 2.4.20 / sqlite-jdbc 与宿主包缓存在 `build/tools/`，均不提交。

主界面入口仍是 Hook 浮层，内容仍为 Compose / Material3 + Swing 无边框窗口，不是原生 ScreenRoute。Hook 类名绑定 SPW 1.19.0；失败时可用插件配置页打开。窗口不能拖动或缩放。这些是先前实现留下的限制，本轮没有更改视觉设计。

## 0.1.1 数据修复

- `PlaybackAccumulator` 用单调时钟计算实际时间，位置移动仅作为播放证据。暂停、seek、缓冲后重建采样基线；长于 3 秒的回调空档与过期查询不补算。
- 每个会话保存实际墙钟收听片段；暂停间隙不会被压缩到会话开头。片段独立拆到本地自然日和小时。每遍实际时间达到歌曲长度一半只计一次；回拖非零位置仍属同一遍，主动跳到零或近曲尾自动回到开头开始新一遍。
- 播放回调串行处理；宿主查询在生命周期锁外执行；停用/重新启用的代际检查丢弃晚到结果。歌词预加载不再当作可靠的换曲事件。
- 不重新开启已结束的旧会话；未正常结束的会话在同一曲目再次观察时恢复，避免重启前后各计一次。停止后关闭统计窗口并释放封面缓存。每 10 秒和暂停、seek、结束/切歌时保存；异常退出仍可能损失最后一个检查点之后的数据。
- `live-sessions.json` 升级为 v2，兼容迁移 v1；旧文件无法读取时保留原文件，界面提示新数据暂不能落盘。原子替换写入，避免并发写同一个临时文件。
- 宿主数据库连接保持只读；无 `library-read` 权限时不查询内部数据库。
- 空状态根据实际时长判断；排行翻页回到顶部，导入后回第一页；同名同次数排序保留输入顺序。

## 0.1.3 稳定性修复

- `live-sessions.json` 保留 v2 的 `finalized` 标记。`PlaybackAccumulator` 在异常退出后重新观察到同一曲目时恢复 6 小时内未完成的会话，继续追加实际片段，而不是新建第二个会话；正常 stop/finalize 的会话不会恢复。
- `DataFiles.sourceId()` 遇到损坏的 `source-id.txt` 会重新生成，避免所有 `ListeningEvent` 因 UUID 解析失败而无法构造。
- `tests/run-data-tests.py` 修正了临时目录权限/输出目录问题，`data` 与 `ui` 两个 suite 均可从仓库直接运行。

## 事件和历史的边界

`ListeningEvent` 包含会话 ID、曲库来源 UUID、歌曲元数据、收听片段及别名。相同身份的多个快照选时长最大的一个，相同时间戳/歌名本身不是合并依据。不同曲库来源下的歌曲 ID 分开，跨设备不自动归并同名歌曲；外部曲库的封面可能只有占位图。

实时会话与宿主 `Listen` 行的对应仍依赖“同曲目、估算开始时间相差不超过 10 秒”，现在是一对一并持久化对应别名。匹配后较完整快照只贡献一次，相同时长优先实际片段；先导出实时会话、后导入匹配宿主记录也会通过别名去重。

**上述宿主匹配仍是启发式，未通过本轮真实播放验证。** 插件中途启用、启用前有长暂停或拖动时，估算开始时间可能不可靠，仍可能漏匹配或误匹配。没有稳定的宿主会话 ID 事件之前不能宣称完全消除此风险。

宿主旧 `Listen` 仅有开始时间和总时长，没有暂停/seek 的实际时间片段。该历史按连续区间近似分配小时；若较长宿主快照取代实时快照，也会使用这个近似分布。它能提供保留期内的历史，但不能恢复从未保存的记录，也不能保证其 duration 与实际收听规则完全一致。旧 live v1 同样标记为近似。新会话的片段模型不会凭空恢复这些信息。

## 备份

新版正式备份 `format=spw-listen-stats-backup, version=2`：

- `payload.events` 保存会话、来源、片段与别名；按身份合并，重复导入幂等，不同会话相加。
- `payload.legacy` 独立保存旧版汇总，防止把无来源汇总冒充完整会话。
- `checksum` 为 payload JSON 的 SHA-256。校验和用于检出损坏，不是认证签名。
- 导入先检查大小（最多 32 MB）、版本、必需校验和、日期、片段、负数、重复 ID 与小时合计，再合并已导入数据；写入前保留 `imported.previous.json`，原子更新 `imported.json`。后一次导入不会丢弃先前导入。
- v1 正式备份必须有正确校验和及完整的 24 小时数组。按用户选择兼容，按每小时和每首次数取较大值，并提示可能少计重叠日期中的不同收听。此策略不是事件级精确合并。
- 导出再次包含已导入会话和旧版汇总，不把预览格式当作正式备份。多设备曲库不按歌曲名称合并。

## 验证与验收

一次集中检查的命令：

```powershell
python tools/build_plugin.py
python tests/run-data-tests.py data
python tests/run-data-tests.py ui
python tests/spmod-structure.py
```

数据回归在 `tests/DataRegressionTest.kt`，使用模拟时间及隔离 `build/tests/<suite>-state/`，不启动播放器、不读取个人曲库。覆盖暂停跨日、回拖/循环、睡眠空档、DST、持久化、宿主别名、连续导入、重复导入、损坏拒绝、停用后的异步返回、重启恢复和损坏 source-id。

本轮构建和回归结果见 `HANDOFF.md`。0.1.2 统计页已在独立 `APPDATA` 中实际启动并点击核对：浅/深主题、概览/排行页签、日/周热力图、日期格选择、图表类型和滚动均有可见效果；搜索、分页、导入导出以及真实播放/暂停/拖动/切歌和宿主落库匹配仍需在正常 SPW 环境中验收。历史截图与此前代理的模拟测试不替代此项。

本机验证实际使用 `py -3.12` 替代 `python`（后者为 WindowsApps 空别名）：30 个 Kotlin 文件编译、13 组数据回归、4 组 UI 几何回归和 106 项包结构检查均通过。没有在真实播放中验证 0.1.3；文档和视觉效果留待用户核对。
