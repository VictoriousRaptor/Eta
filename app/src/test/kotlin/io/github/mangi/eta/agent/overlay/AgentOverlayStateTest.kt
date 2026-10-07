package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentUserInputAnswer
import io.github.mangi.eta.agent.runtime.AgentUserInputQuestion
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentOverlayStateTest {
    @Test
    fun internalStepsCollapseIntoThinkingAndDoNotFlickerDuringTools() {
        var state = AgentOverlayState.Initial.applyEvent(AgentEvent.RunStarted(0, 0, 10, false))
        val internalSteps = listOf(
            AgentEvent.RoundStarted(1, 2),
            AgentEvent.ProviderRequestStarted(1),
            AgentEvent.ProviderResponseStarted(1, 200),
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap"),
            AgentEvent.AssistantReceived(1, 0, "", listOf("tap")),
        )
        internalSteps.forEach { state = state.applyEvent(it) }
        assertEquals(AgentOverlayStatus.Reasoning, state.status)

        state = state.applyEvent(AgentEvent.ToolStarted(1, "call-1", "tap", "{}"))
        assertEquals(AgentOverlayStatus.RunningTool("tap"), state.status)
        // 工具执行中到达的截图、重试、压缩事件不改写当前文字。
        listOf(
            AgentEvent.ToolImagesAttached(1, "tap", 1, 10),
            AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "MODEL_TIMEOUT"),
            AgentEvent.ProviderRequestStarted(2),
        ).forEach { state = state.applyEvent(it) }
        assertEquals(AgentOverlayStatus.RunningTool("tap"), state.status)

        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "tap", "ok", 0, 0))
        assertEquals(AgentOverlayStatus.Reasoning, state.status)

        state = state.applyEvent(AgentEvent.HostedToolStarted(1, "call-hosted", "web_search"))
        assertEquals(AgentOverlayStatus.HostedToolRunning("web_search"), state.status)

        state = state.applyEvent(AgentEvent.HostedToolFinished(1, "call-hosted", "web_search", true))
        assertEquals(AgentOverlayStatus.Reasoning, state.status)
    }

    @Test
    fun pausedStateSurvivesInFlightEventsUntilRunEnds() {
        val paused = AgentOverlayState(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        listOf(
            AgentEvent.ToolFinished(1, "call-1", "tap", "ok", 0, 0),
            AgentEvent.ToolStarted(2, "call-2", "swipe", "{}"),
            AgentEvent.HostedToolStarted(2, "call-hosted", "web_search"),
            AgentEvent.HostedToolFinished(2, "call-hosted", "web_search", true),
            AgentEvent.ProviderRequestStarted(2),
        ).forEach { assertEquals(paused, paused.applyEvent(it)) }
        assertEquals(AgentOverlayPhase.FAILED, paused.applyEvent(AgentEvent.RunFailed("已停止")).phase)
    }

    @Test
    fun thoughtKeepsLatestReasoningTailAndSwitchesToToolSummary() {
        var state = AgentOverlayState.Initial
            .applyEvent(AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 0))
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "用户想订\n  明天的"))
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "高铁票"))
        assertEquals("用户想订 明天的高铁票", state.thought)

        val long = "很长的推理".repeat(40)
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, long))
        assertEquals(96, state.thought.length)
        assertEquals(true, state.thought.endsWith("很长的推理"))

        state = state.applyEvent(AgentEvent.ToolStarted(1, "call-1", "tap_element", "点击元素「搜索」"))
        assertEquals("点击元素「搜索」", state.thought)
        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "tap_element", "ok", 0, 0))
        assertEquals("", state.thought)

        state = state.applyEvent(AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "旧想法"))
        state = state.applyEvent(AgentEvent.AssistantBlockStart(2, AgentEvent.AssistantBlockKind.THINKING, 1))
        assertEquals("", state.thought)
    }

    @Test
    fun clarifyStateOverridesOtherStatusesAndPreservesThought() {
        val request = AgentUserInputRequest("req-1", listOf(AgentUserInputQuestion("city", "目的地？", listOf("广州", "上海"))))
        var state = AgentOverlayState.Initial.applyEvent(AgentEvent.UserInputRequested(request))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)
        assertEquals(request, state.pendingUserInput)

        // Events that normally change status should not override WaitingForUser
        state = state.applyEvent(AgentEvent.RoundStarted(1, 2))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)

        state = state.applyEvent(AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "重试"))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)

        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 0, "文字增量"))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)

        state = state.applyEvent(AgentEvent.ToolStarted(1, "call-1", "tap", "args"))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)
        assertEquals("args", state.thought)

        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "tap", "ok", 0, 0))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)
        assertEquals("", state.thought)

        state = state.applyEvent(AgentEvent.HostedToolStarted(1, "call-h", "search"))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)

        state = state.applyEvent(AgentEvent.HostedToolFinished(1, "call-h", "search", true))
        assertEquals(AgentOverlayStatus.WaitingForUser, state.status)
    }

    @Test
    fun answerUserInputTransitionsToContinuing() {
        val request = AgentUserInputRequest("req-1", listOf(AgentUserInputQuestion("city", "目的地？", listOf("广州", "上海"))))
        var state = AgentOverlayState.Initial.applyEvent(AgentEvent.UserInputRequested(request))

        state = state.applyEvent(AgentEvent.UserInputAnswered(AgentUserInputAnswer("req-1", mapOf("city" to "广州"))))
        assertEquals(AgentOverlayStatus.Continuing, state.status)
        assertNull(state.pendingUserInput)
    }

    @Test
    fun pausedUserInputRequestedAndAnsweredPreservePausePhase() {
        val paused = AgentOverlayState(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        val request = AgentUserInputRequest("req-1", listOf(AgentUserInputQuestion("q1", "请确认")))
        val waiting = paused.applyEvent(AgentEvent.UserInputRequested(request))
        assertEquals(AgentOverlayPhase.PAUSED, waiting.phase)
        assertEquals(AgentOverlayStatus.WaitingForUser, waiting.status)
        assertEquals(request, waiting.pendingUserInput)

        val answered = waiting.applyEvent(AgentEvent.UserInputAnswered(AgentUserInputAnswer("req-1", mapOf("q1" to "ok"))))
        assertEquals(AgentOverlayPhase.PAUSED, answered.phase)
        assertEquals(AgentOverlayStatus.Paused, answered.status)
        assertNull(answered.pendingUserInput)
    }

    @Test
    fun terminalEventsClearPendingUserInput() {
        val request = AgentUserInputRequest("req-1", listOf(AgentUserInputQuestion("q1", "请确认")))
        val state = AgentOverlayState.Initial.applyEvent(AgentEvent.UserInputRequested(request))
        assertEquals(request, state.pendingUserInput)

        val finished = state.applyEvent(AgentEvent.RunFinished(1, 10))
        assertEquals(AgentOverlayPhase.FINISHED, finished.phase)
        assertEquals(AgentOverlayStatus.ResultReady, finished.status)
        assertNull(finished.pendingUserInput)

        val failed = state.applyEvent(AgentEvent.RunFailed("任务出错"))
        assertEquals(AgentOverlayPhase.FAILED, failed.phase)
        assertEquals(AgentOverlayStatus.RunFailed, failed.status)
        assertNull(failed.pendingUserInput)
    }

    @Test
    fun userSupplementReceivedUpdatesStatusProperly() {
        var state = AgentOverlayState.Initial.applyEvent(AgentEvent.UserSupplementReceived(1, "supplement"))
        assertEquals(AgentOverlayStatus.SupplementReceived, state.status)

        // Paused state should remain Paused
        val paused = AgentOverlayState(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        state = paused.applyEvent(AgentEvent.UserSupplementReceived(2, "supplement 2"))
        assertEquals(AgentOverlayStatus.Paused, state.status)

        // Clarify state should remain WaitingForUser
        val request = AgentUserInputRequest("req-1", listOf(AgentUserInputQuestion("city", "目的地？")))
        var clarifyState = AgentOverlayState.Initial.applyEvent(AgentEvent.UserInputRequested(request))
        clarifyState = clarifyState.applyEvent(AgentEvent.UserSupplementReceived(3, "supplement 3"))
        assertEquals(AgentOverlayStatus.WaitingForUser, clarifyState.status)
    }
}
