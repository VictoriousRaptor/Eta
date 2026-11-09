# custom 相对 upstream/main 的修改

本页记录 custom 相对当前上游的功能差异。对照基线为 upstream/main `300b0d186d6339ee95d2f8b40aa19165b4b71e8c`（3.4.1）。custom 采用上游的多会话并行、Subagent、后台虚拟屏、GUI 控制权互斥、Gemini Interactions、浏览器会话及恢复逻辑，再适配以下增量。这里描述源码行为，不表示已经推送或完成真机验证。

custom 差异随上游和本分支持续变化。同步时重新核对实际行为；上游已实现相同功能时采用上游实现，删除重复 custom，部分覆盖时仅保留缺少的增量。下表不是永久保留清单。

完整运行架构见 [Agent Runtime](AGENT_RUNTIME.md)。

## 3.4.1 同步与上游复用

本轮接入上游自 3.4.0 起的 17 个提交，包括 COUI/Miuix 样式切换、系统分享、会话置顶与分支、回答性能统计、本机工具逐项/按组开关、Grok Responses 修正，以及虚拟屏焦点和无障碍观察生命周期修复。

| 范围 | 本轮决定与依据 |
| --- | --- |
| Steer、回执及队列 | 上游仍有普通排队和中断发送，尚未覆盖 custom 的同 run 非中断发送与回执清稿，保留这些增量 |
| Clarify、草稿、历史及浮层恢复 | 上游尚无完整结构化问答链路，保留并接入现有运行登记、事件和恢复模型 |
| 对话分支与 Clarify | 沿用上游分支实现，仅补齐澄清问答的轮次识别和 ID 重映射；问题卡片、回答及过渡正文不成为分支候选，最终回复仍可分支 |
| 设置页 | 采用上游两套原生设置布局；将中途插话、不确定时问我、应用内网页开关分别接入 COUI 与 Miuix，不恢复已被上游淘汰的旧设置包装组件及工具分组开关 |
| 浮层主题及链接路由 | 采用上游共享 `AgentAppTheme`；删除旧的浮层专用 `AppUriHandler` 重复注入，由共享主题向主界面和运行浮层提供同一链接策略 |
| 工具过滤与性能统计 | 采用上游工具禁用集合和计时实现；Clarify schema 同样经过上游目录过滤，保留上游的调用计时、Steer 后指标重置和用量投影 |
| 常亮、默认助理、Custom Tabs | 上游尚未覆盖各自的完整用户行为，保留增量；默认助理配置、Agent 自动化浏览器和外部下载仍沿用各自入口 |

上述决定按旧 custom 最终文件树和新上游实际源码核对。没有发现可由上游完整替代的独立 custom 功能；浮层主题及其链接上下文的重叠实现已收敛到上游共享入口。

### 本轮验证（2026-11-09～10）

生产及测试代码编译、Debug APK 打包、Release Lint 已完成；Lint 为 0 错误、416 警告、1 条提示。首轮全量 JVM/Robolectric 测试执行 2131 项，其中 62 项失败、8 项跳过。

其中 5 项浮层测试失败来自旧夹具缺少新主题的初始化、软件绘制回退及实际布局/绘制条件。补齐夹具并保留原行为断言后，浮层绘制 9 项、浮层生命周期 26 项、对话分支边界 10 项，共 45 项定向复验全部通过。绘制测试包含 COUI/Miuix 两种样式的补充输入与发送。

其余 57 项失败已在相同 JDK、缓存和 Windows 环境下的纯上游 `300b0d1` 独立工作树中逐项复现；复验本分支时也保留相同的失败集合。完整日志、分轮 XML 和比较结果保留在本地 `.analysis`。这不表示全量测试通过，也不代替真机、真实跨进程或模型端到端验收。

## 差异总览

| 范围 | 对照上游的能力 | custom 的增加或调整 |
| --- | --- | --- |
| 聊天页发送 | 运行中短按排队；长按和队列闪电中断旧 run，收尾后发送新任务；底层和浮层已有 steering | 保留主发送短按/长按；将运行中的队列闪电改成同 run 非中断 Steer；输入框有文字时另显示补充闪电 |
| 接收确认 | 队列保存待发正文、图片和文件，可撤回编辑 | 同 run Steer 等接收确认后完成清稿；拒绝时保留输入草稿或恢复队列项，防止旧 run 的操作变成新任务 |
| Clarify | 没有结构化 request_user_input 工具及问答卡片 | 提供结构化问题、选项、自由回答和分页；聊天页与运行胶囊共用卡片 |
| 交互控制 | 已有暂停、恢复、取消及 steering | 澄清等待与用户暂停独立，回答不解除暂停；校验 run、request 和问题 ID，拒绝过期或重复回答 |
| 交互设置 | 没有 custom 的两项交互开关 | 默认开启「中途插话」「不确定时问我」；Steer 读取当前开关，Clarify 在 run 开始时冻结 |
| 浮层生命周期 | 补充输入主要保存在组件本地状态 | Eta 前台移除浮层，后台恢复仍活跃且被请求的浮层；Service 保存跨窗口重建的补充和澄清草稿 |
| 常亮 | 通过运行状态控制聊天页和胶囊 View，暂停/取消/终态关闭动态请求 | 为可见胶囊、结果卡片及系统助手交互窗口保留固定常亮 flag；边框无独立常亮请求 |
| 历史与恢复 | 已有运行事件、checkpoint、归档及重连投影 | Clarify 请求和回答接入这些链路；历史编辑不把澄清回答误当成新用户任务 |
| 默认数字助理 | 目标为系统默认/OEM 时，检测到 Eta/Gemini 绑定就自动清理，不受自动设置开关控制 | 系统默认/OEM 保留现有绑定；关闭自动设置时不主动配置默认助理；仅在开关开启且目标为 Eta/Gemini 时自动配置 |

版本号、Manifest 权限、构建配置和 CI 沿用该上游基线。用户点击链接的 Custom Tabs 功能另引入 AndroidX Browser，开关与回退路径见 [应用内链接](IN_APP_LINKS.md)。本地 debug 签名 Release 是构建产物，不构成 custom 新增的生产签名策略。

## 与上游多会话运行的整合

运行登记与聊天运行状态使用上游的 `RuntimeRunRegistry` 和 `AgentRunStore`。Steer、Clarify、草稿及接收回执均归属于原 run 和会话，切换会话不会改变正在提交的交互目标。迟到的澄清回答和回执不会清理同会话后续问题的提交状态。

浮层优先跟随上游的实际 GUI 控制者，工具名称和参数生成不推断控制权。没有 GUI 控制者时，待回答的澄清可展示临时胶囊；交互草稿与展开状态由各 run 分别保存。澄清不自行取得 GUI 控制权，其他会话事件也不会覆盖当前 GUI 浮层。

Messenger 消息编号沿用上游的多 run 查询协议，custom 的 Steer、Clarify 回答和接收确认另用独立编号，避免自动合并后将查询与交互错误分派。

## 与 Subagent、虚拟屏的整合

Subagent 沿用上游的并发派发、独立配置和过程检视。子任务不直接向用户发起 Clarify；缺少条件时向父任务说明，再由父任务按当前 Clarify 开关提问。即使自定义子任务工具配置为 `*`，也不会提供 `request_user_input`，以免进入没有问答界面承接的等待。

虚拟屏保留微缩镜、展开画布、手动接管和主屏移交。出现 Clarify 时优先展示问答卡片，保留原来的展开状态；回答后恢复画布或收起布局。问答和补充输入期间暂停画布的高频预览，重新展示画布时恢复；隐藏窗口时也停止高频预览。窗口高度仍受可用屏幕空间约束。

Eta 前台继续采用 custom 的统一隐藏规则，包括虚拟屏活跃时；退到后台后，仅恢复入口校验已通过、仍活跃且曾请求展示的运行。虚拟屏状态变化及上游新增的 Activity 可见性通知共用这条恢复路径，不绕过入口校验或重新显示已经结束的运行。

## 聊天页：排队、中断与 Steer

排队、中断和 Steer 保持独立：

| 用户操作 | 当前行为 |
| --- | --- |
| 主发送短按 | 运行中将文字和附件排入队列，当前任务自然结束后按上游规则发送 |
| 主发送长按 | 中断当前 run，收尾后优先发送新任务，继续使用上游 immediateInterrupt 机制 |
| 队列闪电 | 运行中把纯文字队列项发送到当前 run，不取消模型请求或工具，不新建 run |
| 输入框补充闪电 | 运行中发送当前输入框的纯文字，使用同 run Steer；空输入或只有空白时隐藏 |
| 点击队列正文 | 撤回输入框编辑；保留当前未发送文字并合并队列中的图片和文件 |
| 空闲时队列箭头 | 沿用上游立即发送队列项的行为 |
| 手动停止 | 保留剩余队列，不自动开始下一项 |

Steer 仍为纯文字协议。含图片或文件的队列项保留正常排队、撤回编辑和空闲发送；运行中禁用其闪电，避免只发正文却遗漏附件。输入框带附件时也不允许通过补充闪电发送。

队列 Steer 在请求接收确认期间暂存该项，避免旧 run 此时结束后又将它自动发送。拒绝后按原队列相对顺序放回，同时保留其他队列修改及新的中断任务优先级。队列 Steer 不会清除输入框，即使两处文字相同。运行/空闲动作保留点击时的意图，过期闪电不会回退成启动新任务。

输入框 Steer 仅在接收成功、文字及输入同步版本仍匹配、且未进入历史编辑时清稿。回执按 sequence 消费，避免切换会话后再次应用旧回执；成功时也处理匹配的会话源草稿，避免重建后恢复已发送内容。用户后来撤回或编辑的内容继续保留。

## Clarify 与设置

`request_user_input` 每次允许 1～3 个问题，每题最多 4 个建议选项，支持单选、多选和自由文本。聊天页与胶囊共用 `AgentClarificationCard`，多题按页填写，保留前面各题的答案与选项。

澄清期间后续模型请求和工具批次等待回答。Steer 队列、澄清等待、用户暂停分别管理：回答不会自动解除用户暂停，取消会结束等待并拒绝后续答案。控制消息核对 run ID、request ID 和完整问题 ID 集合；过期、重复或错误请求不会被接收为新任务。

两项本地开关均默认开启，不依赖 Root 或 LSPosed：

- `AGENT_STEER_ENABLED`（中途插话）：提交时读取最新值，同时控制输入框、队列及浮层 Steer；关闭不禁用主发送的正常排队和长按中断。
- `AGENT_CLARIFY_ENABLED`（不确定时问我）：在 run 开始时冻结，修改对新任务生效；关闭后不向模型公开澄清工具。压缩与角色回复改写不公开该工具。

OAuth/语音入口重建模型配置时保留入口冻结的 `clarifyEnabled`，随后仍与 Runtime 权限取交集，避免换配置时意外重新启用或关闭澄清。

澄清请求与回答进入 Binder、运行事件、checkpoint、归档及 session 重连回放。重连投影清理旧的澄清状态以避免重复；历史编辑寻找用户轮次边界时跳过澄清答案。进程死亡沿用原有中断恢复策略，不假装仍有活动的澄清等待，也不重放工具。

对话分支采用上游的完整上下文及工具闭合检查。Clarify 问题和回答仍属于原轮次，不把问题卡片或此前的过渡正文当成已完成回复；分支复制与再次分支时保留澄清 ID 前缀，最终回答可正常作为分支边界。

## 浮层、草稿和常亮

进入 Eta 前台时移除胶囊、边框和结果窗口，保留活跃任务与未回答的问题。回到后台，只为仍活跃且此前请求过浮层或仍有待回答澄清的 run 重新创建窗口。Clarify 可临时独立触发展开胶囊；回答后，若此前没有 GUI 浮层需求则移除临时窗口，否则恢复澄清前的展开状态。在后台呈现过澄清的任务结束后，也可展示结果卡片，用户从结果卡片回到会话继续。

浮层草稿由已有 Service 持有，保存补充文字、补充输入模式、Clarify 答案、多选及页码。移除旧 ComposeView 后会创建新实例，组件本地 remember 不能跨该边界；仅设置 SavedStateRegistryOwner 也不能替代保存与恢复交接。因此保留这份纯数据内存状态，无需新增持久化机制或状态容器。新 run、终态及 Service 销毁时清空，旧窗口回调按 run/request ID 拒绝；新 run 同时恢复普通胶囊焦点与高度。

该草稿不承诺跨进程死亡保存，也不与主聊天输入框自动同步。

常亮分两层：

| 情况 | 当前请求 |
| --- | --- |
| 活跃且未取消/暂停的 run | 沿用上游动态状态，绑定当前聊天页与胶囊 View；未暂停的 Clarify 等待也在其中 |
| 暂停后仍可见的胶囊 | 上游动态请求关闭，但 custom 固定窗口 flag 仍申请常亮 |
| 可见的结果卡片、助手交互窗口 | custom 窗口 flag 独立申请常亮 |
| 装饰性高亮边框 | 不申请常亮，胶囊负责同屏交互窗口的请求 |
| 窗口已移除 | 该窗口不再申请常亮；没有额外 WakeLock 或系统息屏时间修改 |

由 GUI 请求的胶囊和边框可跨后续思考和暂停保留；仅由 Clarify 临时触发的窗口在回答后移除。它们的可见性不等于当前正在执行 GUI。保持屏幕的请求也不等于 CPU、网络或进程保活保证。若要改为仅运行期间常亮，需明确改变暂停、结果或助手输入期间的行为，不能将固定 flag 当成完全等价的重复代码删除。

## 默认数字助理：保留用户选择

实现入口为 `AssistantBinding` 和 `AssistantManager`；当前相对上游仍有以下行为差异：

| Eta 设置 | 当前行为 |
| --- | --- |
| 长按电源键目标为「系统默认/OEM」，自动设置开启或关闭 | 保留系统现有默认助理，不自动清空绑定，也不自动恢复原厂助理 |
| 目标为 Eta/Gemini，自动设置关闭 | 保留系统现有默认助理，由用户在系统设置中管理 |
| 目标为 Eta/Gemini，自动设置开启 | 沿用上游自动配置、校验和刷新对应默认助理的行为 |

删除 OEM 恢复分支及其专用清理代码。设置变化、系统启动完成、用户解锁或切换，以及配置异步回调均不会再因目标为 OEM 而清空 `assistant`、`voice_interaction_service` 或 ASSISTANT Role。用户手动设置的 Gemini 也不会被该分支清理；如需切回原厂助理，需在系统设置中手动选择。

配置期间切到 OEM 或关闭自动设置后，回调会停止后续配置；已经发给 Android 的 Role 请求无法取消，本修改不承诺撤销已发出的请求。开启自动设置且目标为 Eta/Gemini 时，原有强制刷新中临时清空再写回 Secure 配置的行为保留。电源键唤起、圈即搜和聊天/Agent 逻辑未作调整。

`AssistantBindingTest` 覆盖全部目标与自动设置开关组合，以及配置期间切到 OEM 的失效判断；连同 `PowerAssistantTargetTest` 共 10 项测试通过。Debug 构建、Release/R8 构建及 Debug 签名验证通过，尚未验证真机 OEM 行为。

## 主要代码入口与验证边界

| 职责 | 入口 |
| --- | --- |
| 聊天发送、队列 Steer、回执清稿 | [AgentAppState](../app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt)、[AgentChatInputBar](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatInputBar.kt)、[AgentChatQueueStrip](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatQueueStrip.kt) |
| 澄清工具及控制 | [AgentInteractionToolCatalog](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentInteractionToolCatalog.kt)、[AgentLoop](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentLoop.kt)、[AgentRunController](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRunController.kt)、[AgentUserInput](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentUserInput.kt) |
| 共用卡片和分页 | [AgentClarificationCard](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentClarificationCard.kt)、[AgentClarificationPager](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentClarificationPager.kt) |
| 窗口与服务草稿 | [AgentRuntimeService](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt)、[AgentOverlayInteractionDraft](../app/src/main/kotlin/io/github/mangi/eta/agent/overlay/AgentOverlayInteractionDraft.kt)、[EtaUiVisibility](../app/src/main/kotlin/io/github/mangi/eta/ui/app/EtaUiVisibility.kt) |
| 配置冻结与权限 | [AgentRuntimeRequestConfigResolver](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeRequestConfigResolver.kt)、[AgentRuntimePolicy](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimePolicy.kt)、[Prefs](../app/src/main/kotlin/io/github/mangi/eta/config/Prefs.kt) |
| 默认数字助理配置 | [AssistantBinding](../app/src/main/kotlin/io/github/mangi/eta/hook/system/AssistantBinding.kt)、[AssistantManager](../app/src/main/kotlin/io/github/mangi/eta/hook/system/AssistantManager.kt)、[AssistantBindingTest](../app/src/test/kotlin/io/github/mangi/eta/hook/system/AssistantBindingTest.kt) |

定向测试覆盖队列文字/附件与撤回、Steer 接收/拒绝和终态竞态、Clarify 状态、窗口重建恢复及常亮请求。构建、Robolectric 和模拟 Binder 测试不等同于真机 OEM 行为、真实跨进程和模型端到端验收；具体执行结果与 APK 信息保留在每次验证报告中。
