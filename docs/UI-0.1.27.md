# 0.1.27 日期弹窗悬停闪烁与控件高度

2026-10-19。修复日期弹窗鼠标在灰色卡片和日历区域之间移动时的背景闪烁，并统一年月/箭头高度。

## 一、去除悬停闪烁

原因有两层：

1. 弹窗白色卡片原本使用 `Surface(onClick = {})` 来拦截点击，Material3 的 clickable Surface 在悬停时会叠加一层状态色；鼠标在无交互的灰色卡片区域和日历日格之间移动时，这层状态反复进出，形成闪烁。
2. 日期格外层还包了显式 `HoverColor`，非选中日悬停会换成 `colors.hover`，与卡片灰色底色接近，进一步放大闪烁。

处理：

- 卡片改为“非 clickable 背景 + 单独的透明点击拦截层 + 内容列”：
  - 背景 Surface 只负责圆角/底色/边框，没有 hover/ripple。
  - 透明层用 `onPointerEvent(Press)` 消费空白处点击，避免穿透到遮罩触发取消；不显示任何悬停色。
- 移除日期格的 `HoverColor`；日期格只有“选中/未选中”两种底色，鼠标移入不再变色。
- 年月/箭头按钮仍保留正常点击反馈，不再影响灰色卡片和日历区域。

截图：`build/screenshots/date_hover_fix.png`。

## 二、年月和箭头高度

- `CalendarNavButton` 从 26dp 改为 **34dp**，与年份/月份 SelectBox 的 34dp 一致。
- 圆角统一为 8dp；箭头按钮最小宽度 34dp。
- 年月选择器和箭头在同一行垂直居中。

## 检查

- 宿主中打开日期弹窗截图：两张卡片的年月和箭头高度一致；无可见 hover 闪烁。
- 未用自动化鼠标在灰色/日历区域来回移动做逐帧截图；悬停层已从这两个节点移除。
- 35 个 Kotlin 文件构建通过；18 组数据回归、6 组 UI 回归通过。
- `.spmod` 128 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.27.spmod`（17,148,748 字节）。

## 产物

- `build/libs/plugin-com.jethuit.spw.listenstats-0.1.27.spmod`
