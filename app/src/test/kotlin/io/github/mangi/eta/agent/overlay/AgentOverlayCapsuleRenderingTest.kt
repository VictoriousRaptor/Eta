package io.github.mangi.eta.agent.overlay

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentUserInputAnswer
import io.github.mangi.eta.agent.runtime.AgentUserInputQuestion
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest
import io.github.mangi.eta.config.Prefs
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentOverlayCapsuleRenderingTest {

    private object testPolicy : InfiniteAnimationPolicy {
        override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R {
            throw CancellationException("Infinite animation disabled for functional Robolectric test")
        }
    }

    private object testMotionDurationScale : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    @Test
    fun capsuleShowsStatusAndTogglesExpansion() {
        val expandedState = mutableStateOf(false)
        var stopClicked = false

        withContent({
            AgentOverlayCapsule(
                state = AgentOverlayState(
                    phase = AgentOverlayPhase.RUNNING,
                    status = AgentOverlayStatus.Reasoning,
                    thought = "thinking...",
                ),
                expanded = expandedState.value,
                onToggleExpanded = { expandedState.value = !expandedState.value },
                onCollapsedSettled = {},
                onPause = {},
                onResume = {},
                onStop = { stopClicked = true },
                onSupplementModeChange = {},
                onSupplement = { true },
                onAnswerUserInput = { true },
            )
        }) { view, activity, pump ->
            val statusText = activity.getString(R.string.overlay_reasoning)
            val expandDesc = activity.getString(R.string.overlay_capsule_toggle, statusText)

            val toggleNode = findNodeByContentDescription(view, expandDesc)
            assertNotNull("收起状态应有展开描述", toggleNode)
            clickNode(toggleNode!!)

            pump()
            assertTrue("点击后应展开", expandedState.value)

            val stopDesc = activity.getString(R.string.action_stop)
            val stopNode = findNodeByContentDescription(view, stopDesc)
            assertNotNull("展开后应有停止按钮", stopNode)
            assertTrue("停止按钮宽度非零", stopNode!!.boundsInRoot.width > 0f)
            assertTrue("停止按钮高度非零", stopNode.boundsInRoot.height > 0f)
            assertTrue("停止按钮在272x480窗口内", stopNode.boundsInRoot.bottom <= 480f)

            maybeCaptureQaPng(view, "controls")

            clickNode(stopNode)
            assertTrue("点击停止回调已触发", stopClicked)
        }
    }

    @Test
    fun pendingClarificationMultiPageAnswerAndIdReset() {
        val q1 = AgentUserInputQuestion(
            id = "q1",
            question = "请选择地点",
            options = listOf("广州", "上海"),
            multiSelect = true,
        )
        val q2 = AgentUserInputQuestion(
            id = "q2",
            question = "备注信息",
            options = emptyList(),
            multiSelect = false,
        )
        val req1 = AgentUserInputRequest(id = "req-1", questions = listOf(q1, q2))

        val currentRequest = mutableStateOf(req1)
        var lastAnswer: AgentUserInputAnswer? = null
        var answerAcceptResult = false
        var pauseClicked = false
        var stopClicked = false

        withContent({
            val req = currentRequest.value
            AgentOverlayCapsule(
                state = AgentOverlayState(
                    phase = AgentOverlayPhase.RUNNING,
                    status = AgentOverlayStatus.WaitingForUser,
                    pendingUserInput = req,
                ),
                expanded = true,
                onToggleExpanded = {},
                onCollapsedSettled = {},
                onPause = { pauseClicked = true },
                onResume = {},
                onStop = { stopClicked = true },
                onSupplementModeChange = {},
                onSupplement = { true },
                onAnswerUserInput = { answer ->
                    lastAnswer = answer
                    answerAcceptResult
                },
            )
        }) { view, activity, pump ->
            // 验证按钮存在与 bounds 在 272x480 内
            val pauseDesc = activity.getString(R.string.overlay_pause)
            val pauseNode = findNodeByContentDescription(view, pauseDesc)
            assertNotNull("应有暂停按钮", pauseNode)
            assertTrue("暂停按钮宽度非零", pauseNode!!.boundsInRoot.width > 0f)
            assertTrue("暂停按钮高度非零", pauseNode.boundsInRoot.height > 0f)
            assertTrue("暂停按钮在272x480窗口内", pauseNode.boundsInRoot.bottom <= 480f)

            val stopDesc = activity.getString(R.string.action_stop)
            val stopNode = findNodeByContentDescription(view, stopDesc)
            assertNotNull("应有停止按钮", stopNode)
            assertTrue("停止按钮宽度非零", stopNode!!.boundsInRoot.width > 0f)
            assertTrue("停止按钮高度非零", stopNode.boundsInRoot.height > 0f)
            assertTrue("停止按钮在272x480窗口内", stopNode.boundsInRoot.bottom <= 480f)

            clickNode(pauseNode)
            assertTrue("暂停回调触发", pauseClicked)
            clickNode(stopNode)
            assertTrue("停止回调触发", stopClicked)

            // 第一页多选选项 + 自由输入
            val optNode = findNodeByText(view, "广州")
            assertNotNull("第一页应有选项广州", optNode)
            clickNode(optNode!!)
            pump()

            val editNode1 = findEditableTextNode(view)
            assertNotNull("第一页应有输入框", editNode1)
            setTextNode(editNode1!!, "商务出行")
            pump()

            // 点击下一页
            val nextText = activity.getString(R.string.clarify_next)
            val nextNode = findNodeByText(view, nextText)
            assertNotNull("应有下一页按钮", nextNode)
            clickNode(nextNode!!)
            pump()

            // 第二页题目出现
            assertNotNull("第二页应有题目备注信息", findNodeByText(view, "备注信息"))

            // 点击上一页确认前页答案保留
            val prevText = activity.getString(R.string.clarify_previous)
            val prevNode = findNodeByText(view, prevText)
            assertNotNull("第二页应有上一页按钮", prevNode)
            clickNode(prevNode!!)
            pump()

            val editNode1Back = findEditableTextNode(view)
            assertEquals("上一页草稿应保留", "商务出行", editNode1Back?.config?.getOrNull(SemanticsProperties.EditableText)?.text)

            // 返回第二页
            val nextNodeAgain = findNodeByText(view, nextText)
            clickNode(nextNodeAgain!!)
            pump()

            // 第二页输入
            val editNode2 = findEditableTextNode(view)
            assertNotNull("第二页应有输入框", editNode2)
            setTextNode(editNode2!!, "需要发票")
            pump()

            // 提交按钮 bounds 检查
            val submitText = activity.getString(R.string.clarify_submit)
            val submitNode = findNodeByText(view, submitText)
            assertNotNull("最后页应有提交按钮", submitNode)
            assertTrue("提交按钮宽度非零", submitNode!!.boundsInRoot.width > 0f)
            assertTrue("提交按钮高度非零", submitNode.boundsInRoot.height > 0f)
            assertTrue("提交按钮在272x480窗口内", submitNode.boundsInRoot.bottom <= 480f)

            maybeCaptureQaPng(view, "clarify")

            // 第一次提交：拒绝（返回 false）
            answerAcceptResult = false
            clickNode(submitNode)
            pump()
            assertNotNull("应收到答案回调", lastAnswer)
            assertEquals("req-1", lastAnswer!!.requestId)
            assertEquals("广州\n商务出行", lastAnswer!!.answers["q1"])
            assertEquals("需要发票", lastAnswer!!.answers["q2"])

            // 确认拒绝后答案还在
            val editNodeAfterReject = findEditableTextNode(view)
            assertEquals("拒绝后答案仍保留", "需要发票", editNodeAfterReject?.config?.getOrNull(SemanticsProperties.EditableText)?.text)

            // 第二次提交：接受（返回 true）
            answerAcceptResult = true
            val submitNodeRetry = findNodeByText(view, submitText)
            assertNotNull("应仍可点击提交", submitNodeRetry)
            clickNode(submitNodeRetry!!)
            pump()
            assertEquals("req-1", lastAnswer!!.requestId)

            // request.id 改变后答案/页码不泄漏
            val req2 = AgentUserInputRequest(
                id = "req-2",
                questions = listOf(q1, q2),
            )
            currentRequest.value = req2
            pump()

            // 确认重置回第一页
            assertNotNull("id改变后重置到第一页", findNodeByText(view, "请选择地点"))
            val editNodeReset = findEditableTextNode(view)
            val draft = editNodeReset?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            assertEquals("id改变后草稿重置", "", draft)
        }
    }

    @Test
    fun supplementModeDraftRetentionRetryAndSteerSwitchToggle() {
        val expandedState = mutableStateOf(true)
        val supplementCalls = mutableListOf<String>()
        var supplementResult = false

        withContent({
            AgentOverlayCapsule(
                state = AgentOverlayState(
                    phase = AgentOverlayPhase.RUNNING,
                    status = AgentOverlayStatus.Reasoning,
                ),
                expanded = expandedState.value,
                onToggleExpanded = {},
                onCollapsedSettled = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onSupplementModeChange = {},
                onSupplement = { text ->
                    supplementCalls.add(text)
                    supplementResult
                },
                onAnswerUserInput = { true },
            )
        }) { view, activity, pump ->
            // 开关已在 withContent 预设为 true，此时应有补充入口
            val supplementDesc = activity.getString(R.string.overlay_supplement)
            val supplementNode = findNodeByContentDescription(view, supplementDesc)
            assertNotNull("开关为true时应有补充入口", supplementNode)

            // 点击补充入口进入补充模式
            clickNode(supplementNode!!)
            pump()

            // 查找补充输入框并输入草稿
            val editNode = findEditableTextNode(view)
            assertNotNull("进入补充模式应有输入框", editNode)
            setTextNode(editNode!!, "需要补充说明")
            pump()

            val sendText = activity.getString(R.string.overlay_send)
            val sendNode = findNodeByText(view, sendText)
            assertNotNull("应有发送按钮", sendNode)

            // 第一次提交：返回 false
            supplementResult = false
            clickNode(sendNode!!)
            pump()
            assertEquals("应调用一次onSupplement", 1, supplementCalls.size)
            assertEquals("需要补充说明", supplementCalls[0])

            // 确认草稿未清除，仍可找到输入框且内容正确
            val editNodeAfterFalse = findEditableTextNode(view)
            assertNotNull("第一次false后仍应在补充模式", editNodeAfterFalse)
            assertEquals(
                "第一次false后草稿未被清除",
                "需要补充说明",
                editNodeAfterFalse?.config?.getOrNull(SemanticsProperties.EditableText)?.text,
            )

            // 输入期间关闭开关：不得调用onSupplement且保留草稿
            Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, false)?.commit()
            pump()

            val sendNodeAfterDisabled = findNodeByText(view, sendText)
            if (sendNodeAfterDisabled != null) {
                clickNode(sendNodeAfterDisabled)
                pump()
            }
            assertEquals("开关关闭期间点击不应新增onSupplement调用", 1, supplementCalls.size)

            val editNodeAfterDisabled = findEditableTextNode(view)
            assertNotNull("开关关闭后草稿仍存在", editNodeAfterDisabled)
            assertEquals(
                "开关关闭后草稿仍保留",
                "需要补充说明",
                editNodeAfterDisabled?.config?.getOrNull(SemanticsProperties.EditableText)?.text,
            )

            // 恢复开关
            Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, true)?.commit()
            pump()

            // 第二次提交：返回 true
            supplementResult = true
            val sendNodeAgain = findNodeByText(view, sendText)
            assertNotNull("恢复开关后应有发送按钮", sendNodeAgain)
            clickNode(sendNodeAgain!!)
            pump()
            assertEquals("应触发第二次onSupplement调用", 2, supplementCalls.size)
            assertEquals("需要补充说明", supplementCalls[1])

            // 第二次为 true，退出补充模式，输入框不再存在
            assertNull("返回true后应退出补充模式", findEditableTextNode(view))

            // 开关为 false 时隐藏补充入口
            Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, false)?.commit()
            pump()
            val supplementNodeDisabled = findNodeByContentDescription(view, supplementDesc)
            assertNull("开关为false时应隐藏补充入口", supplementNodeDisabled)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun pendingClarificationControlsFitReducedHeight() {
        val request = AgentUserInputRequest(
            id = "small-request",
            questions = listOf(AgentUserInputQuestion(id = "q", question = "目的地？")),
        )
        var receivedAnswer: AgentUserInputAnswer? = null
        var pauseClicked = false
        var stopClicked = false

        withContent(
            content = {
                AgentOverlayCapsule(
                    state = AgentOverlayState(
                        phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.WaitingForUser,
                        pendingUserInput = request,
                    ),
                    expanded = true,
                    onToggleExpanded = {},
                    onCollapsedSettled = {},
                    onPause = { pauseClicked = true },
                    onResume = {},
                    onStop = { stopClicked = true },
                    onSupplementModeChange = {},
                    onSupplement = { true },
                    onAnswerUserInput = { answer ->
                        receivedAnswer = answer
                        false
                    },
                )
            },
            windowHeight = 240,
        ) { view, activity, pump ->
            val questionNodeBefore = findNodeByText(view, "目的地？")
            assertNotNull("输入前题目应存在", questionNodeBefore)
            val qBoundsBefore = questionNodeBefore!!.boundsInRoot
            assertTrue("输入前题目宽度应大于0: $qBoundsBefore", qBoundsBefore.width > 0f)
            assertTrue("输入前题目高度应>=20f: $qBoundsBefore", qBoundsBefore.height >= 20f)
            assertTrue("输入前题目left应>=0: $qBoundsBefore", qBoundsBefore.left >= 0f)
            assertTrue("输入前题目right应<=272: $qBoundsBefore", qBoundsBefore.right <= 272f)
            assertTrue("输入前题目top应>=0: $qBoundsBefore", qBoundsBefore.top >= 0f)
            assertTrue("输入前题目bottom应<=240: $qBoundsBefore", qBoundsBefore.bottom <= 240f)

            val editNode = findEditableTextNode(view)
            assertNotNull("输入框应存在", editNode)
            val editBoundsBefore = editNode!!.boundsInRoot
            assertTrue("输入前输入框宽度应大于0: $editBoundsBefore", editBoundsBefore.width > 0f)
            assertTrue("输入前输入框高度应>=20f: $editBoundsBefore", editBoundsBefore.height >= 20f)
            assertTrue("输入前输入框left应>=0: $editBoundsBefore", editBoundsBefore.left >= 0f)
            assertTrue("输入前输入框right应<=272: $editBoundsBefore", editBoundsBefore.right <= 272f)
            assertTrue("输入前输入框top应>=0: $editBoundsBefore", editBoundsBefore.top >= 0f)
            assertTrue("输入前输入框bottom应<=240: $editBoundsBefore", editBoundsBefore.bottom <= 240f)

            setTextNode(editNode, "北京")
            pump()

            val questionNodeAfter = findNodeByText(view, "目的地？")
            assertNotNull("输入后题目仍应存在", questionNodeAfter)
            val qBoundsAfter = questionNodeAfter!!.boundsInRoot
            assertTrue("输入后题目宽度应大于0: $qBoundsAfter", qBoundsAfter.width > 0f)
            assertTrue("输入后题目高度应>=20f: $qBoundsAfter", qBoundsAfter.height >= 20f)
            assertTrue("输入后题目left应>=0: $qBoundsAfter", qBoundsAfter.left >= 0f)
            assertTrue("输入后题目right应<=272: $qBoundsAfter", qBoundsAfter.right <= 272f)
            assertTrue("输入后题目top应>=0: $qBoundsAfter", qBoundsAfter.top >= 0f)
            assertTrue("输入后题目bottom应<=240: $qBoundsAfter", qBoundsAfter.bottom <= 240f)

            val editNodeAfter = findEditableTextNode(view)
            assertNotNull("输入后输入框应存在", editNodeAfter)
            val editBoundsAfter = editNodeAfter!!.boundsInRoot
            assertTrue("输入后输入框宽度应大于0: $editBoundsAfter", editBoundsAfter.width > 0f)
            assertTrue("输入后输入框高度应>=20f: $editBoundsAfter", editBoundsAfter.height >= 20f)
            assertTrue("输入后输入框left应>=0: $editBoundsAfter", editBoundsAfter.left >= 0f)
            assertTrue("输入后输入框right应<=272: $editBoundsAfter", editBoundsAfter.right <= 272f)
            assertTrue("输入后输入框top应>=0: $editBoundsAfter", editBoundsAfter.top >= 0f)
            assertTrue("输入后输入框bottom应<=240: $editBoundsAfter", editBoundsAfter.bottom <= 240f)

            val submitText = activity.getString(R.string.clarify_submit)
            val submitNode = findNodeByText(view, submitText)
            assertNotNull("提交按钮应存在", submitNode)

            val pauseDesc = activity.getString(R.string.overlay_pause)
            val pauseNode = findNodeByContentDescription(view, pauseDesc)
            assertNotNull("暂停按钮应存在", pauseNode)

            val stopDesc = activity.getString(R.string.action_stop)
            val stopNode = findNodeByContentDescription(view, stopDesc)
            assertNotNull("停止按钮应存在", stopNode)

            for ((name, node) in listOf("submit" to submitNode!!, "pause" to pauseNode!!, "stop" to stopNode!!)) {
                val bounds = node.boundsInRoot
                assertTrue("$name 宽度应大于0: $bounds", bounds.width > 0f)
                assertTrue("$name 高度应大于0: $bounds", bounds.height > 0f)
                assertTrue("$name left应>=0: $bounds", bounds.left >= 0f)
                assertTrue("$name right应<=272: $bounds", bounds.right <= 272f)
                assertTrue("$name top应>=0: $bounds", bounds.top >= 0f)
                assertTrue("$name bottom应<=240: $bounds", bounds.bottom <= 240f)
            }

            clickNode(submitNode)
            pump()
            assertNotNull("应收到答案回调", receivedAnswer)
            assertEquals("small-request", receivedAnswer!!.requestId)
            assertEquals("北京", receivedAnswer!!.answers["q"])

            val editNodeAfterReject = findEditableTextNode(view)
            assertNotNull("拒绝后输入框仍应存在", editNodeAfterReject)
            assertEquals("北京", editNodeAfterReject?.config?.getOrNull(SemanticsProperties.EditableText)?.text)

            setTextNode(editNodeAfterReject!!, "广州")
            pump()
            val editNodeModified = findEditableTextNode(view)
            assertNotNull("修改后输入框应存在", editNodeModified)
            assertEquals("草稿能改成广州", "广州", editNodeModified?.config?.getOrNull(SemanticsProperties.EditableText)?.text)
            val modBounds = editNodeModified!!.boundsInRoot
            assertTrue("修改后输入框高度应>=20f: $modBounds", modBounds.height >= 20f)
            assertTrue("修改后输入框宽度应大于0: $modBounds", modBounds.width > 0f)
            assertTrue("修改后输入框left应>=0: $modBounds", modBounds.left >= 0f)
            assertTrue("修改后输入框right应<=272: $modBounds", modBounds.right <= 272f)
            assertTrue("修改后输入框top应>=0: $modBounds", modBounds.top >= 0f)
            assertTrue("修改后输入框bottom应<=240: $modBounds", modBounds.bottom <= 240f)

            clickNode(pauseNode)
            assertTrue("暂停回调触发", pauseClicked)

            clickNode(stopNode)
            assertTrue("停止回调触发", stopClicked)

            maybeCaptureQaPng(view, "clarify-small-height")
        }
    }

    @Test
    fun acceptedSupplementCanBeSentOnlyOnceBeforeExitDelayFinishes() {
        val expandedState = mutableStateOf(false)
        val supplementCalls = mutableListOf<String>()

        withContent({
            AgentOverlayCapsule(
                state = AgentOverlayState(
                    phase = AgentOverlayPhase.RUNNING,
                    status = AgentOverlayStatus.Reasoning,
                ),
                expanded = expandedState.value,
                onToggleExpanded = { expandedState.value = !expandedState.value },
                onCollapsedSettled = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onSupplementModeChange = {},
                onSupplement = { text ->
                    supplementCalls.add(text)
                    true
                },
                onAnswerUserInput = { true },
            )
        }) { view, activity, pump ->
            val statusText = activity.getString(R.string.overlay_reasoning)
            val expandDesc = activity.getString(R.string.overlay_capsule_toggle, statusText)
            val toggleNode = findNodeByContentDescription(view, expandDesc)
            assertNotNull("收起状态应有展开描述", toggleNode)
            clickNode(toggleNode!!)
            pump()
            assertTrue("点击后应展开", expandedState.value)

            val supplementDesc = activity.getString(R.string.overlay_supplement)
            val supplementNode = findNodeByContentDescription(view, supplementDesc)
            assertNotNull("展开后应有补充入口", supplementNode)
            clickNode(supplementNode!!)
            pump()

            val editNode = findEditableTextNode(view)
            assertNotNull("进入补充模式应有输入框", editNode)
            setTextNode(editNode!!, "补充建议")
            pump()

            val sendText = activity.getString(R.string.overlay_send)
            val sendNode = findNodeByText(view, sendText)
            assertNotNull("应有发送按钮", sendNode)

            clickNode(sendNode!!)
            clickNode(sendNode)

            assertEquals("80ms延时结束前多次点击发送只应触发一次回调", 1, supplementCalls.size)
            assertEquals("补充建议", supplementCalls[0])

            pump()
            assertNull("延时结束后应退出补充模式", findEditableTextNode(view))
        }
    }

    @Test
    fun newTextTypedDuringSupplementExitDelaySurvives() {
        val expandedState = mutableStateOf(true)
        val supplementCalls = mutableListOf<String>()

        withContent({
            AgentOverlayCapsule(
                state = AgentOverlayState(
                    phase = AgentOverlayPhase.RUNNING,
                    status = AgentOverlayStatus.Reasoning,
                ),
                expanded = expandedState.value,
                onToggleExpanded = {},
                onCollapsedSettled = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onSupplementModeChange = {},
                onSupplement = { text ->
                    supplementCalls.add(text)
                    true
                },
                onAnswerUserInput = { true },
            )
        }) { view, activity, pump ->
            val supplementDesc = activity.getString(R.string.overlay_supplement)
            val supplementNode = findNodeByContentDescription(view, supplementDesc)
            assertNotNull("展开状态应有补充入口", supplementNode)
            clickNode(supplementNode!!)
            pump()

            val editNode = findEditableTextNode(view)
            assertNotNull("进入补充模式应有输入框", editNode)
            setTextNode(editNode!!, "first draft")
            pump()

            val sendText = activity.getString(R.string.overlay_send)
            val sendNode = findNodeByText(view, sendText)
            assertNotNull("应有发送按钮", sendNode)

            val editNodeBeforeSend = findEditableTextNode(view)
            assertNotNull("发送前应有输入框", editNodeBeforeSend)

            clickNode(sendNode!!)
            setTextNode(editNodeBeforeSend!!, "next draft")

            pump()
            assertNull("延时结束后应退出补充模式", findEditableTextNode(view))

            val supplementNodeReopen = findNodeByContentDescription(view, supplementDesc)
            assertNotNull("退出后应可重新打开补充入口", supplementNodeReopen)
            clickNode(supplementNodeReopen!!)
            pump()

            val editNodeReopened = findEditableTextNode(view)
            assertNotNull("重新打开后应有输入框", editNodeReopened)
            assertEquals(
                "延时期间输入的草稿在重新打开后应保留",
                "next draft",
                editNodeReopened?.config?.getOrNull(SemanticsProperties.EditableText)?.text,
            )
            assertEquals("只应接受首次发送的草稿", listOf("first draft"), supplementCalls)
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun getSemanticsNodes(view: ComposeView): List<SemanticsNode> {
        val ownerView = view.getChildAt(0) ?: return emptyList()
        val owner = ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }

    private fun findNodeByText(view: ComposeView, text: String): SemanticsNode? {
        return getSemanticsNodes(view).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
    }

    private fun findNodeByContentDescription(view: ComposeView, desc: String): SemanticsNode? {
        return getSemanticsNodes(view).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == desc } == true
        }
    }

    private fun findEditableTextNode(view: ComposeView): SemanticsNode? {
        return getSemanticsNodes(view).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.EditableText) != null
        }
    }

    private fun clickNode(node: SemanticsNode) {
        var current: SemanticsNode? = node
        var action: (() -> Boolean)? = null
        while (current != null) {
            action = current.config.getOrNull(SemanticsActions.OnClick)?.action
            if (action != null) break
            current = current.parent
        }
        assertNotNull("Node or its ancestors should have OnClick action: $node", action)
        action!!.invoke()
    }

    private fun setTextNode(node: SemanticsNode, text: String) {
        val action = node.config.getOrNull(SemanticsActions.SetText)?.action
        assertNotNull("Node should have SetText action: $node", action)
        action!!.invoke(AnnotatedString(text))
    }

    private fun maybeCaptureQaPng(view: ComposeView, name: String) {
        val dirPath = System.getenv("ETA_CAPSULE_QA_DIR") ?: return
        if (dirPath.isBlank()) return
        val dir = File(dirPath)
        if (!dir.exists()) {
            val created = dir.mkdirs()
            assertTrue("Failed to create QA screenshot directory: ${dir.absolutePath}", created || dir.exists())
        }
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        assertNotNull("Bitmap.createBitmap returned null", bitmap)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { out ->
            val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            assertTrue("Bitmap compression failed for $name.png", compressed)
        }
        assertTrue("Saved QA screenshot is empty or missing: ${file.absolutePath}", file.exists() && file.length() > 0L)
    }

    private fun printSemanticsDiagnostic(nodes: List<SemanticsNode>) {
        println("=== Semantics Diagnostic (${nodes.size} nodes) ===")
        for ((index, node) in nodes.withIndex()) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text
            val hasClick = node.config.getOrNull(SemanticsActions.OnClick) != null
            val bounds = node.boundsInRoot
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || !editable.isNullOrEmpty() || hasClick) {
                println("  #$index bounds=$bounds text='$text' desc='$desc' editable='$editable' hasClick=$hasClick")
            }
        }
    }

    private fun withContent(
        content: @Composable () -> Unit,
        windowHeight: Int = 480,
        verify: (ComposeView, ComponentActivity, () -> Unit) -> Unit,
    ) {
        val testDispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher("capsule-test").immediate
        val frameClock = BroadcastFrameClock()
        var frameNanos = 0L

        WindowRecomposerPolicy.withFactory(WindowRecomposerFactory { root ->
            root.createLifecycleAwareWindowRecomposer(coroutineContext = testDispatcher + testPolicy + frameClock + testMotionDurationScale)
        }) {
            val originalFrameDelay = ShadowChoreographer.getFrameDelay()
            val originalPaused = ShadowChoreographer.isPaused()
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            ShadowChoreographer.setPaused(false)

            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            Prefs.initLocal(activity)
            val originalSteer = Prefs.isEnabled(Prefs.Keys.AGENT_STEER_ENABLED)
            Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, true)?.commit()

            val view = ComposeView(activity)
            val frameBitmap = Bitmap.createBitmap(272, windowHeight, Bitmap.Config.ARGB_8888)
            val frameCanvas = Canvas(frameBitmap)
            fun layoutContent() {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(272, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(windowHeight, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 272, windowHeight)
            }

            try {
                activity.setContentView(view)
                view.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalSquircleEnabled provides false,
                    ) {
                        MiuixTheme {
                            content()
                        }
                    }
                }
                layoutContent()
                Snapshot.sendApplyNotifications()
                ShadowLooper.idleMainLooper()

                val pump = {
                    repeat(80) {
                        Snapshot.sendApplyNotifications()
                        ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                        Snapshot.sendApplyNotifications()
                        ShadowLooper.idleMainLooper()
                        frameNanos += 16_000_000L
                        frameClock.sendFrame(frameNanos)
                        ShadowLooper.idleMainLooper()
                        Snapshot.sendApplyNotifications()
                        ShadowLooper.idleMainLooper()
                        layoutContent()
                    }
                    Snapshot.sendApplyNotifications()
                    ShadowLooper.idleMainLooper()
                    layoutContent()
                    frameBitmap.eraseColor(Color.TRANSPARENT)
                    view.draw(frameCanvas)
                }
                pump()
                printSemanticsDiagnostic(getSemanticsNodes(view))
                maybeCaptureQaPng(view, "fixture-initial")
                verify(view, activity, pump)
            } finally {
                Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_STEER_ENABLED, originalSteer)?.commit()
                view.disposeComposition()
                controller.pause().stop().destroy()
                ShadowChoreographer.setFrameDelay(originalFrameDelay)
                ShadowChoreographer.setPaused(originalPaused)
                frameBitmap.recycle()
            }
        }
    }
}
