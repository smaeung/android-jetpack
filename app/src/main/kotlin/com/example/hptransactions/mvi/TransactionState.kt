package com.example.hptransactions.mvi

import com.example.hptransactions.data.Transaction

// ═══════════════════════════════════════════════════════════════════════════
// WHY A SINGLE STATE CLASS?
// ═══════════════════════════════════════════════════════════════════════════
// A common anti-pattern is to expose multiple StateFlows from a ViewModel:
//
//   val transactions: StateFlow<List<Transaction>>
//   val isRunning:    StateFlow<Boolean>
//   val dropped:      StateFlow<Int>
//
// The problem: these flows update independently. Between the moment
// `transactions` emits and the moment `isRunning` emits, the UI observes
// a PARTIAL / INCONSISTENT snapshot — the list is updated but the running
// flag is not yet. This can cause rendering glitches, incorrect empty-state
// logic, or stale counters in the stats bar.
//
// With ONE StateFlow<TransactionState>, every _state.update { … } is an
// atomic compare-and-set. The UI always gets a fully-consistent snapshot.
// StateFlow also CONFLATES: if the UI is slow, it receives only the latest
// state — like a ring-buffer of size 1 — and never processes stale values.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Immutable snapshot of everything the UI needs to render the transaction feed.
 *
 * All fields are `val`. The only way to produce a new state is to call
 * `copy(...)` — which creates a fresh object with the changed fields. This
 * means:
 *   1. Compose's structural equality check (used by collectAsStateWithLifecycle)
 *      correctly identifies unchanged states and skips recomposition.
 *   2. No coroutine can mutate a state snapshot that another coroutine is
 *      currently reading — thread safety by construction.
 *   3. State history is trivially reproducible: each state is a complete,
 *      self-contained value with no hidden external references.
 */
data class TransactionState(

    /**
     * The full, unfiltered list of transactions owned by the actor.
     *
     * Ordered newest-first (index 0 = most recent). Capped at 500 items by the
     * actor to prevent unbounded memory growth during long simulations.
     *
     * WHY List<Transaction> AND NOT MutableList?
     * List<T> is a read-only interface. The actor emits list.toList() which
     * produces an ArrayList under the hood — callers can read but not mutate.
     * Attempting to cast to MutableList and calling add() would compile but
     * produce a different instance, not affecting the actor's internal list.
     */
    val transactions: List<Transaction> = emptyList(),

    /**
     * Pre-computed filtered view of [transactions], exposed to Composables.
     *
     * WHY A STORED FIELD INSTEAD OF A COMPUTED PROPERTY?
     * A computed property (get() = transactions.filter { … }) re-runs the
     * filter on every read. Both TransactionScreen and StatsBar access this
     * value on every recomposition — at 60 fps that is 120 filter passes per
     * second over a list of up to 500 items = 30,000 comparisons/second for
     * display logic alone.
     *
     * By storing the filtered result as a field and recomputing it once in
     * ViewModel.recomputeFiltered() whenever transactions or showOnlyFailed
     * changes, the filter runs only when the underlying data changes — not
     * on every frame.
     */
    val filteredTransactions: List<Transaction> = emptyList(),

    /**
     * Whether the producer/consumer coroutines are currently active.
     * Drives the Start/Stop button label and loading indicator visibility.
     */
    val isSimulationRunning: Boolean = false,

    /**
     * Whether the "Failed only" filter chip is active.
     * When true, [filteredTransactions] contains only FAILED transactions.
     */
    val showOnlyFailed: Boolean = false,

    /**
     * Running count of transactions that have been seen by the actor.
     * Resets to the current list size (which is ≤ 500) on each actor update
     * to reflect the cap. Resets to 0 on ClearAll.
     */
    val totalProcessed: Int = 0,

    /**
     * Number of transactions dropped by the ring buffer due to overflow.
     * Increments when ringBuffer.tryEmit() would have returned false under a
     * SUSPEND policy — with DROP_OLDEST it always returns true, so the producer
     * explicitly counts misses for observability. Resets to 0 on ClearAll.
     */
    val droppedCount: Int = 0,

    /**
     * Current number of simulated network calls in flight.
     * Bounded to [0, maxConcurrentRequests] by the Semaphore in ConcurrencyManager.
     * Displayed as "N/5" in StatsBar to show Semaphore throttling live.
     * Uses coerceAtLeast(0) on decrement to guard against decrement racing
     * an increment from a different coroutine.
     */
    val activeNetworkCalls: Int = 0,

    /**
     * Non-null when an error has occurred that the UI should surface.
     * Currently unused (the simulation is deterministic), reserved for
     * real network error handling in production extensions of this sample.
     */
    val errorMessage: String? = null
)
