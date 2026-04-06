package com.example.hptransactions.actor

import com.example.hptransactions.data.Transaction
import com.example.hptransactions.data.TransactionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/**
 * Messages the actor can handle.
 *
 * Modelling commands as a sealed interface keeps the message contract explicit
 * and exhaustive — the compiler will warn if you forget a branch in the when().
 */
sealed interface TransactionMessage

data class AddTransaction(val transaction: Transaction) : TransactionMessage
data class UpdateStatus(val id: String, val status: TransactionStatus) : TransactionMessage
object ClearAll : TransactionMessage

/**
 * Creates a Channel-based actor that owns the mutable transaction list.
 *
 * Messages are processed ONE AT A TIME by a single launched coroutine.
 * Because a single coroutine can only do one thing at a time, shared mutable
 * state is confined to this coroutine — no Mutex needed here, zero race conditions
 * by design.
 *
 * [onStateChange] is called with an immutable snapshot after every mutation so
 * the ViewModel can push the new list into StateFlow.
 *
 * capacity = Channel.UNLIMITED means producers are never suspended while sending;
 * backpressure is handled at the ring-buffer level upstream.
 */
fun CoroutineScope.transactionActor(
    onStateChange: (List<Transaction>) -> Unit
): SendChannel<TransactionMessage> {
    val channel = Channel<TransactionMessage>(capacity = Channel.UNLIMITED)

    launch {
        // Mutable state is safely confined to this single coroutine.
        val list = mutableListOf<Transaction>()

        for (message in channel) {
            when (message) {
                is AddTransaction -> {
                    list.add(0, message.transaction)       // newest first
                    if (list.size > 500) list.removeAt(list.lastIndex) // cap list size
                }
                is UpdateStatus -> {
                    val index = list.indexOfFirst { it.id == message.id }
                    if (index != -1) {
                        list[index] = list[index].copy(status = message.status)
                    }
                }
                is ClearAll -> list.clear()
            }

            // Emit an immutable copy — callers cannot mutate the list we own.
            onStateChange(list.toList())
        }
    }

    return channel
}
