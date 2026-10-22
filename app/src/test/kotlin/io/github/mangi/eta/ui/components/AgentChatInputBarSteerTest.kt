package io.github.mangi.eta.ui.components

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.SteerAcknowledgement
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.PendingImageUi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "en-rUS")
class AgentChatInputBarSteerTest {
    private val input = mutableStateOf("")
    private val inputVersion = mutableStateOf(0)
    private val compositionKey = mutableStateOf(0)
    private val acknowledgement = mutableStateOf<SteerAcknowledgement?>(null)
    private val steerEnabled = mutableStateOf(true)
    private val editing = mutableStateOf(false)
    private val streaming = mutableStateOf(true)
    private val submitting = mutableStateOf(false)
    private val queuedMessages = mutableStateOf<List<QueuedMessageUi>>(emptyList())
    private val consumed = mutableListOf<Long>()
    private val supplements = mutableListOf<String>()
    private val submissions = mutableListOf<String>()
    private val queuedSteers = mutableListOf<String>()
    private val recalls = mutableListOf<Pair<String, String>>()

    @Test
    fun consumedAcknowledgementCannotClearSameTextAfterRecallAndRemount() = withInput { view, pump ->
        setText(view, "supplement")
        acknowledgement.value = SteerAcknowledgement("supplement", 1, 0)
        pump()
        assertEquals("", text(view))
        assertEquals(listOf(1L), consumed)

        input.value = "supplement"
        inputVersion.value++
        pump()
        compositionKey.value++
        pump()

        assertEquals("supplement", text(view))
        assertEquals(listOf(1L), consumed)
    }

    @Test
    fun lateAcknowledgementPreservesSameTextRecalledAfterSubmission() = withInput { view, pump ->
        input.value = "supplement"
        inputVersion.value++
        pump()
        acknowledgement.value = SteerAcknowledgement("supplement", 1, 0)
        pump()

        assertEquals("supplement", text(view))
        assertEquals(listOf(1L), consumed)
        compositionKey.value++
        pump()
        assertEquals("supplement", text(view))
    }

    @Test
    fun acceptancePreservesAChangedLocalDraftAndIsStillConsumed() = withInput { view, pump ->
        setText(view, "new draft")
        acknowledgement.value = SteerAcknowledgement("supplement", 1, 0)
        pump()

        assertEquals("new draft", text(view))
        assertEquals(listOf(1L), consumed)
    }

    @Test
    fun lateAcknowledgementDoesNotClearAMatchingMessageBeingEdited() = withInput { view, pump ->
        input.value = "supplement"
        editing.value = true
        pump()
        acknowledgement.value = SteerAcknowledgement("supplement", 1, 0)
        pump()

        assertEquals("supplement", text(view))
        assertEquals(listOf(1L), consumed)
    }

    @Test
    fun repeatedSameTextIsClearedForEachNewAcknowledgement() = withInput { view, pump ->
        for (sequence in 1L..2L) {
            setText(view, "supplement")
            pump()
            assertEquals("supplement", text(view))
            acknowledgement.value = SteerAcknowledgement("supplement", sequence, 0)
            pump()
            assertEquals("", text(view))
        }
        assertEquals(listOf(1L, 2L), consumed)
    }

    @Test
    fun turningOffSteerHidesOnlyItsEntry() = withInput { view, pump ->
        val description = view.context.getString(R.string.chat_steer_send)
        setText(view, "supplement")
        pump()
        assertEquals(1, nodes(view).count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true })
        steerEnabled.value = false
        pump()
        assertFalse(nodes(view).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true })
        assertEquals("supplement", text(view))
    }

    @Test
    fun supplementEntryIsHiddenForEmptyOrWhitespaceInputAndUsesTheSteerCallback() = withInput { view, pump ->
        val description = view.context.getString(R.string.chat_steer_send)
        assertTrue(describedActions(view, description).isEmpty())
        setText(view, "   \n")
        pump()
        assertTrue(describedActions(view, description).isEmpty())
        setText(view, "supplement")
        pump()
        click(describedActions(view, description).single())
        assertEquals(listOf("supplement"), supplements)
        assertTrue(submissions.isEmpty())
        assertEquals("supplement", text(view))
        setText(view, "")
        pump()
        assertTrue(describedActions(view, description).isEmpty())
    }

    @Test
    fun queuedFlashSteersWhileRecallReceivesTheUnsentDraftAndPrimarySendStillSubmits() = withInput { view, pump ->
        queuedMessages.value = listOf(QueuedMessageUi("queued", "queued text"))
        pump()
        click(describedActions(view, view.context.getString(R.string.chat_steer_send)).single())
        assertEquals(listOf("queued"), queuedSteers)
        assertTrue(submissions.isEmpty())
        setText(view, "new draft")
        pump()
        val preview = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "queued text" } == true }
        click(preview)
        assertEquals(listOf("queued" to "new draft"), recalls)
        click(describedActions(view, view.context.getString(R.string.chat_send)).single())
        pump()
        assertEquals(listOf("new draft"), submissions)
        assertEquals("", text(view))
    }

    @Test
    fun queuedAttachmentsDisableSteerButKeepRecallAndIdleSend() = withInput { view, pump ->
        val image = PendingImageUi("image", "uri", "data", "image/png")
        queuedMessages.value = listOf(QueuedMessageUi("queued", "with image", pendingImages = listOf(image)))
        pump()
        val flash = describedActions(view, view.context.getString(R.string.chat_steer_send)).single()
        assertFalse(actionEnabled(flash))
        val preview = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("with image") } == true }
        click(preview)
        assertEquals(listOf("queued" to ""), recalls)
        streaming.value = false
        pump()
        val send = describedActions(view, view.context.getString(R.string.chat_send)).single(::actionEnabled)
        assertTrue(actionEnabled(send))
        click(send)
        assertEquals(listOf("queued"), queuedSteers)
    }

    @Test
    fun queuedSteerRespectsTheSettingAndPendingInteraction() = withInput { view, pump ->
        queuedMessages.value = listOf(QueuedMessageUi("queued", "queued text"))
        val description = view.context.getString(R.string.chat_steer_send)
        steerEnabled.value = false
        pump()
        assertFalse(actionEnabled(describedActions(view, description).single()))
        steerEnabled.value = true
        submitting.value = true
        pump()
        assertFalse(actionEnabled(describedActions(view, description).single()))
        submitting.value = false
        pump()
        assertTrue(actionEnabled(describedActions(view, description).single()))
    }

    private fun nodes(view: ComposeView): List<SemanticsNode> {
        val ownerView = view.getChildAt(0)
        val owner = ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(owner.unmergedRootSemanticsNode)
    }

    private fun editable(view: ComposeView) = nodes(view).first { it.config.getOrNull(SemanticsProperties.EditableText) != null }
    private fun actionNode(node: SemanticsNode): SemanticsNode {
        var current: SemanticsNode? = node
        while (current != null) {
            if (current.config.getOrNull(SemanticsActions.OnClick) != null || current.config.contains(SemanticsProperties.Disabled)) return current
            current = current.parent
        }
        return node
    }
    private fun actionEnabled(node: SemanticsNode) =
        node.config.getOrNull(SemanticsActions.OnClick) != null && !node.config.contains(SemanticsProperties.Disabled)
    private fun describedActions(view: ComposeView, description: String): List<SemanticsNode> =
        nodes(view).filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
            .map(::actionNode).distinctBy { it.id }
    private fun click(node: SemanticsNode) {
        requireNotNull(actionNode(node).config[SemanticsActions.OnClick].action).invoke()
    }
    private fun text(view: ComposeView) = editable(view).config[SemanticsProperties.EditableText].text
    private fun setText(view: ComposeView, value: String) {
        requireNotNull(editable(view).config[SemanticsActions.SetText].action).invoke(AnnotatedString(value))
    }

    private fun withInput(verify: (ComposeView, () -> Unit) -> Unit) {
        val dispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher("input-test").immediate
        val clock = BroadcastFrameClock()
        val policy = object : InfiniteAnimationPolicy {
            override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R = throw CancellationException("No infinite animation in input test")
        }
        val scale = object : MotionDurationScale { override val scaleFactor = 0f }
        WindowRecomposerPolicy.withFactory(WindowRecomposerFactory { root ->
            root.createLifecycleAwareWindowRecomposer(coroutineContext = dispatcher + clock + policy + scale)
        }) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            SettingsDataStore.init(activity)
            val view = ComposeView(activity)
            var frame = 0L
            fun pump() {
                repeat(10) {
                    Snapshot.sendApplyNotifications()
                    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                    frame += 16_000_000
                    clock.sendFrame(frame)
                    ShadowLooper.idleMainLooper()
                    view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 600, 500)
                }
            }
            try {
                activity.setContentView(view)
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalSquircleEnabled provides false) {
                        MiuixTheme {
                            key(compositionKey.value) {
                                AgentChatInputBar(
                                    input = input.value, inputSyncVersion = inputVersion.value,
                                    modelPickerState = AgentModelPickerUiState(), isCompacting = false,
                                    contextUsage = AgentContextUsageUi(null, null), showContextUsage = false,
                                    isStreaming = streaming.value, steerEnabled = steerEnabled.value,
                                    interactionSubmitting = submitting.value, queuedMessages = queuedMessages.value,
                                    steerAcknowledgement = acknowledgement.value,
                                    onSteerAcknowledgementConsumed = { sequence ->
                                        consumed += sequence
                                        if (acknowledgement.value?.sequence == sequence) acknowledgement.value = null
                                    },
                                    reasoningEffort = ReasoningEffort.OFF, availableReasoningEfforts = emptyList(),
                                    pendingImages = emptyList(), pendingFileReferences = emptyList(),
                                    isEditingMessage = editing.value, editHasLaterTurns = false, preserveFollowingMessages = false,
                                    onReasoningEffortChange = {}, onCompactContext = {}, canCompactContext = false,
                                    onModelSelected = {}, onSubmit = { submissions += it }, onSteer = { supplements += it },
                                    onSteerQueuedMessage = { queuedSteers += it }, onRecallQueuedMessage = { id, draft -> recalls += id to draft },
                                    onStop = {}, onAttachImage = {}, onRemoveImage = {},
                                    onAttachFiles = {}, onAttachFolder = {}, onAttachFilePath = {}, onRemoveFileReference = {},
                                    onCancelMessageEdit = {},
                                )
                            }
                        }
                    }
                }
                pump()
                verify(view, ::pump)
            } finally {
                view.disposeComposition()
                controller.pause().stop().destroy()
            }
        }
    }
}
