package com.example.hptransactions.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hptransactions.actor.AddTransaction
import com.example.hptransactions.actor.ClearAll as ActorClearAll
import com.example.hptransactions.actor.TransactionMessage
import com.example.hptransactions.actor.UpdateStatus
import com.example.hptransactions.actor.transactionActor
import com.example.hptransactions.buffer.TransactionRingBuffer
import com.example.hptransactions.concurrency.ConcurrencyManager
import com.example.hptransactions.data.Transaction
import com.example.hptransactions.data.TransactionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

private val MERCHANTS = listOf(
    "Amazon", "Apple", "Netflix", "Spotify", "Uber", "Stripe",
    "PayPal", "Shopify", "Square", "Braintree"
)

class TransactionViewModel : ViewModel() {

    // ── Single source of truth ────────────────────────────────────────────────
    // StateFlow conflates: if Compose is slow the UI gets the LATEST state only,
    // acting as a ring-buffer of size 1.
    private val _state = MutableStateFlow(TransactionState())
    val state: StateFlow<TransactionState> = _state.asStateFlow()

    // ── Ring buffer (capacity 200, DROP_OLDEST on overflow) ───────────────────
    private val ringBuffer = TransactionRingBuffer(capacity = 200)

    // ── Concurrency helpers ───────────────────────────────────────────────────
    // Semaphore: max 5 simulated network calls at once
    // Mutex:     protects the droppedCount counter
    private val concurrency = ConcurrencyManager(maxConcurrentRequests = 5)

    // ── Actor — owns the mutable list, processes msgs one-at-a-time ──────────
    private val actor: SendChannel<TransactionMessage> =
        viewModelScope.transactionActor { updatedList ->
            _state.update { s ->
                s.copy(
                    transactions = updatedList,
                    totalProcessed = updatedList.size,
                    droppedCount = if (updatedList.isEmpty()) 0 else s.droppedCount
                )
            }
            recomputeFiltered()
        }

    // Running jobs so we can cancel them on StopSimulation
    private var producerJob: Job? = null
    private var consumerJob: Job? = null

    // ── Intent handler ────────────────────────────────────────────────────────
    fun onIntent(intent: TransactionIntent) {
        when (intent) {
            TransactionIntent.StartSimulation -> startSimulation()
            TransactionIntent.StopSimulation  -> stopSimulation()
            TransactionIntent.ClearAll        -> clearAll()
            is TransactionIntent.UpdateFilter -> updateFilter(intent.onlyFailed)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun startSimulation() {
        if (_state.value.isSimulationRunning) return
        _state.update { it.copy(isSimulationRunning = true, errorMessage = null) }

        // Producer: emits ~333 transactions/second (1 every 3 ms).
        // tryEmit() never suspends — DROP_OLDEST handles back-pressure in the buffer.
        producerJob = viewModelScope.launch {
            repeat(2000) { index ->
                val tx = Transaction(
                    id          = UUID.randomUUID().toString(),
                    amount      = Random.nextDouble(1.0, 9999.0),
                    merchant    = MERCHANTS[index % MERCHANTS.size],
                    status      = TransactionStatus.PENDING,
                    timestampMs = System.currentTimeMillis()
                )
                val emitted = ringBuffer.tryEmit(tx)
                if (!emitted) {
                    // Ring buffer was full — oldest event was dropped
                    concurrency.withExclusiveAccess {
                        _state.update { it.copy(droppedCount = it.droppedCount + 1) }
                    }
                }
                delay(3) // ~333 events/sec
            }
        }

        // Consumer: drains the ring buffer and forwards each transaction to
        // the actor for sequential, race-free list mutation.
        consumerJob = viewModelScope.launch {
            ringBuffer.flow.collect { tx ->
                actor.send(AddTransaction(tx))

                // Simulate a network confirmation call, throttled to 5 concurrent.
                launch {
                    concurrency.withThrottle {
                        _state.update { it.copy(activeNetworkCalls = it.activeNetworkCalls + 1) }
                        simulateNetworkCall(tx)
                        _state.update { it.copy(activeNetworkCalls = (it.activeNetworkCalls - 1).coerceAtLeast(0)) }
                    }
                }
            }
        }

        // Auto-stop when producer finishes
        viewModelScope.launch {
            producerJob?.join()
            consumerJob?.cancel()
            _state.update { it.copy(isSimulationRunning = false) }
        }
    }

    private fun stopSimulation() {
        producerJob?.cancel()
        consumerJob?.cancel()
        _state.update { it.copy(isSimulationRunning = false) }
    }

    private fun clearAll() {
        viewModelScope.launch {
            actor.send(ActorClearAll)
        }
    }

    private fun updateFilter(onlyFailed: Boolean) {
        _state.update { it.copy(showOnlyFailed = onlyFailed) }
        recomputeFiltered()
    }

    private fun recomputeFiltered() {
        _state.update { s ->
            s.copy(filteredTransactions = if (s.showOnlyFailed) {
                s.transactions.filter { it.status == TransactionStatus.FAILED }
            } else {
                s.transactions
            })
        }
    }

    /**
     * Simulates a network round-trip (50–200 ms).
     * After "confirmation", the actor updates the transaction's status.
     */
    private suspend fun simulateNetworkCall(tx: Transaction) {
        delay(Random.nextLong(50, 200))
        val newStatus = if (Random.nextFloat() < 0.15f) {
            TransactionStatus.FAILED
        } else {
            TransactionStatus.COMPLETED
        }
        actor.send(UpdateStatus(tx.id, newStatus))
    }

    override fun onCleared() {
        super.onCleared()
        actor.close()
    }
}
