package com.example.hptransactions.data

/**
 * Immutable domain model representing a single payment transaction.
 *
 * WHY A DATA CLASS?
 * Kotlin data classes auto-generate structural equality (equals/hashCode based on
 * field values, not object identity). This is critical for StateFlow: Compose's
 * collectAsStateWithLifecycle() uses equality to decide whether recomposition is
 * needed. If two successive state snapshots contain a Transaction with the same
 * field values, Compose skips recomposition for that item — a free optimisation.
 *
 * WHY ALL FIELDS ARE `val` (IMMUTABLE)?
 * The actor coroutine owns the only mutable reference (mutableListOf<Transaction>).
 * Every time the actor emits a snapshot it calls list.toList(), producing an
 * immutable List<Transaction>. If Transaction itself had `var` fields, a caller
 * could mutate a "snapshot" item while the actor is preparing the next one,
 * creating a data race with no compile-time protection. val fields make this
 * class inherently thread-safe by construction.
 *
 * WHY A UUID STRING FOR [id]?
 * LazyColumn's `key` parameter requires a stable, unique identity per item so
 * Compose can track which item moved, appeared, or disappeared across
 * recompositions. A UUID generated at creation time is:
 *   - Guaranteed unique across the entire session (no collisions)
 *   - Independent of list position (stable even when the item shifts index)
 *   - Serialisable to/from JSON without extra mapping
 * Without stable keys, prepending a single new item forces Compose to destroy
 * and recreate every visible row composable — a catastrophic performance cliff
 * at 300+ transactions/second.
 */
data class Transaction(
    /** Universally-unique identifier. Used as LazyColumn item key. */
    val id: String,

    /** Transaction amount in USD cents, stored as Double for display precision. */
    val amount: Double,

    /** Merchant name — one of the 10 simulated payment terminals. */
    val merchant: String,

    /**
     * Lifecycle status of this transaction.
     * Starts as PENDING, transitions to COMPLETED or FAILED after network confirmation.
     * The actor receives an UpdateStatus message to apply the transition atomically.
     */
    val status: TransactionStatus,

    /** Wall-clock timestamp of when the transaction was created, in milliseconds. */
    val timestampMs: Long
)

/**
 * Three-state lifecycle for a transaction.
 *
 * PENDING   → transaction created by producer, not yet confirmed by network
 * COMPLETED → network call returned success (85% probability in the simulation)
 * FAILED    → network call returned error  (15% probability in the simulation)
 *
 * The status drives the colour of the status badge in TransactionItem:
 *   PENDING   → amber  (#FFA726)
 *   COMPLETED → green  (#66BB6A)
 *   FAILED    → red    (#EF5350)
 */
enum class TransactionStatus { PENDING, COMPLETED, FAILED }
