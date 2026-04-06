package com.example.hptransactions.mvi

/**
 * All user actions that can change the transaction feed state.
 *
 * In MVI the UI only ever sends intents — it never mutates state directly.
 * This makes the data flow strictly one-directional:
 *   UI  →  Intent  →  ViewModel  →  State  →  UI
 */
sealed interface TransactionIntent {
    /** Begin the high-frequency transaction simulation. */
    object StartSimulation : TransactionIntent

    /** Halt the simulation coroutine. */
    object StopSimulation : TransactionIntent

    /** Discard all transactions in the list. */
    object ClearAll : TransactionIntent

    /** Show only failed transactions when [onlyFailed] is true. */
    data class UpdateFilter(val onlyFailed: Boolean) : TransactionIntent
}
