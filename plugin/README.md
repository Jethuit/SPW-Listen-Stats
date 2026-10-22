# 听歌统计模组源码

Kotlin / Compose / Salt UI 实现，目标宿主为 SPW 1.19.0。安装与功能介绍见[项目首页](../README.md)，产品规则见[需求](../docs/REQUIREMENTS.md)，依赖与许可证见[第三方组件](../docs/THIRD-PARTY.md)。

## 目录

- `plugin/`：正式模组源码和随包资源。
- `preview/`：已确认的 HTML 视觉基准及本地素材；可直接打开 `preview/index.html`。预览使用演示数据，旧版控件与正式模组有差异，后续确认的需求优先。
- `tests/`：数据回归、包结构和离屏视觉检查。
- `tools/`：工具链准备、构建与发布包整理脚本。
- `docs/`：当前需求、第三方声明、正式发布说明和效果截图。
- `build/`：忽略提交的工具链、安装包、合成测试状态和检查结果。

## 构建

在仓库根目录执行，按本机安装位置修改 `--spw-dir`：

```powershell
py -3.12 tools/prepare_toolchain.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
py -3.12 tools/build_plugin.py --spw-dir "E:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

构建使用 JDK 25、缓存的 Kotlin 编译器、SaltUI 及对应宿主 jar。工具链缓存位于 `build/tools/`，不提交 Git。版本号以 `tools/build_plugin.py` 的 `PLUGIN_VERSION` 为准；安装包输出到 `build/libs/SPW-Stats-v<版本>.spmod`，解包目录为 `build/install/spw-listen-stats/`。

默认只生成本地产物，不安装或发布。通过 SPW 创意工坊／模组管理本地导入 `.spmod` 并授权 `library-read`、`class-transform`。`py -3.12 tools/prepare_release.py` 仅将当前包整理到 `build/releases/v<版本>/`，不会上传 GitHub。

源码变化不会自动更新已有安装包，需要重新构建。

## 接入与数据

- 顶部听歌统计图标和模组配置的“打开听歌统计”共用入口。页面经宿主 Hook 嵌入内容区，保留侧栏和播放栏；Hook 与宿主内部签名绑定，升级 SPW 后须核实兼容性。
- 宿主历史来源于内部 `Listen` 数据表的只读查询，受保留范围和表结构影响；实时会话由播放回调记录。单次加载合并宿主、实时与导入事件后统一聚合。
- 实际收听按单调时钟计量，并按墙钟区间拆分日期和小时。暂停与拖动跳过的进度不计时，同一遍播放最多计一次；达到歌曲时长的 50% 才计为有效播放。
- 同一会话的精确收听片段按区间并集合并，宿主近似快照单独处理，不能直接相加。当前备份 v2 保留会话身份，兼容旧 v1 汇总的保守合并；旧汇总可能少计。
- 本机来源身份用于隔离曲库 ID。跨设备或重建曲库后不按同名歌曲强行对应；未知播放起点、插件启用前的片段及已丢失的旧记录不能可靠补回。
- 封面取自内嵌图片，成功结果有容量受限缓存，空封面与失败请求分别冷却重试；异库曲目 ID 不查询本机封面。
- 始终跟随宿主深浅主题。支持材质的页面继承宿主背景，接入不可用时回退实色；真实 DWM、播放回调时序及多插件共存仍需实机核对。

数据通常位于 `%APPDATA%\Salt Player for Windows\workshop\data\com.jethuit.spw.listenstats\`；有宿主插件路径时按该安装的数据根目录解析。实时文件为 `live-sessions.json`，导入数据为 `imported.json`，来源身份为 `source-id.txt` 及冗余副本。配置页“写入诊断日志”将状态写入该目录的 `status.log`，成功提示包含实际路径；数据目录不可用时日志可回退系统临时目录。

宿主自附加 Agent 在部分非 ASCII 路径下的安装失败尚未解决；该问题不能通过插件 Manifest 的 UTF-8 折行修复来解释。

## 轻量检查

先编译源码，再按改动范围选择检查，不要求每次运行全部套件：

```powershell
py -3.12 tests/run-data-tests.py data
py -3.12 tests/spmod-structure.py build/libs/SPW-Stats-v<版本>.spmod
```

数据入口也支持 `integrity`、`astra`、`review`、`cover`、`ui`、`ranking`、`heatmap`、`format`、`navigation`。检查使用 `build/tests/` 下的合成状态和模拟宿主；离屏检查不能代替真实 SPW 验收。真实配置、数据库、听歌历史和私人备份不用于提交或合成回归。
