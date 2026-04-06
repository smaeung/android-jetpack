package com.example.hptransactions.mvi

import app.cash.turbine.test
import com.example.hptransactions.data.TransactionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty and not running`() = runTest(testDispatcher) {
        val vm = TransactionViewModel()
        val state = vm.state.value
        assertTrue(state.transactions.isEmpty())
        assertFalse(state.isSimulationRunning)
        assertEquals(0, state.totalProcessed)
        assertEquals(0, state.droppedCount)
    }

    @Test
    fun `StartSimulation sets isSimulationRunning to true`() = runTest(testDispatcher) {
        val vm = TransactionViewModel()
        vm.state.test {
            awaitItem() // initial
            vm.onIntent(TransactionIntent.StartSimulation)
            val running = awaitItem()
            assertTrue(running.isSimulationRunning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `StopSimulation sets isSimulationRunning to false`() = runTest(testDispatcher) {
        val vm = TransactionViewModel()
        vm.onIntent(TransactionIntent.StartSimulation)
        testDispatcher.scheduler.advanceTimeBy(10)
        vm.onIntent(TransactionIntent.StopSimulation)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.isSimulationRunning)
    }

    @Test
    fun `ClearAll resets transactions and counters`() = runTest(testDispatcher) {
        val vm = TransactionViewModel()
        vm.onIntent(TransactionIntent.StartSimulation)
        testDispatcher.scheduler.advanceTimeBy(100)
        vm.onIntent(TransactionIntent.StopSimulation)
        vm.onIntent(TransactionIntent.ClearAll)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.transactions.isEmpty())
        assertEquals(0, vm.state.value.totalProcessed)
    }

    @Test
    fun `UpdateFilter shows only failed transactions when filter is on`() = runTest(testDispatcher) {
        val vm = TransactionViewModel()

        vm.onIntent(TransactionIntent.StartSimulation)
        testDispatcher.scheduler.advanceTimeBy(30L)
        vm.onIntent(TransactionIntent.StopSimulation)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(TransactionIntent.UpdateFilter(onlyFailed = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.showOnlyFailed)

        val filtered = vm.state.value.filteredTransactions
        val allTxs   = vm.state.value.transactions
        val failedCount = allTxs.count { it.status == TransactionStatus.FAILED }

        assertEquals(failedCount, filtered.size)
        assertTrue(filtered.all { it.status == TransactionStatus.FAILED })

        vm.onIntent(TransactionIntent.UpdateFilter(onlyFailed = false))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(allTxs.size, vm.state.value.filteredTransactions.size)
    }

    @Test
    fun `activeNetworkCalls never goes below zero`() = runTest(testDispatcher) {
        val vm = TransactionViewModel()

        vm.onIntent(TransactionIntent.StartSimulation)
        testDispatcher.scheduler.advanceTimeBy(300L)
        vm.onIntent(TransactionIntent.StopSimulation)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            "activeNetworkCalls was ${vm.state.value.activeNetworkCalls}, expected >= 0",
            vm.state.value.activeNetworkCalls >= 0
        )
    }
}
