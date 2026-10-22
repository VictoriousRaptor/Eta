# custom 相对 upstream/main 的修改

本页记录 custom 的功能差异及同步上游时需要保留的行为。对照基线为 upstream/main `5e9d3a7b5a7b9d348f36222a629d0626a98ccd0b`（3.3.3），说明已包含 2026-10-23 的聊天队列 Steer 兼容修改。这里描述当前源码行为，不表示所有修改均已提交或推送。

完整运行架构见 [Agent Runtime](AGENT_RUNTIME.md)。

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

版本号、依赖、Manifest 权限、构建配置和 CI 沿用该上游基线。本地 debug 签名 Release 是构建产物，不构成 custom 新增的生产签名策略。

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

## 浮层、草稿和常亮

进入 Eta 前台时移除胶囊、边框和结果窗口，保留活跃任务与未回答的问题。回到后台，只为仍活跃且此前请求过浮层的 run 重新创建窗口。Clarify 可独立触发展开胶囊；在后台呈现过澄清的任务结束后，也可展示结果卡片，用户从结果卡片回到会话继续。

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

胶囊和边框可能由 GUI 或 Clarify 触发，并可跨后续思考和暂停保留；它们的可见性不等于当前正在执行 GUI。保持屏幕的请求也不等于 CPU、网络或进程保活保证。若要改为仅运行期间常亮，需明确改变暂停、结果或助手输入期间的行为，不能将固定 flag 当成完全等价的重复代码删除。

## 主要代码入口与验证边界

| 职责 | 入口 |
| --- | --- |
| 聊天发送、队列 Steer、回执清稿 | [AgentAppState](../app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt)、[AgentChatInputBar](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatInputBar.kt)、[AgentChatQueueStrip](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatQueueStrip.kt) |
| 澄清工具及控制 | [AgentInteractionToolCatalog](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentInteractionToolCatalog.kt)、[AgentLoop](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentLoop.kt)、[AgentRunController](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRunController.kt)、[AgentUserInput](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentUserInput.kt) |
| 共用卡片和分页 | [AgentClarificationCard](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentClarificationCard.kt)、[AgentClarificationPager](../app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentClarificationPager.kt) |
| 窗口与服务草稿 | [AgentRuntimeService](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt)、[AgentOverlayInteractionDraft](../app/src/main/kotlin/io/github/mangi/eta/agent/overlay/AgentOverlayInteractionDraft.kt)、[EtaUiVisibility](../app/src/main/kotlin/io/github/mangi/eta/ui/app/EtaUiVisibility.kt) |
| 配置冻结与权限 | [AgentRuntimeRequestConfigResolver](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeRequestConfigResolver.kt)、[AgentRuntimePolicy](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimePolicy.kt)、[Prefs](../app/src/main/kotlin/io/github/mangi/eta/config/Prefs.kt) |

定向测试覆盖队列文字/附件与撤回、Steer 接收/拒绝和终态竞态、Clarify 状态、窗口重建恢复及常亮请求。构建、Robolectric 和模拟 Binder 测试不等同于真机 OEM 行为、真实跨进程和模型端到端验收；具体执行结果与 APK 信息保留在每次验证报告中。
