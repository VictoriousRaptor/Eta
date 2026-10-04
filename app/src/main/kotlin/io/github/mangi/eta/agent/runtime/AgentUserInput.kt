package io.github.mangi.eta.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
data class AgentUserInputQuestion(
    val id: String,
    val question: String,
    val options: List<String> = emptyList(),
)

@Serializable
data class AgentUserInputRequest(
    val id: String,
    val questions: List<AgentUserInputQuestion>,
) {
    fun accepts(answer: AgentUserInputAnswer): Boolean =
        answer.requestId == id && answer.answers.keys == questions.map { it.id }.toSet() &&
            answer.answers.values.all { it.isNotBlank() && it.length <= MAX_ANSWER_CHARS }

    companion object {
        const val MAX_ANSWER_CHARS = 4000
        fun fromToolArguments(id: String, raw: String): AgentUserInputRequest {
            val items = JSONObject(raw).getJSONArray("questions")
            require(items.length() in 1..3) { "请提出 1 到 3 个问题" }
            val questions = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val options = item.optJSONArray("options")
                AgentUserInputQuestion(
                    id = item.getString("id"),
                    question = item.getString("question"),
                    options = options?.let { array ->
                        (0 until array.length()).map { array.getString(it) }
                    }.orEmpty(),
                ).also { question ->
                    require(question.id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
                    require(question.question.isNotBlank() && question.question.length <= 500)
                    require(question.options.size <= 4 && question.options.distinct().size == question.options.size)
                    require(question.options.all { it.isNotBlank() && it.length <= 120 })
                }
            }
            require(questions.map { it.id }.distinct().size == questions.size) { "问题 ID 不得重复" }
            return AgentUserInputRequest(id, questions)
        }
    }
}

@Serializable
data class AgentUserInputAnswer(val requestId: String, val answers: Map<String, String>)

internal object AgentUserInputCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(request: AgentUserInputRequest): String = json.encodeToString(request)
    fun encode(answer: AgentUserInputAnswer): String = json.encodeToString(answer)
    fun request(raw: String): AgentUserInputRequest = json.decodeFromString(raw)
    fun answer(raw: String): AgentUserInputAnswer = json.decodeFromString(raw)
}
