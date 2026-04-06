package com.example.hptransactions.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hptransactions.mvi.TransactionState

/**
 * Header bar showing live simulation stats and a context-sensitive scroll button.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PATTERN 4b: derivedStateOf — COARSE-GRAINED RECOMPOSITION CONTROL
 * ═══════════════════════════════════════════════════════════════════════
 * The problem: [LazyListState.firstVisibleItemIndex] changes on EVERY PIXEL
 * of scrolling. During a fast fling this can fire hundreds of times per
 * second. If StatsBar reads firstVisibleItemIndex directly:
 *
 *   BAD — recomposes on every scroll pixel:
 *     val scrolled = listState.firstVisibleItemIndex > SCROLL_THRESHOLD
 *     // Compose sees firstVisibleItemIndex as a dependency.
 *     // StatsBar recomposes every time the index changes (every pixel).
 *
 * derivedStateOf wraps the expression in a "derived state" object whose
 * value is the RESULT of the expression (a Boolean here), not the raw input.
 * Compose tracks the derived state as the dependency of the Composable, not
 * the underlying raw state:
 *
 *   GOOD — recomposes only when the Boolean flips:
 *     val showScrollToTop by remember {
 *         derivedStateOf { listState.firstVisibleItemIndex > SCROLL_THRESHOLD }
 *     }
 *     // Compose tracks `showScrollToTop` (Boolean), not `firstVisibleItemIndex`.
 *     // StatsBar recomposes ONLY when the Boolean changes: false→true or true→false.
 *     // During a full scroll from top to bottom: exactly 2 recompositions total.
 *
 * WHY remember { derivedStateOf { … } } AND NOT JUST remember { … }?
 *   - remember { expr } evaluates expr ONCE at first composition and caches it.
 *   - derivedStateOf { expr } re-evaluates expr whenever its internal State
 *     dependencies change (here: firstVisibleItemIndex), then notifies
 *     subscribers only if the result changed.
 *   - The outer remember prevents a new derivedStateOf object from being
 *     allocated on every recomposition (which would defeat the purpose).
 *
 * WHEN TO USE derivedStateOf:
 *   Use it when: a derived value changes LESS OFTEN than its input.
 *   Examples:
 *     - A "scroll to top" button that appears after 5 items (Boolean) derived
 *       from a raw scroll offset (Int that changes every pixel) ← this case
 *     - A "submit" button enabled state (Boolean) derived from 5 form fields
 *     - A filtered list (List) derived from a full list + a filter string
 * ═══════════════════════════════════════════════════════════════════════
 */
@Composable
fun StatsBar(
    state: TransactionState,
    listState: LazyListState,
    onScrollToTop: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ── PATTERN 4b: derivedStateOf ────────────────────────────────────────────
    // firstVisibleItemIndex fires on every scroll event.
    // showScrollToTop (Boolean) fires only when crossing SCROLL_THRESHOLD.
    // StatsBar recomposes only on the Boolean flip — not on every scroll pixel.
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > SCROLL_THRESHOLD }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

            // Four stat chips displaying live counters from the ViewModel state.
            // These only recompose when the TransactionState itself changes
            // (totalProcessed, droppedCount, activeNetworkCalls, filteredTransactions.size).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Total transactions seen by the actor (capped at 500 by actor logic).
                StatChip(label = "Processed", value = state.totalProcessed.toString())

                // Transactions dropped by the ring buffer overflow (DROP_OLDEST).
                // In normal conditions this stays at 0 — it rises only when the
                // consumer cannot keep up with 333 events/sec sustained load.
                StatChip(label = "Dropped",   value = state.droppedCount.toString())

                // Simulated network calls currently inside ConcurrencyManager.withThrottle.
                // The "/5" suffix shows the Semaphore limit — this counter never
                // exceeds 5 thanks to the Semaphore(permits = 5) in ConcurrencyManager.
                StatChip(label = "Network",   value = "${state.activeNetworkCalls}/5")

                // Visible item count — changes when the filter chip is toggled.
                // Uses filteredTransactions (pre-computed field) not a live filter.
                StatChip(label = "Showing",   value = state.filteredTransactions.size.toString())
            }

            // ── ANIMATED SCROLL-TO-TOP BUTTON ─────────────────────────────────
            // AnimatedVisibility fades the button in/out when showScrollToTop flips.
            // Because showScrollToTop uses derivedStateOf, this animation triggers
            // at most twice per scroll session — not hundreds of times.
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TextButton(
                    onClick = onScrollToTop,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scroll to top ↑")
                }
            }
        }
    }
}

/**
 * A small two-line chip displaying a numeric [value] with a [label] below it.
 *
 * Extracted as a private Composable so changes to chip layout only require
 * recomposing the individual chip — not the entire Row.
 */
@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Number of items from the top before the "Scroll to top" button appears.
// Chosen so the button appears after the user has scrolled past the first screen.
private const val SCROLL_THRESHOLD = 5
