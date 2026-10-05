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
}
