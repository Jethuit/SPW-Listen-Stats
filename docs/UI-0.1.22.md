# 0.1.22 封面性能、日期弹窗/菜单、底部文案与顶部入口验证

2026-10-19。基于 0.1.21（MiSans 宿主字体接入）继续修改；保留内嵌内容区、宿主字体、浅深主题和每页 25 首。

## 一、排行标题行间距

- 排行页删除了 `PageNav` 下方旧的 18dp 间距；标题/搜索区域外层统一使用 **12dp 上下 padding**。
- 标题和搜索框同处一个 `SectionHeading` 操作区，行高由 `heightIn(min=34dp)` 随实际控件/大字体的内容增高，再加 12dp 上下内边距。
- 没有移动表头文字或只上移搜索框；表头仍按自己的上/下 padding 布局。
- 截图：`build/screenshots/ranking_heading_final.png`（本地测试，不提交）。

## 二、封面性能（实测分段）

使用 `-Dspw.listenstats.coverDiagnostics=true`，在隔离宿主中打开排行页，25 首同一页、55px 缩略图。日志字段：`wait_ms`（等待并发名额）、`api_ms`（宿主 API）、`decode_ms`（Skia 解码+缩图）、`ui_ms`（请求开始到界面显示）。

| 并发 | 样本 | wait_ms | api_ms | decode_ms | ui_ms |
| --- | --- | --- | --- | --- | --- |
| 3（基线，25 首） | 全部 miss | 0–389 | 3–199（几乎都 <10） | 5–151 | 191–627 |
| 4（调整后，25 首） | 全部 miss | 0–355 | 3–244（几乎都 <15） | 21–160 | 约 300–562 |

结论：**本地测试的主要耗时是并发排队，不是宿主 API 或解码**；API 常态个位数毫秒，解码多数 <80ms，个别 150–160ms。把上限从 3 调到 4 只能有限降低尾部等待，不是无限加并发。当前未测到缓存命中/翻页返回场景，也没有测滚动 FPS；因此只能说分段耗时已记录、并发上限有界调整为 4，不能宣称“慢已解决”。

代码措施：

- `CoverStore.load(trackId, targetPx)` 改为 `suspend`；等待并发名额可取消，页面离开时不会继续占名额。
- 44dp 目标尺寸在 IO 后台用 raster surface 居中裁剪；`Image.makeFromEncoded()` 的懒解码由 raster 绘制强制完成。
- 12 MiB LRU、键 `trackId@sizePx`、成功才缓存、失败 30 秒冷却；同请求 `CompletableFuture` 合并，等待者不占并发名额。
- 排行行稳定 `key(trackId)`；重试由行自身 30 秒一次，不再依赖 5 秒 `loadedAt`。
- 移除全局 `LocalViewportRevision` 重组源；只有热力图/趋势图各自外层读取滚动 bucket，`HoverColor` 不再随滚动重建。
- 暂不引入 `LazyColumn`；25 行先验证，若仍需虚拟化再单独评估。

未验证：缓存命中后翻回、页切换取消的端到端耗时、真实滚动 FPS、内存峰值。

## 三、排行说明

- 删除标题下方“共 xx 首 · 第 1–25 首”及留白。
- 底部固定文案：`共 xx 首 · 听满单曲 50% 计一次`；数量使用当前筛选结果（无搜索为全部，有搜索为匹配数）。
- 右侧仅保留分页；单页和空结果也显示说明，例如 `共 0 首 · 听满单曲 50% 计一次`。

## 四、日期与菜单

- 新增 `ui/DateRangeDialog.kt`：页面内 Compose 模态，无 Windows 标题栏。
- **双月并排日历**：第一次点击开始、第二次点击结束；支持同一天、跨月；蓝色端点和浅蓝范围；再次点击可重新选择。空间不足（内容宽度 <470dp）退为单月，选择状态保留。
- 顶部 `« ‹ › »` 快速切换年/月；月份网格从周一开始；初始范围预填；取消不应用，确定才回填统计。
- 错误/未完成选择显示在对话框内；Enter 确认、Esc 取消；焦点在对话框根/按钮上。
- `SelectBox` 增加 `selected`，年份/日期范围/按日按周统一主题菜单；选中 `controlBackground` + accent、悬停 `hover`、8dp 外框/5dp 内项。
- 截图：`build/screenshots/cal_final_print.png`（双月、同一天端点、白色卡片、取消/确定）。
- 菜单截图：`build/screenshots/test_menu2.png`（主题背景、选中态；菜单容器仍是 Material3 `DropdownMenu`，不是 SaltUI `PopupMenu` API）。

## 五、顶部快捷入口：验证结论为“当前不可安全接入”

**未实现占位按钮。**

- 宿主 1.19.0 源码级组件 `com.xuncorp.voxzen.ui.main.titlebar.MainTitleBar` 的实际 public 签名：
  `MainTitleBar(MainTitleBarType, PlaybackChromeVisibilityState, MusicVideoPlaybackUiState, Modifier)`；没有 title/action/trailing Composable slot。
- 操作区在内部 `MainTitleBarContent` 构建，不是公开参数；`CaptionButtons` 等也是内部实现。
- `afterComposable` 只能在目标 composable 之后追加；对 `MainTitleBar` 追加会落在标题栏之后。`replaceComposable` 没有调用原实现的委托；`appendContent` 需要内容宿主，不是任意 Row lambda。
- 固定屏幕坐标的悬浮图标会违反真实接入、不干扰拖动的要求，因此没有做。

结论：需要宿主提供标题栏 action slot 或官方插件扩展点；当前保留配置页入口。不要用 overlay 伪造。

## 检查

- 35 个 Kotlin 文件构建通过。
- 18 组数据回归、6 组 UI 回归通过。
- `.spmod` 130 项结构检查通过；权限仍为 `library-read`、`class-transform`。
- 宿主隔离测试：排行页正常渲染，封面能加载（25 首分段日志见上）；`SelectBox` 菜单可打开；双月日期弹窗可打开并显示。
- **未测**：滚动 FPS、内存峰值、缓存命中/翻页返回、真实播放/seek、顶部入口。
- 产物：`build/libs/plugin-com.jethuit.spw.listenstats-0.1.22.spmod`（17,143,573 字节）。
