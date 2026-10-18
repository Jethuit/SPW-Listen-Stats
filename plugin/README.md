# SPW Listen Stats 正式模组

Kotlin / Compose 模组源码。界面基准是仓库根目录的 `preview/`。

## 构建

从仓库根目录运行：

```powershell
python tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

产物：

```text
build/libs/plugin-com.jethuit.spw.listenstats-0.1.0.spmod
build/install/spw-listen-stats/     # 可直接放进 PF4J 插件目录
```

工具链（JDK 25、Kotlin 2.4.20、sqlite-jdbc、宿主 fat jar）缓存在 `build/tools/`，不提交 Git。

## 安装

在 SPW 创意工坊使用“本地导入”选择 `.spmod`，启用并授权：

- `library-read`：读取歌曲元数据与内嵌封面；
- `class-transform`：注册主界面 UI Hook。

授权后主界面右下角显示“听歌统计”入口。若 Hook 不可用，可在插件配置页点击“打开听歌统计”。

## 数据

- 只读宿主 `spw.db` 的 `Listen` / `Track`，按 `startTime + duration` 拆分到本地小时和日期；
- 每首歌仅当单次 `Listen.duration ≥ Track.duration × 50%` 时计一次；
- 统计不写入宿主数据库；
- 备份为版本 1 JSON，带 SHA-256 校验，导入按日期/小时/次数取较大值合并。

## 配置与诊断

`src/main/resources/preference_config.json` 提供“打开听歌统计”和“写入诊断日志”两个按钮。日志位于：

```text
%APPDATA%\Salt Player for Windows\workshop\data\com.jethuit.spw.listenstats\status.log
```

## 结构

```text
src/main/kotlin/com/jethuit/spw/listenstats/
  MainPlugin.kt, PlaybackExtension.kt, PluginLog.kt, PluginState.kt, Prefs.kt
  data/   宿主数据库、统计、封面、备份
  ui/     Compose 页面、热力图、图表、主题、组件
src/main/resources/
  preference_config.json
  META-INF/extensions.idx
```

实现细节与限制见 [../docs/NATIVE-IMPLEMENTATION.md](../docs/NATIVE-IMPLEMENTATION.md)。
