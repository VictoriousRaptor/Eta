package io.github.mangi.eta.agent.runtime

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayInteractionDraft
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.app.EtaUiVisibility
import io.github.mangi.eta.ui.app.EtaUiVisibilityTracker
import io.github.mangi.eta.ui.components.AgentClarificationPagerState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AgentRuntimeOverlayLifecycleTest {
    private class TestActivity : Activity()
    private lateinit var tracker: EtaUiVisibilityTracker
    private lateinit var controller: ServiceController<AgentRuntimeService>
    private lateinit var service: AgentRuntimeService
    private val registry get() = field("runs") as RuntimeRunRegistry
    private val gui get() = field("guiControl") as GuiControlCoordinator

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        tracker = EtaUiVisibilityTracker(stopDebounceMs = 0L, isTrackedActivity = { it is TestActivity })
        EtaUiVisibility.setTrackerForTest(tracker)
        controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        service = controller.get()
        Prefs.initLocal(service)
        Prefs.localAgentPreferences()!!.edit().putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, true).commit()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        controller.destroy()
        EtaUiVisibility.setTrackerForTest(null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun foregroundVisibilityKeepsActiveRunAndService() {
        val run = run("active")
        tracker.onActivityStarted(TestActivity())
        ShadowLooper.idleMainLooper()
        assertTrue(registry.contains(run))
        assertFalse(run.session.isTerminal)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun foregroundCompletionClearsOnlyCompletedRunAndStopsWhenEmpty() {
        val run = run("finished")
        run.overlayRequested = true
        present(run)
        tracker.onActivityStarted(TestActivity())
        ShadowLooper.idleMainLooper()
        complete(run, ok = true)
        assertFalse(run.overlayRequested)
        assertEquals(AgentOverlayInteractionDraft.Empty, run.interactionDraft)
        assertNull(field("capsuleView"))
        assertNull(field("resultCardView"))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun foregroundFailureClearsPendingAndKeepsOtherRunAlive() {
        val failed = run("failed")
        val other = run("other")
        failed.overlayState = waiting("request")
        failed.overlayRequested = true
        other.interactionDraft = AgentOverlayInteractionDraft(supplementText = "仍在编辑")
        present(failed)
        tracker.onActivityStarted(TestActivity())
        ShadowLooper.idleMainLooper()
        complete(failed, ok = false)
        assertFalse(failed.overlayRequested)
        assertNull(failed.overlayState.pendingUserInput)
        assertEquals("仍在编辑", other.interactionDraft.supplementText)
        assertTrue(registry.contains(other))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun returningToEtaDismissesTerminalResultAndStopsWhenEmpty() = withRecomposerPolicy { pump ->
        val run = run("result")
        run.hasExecutedForegroundTool = true
        present(run)
        complete(run, ok = true)
        pump()
        assertNotNull(field("resultCardView"))
        tracker.onActivityStarted(TestActivity())
        pump()
        assertNull(field("resultCardView"))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun overlayLayoutParamsKeepInteractiveWindowsAwake() {
        val capsule = invoke("capsuleLayoutParams") as WindowManager.LayoutParams
        val card = invoke("resultCardLayoutParams") as WindowManager.LayoutParams
        val glow = invoke("glowLayoutParams") as WindowManager.LayoutParams
        assertTrue(capsule.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertTrue(card.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertEquals(0, glow.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun pendingInputRemainsExpandedAndFocusableAcrossModeChanges() = withRecomposerPolicy { pump ->
        val run = run("pending")
        run.overlayState = waiting("request")
        present(run)
        invoke("showOverlay")
        pump()
        invoke("setCapsuleInputMode", Boolean::class.javaPrimitiveType!! to false)
        invoke("toggleCapsule")
        invoke("onCapsuleCollapsedSettled")
        pump()
        val params = field("capsuleParams") as WindowManager.LayoutParams
        assertTrue(expanded().value)
        assertTrue(params.height > dp(124))
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun repeatedOverlayShowWaitsForCollapseAnimationBeforeShrinkingWindow() = withRecomposerPolicy { pump ->
        val run = run("collapse")
        present(run)
        invoke("showOverlay")
        pump()
        invoke("toggleCapsule")
        pump()
        invoke("toggleCapsule")
        invoke("showOverlay")
        assertEquals(dp(124), (field("capsuleParams") as WindowManager.LayoutParams).height)
        invoke("onCapsuleCollapsedSettled")
        assertEquals(dp(68), (field("capsuleParams") as WindowManager.LayoutParams).height)
    }

    @Test
    fun clarificationOnlyOffersPauseWhenItsRunOwnsGuiControl() = withRecomposerPolicy { pump ->
        val run = run("clarify-pause")
        event(run, AgentEvent.UserInputRequested(waiting("request").pendingUserInput!!))
        pump()
        assertFalse(capsuleHasAction(R.string.overlay_pause))
        assertTrue(capsuleHasAction(R.string.action_stop))
        val control = gui.openRun(run.runId, run.session.controller) { event(run, AgentEvent.GuiControlChanged(it)) }
        try {
            control.withAction { }
            pump()
            assertTrue(capsuleHasAction(R.string.overlay_pause))
        } finally {
            control.close()
        }
    }

    @Test
    fun foregroundDetachesWindowsAndBackCallbackWithoutDiscardingPendingDraft() = withRecomposerPolicy { pump ->
        val run = run("pending")
        run.overlayRequested = true
        run.overlayState = waiting("request")
        run.interactionDraft = run.interactionDraft.copy(
            clarificationState = AgentClarificationPagerState(run.overlayState.pendingUserInput!!).withAnswer("q", "答案"),
        )
        present(run)
        invoke("showOverlay")
        pump()
        val capsule = field("capsuleView") as View
        val glow = field("glowView") as View
        val dispatcher = CountingBackInvokedDispatcher()
        setField("resultCardBack", dispatcher to OnBackInvokedCallback {})
        tracker.onActivityStarted(TestActivity())
        pump()
        assertEquals(1, dispatcher.unregisterCount)
        listOf("capsuleView", "glowView", "resultCardView", "capsuleParams", "glowParams", "resultCardParams", "resultCardBack").forEach {
            assertNull(it, field(it))
        }
        assertFalse(capsule.isAttachedToWindow)
        assertFalse(glow.isAttachedToWindow)
        assertSame(run, field("displayedRun"))
        assertEquals("request", run.overlayState.pendingUserInput?.id)
        assertEquals("答案", run.interactionDraft.clarificationState?.answers?.get("q"))
        assertTrue(run.overlayRequested)
        assertFalse(run.session.isTerminal)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun backgroundRestoresPendingInputEvenWithoutPreviousRevealRequest() = withRecomposerPolicy { pump ->
        val run = run("pending")
        run.overlayState = waiting("request")
        present(run)
        val activity = TestActivity()
        tracker.onActivityStarted(activity)
        pump()
        tracker.onActivityStopped(activity)
        pump()
        assertNotNull(field("capsuleView"))
        assertNotNull(field("glowView"))
        assertNull(field("resultCardView"))
        assertTrue(expanded().value)
        assertEquals(0, (field("capsuleParams") as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(run.hasPresentedClarification)
    }

    @Test
    fun backgroundRestoresExpandedControlsAndSupplementDraftAtTheirOriginalHeight() = withRecomposerPolicy { pump ->
        val run = run("draft")
        run.overlayRequested = true
        run.capsuleExpanded = true
        present(run)
        invoke("showOverlay")
        pump()
        val activity = TestActivity()
        tracker.onActivityStarted(activity)
        pump()
        tracker.onActivityStopped(activity)
        pump()
        assertEquals(dp(124), (field("capsuleParams") as WindowManager.LayoutParams).height)
        assertTrue(expanded().value)
        run.interactionDraft = AgentOverlayInteractionDraft(supplementText = "补充草稿", supplementMode = true)
        val old = field("capsuleView")
        tracker.onActivityStarted(activity)
        pump()
        tracker.onActivityStopped(activity)
        pump()
        assertNotSame(old, field("capsuleView"))
        assertEquals("补充草稿", run.interactionDraft.supplementText)
        val params = field("capsuleParams") as WindowManager.LayoutParams
        assertEquals(dp(260), params.height)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    @Test
    fun backgroundDoesNotRestoreTextOnlyRunWithoutOverlayRequest() = withRecomposerPolicy { pump ->
        val run = run("text")
        present(run)
        val activity = TestActivity()
        tracker.onActivityStarted(activity)
        pump()
        tracker.onActivityStopped(activity)
        pump()
        assertNull(field("capsuleView"))
        assertNull(field("glowView"))
        assertNull(field("resultCardView"))
        assertFalse(run.session.isTerminal)
    }

    @Test
    fun backgroundClarificationShowsResultCardForSuccessAndFailure() = withRecomposerPolicy { pump ->
        for (ok in listOf(true, false)) {
            val run = run("clarify-$ok")
            run.overlayState = waiting("request-$ok")
            present(run)
            invoke("showOverlay")
            pump()
            assertTrue(run.hasPresentedClarification)
            complete(run, ok)
            pump()
            assertNotNull(field("resultCardView"))
            assertNull(run.overlayState.pendingUserInput)
            assertEquals(AgentOverlayInteractionDraft.Empty, run.interactionDraft)
            assertFalse(run.overlayRequested)
            invoke("dismissAndStop")
        }
    }

    @Test
    fun backgroundTextOnlyCompletionDoesNotShowResultCard() = withRecomposerPolicy { pump ->
        val run = run("text")
        present(run)
        complete(run, true)
        pump()
        assertNull(field("resultCardView"))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun answeredClarificationRemovesTemporaryWindowsAndDoesNotRestoreThemInBackground() = withRecomposerPolicy { pump ->
        val run = run("temporary-clarify")
        val request = waiting("request").pendingUserInput!!
        event(run, AgentEvent.UserInputRequested(request))
        pump()
        val capsule = field("capsuleView") as View
        val glow = field("glowView") as View
        event(run, AgentEvent.UserInputAnswered(AgentUserInputAnswer(request.id, mapOf("q" to "答案"))))
        pump()
        assertFalse(capsule.isAttachedToWindow)
        assertFalse(glow.isAttachedToWindow)
        assertNull(field("capsuleView"))
        assertFalse(run.overlayRequested)
        assertTrue(registry.contains(run))

        val activity = TestActivity()
        tracker.onActivityStarted(activity)
        pump()
        tracker.onActivityStopped(activity)
        pump()
        assertNull(field("capsuleView"))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun answeredClarificationRestoresPreviousExpansionAndWindowFlags() = withRecomposerPolicy { pump ->
        val run = run("existing-capsule")
        run.overlayRequested = true
        present(run)
        invoke("showOverlay")
        pump()
        val capsule = field("capsuleView") as View
        val params = field("capsuleParams") as WindowManager.LayoutParams
        val flagsBeforeClarification = params.flags
        for (wasExpanded in listOf(false, true)) {
            run.capsuleExpanded = wasExpanded
            expanded().value = wasExpanded
            invoke("setCapsuleInputMode", Boolean::class.javaPrimitiveType!! to false)
            val request = waiting("request-$wasExpanded").pendingUserInput!!
            event(run, AgentEvent.UserInputRequested(request))
            pump()
            assertTrue(expanded().value)
            event(run, AgentEvent.UserInputAnswered(AgentUserInputAnswer(request.id, mapOf("q" to "答案"))))
            pump()
            assertSame(capsule, field("capsuleView"))
            assertTrue(capsule.isAttachedToWindow)
            assertEquals(wasExpanded, expanded().value)
            assertEquals(flagsBeforeClarification, params.flags)
            assertEquals(dp(if (wasExpanded) 124 else 68), params.height)
        }
    }

    @Test
    fun clarificationAnsweredInForegroundDoesNotCreateOverlayWhenLeavingEta() = withRecomposerPolicy { pump ->
        val run = run("foreground-clarify")
        val activity = TestActivity()
        tracker.onActivityStarted(activity)
        pump()
        val request = waiting("request").pendingUserInput!!
        event(run, AgentEvent.UserInputRequested(request))
        pump()
        event(run, AgentEvent.UserInputAnswered(AgentUserInputAnswer(request.id, mapOf("q" to "答案"))))
        pump()
        tracker.onActivityStopped(activity)
        pump()
        assertNull(field("capsuleView"))
        assertFalse(run.overlayRequested)
    }

    @Test
    fun guiOwnershipWaitsForSuccessfulEntryDismissalBeforeShowingOverlay() = withRecomposerPolicy { pump ->
        val run = run("external-owner", dismissEntrySurface = true)
        val control = gui.openRun(run.runId, run.session.controller)
        try {
            control.withAction { }
            pump()
            assertNull("StateFlow ownership alone must not reveal the entry overlay", field("capsuleView"))

            event(run, AgentEvent.GuiControlChanged(GuiControlStatus.Acquired), entrySurfaceReady = false)
            pump()
            assertNull(field("capsuleView"))
            assertFalse(run.overlayRequested)
            event(run, AgentEvent.ToolFinished(1, "tap", "tap", "entry dismissal failed", 0, 0, success = false))
            pump()
            assertNull(field("capsuleView"))
            assertFalse(run.hasExecutedForegroundTool)

            event(run, AgentEvent.GuiControlChanged(GuiControlStatus.Acquired), entrySurfaceReady = true)
            pump()
            assertNotNull(field("capsuleView"))
        } finally {
            control.close()
        }
    }

    @Test
    fun terminalCapturedRunSupplementDoesNotStartNewTask() {
        val run = run("finished")
        present(run)
        run.session.complete(AgentRuntimeWire.RunResult(run.runId, true, "完成"))
        registry.finish(run.runId, run.session)
        assertFalse(invoke("requestSupplement", String::class.java to run.runId, String::class.java to "迟到的旧回调") as Boolean)
        assertTrue(registry.all().isEmpty())
        assertNull(field("capsuleView"))
    }

    @Test
    fun clarifyOnlyResultCardCanBeDismissedWithoutGuiOwnershipHistory() = withRecomposerPolicy { pump ->
        val run = run("clarify-result")
        event(run, AgentEvent.UserInputRequested(waiting("request").pendingUserInput!!))
        pump()
        complete(run, true)
        pump()
        assertNotNull(field("resultCardView"))
        assertNull(field("lastGuiOwnerRunId"))
        invoke("dismissResult", RuntimeRunRegistry.Run::class.java to run)
        pump()
        assertNull(field("resultCardView"))
        assertNull(field("displayedRun"))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun clarificationCanDisplayWithoutAcquiringGuiControl() = withRecomposerPolicy { pump ->
        val run = run("clarify")
        event(run, AgentEvent.UserInputRequested(waiting("request").pendingUserInput!!))
        pump()
        assertNull(gui.state.value.ownerRunId)
        assertSame(run, field("displayedRun"))
        assertNotNull(field("capsuleView"))
        assertTrue(run.hasPresentedClarification)
    }

    @Test
    fun guiOwnerPreemptsClarificationAndReleaseRestoresItsOwnDraft() = withRecomposerPolicy { pump ->
        val clarify = run("clarify")
        event(clarify, AgentEvent.UserInputRequested(waiting("request").pendingUserInput!!))
        pump()
        clarify.interactionDraft = clarify.interactionDraft.copy(
            supplementText = "Clarify 的草稿",
            clarificationState = clarify.interactionDraft.clarificationState!!.withAnswer("q", "原答案"),
        )
        val owner = run("gui")
        owner.interactionDraft = AgentOverlayInteractionDraft(supplementText = "GUI 的草稿")
        val control = gui.openRun(owner.runId, owner.session.controller) {
            event(owner, AgentEvent.GuiControlChanged(it))
        }
        control.withAction { }
        pump()
        assertSame(owner, field("displayedRun"))
        event(clarify, AgentEvent.UserInputRequested(clarify.overlayState.pendingUserInput!!))
        pump()
        assertSame(owner, field("displayedRun"))
        assertEquals("GUI 的草稿", owner.interactionDraft.supplementText)
        control.close()
        pump()
        assertSame(clarify, field("displayedRun"))
        assertEquals("原答案", clarify.interactionDraft.clarificationState?.answers?.get("q"))
        assertEquals("Clarify 的草稿", clarify.interactionDraft.supplementText)
    }

    @Test
    fun anotherRunCompletionDoesNotClearDisplayedDraftOrReplaceGuiOwner() = withRecomposerPolicy { pump ->
        val owner = run("owner")
        owner.interactionDraft = AgentOverlayInteractionDraft(supplementText = "保留", supplementMode = true)
        val control = gui.openRun(owner.runId, owner.session.controller) { event(owner, AgentEvent.GuiControlChanged(it)) }
        control.withAction { }
        pump()
        val other = run("other")
        other.overlayState = waiting("other-request")
        other.hasPresentedClarification = true
        complete(other, true)
        pump()
        assertSame(owner, field("displayedRun"))
        assertEquals("保留", owner.interactionDraft.supplementText)
        assertTrue(owner.interactionDraft.supplementMode)
        assertNull(other.overlayState.pendingUserInput)
        assertEquals(AgentOverlayInteractionDraft.Empty, other.interactionDraft)
        assertNull(field("resultCardView"))
        control.close()
    }

    @Test
    fun steerTargetsHiddenRunAndRejectsUnknownRunWithoutChangingDisplayedDraft() {
        val hidden = run("hidden")
        val shown = run("shown")
        shown.interactionDraft = AgentOverlayInteractionDraft(supplementText = "保留草稿")
        present(shown)
        assertTrue(invoke("steerRun", String::class.java to hidden.runId, String::class.java to "只发给 hidden") as Boolean)
        assertFalse(invoke("steerRun", String::class.java to "stale", String::class.java to "错误") as Boolean)
        assertEquals("只发给 hidden", hidden.session.controller.pollSteeringMessage())
        assertNull(shown.session.controller.pollSteeringMessage())
        assertEquals("保留草稿", shown.interactionDraft.supplementText)
    }

    @Test
    fun clarificationAnswerValidatesRunAndRequestWithoutClearingAnotherRunDraft() {
        val waitingRun = run("waiting")
        val shown = run("shown")
        shown.interactionDraft = AgentOverlayInteractionDraft(supplementText = "保留草稿")
        present(shown)
        val request = waiting("request").pendingUserInput!!
        val ready = CountDownLatch(1)
        val worker = thread { waitingRun.session.controller.awaitUserInput(request) { ready.countDown() } }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val answer = AgentUserInputAnswer("request", mapOf("q" to "答案"))
            assertFalse(invoke("answerRunUserInput", String::class.java to shown.runId, AgentUserInputAnswer::class.java to answer) as Boolean)
            assertFalse(invoke("answerRunUserInput", String::class.java to waitingRun.runId, AgentUserInputAnswer::class.java to answer.copy(requestId = "old")) as Boolean)
            assertTrue(invoke("answerRunUserInput", String::class.java to waitingRun.runId, AgentUserInputAnswer::class.java to answer) as Boolean)
            assertFalse(invoke("answerRunUserInput", String::class.java to waitingRun.runId, AgentUserInputAnswer::class.java to answer) as Boolean)
            assertEquals("保留草稿", shown.interactionDraft.supplementText)
        } finally {
            waitingRun.session.controller.cancel()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
    }

    private fun run(id: String, dismissEntrySurface: Boolean = false): RuntimeRunRegistry.Run {
        val request = AgentRuntimeWire.RunRequest(
            runId = id, prompt = "测试",
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test", model = "test", systemPrompt = ""),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(id, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, id, dismissEntrySurface),
        )
        return (registry.admit(request) { RuntimeRunRegistry.Run(request, AgentRuntimeSession(id)) }
            as RuntimeRunRegistry.Admission.Accepted).run.also { it.prepared(request) }
    }

    private fun waiting(id: String) = AgentOverlayState(
        phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.WaitingForUser,
        pendingUserInput = AgentUserInputRequest(id, listOf(AgentUserInputQuestion("q", "问题"))),
    )

    private fun present(run: RuntimeRunRegistry.Run) {
        invoke("bindDisplayedRun", RuntimeRunRegistry.Run::class.java to run)
        @Suppress("UNCHECKED_CAST")
        val state = field("state") as MutableState<AgentOverlayState>
        state.value = run.overlayState
    }

    private fun event(run: RuntimeRunRegistry.Run, event: AgentEvent, entrySurfaceReady: Boolean = true) {
        invoke("handleAcceptedRunEvent", RuntimeRunRegistry.Run::class.java to run, AgentEvent::class.java to event,
            Boolean::class.javaPrimitiveType!! to entrySurfaceReady)
    }

    private fun complete(run: RuntimeRunRegistry.Run, ok: Boolean) {
        val result = AgentRuntimeWire.RunResult(run.runId, ok, if (ok) "完成" else "", if (ok) null else "失败")
        run.session.complete(result)
        registry.finish(run.runId, run.session)
        invoke("postTerminalOverlay", RuntimeRunRegistry.Run::class.java to run, AgentRuntimeWire.RunResult::class.java to result)
        ShadowLooper.idleMainLooper()
    }

    @Suppress("UNCHECKED_CAST")
    private fun expanded() = field("capsuleExpanded") as MutableState<Boolean>
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun capsuleHasAction(label: Int): Boolean {
        val view = field("capsuleView") as ComposeView
        val ownerView = view.getChildAt(0)
        val owner = ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
        fun containsAction(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(service.getString(label)) == true ||
                node.children.any(::containsAction)
        return containsAction(owner.unmergedRootSemanticsNode)
    }
    private fun setField(name: String, value: Any?) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    private fun invoke(name: String, vararg args: Pair<Class<*>, Any?>): Any? = service.javaClass
        .getDeclaredMethod(name, *args.map { it.first }.toTypedArray()).apply { isAccessible = true }
        .invoke(service, *args.map { it.second }.toTypedArray())

    private class CountingBackInvokedDispatcher : OnBackInvokedDispatcher {
        var unregisterCount = 0
        override fun registerOnBackInvokedCallback(priority: Int, callback: OnBackInvokedCallback) = Unit
        override fun unregisterOnBackInvokedCallback(callback: OnBackInvokedCallback) { unregisterCount++ }
    }

    private object testPolicy : InfiniteAnimationPolicy {
        override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R =
            throw CancellationException("Infinite animation disabled for functional Robolectric test")
    }
    private object testMotionDurationScale : MotionDurationScale { override val scaleFactor = 0f }

    private fun withRecomposerPolicy(block: (pump: () -> Unit) -> Unit) {
        val dispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher("overlay-test").immediate
        val clock = BroadcastFrameClock()
        var nanos = 0L
        val pump = {
            repeat(10) {
                Snapshot.sendApplyNotifications()
                ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                Snapshot.sendApplyNotifications()
                ShadowLooper.idleMainLooper()
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                ShadowLooper.idleMainLooper()
            }
            Snapshot.sendApplyNotifications()
            ShadowLooper.idleMainLooper()
        }
        try {
            WindowRecomposerPolicy.withFactory(WindowRecomposerFactory { root ->
                root.createLifecycleAwareWindowRecomposer(coroutineContext = dispatcher + testPolicy + clock + testMotionDurationScale)
            }) { block(pump) }
        } finally {
            invoke("removeOverlayWindowsForForeground")
        }
    }
}
