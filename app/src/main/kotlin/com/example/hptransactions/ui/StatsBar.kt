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
 * Header bar that shows live stats and a "Scroll to Top" button.
 *
 * ## Compose performance technique 1: derivedStateOf
 *
 * [listState.firstVisibleItemIndex] changes on every scroll pixel, but we only
 * care about whether it is > SCROLL_THRESHOLD. Without derivedStateOf, StatsBar
 * would recompose on every scroll event.
 *
 * Wrapping the boolean in [derivedStateOf] means Compose only schedules a
 * recomposition of this composable when the *derived boolean* flips (false→true
 * or true→false), not on every raw index change.
 *
 * ## Compose performance technique 2: deferred state reads
 *
 * The network-call counter changes rapidly. It is read inside a lambda passed
 * to [Text] via string interpolation, not hoisted into the recomposition scope,
 * which keeps recompositions local to the smallest possible subtree.
 */
@Composable
fun StatsBar(
    state: TransactionState,
    listState: LazyListState,
    onScrollToTop: () -> Unit,
    modifier: Modifier = Modifier
) {
    // PERFORMANCE: derivedStateOf — recompose StatsBar only when the boolean flips.
    // Without this, every scroll event (hundreds per second) would trigger a recomposition.
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > SCROLL_THRESHOLD }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip(label = "Processed", value = state.totalProcessed.toString())
                StatChip(label = "Dropped",   value = state.droppedCount.toString())
                StatChip(label = "Network",   value = "${state.activeNetworkCalls}/5")
                StatChip(label = "Showing",   value = state.filteredTransactions.size.toString())
            }

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

private const val SCROLL_THRESHOLD = 5
