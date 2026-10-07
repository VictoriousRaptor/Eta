package io.github.mangi.eta.agent.overlay

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest

/** Agent 浮窗所处的阶段。 */
internal enum class AgentOverlayPhase { RUNNING, PAUSED, FINISHED, FAILED }

/**
 * Agent 浮窗的渲染状态。由 [AgentEvent] 流累积而来，[AgentOverlayBubble] 直接消费。
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val status: AgentOverlayStatus = AgentOverlayStatus.Reasoning,
    val detailText: String = "",
    val pendingUserInput: AgentUserInputRequest? = null,
    /**
     * 胶囊第二行：思考时是模型最近一句可见推理，执行工具时是参数摘要（如"点击「搜索」"）。
     * 只保留尾部一小段，供用户扫一眼知道 Agent 在想什么；完整推理仍在会话里。
     */
    val thought: String = "",
) {
    companion object {
        val Initial = AgentOverlayState(status = AgentOverlayStatus.Reasoning)
    }
}

/**
 * 将一个 [AgentEvent] 折叠进当前渲染状态。
 *
 * 胶囊与通知只展示用户关心的少数几个阶段：思考、操作某个工具、整理回答。
 * 请求模型、模型已响应、生成工具参数、轮次、读取截图等内部步骤不单独露出：
 * 它们每轮都会出现、持续不到一秒，频繁切换只会干扰用户。
 * 同一工具的完成与下一次调用之间也不切到"完成"，保持在操作态，避免文字来回跳。
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState {
    // 暂停在下一个检查点生效，之前已在途的工具结束等事件不能把文字切回运行态。
    if (phase == AgentOverlayPhase.PAUSED) {
        when (event) {
            is AgentEvent.UserInputRequested,
            is AgentEvent.UserInputAnswered,
            is AgentEvent.UserSupplementReceived,
            is AgentEvent.RunFinished,
            is AgentEvent.RunFailed -> Unit
            else -> return this
        }
    }
    return projectEvent(event)
}

private fun AgentOverlayState.projectEvent(event: AgentEvent): AgentOverlayState = when (event) {
    is AgentEvent.UserInputRequested -> copy(
        pendingUserInput = event.request,
        status = AgentOverlayStatus.WaitingForUser,
    )

    is AgentEvent.UserInputAnswered -> copy(
        pendingUserInput = null,
        status = if (phase == AgentOverlayPhase.PAUSED) AgentOverlayStatus.Paused else AgentOverlayStatus.Continuing,
    )

    is AgentEvent.RunStarted -> copy(phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.Reasoning, detailText = "")
    is AgentEvent.RoundStarted -> copy(round = event.round).thinkingUnlessWorking()
    is AgentEvent.ProviderRequestStarted -> copy(round = event.round).thinkingUnlessWorking()
    is AgentEvent.ModelRetryScheduled -> copy(round = event.round, detailText = event.displayMessage).thinkingUnlessWorking()
    is AgentEvent.ContextCompaction -> copy(detailText = event.displayMessage).thinkingUnlessWorking()

    is AgentEvent.AssistantBlockDelta -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> if (pendingUserInput != null) this else appendStreamingText(event)
        AgentEvent.AssistantBlockKind.THINKING -> copy(thought = appendThought(thought, event.delta))
        AgentEvent.AssistantBlockKind.TOOL_CALL -> this
    }

    // 新一轮思考从空白开始，避免把上一轮的尾句当成当前想法。
    is AgentEvent.AssistantBlockStart -> if (event.kind == AgentEvent.AssistantBlockKind.THINKING) copy(thought = "") else this

    is AgentEvent.ToolStarted -> {
        if (pendingUserInput != null) copy(round = event.round, thought = event.argsPreview.trim())
        else running(AgentOverlayStatus.RunningTool(event.name), event.round).copy(thought = event.argsPreview.trim())
    }
    is AgentEvent.HostedToolStarted -> {
        if (pendingUserInput != null) copy(round = event.round)
        else running(AgentOverlayStatus.HostedToolRunning(event.name), event.round)
    }

    // 工具结束后模型马上开始下一轮思考；直接回到思考态，由下一次工具调用切换文字。
    is AgentEvent.ToolFinished -> {
        if (pendingUserInput != null) copy(round = event.round, thought = "")
        else running(AgentOverlayStatus.Reasoning, event.round).copy(thought = "")
    }
    is AgentEvent.HostedToolFinished -> {
        if (pendingUserInput != null) copy(round = event.round, thought = "")
        else running(AgentOverlayStatus.Reasoning, event.round).copy(thought = "")
    }

    is AgentEvent.UserSupplementReceived -> copy(
        status = if (pendingUserInput != null) {
            AgentOverlayStatus.WaitingForUser
        } else if (phase == AgentOverlayPhase.PAUSED) {
            AgentOverlayStatus.Paused
        } else {
            AgentOverlayStatus.SupplementReceived
        },
        detailText = "",
    )

    is AgentEvent.RunFinished -> copy(
        pendingUserInput = null,
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        status = AgentOverlayStatus.ResultReady,
    )

    is AgentEvent.RunFailed -> copy(
        pendingUserInput = null,
        phase = AgentOverlayPhase.FAILED,
        status = AgentOverlayStatus.RunFailed,
        detailText = event.reason,
    )

    is AgentEvent.AssistantBlockEnd,
    is AgentEvent.AssistantReceived,
    is AgentEvent.ProviderResponseStarted,
    is AgentEvent.UsageReceived,
    is AgentEvent.ToolImagesAttached,
    -> this
}

private fun AgentOverlayState.running(status: AgentOverlayStatus, round: Int) =
    copy(phase = AgentOverlayPhase.RUNNING, round = round, status = status)

/** 正在执行工具、输出回答或等待用户回答时，后台的请求、重试与压缩事件不打断当前文字。 */
private fun AgentOverlayState.thinkingUnlessWorking(): AgentOverlayState = when (status) {
    is AgentOverlayStatus.RunningTool,
    is AgentOverlayStatus.HostedToolRunning,
    AgentOverlayStatus.GeneratingAnswer,
    AgentOverlayStatus.Paused,
    AgentOverlayStatus.Stopping,
    AgentOverlayStatus.WaitingForUser,
    -> this
    else -> copy(phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.Reasoning)
}

private const val MaxStreamingPreviewChars = 320
private const val MaxThoughtChars = 96

/** 合并空白并只保留尾部，胶囊里显示的是"刚刚想到哪"，不是整段推理。 */
private fun appendThought(current: String, delta: String): String {
    val merged = (current + delta).replace(Regex("\\s+"), " ").trimStart()
    return if (merged.length <= MaxThoughtChars) merged else merged.takeLast(MaxThoughtChars).trimStart()
}

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantBlockDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .take(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.GeneratingAnswer,
        detailText = nextPreview,
    )
}
