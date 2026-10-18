# 新聊天交接

本轮安排已由用户确认：**本轮整理需求、交接和上传 GitHub；正式模组在新聊天继续。** 用户已经授权后续全面施工，要求前端按当前示例实现，遇到不能实现的功能明确告知。

## 从哪里开始

1. 阅读根目录 `AGENTS.md`。
2. 阅读 [正式需求](REQUIREMENTS.md)，它记录最新设计，不能只看早期截图。
3. 阅读 [API 与环境](API-FEASIBILITY.md)，区分已确认接口与待验证能力。
4. 打开 `preview/index.html` 与 `preview/index.html#ranking` 看当前前端；数据全部为示例。
5. 用 `git status --short --branch`、`git remote -v` 查看当前分支与远端，先保留已有变更再开发。

## 当前状态

- 项目名：SPW-Listen Stats；GitHub：<https://github.com/Jethuit/SPW-Listen-Stats>，用户要求公开。
- 本机工作目录：`E:\Code\spw status`。
- 本机 SPW：`E:\SteamLibrary\steamapps\common\Salt Player for Windows`，核查版本 1.19.0。
- 预览最后一次确认的代码提交：`4e25ed1`。用户已完成多轮细节审阅，正式 UI 依此实现，不再另起设计。
- 现在没有 Kotlin 正式模组、真实听歌记录、实际插件存储或构建产物；不要把原型误报为插件已完成。
- 本轮没有安装 JDK／Gradle，也没有改动 SPW 程序或个人曲库。
- `.references/` 仅本地保留，上游地址与版本见 API 文档；GitHub 克隆不包含它。

## 已有文件

| 路径 | 用途 |
| --- | --- |
| `preview/index.html` | 定稿页面结构、文案、内联本地图标 |
| `preview/styles.css` | 浅深主题、尺寸与响应式布局；方块圆角 6px |
| `preview/app.js` | 示例数据、两页切换、热力图、图表、每页 50 首、导入导出 |
| `preview/assets/icons/` | Google Material Symbols 原始 SVG 与 Apache-2.0 许可 |
| `preview/assets/covers/` | 虚构专辑的原创本地示意封面与默认图 |
| `tests/chart-geometry.test.cjs` | 图表坐标与日期／粒度联动检查 |
| `tests/heatmap-layout.test.cjs` | 2000～2026 年日／周日期位置、方格数、跨年留空检查 |
| `tests/song-pagination.test.cjs` | 0～10000 首、50 首分页、搜索与排名检查 |

检查命令是 `node --check preview/app.js` 和上述三个 Node 脚本，无第三方依赖。脚本用小型 DOM 替身检查逻辑与坐标，不是完整浏览器视觉测试，更不能替代 SPW 宿主测试。

## 优先处理的工作

- 核实 API／Salt UI／Compose 与宿主的实际版本组合，准备 JDK 25 构建环境。
- 建立独立正式模组目录，先打包并加载一个最小 `.spmod`。
- 实测原生页面／主页入口与播放事件，尽早报告任何无法达到的要求。
- 建立真实统计状态机、每日／小时分配、每首歌 50% 计次及持久化。
- 逐项还原定稿前端；接入真实封面、全量搜索和 50 首分页。
- 实现正式备份格式并验证权限、异常、重启与版本升级。
- 持续使用 Git 提交，按用户要求上传 GitHub；本轮已授权的 GitHub 目标就是上述仓库。

## 可直接复制到新聊天

```text
继续开发 SPW-Listen Stats 正式模组。工作目录 E:\Code\spw status，GitHub 为 https://github.com/Jethuit/SPW-Listen-Stats。
先读 AGENTS.md、docs/HANDOFF.md、docs/REQUIREMENTS.md、docs/API-FEASIBILITY.md，再检查 Git 分支、状态和远端。
当前 preview/ 是我已经确认的最终前端，正式版必须按当前示例实现，优先原生 Salt UI，不要重新设计。
SPW 安装目录是 E:\SteamLibrary\steamapps\common\Salt Player for Windows，核查版本 1.19.0；目录已提供给你作本地研究。
统计实际听歌时长；每首歌实际听满 50% 计一次；不统计跨歌曲的总播放次数。排行独立页面，每页 50 首。
需要真正可安装运行的 .spmod、持久化、真实封面和导入导出；先验证宿主入口和事件，不把预览当成正式实现。
不能实现或存在宿主限制的功能请具体说出来。需要重大产品选择时再问我，能独立推进的工作继续做。
文件清晰分类，使用 Git 保存并上传到上述仓库；集中做一轮合适验证，视觉与文档交给我检查。
```

