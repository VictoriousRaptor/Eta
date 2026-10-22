package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRevisionReducerTest {
    @Test
    fun editingUserTurnCoveredBySummaryDropsStaleSummary() {
        val summary = AgentModelClient.ConversationMessage("assistant", "旧操作结果", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        val state = AgentChatUiState(
            messages = listOf(UserMessageUi("old", "旧请求"), UserMessageUi("latest", "最新请求")),
            history = listOf(summary, AgentModelClient.ConversationMessage("user", "最新请求")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val boundary = AgentConversationRevisionReducer.boundary(state, "latest")!!
        assertTrue(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        val next = state.copy(messages = state.messages + UserMessageUi("next", "后续请求"),
            history = state.history + AgentModelClient.ConversationMessage("user", "后续请求"))
        assertTrue(AgentConversationRevisionReducer.boundary(next, "next")!!.historyPrefix.contains(summary))
    }

    @Test
    fun boundaryMapsAssistantToItsUserTurnAndKeepsToolTranscriptPrefix() {
        val state = conversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!

        assertEquals("user-2", boundary.userMessage.id)
        assertEquals(4, boundary.userMessageIndex)
        assertEquals(1, boundary.laterTurnCount)
        assertFalse(boundary.contextWasCompacted)
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromMiddleTurnTruncatesMessagesAndHistoryTogether() {
        val state = conversationState().copy(appliedRuntimeRunIds = listOf("run-1", "run-2"))

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-2")!!

        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
            revised.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), revised.history.map { it.role })
        assertEquals(listOf("run-1", "run-2"), revised.appliedRuntimeRunIds)
    }

    @Test
    fun compactedCheckpointAlignsRetainedTurnsFromTheTail() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "已压缩"),
                AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
                AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
            )
        )

        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        val retained = AgentConversationRevisionReducer.boundary(compacted, "user-2")!!

        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertFalse(retained.contextWasCompacted)
        assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
    }

    @Test
    fun visibleMessagesStopAtEditedUserWithoutMutatingTheSource() {
        val messages = conversationState().messages

        val visible = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "user-2")

        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"), visible.map { it.id })
        assertEquals(8, messages.size)
    }

    @Test
    fun invalidMessageDoesNotChangeConversation() {
        val state = conversationState()

        assertNull(AgentConversationRevisionReducer.boundary(state, "missing"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "missing"))
        assertEquals(
            state.messages,
            AgentConversationRevisionReducer.visibleMessagesForEdit(state.messages, "missing"),
        )
    }

    private fun conversationState(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            ThinkingMessageUi(id = "thinking-1", content = "思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问", images = listOf("data:image/png;base64,AA==")),
            AgentMessageUi(id = "assistant-2", content = "第二答"),
            UserMessageUi(id = "user-3", content = "第三问"),
            AgentMessageUi(id = "assistant-3", content = "第三答"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "第一问"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "结果"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第一答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
        ),
        input = "草稿",
        isStreaming = false,
        thinkingEnabled = false,
    )

    @Test
    fun clarificationAnswersDoNotShiftTurnBoundariesOrCountAsUserTurns() {
        val state = conversationStateWithClarifications()
        val secondTurnTargets = listOf(
            "user-2",
            "clarification-answer-run-2-req-1",
            "clarification-answer-run-2-req-2",
            "assistant-2",
        )

        // 历史模型消息中的 user 角色数量没有因为澄清答案而增加（仍为 3 次问答）
        assertEquals(3, state.history.count { it.role == "user" })

        for (targetId in secondTurnTargets) {
            val boundary = AgentConversationRevisionReducer.boundary(state, targetId)!!
            assertEquals("user-2", boundary.userMessage.id)
            assertEquals(listOf("data:image/png;base64,AA=="), boundary.userMessage.images)
            assertEquals(4, boundary.userMessageIndex)
            assertEquals(1, boundary.laterTurnCount)
            assertFalse(boundary.contextWasCompacted)
            assertEquals(
                listOf("user", "assistant", "tool", "assistant"),
                boundary.historyPrefix.map { it.role },
            )
        }

        // 第一轮真实轮次不受错位影响
        val firstBoundary = AgentConversationRevisionReducer.boundary(state, "user-1")!!
        assertEquals("user-1", firstBoundary.userMessage.id)
        assertEquals(0, firstBoundary.userMessageIndex)
        assertEquals(2, firstBoundary.laterTurnCount)
        assertFalse(firstBoundary.contextWasCompacted)
        assertTrue(firstBoundary.historyPrefix.isEmpty())

        // 后续第三轮真实轮次不受错位影响，前缀保留第二轮全部澄清 tool transcript
        val thirdBoundary = AgentConversationRevisionReducer.boundary(state, "assistant-3")!!
        assertEquals("user-3", thirdBoundary.userMessage.id)
        assertEquals(13, thirdBoundary.userMessageIndex)
        assertEquals(0, thirdBoundary.laterTurnCount)
        assertFalse(thirdBoundary.contextWasCompacted)
        assertEquals(12, thirdBoundary.historyPrefix.size)
        assertEquals(
            listOf(
                "user", "assistant", "tool", "assistant",
                "user", "assistant", "tool", "assistant", "tool", "assistant", "tool", "assistant",
            ),
            thirdBoundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromTurnWithClarificationAnswersPreservesEarlierTurnAndPrefersJournal() {
        val baseState = conversationStateWithClarifications()
        val compactedHistory = listOf(
            AgentModelClient.ConversationMessage(role = "system", content = "已压缩历史"),
            AgentModelClient.ConversationMessage(role = "user", content = "最新问"),
        )
        // journal 显式非空且包含完整历史，应优先于被压缩的 history
        val stateWithJournal = baseState.copy(
            journal = baseState.history,
            history = compactedHistory,
        )

        val deleteTargets = listOf(
            "user-2",
            "clarification-answer-run-2-req-1",
            "clarification-answer-run-2-req-2",
            "assistant-2",
        )

        for (targetId in deleteTargets) {
            val revised = AgentConversationRevisionReducer.deleteFromTurn(stateWithJournal, targetId)!!
            assertEquals(
                listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
                revised.messages.map { it.id },
            )
            val expectedRoles = listOf("user", "assistant", "tool", "assistant")
            assertEquals(expectedRoles, revised.history.map { it.role })
            assertEquals(expectedRoles, revised.journal.map { it.role })
            // 已删除轮的澄清 tool 片段完全不保留在 history/journal 中
            assertFalse(revised.history.any { it.content == "确认" || it.content == "深圳" })
            assertFalse(revised.journal.any { it.content == "确认" || it.content == "深圳" })
        }
    }

    @Test
    fun compactedHistoryTailAlignmentPreservesPrefixAndFlagsMissingTurnsWithClarificationAnswers() {
        val state = conversationStateWithClarifications()
        val compactedHistory = listOf(
            AgentModelClient.ConversationMessage(role = "system", content = "已压缩"),
            AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                toolCallsJson = "[{\"id\":\"req-1\",\"function\":{\"name\":\"request_user_input\"}}]",
            ),
            AgentModelClient.ConversationMessage(role = "tool", content = "确认"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
        )
        val compacted = state.copy(history = compactedHistory, journal = emptyList())

        // 第一轮已压缩丢失，标记 contextWasCompacted 且前缀为空
        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertEquals(2, missing.laterTurnCount)

        // 第二轮仍保留在压缩记录中，无论从 user-2、澄清答案还是 assistant-2 查询，均正确对齐
        val secondTurnTargets = listOf(
            "user-2",
            "clarification-answer-run-2-req-1",
            "clarification-answer-run-2-req-2",
            "assistant-2",
        )
        for (targetId in secondTurnTargets) {
            val retained = AgentConversationRevisionReducer.boundary(compacted, targetId)!!
            assertFalse(retained.contextWasCompacted)
            assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
            assertEquals(1, retained.laterTurnCount)
        }

        // 第三轮正确保留第二轮保留下来的前缀
        val third = AgentConversationRevisionReducer.boundary(compacted, "user-3")!!
        assertFalse(third.contextWasCompacted)
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), third.historyPrefix.map { it.role })
        assertEquals(0, third.laterTurnCount)
    }

    @Test
    fun steerSupplementCountsAsUserTurnWhileClarificationAnswerDoesNot() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "user-1", content = "第一问"),
                AgentMessageUi(id = "assistant-1", content = "第一答"),
                UserMessageUi(id = "user-2", content = "第二问"),
                ToolActivityMessageUi(
                    id = "tool-clarify-1",
                    toolName = "request_user_input",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "{}",
                ),
                UserMessageUi(id = "clarification-answer-run-2-req-1", content = "澄清回答"),
                UserMessageUi(id = "user-run-2-supplement-1", content = "补充指示"),
                AgentMessageUi(id = "assistant-2", content = "第二答"),
                UserMessageUi(id = "user-3", content = "第三问"),
                AgentMessageUi(id = "assistant-3", content = "第三答"),
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "第一问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第一答"),
                AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    toolCallsJson = "[{\"id\":\"req-1\",\"function\":{\"name\":\"request_user_input\"}}]",
                ),
                AgentModelClient.ConversationMessage(role = "tool", content = "澄清回答"),
                AgentModelClient.ConversationMessage(role = "user", content = "补充指示"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
                AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )

        // Steer supplement 具有独立的用户轮次与匹配的 role=user
        val supplementBoundary = AgentConversationRevisionReducer.boundary(state, "user-run-2-supplement-1")!!
        assertEquals("user-run-2-supplement-1", supplementBoundary.userMessage.id)
        assertEquals(5, supplementBoundary.userMessageIndex)
        assertEquals(1, supplementBoundary.laterTurnCount)
        assertEquals(
            listOf("user", "assistant", "user", "assistant", "tool"),
            supplementBoundary.historyPrefix.map { it.role },
        )

        // Clarify 答案不成为用户边界，也不会增加 laterTurnCount
        for (targetId in listOf("user-2", "clarification-answer-run-2-req-1")) {
            val boundary = AgentConversationRevisionReducer.boundary(state, targetId)!!
            assertEquals("user-2", boundary.userMessage.id)
            assertEquals(2, boundary.userMessageIndex)
            assertEquals(2, boundary.laterTurnCount) // 包含 user-run-2-supplement-1 和 user-3，澄清答案不计数
            assertEquals(listOf("user", "assistant"), boundary.historyPrefix.map { it.role })
        }
    }

    private fun conversationStateWithClarifications(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            ThinkingMessageUi(id = "thinking-1", content = "思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问", images = listOf("data:image/png;base64,AA==")),
            ThinkingMessageUi(id = "thinking-2-1", content = "思考中", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-clarify-1",
                toolName = "request_user_input",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"prompt\":\"确认继续？\"}",
            ),
            UserMessageUi(id = "clarification-answer-run-2-req-1", content = "确认"),
            ThinkingMessageUi(id = "thinking-2-2", content = "继续分析", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-clarify-2",
                toolName = "request_user_input",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"prompt\":\"选择城市\"}",
            ),
            UserMessageUi(id = "clarification-answer-run-2-req-2", content = "深圳"),
            ToolActivityMessageUi(
                id = "tool-2-exec",
                toolName = "search",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"query\":\"深圳\"}",
            ),
            AgentMessageUi(id = "assistant-2", content = "第二答"),
            UserMessageUi(id = "user-3", content = "第三问"),
            AgentMessageUi(id = "assistant-3", content = "第三答"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "第一问"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "结果"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第一答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                toolCallsJson = "[{\"id\":\"req-1\",\"function\":{\"name\":\"request_user_input\"}}]",
            ),
            AgentModelClient.ConversationMessage(role = "tool", content = "确认"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                toolCallsJson = "[{\"id\":\"req-2\",\"function\":{\"name\":\"request_user_input\"}}]",
            ),
            AgentModelClient.ConversationMessage(role = "tool", content = "深圳"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                toolCallsJson = "[{\"id\":\"call-3\",\"function\":{\"name\":\"search\"}}]",
            ),
            AgentModelClient.ConversationMessage(role = "tool", content = "搜索结果"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
        ),
        input = "草稿",
        isStreaming = false,
        thinkingEnabled = false,
    )
}
