package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.runtime.AgentUserInputAnswer
import io.github.mangi.eta.agent.runtime.AgentUserInputQuestion
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest

/**
 * 分页纯状态与转换 helper，不依赖 Android UI 框架，用于驱动 AgentClarificationCard 并支持单元测试。
 */
data class AgentClarificationPagerState(
    val request: AgentUserInputRequest,
    val currentPageIndex: Int = 0,
    val answers: Map<String, String> = emptyMap(),
) {
    val totalPages: Int get() = request.questions.size

    val isSingleQuestion: Boolean get() = totalPages <= 1

    val currentQuestion: AgentUserInputQuestion?
        get() = request.questions.getOrNull(currentPageIndex)

    val currentAnswer: String
        get() = currentQuestion?.let { answers[it.id] }.orEmpty()

    val canGoPrevious: Boolean
        get() = currentPageIndex > 0

    val isCurrentAnswerValid: Boolean
        get() {
            val q = currentQuestion ?: return false
            val answer = answers[q.id]?.trim().orEmpty()
            return answer.isNotBlank() && answer.length <= AgentUserInputRequest.MAX_ANSWER_CHARS
        }

    val canGoNext: Boolean
        get() = currentPageIndex < totalPages - 1 && isCurrentAnswerValid

    val isLastPage: Boolean
        get() = currentPageIndex >= totalPages - 1

    val canSubmit: Boolean
        get() = totalPages > 0 && request.questions.all { q ->
            val ans = answers[q.id]?.trim().orEmpty()
            ans.isNotBlank() && ans.length <= AgentUserInputRequest.MAX_ANSWER_CHARS
        }

    fun withAnswer(questionId: String, text: String): AgentClarificationPagerState {
        return copy(answers = answers + (questionId to text))
    }

    fun previousPage(): AgentClarificationPagerState {
        return if (canGoPrevious) copy(currentPageIndex = currentPageIndex - 1) else this
    }

    fun nextPage(): AgentClarificationPagerState {
        return if (canGoNext) copy(currentPageIndex = currentPageIndex + 1) else this
    }

    fun buildFinalAnswer(): AgentUserInputAnswer? {
        if (!canSubmit) return null
        val trimmedAnswers = LinkedHashMap<String, String>()
        for (q in request.questions) {
            val ans = answers[q.id]?.trim() ?: return null
            trimmedAnswers[q.id] = ans
        }
        val finalAnswer = AgentUserInputAnswer(
            requestId = request.id,
            answers = trimmedAnswers,
        )
        return if (request.accepts(finalAnswer)) finalAnswer else null
    }
}
