package com.example.hptransactions.actor

import com.example.hptransactions.data.Transaction
import com.example.hptransactions.data.TransactionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
// MESSAGE PROTOCOL — WHY A SEALED INTERFACE?
// ═══════════════════════════════════════════════════════════════════════════
// A sealed interface defines a closed set of types. The Kotlin compiler can
// then verify that every `when` expression over TransactionMessage is
// exhaustive. If a new message type is added here but not handled in the
// actor's `when` block, the code will NOT COMPILE — the bug is caught at
// compile time, not at runtime in production.
//
// This is the "sealed command" pattern from Domain-Driven Design: every
// mutation that the list can undergo is an explicit, named message.
// There is no way to accidentally mutate state by bypassing the protocol.
// ═══════════════════════════════════════════════════════════════════════════

/** The complete set of mutations the actor's list can undergo. */
sealed interface TransactionMessage

/**
 * Prepend [transaction] to the head of the list (newest-first ordering).
 *
 * WHY PREPEND INSTEAD OF APPEND?
 * The UI displays the most recent transaction at the top. Prepending means
 * index 0 is always the latest — no sorting needed. The LazyColumn with
 * stable keys can animate items downward as new ones appear at the top.
 */
data class AddTransaction(val transaction: Transaction) : TransactionMessage

/**
 * Update the [status] of the transaction identified by [id].
 *
 * WHY A SEPARATE MESSAGE INSTEAD OF REPLACING THE WHOLE ITEM?
 * The network confirmation arrives asynchronously (50–200 ms after the
 * transaction was added). By the time it arrives, the item may have shifted
 * position in the list. Identifying it by UUID and mutating only the status
 * field is both correct and cheap — no list reconstruction needed.
 */
data class UpdateStatus(val id: String, val status: TransactionStatus) : TransactionMessage

/**
 * Discard every item in the list and reset to empty.
 *
 * WHY AN OBJECT (SINGLETON)?
 * ClearAll carries no payload. A Kotlin `object` is the idiomatic way to
 * represent a zero-arity message — it creates a single shared instance,
 * avoiding an allocation on every clear request.
 */
object ClearAll : TransactionMessage

// ═══════════════════════════════════════════════════════════════════════════
// THE ACTOR PATTERN — WHY USE IT FOR MUTABLE STATE?
// ═══════════════════════════════════════════════════════════════════════════
//
// The naive approach to a shared mutable list is synchronisation:
//
//   // OPTION A: synchronized block — WRONG on Android
//   synchronized(list) { list.add(tx) }   // blocks the OS thread
//                                           // starves other coroutines
//                                           // causes UI jank
//
//   // OPTION B: Mutex.withLock — better, but verbose
//   mutex.withLock { list.add(tx) }        // suspends coroutine (safe)
//                                           // every call site needs the lock
//                                           // easy to forget
//
//   // OPTION C: Actor — BEST
//   actor.send(AddTransaction(tx))          // send is non-blocking
//                                           // the actor serialises all
//                                           // access automatically
//                                           // call sites need no lock
//
// The actor pattern confines all mutable state to a SINGLE coroutine. Because
// a coroutine executes one thing at a time, concurrent access to the list is
// structurally impossible — no lock is needed at all.
//
// HOW IT WORKS:
//   1. Multiple producers call actor.send(message) concurrently.
//   2. Messages queue up in the actor's Channel.
//   3. One coroutine processes them sequentially: read, mutate, emit snapshot.
//   4. The snapshot (an immutable copy) is passed to onStateChange().
//   5. onStateChange() updates the StateFlow — the UI recomposes.
//
// WHY Channel + launch INSTEAD OF actor {}?
// Kotlin's built-in actor {} builder is annotated @ObsoleteCoroutinesApi and
// may be removed in a future version of kotlinx-coroutines. The Channel +
// launch pattern is semantically identical and uses only stable APIs.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Creates and starts a Channel-based actor that serialises all mutations to
 * a private mutable transaction list.
 *
 * @param onStateChange Callback invoked after every successful mutation with
 *   an immutable snapshot of the current list. The ViewModel uses this to
 *   push the new list into its StateFlow.
 * @return The [SendChannel] through which callers send [TransactionMessage]s.
 *   Callers should call [SendChannel.close] when done to terminate the actor
 *   coroutine cleanly (done in ViewModel.onCleared).
 */
fun CoroutineScope.transactionActor(
    onStateChange: (List<Transaction>) -> Unit
): SendChannel<TransactionMessage> {

    // Channel.UNLIMITED: producers never suspend while sending.
    // Back-pressure is handled upstream by the ring buffer. Messages that
    // survive the ring buffer must not be dropped here — UNLIMITED ensures they
    // are all delivered to the actor for processing.
    val channel = Channel<TransactionMessage>(capacity = Channel.UNLIMITED)

    launch {
        // ─── MUTABLE STATE CONFINED TO THIS COROUTINE ───────────────────────
        // Nothing outside this launch block can ever touch `list` directly.
        // All access goes through the channel protocol above.
        val list = mutableListOf<Transaction>()

        // for-in on a Channel suspends when empty and resumes when a new
        // message arrives. It exits cleanly when the channel is closed.
        for (message in channel) {
            when (message) {

                is AddTransaction -> {
                    // Add to position 0 so the list stays newest-first.
                    list.add(0, message.transaction)

                    // Safety cap: if the actor falls behind (e.g. under extreme
                    // load) the list could grow indefinitely. 500 items is enough
                    // for a practical UI — the oldest item is pruned.
                    if (list.size > 500) list.removeAt(list.lastIndex)
                }

                is UpdateStatus -> {
                    // Locate by UUID. If the transaction was pruned (older than
                    // position 500) or never existed, indexOfFirst returns -1.
                    // The guard prevents an IndexOutOfBoundsException.
                    val index = list.indexOfFirst { it.id == message.id }
                    if (index != -1) {
                        // data class copy() creates a new Transaction with only
                        // `status` changed — all other fields stay the same.
                        list[index] = list[index].copy(status = message.status)
                    }
                }

                is ClearAll -> list.clear()
            }

            // toList() produces a NEW immutable List<Transaction> backed by a
            // separate array. The actor's mutable list continues to be owned
            // solely by this coroutine. The snapshot is safe to read from any
            // thread — including the Main thread in the StateFlow update.
            onStateChange(list.toList())
        }
    }

    return channel
}
