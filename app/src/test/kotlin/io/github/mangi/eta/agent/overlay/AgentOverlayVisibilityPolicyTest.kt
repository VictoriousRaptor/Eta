package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayVisibilityPolicyTest {
    @Test
    fun clarificationRevealsAnAnswerSurfaceAndKeepsPauseIndependent() {
        val request = io.github.mangi.eta.agent.runtime.AgentUserInputRequest("question", listOf(
            io.github.mangi.eta.agent.runtime.AgentUserInputQuestion("city", "目的地？"),
        ))
        val event = AgentEvent.UserInputRequested(request)
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(event))
        assertTrue(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event))
        val waiting = AgentOverlayState(phase = AgentOverlayPhase.PAUSED).applyEvent(event)
        val supplemented = waiting.applyEvent(AgentEvent.UserSupplementReceived(1, "补充"))
        org.junit.Assert.assertEquals(AgentOverlayPhase.PAUSED, supplemented.phase)
        org.junit.Assert.assertEquals(request, supplemented.pendingUserInput)
        val answered = supplemented.applyEvent(AgentEvent.UserInputAnswered(
            io.github.mangi.eta.agent.runtime.AgentUserInputAnswer(request.id, mapOf("city" to "广州")),
        ))
        org.junit.Assert.assertNull(answered.pendingUserInput)
        org.junit.Assert.assertEquals(AgentOverlayPhase.PAUSED, answered.phase)
        org.junit.Assert.assertEquals(AgentOverlayStatus.Paused, answered.status)
    }

    @Test
    fun `text-only and background tool events do not reveal operation overlay`() {
        val events = listOf(
            AgentEvent.RunStarted(
                initialImages = 0,
                initialImageBytes = 0,
                toolCount = 24,
                terminalTools = false
            ),
            AgentEvent.RoundStarted(round = 1, messageCount = 2),
            AgentEvent.ProviderRequestStarted(round = 1),
            AgentEvent.ProviderResponseStarted(round = 1, httpCode = 200),
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 7,
                delta = "thinking"
            ),
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 1,
                deltaChars = 5,
                delta = "hello"
            ),
            AgentEvent.AssistantReceived(
                round = 1,
                contentChars = 5,
                reasoningContent = "",
                toolNames = emptyList()
            ),
            AgentEvent.AssistantBlockEnd(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                index = 2,
                blockId = "call_terminal",
                name = "terminal",
                contentChars = 12
            ),
            AgentEvent.AssistantReceived(
                round = 1,
                contentChars = 0,
                reasoningContent = "",
                toolNames = listOf("run_command", "search_apps")
            ),
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_status",
                name = "run_command",
                argsPreview = "{}"
            ),
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_status",
                name = "run_command",
                resultSummary = "ok",
                imageCount = 0,
                imageBytes = 0
            ),
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_browser",
                name = "browser_use",
                argsPreview = "提取正文 · example.com"
            ),
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_browser",
                name = "browser_use",
                resultSummary = "ok=true, action=get_readable, host=example.com",
                imageCount = 0,
                imageBytes = 0
            ),
            AgentEvent.RunFinished(round = 1, contentChars = 5)
        )

        assertFalse(events.any(AgentOverlayVisibilityPolicy::shouldRevealFor))
    }

    @Test
    fun `screen observation reveals operation overlay after observation finishes`() {
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call_observe",
                    name = "observe_screen",
                    argsPreview = "{}"
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call_observe",
                    name = "observe_screen",
                    resultSummary = "ok",
                    imageCount = 1,
                    imageBytes = 2048
                )
            )
        )
    }

    @Test
    fun `foreground operation tools reveal operation overlay before execution`() {
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.AssistantBlockEnd(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                    index = 0,
                    blockId = "call_1",
                    name = "tap",
                    contentChars = 12
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.AssistantReceived(
                    round = 1,
                    contentChars = 0,
                    reasoningContent = "",
                    toolNames = listOf("search_apps", "launch_app")
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call_1",
                    name = "tap",
                    argsPreview = "{}"
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call_1",
                    name = "tap",
                    resultSummary = "ok",
                    imageCount = 0,
                    imageBytes = 0
                )
            )
        )
    }

    @Test
    fun `screen observation dismisses external entry surface before execution`() {
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(
                AgentEvent.AssistantReceived(
                    round = 1,
                    contentChars = 0,
                    reasoningContent = "",
                    toolNames = listOf("observe_screen")
                )
            )
        )
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call_status",
                    name = "run_command",
                    argsPreview = "{}"
                )
            )
        )
    }

    @Test
    fun `clock direct action dismisses entry surface without revealing operation overlay`() {
        val event = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call_alarm",
            name = "set_alarm",
            argsPreview = "参数已接收",
        )

        assertTrue(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(event))
        assertFalse(AgentOverlayVisibilityPolicy.isForegroundOperationTool("set_alarm"))
    }

    @Test
    fun `foreground execution is recorded only after entry surface is ready`() {
        val planned = AgentEvent.AssistantReceived(
            round = 1,
            contentChars = 0,
            reasoningContent = "",
            toolNames = listOf("launch_app"),
        )
        val started = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call_launch",
            name = "launch_app",
            argsPreview = "参数已接收",
        )

        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                planned,
                entrySurfaceReady = true,
            )
        )
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                started,
                entrySurfaceReady = false,
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                started,
                entrySurfaceReady = true,
            )
        )
    }

    @Test
    fun foregroundSuppressesClarifyAndForegroundTools() {
        val clarifyEvent = AgentEvent.UserInputRequested(
            io.github.mangi.eta.agent.runtime.AgentUserInputRequest(
                id = "req-1",
                questions = listOf(io.github.mangi.eta.agent.runtime.AgentUserInputQuestion("q", "问题")),
            ),
        )
        val toolEvent = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call_tap",
            name = "tap",
            argsPreview = "{}",
        )

        // 后台（appVisible = false 或默认单参数）：正常显示
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(clarifyEvent))
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(clarifyEvent, appVisible = false))
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(toolEvent))
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(toolEvent, appVisible = false))

        // 前台（appVisible = true）：一律抑制，不显示悬浮窗
        assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(clarifyEvent, appVisible = true))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(toolEvent, appVisible = true))
    }

    @Test
    fun shouldRestoreForPolicyBehavior() {
        // 前台可见时：绝不恢复
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRestoreFor(
                activeRun = true,
                overlayRequested = true,
                appVisible = true,
            )
        )

        // 已经处于终态（activeRun = false）：绝不复活
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRestoreFor(
                activeRun = false,
                overlayRequested = true,
                appVisible = false,
            )
        )

        // 本次 run 未曾请求过展示（例如纯后台文本/搜索）：不恢复
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRestoreFor(
                activeRun = true,
                overlayRequested = false,
                appVisible = false,
            )
        )

        // 活跃 run、曾请求过展示、退至后台：正常恢复
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRestoreFor(
                activeRun = true,
                overlayRequested = true,
                appVisible = false,
            )
        )
    }

    @Test
    fun shouldShowResultCardPolicyBehavior() {
        // 前台发生终态：不展示结果卡片
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = true,
                appVisible = true,
            )
        )

        // 后台发生终态且执行过前台工具：展示结果卡片
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = true,
                appVisible = false,
            )
        )

        // 未执行前台工具：任何情况均不展示结果卡片
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = false,
                appVisible = false,
            )
        )
    }
}
