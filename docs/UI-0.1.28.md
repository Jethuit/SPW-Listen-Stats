# 0.1.28 分页不跳顶与箭头统一

2026-10-19。处理切换页数时滚动跳顶，以及日期弹窗/分页/下拉菜单箭头样式不一致。

## 一、切页不跳顶部

- 排行分页的 `onPageChange` 不再调用 `scrollTop()`；只更新页码。
- 页面切换（听歌概览/听歌排行）仍会回到内容顶部。
- 搜索、筛选变化仍会把页码重置到第一页，但不会额外滚动。

## 二、箭头统一

日期弹窗和分页箭头此前是文本 `‹` / `›`，下拉菜单使用 `ExpandMore` 路径图标，三者字形/线宽不同。

现在：

- 日期卡上/下月箭头：`IconPaths.ExpandMore` 旋转 `90°` / `-90°`。
- 分页上一页/下一页：同一路径旋转 `90°` / `-90°`。
- 下拉菜单：同一 `ExpandMore`，不旋转。
- 图标尺寸统一 16dp，色调统一 `colors.muted`；按钮高度均 34dp、圆角 8dp。
- 分页箭头禁用时只降低 alpha。

截图：`build/screenshots/arrow_consistency.png`（日期弹窗左右箭头已是同一套 chevron）。

## 检查

- 35 个 Kotlin 文件构建通过；18 组数据回归、6 组 UI 回归通过。
- `.spmod` 128 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.28.spmod`（17,149,188 字节）。
- 未单独截图排行页箭头；截图中的日期箭头与分页代码共用同一路径/尺寸/色调。
- 切页不跳顶未做自动化滚动测试；仅移除了 `scrollTop()` 调用。

## 产物

- `build/libs/plugin-com.jethuit.spw.listenstats-0.1.28.spmod`
