# 0.1.22 封面性能、日期弹窗/菜单、底部文案与顶部入口验证

2026-10-19。基于 0.1.21（MiSans 宿主字体接入）继续修改；保留内嵌内容区、宿主字体、浅深主题和每页 25 首。

## 一、封面性能

代码完成，**未做宿主帧率/内存实测**。

- `CoverStore.load(trackId, targetPx)` 现在按 44dp 当前显示缩放计算目标像素，在 IO 线程用 Skia raster surface 居中裁剪为方形缩略图；`Image.makeFromEncoded()` 的懒解码由 raster 绘制强制完成，UI 不再接收原尺寸位图。
- 缓存改为 12 MiB 预算的访问顺序 LRU，键为 `trackId@sizePx`，只缓存成功结果；失败保留 30 秒冷却。淘汰不关闭仍可能被可见行引用的 `ImageBitmap`。
- 同一 `trackId@sizePx` 的并发请求用 `CompletableFuture` 合并；全局最多 3 个并发读取/解码。
- 排行行用稳定 `key(rank.value.track.id)`；封面重试不再依赖每 5 秒变化的 `loadedAt`，改为组件自身的 30 秒一次重试。
- 移除全局 `LocalViewportRevision` 重组源；只在热力图/趋势图各自外层读取滚动 bucket（48px），`HoverColor` 不再随滚动重建。排行榜不会因每个滚动位置整页重组。
- 未引入 `LazyColumn`：当前每页仅 25 行，先验证上述优化；若仍需虚拟化再单独评估。

需要实测：相同曲目/窗口/缩放下首次加载、滚动、翻页返回、多次翻页后的内存；本次只验证了宿主能正常显示封面。

## 二、日期弹窗与菜单

代码完成；宿主中已验证弹窗能打开、字段和按钮能显示。

- 新增 `ui/DateRangeDialog.kt`：页面内 Compose 模态层，无 Windows 标题栏；使用页面字体/主题/控件样式。
- 开始/结束日期预填当前有效范围；格式 `YYYY-MM-DD`。错误显示在弹窗内，不再弹第二个错误窗口；开始日期晚于结束日期会阻止确认；取消不修改筛选。
- 支持 Enter 确认、Esc 取消；开始日期自动聚焦，文本框使用单行输入，正常 Tab 焦点切换由 Compose 处理。
- `SelectBox` 增加 `selected` 参数，年份、日期范围、按日/按周统一使用自定义菜单；菜单背景改用主题 `background`，选中行 `controlBackground` + accent，悬停 `hover`，8dp 外框/5dp 内项，去掉 Material3 默认偏紫底色。
- 日期弹窗在页面根层覆盖，不随内容滚动；截图验证时卡片仍受旧版 scrim 叠放影响，已改为“scrim 与卡片兄弟层”，最终颜色需宿主复核。

## 三、排行说明

- 删除标题下方“共 xx 首 · 第 1–25 首”及其留白。
- 底部固定文案：`共 xx 首 · 听满单曲 50% 计一次`；数量使用当前筛选结果（无搜索为全部，有搜索为匹配数）。
- 右侧仅保留分页；单页和空结果也显示说明，例如 `共 0 首 · 听满单曲 50% 计一次`。

## 四、顶部快捷入口：验证结论为“当前不可安全接入”

**未实现占位按钮。** 原因：

- 宿主 1.19.0 的源码级组件是 `com.xuncorp.voxzen.ui.main.titlebar.MainTitleBar`，实际 public 函数签名为：
  `MainTitleBar(MainTitleBarType, PlaybackChromeVisibilityState, MusicVideoPlaybackUiState, Modifier)`。
  没有任何 `title/action/trailing` Composable slot。
- 标题栏操作区在内部 `MainTitleBarContent` 中构建，不是公开参数；`CaptionButtons` 等也是内部实现。
- `HookRegistrar.afterComposable` 只能在目标 composable **之后**追加；对 `MainTitleBar` 追加会落在标题栏之后，对内部 Row 追加需要内部调用点，且不能保证插入到现有彩色图标左侧。
- `replaceComposable` 不提供“调用原实现”的委托，替换整个标题栏需要重写宿主标题栏；`appendContent` 需要内容宿主/`ContentHook`，不是任意 `Row` Composable lambda。
- 固定屏幕坐标的悬浮图标会违反“真实接入、不干扰拖动”的要求，因此没有做。

结论：需要宿主提供标题栏 action slot 或官方插件扩展点；当前版本不实现顶部入口，保留配置页入口。不要用 overlay 伪造。

## 检查

- 35 个 Kotlin 文件构建通过。
- 18 组数据回归、6 组 UI 回归通过。
- `.spmod` 125 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 宿主隔离测试：排行页正常渲染，封面能加载；`SelectBox` 菜单可打开；日期弹窗能打开并显示两个字段/取消/确定。**未测帧率、内存、真实播放/seek、顶部入口。**
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.22.spmod`（17,124,881 字节）。
