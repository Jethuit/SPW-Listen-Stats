# 第三方组件

## SPW-Lyrics 结构发现机制

- 项目：<https://github.com/GaBoron/SPW-Lyrics>，参考提交 `4fd8fd8c7881102fdebb410a2b8b7a2044715b5a`。
- 许可证：MIT，Copyright (c) 2026 GaBoron；原始许可随包保存在 `classes/META-INF/LICENSE-SPW-Lyrics`。
- 借鉴并改写 UI、导航与主题的结构查询，使用宿主已有的 Byte Buddy ASM 读取字节码；不打包歌词插件。统计页的 Salt Layer、桌面转场与播放器返回优先级使用本项目专用规则。

## SaltUI

- 项目：<https://github.com/Moriafly/SaltUI>
- 许可证：Apache License 2.0
- 打包版本：`io.github.moriafly:salt-ui-desktop:3.0.0-beta02`、`io.github.moriafly:salt-core-desktop:3.0.0-beta02`
- 用途：宿主内嵌页的 `SaltTheme`、`verticalScroll` 等；jar 放在 `.spmod` 的 `lib/`，由 `tools/prepare_toolchain.py` 下载到 `build/tools/saltui/`，不提交 Git。
- 宿主显示 Salt UI 3.0.0-SNAPSHOT / CMP 1.12.1；这里使用 Maven 上对应的 3.0.0-beta02 发布版。

## SQLite JDBC

- 项目：<https://github.com/xerial/sqlite-jdbc>
- 打包版本：`org.xerial:sqlite-jdbc:3.50.3.0`。
- 许可证：Apache License 2.0，保留原始 Zentus BSD 风格声明；上游 jar 内的原始许可证保持不变。

## Google Material Symbols

- 项目：<https://github.com/google/material-design-icons>
- 许可证：Apache License 2.0。正式模组将图标路径表示为 Kotlin 字符串供本地绘制，预览来源见 `preview/assets/icons/README.md`。

## 随包声明

项目 MIT、Apache 2.0、SQLite JDBC Zentus 声明及第三方 NOTICE 位于 `.spmod` 的 `classes/META-INF/`；源码对应 `plugin/src/main/resources/META-INF/`。

## 宿主与构建依赖

- Kotlin、Compose Multiplatform、PF4J 和 Workshop API 通过宿主或构建工具链使用；播放器安装、宿主 jar、JDK 与编译器不随模组分发，构建缓存不提交。
