package io.github.mangi.eta.agent.runtime

object AgentUserInputDisplay {
    /**
     * 将用户回答格式化为显示文本。
     *
     * - 仅包含答案内容，无问题文本或 ID 前缀。
     * - 当 [request] 存在且 ID 匹配时，严格按问题的定义顺序提取答案。
     *   若答案中存在未在问题列表中定义的多余字段（如扩展自由输入），按其原顺序追加，确保不丢失用户自由输入。
     * - 当无匹配 [request]（如历史回放或跨进程重连）时，直接按答案 Map 的稳定迭代顺序展示各答案。
     * - 多项答案以双换行（\n\n）连接。
     */
    fun formatAnswer(
        answer: AgentUserInputAnswer,
        request: AgentUserInputRequest? = null,
    ): String {
        val matchingRequest = request?.takeIf { it.id == answer.requestId }
        val orderedAnswers = if (matchingRequest != null) {
            val fromQuestions = matchingRequest.questions.mapNotNull { question ->
                answer.answers[question.id]
            }
            val remaining = answer.answers.filterKeys { key ->
                matchingRequest.questions.none { it.id == key }
            }.values
            fromQuestions + remaining
        } else {
            answer.answers.values.toList()
        }
        return orderedAnswers.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty {
            orderedAnswers.joinToString("\n\n")
        }
    }
}
