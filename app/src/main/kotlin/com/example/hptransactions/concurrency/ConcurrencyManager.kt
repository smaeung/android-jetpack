package com.example.hptransactions.concurrency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Provides two concurrency primitives used when processing high-frequency transactions.
 *
 * ## Mutex — exclusive access to shared mutable state
 * Unlike Java's `synchronized`, Kotlin's [Mutex] merely *suspends* the coroutine
 * while waiting for the lock. The thread is released back to the pool, keeping the
 * UI responsive even under heavy write contention.
 *
 * ## Semaphore — throttle concurrent network / I/O calls
 * A [Semaphore] with N permits ensures at most N coroutines execute the protected
 * block simultaneously. This prevents thread starvation and avoids flooding the
 * network with thousands of simultaneous requests.
 */
class ConcurrencyManager(maxConcurrentRequests: Int = 5) {

    private val mutex = Mutex()
    private val semaphore = Semaphore(permits = maxConcurrentRequests)

    /**
     * Run [block] with exclusive access to shared state.
     * All other callers suspend (not block) until the lock is released.
     *
     * Use this for in-memory writes that must not interleave, such as
     * updating an aggregated stats counter.
     */
    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T =
        mutex.withLock { block() }

    /**
     * Run [block] subject to the concurrency limit set at construction time.
     * Excess callers suspend until a permit is available.
     *
     * Use this for network calls, DB writes, or any I/O where you want
     * a hard cap on parallelism.
     */
    suspend fun <T> withThrottle(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}
