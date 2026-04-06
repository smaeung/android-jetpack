package com.example.hptransactions.buffer

import app.cash.turbine.test
import com.example.hptransactions.data.Transaction
import com.example.hptransactions.data.TransactionStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionRingBufferTest {

    private fun tx(id: String) = Transaction(
        id = id, amount = 1.0, merchant = "Test",
        status = TransactionStatus.PENDING, timestampMs = 0L
    )

    @Test
    fun `emitting within capacity delivers the item to collector`() = runTest {
        val buffer = TransactionRingBuffer(capacity = 10)
        buffer.flow.test {
            buffer.tryEmit(tx("a"))
            val received = awaitItem()
            assertEquals("a", received.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Verifies DROP_OLDEST semantics using a real slow subscriber.
     *
     * Turbine's flow.test{} uses an unbounded internal channel — items are consumed
     * immediately so the SharedFlow buffer never fills and DROP_OLDEST never fires.
     * Instead we use a real coroutine that blocks inside its collect lambda after the
     * first item, leaving subsequent emissions to genuinely accumulate in the buffer
     * and trigger DROP_OLDEST.
     *
     * Timeline:
     *   tryEmit("1") → subscriber receives it, then blocks on mutex
     *   tryEmit("2") → sits in extraBufferCapacity[0]
     *   tryEmit("3") → sits in extraBufferCapacity[1]  (buffer full, capacity=2)
     *   tryEmit("4") → DROP_OLDEST evicts "2"; buffer = ["3","4"]
     *   mutex.unlock → subscriber resumes, drains "3" then "4"
     *   Expected received: ["1","3","4"]
     */
    @Test
    fun `DROP_OLDEST drops oldest buffered item when subscriber is blocked`() = runTest {
        val buffer = TransactionRingBuffer(capacity = 2)
        val received = mutableListOf<String>()
        val blockSubscriber = Mutex(locked = true)
        var firstItem = true

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            buffer.flow.collect { transaction ->
                if (firstItem) {
                    firstItem = false
                    blockSubscriber.withLock { } // block until unlocked
                }
                received.add(transaction.id)
            }
        }

        buffer.tryEmit(tx("1"))
        advanceUntilIdle()       // subscriber receives "1", blocks on mutex

        buffer.tryEmit(tx("2"))  // extraBufferCapacity[0]
        buffer.tryEmit(tx("3"))  // extraBufferCapacity[1] — buffer now full
        buffer.tryEmit(tx("4"))  // DROP_OLDEST: "2" evicted; buffer = ["3","4"]

        blockSubscriber.unlock()
        advanceUntilIdle()       // subscriber drains remaining items

        assertEquals(listOf("1", "3", "4"), received)
        job.cancel()
    }

    @Test
    fun `tryEmit always returns true under DROP_OLDEST even beyond capacity`() = runTest {
        val buffer = TransactionRingBuffer(capacity = 1)
        buffer.flow.test {
            val results = (1..5).map { i -> buffer.tryEmit(tx("id-$i")) }
            assertTrue("All tryEmit calls must return true", results.all { it })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `collector receives items in emission order`() = runTest {
        val buffer = TransactionRingBuffer(capacity = 5)
        buffer.flow.test {
            val ids = listOf("a", "b", "c")
            ids.forEach { buffer.tryEmit(tx(it)) }
            val received = (1..3).map { awaitItem().id }
            assertEquals(ids, received)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Verifies that capacity=1 keeps only the single newest buffered item.
     *
     * Same slow-subscriber technique as above. With extraBufferCapacity=1:
     *   tryEmit("A") → subscriber receives it, then blocks
     *   tryEmit("B") → sits in extraBufferCapacity[0]  (buffer full)
     *   tryEmit("C") → DROP_OLDEST evicts "B"; buffer = ["C"]
     *   unlock → subscriber resumes, receives "C"
     *   Expected received: ["A","C"]
     */
    @Test
    fun `ring buffer capacity 1 keeps only newest item when subscriber is blocked`() = runTest {
        val buffer = TransactionRingBuffer(capacity = 1)
        val received = mutableListOf<String>()
        val blocked = Mutex(locked = true)
        var firstItem = true

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            buffer.flow.collect { transaction ->
                if (firstItem) {
                    firstItem = false
                    blocked.withLock { } // block until unlocked
                }
                received.add(transaction.id)
            }
        }

        buffer.tryEmit(tx("A"))
        advanceUntilIdle()       // subscriber receives "A", blocks

        buffer.tryEmit(tx("B"))  // extraBufferCapacity[0] — buffer full (capacity=1)
        buffer.tryEmit(tx("C"))  // DROP_OLDEST: "B" evicted; buffer = ["C"]

        blocked.unlock()
        advanceUntilIdle()       // subscriber drains "C"

        assertEquals(listOf("A", "C"), received)
        job.cancel()
    }
}
