package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUserInputAnswer
import io.github.mangi.eta.agent.runtime.AgentUserInputQuestion
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.SteerAcknowledgement
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentAppStateConversationLoadingTest {
    private lateinit var context: Context
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var simulatedRuntimeConnection: ServiceConnection? = null
    private val receivedControls = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).declareActionUnbindable(AgentRuntimeWire.ACTION_BIND)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun tearDown() {
        scope.cancel()
        try {
            awaitCondition { job.isCompleted }
        } finally {
            simulatedRuntimeConnection?.onBindingDied(ComponentName(context, "TestRuntime"))
            EtaDatabase.closeForTests()
        }
    }

    @Test
    fun streamingMainSubmitQueuesWithoutReplacingThePendingClarification() {
        val state = streamingConversation()
        val pending = state.homeState.pendingUserInput

        state.sendCurrentMessage("queued message")

        assertEquals(listOf("queued message"), state.homeState.queuedMessages.map { it.content })
        assertEquals(pending, state.homeState.pendingUserInput)
        assertTrue(state.homeState.isStreaming)
        assertFalse(state.homeState.interactionSubmitting)
    }

    @Test
    fun noninterruptSupplementUsesTheExistingRunAndRetainsDraftWhenRejected() {
        val state = streamingConversation()
        state.registerRunForTest("interaction-run", "first")

        state.steerCurrentMessage("supplement")

        assertTrue(state.homeState.interactionSubmitting)
        assertTrue(state.homeState.queuedMessages.isEmpty())
        assertEquals("interaction-run", state.runStoreForTest().activeRunId("first"))
        awaitCondition { !state.homeState.interactionSubmitting }
        assertEquals("draft", state.homeState.input)
        assertEquals(null, state.homeState.steerAcknowledgement)
        assertTrue(state.homeState.queuedMessages.isEmpty())
    }

    @Test
    fun acceptedSupplementClearsTheRecalledSourceDraftAndConsumesOnlyItsOwnAcknowledgement() {
        val (state, accept) = controlledSteerConversation()
        state.sendCurrentMessage("supplement")
        state.recallQueuedMessage(state.homeState.queuedMessages.single().id, "")
        val version = state.homeState.inputSyncVersion

        state.steerCurrentMessage("supplement")
        accept()

        assertEquals("", state.homeState.input)
        assertEquals(version, state.homeState.steerAcknowledgement?.inputSyncVersion)
        val sequence = requireNotNull(state.homeState.steerAcknowledgement).sequence
        state.consumeSteerAcknowledgement(sequence - 1)
        assertEquals(sequence, state.homeState.steerAcknowledgement?.sequence)
        state.consumeSteerAcknowledgement(sequence)
        assertEquals(null, state.homeState.steerAcknowledgement)
        state.consumeSteerAcknowledgement(sequence)
        assertEquals("", state.homeState.input)
    }

    @Test
    fun lateAcceptancePreservesSameTextRecalledAfterTheSubmission() {
        val (state, accept) = controlledSteerConversation()
        val submittedVersion = state.homeState.inputSyncVersion
        state.steerCurrentMessage("supplement")
        state.sendCurrentMessage("supplement")
        state.recallQueuedMessage(state.homeState.queuedMessages.single().id, "")

        accept()

        assertEquals("supplement", state.homeState.input)
        assertTrue(state.homeState.inputSyncVersion > submittedVersion)
        assertEquals(submittedVersion, state.homeState.steerAcknowledgement?.inputSyncVersion)
        state.consumeSteerAcknowledgement(requireNotNull(state.homeState.steerAcknowledgement).sequence)
        assertEquals("supplement", state.homeState.input)
    }

    @Test
    fun lateAcceptancePreservesAMatchingMessageThatIsNowBeingEdited() {
        val (state, accept) = controlledSteerConversation()
        val content = "first-message-0-end"
        state.steerCurrentMessage(content)
        AgentAppState::class.java.getDeclaredMethod(
            "updateCurrentConversation", AgentChatHomeUiState::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }.invoke(state, state.homeState.copy(isStreaming = false), false)
        state.beginMessageEdit("first-0")

        accept()

        assertEquals(content, state.homeState.input)
        assertEquals("first-0", state.homeState.messageEdit?.targetMessageId)
    }

    @Test
    fun recalledSameTextSurvivesAnAlreadyConsumedSupplementAndConversationSwitch() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val update = AgentAppState::class.java.getDeclaredMethod(
            "updateCurrentConversation", AgentChatHomeUiState::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }
        update.invoke(state, state.homeState.copy(
            isStreaming = true, steerAcknowledgement = SteerAcknowledgement("supplement", 1, 0),
        ), false)
        state.consumeSteerAcknowledgement(1)
        state.sendCurrentMessage("supplement")
        update.invoke(state, state.homeState.copy(isStreaming = false), false)
        state.recallQueuedMessage(state.homeState.queuedMessages.single().id, "")

        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.selectConversation("first")
        awaitLoaded(state, "first")

        assertEquals("supplement", state.homeState.input)
        assertTrue(state.homeState.queuedMessages.isEmpty())
        assertEquals(null, state.homeState.steerAcknowledgement)
    }

    @Test
    fun disabledSteerDoesNotSubmitOrChangeTheDraftOrPendingClarification() {
        val state = streamingConversation()
        val before = state.homeState
        requireNotNull(Prefs.localAgentPreferences()).edit().putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, false).commit()

        state.steerCurrentMessage("supplement")

        assertEquals(before, state.homeState)
    }

    @Test
    fun queueSteerUsesCurrentRunWithoutClearingTheMatchingInputDraftOrAttachments() {
        val (state, accept) = controlledSteerConversation()
        state.sendCurrentMessage("supplement")
        state.sendCurrentMessage("next queued")
        val target = state.homeState.queuedMessages.first()
        val remaining = state.homeState.queuedMessages.last()
        val image = PendingImageUi("image", "uri", "data", "image/png")
        updateTestState(state, state.homeState.copy(input = "supplement", pendingImages = listOf(image)))
        val pending = state.homeState.pendingUserInput

        state.steerQueuedMessage(target.id)
        assertEquals(listOf(remaining), state.homeState.queuedMessages)
        state.steerQueuedMessage(remaining.id)
        assertEquals(listOf(remaining), state.homeState.queuedMessages)
        accept()

        assertEquals(listOf("interaction-run" to "supplement"), receivedControls)
        assertEquals("interaction-run", state.runStoreForTest().activeRunId("first"))
        assertEquals(listOf(remaining), state.homeState.queuedMessages)
        assertEquals("supplement", state.homeState.input)
        assertEquals(listOf(image), state.homeState.pendingImages)
        assertEquals(pending, state.homeState.pendingUserInput)
        assertEquals(null, state.homeState.steerAcknowledgement)
        assertTrue(state.homeState.isStreaming)
    }

    @Test
    fun rejectedQueueSteerRestoresItsPlaceWithoutOverwritingLaterQueueEdits() {
        val (state, reject) = controlledSteerConversation(accepted = false)
        state.sendCurrentMessage("before")
        state.sendCurrentMessage("steer me")
        state.sendCurrentMessage("after")
        val original = state.homeState.queuedMessages

        state.steerQueuedMessage(original[1].id)
        state.removeQueuedMessage(original[0].id)
        state.sendCurrentMessage("new queued")
        reject()

        assertEquals(listOf("steer me", "after", "new queued"), state.homeState.queuedMessages.map { it.content })
        assertEquals(original[1], state.homeState.queuedMessages.first())
        assertTrue(state.homeState.queuedMessages.none { it.immediateInterrupt })
        assertEquals(null, state.homeState.steerAcknowledgement)
    }

    @Test
    fun rejectedQueueSteerDoesNotPreemptANewImmediateInterrupt() {
        val (state, reject) = controlledSteerConversation(accepted = false)
        state.sendCurrentMessage("steer me")
        val queued = state.homeState.queuedMessages.single()
        state.steerQueuedMessage(queued.id)
        val interrupt = QueuedMessageUi("interrupt", "new task", immediateInterrupt = true)
        updateTestState(state, AgentMessageQueueReducer.enqueue(state.homeState, interrupt))

        reject()

        assertEquals(listOf(interrupt, queued), state.homeState.queuedMessages)
    }

    @Test
    fun acceptedQueueSteerCannotBeAutomaticallySentAgainWhenRunFinishesBeforeAck() {
        val (state, accept) = controlledSteerConversation()
        state.sendCurrentMessage("steer me")
        state.steerQueuedMessage(state.homeState.queuedMessages.single().id)
        finishInteractionRun(state)

        accept()

        assertEquals(listOf("interaction-run" to "steer me"), receivedControls)
        assertTrue(state.homeState.queuedMessages.isEmpty())
        assertFalse(state.homeState.isStreaming)
        assertEquals(null, state.runStoreForTest().activeRunId("first"))
    }

    @Test
    fun rejectedQueueSteerIsRestoredEvenIfRunFinishesBeforeAck() {
        lateinit var saveMutex: Mutex
        val saveOwner = Any()
        val toastCount = ShadowToast.shownToastCount()
        val (state, reject) = controlledSteerConversation(accepted = false, afterReply = {
            // The rejection must reach the UI while the real persistence path is still blocked.
            awaitCondition { ShadowToast.shownToastCount() > toastCount }
            saveMutex.unlock(saveOwner)
        })
        state.sendCurrentMessage("steer me")
        val queued = state.homeState.queuedMessages.single()
        state.steerQueuedMessage(queued.id)
        val persistence = AgentAppState::class.java.getDeclaredField("conversationPersistence")
            .apply { isAccessible = true }.get(state)
        saveMutex = AgentConversationPersistence::class.java.getDeclaredField("mutex")
            .apply { isAccessible = true }.get(persistence) as Mutex
        runBlocking { saveMutex.lock(saveOwner) }
        try {
            finishInteractionRun(state)
            reject()
            assertEquals(listOf(queued), state.homeState.queuedMessages)
            assertFalse(state.homeState.isStreaming)
            assertFalse(state.homeState.interactionSubmitting)
        } finally {
            if (saveMutex.holdsLock(saveOwner)) saveMutex.unlock(saveOwner)
        }
    }

    @Test
    fun queueKeepsTextImagesAndFilesAndRecallsThemForEditing() {
        val (state, _) = controlledSteerConversation()
        val image = PendingImageUi("image", "uri", "data", "image/png")
        val file = PendingFileReferenceUi("file", AgentFileReference("doc.txt", "/sdcard/doc.txt", AgentFileReferenceKind.File))
        requireNotNull(Prefs.localAgentPreferences()).edit().putBoolean(Prefs.Keys.AGENT_TERMINAL_TOOLS, true).commit()
        updateTestState(state, state.homeState.copy(pendingImages = listOf(image), pendingFileReferences = listOf(file)))

        state.sendCurrentMessage("with attachments")
        val queued = state.homeState.queuedMessages.single()
        assertEquals(listOf(image), queued.pendingImages)
        assertEquals(listOf(file), queued.pendingFileReferences)
        assertTrue(state.homeState.pendingImages.isEmpty())
        assertTrue(state.homeState.pendingFileReferences.isEmpty())
        state.steerQueuedMessage(queued.id)
        assertEquals(listOf(queued), state.homeState.queuedMessages)
        assertFalse(state.homeState.interactionSubmitting)
        state.recallQueuedMessage(queued.id, "new draft")

        assertEquals("new draft\nwith attachments", state.homeState.input)
        assertEquals(listOf(image), state.homeState.pendingImages)
        assertEquals(listOf(file), state.homeState.pendingFileReferences)
        assertTrue(state.homeState.queuedMessages.isEmpty())
        assertTrue(receivedControls.isEmpty())
    }

    @Test
    fun disabledSteerAndStaleQueueActionsCannotInterruptOrStartAnotherRun() {
        val (state, _) = controlledSteerConversation()
        state.sendCurrentMessage("queued")
        val queued = state.homeState.queuedMessages.single()
        val before = state.homeState
        requireNotNull(Prefs.localAgentPreferences()).edit().putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, false).commit()
        state.steerQueuedMessage(queued.id)
        assertEquals(before, state.homeState)
        state.steerQueuedMessage(queued.id, requireActiveRun = false)
        assertEquals(before, state.homeState)
        requireNotNull(Prefs.localAgentPreferences()).edit().putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, true).commit()
        updateTestState(state, state.homeState.copy(isStreaming = false))
        val idle = state.homeState
        state.steerQueuedMessage(queued.id)
        assertEquals(idle, state.homeState)
        assertTrue(receivedControls.isEmpty())
    }

    @Test
    fun rejectedOldQueueSteerIsRestoredWithoutChangingANewerRunsDraftOrSubmission() {
        val (state, reject) = controlledSteerConversation(accepted = false)
        state.sendCurrentMessage("old queued")
        val queued = state.homeState.queuedMessages.single()
        state.steerQueuedMessage(queued.id)
        state.runStoreForTest().remove("interaction-run")
        state.registerRunForTest("new-run", "first")
        updateTestState(state, state.homeState.copy(input = "new draft", interactionSubmitting = true))

        reject()

        assertEquals(listOf(queued), state.homeState.queuedMessages)
        assertEquals("new draft", state.homeState.input)
        assertTrue(state.homeState.interactionSubmitting)
        assertEquals(null, state.homeState.steerAcknowledgement)
    }

    @Test
    fun parallelSteerUsesTheSelectedRunAndAcknowledgesOnlyItsOwnConversation() {
        val (state, accept) = controlledSteerConversation(includeSecondConversation = true)
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("second-run", "second")
        updateTestState(state, state.homeState.copy(input = "supplement"))

        state.steerCurrentMessage("supplement")
        assertTrue(state.homeState.interactionSubmitting)
        state.selectConversation("first")
        awaitLoaded(state, "first")
        updateTestState(state, state.homeState.copy(input = "supplement", interactionSubmitting = true))
        val firstBeforeAck = state.homeState

        accept()

        assertEquals(listOf("second-run" to "supplement"), receivedControls)
        assertEquals(firstBeforeAck, state.homeState)
        assertEquals(setOf("interaction-run", "second-run"), state.runStoreForTest().observedRunIds)
        state.selectConversation("second")
        awaitLoaded(state, "second")
        assertEquals("", state.homeState.input)
        assertEquals("supplement", state.homeState.steerAcknowledgement?.text)
        assertFalse(state.homeState.interactionSubmitting)
    }

    @Test
    fun parallelClarificationAnswersAndCompletionLeaveTheOtherRequestUntouched() {
        val (state, accept) = controlledSteerConversation(
            controlType = AgentRuntimeWire.MSG_ANSWER_USER_INPUT, includeSecondConversation = true,
        )
        val firstRequest = requireNotNull(state.homeState.pendingUserInput)
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("second-run", "second")
        val secondRequest = firstRequest.copy(id = "second-question")
        state.applyEventForTest("second-run", AgentEvent.UserInputRequested(secondRequest))
        state.answerUserInput(AgentUserInputAnswer(firstRequest.id, mapOf("choice" to "first answer")))
        assertFalse(state.homeState.interactionSubmitting)
        val answer = AgentUserInputAnswer(secondRequest.id, mapOf("choice" to "second answer"))

        state.answerUserInput(answer)
        assertTrue(state.homeState.interactionSubmitting)
        state.selectConversation("first")
        awaitLoaded(state, "first")
        updateTestState(state, state.homeState.copy(interactionSubmitting = true))
        val firstBeforeAck = state.homeState
        accept()
        state.applyEventForTest("second-run", AgentEvent.UserInputAnswered(answer))

        assertEquals("second-run", receivedControls.single().first)
        assertEquals(firstBeforeAck, state.homeState)
        state.selectConversation("second")
        awaitLoaded(state, "second")
        assertEquals(null, state.homeState.pendingUserInput)
        assertFalse(state.homeState.interactionSubmitting)
        assertTrue(state.homeState.messages.filterIsInstance<UserMessageUi>().any { it.content == "second answer" })
        state.selectConversation("first")
        awaitLoaded(state, "first")
        state.completeRunForTest("second-run", "second finished")
        awaitCondition { state.runStoreForTest().activeRunId("second") == null }
        assertEquals(firstRequest, state.homeState.pendingUserInput)
        assertTrue(state.homeState.interactionSubmitting)
        assertTrue(state.homeState.isStreaming)
    }

    @Test
    fun delayedClarificationAckAndAnswerDoNotResetANewerRequestSubmission() {
        val (state, accept) = controlledSteerConversation(controlType = AgentRuntimeWire.MSG_ANSWER_USER_INPUT)
        val firstRequest = requireNotNull(state.homeState.pendingUserInput)
        val answer = AgentUserInputAnswer(firstRequest.id, mapOf("choice" to "first answer"))
        state.answerUserInput(answer)
        state.applyEventForTest("interaction-run", AgentEvent.UserInputAnswered(answer))
        val nextRequest = firstRequest.copy(id = "next-question")
        state.applyEventForTest("interaction-run", AgentEvent.UserInputRequested(nextRequest))
        updateTestState(state, state.homeState.copy(interactionSubmitting = true))

        accept()
        state.applyEventForTest("interaction-run", AgentEvent.UserInputAnswered(answer))

        assertEquals(nextRequest, state.homeState.pendingUserInput)
        assertTrue(state.homeState.interactionSubmitting)
    }

    private fun AgentAppState.applyEventForTest(runId: String, event: AgentEvent) {
        AgentAppState::class.java.getDeclaredMethod(
            "applyRunEvent", String::class.java, AgentEvent::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }.invoke(this, runId, event, false)
    }

    private fun updateTestState(state: AgentAppState, next: AgentChatHomeUiState) {
        AgentAppState::class.java.getDeclaredMethod(
            "updateCurrentConversation", AgentChatHomeUiState::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }.invoke(state, next, false)
    }

    private fun finishInteractionRun(state: AgentAppState) {
        AgentAppState::class.java.getDeclaredMethod(
            "applyRunResult", String::class.java, AgentRuntimeWire.RunResult::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }.invoke(
            state, "interaction-run", AgentRuntimeWire.RunResult("interaction-run", true, "done"), false,
        )
    }

    private fun controlledSteerConversation(
        accepted: Boolean = true,
        controlType: Int = AgentRuntimeWire.MSG_STEER,
        includeSecondConversation: Boolean = false,
        afterReply: () -> Unit = {},
    ): Pair<AgentAppState, () -> Unit> {
        var ready = false
        var reply: Messenger? = null
        val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                assertEquals(controlType, message.what)
                receivedControls += AgentRuntimeWire.runIdFromBundle(message.data) to message.data.getString("payload").orEmpty()
                reply = message.replyTo
            }
        })
        val runtimeContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                if (!ready) return false
                simulatedRuntimeConnection = connection
                connection.onServiceConnected(ComponentName(this, "TestRuntime"), messenger.binder)
                return true
            }
            override fun unbindService(connection: ServiceConnection) {}
        }
        val state = streamingConversation(runtimeContext, includeSecondConversation)
        state.registerRunForTest("interaction-run", "first")
        ready = true
        val originalJobs = job.children.toSet()
        return state to {
            val submissions = job.children.filterNot { it in originalJobs }.toList()
            awaitCondition { reply != null }
            requireNotNull(reply).send(Message.obtain(null, AgentRuntimeWire.MSG_CONTROL_RESPONSE).apply {
                data = Bundle().apply { putBoolean("accepted", accepted) }
            })
            reply = null
            afterReply()
            awaitCondition { submissions.all { it.isCompleted } }
        }
    }

    private fun streamingConversation(
        runtimeContext: Context = context,
        includeSecondConversation: Boolean = false,
    ): AgentAppState {
        if (includeSecondConversation) {
            seed("first" to conversation("first"), "second" to conversation("second"))
        } else seed("first" to conversation("first"))
        Prefs.initLocal(context)
        requireNotNull(Prefs.localAgentPreferences()).edit().putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, true).commit()
        val state = AgentAppState(runtimeContext, scope)
        val recovering = AgentAppState::class.java.getDeclaredField("runtimeRecoveryInProgress")
            .apply { isAccessible = true }.get(state) as java.util.concurrent.atomic.AtomicBoolean
        awaitCondition { !recovering.get() }
        val pending = AgentUserInputRequest("question", listOf(AgentUserInputQuestion("choice", "Which?", emptyList())))
        AgentAppState::class.java.getDeclaredMethod(
            "updateCurrentConversation", AgentChatHomeUiState::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }.invoke(
            state, state.homeState.copy(isStreaming = true, input = "draft", pendingUserInput = pending), false,
        )
        return state
    }

    @Test
    fun fastConversationSwitchesKeepTheLastSelectionAndRestoreEveryBody() {
        seed("first" to conversation("first"), "second" to conversation("second"), "third" to conversation("third"))
        val state = AgentAppState(context, scope)

        state.selectConversation("second")
        state.selectConversation("third")
        awaitLoaded(state, "third")
        assertEquals(listOf("third-0", "third-1", "third-2"), state.homeState.messages.map { it.id })

        state.selectConversation("second")
        awaitLoaded(state, "second")
        assertEquals(listOf("second-0", "second-1", "second-2"), state.homeState.messages.map { it.id })
        state.selectConversation("first")
        awaitLoaded(state, "first")
        assertEquals(listOf("first-0", "first-1", "first-2"), state.homeState.messages.map { it.id })
        assertEquals(9, runBlocking {
            val dao = EtaDatabase.get(context).conversationDao()
            listOf("first", "second", "third").sumOf { dao.messageCount(it) }
        })
    }

    @Test
    fun deletingTheSelectedConversationLoadsTheNextConversation() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)

        state.deleteConversation("first")

        awaitLoaded(state, "second")
        assertEquals(listOf("second-0", "second-1", "second-2"), state.homeState.messages.map { it.id })
        awaitCondition { runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") == 0 } }
        assertEquals(3, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("second") })
    }

    @Test
    fun deletingAnUnloadedConversationKeepsOtherStoredConversationsIntact() {
        seed("first" to conversation("first"), "second" to conversation("second"), "third" to conversation("third"))
        val state = AgentAppState(context, scope)

        state.deleteConversation("second")
        awaitCondition { runBlocking { EtaDatabase.get(context).conversationDao().messageCount("second") == 0 } }
        state.selectConversation("third")

        awaitLoaded(state, "third")
        assertEquals("third", state.conversationPaneState.selectedConversationId)
        assertEquals(3, state.homeState.messages.size)
        assertEquals(3, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })
        assertEquals(setOf("first", "third"), state.conversationPaneState.conversations.map { it.id }.toSet())
    }

    @Test
    fun loadingAnEarlierPageThenDeletingATurnPreservesTheEarlierPrefix() {
        seed("first" to conversation("first", count = 150))
        val state = AgentAppState(context, scope)
        assertTrue(state.homeState.hasEarlierMessages)
        assertEquals(128, state.homeState.messages.size)

        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages && !state.homeState.hasEarlierMessages }
        assertEquals((0 until 150).map { "first-$it" }, state.homeState.messages.map { it.id })
        state.deleteMessageTurn("first-149")
        awaitCondition { runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") == 149 } }

        val messages = runBlocking { AgentConversationStore.loadAllMessages(context, "first") }
        assertEquals((0 until 149).map { "first-$it" }, messages.map { it.id })
    }

    @Test
    fun searchAndExportIncludeMessagesOutsideTheLoadedWindow() {
        seed("first" to conversation("first"), "second" to conversation("second", count = 150))
        val state = AgentAppState(context, scope)

        state.updateSearchQuery("second-message-0-end")
        awaitCondition { state.conversationPaneState.conversations.map { it.id } == listOf("second") }
        assertEquals("first", state.conversationPaneState.selectedConversationId)

        val markdown = runBlocking { state.exportConversationMarkdown("second") }
        assertTrue(requireNotNull(markdown).contains("second-message-0-end"))
        assertTrue(markdown.contains("second-message-149-end"))
        assertFalse(state.isLoadingConversation)
    }

    @Test
    fun repeatedPrefetchRequestsLoadOnlyOnePage() {
        seed("first" to conversation("first", count = 600))
        val state = AgentAppState(context, scope)

        repeat(10) { state.loadEarlierMessages() }
        awaitCondition { !state.isLoadingEarlierMessages }

        assertEquals(256, state.homeState.messages.size)
        assertEquals(256, state.homeState.messages.map { it.id }.distinct().size)
        assertEquals("first-344", state.homeState.messages.first().id)
    }

    @Test
    fun switchingConversationCancelsAnOutstandingHistoryRead() {
        seed("first" to conversation("first", count = 600), "second" to conversation("second"))
        val state = AgentAppState(context, scope)

        state.loadEarlierMessages()
        state.selectConversation("second")
        awaitLoaded(state, "second")

        assertEquals(ConversationHistoryLoadState.Idle, state.historyLoadState)
        assertEquals(listOf("second-0", "second-1", "second-2"), state.homeState.messages.map { it.id })
        assertEquals(600, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })
    }

    @Test
    fun failedPageKeepsTheCurrentWindowAndCanBeRetried() {
        val original = conversation("first", count = 300)
        val longMessage = UserMessageUi("first-100", "历史正文".repeat(6_000))
        seed("first" to original.copy(messages = original.messages.map { if (it.id == longMessage.id) longMessage else it }))
        val state = AgentAppState(context, scope)
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "DELETE FROM agent_text_chunks WHERE owner_table = 'conversation_messages' AND owner_id = 'first-100'",
        )

        state.loadEarlierMessages()
        awaitCondition { state.historyLoadState == ConversationHistoryLoadState.Failed }
        assertEquals(128, state.homeState.messages.size)
        assertEquals("first-172", state.homeState.messages.first().id)

        runBlocking {
            EtaDatabase.get(context).conversationDao().insertMessages(listOf(
                ConversationMessageEntity(longMessage.id, "first", 100, "user", longMessage.content),
            ))
        }
        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages }
        assertEquals(ConversationHistoryLoadState.Idle, state.historyLoadState)
        assertEquals(longMessage, state.homeState.messages.first { it.id == longMessage.id })
        assertEquals(300, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })
    }

    @Test
    fun returningToTheLatestMessagesReleasesOldPagesWithoutDeletingHistory() {
        seed("first" to conversation("first", count = 600))
        val state = AgentAppState(context, scope)
        repeat(3) {
            state.loadEarlierMessages()
            awaitCondition { !state.isLoadingEarlierMessages }
        }
        assertEquals(512, state.homeState.messages.size)

        state.updateHistoryViewport(atLatest = true)
        awaitCondition { !state.isLoadingEarlierMessages }
        assertEquals(128, state.homeState.messages.size)
        assertEquals("first-472", state.homeState.messages.first().id)
        assertEquals(600, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })

        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages }
        assertEquals(256, state.homeState.messages.size)
        assertEquals("first-344", state.homeState.messages.first().id)
    }

    @Test
    fun scrollingBackToHistoryCancelsPageReleaseWithoutCancellingTheNextRead() {
        seed("first" to conversation("first", count = 600))
        val state = AgentAppState(context, scope)
        repeat(3) {
            state.loadEarlierMessages()
            awaitCondition { !state.isLoadingEarlierMessages }
        }
        state.updateHistoryViewport(atLatest = true)
        state.updateHistoryViewport(atLatest = false)
        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages }

        assertEquals(600, state.homeState.messages.size)
        assertFalse(state.homeState.hasEarlierMessages)
        assertEquals(ConversationHistoryLoadState.Idle, state.historyLoadState)
    }

    @Test
    fun concurrentResultsAndStopRemainBoundToTheirConversations() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val runs = state.runStoreForTest()
        state.registerRunForTest("run-a", "first")
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("run-b", "second")
        state.selectConversation("first")
        awaitLoaded(state, "first")

        state.stopCurrentRun()
        assertTrue(runs.isStopRequested("run-a"))
        assertFalse(runs.isStopRequested("run-b"))
        state.completeRunForTest("run-b", "乙任务结果")
        awaitCondition { runs.observedRunIds == setOf("run-a") }

        assertEquals("first", state.conversationPaneState.selectedConversationId)
        assertTrue(state.homeState.isStreaming)
        assertFalse(state.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "乙任务结果" })
        assertEquals(setOf("run-a"), runs.observedRunIds)

        state.completeRunForTest("run-a", "甲任务结果")
        awaitCondition { !state.homeState.isStreaming }
        state.selectConversation("second")
        awaitLoaded(state, "second")
        assertTrue(state.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "乙任务结果" })
        assertFalse(state.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "甲任务结果" })
    }

    @Test
    fun deletingAnActiveConversationWaitsForItsResultAndKeepsOtherRuns() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val runs = state.runStoreForTest()
        state.registerRunForTest("run-a", "first")
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("run-b", "second")

        state.deleteConversation("first")
        assertTrue(state.conversationPaneState.conversations.any { it.id == "first" })
        assertTrue(runs.isStopRequested("run-a"))
        assertFalse(runs.isStopRequested("run-b"))

        state.completeRunForTest("run-a", "已保存的结果")
        awaitCondition { state.conversationPaneState.conversations.none { it.id == "first" } }
        assertEquals(setOf("run-b"), runs.observedRunIds)
        assertTrue(state.homeState.isStreaming)
        state.completeRunForTest("run-a", "迟到的结果")
        assertFalse(state.conversationPaneState.conversations.any { it.id == "first" })
        state.completeRunForTest("run-b", "另一个任务结果")
    }

    @Test
    fun backgroundQueueContinuesWithItsCompletedHistoryWhileAnotherConversationRuns() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val runs = state.runStoreForTest()
        state.registerRunForTest("run-a", "first")
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("run-b", "second")
        state.sendCurrentMessage("后台后续消息")
        state.selectConversation("first")
        awaitLoaded(state, "first")

        state.completeRunForTest("run-b", "后台已完成的回复")
        awaitCondition {
            runBlocking { AgentConversationStore.loadConversation(context, "second") }
                ?.journal?.any { it.role == "user" && it.content == "后台后续消息" } == true
        }
        val saved = requireNotNull(runBlocking { AgentConversationStore.loadConversation(context, "second") })
        val answerIndex = saved.journal.indexOfFirst { it.content == "后台已完成的回复" }
        val followupIndex = saved.journal.indexOfFirst { it.content == "后台后续消息" }
        assertTrue(answerIndex >= 0 && answerIndex < followupIndex)
        assertEquals("first", state.conversationPaneState.selectedConversationId)
        assertTrue(state.homeState.isStreaming)
        assertEquals("run-a", runs.activeRunId("first"))
        awaitCondition { runs.observedRunIds == setOf("run-a") }
        state.completeRunForTest("run-a", "前台任务完成")
    }

    private fun AgentAppState.runStoreForTest(): AgentRunStore =
        AgentAppState::class.java.getDeclaredField("runStore").apply { isAccessible = true }.get(this) as AgentRunStore

    private fun AgentAppState.registerRunForTest(runId: String, conversationId: String) {
        check(runStoreForTest().register(runId, conversationId))
        AgentAppState::class.java.getDeclaredMethod("setConversationStreaming", String::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(this, runId, true)
    }

    private fun AgentAppState.completeRunForTest(runId: String, content: String) {
        val result = AgentRuntimeWire.RunResult(
            runId, true, content,
            transcript = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = content)),
        )
        AgentAppState::class.java.getDeclaredMethod("applyRunResult", String::class.java,
            AgentRuntimeWire.RunResult::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(this, runId, result, false)
    }

    private fun conversation(id: String, count: Int = 3): AgentChatHomeUiState {
        val messages = (0 until count).map { index -> UserMessageUi("$id-$index", "$id-message-$index-end") }
        val history = messages.map { message ->
            AgentModelClient.ConversationMessage(role = "user", content = message.content, messageId = message.id)
        }
        return AgentChatHomeUiState(
            messages = messages, history = history, journal = history,
            input = "", isStreaming = false, thinkingEnabled = false,
        )
    }

    private fun seed(vararg conversations: Pair<String, AgentChatHomeUiState>) = runBlocking {
        val states = linkedMapOf(*conversations)
        AgentConversationStore.save(
            context, states.keys.first(), states,
            states.keys.associateWith { it },
            states.keys.associateWith { 1L },
        )
    }

    private fun awaitLoaded(state: AgentAppState, id: String) {
        awaitCondition {
            state.conversationPaneState.selectedConversationId == id &&
                state.homeState.isConversationLoaded && !state.isLoadingConversation
        }
        assertFalse(state.conversationLoadFailed)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(1)
        }
        assertTrue("异步会话操作未在限定时间内完成", condition())
    }
}
