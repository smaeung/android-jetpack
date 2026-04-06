package com.example.hptransactions.mvi

import com.example.hptransactions.data.Transaction

/**
 * Single, immutable snapshot of everything the UI needs to render.
 *
 * Exposing one [TransactionState] from the ViewModel (instead of multiple
 * individual StateFlows) guarantees the UI always sees a consistent, atomic
 * view of the world — no partial updates where [transactions] is updated but
 * [totalProcessed] is not yet.
 *
 * StateFlow performs *conflation*: if the UI is slow to consume updates, older
 * states are dropped and only the latest is delivered — an implicit ring-buffer
 * of size 1.
 */
data class TransactionState(
    val transactions: List<Transaction> = emptyList(),
    val filteredTransactions: List<Transaction> = emptyList(),
    val isSimulationRunning: Boolean = false,
    val showOnlyFailed: Boolean = false,
    val totalProcessed: Int = 0,
    val droppedCount: Int = 0,
    val activeNetworkCalls: Int = 0,
    val errorMessage: String? = null
)
