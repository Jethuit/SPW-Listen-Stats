# 0.1.30 下拉箭头被截断的修复

2026-10-19。截图中的年份/月份下拉箭头几乎只剩一个小点，不是图标尺寸本身，而是 **SelectBox 触发按钮用了固定宽度**。

## 根因

```kotlin
// 旧代码：minWidth 被当成固定 width
Modifier.width(minWidth)
```

日期卡年份按钮 `minWidth = 78dp`、月份 `58dp`，文字 + 8dp 间隔 + 18dp 箭头 + 20dp 内边距超过这个宽度，箭头被裁掉。

## 修改

- `SelectBox` 改为 `widthIn(min = minWidth)`，按钮按内容自然变宽，不再裁剪箭头。
- 三个箭头图标统一为 **24dp**：
  - 年份/月份：`keyboard_arrow_left/right`（24 viewBox）
  - 下拉：`keyboard_arrow_down`（960 viewBox）
  - 分页：`keyboard_arrow_left/right`
- 日期弹窗按钮仍为 34dp 高、8dp 圆角。
- 分页切页不跳顶保持 0.1.28 的行为。

截图：`build/screenshots/arrows_24.png`（下拉箭头完整、日期左右箭头同尺寸）。

## 检查

- 35 个 Kotlin 文件构建通过；18 组数据回归、6 组 UI 回归通过。
- `.spmod` 128 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.30.spmod`（17,149,670 字节）。
- 截图：`build/screenshots/arrows_24.png`。
- 未单独截图分页箭头；它与日期箭头使用同一 24dp 设置。
- 年份/月份按钮现在按内容变宽；日期卡内两套控件仍能并排。

## 产物

- `build/libs/plugin-com.jethuit.spw.listenstats-0.1.30.spmod`
