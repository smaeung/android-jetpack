package com.example.hptransactions.buffer

import app.cash.turbine.test
import com.example.hptransactions.data.Transaction
import com.example.hptransactions.data.TransactionStatus
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

    @Test
    fun `DROP_OLDEST drops oldest item when subscriber buffer is full`() = runTest {
        val buffer = TransactionRingBuffer(capacity = 2)
        buffer.flow.test {
            buffer.tryEmit(tx("1"))
            buffer.tryEmit(tx("2"))
            buffer.tryEmit(tx("3")) // buffer full: tx("1") dropped, tx("3") accepted

            val first = awaitItem()
            assertEquals("2", first.id)
            val second = awaitItem()
            assertEquals("3", second.id)

            cancelAndIgnoreRemainingEvents()
        }
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

    @Test
    fun `ring buffer size 1 drops all but the last item when collector is slow`() = runTest {
        val buffer = TransactionRingBuffer(capacity = 1)
        buffer.flow.test {
            buffer.tryEmit(tx("A"))
            buffer.tryEmit(tx("B")) // drops A
            buffer.tryEmit(tx("C")) // drops B

            val received = awaitItem()
            assertEquals("C", received.id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
