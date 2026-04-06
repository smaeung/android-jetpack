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

// Simulated merchant names — cycled through in order so the list looks realistic.
private val MERCHANTS = listOf(
    "Amazon", "Apple", "Netflix", "Spotify", "Uber", "Stripe",
    "PayPal", "Shopify", "Square", "Braintree"
)

/**
 * Orchestrator ViewModel that wires all five high-performance patterns together.
 *
 * Responsibilities:
 *   1. Own the single [StateFlow<TransactionState>] — the UI's only data source.
 *   2. Own the [TransactionRingBuffer] — absorbs producer bursts (Pattern 1).
 *   3. Own the [transactionActor] — serialises list mutations (Pattern 2).
 *   4. Own the [ConcurrencyManager] — controls lock/throttle (Pattern 3).
 *   5. Process [TransactionIntent]s from the UI and drive state transitions (Pattern 5).
 *   6. Keep the UI free by running all work on [viewModelScope] coroutines.
 *
 * WHY EXTEND ViewModel?
 * ViewModel survives configuration changes (screen rotation). If this were a
 * plain class, rotating the device would destroy and recreate the producer/consumer
 * coroutines, the ring buffer, and the actor — losing all in-flight transactions.
 * ViewModel ensures a single long-lived instance per screen.
 */
class TransactionViewModel : ViewModel() {

    // ─── PATTERN 5: Single immutable StateFlow ───────────────────────────────
    //
    // _state is private and mutable — only this class can write to it.
    // `state` is exposed publicly as a read-only StateFlow via asStateFlow().
    //
    // WHY asStateFlow() INSTEAD OF EXPOSING _state DIRECTLY?
    // MutableStateFlow allows external callers to call _state.value = … which
    // would bypass the MVI contract. asStateFlow() wraps it in a read-only
    // interface — callers can only collect, never emit.
    //
    // WHY StateFlow INSTEAD OF LiveData?
    // StateFlow is coroutine-native, supports structured concurrency, has a
    // deterministic initial value, and collectAsStateWithLifecycle() is
    // lifecycle-aware out of the box. LiveData requires observeAsState() which
    // is more verbose and less composable.
    private val _state = MutableStateFlow(TransactionState())
    val state: StateFlow<TransactionState> = _state.asStateFlow()

    // ─── PATTERN 1: Ring buffer ───────────────────────────────────────────────
    // Capacity 200: at 333 tx/sec this covers ~600 ms of burst without dropping.
    // DROP_OLDEST ensures the producer never blocks and the UI sees fresh data.
    private val ringBuffer = TransactionRingBuffer(capacity = 200)

    // ─── PATTERNS 1 & 3: Concurrency helpers ─────────────────────────────────
    // Mutex:     protects droppedCount increments against concurrent races.
    // Semaphore: caps simultaneous simulated network calls at 5.
    private val concurrency = ConcurrencyManager(maxConcurrentRequests = 5)

    // ─── PATTERN 2: Channel-based actor ──────────────────────────────────────
    //
    // The actor is initialised immediately at ViewModel construction time so it
    // is ready to receive messages as soon as the first intent arrives.
    //
    // The onStateChange lambda:
    //   - Receives an immutable snapshot from the actor after each mutation.
    //   - Calls _state.update {} to atomically replace the current state.
    //   - update {} is a compare-and-set loop: if another coroutine updated
    //     the state concurrently, this update retries with the latest value.
    //     No manual locking needed.
    //   - Resets droppedCount to 0 when the list becomes empty (ClearAll).
    //   - Calls recomputeFiltered() so filteredTransactions stays in sync.
    private val actor: SendChannel<TransactionMessage> =
        viewModelScope.transactionActor { updatedList ->
            _state.update { s ->
                s.copy(
                    transactions   = updatedList,
                    totalProcessed = updatedList.size,
                    // Reset droppedCount atomically with the list clear so the
                    // UI never shows "Processed: 0, Dropped: 47" simultaneously.
                    droppedCount   = if (updatedList.isEmpty()) 0 else s.droppedCount
                )
            }
            recomputeFiltered()
        }

    // Handles to the running producer/consumer jobs so they can be cancelled on demand.
    private var producerJob: Job? = null
    private var consumerJob: Job? = null

    // ─── INTENT DISPATCH ─────────────────────────────────────────────────────
    /**
     * Single entry point for all UI actions.
     *
     * The `when` is over a sealed interface, so the compiler enforces that
     * every [TransactionIntent] subtype is handled — no silent no-ops possible.
     */
    fun onIntent(intent: TransactionIntent) {
        when (intent) {
            TransactionIntent.StartSimulation -> startSimulation()
            TransactionIntent.StopSimulation  -> stopSimulation()
            TransactionIntent.ClearAll        -> clearAll()
            is TransactionIntent.UpdateFilter -> updateFilter(intent.onlyFailed)
        }
    }

    // ─── PRIVATE HELPERS ─────────────────────────────────────────────────────

    private fun startSimulation() {
        // Idempotency guard: ignore if already running.
        if (_state.value.isSimulationRunning) return
        _state.update { it.copy(isSimulationRunning = true, errorMessage = null) }

        // ── PRODUCER COROUTINE ───────────────────────────────────────────────
        // Generates 2 000 transactions at 1 per 3 ms (~333/sec).
        //
        // WHY delay(3) AND NOT Thread.sleep(3)?
        // Thread.sleep() blocks the OS thread for 3 ms on every iteration.
        // At 333 iterations/second that is 1 000 ms of thread-blocking per
        // second — effectively a dedicated thread doing nothing but sleeping.
        // coroutines delay() suspends the coroutine, releasing the thread to
        // handle other coroutines during the wait. Zero thread waste.
        //
        // WHY tryEmit() AND NOT emit() (SUSPEND)?
        // emit() would pause the producer whenever the ring buffer is full,
        // coupling producer speed to consumer speed. tryEmit() never suspends:
        // if the buffer is full, DROP_OLDEST silently makes room.
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
                    // Under DROP_OLDEST this branch is never actually reached
                    // (tryEmit always returns true). It is kept as a defensive
                    // counter for if the overflow policy ever changes in tests.
                    concurrency.withExclusiveAccess {
                        _state.update { it.copy(droppedCount = it.droppedCount + 1) }
                    }
                }
                delay(3)
            }
        }

        // ── CONSUMER COROUTINE ───────────────────────────────────────────────
        // Drains the ring buffer and dispatches each transaction to:
        //   (a) the actor — for list mutation (sequential, race-free)
        //   (b) a throttled network call — limited to 5 concurrent by Semaphore
        consumerJob = viewModelScope.launch {
            ringBuffer.flow.collect { tx ->
                // (a) Actor receives AddTransaction. The actor's internal coroutine
                // processes it when it finishes the current message. Channel.UNLIMITED
                // means this send() call never suspends.
                actor.send(AddTransaction(tx))

                // (b) Launch a child coroutine for the network call so it does
                // not block the consumer from collecting the next ring-buffer item.
                // Each network call runs concurrently, but the Semaphore inside
                // withThrottle() ensures at most 5 run simultaneously.
                launch {
                    concurrency.withThrottle {
                        // Atomic increment: _state.update is a CAS loop, so two
                        // coroutines entering simultaneously will correctly both
                        // increment by 1, never colliding on a stale read.
                        _state.update { it.copy(activeNetworkCalls = it.activeNetworkCalls + 1) }
                        simulateNetworkCall(tx)
                        // Decrement with coerceAtLeast(0) guards against the
                        // theoretical case where the counter goes negative due to
                        // a coroutine cancellation arriving mid-update.
                        _state.update { it.copy(activeNetworkCalls = (it.activeNetworkCalls - 1).coerceAtLeast(0)) }
                    }
                }
            }
        }

        // ── AUTO-STOP ────────────────────────────────────────────────────────
        // When the producer finishes its 2 000 iterations, cancel the consumer
        // (which would otherwise block forever waiting for the ring buffer) and
        // update the running flag. join() suspends until producerJob completes.
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

    /**
     * Send ClearAll to the actor rather than calling _state.update directly.
     *
     * WHY ROUTE THROUGH THE ACTOR?
     * The actor's Channel is ordered. If AddTransaction and UpdateStatus
     * messages are already queued, ClearAll will process AFTER them — the list
     * is cleared after all pending mutations complete, not in the middle of one.
     * If we called _state.update directly from here, the UI might briefly see
     * an empty list while the actor still has queued mutations that will
     * re-populate it, causing a visible flicker.
     *
     * The import alias `ActorClearAll` avoids ambiguity with
     * TransactionIntent.ClearAll which is also in scope.
     */
    private fun clearAll() {
        viewModelScope.launch {
            actor.send(ActorClearAll)
        }
    }

    private fun updateFilter(onlyFailed: Boolean) {
        // update {} is atomic: no other coroutine can observe a state where
        // showOnlyFailed is updated but filteredTransactions is not.
        _state.update { it.copy(showOnlyFailed = onlyFailed) }
        // Recompute the filtered list immediately using the just-updated flag.
        recomputeFiltered()
    }

    /**
     * Recomputes [TransactionState.filteredTransactions] based on the current
     * values of [TransactionState.showOnlyFailed] and [TransactionState.transactions].
     *
     * Called from two sites:
     *   1. Actor's onStateChange — after any list mutation (add/update/clear).
     *   2. updateFilter() — after the filter flag changes.
     *
     * Storing the result in state means Composables pay the filter cost only
     * when the data actually changes, not on every recomposition.
     */
    private fun recomputeFiltered() {
        _state.update { s ->
            s.copy(
                filteredTransactions = if (s.showOnlyFailed) {
                    s.transactions.filter { it.status == TransactionStatus.FAILED }
                } else {
                    s.transactions
                }
            )
        }
    }

    /**
     * Simulates an asynchronous network confirmation call.
     *
     * Delays 50–200 ms to represent realistic network latency, then assigns
     * a final status: 85% COMPLETED, 15% FAILED. Sends the result back to the
     * actor as an UpdateStatus message so the status transition is serialised
     * along with all other list mutations.
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

    /**
     * Called by the framework when this ViewModel is about to be destroyed
     * (e.g. the screen is popped from the back stack).
     *
     * Closing the actor's channel signals the `for (message in channel)` loop
     * inside transactionActor to terminate cleanly — no resource leak.
     * viewModelScope is cancelled automatically by the ViewModel framework,
     * which cancels producerJob and consumerJob as well.
     */
    override fun onCleared() {
        super.onCleared()
        actor.close()
    }
}
