package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.ui.components.AgentClarificationPagerState

/**
 * 浮层交互草稿状态，由 AgentRuntimeService 持有，避免 ComposeView 移除时输入丢失。
 */
data class AgentOverlayInteractionDraft(
    val supplementText: String = "",
    val supplementMode: Boolean = false,
    val clarificationState: AgentClarificationPagerState? = null,
) {
    companion object {
        val Empty = AgentOverlayInteractionDraft()
    }
}
