package io.github.pkczc.eta.hook.breeno

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoHookStateTest {
    @Test
    fun nativeModeCannotBeBypassedByAPrefixAndBothInputsNormalizeTheSamePrompt() {
        assertNull(breenoCustomModelPrompt("/agent 调低音量", false, true, true))
        assertNull(breenoCustomModelPrompt("/agent%20调低音量", false, false, true))
        assertNull(breenoCustomModelPrompt("调低音量", true, true, true))
        assertEquals("调低音量", breenoCustomModelPrompt(" /agent 调低音量 ", true, true, true))
        assertEquals("调低音量", breenoCustomModelPrompt("/agent%20调低音量", true, true, true))
        assertEquals("调低音量", breenoCustomModelPrompt("调低音量", true, false, true))
    }

    @Test
    fun disablingExclusiveTakeoverReturnsNormalAndPrefixedRequestsToBreeno() {
        listOf("调低音量", "/agent 调低音量", "/agent%20调低音量").forEach { text ->
            listOf(false, true).forEach { requirePrefix ->
                assertNull(breenoCustomModelPrompt(
                    text = text,
                    customModelEnabled = true,
                    requirePrefix = requirePrefix,
                    exclusiveTakeoverEnabled = false,
                ))
            }
        }
    }

    @Test
    fun pendingAcksRemainBoundedAndRequestRescanOnOverflow() {
        val state = PendingAckState(capacity = 2, rescanAttempts = 2)

        assertEquals(PendingAckState.EnqueueResult.ADDED, state.enqueue("run-1"))
        assertEquals(PendingAckState.EnqueueResult.DUPLICATE, state.enqueue("run-1"))
        assertEquals(PendingAckState.EnqueueResult.ADDED, state.enqueue("run-2"))
        assertEquals(PendingAckState.EnqueueResult.OVERFLOW, state.enqueue("run-3"))
        assertEquals(2, state.pendingCount())

        assertEquals("run-1", state.poll())
        assertEquals("run-2", state.poll())
        assertTrue(state.takeRescanRequest())
        assertTrue(state.takeRescanRequest())
        assertFalse(state.takeRescanRequest())
        assertFalse(state.hasWork())
    }

    @Test
    fun handledRunIdsUseABoundedAccessOrderedWindow() {
        val runIds = BoundedRunIdSet(capacity = 2)

        assertTrue(runIds.add("run-1"))
        assertTrue(runIds.add("run-2"))
        assertFalse(runIds.add("run-1"))
        assertTrue(runIds.add("run-3"))

        assertTrue(runIds.contains("run-1"))
        assertFalse(runIds.contains("run-2"))
        assertTrue(runIds.contains("run-3"))
        assertEquals(2, runIds.size())
    }

    @Test
    fun handledRunIdsExpireAfterTheirDeliveryWindow() {
        var now = 1_000L
        val runIds = BoundedRunIdSet(
            capacity = 2,
            ttlMillis = 100L,
            nowMillis = { now },
        )

        assertTrue(runIds.add("run-1"))
        now = 1_099L
        assertTrue(runIds.contains("run-1"))
        now = 1_100L
        assertFalse(runIds.contains("run-1"))
        assertEquals(0, runIds.size())
    }

    @Test
    fun requestDedupKeyIsFixedLengthAndDoesNotRetainPromptText() {
        val prompt = "请总结这段包含敏感信息的请求"

        val key = requestKeys("record-1", prompt = prompt).single()

        assertEquals(64, key.length)
        assertFalse(key.contains(prompt))
        assertEquals(key, requestKeys("record-1", prompt = prompt).single())
        assertNotEquals(key, requestKeys("record-1", prompt = "$prompt!").single())
        assertNotEquals(
            requestKeys("bc", roomId = "a"),
            requestKeys("c", roomId = "ab"),
        )
    }

    @Test
    fun consecutiveIdenticalCommandsWithDifferentRecordIdsRemainIndependent() {
        val first = requestKeys("record-1")
        val second = requestKeys("record-2")

        assertTrue(first.intersect(second.toSet()).isEmpty())
        assertTrue(first.intersect(requestKeys("record-1").toSet()).isNotEmpty())
    }

    @Test
    fun outboundAndChatCallbacksShareTheOriginalRecordAlias() {
        val outbound = requestKeys("transport-record", originalRecordId = "query-record")
        val query = requestKeys("query-record")

        assertTrue(outbound.intersect(query.toSet()).isNotEmpty())
        assertTrue(query.intersect(requestKeys("query-record", roomId = "other-room").toSet()).isEmpty())
    }

    @Test
    fun missingHostRecordIdsDoNotSwallowIndependentRequests() {
        val first = requestKeys("", fallbackId = "run-1")
        val second = requestKeys("", fallbackId = "run-2")

        assertNotEquals(first, second)
    }

    @Test
    fun delayedCallbacksRemainDeduplicatedByTheirRecordAlias() {
        var now = 1_000L
        val handled = BoundedRunIdSet(capacity = 4, nowMillis = { now })
        requestKeys("transport-record", originalRecordId = "query-record").forEach { handled.add(it) }

        now += 10 * 60_000L
        assertTrue(requestKeys("query-record").any(handled::contains))
        assertFalse(requestKeys("new-query-record").any(handled::contains))
    }

    @Test
    fun claimedRoomsStayOwnedDuringLongTasksAndLateRepliesUntilReleased() {
        var now = 1_000L
        val rooms = BoundedRunIdSet(capacity = 2, nowMillis = { now })
        rooms.add("room-1")

        now += 10 * 60_000L
        assertTrue(rooms.contains("room-1"))
        assertFalse(rooms.contains("other-room"))
        rooms.remove("room-1")
        assertFalse(rooms.contains("room-1"))
    }

    private fun requestKeys(
        recordId: String,
        originalRecordId: String = "",
        roomId: String = "room-42",
        fallbackId: String = "run-1",
        prompt: String = "再把音量降低一点",
    ): List<String> = breenoRequestDedupKeys(roomId, recordId, originalRecordId, fallbackId, prompt)

    @Test
    fun retryBudgetStopsAndCanBeResetByANewDeliveryEvent() {
        val budget = BoundedRetryBudget(maxAttempts = 2)

        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())

        budget.reset()

        assertTrue(budget.tryAcquire())
    }

    @Test
    fun admittedTaskDoesNotRunBeforeActiveStateCanBePublished() {
        val gate = TaskAdmissionGate()
        val workerStarted = CountDownLatch(1)
        val admitted = AtomicReference<Boolean?>()
        val worker = Thread {
            workerStarted.countDown()
            admitted.set(gate.awaitAdmission())
        }.apply {
            isDaemon = true
            start()
        }

        assertTrue(workerStarted.await(1, TimeUnit.SECONDS))
        assertNull(admitted.get())

        gate.admit()
        worker.join(1_000L)

        assertFalse(worker.isAlive)
        assertEquals(true, admitted.get())
    }

    @Test
    fun cancelledAdmissionReleasesWaitingTaskWithoutRunningIt() {
        val gate = TaskAdmissionGate()
        val workerStarted = CountDownLatch(1)
        val admitted = AtomicReference<Boolean?>()
        val worker = Thread {
            workerStarted.countDown()
            admitted.set(gate.awaitAdmission())
        }.apply {
            isDaemon = true
            start()
        }

        assertTrue(workerStarted.await(1, TimeUnit.SECONDS))
        gate.cancel()
        worker.join(1_000L)

        assertFalse(worker.isAlive)
        assertEquals(false, admitted.get())
    }
}
