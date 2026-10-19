# v0.1.0 首次发布准备

日期：2026-10-19。

## 已确认的发布范围

- 用户选择 `v0.1.0`，作为首个预发布版。
- 仅创建 GitHub **草稿 Release**，`draft=true`、`prerelease=true`，不公开发布。
- 基于开发版 0.1.35（`ba41d85`）整理，首个公开编号统一为 0.1.0；Git 历史与开发阶段说明保留。
- 仓库：`Jethuit/SPW-Listen-Stats`；源码继续使用 `codex/listening-stats` 分支，不在本轮合并 main。

## 发布材料

- 对外说明：`docs/releases/v0.1.0.md`，作为 GitHub Release 正文。
- 安装包：`build/releases/v0.1.0/plugin-com.jethuit.spw.listenstats-0.1.0.spmod`。
- 校验文件：`build/releases/v0.1.0/SHA256SUMS.txt`。
- 包内许可证：项目 MIT、Apache 2.0、SQLite JDBC Zentus 原始声明，以及第三方 NOTICE。

构建缓存、宿主文件、机器配置、真实听歌记录与本地预览导出不作为发布附件或源码提交。

## 验证

相关文件整理完成后集中执行一轮，全部通过：

- 构建成功：35 个 Kotlin 文件；安装包大小 **17,164,871 字节**。
- data：**18 个用例通过**；ui：**6 组通过**；cover：**8 组通过**。
- 包结构：**135 项**，项目与第三方许可证齐全，未打包宿主 jar、数据库或工具链目录；权限仍为 `library-read`、`class-transform`。
- 发布目录与校验文件已生成，包内版本和当前构建版本均为 `0.1.0`。
- `git diff --cached --check` 通过，整体命令退出码为 0。

安装包 SHA-256：

```text
3e094b0b0e77574ee7792a4ec6bc282c3b2b219bbf3f5d39c067cab30f8d2c0b
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
