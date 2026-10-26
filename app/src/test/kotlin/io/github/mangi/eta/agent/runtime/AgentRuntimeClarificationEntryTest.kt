package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.core.AndroidAgentLogger
import java.lang.reflect.InvocationTargetException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeClarificationEntryTest {
    @Test
    fun failedEntryDismissalReturnsToolErrorWithoutPublishingOrWaiting() = checkEntry(false)

    @Test
    fun successfulEntryDismissalStillAnswersTheSameRun() = checkEntry(true)

    @Test
    fun breenoWithoutAccessibilityReturnsToolErrorWithoutWaiting() = checkEntry(false, "breeno")

    @Test
    fun xiaoAiWithoutAccessibilityReturnsToolErrorWithoutWaiting() = checkEntry(false, "xiaoai")

    private fun checkEntry(dismissed: Boolean, source: String = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession("clarify-entry", controller, eventSink = events::add)
        var dismissCalls = 0
        val guard = EntrySurfaceGuard.from(
            AgentRuntimeWire.EntryHandoff("entry", source, "{}", true),
            AndroidAgentLogger,
            etaVoiceSurfaceDismissal = { dismissCalls++; dismissed },
        )
        val executor = AgentRuntimeRunExecutor(
            RuntimeEnvironment.getApplication(),
            currentPermissions = { AgentRuntimePolicy.Permissions(terminalTools = false, browserTools = false, thinking = false) },
            snapshotRequest = { it },
            guiControl = GuiControlCoordinator(),
            onAcceptedEvent = { event, entry ->
                if (event is AgentEvent.UserInputRequested) {
                    entry?.dismissOnce()
                    // An erroneously published request is answered so a regression cannot hang the test.
                    assertTrue(controller.answerUserInput(AgentUserInputAnswer(event.request.id, mapOf("q" to "答案"))))
                }
            },
            persistArtifacts = { _, _, _ -> },
        )
        val accept = AgentRuntimeRunExecutor::class.java.getDeclaredMethod(
            "acceptEvent", AgentRuntimeSession::class.java, AgentEvent::class.java,
            MutableList::class.java, EntrySurfaceGuard::class.java, AgentRunCheckpointRecorder::class.java,
        ).apply { isAccessible = true }
        var round = 0
        var toolResult: JSONObject? = null
        var requestId: String? = null
        val provider = object : AgentProviderClient {
            override val id = "clarify-entry-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, false, false, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (round++ == 0) {
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(
                            JSONObject().put("id", "ask").put("type", "function").put("function", JSONObject()
                                .put("name", "request_user_input")
                                .put("arguments", """{"questions":[{"id":"q","question":"问题？"}]}""")),
                        )))
                }
                toolResult = (0 until request.messages.length()).map(request.messages::getJSONObject)
                    .last { it.optString("role") == "tool" }.let { JSONObject(it.getString("content")) }
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "继续")
                    .put("finish_reason", "stop"))
            }
        }
        val result = AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = ""),
            prompt = "开始", provider = provider, runController = controller,
            toolExecutor = AgentModelClient.ToolExecutor { error("Clarify must remain local to the loop") },
            onEvent = { event ->
                if (event is AgentEvent.UserInputRequested) requestId = event.request.id
                try {
                    accept.invoke(executor, session, event, null, guard, null)
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            },
        )
        assertEquals("继续", result.content)
        assertEquals(if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) 1 else 0, dismissCalls)
        assertEquals(dismissed, requireNotNull(toolResult).getBoolean("ok"))
        assertEquals(if (dismissed) 1 else 0, events.filterIsInstance<AgentEvent.UserInputRequested>().size)
        assertEquals(if (dismissed) 1 else 0, events.filterIsInstance<AgentEvent.UserInputAnswered>().size)
        if (!dismissed) {
            assertEquals("TOOL_ERROR", requireNotNull(toolResult).getString("code"))
            assertTrue(requireNotNull(toolResult).getString("message").contains("ENTRY_SURFACE_NOT_READY"))
            val replay = mutableListOf<AgentEvent>()
            session.attach(replay::add, {})
            assertTrue(replay.none { it is AgentEvent.UserInputRequested })
        }
        assertFalse(controller.answerUserInput(AgentUserInputAnswer(requireNotNull(requestId), mapOf("q" to "答案"))))
    }
}
