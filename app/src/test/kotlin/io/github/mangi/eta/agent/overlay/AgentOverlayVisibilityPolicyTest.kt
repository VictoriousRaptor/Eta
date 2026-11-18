package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentUserInputQuestion
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayVisibilityPolicyTest {
    @Test
    fun plannedToolsAndWaitingForGuiDoNotRevealOrDismissTheEntrySurface() {
        val events = listOf(
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap"),
            AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap", contentChars = 0),
            AgentEvent.AssistantReceived(1, 0, "", listOf("launch_app", "observe_screen")),
            AgentEvent.ToolStarted(1, "tap-1", "tap", "点击"),
            AgentEvent.ToolStarted(1, "type-1", "type_text", "输入文本"),
            AgentEvent.ToolStarted(1, "alarm-1", "set_alarm", "创建闹钟"),
            AgentEvent.GuiControlChanged(GuiControlStatus.Waiting),
            AgentEvent.GuiControlChanged(GuiControlStatus.Released),
            AgentEvent.GuiControlChanged(GuiControlStatus.UserPaused),
        )
        events.forEach {
            assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(it))
            assertFalse(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(it))
            assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(it, entrySurfaceReady = true))
        }
    }

    @Test
    fun acquiredGuiControlRevealsAndDismissesTheEntrySurface() {
        val acquired = AgentEvent.GuiControlChanged(GuiControlStatus.Acquired)
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(acquired))
        assertTrue(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(acquired))
    }

    @Test
    fun clarificationIndependentlyRevealsAnAnswerSurface() {
        val event = clarificationEvent()
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(event))
        assertTrue(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, entrySurfaceReady = true))
    }

    @Test
    fun completedForegroundToolsRevealTheOverlayAndAreRecordedAfterPreparation() {
        val finished = AgentEvent.ToolFinished(1, "observe-1", "observe_screen", "完成", 0, 0, success = true)
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(finished))
        assertFalse(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(finished))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished, false))
        assertTrue(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished, true))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished.copy(success = false), true))

        val images = AgentEvent.ToolImagesAttached(1, "observe_screen", 1, 10)
        assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(images))
        assertFalse(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(images))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(images, entrySurfaceReady = true))
    }

    @Test
    fun backgroundToolsAndReleaseHaveNoForegroundSideEffects() {
        listOf("terminal", "terminal_job", "read_file", "browser_use", "web_search", "release_gui", "get_current_context")
            .forEach { name ->
                val event = AgentEvent.ToolFinished(1, "call", name, "完成", 0, 0)
                assertFalse(name, AgentOverlayVisibilityPolicy.shouldRevealFor(event))
                assertFalse(name, AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(name))
                assertFalse(name, AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, true))
            }
        assertTrue(AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal("set_alarm"))
        assertFalse(AgentOverlayVisibilityPolicy.isForegroundOperationTool("set_alarm"))
    }

    @Test
    fun foregroundIntentFallbackIsRecordedOnlyAfterSuccessfulCompletion() {
        val started = AgentEvent.ToolStarted(1, "alarm-1", "set_alarm", "创建闹钟")
        val finished = AgentEvent.ToolFinished(1, "alarm-1", "set_alarm", "完成", 0, 0, success = true)
        assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(started))
        assertFalse(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(started))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(started, true))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished, false))
        assertTrue(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished, true))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(finished.copy(success = false), true))
    }

    @Test
    fun foregroundSuppressesClarifyAndGuiOverlayEvents() {
        val events = listOf(
            clarificationEvent(),
            AgentEvent.GuiControlChanged(GuiControlStatus.Acquired),
            AgentEvent.ToolFinished(1, "tap-1", "tap", "完成", 0, 0, success = true),
            AgentEvent.ToolImagesAttached(1, "observe_screen", 1, 10),
        )
        events.forEach { event ->
            assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(event))
            assertTrue(AgentOverlayVisibilityPolicy.shouldRevealFor(event, appVisible = false))
            assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(event, appVisible = true))
        }
    }

    @Test
    fun restoreRequiresAnActiveRunWithAnOverlayRequestInBackground() {
        assertFalse(AgentOverlayVisibilityPolicy.shouldRestoreFor(
            activeRun = true,
            overlayRequested = true,
            appVisible = true,
        ))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRestoreFor(
            activeRun = false,
            overlayRequested = true,
            appVisible = false,
        ))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRestoreFor(
            activeRun = true,
            overlayRequested = false,
            appVisible = false,
        ))
        assertTrue(AgentOverlayVisibilityPolicy.shouldRestoreFor(
            activeRun = true,
            overlayRequested = true,
            appVisible = false,
        ))
    }

    @Test
    fun resultCardRequiresForegroundExecutionOrPresentedClarifyInBackground() {
        assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(
            hasExecutedForegroundTool = true,
            appVisible = true,
        ))
        assertTrue(AgentOverlayVisibilityPolicy.shouldShowResultCard(
            hasExecutedForegroundTool = true,
            appVisible = false,
        ))
        assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(
            hasExecutedForegroundTool = false,
            appVisible = false,
            hasPresentedClarification = false,
        ))
        assertTrue(AgentOverlayVisibilityPolicy.shouldShowResultCard(
            hasExecutedForegroundTool = false,
            appVisible = false,
            hasPresentedClarification = true,
        ))
        assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(
            hasExecutedForegroundTool = false,
            appVisible = true,
            hasPresentedClarification = true,
        ))
    }

    private fun clarificationEvent() = AgentEvent.UserInputRequested(
        AgentUserInputRequest("question", listOf(AgentUserInputQuestion("city", "目的地？"))),
    )
}
