package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AgentUserInputTest {
    private val request = AgentUserInputRequest("request-1", listOf(
        AgentUserInputQuestion("destination", "目的地？", listOf("广州", "深圳")),
        AgentUserInputQuestion("date", "哪一天？"),
    ))
    private val answer = AgentUserInputAnswer(request.id, mapOf("destination" to "佛山", "date" to "周六"))

    @Test
    fun rejectsIncompleteWrongAndDuplicateAnswersWhileAcceptingFreeText() {
        val controller = AgentRunController()
        val requested = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<AgentUserInputAnswer>()
        val worker = thread {
            result.set(controller.awaitUserInput(request) {
                requested.countDown()
                release.await(2, TimeUnit.SECONDS)
            })
            delivered.countDown()
        }
        try {
            assertTrue(requested.await(1, TimeUnit.SECONDS))
            assertFalse(controller.answerUserInput(answer.copy(requestId = "stale")))
            assertFalse(controller.answerUserInput(answer.copy(answers = mapOf("date" to "周六"))))
            assertFalse(controller.answerUserInput(answer.copy(answers = answer.answers + ("other" to "x"))))
            assertFalse(controller.answerUserInput(answer.copy(answers = answer.answers + ("date" to " "))))
            assertTrue(controller.answerUserInput(answer))
            assertFalse(controller.answerUserInput(answer))
            release.countDown()
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals(answer, result.get())
            assertFalse(controller.answerUserInput(answer))
        } finally { release.countDown(); controller.cancel(); worker.join(1000) }
    }

    @Test
    fun cancellationWakesClarificationWaitAndRejectsLateAnswer() {
        val controller = AgentRunController()
        val requested = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = thread {
            try { controller.awaitUserInput(request) { requested.countDown() } }
            catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            assertTrue(requested.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
            assertFalse(controller.answerUserInput(answer))
        } finally { controller.cancel(); worker.join(1000) }
    }

    @Test
    fun answerAndSteeringDoNotReleaseUserPause() {
        val controller = AgentRunController()
        val requested = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = thread {
            controller.awaitUserInput(request) { requested.countDown() }
            answered.countDown()
            controller.throwIfCancelled()
            finished.countDown()
        }
        try {
            assertTrue(requested.await(1, TimeUnit.SECONDS))
            controller.pause()
            assertTrue(controller.steer("补充"))
            assertTrue(controller.answerUserInput(answer))
            assertTrue(answered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
            assertEquals("补充", controller.pollSteeringMessage())
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        } finally { controller.resume(); controller.cancel(); worker.join(1000) }
    }

    @Test
    fun codecPreservesQuestionsOptionsAndArbitraryAnswers() {
        assertEquals(request, AgentUserInputCodec.request(AgentUserInputCodec.encode(request)))
        assertEquals(answer, AgentUserInputCodec.answer(AgentUserInputCodec.encode(answer)))
    }

    @Test
    fun toolArgumentsRejectDuplicateIdsAndBlankQuestions() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentUserInputRequest.fromToolArguments("r", """{"questions":[{"id":"x","question":"a"},{"id":"x","question":"b"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentUserInputRequest.fromToolArguments("r", """{"questions":[{"id":"x","question":" "}]}""")
        }
    }

    @Test
    fun toolArgumentsParseDefaultAndExplicitMultiSelect() {
        val rawDefault = """{"questions":[{"id":"q1","question":"去哪里？","options":["北京","上海"]}]}"""
        val parsedDefault = AgentUserInputRequest.fromToolArguments("req-def", rawDefault)
        assertEquals(1, parsedDefault.questions.size)
        assertFalse(parsedDefault.questions[0].multiSelect)

        val rawExplicitTrue = """{"questions":[{"id":"q1","question":"偏好？","options":["静音","靠窗"],"multiSelect":true}]}"""
        val parsedExplicitTrue = AgentUserInputRequest.fromToolArguments("req-true", rawExplicitTrue)
        assertTrue(parsedExplicitTrue.questions[0].multiSelect)

        val rawExplicitFalse = """{"questions":[{"id":"q1","question":"单选偏好？","options":["A","B"],"multiSelect":false}]}"""
        val parsedExplicitFalse = AgentUserInputRequest.fromToolArguments("req-false", rawExplicitFalse)
        assertFalse(parsedExplicitFalse.questions[0].multiSelect)
    }

    @Test
    fun codecPreservesMultiSelectFlag() {
        val multiRequest = AgentUserInputRequest("multi-req", listOf(
            AgentUserInputQuestion("diet", "饮食偏好？", listOf("无辣", "素食", "清淡"), multiSelect = true),
            AgentUserInputQuestion("seat", "座位偏好？", listOf("靠窗", "过道"), multiSelect = false),
        ))
        val encoded = AgentUserInputCodec.encode(multiRequest)
        val decoded = AgentUserInputCodec.request(encoded)
        assertEquals(multiRequest, decoded)
        assertTrue(decoded.questions[0].multiSelect)
        assertFalse(decoded.questions[1].multiSelect)
    }

    @Test
    fun acceptsAcceptsMultiLineAnswerFromMultiSelect() {
        val multiRequest = AgentUserInputRequest("multi-req-2", listOf(
            AgentUserInputQuestion("q1", "选择？", listOf("A", "B", "C"), multiSelect = true),
        ))
        val multiLineAnswer = AgentUserInputAnswer("multi-req-2", mapOf("q1" to "A\nB\n附加说明"))
        assertTrue(multiRequest.accepts(multiLineAnswer))
    }
}
