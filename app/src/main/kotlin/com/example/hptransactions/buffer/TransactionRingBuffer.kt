package com.example.hptransactions.buffer

import com.example.hptransactions.data.Transaction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Ring buffer implemented with MutableSharedFlow.
 *
 * Key settings:
 *  - replay = 0            : new collectors do NOT receive historical events
 *  - extraBufferCapacity   : how many items can queue up before the overflow policy kicks in
 *  - DROP_OLDEST           : when the buffer is full the oldest un-consumed event is discarded,
 *                            making room for the newest one — classic ring-buffer semantics.
 *
 * This means the Compose UI always sees the freshest transaction data without ever running
 * out of memory, even if 1 000s of events arrive in a burst.
 */
class TransactionRingBuffer(capacity: Int = 200) {

    private val _flow = MutableSharedFlow<Transaction>(
        replay = 0,
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Read-only view consumed by the ViewModel. */
    val flow = _flow.asSharedFlow()

    /**
     * Emit a new transaction into the buffer.
     * Never suspends — excess events are silently dropped (oldest first).
     */
    fun tryEmit(transaction: Transaction): Boolean = _flow.tryEmit(transaction)
}
