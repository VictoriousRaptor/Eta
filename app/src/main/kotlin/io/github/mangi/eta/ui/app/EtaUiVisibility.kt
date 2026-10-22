package io.github.mangi.eta.ui.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.ui.MainActivity
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet

fun interface EtaUiVisibilityListener {
    fun onVisibilityChanged(isVisible: Boolean)
}

/**
 * 跟踪 Eta 主界面的可见性（通过 Application.ActivityLifecycleCallbacks 跟踪 started/stopped）。
 *
 * - 使用 started/stopped 状态判断，避免系统浮层取得焦点导致 resumed 切换；
 * - 针对配置重建（如横竖屏旋转等），在最后一个 Activity 停止后加入主线程防抖延迟确认（150-250ms），避免短暂生命周期空隙误判 App 退到后台；
 * - 纯主线程操作与订阅，移除监听防泄漏；支持注入 Activity predicate 方便单元测试。
 */
class EtaUiVisibilityTracker(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val stopDebounceMs: Long = 200L,
    private val isTrackedActivity: (Activity) -> Boolean = { it is MainActivity },
) : Application.ActivityLifecycleCallbacks {

    private val startedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val listeners = CopyOnWriteArraySet<EtaUiVisibilityListener>()
    private var pendingStopRunnable: Runnable? = null

    @Volatile
    var isVisible: Boolean = false
        private set

    fun addListener(listener: EtaUiVisibilityListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: EtaUiVisibilityListener) {
        listeners.remove(listener)
    }

    override fun onActivityStarted(activity: Activity) {
        if (!isTrackedActivity(activity)) return
        pendingStopRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingStopRunnable = null
        }
        startedActivities.add(activity)
        updateVisibility(true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (!isTrackedActivity(activity)) return
        startedActivities.remove(activity)
        if (startedActivities.isEmpty()) {
            pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                pendingStopRunnable = null
                if (startedActivities.isEmpty()) {
                    updateVisibility(false)
                }
            }
            pendingStopRunnable = runnable
            if (stopDebounceMs > 0) {
                mainHandler.postDelayed(runnable, stopDebounceMs)
            } else {
                runnable.run()
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (!isTrackedActivity(activity)) return
        startedActivities.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private fun updateVisibility(nextVisible: Boolean) {
        if (isVisible == nextVisible) return
        isVisible = nextVisible
        for (listener in listeners) {
            listener.onVisibilityChanged(nextVisible)
        }
    }

    fun resetForTest(visible: Boolean = false) {
        pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStopRunnable = null
        startedActivities.clear()
        isVisible = visible
        listeners.clear()
    }
}

/**
 * 生产环境全局单例包装。
 */
object EtaUiVisibility {
    @Volatile
    private var trackerInstance: EtaUiVisibilityTracker? = null

    val isVisible: Boolean
        get() = trackerInstance?.isVisible ?: false

    fun init(application: Application, tracker: EtaUiVisibilityTracker = EtaUiVisibilityTracker()) {
        if (trackerInstance == null) {
            application.registerActivityLifecycleCallbacks(tracker)
            trackerInstance = tracker
        }
    }

    fun addListener(listener: EtaUiVisibilityListener) {
        trackerInstance?.addListener(listener)
    }

    fun removeListener(listener: EtaUiVisibilityListener) {
        trackerInstance?.removeListener(listener)
    }

    internal fun setTrackerForTest(tracker: EtaUiVisibilityTracker?) {
        trackerInstance = tracker
    }
}
