package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentUserInputDisplayTest {

    @Test
    fun singleQuestionOnlyDisplaysAnswer() {
        val request = AgentUserInputRequest(
            id = "req-1",
            questions = listOf(
                AgentUserInputQuestion(id = "meal", question = "你吃过饭了吗？", options = listOf("吃过了", "还没")),
            ),
        )
        val answer = AgentUserInputAnswer(
            requestId = "req-1",
            answers = mapOf("meal" to "吃过了"),
        )
        val formatted = AgentUserInputDisplay.formatAnswer(answer, request)
        assertEquals("吃过了", formatted)
    }

    @Test
    fun multipleQuestionsPreserveQuestionOrder() {
        val request = AgentUserInputRequest(
            id = "req-2",
            questions = listOf(
                AgentUserInputQuestion(id = "q1", question = "问题 1"),
                AgentUserInputQuestion(id = "q2", question = "问题 2"),
                AgentUserInputQuestion(id = "q3", question = "问题 3"),
            ),
        )
        // 答案 Map 中乱序存放
        val answer = AgentUserInputAnswer(
            requestId = "req-2",
            answers = mapOf(
                "q3" to "答案 3",
                "q1" to "答案 1",
                "q2" to "答案 2",
            ),
        )
        val formatted = AgentUserInputDisplay.formatAnswer(answer, request)
        // 应严格按照 q1 -> q2 -> q3 顺序输出
        assertEquals("答案 1\n\n答案 2\n\n答案 3", formatted)
    }

    @Test
    fun missingRequestReplayFallbackToAnswerMapOrder() {
        val answer = AgentUserInputAnswer(
            requestId = "req-3",
            answers = linkedMapOf(
                "first" to "第一条答案",
                "second" to "第二条答案",
            ),
        )
        val formatted = AgentUserInputDisplay.formatAnswer(answer, request = null)
        assertEquals("第一条答案\n\n第二条答案", formatted)
    }

    @Test
    fun freeTextAnswerIsPreservedWithoutLoss() {
        val request = AgentUserInputRequest(
            id = "req-4",
            questions = listOf(
                AgentUserInputQuestion(id = "city", question = "你想去哪个城市？"),
            ),
        )
        val freeText = "我想去广州市天河区体育西路附近的一家粤菜馆"
        val answer = AgentUserInputAnswer(
            requestId = "req-4",
            answers = mapOf("city" to freeText),
        )
        val formatted = AgentUserInputDisplay.formatAnswer(answer, request)
        assertEquals(freeText, formatted)
    }

    @Test
    fun unmatchedExtraAnswerKeysAreNotLost() {
        val request = AgentUserInputRequest(
            id = "req-5",
            questions = listOf(
                AgentUserInputQuestion(id = "q1", question = "题 1"),
            ),
        )
        val answer = AgentUserInputAnswer(
            requestId = "req-5",
            answers = linkedMapOf(
                "q1" to "答 1",
                "extra_key" to "额外自由输入",
            ),
        )
        val formatted = AgentUserInputDisplay.formatAnswer(answer, request)
        assertEquals("答 1\n\n额外自由输入", formatted)
    }
}
