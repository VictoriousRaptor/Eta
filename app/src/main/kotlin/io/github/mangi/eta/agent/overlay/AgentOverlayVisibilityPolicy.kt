package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import io.github.mangi.eta.agent.tool.AgentToolRequirements

/** 浮窗跟随实际手机操作权或等待用户回答；模型生成工具参数不表示已经可以操作前台。 */
internal object AgentOverlayVisibilityPolicy {
    fun shouldRevealFor(event: AgentEvent, appVisible: Boolean = false): Boolean {
        if (appVisible) return false
        return when (event) {
            is AgentEvent.UserInputRequested -> true
            is AgentEvent.GuiControlChanged -> event.status == GuiControlStatus.Acquired
            is AgentEvent.ToolFinished -> isForegroundOperationTool(event.name)
            is AgentEvent.ToolImagesAttached -> isForegroundOperationTool(event.toolName)
            else -> false
        }
    }

    /** 活跃运行曾请求展示，且应用退至后台时恢复浮窗。 */
    fun shouldRestoreFor(
        activeRun: Boolean,
        overlayRequested: Boolean,
        appVisible: Boolean,
    ): Boolean = activeRun && overlayRequested && !appVisible

    fun shouldShowResultCard(
        hasExecutedForegroundTool: Boolean,
        appVisible: Boolean = false,
        hasPresentedClarification: Boolean = false,
    ): Boolean = !appVisible && (hasExecutedForegroundTool || hasPresentedClarification)

    fun shouldDismissEntrySurfaceFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.UserInputRequested -> true
        is AgentEvent.GuiControlChanged -> event.status == GuiControlStatus.Acquired
        else -> false
    }

    internal fun isForegroundOperationTool(name: String?): Boolean =
        AgentToolRequirements.requiresGuiControl(name)

    internal fun requiresEntrySurfaceDismissal(name: String?): Boolean =
        AgentToolRequirements.requiresGuiControl(name) || AgentToolRequirements.hasForegroundIntentFallback(name)

    internal fun shouldRecordForegroundExecution(
        event: AgentEvent,
        entrySurfaceReady: Boolean,
    ): Boolean = entrySurfaceReady && event is AgentEvent.ToolFinished &&
        requiresEntrySurfaceDismissal(event.name) && event.success != false

    /**
     * App 自身聊天页发起的任务，当用户仍停留在 Eta 前台且未启用后台虚拟屏时不再叠加系统浮窗，
     * 避免遮挡已经在展示实时过程的对话流；若后台虚拟屏已激活，则保留顶部微缩镜胶囊供用户随时展开查看或接管。
     */
    internal fun shouldSuppressOverlayInEtaUi(
        handoffSource: String?,
        etaUiInForeground: Boolean,
        virtualDisplayActive: Boolean = false,
    ): Boolean = !virtualDisplayActive &&
        etaUiInForeground &&
        handoffSource == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE

    internal fun shouldShowResultCard(
        hasExecutedForegroundTool: Boolean,
        handoffSource: String?,
        etaUiInForeground: Boolean,
    ): Boolean = hasExecutedForegroundTool && !shouldSuppressOverlayInEtaUi(handoffSource, etaUiInForeground, virtualDisplayActive = false)
}
