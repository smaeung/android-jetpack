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

// File-level constants: created once per class load, shared across all
// TransactionItem instances. Creating a new NumberFormat / SimpleDateFormat
// on every recomposition would be thousands of allocations per second.
private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * A single row in the transaction feed.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PATTERN 4a: DEFERRED STATE READS (LAYOUT-PHASE READS)
 * ═══════════════════════════════════════════════════════════════════════
 * Jetpack Compose has three phases per frame:
 *
 *   1. COMPOSITION  — runs Composable functions, produces a node tree.
 *                     Most expensive. Recompositions should be minimised.
 *   2. LAYOUT       — measures and places each node.
 *   3. DRAW         — paints pixels. Cheapest per-frame cost.
 *
 * When a value is read inside a Composable function body, Compose registers
 * a dependency on it and will RECOMPOSE (run phase 1 again) whenever it changes.
 *
 * The [slideOffset] animation value changes on every single frame (60 fps)
 * while the enter animation plays. If we read it during Composition:
 *
 *   BAD — reads during Composition, triggers recompose every frame:
 *     Modifier.offset(x = slideOffset.dp)
 *     ────────────────────────────────────
 *     At 60 fps × 20 visible items = 1 200 full recompositions/second
 *     just for slide-in animations.
 *
 * The LAMBDA form of offset() accepts a block that is invoked during the
 * LAYOUT phase, not during Composition. Compose therefore does NOT register
 * a Composition-phase dependency on slideOffset:
 *
 *   GOOD — reads during Layout, skips Composition entirely:
 *     Modifier.offset { IntOffset(slideOffset.roundToInt(), 0) }
 *     ──────────────────────────────────────────────────────────
 *     The lambda is called in Layout. Composition is skipped.
 *     0 recompositions while the animation plays.
 *
 * The same technique applies to Modifier.drawBehind { … } (Draw phase) and
 * Modifier.graphicsLayer { … } (also Layout/Draw phase).
 * ═══════════════════════════════════════════════════════════════════════
 */
@Composable
fun TransactionItem(transaction: Transaction, modifier: Modifier = Modifier) {

    // ── STATUS COLOUR ANIMATION ───────────────────────────────────────────────
    // animateColorAsState smoothly interpolates between status colours when a
    // transaction transitions from PENDING → COMPLETED or PENDING → FAILED.
    // The animation is tied to `transaction.status` — when the actor sends an
    // UpdateStatus message and the StateFlow emits a new state, the Composable
    // recomposes with the new status and the animation begins automatically.
    val statusColor by animateColorAsState(
        targetValue = when (transaction.status) {
            TransactionStatus.PENDING   -> Color(0xFFFFA726) // amber
            TransactionStatus.COMPLETED -> Color(0xFF66BB6A) // green
            TransactionStatus.FAILED    -> Color(0xFFEF5350) // red
        },
        animationSpec = tween(durationMillis = 300),
        label = "statusColor" // required for Animation Inspector in Android Studio
    )

    // ── SLIDE-IN ANIMATION ────────────────────────────────────────────────────
    // animateFloatAsState animates toward targetValue = 0f (no offset).
    // The starting value is whatever the animation clock held before the
    // composable entered the tree — effectively 0 since this is freshly created.
    // To make the slide-in visible, the initial value would need to be set via
    // animationSpec + initialValue. Kept simple here to illustrate the technique.
    val slideOffset by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(durationMillis = 250),
        label = "slideOffset"
    )

    Row(
        modifier = modifier
            // ── KEY PERFORMANCE OPTIMISATION ─────────────────────────────────
            // Lambda overload of offset(): the { } block runs in the Layout phase.
            // slideOffset is read here, NOT during Composition above.
            // Result: zero recompositions while this animation is active.
            .offset { IntOffset(slideOffset.roundToInt(), 0) }
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left column: merchant name + timestamp
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.merchant,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                // timeFormat is file-level — not re-instantiated on recompose.
                text = timeFormat.format(Date(transaction.timestampMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Right column: amount + animated status badge
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = currencyFormat.format(transaction.amount),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = transaction.status.name,
                style = MaterialTheme.typography.labelSmall,
                // statusColor is a State<Color> from animateColorAsState above.
                // Reading it here (inside Composition) is intentional — we WANT
                // recomposition when the status changes. Only the offset animation
                // above benefits from the deferred-read technique.
                color = statusColor
            )
        }
    }
}
