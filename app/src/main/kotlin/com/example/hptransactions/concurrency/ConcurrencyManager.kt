package com.example.hptransactions.concurrency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Encapsulates two coroutine-aware synchronisation primitives:
 *   - [Mutex]     for exclusive access to shared mutable state
 *   - [Semaphore] for throttling the number of concurrent I/O operations
 *
 * ═══════════════════════════════════════════════════════════════════════
 * WHY "COROUTINE-AWARE" MATTERS
 * ═══════════════════════════════════════════════════════════════════════
 * Traditional Java primitives (synchronized, ReentrantLock, Semaphore from
 * java.util.concurrent) BLOCK the OS thread while waiting. On Android's
 * single Main thread, a 50 ms block = a dropped frame, a 500 ms block = ANR.
 *
 * Kotlin's coroutine primitives SUSPEND the coroutine instead of blocking
 * the thread. The suspended coroutine is parked in memory (cheap), and the
 * thread is immediately returned to the pool to do other work. When the
 * lock/permit becomes available, the coroutine is rescheduled. The net
 * effect: thread pool is never starved, the UI stays responsive.
 *
 *   Java synchronized (WRONG on Android under high load):
 *     Thread-1 holds lock → Thread-2, Thread-3, … all BLOCKED
 *     Thread pool exhausted → Main thread starves → ANR
 *
 *   Kotlin Mutex (CORRECT):
 *     Coroutine-1 holds lock → Coroutine-2, Coroutine-3, … SUSPENDED
 *     Threads are returned to pool → pool handles other work → no ANR
 * ═══════════════════════════════════════════════════════════════════════
 */
class ConcurrencyManager(maxConcurrentRequests: Int = 5) {

    // ─── MUTEX ──────────────────────────────────────────────────────────────
    // A Mutex is a MUTual EXclusion lock: only one coroutine can be inside the
    // protected block at a time. All others suspend until the lock is released.
    //
    // Used in this app to protect the `droppedCount` increment in the producer:
    //   Two concurrent coroutines both detect a ring-buffer miss at the same
    //   instant. Without a Mutex, both read droppedCount = 5, both compute 5+1,
    //   both write 6 — one increment is lost. With Mutex, only one increments
    //   at a time, so the final value is always correct.
    //
    // WHY NOT AtomicInteger INSTEAD?
    // AtomicInteger.incrementAndGet() is lock-free and even cheaper. However,
    // Mutex is shown here to demonstrate the primitive. In production, prefer
    // _state.update { it.copy(droppedCount = it.droppedCount + 1) } because
    // StateFlow.update() is itself a compare-and-set loop — no external lock needed.
    private val mutex = Mutex()

    // ─── SEMAPHORE ───────────────────────────────────────────────────────────
    // A Semaphore holds N "permits". Calling withPermit() acquires one permit
    // (suspending if none are available) and releases it after the block returns.
    //
    // maxConcurrentRequests = 5 means at most 5 simulated network calls run
    // simultaneously. The 6th caller suspends until one of the 5 finishes.
    //
    // WHY CAP AT 5?
    // At 333 transactions/second each needing a 50–200 ms network round-trip,
    // the steady-state queue of in-flight calls would be:
    //   333 tx/sec × 0.125 sec average = ~41 concurrent calls (uncapped)
    //
    // 41 concurrent HTTP connections would exhaust a typical OkHttp connection
    // pool (default: 5), causing connection starvation and request timeouts.
    // A Semaphore(5) matches the pool size, keeping throughput high without
    // overloading downstream infrastructure.
    //
    // The value is injected through the constructor so tests can use a
    // different limit without hardcoding it.
    private val semaphore = Semaphore(permits = maxConcurrentRequests)

    /**
     * Execute [block] with exclusive access — no other caller can be inside
     * [withExclusiveAccess] at the same time.
     *
     * The coroutine suspends (not blocks) if the Mutex is currently held.
     * The thread is free to run other coroutines while waiting.
     *
     * Typical use: protecting a counter or in-memory cache from concurrent writes.
     */
    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T =
        mutex.withLock { block() }

    /**
     * Execute [block] subject to the concurrency limit specified at construction.
     *
     * If all permits are taken, the calling coroutine suspends until one is
     * returned. This enforces a hard upper bound on concurrent I/O operations
     * without blocking any OS thread.
     *
     * Typical use: network calls, database writes, file I/O — any operation
     * that is expensive when done in parallel beyond a certain point.
     */
    suspend fun <T> withThrottle(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}
