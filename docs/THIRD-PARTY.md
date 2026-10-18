# 第三方组件

## SaltUI

- 项目：<https://github.com/Moriafly/SaltUI>
- 许可证：Apache License 2.0
- 打包版本：`io.github.moriafly:salt-ui-desktop:3.0.0-beta02`、`io.github.moriafly:salt-core-desktop:3.0.0-beta02`
- 用途：宿主内嵌页的 `SaltTheme`、`verticalScroll` 等；jar 放在 `.spmod` 的 `lib/`，由 `tools/prepare_toolchain.py` 下载到 `build/tools/saltui/`，不提交 Git。
- 宿主显示 Salt UI 3.0.0-SNAPSHOT / CMP 1.12.1；这里使用 Maven 上对应的 3.0.0-beta02 发布版。

## 其他

- Kotlin、Compose Multiplatform、sqlite-jdbc、PF4J 等通过宿主安装或构建工具链使用；构建缓存不提交。
