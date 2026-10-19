# 0.1.29 使用 keyboard_arrow 图标

2026-10-19。按用户指定的 Google Material Symbols 图标替换箭头。

## 变化

- 新增 `KeyboardArrowLeft`、`KeyboardArrowRight`、`KeyboardArrowDown` 三个 24px 路径。
- 下拉菜单：`keyboard_arrow_down`。
- 日期弹窗上/下月：`keyboard_arrow_left` / `keyboard_arrow_right`。
- 排行分页上一页/下一页：同一组 `keyboard_arrow_left/right`。
- 不再用 `ExpandMore` 旋转来伪造左右箭头。
- 所有箭头统一 18dp 视觉尺寸、`colors.muted` 颜色、34dp 按钮、8dp 圆角。
- 分页禁用箭头降低 alpha。

## 技术说明

原有 `AppIcon` 只处理 960 viewBox（Material 的负 Y 坐标）。`keyboard_arrow` 使用 24px viewBox、正 Y 坐标，因此给 `AppIcon` 增加：

- `viewBoxSize`：24f；
- `positiveCoordinates = true`：不做底部平移。

## 检查

- 35 个 Kotlin 文件构建通过；18 组数据回归、6 组 UI 回归通过。
- `.spmod` 128 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.29.spmod`（17,149,688 字节）。
- 宿主截图：`build/screenshots/keyboard_arrows.png`。
- 日期左右箭头已使用 keyboard_arrow，和下拉 chevron 同一图标族。
- 未单独截图分页箭头；分页代码使用同一路径/尺寸/颜色。

## 产物

- `build/libs/plugin-com.jethuit.spw.listenstats-0.1.29.spmod`
