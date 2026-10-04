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
}
