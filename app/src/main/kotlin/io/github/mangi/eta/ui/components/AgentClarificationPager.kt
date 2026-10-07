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
    val selectedOptions: Map<String, Set<String>> = emptyMap(),
) {
    val totalPages: Int get() = request.questions.size

    val isSingleQuestion: Boolean get() = totalPages <= 1

    val currentQuestion: AgentUserInputQuestion?
        get() = request.questions.getOrNull(currentPageIndex)

    val currentAnswer: String
        get() = currentQuestion?.let { answers[it.id] }.orEmpty()

    val currentSelectedOptions: Set<String>
        get() = currentQuestion?.let { selectedOptions[it.id] }.orEmpty()

    fun selectedOptionsFor(questionId: String): Set<String> =
        selectedOptions[questionId].orEmpty()

    val canGoPrevious: Boolean
        get() = currentPageIndex > 0

    fun formatAnswer(question: AgentUserInputQuestion): String? {
        if (question.multiSelect) {
            val selected = selectedOptions[question.id].orEmpty()
            val freeText = answers[question.id]?.trim().orEmpty()
            if (selected.isEmpty() && freeText.isBlank()) return null
            val parts = mutableListOf<String>()
            for (opt in question.options) {
                if (opt in selected) {
                    parts.add(opt)
                }
            }
            for (opt in selected) {
                if (opt !in question.options && opt !in parts) {
                    parts.add(opt)
                }
            }
            if (freeText.isNotBlank()) {
                parts.add(freeText)
            }
            val formatted = parts.joinToString("\n")
            return if (formatted.isNotBlank() && formatted.length <= AgentUserInputRequest.MAX_ANSWER_CHARS) {
                formatted
            } else {
                null
            }
        } else {
            val ans = answers[question.id]?.trim() ?: return null
            return if (ans.isNotBlank() && ans.length <= AgentUserInputRequest.MAX_ANSWER_CHARS) ans else null
        }
    }

    val currentFormattedAnswer: String?
        get() = currentQuestion?.let { formatAnswer(it) }

    val isCurrentAnswerValid: Boolean
        get() {
            val q = currentQuestion ?: return false
            return formatAnswer(q) != null
        }

    val canGoNext: Boolean
        get() = currentPageIndex < totalPages - 1 && isCurrentAnswerValid

    val isLastPage: Boolean
        get() = currentPageIndex >= totalPages - 1

    val canSubmit: Boolean
        get() = totalPages > 0 && request.questions.all { formatAnswer(it) != null }

    fun withAnswer(questionId: String, text: String): AgentClarificationPagerState {
        return copy(answers = answers + (questionId to text))
    }

    fun toggleOption(questionId: String, option: String): AgentClarificationPagerState {
        val current = selectedOptions[questionId].orEmpty()
        val updated = if (option in current) current - option else current + option
        return copy(selectedOptions = selectedOptions + (questionId to updated))
    }

    fun withSelectedOptions(questionId: String, options: Set<String>): AgentClarificationPagerState {
        return copy(selectedOptions = selectedOptions + (questionId to options))
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
            val ans = formatAnswer(q) ?: return null
            trimmedAnswers[q.id] = ans
        }
        val finalAnswer = AgentUserInputAnswer(
            requestId = request.id,
            answers = trimmedAnswers,
        )
        return if (request.accepts(finalAnswer)) finalAnswer else null
    }
}
