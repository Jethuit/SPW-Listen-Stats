# 听歌统计

Salt Player for Windows 的本地听歌统计插件：看你听了多久，也看最常听的歌、歌手和专辑。数据只存在本机。

完整说明见[插件详细说明](docs/PLUGIN_GUIDE.md)。

## 功能

- **总览**：累计时长、听歌天数、最长连续和当前连续天数。
- **热力图**：按天或按周，可选年份或最近一年，颜色阈值根据近 12 个月数据自动计算。
- **时长分布**：按小时、日期或每 7 天汇总，支持柱状、折线、平滑曲线；默认“今天 + 柱状图”，之后记住你的选择。
- **歌曲明细**：听满单曲 50% 计一次有效播放；可按歌名或歌手搜索，每页数量可自定义（默认 25）。
- **排行**：歌曲、歌手、专辑，按播放次数或收听时长排序。合作歌曲分别计入每位歌手。
- **外观**：跟随 SPW 深浅主题，适配椒盐经典、亚克力、云母和云母 Alt；页面动画可在模组配置中关闭。
- **备份**：支持导入、导出和合并去重，入口在右上角“…”菜单。时长显示四舍五入到分钟。

## 截图

以下为历史截图，仅供参考。

![深色材质下的听歌概览、时长分布与歌曲明细](https://raw.githubusercontent.com/Jethuit/SPW-Listen-Stats/main/docs/screenshots/v0.3.1/overview-dark-details.png)

![深色材质下的歌曲播放次数排行](https://raw.githubusercontent.com/Jethuit/SPW-Listen-Stats/main/docs/screenshots/v0.3.1/ranking-dark.png)

![浅色主题下的时长分布与歌曲明细](https://raw.githubusercontent.com/Jethuit/SPW-Listen-Stats/main/docs/screenshots/v0.3.1/distribution-light-details.png)

![浅色主题下的听歌热力图与时长分布](https://raw.githubusercontent.com/Jethuit/SPW-Listen-Stats/main/docs/screenshots/v0.3.1/heatmap-light.png)

## 安装与打开

1. 从 [GitHub Releases](https://github.com/Jethuit/SPW-Listen-Stats/releases) 下载 `.spmod` 安装包。
2. 在 SPW 创意工坊／模组管理中选择本地导入，按提示授权并启用。
3. 点击窗口顶部、彩色快捷按钮左侧的听歌统计图标，或在模组配置中点“打开听歌统计”。

无需额外运行环境。

## 常见问题

**更新失败？** 先在 SPW 中删除插件，再导入新的安装包。

**诊断日志在哪？** 在插件配置中开启写入诊断日志，然后查看数据目录里的 `status.log`。其中可能含本机路径，分享前请打码。

> `%APPDATA%\Salt Player for Windows\workshop\data\com.jethuit.spw.listenstats`

或

> `C:\Users\用户名\AppData\Roaming\Salt Player for Windows\workshop\data\com.jethuit.spw.listenstats`

其他问题请到 [Issues](https://github.com/Jethuit/SPW-Listen-Stats/issues) 反馈。

## 注意事项

- 数据合并自播放器本地历史、插件启用后的记录和你导入的备份，不会上传到任何服务器。
- 兼容性取决于 SPW 版本、页面结构和权限；宿主更新后可能需要适配。
- 启用前的历史受播放器保留范围限制，小时分布可能有偏差。
- 换设备或重建曲库后，歌曲记录可能对不上；导入旧版汇总备份可能少计。
- 封面取自音频内嵌图片，读不到时显示默认图。
- 背景材质不可用时，会改用纯色背景。

## 许可证

原创代码与文档采用 [MIT License](LICENSE)。第三方组件（SaltUI、sqlite-jdbc、Material Symbols）的声明见[第三方组件](docs/THIRD-PARTY.md)。
