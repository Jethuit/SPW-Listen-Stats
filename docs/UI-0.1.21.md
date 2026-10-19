# 0.1.21 控件底色与宿主字体

2026-10-19，依据用户反馈：控件底色过浅，SPW 已选 MiSans，但插件仍显示系统字体。

- 浅色控件底色由 #F5F6F8 轻微加深为 #EDEEF1，用于日/周与图表分组、日期/年份选择、搜索和分页。页面底色、热力图色阶与深色主题保持原设置。
- 0.1.20 仅在插件 SaltUI 内继承文字样式，没有把宿主字体选择传入。现从 SPW 1.19.0 的 AppConfig 读取 fontStrategy / customFontFilePath，按 FollowSystem、Custom、MiSansRegular 选择字体。
- MiSans 直接读取宿主 classloader 中的 composeResources/voxzen.composeapp.generated.resources/font/misans.ttf；自定义字体读取宿主选择的文件，先验证格式，再建立 Compose FontFamily。字体未打包进插件或提交 Git。
- 显式将字体传入插件 SaltTheme 的文字样式，覆盖正文、控件、搜索和图表；宿主字体选项变化时重新加载。读取失败记录日志并回退系统字体。
- 这些宿主设置属于内部 API，仍绑定 SPW 1.19.0。未启动宿主验收；最终视觉由用户核对。

检查完成：34 个 Kotlin 文件构建通过；6 组 UI 回归通过，包括从宿主读取的字体家族确认为 MiSans、自定义字体加载、跟随系统及损坏/丢失文件回退。包结构检查通过（116 项），diff 空白检查通过。

首次字体检查遇到 AWT 字节流校验依赖 Windows 临时目录权限的问题；改为对自定义字体直接校验原文件，对测试字体在 build/tests 隔离目录校验，修复后重建并通过 UI 回归。未扩大运行数据回归。最终产物为 build/libs/plugin-com.jethuit.spw.listenstats-0.1.21.spmod（17,094,169 字节）。以上不代表 SPW 实机渲染已验收。
