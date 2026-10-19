# 0.1.32 跳页数字居中与圆角统一

2026-10-19。

## 修改

- `PageJumpControl` 的页码输入框：
  - 文本对齐改为 `TextAlign.Center`，数字和光标居中。
  - 高度从 30dp 调到 34dp，和分页按钮同一高度。
  - 圆角 6dp → **8dp**。
- 分页数字按钮的选中/悬停背景圆角 5dp → **8dp**。
- 分页箭头按钮、页码胶囊、跳页输入框统一为 **8dp 圆角**。
- 页码数字、输入框文本都使用 `AppType.tiny`，字色 `colors.text`。

## 检查

- 35 个 Kotlin 文件构建通过；18 组数据回归、6 组 UI 回归通过。
- `.spmod` 130 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.32.spmod`（17,155,069 字节）。
- 未单独截图本版；0.1.31 的底栏布局截图仍可参考，本版只改输入框对齐和圆角。

## 产物

- `build/libs/plugin-com.jethuit.spw.listenstats-0.1.32.spmod`
