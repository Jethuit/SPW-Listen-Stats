# SPW Listen Stats 正式模组

Kotlin / Compose 模组源码，视觉基准为根目录 `preview/`。当前 0.1.3 包含数据正确性修订、重启恢复和 [UI 修订](../docs/UI-FIXES.md)，仍需真实宿主验收。

## 构建

```powershell
python tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.3.spmod`；解包目录 `build/install/spw-listen-stats/`。工具链缓存在 `build/tools/`，不提交 Git。

在 SPW 创意工坊本地导入并授权 `library-read`、`class-transform`。Hook 失败时可在配置页打开统计。页面是宿主浮层入口加独立无边框窗口，仍绑定 SPW 1.19.0。

## 数据与检查

实际收听按单调时钟和逐段墙钟区间累计，每遍最多计一次；暂停继续与回拖非零位置保留同一遍。实时 JSON v2 支持旧版迁移；未正常结束的会话在同曲目再次播放时恢复，正常结束后不再恢复。正式备份 v2 按会话身份合并去重；旧 v1 汇总保守合并并提示限制。宿主历史小时分配及实时/宿主开始时间匹配仍有近似误差。

```powershell
python tests/run-data-tests.py
python tests/spmod-structure.py
```

检查数据和日志仅写 `build/tests/` 的隔离目录。实际运行日志位于 `%APPDATA%\Salt Player for Windows\workshop\data\com.jethuit.spw.listenstats\status.log`。

架构、完整限制与验收说明见 [正式实现说明](../docs/NATIVE-IMPLEMENTATION.md)，最新接手状态见 [交接](../docs/HANDOFF.md)。
