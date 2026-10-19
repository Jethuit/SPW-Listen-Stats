# 0.1.34 复审

日期：2026-10-19。基于提交 `e933c7a`。本轮只审查，没有修改正式代码、构建插件包、安装或启动 SPW。

后续状态：用户已授权修复，0.1.35 已修改代码并通过 8 组封面回归及构建／包结构检查；处理记录见 [BUGFIX-0.1.35.md](BUGFIX-0.1.35.md)。下文保留原始审查证据。

## 发现

### [P2] API 自己取消封面 future 会终止行的自动重试

位置：`plugin/src/main/kotlin/com/jethuit/spw/listenstats/data/CoverStore.kt:151–152`、第 98 行的 `await()`，以及 `ui/RankingPage.kt:302–314`。

**证据：隔离运行已复现。** 当 `getCoverById()` 返回的 future 被 API 自己取消时，`get()` 抛出 `CancellationException`。当前实现将它当成读取协程被取消而重抛，使缓存层的 `Deferred` 进入取消状态。`load()` 的 `await()` 随后把这个取消传到调用方。

排行封面的 `LaunchedEffect` 没有区分“当前行离开组合”与“被等待的 API future 自己取消”。因此即使当前行仍显示、父作用域仍正常，其 `while (image == null)` 也会直接结束，无法执行后面的 30 秒延迟或任何后续自动重试。用户需要离开再返回，或触发组件 key 变化，才能重新发起加载。

这与普通 API 读取异常不同：普通异常被转成 `null`，行仍会等待并重试。它也不同于用户翻页产生的正常调用方取消，后者应继续传播。

**修复方向：** 区分 API future 取消和当前协程自身取消。在准备传播取消异常时检查当前协程状态：当前任务已取消时继续传播；仅 API future 被取消时按可重试的读取结果处理。应补充覆盖整段重试协程的回归，保留真实调用方取消的对照。

**影响边界：** 已证明当宿主返回取消的 future 时会发生；本轮没有证明 SPW 1.19.0 实际会在什么操作下取消该 future，也没有测量发生频率。不是所有封面失败都会触发这个问题。

## 为什么现有测试没有拦住

`tests/CoverRegressionTest.kt` 的 `an API cancellation can be retried immediately` 用例检查第一次加载任务确实被取消，然后手动再次调用 `load()`。它证明没有错误的失败冷却，但没有证明原来的行加载协程仍然存活。因此该用例可以通过，而页面自动重试仍然中断。

## 本轮验证

集中执行一轮隔离检查，直接编译当前 `CoverStore.kt`，使用模拟 Workshop API。检查在普通协程中运行与 `Cover()` 相同的加载／延迟循环，没有启动 Compose UI 或播放器，没有读取真实曲库、历史或机器配置。

```text
CONTROL actual caller cancellation propagates
REPRO API cancels future: row job canceled=true, row attempts=1, parent active=true
CONTROL API is available to a manual reload: calls=2; canceled row still has no retry job
```

进程退出码为 0，表示缺陷行为和对照按预期复现，不代表问题已修复。临时脚本在 Git 忽略的 `build/tests/review-0.1.34/`：

```powershell
py -3.12 build/tests/review-0.1.34/run_review.py
```

没有重复运行上一轮已通过的完整回归，也没有重建插件。

## 其他复审结论与限制

- 检查了请求等待者计数、最后等待者取消、代际与请求身份保护、完成回调移除新旧请求的关系；未发现足以报告为另一项确定 Bug 的证据。
- 日期弹窗的 Esc 处理已在初始焦点的祖先路径上，全局 Enter 拦截已移除，高度约束与纵向滚动已存在。未发现新的确定源码问题。
- 日期弹窗焦点分组、下拉 Popup 与焦点的实际配合、Tab 行为及受限高度滚动，本轮没有在宿主中实测。仍应由用户进行界面核对，不能把源码审阅视为验收通过。
