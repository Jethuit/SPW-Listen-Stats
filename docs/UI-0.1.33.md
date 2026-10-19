# 0.1.33 分页箭头透明底色

2026-10-19。

## 修改

- 分页上一页/下一页按钮的 `Surface` 底色从 `colors.controlBackground` 改为 `Color.Transparent`。
- 箭头图标仍为 `colors.muted`，禁用时降低 alpha。
- 点击区域仍为 34×34dp，圆角 8dp。
- 页码胶囊和跳页输入框保持 `controlBackground`，视觉层级不变。
- 日期弹窗的上/下月按钮不受影响，仍保留控件底色。

## 检查

- 35 个 Kotlin 文件构建通过；18 组数据回归、6 组 UI 回归通过。
- `.spmod` 130 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.33.spmod`（17,155,065 字节）。
- 本版只改分页箭头的底色，没有重新截图。

## 产物

- `build/libs/plugin-com.jethuit.spw.listenstats-0.1.33.spmod`
