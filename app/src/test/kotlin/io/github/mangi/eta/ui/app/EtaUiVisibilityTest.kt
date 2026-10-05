package io.github.mangi.eta.ui.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EtaUiVisibilityTest {

    private class TrackedActivity : Activity()
    private class NonTrackedActivity : Activity()

    @Test
    fun defaultVisibilityIsFalse() {
        val tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 200L,
            isTrackedActivity = { it is TrackedActivity },
        )
        assertFalse(tracker.isVisible)
    }

    @Test
    fun startTrackedActivityMarksVisibleAndDispatches() {
        val tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 200L,
            isTrackedActivity = { it is TrackedActivity },
        )
        val events = mutableListOf<Boolean>()
        tracker.addListener { events.add(it) }

        val activity = TrackedActivity()
        tracker.onActivityStarted(activity)

        assertTrue(tracker.isVisible)
        assertEquals(listOf(true), events)

        // 重复调用不产生冗余回调
        tracker.onActivityStarted(activity)
        assertEquals(listOf(true), events)
    }

    @Test
    fun nonTrackedActivityDoesNotAffectVisibility() {
        val tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 200L,
            isTrackedActivity = { it is TrackedActivity },
        )
        val events = mutableListOf<Boolean>()
        tracker.addListener { events.add(it) }

        val other = NonTrackedActivity()
        tracker.onActivityStarted(other)
        assertFalse(tracker.isVisible)
        assertTrue(events.isEmpty())

        tracker.onActivityStopped(other)
        assertFalse(tracker.isVisible)
    }

    @Test
    fun multiInstanceMaintainsVisibilityUntilAllStopped() {
        val tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 200L,
            isTrackedActivity = { it is TrackedActivity },
        )
        val a1 = TrackedActivity()
        val a2 = TrackedActivity()

        tracker.onActivityStarted(a1)
        tracker.onActivityStarted(a2)
        assertTrue(tracker.isVisible)

        // a1 停止，a2 仍在运行
        tracker.onActivityStopped(a1)
        assertTrue(tracker.isVisible)

        // 推进延迟，因为 a2 仍在运行，依然保持可见
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        assertTrue(tracker.isVisible)
    }

    @Test
    fun configChangeDebounceCancelsHiding() {
        val tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 200L,
            isTrackedActivity = { it is TrackedActivity },
        )
        val events = mutableListOf<Boolean>()
        tracker.addListener { events.add(it) }

        val oldActivity = TrackedActivity()
        tracker.onActivityStarted(oldActivity)
        assertTrue(tracker.isVisible)
        assertEquals(listOf(true), events)

        // 旧 Activity 停止
        tracker.onActivityStopped(oldActivity)
        // 在 200ms 防抖延迟到达前，isVisible 仍保持 true
        assertTrue(tracker.isVisible)

        // 模拟 100ms 后新 Activity 启动（配置重建）
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        val newActivity = TrackedActivity()
        tracker.onActivityStarted(newActivity)

        // 推进超过 200ms
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        // 因为新 Activity 已在延迟内启动，pending 隐藏任务被取消，状态始终为 true
        assertTrue(tracker.isVisible)
        assertEquals(listOf(true), events)
    }

    @Test
    fun allStoppedBecomesFalseAfterDebounce() {
        val tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 200L,
            isTrackedActivity = { it is TrackedActivity },
        )
        val events = mutableListOf<Boolean>()
        tracker.addListener { events.add(it) }

        val activity = TrackedActivity()
        tracker.onActivityStarted(activity)
        assertEquals(listOf(true), events)

        tracker.onActivityStopped(activity)
        // 延迟前仍为 true
        assertTrue(tracker.isVisible)

        // 推进 250ms
        ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
        // 确认不可见
        assertFalse(tracker.isVisible)
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun removeListenerPreventsFurtherCallbacks() {
        val tracker = EtaUiVisibilityTracker(
            stopDebounceMs = 0L,
            isTrackedActivity = { it is TrackedActivity },
        )
        val events = mutableListOf<Boolean>()
        val listener = EtaUiVisibilityListener { events.add(it) }
        tracker.addListener(listener)

        val activity = TrackedActivity()
        tracker.onActivityStarted(activity)
        assertEquals(listOf(true), events)

        tracker.removeListener(listener)
        tracker.onActivityStopped(activity)
        // 监听器已被移除，不再收到 false 通知
        assertEquals(listOf(true), events)
        assertFalse(tracker.isVisible)
    }
}
