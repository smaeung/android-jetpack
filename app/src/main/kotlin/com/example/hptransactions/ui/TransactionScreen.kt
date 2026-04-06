package com.example.hptransactions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
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
 * Root screen that wires the ViewModel to the UI.
 *
 * ## Compose performance technique: stable keys in LazyColumn
 *
 * Each transaction item is keyed by [Transaction.id] (a UUID).
 * Without stable keys, Compose destroys and recreates every visible item
 * whenever a new transaction is prepended to the list. With stable keys,
 * Compose understands which items moved and only animates them — no
 * unnecessary recompositions for unchanged items.
 */
@Composable
fun TransactionScreen(
    viewModel: TransactionViewModel = viewModel()
) {
    // collectAsStateWithLifecycle automatically pauses collection when the app
    // is backgrounded, saving CPU and battery.
    val state by viewModel.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("HP Transactions") })
        },
        bottomBar = {
            ControlBar(
                isRunning     = state.isSimulationRunning,
                showOnlyFailed = state.showOnlyFailed,
                onStart       = { viewModel.onIntent(TransactionIntent.StartSimulation) },
                onStop        = { viewModel.onIntent(TransactionIntent.StopSimulation) },
                onClear       = { viewModel.onIntent(TransactionIntent.ClearAll) },
                onFilterToggle = { viewModel.onIntent(TransactionIntent.UpdateFilter(!state.showOnlyFailed)) }
            )
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            // Live stats + scroll-to-top (uses derivedStateOf internally)
            StatsBar(
                state       = state,
                listState   = listState,
                onScrollToTop = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(index = 0)
                    }
                }
            )

            if (state.filteredTransactions.isEmpty()) {
                EmptyState(isRunning = state.isSimulationRunning)
            } else {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // PERFORMANCE: key = { it.id } — stable UUID key.
                    // Compose moves existing composables rather than destroying them
                    // when items shift position in the list.
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

            FilterChip(
                selected = showOnlyFailed,
                onClick  = onFilterToggle,
                label    = { Text("Failed") }
            )
        }
    }
}
