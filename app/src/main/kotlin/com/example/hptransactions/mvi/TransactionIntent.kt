package com.example.hptransactions.mvi

// ═══════════════════════════════════════════════════════════════════════════
// MVI — MODEL-VIEW-INTENT ARCHITECTURE
// ═══════════════════════════════════════════════════════════════════════════
// MVI enforces a strictly unidirectional data flow:
//
//   User action
//       │
//       ▼
//   UI emits an Intent  ──────────────────────────────────────────────────┐
//                                                                         │
//       ┌────────────────────────────────────────────────────────────────-┘
//       │
//       ▼
//   ViewModel.onIntent(intent) processes it
//       │
//       ▼
//   _state.update { … }  produces a new immutable State
//       │
//       ▼
//   StateFlow emits the new State
//       │
//       ▼
//   Compose collects and recomposes only what changed
//
// The UI NEVER mutates state directly. It only sends intents. This means:
//   - State changes are traceable: every mutation has a named intent as cause.
//   - The ViewModel can be tested without a UI: inject intents, assert state.
//   - The UI is a pure function of state: given the same State it always
//     renders the same pixels — no hidden mutable fields in Composables.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * The complete set of user actions that can change [TransactionState].
 *
 * WHY A SEALED INTERFACE?
 * A sealed interface (or sealed class) restricts which types can implement it
 * to those declared in the same file. This means:
 *   1. The compiler can verify that the `when` in ViewModel.onIntent() is
 *      exhaustive — no intent goes unhandled.
 *   2. Adding a new intent without updating onIntent() causes a compile error,
 *      not a silent runtime no-op.
 *   3. The complete user interaction surface is documented in one place.
 *
 * WHY NOT AN ENUM?
 * Enums cannot carry different payloads per variant. UpdateFilter needs a
 * Boolean argument; enums cannot have per-instance constructor parameters.
 * Sealed interfaces + data class / object achieve the same exhaustive matching
 * while supporting arbitrary per-variant data.
 */
sealed interface TransactionIntent {

    /**
     * Start the high-frequency transaction simulation.
     *
     * Triggers the producer coroutine (333 tx/sec) and the consumer coroutine
     * (drains ring buffer → actor → network throttle). Sets
     * [TransactionState.isSimulationRunning] = true.
     *
     * If the simulation is already running this intent is a no-op (the
     * ViewModel guards with an early return).
     */
    object StartSimulation : TransactionIntent

    /**
     * Halt the simulation immediately.
     *
     * Cancels both the producer and consumer coroutines. In-flight network
     * calls (inside ConcurrencyManager.withThrottle) are allowed to complete
     * because they run in separate child coroutines of viewModelScope, not of
     * the consumer job. Sets [TransactionState.isSimulationRunning] = false.
     */
    object StopSimulation : TransactionIntent

    /**
     * Discard all transactions and reset the counters.
     *
     * Sends a [com.example.hptransactions.actor.ClearAll] message to the actor.
     * The actor processes it sequentially after any pending AddTransaction or
     * UpdateStatus messages that are already queued — ensuring the list is
     * cleared in the correct order without losing any in-flight update.
     *
     * The counter reset (totalProcessed = 0, droppedCount = 0) happens inside
     * the actor's onStateChange callback when it observes an empty list, so
     * the UI always sees counters and list change atomically.
     */
    object ClearAll : TransactionIntent

    /**
     * Toggle the failed-only filter.
     *
     * @param onlyFailed true  → [TransactionState.filteredTransactions] contains
     *                          only FAILED transactions.
     *                   false → [TransactionState.filteredTransactions] mirrors
     *                          the full transaction list.
     *
     * WHY A DATA CLASS (NOT OBJECT)?
     * This intent carries a payload ([onlyFailed]). A `data class` is the
     * idiomatic choice: it gets equals/hashCode/copy/toString for free, and
     * the sealed interface still enforces exhaustive matching.
     */
    data class UpdateFilter(val onlyFailed: Boolean) : TransactionIntent
}
