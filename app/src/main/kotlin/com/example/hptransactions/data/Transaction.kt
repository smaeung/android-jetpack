package com.example.hptransactions.data

/**
 * Immutable domain model for a payment transaction.
 *
 * [id] is a UUID string used as a stable key in LazyColumn, allowing Compose
 * to reorder/animate items without destroying and recreating them.
 */
data class Transaction(
    val id: String,
    val amount: Double,
    val merchant: String,
    val status: TransactionStatus,
    val timestampMs: Long
)

enum class TransactionStatus { PENDING, COMPLETED, FAILED }
