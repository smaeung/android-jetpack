package com.example.hptransactions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hptransactions.mvi.TransactionIntent
import com.example.hptransactions.mvi.TransactionViewModel
import kotlinx.coroutines.launch

/**
 * Root screen that collects [TransactionState] and renders the transaction feed.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PATTERN 4c: STABLE KEYS IN LazyColumn
 * ═══════════════════════════════════════════════════════════════════════
 * LazyColumn recycles composables as items scroll off screen (like RecyclerView).
 * When items are added/removed/reordered, Compose must decide which composable
 * corresponds to which data item in the new list.
 *
 * WITHOUT stable keys:
 *   Compose uses list position (index 0, 1, 2…) as the identity.
 *   New transaction prepended at index 0 → every item's identity shifts by 1.
 *   Compose sees "item at index 0 changed", "item at index 1 changed", …
 *   Result: ALL visible composables are destroyed and recreated every time
 *           a new transaction arrives — at 333 tx/sec that is catastrophic.
 *
 * WITH key = { transaction -> transaction.id }:
 *   Compose uses the UUID as identity. "TX-abc123 is now at index 1 instead
 *   of index 0" — Compose recognises it and MOVES the existing composable
 *   (cheap), animating it downward. Only the new item at index 0 is created.
 *   Result: O(1) composable creation per new transaction, regardless of list size.
 *
 * The key must be:
 *   - Stable: same object always produces the same key (UUID qualifies).
 *   - Unique: no two items at the same time share a key (UUID guarantees this).
 *   - Serialisable to a supported type: String, Int, Long, etc. (String UUID qualifies).
 * ═══════════════════════════════════════════════════════════════════════
 *
 * WHY collectAsStateWithLifecycle INSTEAD OF collectAsState?
 * ═══════════════════════════════════════════════════════════════════════
 * collectAsState() collects the StateFlow for as long as the Composable is
 * in the composition — even when the app is backgrounded (minimised).
 * This means the ViewModel keeps processing state updates, the actor keeps
 * receiving messages, and Compose keeps scheduling recompositions while
 * nothing is visible to the user — pure wasted CPU and battery.
 *
 * collectAsStateWithLifecycle() from lifecycle-runtime-compose pauses
 * collection automatically when the Lifecycle drops below STARTED (i.e. when
 * the app is backgrounded). Collection resumes when the app returns to
 * foreground. This is the recommended pattern for all production Compose apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionScreen(
    viewModel: TransactionViewModel = viewModel()
) {
    // Lifecycle-aware StateFlow collection. Pauses in background, resumes in foreground.
    val state by viewModel.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("HP Transactions") })
        },
        bottomBar = {
            ControlBar(
                isRunning      = state.isSimulationRunning,
                showOnlyFailed = state.showOnlyFailed,
                onStart        = { viewModel.onIntent(TransactionIntent.StartSimulation) },
                onStop         = { viewModel.onIntent(TransactionIntent.StopSimulation) },
                onClear        = { viewModel.onIntent(TransactionIntent.ClearAll) },
                onFilterToggle = { viewModel.onIntent(TransactionIntent.UpdateFilter(!state.showOnlyFailed)) }
            )
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            // Stats header — uses derivedStateOf internally for the scroll button.
            StatsBar(
                state       = state,
                listState   = listState,
                onScrollToTop = {
                    coroutineScope.launch {
                        // animateScrollToItem is a suspend function that scrolls
                        // with a smooth animation and only returns when complete.
                        listState.animateScrollToItem(index = 0)
                    }
                }
            )

            // Show an empty/loading state when no transactions are visible.
            // Uses filteredTransactions (pre-computed) — not a live filter call.
            if (state.filteredTransactions.isEmpty()) {
                EmptyState(isRunning = state.isSimulationRunning)
            } else {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // ── PATTERN 4c: STABLE KEYS ───────────────────────────────
                    // key = { it.id } — each transaction's UUID is its stable identity.
                    // When a new transaction is prepended, existing composables are
                    // MOVED (not recreated). Only one new composable is created per
                    // new transaction, regardless of how many items are already visible.
                    items(
                        items = state.filteredTransactions,
                        key   = { transaction -> transaction.id }
                    ) { transaction ->
                        TransactionItem(transaction = transaction)
                    }
                }
            }
        }
    }
}

/**
 * Shown when there are no transactions to display.
 *
 * Distinguishes between two cases:
 *   - Simulation is running but no transactions have arrived yet → spinner
 *   - Simulation has not started (or was cleared) → call-to-action text
 */
@Composable
private fun EmptyState(isRunning: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isRunning) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Waiting for transactions…")
            }
        } else {
            Text(
                text = "Press Start to begin the simulation",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Bottom action bar with Start/Stop, Clear, and Failed-filter controls.
 *
 * Stateless — all state comes from the caller (TransactionScreen) which reads
 * it from the ViewModel's StateFlow. Stateless Composables are easier to test,
 * preview, and reuse.
 */
@Composable
private fun ControlBar(
    isRunning: Boolean,
    showOnlyFailed: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onFilterToggle: () -> Unit
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Toggle between Start and Stop depending on simulation state.
            // The button label and colour change, but the weight and position stay.
            if (isRunning) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Text("Start")
                }
            }

            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("Clear")
            }

            // FilterChip shows current filter state via the `selected` parameter.
            // Tapping it fires UpdateFilter(!showOnlyFailed) — the ViewModel
            // toggles showOnlyFailed and recomputes filteredTransactions atomically.
            FilterChip(
                selected = showOnlyFailed,
                onClick  = onFilterToggle,
                label    = { Text("Failed") }
            )
        }
    }
}
