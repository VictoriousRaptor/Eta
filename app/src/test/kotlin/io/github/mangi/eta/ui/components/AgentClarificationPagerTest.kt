package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.runtime.AgentUserInputQuestion
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentClarificationPagerTest {

    @Test
    fun singleQuestionPagerStateBehavior() {
        val request = AgentUserInputRequest(
            id = "single-1",
            questions = listOf(
                AgentUserInputQuestion("q1", "你叫什么名字？"),
            ),
        )
        val initial = AgentClarificationPagerState(request = request)
        assertTrue(initial.isSingleQuestion)
        assertEquals(1, initial.totalPages)
        assertEquals(0, initial.currentPageIndex)
        assertFalse(initial.canGoPrevious)
        assertFalse(initial.canGoNext)
        assertTrue(initial.isLastPage)
        assertFalse(initial.canSubmit)
        assertNull(initial.buildFinalAnswer())

        // 填入空白文本，仍不可提交
        val blankAnswer = initial.withAnswer("q1", "   ")
        assertFalse(blankAnswer.canSubmit)
        assertNull(blankAnswer.buildFinalAnswer())

        // 填入有效答案
        val answered = initial.withAnswer("q1", "  小明  ")
        assertTrue(answered.canSubmit)
        val finalAnswer = answered.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("single-1", finalAnswer!!.requestId)
        assertEquals(mapOf("q1" to "小明"), finalAnswer.answers)
    }

    @Test
    fun threeQuestionsNavigationAnswerRetentionAndOrderedSubmission() {
        val request = AgentUserInputRequest(
            id = "multi-3",
            questions = listOf(
                AgentUserInputQuestion("dest", "目的地？", listOf("北京", "上海")),
                AgentUserInputQuestion("date", "出发日期？"),
                AgentUserInputQuestion("budget", "预算范围？"),
            ),
        )
        var state = AgentClarificationPagerState(request = request)
        assertFalse(state.isSingleQuestion)
        assertEquals(3, state.totalPages)
        assertEquals(0, state.currentPageIndex)
        assertEquals("dest", state.currentQuestion?.id)

        // 1. 空白不翻页
        assertFalse(state.isCurrentAnswerValid)
        assertFalse(state.canGoNext)
        state = state.nextPage()
        assertEquals(0, state.currentPageIndex) // 依然停在第 1 题

        state = state.withAnswer("dest", "  ")
        assertFalse(state.isCurrentAnswerValid)
        assertFalse(state.canGoNext)
        state = state.nextPage()
        assertEquals(0, state.currentPageIndex)

        // 2. 填写第 1 题并翻到第 2 题
        state = state.withAnswer("dest", "北京")
        assertTrue(state.isCurrentAnswerValid)
        assertTrue(state.canGoNext)
        state = state.nextPage()
        assertEquals(1, state.currentPageIndex)
        assertEquals("date", state.currentQuestion?.id)
        assertTrue(state.canGoPrevious)
        assertFalse(state.isLastPage)

        // 3. 在第 2 题填写答案，然后返回第 1 题确认并修改答案
        state = state.withAnswer("date", "2026-10-10")
        state = state.previousPage()
        assertEquals(0, state.currentPageIndex)
        assertEquals("北京", state.currentAnswer) // 原答案保存完好

        // 修改第 1 题答案为上海
        state = state.withAnswer("dest", "上海")
        assertEquals("上海", state.currentAnswer)

        // 再次前进到第 2 题，确认第 2 题答案依然保存
        state = state.nextPage()
        assertEquals(1, state.currentPageIndex)
        assertEquals("2026-10-10", state.currentAnswer)

        // 4. 前进到第 3 题（最后一题）
        assertTrue(state.canGoNext)
        state = state.nextPage()
        assertEquals(2, state.currentPageIndex)
        assertEquals("budget", state.currentQuestion?.id)
        assertTrue(state.isLastPage)
        assertFalse(state.canGoNext)

        // 5. 不完整不能提交
        assertFalse(state.canSubmit)
        assertNull(state.buildFinalAnswer())

        // 6. 填写第 3 题，最后统一有序提交
        state = state.withAnswer("budget", " 5000元 ")
        assertTrue(state.canSubmit)
        val finalAnswer = state.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("multi-3", finalAnswer!!.requestId)

        // 验证各题目答案已 trim，且键值顺序严格对应 request.questions 的定义顺序
        val keys = finalAnswer.answers.keys.toList()
        assertEquals(listOf("dest", "date", "budget"), keys)
        assertEquals("上海", finalAnswer.answers["dest"])
        assertEquals("2026-10-10", finalAnswer.answers["date"])
        assertEquals("5000元", finalAnswer.answers["budget"])
    }

    @Test
    fun multiSelectToggleAndEmptyAnswerGating() {
        val request = AgentUserInputRequest(
            id = "multi-toggle-1",
            questions = listOf(
                AgentUserInputQuestion("pref", "偏好设置？", listOf("选项A", "选项B", "选项C"), multiSelect = true),
            ),
        )
        var state = AgentClarificationPagerState(request = request)
        // 1. 初始状态：无选项且无自由文本，门禁生效
        assertFalse(state.isCurrentAnswerValid)
        assertFalse(state.canSubmit)
        assertNull(state.buildFinalAnswer())

        // 2. 选中选项A：已回答，可以提交
        state = state.toggleOption("pref", "选项A")
        assertTrue(state.isCurrentAnswerValid)
        assertTrue(state.canSubmit)
        assertEquals(setOf("选项A"), state.selectedOptionsFor("pref"))
        var finalAnswer = state.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("选项A", finalAnswer!!.answers["pref"])

        // 3. 再次点击选项A：取消选择，恢复不可提交
        state = state.toggleOption("pref", "选项A")
        assertFalse(state.isCurrentAnswerValid)
        assertFalse(state.canSubmit)
        assertTrue(state.selectedOptionsFor("pref").isEmpty())
        assertNull(state.buildFinalAnswer())

        // 4. 输入空白自由文本：仍然不可提交
        state = state.withAnswer("pref", "   ")
        assertFalse(state.isCurrentAnswerValid)
        assertFalse(state.canSubmit)
        assertNull(state.buildFinalAnswer())

        // 5. 仅输入有效自由文本（无选项）：可以提交
        state = state.withAnswer("pref", "自由补充")
        assertTrue(state.isCurrentAnswerValid)
        assertTrue(state.canSubmit)
        finalAnswer = state.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("自由补充", finalAnswer!!.answers["pref"])

        // 6. 同时勾选选项并保留自由文本：选项与自由文本合并
        state = state.toggleOption("pref", "选项B")
        assertTrue(state.isCurrentAnswerValid)
        assertTrue(state.canSubmit)
        finalAnswer = state.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("选项B\n自由补充", finalAnswer!!.answers["pref"])
    }

    @Test
    fun multiSelectAnswerOrderingFollowsQuestionDefinition() {
        val request = AgentUserInputRequest(
            id = "multi-order-1",
            questions = listOf(
                AgentUserInputQuestion("features", "需要哪些功能？", listOf("定位", "通知", "蓝牙", "相册"), multiSelect = true),
            ),
        )
        var state = AgentClarificationPagerState(request = request)

        // 乱序点击：先选相册，再选通知，再选定位
        state = state.toggleOption("features", "相册")
        state = state.toggleOption("features", "通知")
        state = state.toggleOption("features", "定位")

        // 答案必须按照 question.options 定义顺序（定位 -> 通知 -> 相册）排列
        var finalAnswer = state.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("定位\n通知\n相册", finalAnswer!!.answers["features"])

        // 补充自由文本，接在下一行
        state = state.withAnswer("features", "麦克风权限待定")
        finalAnswer = state.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("定位\n通知\n相册\n麦克风权限待定", finalAnswer!!.answers["features"])
    }

    @Test
    fun mixedQuestionsNavigationAnswerRetentionAndSubmission() {
        val request = AgentUserInputRequest(
            id = "mixed-2",
            questions = listOf(
                AgentUserInputQuestion("tags", "标签（可多选）？", listOf("开源", "安全", "高效"), multiSelect = true),
                AgentUserInputQuestion("env", "部署环境？", listOf("生产", "预发"), multiSelect = false),
            ),
        )
        var state = AgentClarificationPagerState(request = request)
        assertEquals(2, state.totalPages)
        assertEquals(0, state.currentPageIndex)
        assertFalse(state.canGoNext)

        // 第 1 题（多选）：选择 "高效" 和 "开源"，并添加自由文本 "私有化部署"
        state = state.toggleOption("tags", "高效")
        state = state.toggleOption("tags", "开源")
        state = state.withAnswer("tags", "私有化部署")
        assertTrue(state.canGoNext)

        // 前进到第 2 题
        state = state.nextPage()
        assertEquals(1, state.currentPageIndex)
        assertEquals("env", state.currentQuestion?.id)
        assertFalse(state.isCurrentAnswerValid)
        assertFalse(state.canSubmit)

        // 第 2 题（单选）：选择 "生产"
        state = state.withAnswer("env", "生产")
        assertTrue(state.isCurrentAnswerValid)
        assertTrue(state.canSubmit)

        // 返回第 1 题，验证状态保留
        state = state.previousPage()
        assertEquals(0, state.currentPageIndex)
        assertEquals(setOf("高效", "开源"), state.selectedOptionsFor("tags"))
        assertEquals("私有化部署", state.currentAnswer)

        // 修改第 1 题：取消 "高效"，添加 "安全"，修改自由文本
        state = state.toggleOption("tags", "高效")
        state = state.toggleOption("tags", "安全")
        state = state.withAnswer("tags", "本地优先")

        // 再次前进到第 2 题，验证第 2 题答案完好
        state = state.nextPage()
        assertEquals(1, state.currentPageIndex)
        assertEquals("生产", state.currentAnswer)
        assertTrue(state.canSubmit)

        // 最终提交
        val finalAnswer = state.buildFinalAnswer()
        assertNotNull(finalAnswer)
        assertEquals("mixed-2", finalAnswer!!.requestId)
        assertEquals(listOf("tags", "env"), finalAnswer.answers.keys.toList())
        assertEquals("开源\n安全\n本地优先", finalAnswer.answers["tags"])
        assertEquals("生产", finalAnswer.answers["env"])
    }
}
