# SPW AutoMix 技术可行性与实施方案

调研基线为 2026-11-23 的 Workshop API 主线与本机 Steam 版 SPW 1.19.3。本阶段只做了源码和安装文件的静态调查。产品目标是让相邻本地歌曲在 SPW 正常播放流程中自然衔接，用户可逐对修改过渡。

## 路线结论

**优先验证替换 Morvanium 的过渡计划，让 Pisces 继续执行和输出。** 本机播放器的调度代码先预览下一项，再调用独立的规划方法；执行端另有两条源流、混音同步、参数包络及队列提交机制。这比直接操作音频句柄更接近所需的扩展边界。最值得优先验证的问题是：由插件提供的一份计划，能否准确改变两轨的入点、增益及低频交接，同时让宿主照常处理暂停、切歌、听歌记录和设备。

| 路线 | 可获得的能力 | 主要代价及选用条件 |
| --- | --- | --- |
| 宿主管设备与执行，插件只提交计划 | 最大限度沿用原队列、输出模式、切歌取消及元数据。插件可以独立决定选点和曲线 | 计划对象和执行器是私有实现。每项 DSP 必须证明宿主会执行，计划表达不了的效果应标为不可用 |
| 插件渲染，宿主管设备 | 插件可逐采样控制两轨，预渲染混合块或提供单个浮点源，由宿主负责最终输出 | 尚未发现公开的替代输入源接口。须证明宿主能拉取该源、按正确时间轴提交 B，并在 seek 时丢弃旧缓冲。仅有主输出上的后置 DSP 无法分开已经相加的两轨 |
| 独立解码、混音及输出 | 离线可完整控制，便于试听、测量和复现；显式独立预览也可行 | 正常播放要解决输出独占权、设备热切换、系统媒体控件和队列同步。仅暂停宿主再另开播放器无法保证这些条件。未证明所有权交接前，仅用于离线文件或独立预览 |

## 宿主证据与边界

**公开契约。** 实查上游 HEAD `cbf20375`，API 工程版本及最新标签为 `0.1.0-dev23`。Playback 能获取当前项、暂停、继续、上下首、seek 和切换独占；当前项查询需要 `LIBRARY_READ`，可能阻塞。PlaybackExtensionPoint 有状态、seek 通知和每秒位置通知。未发现下一播放项预览、设备渲染时钟、逐轨句柄、PCM 源或计划提供者。不能从曲库分页顺序推断随机队列，也不能用每秒回调驱动拍级自动化。Hook 注册机制由官方提供，需 `CLASS_TRANSFORM`，普通 Hook 不支持 native、abstract、suspend、构造器和已经内联的调用。它为实验计划适配提供入口，具体目标签名没有跨版本保证。[固定版本源码](https://github.com/Moriafly/spw-workshop-api/tree/cbf20375b95b42d95c3fb16f1ceb53f4befc205c/api/src/main/java/com/xuncorp/spw/workshop/api) · [官方 Hook 文档](https://saltplayer.com/workshop/hook)

**检查对象。** 版本来自安装配置的 `jpackage.app-version`。直接读取安装目录下包含 JVM 类的 `app/ffmpeg-x64.dll`，SHA-256 为 `75eb9f7185acbbb2302fbcf3356d70f08958a410d2eaf32443abedddafa0e9a0`。通过归档条目、Kotlin 元数据与 JVM 调用指令交叉定位，未加载音频 DLL。下面采用语义名描述职责，混淆后的 JVM 名称仅作为该样本的复查坐标。

| 技术问题 | 本机证据 | 仍需证明 |
| --- | --- | --- |
| 是否有双源混合 | Pisces 播放器包含创建 FileTempoStream、加入 MixStream、准备及回滚过渡的路径。AutomixExecutionEngine 引用 incomingStream、outgoingStream；BassMixImpl 绑定加源、移源和每源定位。安装包带 bassmix.dll | 用可区分的双源信号录到真实重叠，确定暂停、释放和缓冲边界 |
| 决策与执行能否分离 | Morvanium 设置桥只转发启用开关。调度协程中可见 onPeekNext 调用，随后调用 MorvaniumArrangedTransitionPlanner 的普通实例方法，两个分析对象、宿主能力、一个 long、处理能力与曲目关系进入该方法，返回编排结果。另有 prepared、active、mixer sync、commit、rollback 路径 | Workshop 是否接受该目标；返回对象构造的不变量、拒绝计划的语义、实际触发条件和提前量 |
| 每轨进度、增益及 EQ | TransitionPlan 元数据含双 cue、双播放速率及 gainCurve。BASSmix 绑定 ChannelSetPosition、ChannelSetEnvelope。执行器中有按目标选流、effectEnvelopes、low_shelf_gain_db；包内还有 BASS_FX_TempoCreate | 验证哪些参数对两轨分别生效，是否真正保音高、是否支持三频段和连续自动化。BASSmix 音量包络本身只处理其规定的属性，三段 EQ 需独立效果链 |
| 提前知道下一首及预解码 | PlaybackService 有 onPeekNext、onGetAutomixRelationship、onCommitAutomixNext；调度中有 incomingAnalysis/outgoingAnalysis 和 prepared 流。内部也有 PlaybackQueueState | 预览是否与最终提交一致、队列插播和随机重排的失效时机，以及短曲、慢盘下的有效提前量。插件可提前读已确认的本地文件 |
| 输出与系统功能 | 安装文件有 BASS WASAPI、ASIO 回调及相应 DLL；官方文档另列 DirectSound。ListenRecorder 和视频音轨切换器均有 AutoMix 状态观察点 | 静态证据不能决定各后端、独占设置、睡眠定时和视频音轨下是否实际支持过渡。按模式验收，保留宿主自己的禁用条件 |

复查规划调用时，可在当前样本的 `com/xuncorp/pisces/\u0528$\u07a5` 类中查看反汇编。字节码偏移 438 调用 `onPeekNext(Continuation)`，1170 调用 `androidx/compose/ui/\u013f.\u037f`，后者返回 `androidx/compose/ui/\u0574`，签名没有 Continuation。前者是队列职责的证据，后者才是普通方法 Hook 的候选。正式适配应核对调用点、类型形状、能力描述和宿主指纹；发现歧义就保留原方法。关闭 Morvanium 开关可能连同此调度一起关闭，需验证。计划替换成功时沿用同一次宿主过渡，避免两个调度器同时发声。[宿主更新日志](https://moriafly.com/program/spw/changelog.html) · [SPW 输出模式](https://moriafly.com/program/spw/doc/audio-engine.html) · [BASSmix 源包络语义](https://www.un4seen.com/doc/bassmix/BASS_Mixer_ChannelSetEnvelope.html)

## 参考工程与依赖选择

| 参考 | 源码中的做法 | 本项目取舍 |
| --- | --- | --- |
| Mixxx | analyzerbeats 区分固定速度假设、网格版本及首拍位置；引擎分别处理同步、变速与分频 | 单个 BPM 不能决定拍相位。保存源文件拍点，逐段决定能否跟拍。用不同瞬态与立体声素材比较伸缩器。[节拍分析](https://github.com/mixxxdj/mixxx/blob/825e45199b1c68e42035eea30cd796eb7577a841/src/analyzer/analyzerbeats.cpp) · [伸缩器](https://github.com/mixxxdj/mixxx/blob/825e45199b1c68e42035eea30cd796eb7577a841/src/engine/bufferscalers/enginebufferscalerubberband.cpp) |
| SPlayer-Next | planner 在有效网格区间枚举重拍候选，结合主体结束点；原生伸缩器处理输入、输出延迟。文档另述用分离声部复核主唱重叠和改变低音交接 | 借鉴候选比较、拒绝条件和伸缩预热。首版用整轨 EQ 和保守的人声冲突估计；多声部分离单列实验，避免把中频能量误当确定的人声。[选点代码](https://github.com/SPlayer-Dev/SPlayer-Next/blob/5772455c88c88983800d14d1011c84aa08fbd1e8/electron/main/services/automix/planner.ts) · [伸缩代码](https://github.com/SPlayer-Dev/SPlayer-Next/blob/5772455c88c88983800d14d1011c84aa08fbd1e8/native/audio-engine/src/dsp/automix_tempo.rs) · [AutoMix 文档](https://splayer-next.imsyy.top/automix.html) |

**推荐技术栈。** 独立插件使用 Kotlin/JVM、Workshop API、PF4J，后期编辑器使用 Compose。第一版 DSP 选 C++、CMake、float32 双声道与 Signalsmith Stretch，独立命令行渲染；先用 WAV PCM 输入输出，之后接入经审查的多格式解码器。离线和实时共用计划格式与时间定义。若宿主能执行该计划，常规播放无需再加载第二套伸缩器。需要自有实时渲染时再增加薄 JNI 批量块接口。分析结果与手动覆盖建议存 SQLite，音频本体留在用户文件中。[Signalsmith 的延迟、自动化和许可说明](https://signalsmith-audio.co.uk/code/stretch/)

Workshop API 为 Apache-2.0，Mixxx 主项目为 GPL-2.0-or-later，SPlayer-Next 主项目为 AGPL-3.0；本次仅研究其设计。Signalsmith Stretch 为 MIT，分发时核对固定版本及依赖的通知文件。Rubber Band 可作离线音质对照，正式集成需按 GPL 或商业授权评估。宿主附带的 BASS、解码 DLL 和参考项目模型权重分别审查；本次交付没有引入这些代码、二进制或模型。[Workshop 许可证](https://github.com/Moriafly/spw-workshop-api/blob/cbf20375b95b42d95c3fb16f1ceb53f4befc205c/LICENSE) · [Mixxx 许可证](https://github.com/mixxxdj/mixxx/blob/825e45199b1c68e42035eea30cd796eb7577a841/LICENSE) · [SPlayer-Next 许可证](https://github.com/SPlayer-Dev/SPlayer-Next/blob/5772455c88c88983800d14d1011c84aa08fbd1e8/LICENSE) · [Rubber Band 授权](https://breakfastquay.com/rubberband/license.html)

## 模块与时间轴

1. **音频分析**在可取消的后台任务中生成 TrackAnalysis。保存源采样率、有效区段、拍点、强拍、局部拍号与速度、结构边界、能量、低频、静音、人声活动估计和调性（含 Camelot）。按特征及区段保存置信度，允许未知。首轮用 onset 与局部节奏一致性估拍、色度分布估调、谱变化与重复性找段界；弱证据下只标边界。先分析首尾候选窗，发现曲首过长、渐变速度或结构不明时扩大分析范围。缓存键包含文件身份、内容指纹、有效轨段、分析器版本和设置。文件属性改变时重验内容，读取期间变更则丢弃该结果，完整结果事务提交。
2. **过渡规划**枚举 A 出点、B 入点和 2/4/8 小节重叠。先检查文件边界、有效拍网、拍号、可用提前量及执行能力；再比较乐句边界距离、速度偏差、和声距离、能量落差、低频叠加强度与主唱重叠。每一项按置信度计权，保留候选、分项代价、拒绝原因和人工覆盖记录。检查半速/倍速歧义与整段漂移，网格差时不拉速。稳定同拍选长过渡，双低音选强拍换低音，主唱冲突先改位置再缩短重叠。降速/提速初始实验限幅建议 ±4%，之后通过试听校准。无合适候选时选短淡化，连续现场录音、同专辑连续轨或淡化也会破坏起音时交还宿主原生衔接。
3. **共享计划**保存 A/B 有序文件身份、源 cue、源拍点映射、目标输出帧、每轨速度/增益/EQ 等参数点、插值、策略与版本。源 cue 属于各自的解码时间轴；渲染以唯一输出帧时钟运行。按相邻小节强拍之间的实际拍网计算时长。B 变速期间由速率曲线积分求源位置；交接后若需恢复原速，安排渐回并验证尾部拍差。预设只是带约束的参数集合，歌曲对覆盖优先；文件或网格改变后提示复核旧 cue。
4. **双轨渲染**分别解码、对齐采样率、预热伸缩器、补偿延迟，然后逐轨分频/EQ、增益、滤波与效果，合入同一浮点总线。按局部响度匹配，保留峰值余量，记录真峰值和限幅器压缩量。Bass Swap 让 A 和 B 的低频在选定强拍交接，检查分频重组和相位抵消；等功率曲线也可能让相关信号过载。效果尾音留在同一输出总线衰减，循环按拍点对齐加微交叉窗。实时回调只处理已准备的块和参数，解码预读、文件 IO、分析、分配与日志写入移到工作线程。
5. **宿主适配与编辑器**分别消费同一份计划。宿主负责当前播放项、音量、设备、状态广播及队列提交；插件按会话代号丢弃过期计划。手动上/下一首、seek、队列改动、设备重建或停用时撤销预备任务与未执行曲线；正在混合时由已验证的宿主回滚/结束路径平滑收束。暂停同时冻结两轨与自动化。未知版本、撤权或能力不足时跳过自定义策略。编辑器只改计划，可用缓存波形和候选窗快速重渲染。每对歌曲保存的修改与全局预设分开。

## 原型、实施顺序与通过条件

| 阶段 | 交付 | 决定是否继续的证据 |
| --- | --- | --- |
| 1 · 宿主调研 | 本文为静态结论。下一次宿主实验先做只读观测，再在隔离测试配置中仅对已知曲对替换一份计划 | 记录候选 B、计划、实际提交 B 和取消顺序；改变单个 cue、单轨静音、单轨低频衰减并捕获输出。计划被拒或未生效时保留原生播放 |
| 2 · 离线混音 | CLI 输入两首本地 WAV、A/B 手动强拍 cue、拍网或 BPM 与拍号、长度及两轨曲线；输出含过渡前后上下文的 WAV、计划及测量结果 | 先用单双轨脉冲、低音和不同频率的标记检查双源确实重叠、独立增益/EQ、重采样及首末拍误差。比较原速与保音高伸缩；同一计划重复渲染可对齐。再用真实曲对听低音换手、人声和响度 |
| 3 · 自动分析和决策 | 加入特征缓存、置信度、候选排序、自动降级与逐对覆盖；生成可复查的选择理由 | 素材覆盖电子舞曲、双主唱、半/倍速、变拍或现场、古典弱拍、连续专辑。检查文件改动使缓存失效，长过渡、短淡化、直接衔接都能在合适条件下选到 |
| 4 · 创意工坊接入 | 独立模组目录、插件 ID 和包；优先仅转换规划结果。按能力启用已验证过渡 | 在 WASAPI 共享/独占、DirectSound 及可用 ASIO 设备分别测试。重叠前、中、后触发暂停恢复、seek、上下首、插播、随机、拔设备、禁用/卸载；核对进度、元数据、主音量、记录、原有 Morvanium 设置及释放。每次队列只前进一次，无第二输出或残留声音 |
| 5 · 预设与编辑器 | 双轨波形、歌曲/BPM/调性、重叠区与曲线、可拖动 cue、试听和逐对保存 | 自动与手动使用同一渲染语义。覆盖五种音量模式、开头/中间/末尾换低音、三频交接与快速切低音；无滤波及四种进出滤波组合；无效果及三种混响和半拍回声；无循环及 1/2/4/8 拍循环、节拍匹配开关。重开配置可还原，参数变化不触发整曲重分析 |

**原型试听与评判。** 同一曲对分别准备原生无缝/短淡化、Morvanium、自研方案；Spotify 上有相同母带且可启用对应过渡时加入对照。用整体回放增益匹配试听响度、随机化播放次序，保留过渡自身的动态。记录拍点偏差与累积漂移、过渡前后短时响度、真峰值、削波和缓冲欠载，再逐对评价鼓点双击、低频空洞、截断唱句、伸缩涂抹及乐句完整性。建议原型先以合成网格全段最大拍点误差 10 ms、交付 WAV 真峰值不高于 −1 dBTP 为初始工程目标；真实曲目的自然度需试听单独判定。保存未采用长混的理由和听感反例，不用总体平均分掩盖某类曲目的退化。

**尚未验收的决定点。** 第一项是计划替换后两轨 DSP 与宿主状态是否同时正确；第二项是各模式支持的效果集合，以及宿主是否自行覆盖计划；第三项是准备不及时或中途取消后能否只恢复一次普通播放。如果第一项失败，保留离线参考引擎，优先验证宿主拉取自定义音源或向上游提出显式的计划提供者接口。只有设备交接和播放状态职责均可验证时，再考虑独立引擎接管正常播放。
