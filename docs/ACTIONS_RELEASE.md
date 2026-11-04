# GitHub Actions 发布

发布工作流为 `.github/workflows/publish-release.yml`。它使用工作流自动提供的 `GITHUB_TOKEN` 和 `contents: write` 创建 Release、上传附件并发布，作者为 `github-actions[bot]`，无需个人访问令牌。

## 发布已经准备好的包

1. 按构建流程准备未发布的新版本，检查包内版本、文件名、发布说明和 SHA-256；保留该安装包，不在 Actions 中重建。
2. 通过 GitHub 插件的 `create_blob`（`encoding: base64`）传送原始安装包，记录返回的 Git blob SHA。该二进制对象不加入源码文件树，不提交 SPW 安装文件、工具链或私人数据。
3. 将当前源码、`docs/releases/v<版本>.md`、发布脚本和工作流同步至 GitHub，保留远端 README 与已有历史。更新 `.github/release-request.json`，写入版本、Git blob SHA、包的 SHA-256 和字节数。
4. 对 `main` 的这项发布请求变更会自动触发工作流；也可在 Actions 中手动运行。工作流固定使用本次提交的源码和说明，先校验包，再创建草稿，附件全部验证后才发布。

GitHub 的 blob 接口用于本次包的传送，正式下载始终使用 Release 附件。不要将这类未加入文件树的中转对象当作长期包存储；新版本发布前重新通过插件传送对应原包。

## 保留既有版本

脚本拒绝修改已发布 Release、复用已有但不属于本次草稿的标签、移动标签或替换附件。重复运行已成功发布的版本会明确失败，不覆盖它。中途中断时，只可恢复同一 bot、同一提交、同一说明和同一校验值的草稿；已存在的附件必须在大小和摘要上完全匹配，才能跳过上传。

源码与工作流的 GitHub 写入均通过 GitHub 插件完成；真正的 Release 与附件由 Actions bot 执行。工作流实际执行和 Release 结果应在发布后核对，静态配置不代表发布成功。

## 修正发布说明

用户明确要求修正文案时，编辑对应 `docs/releases/v<版本>.md`，并在 `.github/release-notes-request.json` 中指定版本、当前发布正文的 SHA-256 与原安装包摘要。`update-release-notes.yml` 由请求文件变更触发，仅更新 Release 的 `body`；它校验旧正文和原包身份，拒绝覆盖审核之后出现的其他文字修改，不调用构建、附件上传或标签接口。这与重新发布版本分开处理。

## 本地轻量检查

```powershell
py -3.12 tests/test-publish-release.py
```

该检查使用合成包和模拟 GitHub，覆盖新发布、已发布版本保护、标签保护、包校验、草稿恢复和附件冲突，不联网、不修改现有安装包。
