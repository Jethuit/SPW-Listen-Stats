# v0.1.2：返回箭头与宿主对齐

日期：2026-10-19。用户要求统计页左上角返回箭头的位置与样式准确匹配 SPW 模组管理页提供的截图。

## 改动

- 将统计页自绘的 Material 路径箭头换成 `BasicScreenDefaults.BackButton`，直接使用已打包的 SaltUI 原生返回控件。宿主 1.19.0 的 `BasicScreenBackButton` 经委托调用同一套 `BasicScreenDefaults.BackButton`。
- 外层按 SaltUI `DesktopTitleBar` 使用 56dp 高的行、12dp 左边距和垂直居中；按钮本身为 36dp，内边距 8dp，使用 `SaltIcons.Back` 矢量图标和原生内容色、交互反馈。
- 删除原箭头 20dp 左／上边距及灰色小图标。返回仍执行关闭统计页，概览、排行与主标题布局不改动，保留 v0.1.1 导航修复。

## 验证

37 个 Kotlin 文件构建通过，安装包结构检查通过（139 项），权限保持原样。

使用 `ImageComposeScene` 在 125% 缩放下离屏渲染真实 `HostBackButton`，与用户提供的宿主模组管理截图裁剪比较；不启动播放器、不打开窗口，不读取曲库或个人配置。截图中内容区起点为物理坐标 `(276, 50)`，x=275 是侧栏分隔线。

- 两者箭头墨迹边界均为相对内容区 `(28, 28, 47, 44)`，即物理像素 x=304～322、y=78～93，宽 19、高 16。
- 64×64 对照区域的平均颜色通道绝对误差为 0.0030（单通道范围 0～255）；没有据此宣称完整宿主页面已实机验收。
- 检查入口：`py -3.12 tests/render-back-button.py --reference <用户提供的完整宿主截图>`。参考截图和离屏产物保留在本机临时目录／`build/`，不提交用户截图。
- 离屏运行使用宿主 Skiko 原生库路径，JNA 临时目录在 `build/tests/back-button/native-temp/`。

本地安装包：`build/releases/v0.1.2/SPW-Stats-v0.1.2.spmod`，17,174,412 字节。当前公开 Release 仍为 v0.1.0，未自动发布。用户需本地导入后核对完整页面及交互。
