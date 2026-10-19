# 顶部“听歌统计”入口调查

日期：2026-10-19。研究对象：本机 SPW 1.19.0。用户最新指定位置：窗口最上方，彩色快捷按钮左侧的红框区域。

## 结论

**找到可由宿主 UI Hook 解析器识别的内部调用点，值得继续做实际注入验证。** 在 `CaptionButtons` 的按钮行内，对 `DebugCaptionButton` 使用 `afterComposable`，可尝试把统计按钮放在彩色快捷按钮左侧，符合用户最新红框位置。此前调查的 Quick 之后位置保留为备选。

这修正了早期“无安全插入点”的过宽结论：整个 `MainTitleBar` 确实没有 action slot，但内部按钮调用可以单独作为候选追加位置。当前证据确认目标解析与调用关系，不等于已经完成注入。

## 已确认的调用关系

```text
MainTitleBar
  MainTitleBarContent
    CaptionButtons
      Row
        提示类按钮
        DebugCaptionButton          ← 在此调用之后追加
        [听歌统计，待实现]
        QuickCaptionButton
        SteamCaptionButton          ← 受 Steam 环境条件控制
```

`DebugCaptionButton` 的调用本身在 Steam 条件判断之前，即使该组件内部不显示调试图标也会返回到追加位置，故候选入口不依赖调试图标或头像是否显示。它属于正常主窗口标题栏，与“歌曲”“曲风”等内容页面的导航独立；初始向导、完整播放界面等其他标题栏分支不能一并宣称支持。

SPW 1.19.0 的精确绑定：

| 项目 | 值 |
| --- | --- |
| 目标组件 | `DebugCaptionButton`（Quick 之前） |
| `className` | `androidx.compose.ui.\u04f4` |
| `methodName` | `\u037f` |
| `inClass` | `androidx.compose.ui.\u0205`，即 `MainTitleBar_desktopKt` / `CaptionButtons` 所在类 |
| `parameterTypes` | `emptyList()`，不包含 Composer 和 changed mask |
| JVM 描述符 | `(Landroidx/compose/runtime/Composer;I)V` |
| 调用点数量 | 整个调用类中恰好 1 处 |

表中 `\uXXXX` 是 Kotlin/Java 字符串中的 Unicode 转义，应按实际字符传给 API。混淆名仅对本次检查的宿主版本成立。

## 建议的接入方式

1. 注册独立的 `afterComposable` 句柄，追加一个柱状图图标按钮，悬停提示和无障碍名称均为“听歌统计”。布局由现有 Row 分配空间。
2. 优先复用宿主 `BasicCaptionButton` / `BasicIconCaptionButton` 的尺寸、悬停和提示行为。其实际实现位于 `com.xuncorp.voxzen.ui.component.\u0529`；调用桥接方式尚待编译与渲染验证。
3. 单击沿用 `MainPlugin.openStats()` / `StatsHostState.open()`，直接打开现有统计页，不另建窗口或导航入口。
4. 顶部句柄与统计内容区句柄分开管理。顶部目标不兼容时保留配置页入口和原有统计功能；停用时移除追加按钮，不能留下重复注册。
5. 继续使用现有 `class-transform` 权限；此方案不要求新增权限，也不要求修改 SPW 安装文件。

## 本轮验证与边界

初次运行 `py -3.12 tests/probe-titlebar-hook.py` 检查了 Quick 之后的候选。最新红框位置运行 `py -3.12 tests/probe-titlebar-hook.py --before-quick`，检查内容：

- 宿主真实 UI Hook 解析器接受上述目标、调用类与空业务参数列表。
- ASM 扫描整个调用类，确认仅一处目标调用，排除了同类提取 lambda 引起的多处匹配。
- `CaptionButtons` 中有一个 Row，Debug 调用之后紧接 Quick 调用，Quick 之后是可选 Steam 按钮调用。

探针在独立 JVM 中执行，未启动播放器、未注册 Hook、未安装插件。APPDATA 和 LOCALAPPDATA 指向 `build/tests/titlebar-hook/` 下的隔离目录。

尚需实际验证：agent 注入、首帧与重组、鼠标单击/悬停、标题栏拖动及双击最大化、浅深主题、窄屏与缩放、停用再启用，以及其他顶部插件同时启用时的布局。宿主升级后的混淆绑定需要重查。

本轮不改变生产源码、插件版本、发布标签或 v0.1.1 附件。原始宿主类和反汇编仅保留于忽略的 `build/_probe/`。
