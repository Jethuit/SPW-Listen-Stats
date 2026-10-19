# v0.1.0 首次发布准备

日期：2026-10-19。

## 已确认的发布范围

- 用户选择 `v0.1.0`，作为首个预发布版。
- 仅创建 GitHub **草稿 Release**，`draft=true`、`prerelease=true`，不公开发布。
- 基于开发版 0.1.35（`ba41d85`）整理，首个公开编号统一为 0.1.0；Git 历史与开发阶段说明保留。
- 仓库：`Jethuit/SPW-Listen-Stats`；源码继续使用 `codex/listening-stats` 分支，不在本轮合并 main。
- 用户随后确定插件显示名称为 **SPW热力图&排行**，提供三张实际截图用于 README／Release，并要求推荐使用“椒盐经典”主题样式获得最佳体验。图片原样保存到 `docs/screenshots/v0.1.0/`；插件 ID 与页面标题“听歌统计”保持。

## 发布材料

- 对外说明：`docs/releases/v0.1.0.md`，作为 GitHub Release 正文。
- 安装包：`build/releases/v0.1.0/SPW-Heatmap-Ranking-v0.1.0.spmod`，对外文件名不包含作者 ID。
- 校验文件：`build/releases/v0.1.0/SHA256SUMS.txt`。
- 包内许可证：项目 MIT、Apache 2.0、SQLite JDBC Zentus 原始声明，以及第三方 NOTICE。

构建缓存、宿主文件、机器配置、真实听歌记录与本地预览导出不作为发布附件或源码提交。

## 验证

首次发布准备与本轮名称／截图更新的验证记录：

- 名称更新后构建成功：35 个 Kotlin 文件；当前安装包大小 **17,164,914 字节**。
- 首轮准备时 data：**18 个用例通过**；ui：**6 组通过**；cover：**8 组通过**。本轮没有重复执行逻辑未变的回归。
- 包结构：**135 项**，项目与第三方许可证齐全，未打包宿主 jar、数据库或工具链目录；权限仍为 `library-read`、`class-transform`。
- 发布目录与校验文件已生成，包内版本和当前构建版本均为 `0.1.0`。
- `git diff --cached --check` 通过，整体命令退出码为 0。

安装包 SHA-256：

```text
4d8fe66212001919ca9a0eb916ae0e9023e8d9733c6c6d4e67f19f35e451d32f
```

仅有既有编译警告与字体回退用例的预期日志；本轮未启动 SPW，既有待实机核对项仍保留在对外说明中。

## 复用命令

```powershell
py -3.12 tools/build_plugin.py
py -3.12 tests/run-data-tests.py data
py -3.12 tests/run-data-tests.py ui
py -3.12 tests/run-data-tests.py cover
py -3.12 tests/spmod-structure.py
py -3.12 tools/prepare_release.py
```

校验文件绑定最终 `.spmod` 内容。重新构建后应重新生成发布目录；GitHub 草稿附件应与该目录一致。

## 名称与实际效果图更新

README 和发布说明展示听歌概览与热力图、时长分布与小结、歌曲排行三张实际截图。源码文档使用相对路径；草稿 Release 正文使用对应提交的原始图片地址，避免 Release 页的相对链接失效。

模组 manifest、配置分组名称、启用及入口失败提示改为“SPW热力图&排行”。本轮集中检查通过：构建、135 项包结构、manifest／配置名称、内部 ID 保持、三张截图与用户原图的 SHA-256 一致性、文档图片路径、主题提示和差异格式检查；发布目录与校验文件已重新生成。整体退出码为 0。

现有 GitHub 草稿（Release ID `417088208`）继续使用 `draft=true`、`prerelease=true`；附件上传先校验新文件，再替换旧文件，远端摘要与本地匹配后保存到本机 `build/releases/draft-v0.1.0.json`。

用户随后要求简化安装包文件名与 Release 正文。当前文件名统一为 `SPW-Heatmap-Ranking-v0.1.0.spmod`；说明仅保留功能简介、安装入口、“椒盐经典”建议、兼容性链接与三张实际截图。此轮仅改文件名与发布文案，复用已验证的安装包内容并重新生成校验文件，不重新编译插件。

本轮轻量检查通过：发布目录生成、135 项包结构、改名前后二进制 SHA-256 一致、校验文件仅引用新文件名、差异格式检查。安装包摘要不变；GitHub 上只改安装附件名称并更新校验附件及简短正文。
