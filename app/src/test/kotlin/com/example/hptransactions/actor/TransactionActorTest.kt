package com.example.hptransactions.actor

import com.example.hptransactions.data.Transaction
import com.example.hptransactions.data.TransactionStatus
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionActorTest {

    private fun tx(id: String, status: TransactionStatus = TransactionStatus.PENDING) =
        Transaction(id = id, amount = 1.0, merchant = "M", status = status, timestampMs = 0L)

    @Test
    fun `AddTransaction prepends newest item to list`() = runTest {
        val states = mutableListOf<List<Transaction>>()
        val actor: SendChannel<TransactionMessage> = transactionActor { states.add(it) }

        actor.send(AddTransaction(tx("1")))
        actor.send(AddTransaction(tx("2")))
        actor.close()

        // Drain the channel
        advanceUntilIdle()

        // Last emitted state should have "2" at index 0 (newest first)
        val lastState = states.last()
        assertEquals("2", lastState[0].id)
        assertEquals("1", lastState[1].id)
    }

    @Test
    fun `UpdateStatus changes the status of the correct transaction`() = runTest {
        val states = mutableListOf<List<Transaction>>()
        val actor: SendChannel<TransactionMessage> = transactionActor { states.add(it) }

        actor.send(AddTransaction(tx("1")))
        actor.send(UpdateStatus("1", TransactionStatus.COMPLETED))
        actor.close()
        advanceUntilIdle()

        val last = states.last()
        assertEquals(TransactionStatus.COMPLETED, last.first { it.id == "1" }.status)
    }

    @Test
    fun `ClearAll empties the list`() = runTest {
        val states = mutableListOf<List<Transaction>>()
        val actor: SendChannel<TransactionMessage> = transactionActor { states.add(it) }

        actor.send(AddTransaction(tx("1")))
        actor.send(AddTransaction(tx("2")))
        actor.send(ClearAll)
        actor.close()
        advanceUntilIdle()

        assertTrue(states.last().isEmpty())
    }

    @Test
    fun `UpdateStatus with non-existent ID leaves list unchanged`() = runTest {
        val states = mutableListOf<List<Transaction>>()
        val actor: SendChannel<TransactionMessage> = transactionActor { states.add(it) }

        actor.send(AddTransaction(tx("exists")))
        actor.send(UpdateStatus("no-such-id", TransactionStatus.COMPLETED))
        actor.close()
        advanceUntilIdle()

        val last = states.last()
        assertEquals(1, last.size)
        assertEquals("exists", last[0].id)
        assertEquals(TransactionStatus.PENDING, last[0].status)
    }
}
