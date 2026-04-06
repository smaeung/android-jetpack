package com.example.hptransactions.concurrency

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyManagerTest {

    @Test
    fun `withExclusiveAccess serializes concurrent access`() = runTest {
        val manager = ConcurrencyManager(maxConcurrentRequests = 10)
        val counter = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val jobs = (1..20).map {
            launch {
                manager.withExclusiveAccess {
                    val current = counter.incrementAndGet()
                    maxObserved.updateAndGet { max -> maxOf(max, current) }
                    kotlinx.coroutines.delay(1)
                    counter.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }

        // At most 1 coroutine inside withExclusiveAccess at a time
        assertEquals(1, maxObserved.get())
    }

    @Test
    fun `withThrottle limits concurrent executions to permit count`() = runTest {
        val manager = ConcurrencyManager(maxConcurrentRequests = 3)
        val concurrent = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val jobs = (1..10).map {
            launch {
                manager.withThrottle {
                    val current = concurrent.incrementAndGet()
                    maxObserved.updateAndGet { max -> maxOf(max, current) }
                    kotlinx.coroutines.delay(10)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }

        assertTrue("Max concurrent was ${maxObserved.get()} but should be <= 3",
            maxObserved.get() <= 3)
    }
}
