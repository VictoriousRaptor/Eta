package io.github.mangi.eta.agent.runtime

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.MutableState
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.ui.app.EtaUiVisibility
import io.github.mangi.eta.ui.app.EtaUiVisibilityTracker
import org.junit.After
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AgentRuntimeOverlayLifecycleTest {

    private class TestActivity : Activity()

    private lateinit var tracker: EtaUiVisibilityTracker
    private lateinit var controller: ServiceController<AgentRuntimeService>
    private lateinit var service: AgentRuntimeService

    @Before
    fun setUp() {
        tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 0L,
            isTrackedActivity = { it is TestActivity },
        )
        EtaUiVisibility.setTrackerForTest(tracker)
        controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        EtaUiVisibility.setTrackerForTest(null)
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
        @Suppress("UNCHECKED_CAST")
        val stateState = stateField.get(service) as MutableState<AgentOverlayState>
        stateState.value = AgentOverlayState(
            phase = AgentOverlayPhase.FINISHED,
            status = AgentOverlayStatus.ResultReady,
        )

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
        @Suppress("UNCHECKED_CAST")
        val stateState = stateField.get(service) as MutableState<AgentOverlayState>
        stateState.value = AgentOverlayState(
            phase = AgentOverlayPhase.FAILED,
            status = AgentOverlayStatus.RunFailed,
        )

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
}
