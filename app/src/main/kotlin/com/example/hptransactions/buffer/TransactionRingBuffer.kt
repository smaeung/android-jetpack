package com.example.hptransactions.buffer

import com.example.hptransactions.data.Transaction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A ring buffer (circular buffer) built on top of [MutableSharedFlow].
 *
 * ═══════════════════════════════════════════════════════════════
 * WHAT IS A RING BUFFER AND WHY DO WE NEED ONE?
 * ═══════════════════════════════════════════════════════════════
 * A ring buffer is a fixed-capacity queue where writing past the end
 * automatically overwrites the oldest entry, keeping memory usage constant
 * regardless of how many items are produced.
 *
 * In this app the producer emits ~333 transactions/second. The Compose UI
 * recomposes at most 60 times/second. If unconstrained, the gap between
 * producer speed and UI render speed grows without bound, eventually causing:
 *   - OutOfMemoryError from the accumulated backlog
 *   - UI showing transactions from seconds ago instead of right now
 *   - Increasing latency as the consumer works through stale events
 *
 * The ring buffer solves all three: once full, the oldest (most stale) event
 * is silently discarded to make room for the newest. The UI always receives
 * the most recent data, and memory usage is bounded by [capacity].
 *
 * ═══════════════════════════════════════════════════════════════
 * WHY MutableSharedFlow INSTEAD OF Channel?
 * ═══════════════════════════════════════════════════════════════
 * Channel is designed for point-to-point communication (one sender, one
 * receiver). SharedFlow supports multiple concurrent collectors and exposes
 * a clean Flow<T> API that integrates naturally with Compose's
 * collectAsStateWithLifecycle(). It also separates the write API (tryEmit)
 * from the read API (asSharedFlow()), enforcing encapsulation.
 *
 * ═══════════════════════════════════════════════════════════════
 * THE THREE KEY SharedFlow PARAMETERS EXPLAINED
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. replay = 0
 *    New collectors do NOT receive any cached/historical events.
 *    WHY: After a screen rotation or process death, the new collector should
 *    receive only live events. Replaying hundreds of stale transactions to a
 *    freshly-created collector would cause a burst recomposition storm and
 *    show data that is already outdated.
 *
 * 2. extraBufferCapacity = capacity
 *    The internal buffer that holds events between the moment tryEmit() is
 *    called and the moment the collector coroutine processes them.
 *    WHY 200 (default): at 333 events/sec, 200 slots covers ~600 ms of
 *    producer output — more than enough slack for a Compose frame budget
 *    of 16 ms. Increasing this widens the safety margin; decreasing it
 *    increases the drop rate under sustained load.
 *
 * 3. onBufferOverflow = BufferOverflow.DROP_OLDEST
 *    When all [extraBufferCapacity] slots are full and a new event arrives:
 *      DROP_OLDEST → discard the oldest buffered event, accept the new one.
 *      DROP_LATEST → discard the new event (we do NOT want this — stale data).
 *      SUSPEND     → suspend the producer until space is available (defeats
 *                    the purpose — the producer would block the UI thread).
 *    With DROP_OLDEST, tryEmit() ALWAYS returns true and NEVER suspends.
 *    The producer can run at any speed without back-pressure ever reaching it.
 *
 * ═══════════════════════════════════════════════════════════════
 * VISUAL: what happens when capacity = 3 and a 4th event arrives
 * ═══════════════════════════════════════════════════════════════
 *
 *   Buffer before:  [ TX-001 | TX-002 | TX-003 ]
 *                      oldest            newest
 *
 *   tryEmit(TX-004) called — buffer is full:
 *     TX-001 is evicted (oldest, most stale)
 *     TX-004 is inserted at the end
 *
 *   Buffer after:   [ TX-002 | TX-003 | TX-004 ]
 */
class TransactionRingBuffer(capacity: Int = 200) {

    private val _flow = MutableSharedFlow<Transaction>(
        replay = 0,                                    // no history for late collectors
        extraBufferCapacity = capacity,                // ring size
        onBufferOverflow = BufferOverflow.DROP_OLDEST  // ring-buffer eviction policy
    )

    /**
     * Read-only view of the buffer exposed to consumers (the ViewModel).
     *
     * Returning [asSharedFlow()] instead of [_flow] directly ensures that no
     * caller outside this class can emit into the buffer — write access is
     * restricted to [tryEmit].
     */
    val flow = _flow.asSharedFlow()

    /**
     * Attempt to place [transaction] into the ring buffer.
     *
     * With [BufferOverflow.DROP_OLDEST] this call NEVER suspends and ALWAYS
     * returns true — the overflow policy guarantees space is made available
     * by evicting the oldest item if necessary.
     *
     * The caller (TransactionViewModel) still checks the return value so it
     * can increment the [droppedCount] counter for observability — but
     * functionally the Boolean is always true under DROP_OLDEST.
     *
     * WHY tryEmit INSTEAD OF emit (suspend)?
     * The producer runs inside viewModelScope.launch{} on the Default dispatcher.
     * Using suspending emit() would pause the producer coroutine whenever the
     * buffer is full, creating an implicit coupling between producer speed and
     * consumer speed. tryEmit() keeps the producer completely free-running —
     * back-pressure is handled entirely by the DROP_OLDEST policy.
     */
    fun tryEmit(transaction: Transaction): Boolean = _flow.tryEmit(transaction)
}
