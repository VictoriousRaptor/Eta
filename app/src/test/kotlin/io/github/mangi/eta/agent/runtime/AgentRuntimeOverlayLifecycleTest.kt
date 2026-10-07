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
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.app.EtaUiVisibility
import io.github.mangi.eta.ui.app.EtaUiVisibilityTracker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 0L,
            isTrackedActivity = { it is TestActivity },
        )
        EtaUiVisibility.setTrackerForTest(tracker)
        controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        service = controller.get()
        Prefs.initLocal(service)
    }

    @After
    fun tearDown() {
        controller.destroy()
        EtaUiVisibility.setTrackerForTest(null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun runningVisibilityChangeDoesNotStopService() {
        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        assertTrue(EtaUiVisibility.isVisible)

        ShadowLooper.idleMainLooper()

        val shadowService = shadowOf(service)
        assertFalse(shadowService.isStoppedBySelf)
    }

    @Test
    fun finishedTerminalStateInForegroundStopsServiceAndClearsOverlayRequested() {
        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        assertTrue(EtaUiVisibility.isVisible)

        val overlayRequestedField = AgentRuntimeService::class.java.getDeclaredField("overlayRequested").apply {
            isAccessible = true
        }
        overlayRequestedField.set(service, true)
        assertTrue(overlayRequestedField.get(service) as Boolean)

        val enterFinalStateMethod = AgentRuntimeService::class.java.getDeclaredMethod(
            "enterFinalState",
            AgentOverlayState::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
        }

        val finalState = AgentOverlayState(
            phase = AgentOverlayPhase.FINISHED,
            status = AgentOverlayStatus.ResultReady,
        )
        enterFinalStateMethod.invoke(service, finalState, false)

        ShadowLooper.idleMainLooper()

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isStoppedBySelf)
        assertFalse(overlayRequestedField.get(service) as Boolean)
    }

    @Test
    fun failedTerminalStateInForegroundStopsServiceAndClearsOverlayRequested() {
        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        assertTrue(EtaUiVisibility.isVisible)

        val overlayRequestedField = AgentRuntimeService::class.java.getDeclaredField("overlayRequested").apply {
            isAccessible = true
        }
        overlayRequestedField.set(service, true)
        assertTrue(overlayRequestedField.get(service) as Boolean)

        val enterFinalStateMethod = AgentRuntimeService::class.java.getDeclaredMethod(
            "enterFinalState",
            AgentOverlayState::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
        }

        val failedState = AgentOverlayState(
            phase = AgentOverlayPhase.FAILED,
            status = AgentOverlayStatus.RunFailed,
        )
        enterFinalStateMethod.invoke(service, failedState, false)

        ShadowLooper.idleMainLooper()

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isStoppedBySelf)
        assertFalse(overlayRequestedField.get(service) as Boolean)
    }

    @Test
    fun finishedTerminalStateInBackgroundStopsServiceWhenReturningToEta() {
        val stateField = AgentRuntimeService::class.java.getDeclaredField("state").apply {
            isAccessible = true
        }
        val stateObject = stateField.get(service)
        val setValueMethod = stateObject::class.java.getDeclaredMethod("setValue", AgentOverlayState::class.java).apply {
            isAccessible = true
        }
        setValueMethod.invoke(stateObject, AgentOverlayState(
            phase = AgentOverlayPhase.FINISHED,
            status = AgentOverlayStatus.ResultReady,
        ))

        val overlayRequestedField = AgentRuntimeService::class.java.getDeclaredField("overlayRequested").apply {
            isAccessible = true
        }
        overlayRequestedField.set(service, true)
        assertTrue(overlayRequestedField.get(service) as Boolean)

        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        ShadowLooper.idleMainLooper()

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isStoppedBySelf)
        assertFalse(overlayRequestedField.get(service) as Boolean)
    }

    @Test
    fun failedTerminalStateInBackgroundStopsServiceWhenReturningToEta() {
        val stateField = AgentRuntimeService::class.java.getDeclaredField("state").apply {
            isAccessible = true
        }
        val stateObject = stateField.get(service)
        val setValueMethod = stateObject::class.java.getDeclaredMethod("setValue", AgentOverlayState::class.java).apply {
            isAccessible = true
        }
        setValueMethod.invoke(stateObject, AgentOverlayState(
            phase = AgentOverlayPhase.FAILED,
            status = AgentOverlayStatus.RunFailed,
        ))

        val overlayRequestedField = AgentRuntimeService::class.java.getDeclaredField("overlayRequested").apply {
            isAccessible = true
        }
        overlayRequestedField.set(service, true)
        assertTrue(overlayRequestedField.get(service) as Boolean)

        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        ShadowLooper.idleMainLooper()

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isStoppedBySelf)
        assertFalse(overlayRequestedField.get(service) as Boolean)
    }

    @Test
    fun overlayLayoutParamsHaveCorrectFlags() {
        val capsuleParamsMethod = AgentRuntimeService::class.java.getDeclaredMethod("capsuleLayoutParams").apply {
            isAccessible = true
        }
        val capsuleLp = capsuleParamsMethod.invoke(service) as android.view.WindowManager.LayoutParams
        assertTrue("capsule should keep screen on", (capsuleLp.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        val resultCardParamsMethod = AgentRuntimeService::class.java.getDeclaredMethod("resultCardLayoutParams").apply {
            isAccessible = true
        }
        val resultCardLp = resultCardParamsMethod.invoke(service) as android.view.WindowManager.LayoutParams
        assertTrue("result card should keep screen on", (resultCardLp.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        val glowParamsMethod = AgentRuntimeService::class.java.getDeclaredMethod("glowLayoutParams").apply {
            isAccessible = true
        }
        val glowLp = glowParamsMethod.invoke(service) as android.view.WindowManager.LayoutParams
        assertFalse("glow should NOT keep screen on", (glowLp.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
    }

    private object testPolicy : InfiniteAnimationPolicy {
        override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R {
            throw CancellationException("Infinite animation disabled for functional Robolectric test")
        }
    }

    private object testMotionDurationScale : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    private class CountingBackInvokedDispatcher : OnBackInvokedDispatcher {
        var registerCount = 0
        var unregisterCount = 0
        override fun registerOnBackInvokedCallback(priority: Int, callback: OnBackInvokedCallback) {
            registerCount++
        }
        override fun unregisterOnBackInvokedCallback(callback: OnBackInvokedCallback) {
            unregisterCount++
        }
    }

    private fun withRecomposerPolicy(block: (pump: () -> Unit) -> Unit) {
        val testDispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher("overlay-test").immediate
        val frameClock = BroadcastFrameClock()
        var frameNanos = 0L

        val pumpOverlay = {
            repeat(10) {
                Snapshot.sendApplyNotifications()
                ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                Snapshot.sendApplyNotifications()
                ShadowLooper.idleMainLooper()
                frameNanos += 16_000_000L
                frameClock.sendFrame(frameNanos)
                ShadowLooper.idleMainLooper()
                Snapshot.sendApplyNotifications()
                ShadowLooper.idleMainLooper()
            }
            Snapshot.sendApplyNotifications()
            ShadowLooper.idleMainLooper()
        }

        try {
            WindowRecomposerPolicy.withFactory(WindowRecomposerFactory { root ->
                root.createLifecycleAwareWindowRecomposer(coroutineContext = testDispatcher + testPolicy + frameClock + testMotionDurationScale)
            }) {
                block(pumpOverlay)
            }
        } finally {
            runCatching { invokeMethod(service, "removeOverlayWindowsForForeground") }
        }
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(target)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field.set(target, value)
    }

    private fun invokeMethod(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): Any? {
        val types = args.map { it.first }.toTypedArray()
        val values = args.map { it.second }.toTypedArray()
        val method = if (types.isEmpty()) {
            target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }
        } else {
            target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
        }
        return if (values.isEmpty()) method.invoke(target) else method.invoke(target, *values)
    }

    private fun getOverlayState(): AgentOverlayState {
        val stateHolder = getField(service, "state") ?: error("state field is null")
        val getValMethod = stateHolder.javaClass.getDeclaredMethod("getValue").apply { isAccessible = true }
        return getValMethod.invoke(stateHolder) as AgentOverlayState
    }

    private fun setOverlayState(state: AgentOverlayState) {
        val stateHolder = getField(service, "state") ?: error("state field is null")
        val setValMethod = stateHolder.javaClass.getDeclaredMethod("setValue", AgentOverlayState::class.java).apply { isAccessible = true }
        setValMethod.invoke(stateHolder, state)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getCapsuleExpanded(): MutableState<Boolean> {
        return getField(service, "capsuleExpanded") as MutableState<Boolean>
    }

    @Test
    fun dynamicFocusKeepsScreenOnAndPendingInputStaysFocusable() = withRecomposerPolicy { pump ->
        val session = AgentRuntimeSession("test-run")
        setField(service, "activeSession", session)
        setField(service, "overlayRequested", true)

        invokeMethod(service, "showOverlay")
        pump()

        val capsuleView = getField(service, "capsuleView") as View?
        val glowView = getField(service, "glowView") as View?
        assertNotNull("capsuleView should be non-null", capsuleView)
        assertNotNull("glowView should be non-null", glowView)
        assertTrue("capsuleView should be attached", capsuleView!!.isAttachedToWindow)
        assertTrue("glowView should be attached", glowView!!.isAttachedToWindow)

        val capsuleParams = getField(service, "capsuleParams") as WindowManager.LayoutParams
        invokeMethod(service, "setCapsuleInputMode", Boolean::class.javaPrimitiveType!! to true)
        pump()
        assertEquals(0, capsuleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue((capsuleParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        invokeMethod(service, "setCapsuleInputMode", Boolean::class.javaPrimitiveType!! to false)
        pump()
        assertTrue((capsuleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue((capsuleParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        val request = AgentUserInputRequest(
            id = "req",
            questions = listOf(AgentUserInputQuestion(id = "q", question = "目的地？")),
        )
        setOverlayState(AgentOverlayState(
            phase = AgentOverlayPhase.RUNNING,
            status = AgentOverlayStatus.WaitingForUser,
            pendingUserInput = request,
        ))
        getCapsuleExpanded().value = true

        invokeMethod(service, "setCapsuleInputMode", Boolean::class.javaPrimitiveType!! to false)
        pump()
        assertEquals(0, capsuleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue((capsuleParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        val density = service.resources.displayMetrics.density
        val controlsHeightPx = (124 * density).toInt()
        assertTrue("Height should be greater than controls height", capsuleParams.height > controlsHeightPx)

        val currentHeight = capsuleParams.height
        invokeMethod(service, "toggleCapsule")
        pump()
        assertEquals("Clarify forbids toggleCapsule collapse", currentHeight, capsuleParams.height)
        assertTrue(getCapsuleExpanded().value)

        invokeMethod(service, "onCapsuleCollapsedSettled")
        pump()
        assertEquals("Clarify forbids onCapsuleCollapsedSettled collapse", currentHeight, capsuleParams.height)
    }

    @Test
    fun foregroundRemovesAllOverlayWindowsButKeepsActiveSessionAndPending() = withRecomposerPolicy { pump ->
        val session = AgentRuntimeSession("test-run")
        setField(service, "activeSession", session)
        setField(service, "overlayRequested", true)

        val request = AgentUserInputRequest(
            id = "req",
            questions = listOf(AgentUserInputQuestion(id = "q", question = "目的地？")),
        )
        setOverlayState(AgentOverlayState(
            phase = AgentOverlayPhase.RUNNING,
            status = AgentOverlayStatus.WaitingForUser,
            pendingUserInput = request,
        ))

        invokeMethod(service, "showOverlay")
        val wm = service.getSystemService(WindowManager::class.java)
        invokeMethod(service, "showResultCard", WindowManager::class.java to wm)
        pump()

        val oldCapsuleView = getField(service, "capsuleView") as View?
        val oldGlowView = getField(service, "glowView") as View?
        val oldResultCardView = getField(service, "resultCardView") as View?
        assertNotNull("capsuleView should exist", oldCapsuleView)
        assertNotNull("glowView should exist", oldGlowView)
        assertNotNull("resultCardView should exist", oldResultCardView)
        assertNotNull("capsuleParams should exist", getField(service, "capsuleParams"))
        assertNotNull("glowParams should exist", getField(service, "glowParams"))
        assertNotNull("resultCardParams should exist", getField(service, "resultCardParams"))

        val countingDispatcher = CountingBackInvokedDispatcher()
        val callback = OnBackInvokedCallback {}
        setField(service, "resultCardBack", countingDispatcher to callback)

        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        pump()

        assertEquals("unregisterOnBackInvokedCallback should be called once", 1, countingDispatcher.unregisterCount)
        assertNull("capsuleView should be null", getField(service, "capsuleView"))
        assertNull("glowView should be null", getField(service, "glowView"))
        assertNull("resultCardView should be null", getField(service, "resultCardView"))
        assertNull("capsuleParams should be null", getField(service, "capsuleParams"))
        assertNull("glowParams should be null", getField(service, "glowParams"))
        assertNull("resultCardParams should be null", getField(service, "resultCardParams"))
        assertNull("resultCardBack should be null", getField(service, "resultCardBack"))

        assertFalse("old capsuleView should be detached", oldCapsuleView!!.isAttachedToWindow)
        assertFalse("old glowView should be detached", oldGlowView!!.isAttachedToWindow)
        assertFalse("old resultCardView should be detached", oldResultCardView!!.isAttachedToWindow)

        val currentSession = getField(service, "activeSession") as AgentRuntimeSession?
        assertSame("activeSession should remain the same", session, currentSession)
        assertFalse("activeSession should not be terminal", session.isTerminal)
        assertEquals("pending should remain identical", request, getOverlayState().pendingUserInput)
        assertTrue("overlayRequested should remain true", getField(service, "overlayRequested") as Boolean)
        assertFalse("service should not be stopped", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun backgroundRestoresPendingCapsuleAndFocusableFlags() = withRecomposerPolicy { pump ->
        val session = AgentRuntimeSession("test-run")
        setField(service, "activeSession", session)
        setField(service, "overlayRequested", true)

        val request = AgentUserInputRequest(
            id = "req",
            questions = listOf(AgentUserInputQuestion(id = "q", question = "目的地？")),
        )
        setOverlayState(AgentOverlayState(
            phase = AgentOverlayPhase.RUNNING,
            status = AgentOverlayStatus.WaitingForUser,
            pendingUserInput = request,
        ))

        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        pump()

        tracker.onActivityStopped(testActivity)
        pump()

        val capsuleView = getField(service, "capsuleView") as View?
        val glowView = getField(service, "glowView") as View?
        val resultCardView = getField(service, "resultCardView") as View?
        assertNotNull("capsuleView should be restored", capsuleView)
        assertNotNull("glowView should be restored", glowView)
        assertNull("resultCard should NOT be restored", resultCardView)
        assertTrue("capsuleView should be attached", capsuleView!!.isAttachedToWindow)
        assertTrue("glowView should be attached", glowView!!.isAttachedToWindow)

        assertTrue("capsuleExpanded should be true", getCapsuleExpanded().value)
        assertEquals("pending should not be lost", request, getOverlayState().pendingUserInput)

        val capsuleParams = getField(service, "capsuleParams") as WindowManager.LayoutParams
        val density = service.resources.displayMetrics.density
        val controlsHeightPx = (124 * density).toInt()
        assertTrue("height should be larger than controls height", capsuleParams.height > controlsHeightPx)
        assertEquals("should be focusable", 0, capsuleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue("should keep screen on", (capsuleParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
    }

    @Test
    fun backgroundRestoresNormalExpandedControlsAtCorrectHeight() = withRecomposerPolicy { pump ->
        val session = AgentRuntimeSession("test-run")
        setField(service, "activeSession", session)
        setField(service, "overlayRequested", true)
        setOverlayState(AgentOverlayState(
            phase = AgentOverlayPhase.RUNNING,
            status = AgentOverlayStatus.Reasoning,
            pendingUserInput = null,
        ))
        getCapsuleExpanded().value = true

        invokeMethod(service, "showOverlay")
        pump()

        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        pump()

        tracker.onActivityStopped(testActivity)
        pump()

        val capsuleView = getField(service, "capsuleView") as View?
        val glowView = getField(service, "glowView") as View?
        assertNotNull("capsuleView should be restored", capsuleView)
        assertNotNull("glowView should be restored", glowView)
        assertTrue(getCapsuleExpanded().value)

        val capsuleParams = getField(service, "capsuleParams") as WindowManager.LayoutParams
        val density = service.resources.displayMetrics.density
        val controlsHeightPx = (124 * density).toInt()
        val collapsedHeightPx = (68 * density).toInt()
        assertEquals("height should be controls height 124dp", controlsHeightPx, capsuleParams.height)
        assertTrue("height should not be 68dp", capsuleParams.height != collapsedHeightPx)
        assertTrue("NOT_FOCUSABLE should be set", (capsuleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue("KEEP_SCREEN_ON should be set", (capsuleParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
    }

    @Test
    fun backgroundDoesNotRestoreWithoutOverlayRequest() = withRecomposerPolicy { pump ->
        val session = AgentRuntimeSession("test-run")
        setField(service, "activeSession", session)
        setField(service, "overlayRequested", false)
        setOverlayState(AgentOverlayState(
            phase = AgentOverlayPhase.RUNNING,
            status = AgentOverlayStatus.Reasoning,
        ))

        val testActivity = TestActivity()
        tracker.onActivityStarted(testActivity)
        pump()

        tracker.onActivityStopped(testActivity)
        pump()

        assertNull("capsuleView should remain null", getField(service, "capsuleView"))
        assertNull("glowView should remain null", getField(service, "glowView"))
        assertNull("resultCardView should remain null", getField(service, "resultCardView"))
        assertFalse("session should not be terminal", session.isTerminal)
        assertFalse("service should not be stopped", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun finishedTerminalStateClearsPendingUserInput() {
        val request = AgentUserInputRequest(
            id = "req-finished",
            questions = listOf(AgentUserInputQuestion("q", "问题")),
        )
        val finalState = AgentOverlayState(
            phase = AgentOverlayPhase.FINISHED,
            status = AgentOverlayStatus.ResultReady,
            detailText = "任务已完成",
            pendingUserInput = request,
        )

        invokeMethod(
            service,
            "enterFinalState",
            AgentOverlayState::class.java to finalState,
            Boolean::class.javaPrimitiveType!! to false,
        )

        val currentState = getOverlayState()
        assertEquals(AgentOverlayPhase.FINISHED, currentState.phase)
        assertEquals(AgentOverlayStatus.ResultReady, currentState.status)
        assertEquals("任务已完成", currentState.detailText)
        assertNull("pendingUserInput must be cleared", currentState.pendingUserInput)
    }

    @Test
    fun failedTerminalStateClearsPendingUserInput() {
        val request = AgentUserInputRequest(
            id = "req-failed",
            questions = listOf(AgentUserInputQuestion("q", "问题")),
        )
        val finalState = AgentOverlayState(
            phase = AgentOverlayPhase.FAILED,
            status = AgentOverlayStatus.RunFailed,
            detailText = "执行失败",
            pendingUserInput = request,
        )

        invokeMethod(
            service,
            "enterFinalState",
            AgentOverlayState::class.java to finalState,
            Boolean::class.javaPrimitiveType!! to false,
        )

        val currentState = getOverlayState()
        assertEquals(AgentOverlayPhase.FAILED, currentState.phase)
        assertEquals(AgentOverlayStatus.RunFailed, currentState.status)
        assertEquals("执行失败", currentState.detailText)
        assertNull("pendingUserInput must be cleared", currentState.pendingUserInput)
    }
}
