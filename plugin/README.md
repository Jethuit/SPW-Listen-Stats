# 听歌统计 正式模组

Kotlin / Compose 模组源码，视觉基准为根目录 `preview/`。首个公开预发布版为 0.1.0，基于开发版 0.1.35 整理，保留封面取消及重试修复。安装与限制见 [v0.1.0 发布说明](../docs/releases/v0.1.0.md)。

推荐使用 SPW 的“椒盐经典”主题样式获得最佳体验。插件显示名称、页面标题统一为“听歌统计”，入口为“打开听歌统计”。

## 构建

```powershell
python tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

产物：`build/libs/听歌统计-v0.1.0.spmod`；解包目录 `build/install/spw-listen-stats/`。执行 `py -3.12 tools/prepare_release.py` 将安装包整理到 `build/releases/v0.1.0/`。工具链缓存在 `build/tools/`，SaltUI jar 缓存在 `build/tools/saltui/`，不提交 Git。

在 SPW 创意工坊本地导入并授权 `library-read`、`class-transform`。入口在模块管理页插件配置“打开听歌统计”；页面通过 AppNavigation hook 内嵌在宿主内容区，不再创建 JDialog/Popup。Hook 仍绑定 SPW 1.19.0。

## 数据与检查

实际收听按单调时钟和逐段墙钟区间累计，每遍最多计一次；暂停继续与回拖非零位置保留同一遍。实时 JSON v2 支持旧版迁移；仅初始化后首次有效播放观察可恢复同曲目的未结束会话，其他遗留会话会关闭，正常结束后不再恢复。时钟校正产生的重叠片段会修整并标为近似。正式备份 v2 按会话身份合并去重；旧 v1 汇总保守合并并提示限制。宿主历史小时分配及实时/宿主开始时间匹配仍有近似误差。

```powershell
python tests/run-data-tests.py
python tests/spmod-structure.py
```

检查数据和日志仅写 `build/tests/` 的隔离目录。实际运行日志位于 `%APPDATA%\Salt Player for Windows\workshop\data\com.jethuit.spw.listenstats\status.log`。

架构、完整限制与验收说明见 [正式实现说明](../docs/NATIVE-IMPLEMENTATION.md)，最新接手状态见 [交接](../docs/HANDOFF.md)。
