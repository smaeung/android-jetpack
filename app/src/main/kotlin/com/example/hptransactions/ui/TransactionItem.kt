package com.example.hptransactions.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.hptransactions.data.Transaction
import com.example.hptransactions.data.TransactionStatus
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * A single transaction row.
 *
 * ## Compose performance technique: deferred state reads
 *
 * The slide-in animation ([slideOffset]) changes every frame while the item
 * enters. If we read it during Composition (`Modifier.offset(x = value.dp)`)
 * Compose would RECOMPOSE the entire item on every animation frame — expensive.
 *
 * Instead we use `Modifier.offset { IntOffset(...) }` (the lambda overload).
 * The lambda is read during the LAYOUT phase, completely skipping the
 * Composition phase. This means zero recompositions while animating.
 */
@Composable
fun TransactionItem(transaction: Transaction, modifier: Modifier = Modifier) {

    val statusColor by animateColorAsState(
        targetValue = when (transaction.status) {
            TransactionStatus.PENDING   -> Color(0xFFFFA726)
            TransactionStatus.COMPLETED -> Color(0xFF66BB6A)
            TransactionStatus.FAILED    -> Color(0xFFEF5350)
        },
        animationSpec = tween(durationMillis = 300),
        label = "statusColor"
    )

    // Animate from 60 px right → 0 when the item first appears.
    val slideOffset by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(durationMillis = 250),
        label = "slideOffset"
    )

    Row(
        modifier = modifier
            // PERFORMANCE: lambda offset reads slideOffset during Layout phase only.
            // Skips Composition entirely while the animation is running.
            .offset { IntOffset(slideOffset.roundToInt(), 0) }
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.merchant,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = timeFormat.format(Date(transaction.timestampMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = currencyFormat.format(transaction.amount),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = transaction.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }
    }
}
