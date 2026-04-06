# High-Performance Android Transaction UI

A complete Android sample app demonstrating **five production-grade patterns** for handling high-frequency concurrent data in Jetpack Compose — without UI jank, race conditions, or memory pressure.

The app simulates a payment terminal receiving ~333 transactions per second, rendering them live in a scrollable list with real-time status updates.

---

## Contents

- [Why This Exists](#why-this-exists)
- [Architecture Overview](#architecture-overview)
- [End-to-End Data Flow](#end-to-end-data-flow)
- [Pattern 1 — Ring Buffer](#pattern-1--ring-buffer)
- [Pattern 2 — Actor Model](#pattern-2--actor-model)
- [Pattern 3 — Mutex and Semaphore](#pattern-3--mutex-and-semaphore)
- [Pattern 4 — Compose Performance](#pattern-4--compose-performance)
- [Pattern 5 — MVI and StateFlow](#pattern-5--mvi-and-stateflow)
- [Project Structure](#project-structure)
- [Source Code Guide](#source-code-guide)
- [Test Suite](#test-suite)
- [How to Build and Run](#how-to-build-and-run)

---

## Why This Exists

Standard Android tutorials show one transaction arriving at a time. Real payment systems look very different:

| Challenge | Why the naive approach breaks |
|---|---|
| 300+ events/second from a producer | `StateFlow.emit()` suspends producers; `LiveData.postValue()` silently drops frames |
| 50 concurrent network calls | Uncapped coroutines exhaust thread pools; `synchronized {}` blocks the UI thread |
| List of 2 000 items with constant prepends | Without stable keys every prepend destroys and recreates all visible rows |
| Scroll-position-dependent UI state | Reading raw scroll offset during Composition recomposes on every scroll pixel |
| Filter toggle over a live list | Multiple `StateFlow`s diverge; the UI sees a partially-updated inconsistent snapshot |

This sample addresses every one of those problems with documented, testable patterns.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                                         │
│                                                                     │
│  TransactionScreen ──Intent──► TransactionViewModel                │
│  TransactionItem   ◄──State──── (single StateFlow<TxState>)        │
│  StatsBar                                                           │
└─────────────────────────────────────────────────────────────────────┘
                              │ orchestrates
┌─────────────────────────────┼───────────────────────────────────────┐
│  Domain / Concurrency Layer │                                       │
│                             ▼                                       │
│  Producer coroutine ──tryEmit──► TransactionRingBuffer              │
│  (2 000 tx @ 3 ms each)          (SharedFlow, DROP_OLDEST)         │
│                                         │                           │
│                                    flow.collect                     │
│                                         │                           │
│                                         ▼                           │
│                              Consumer coroutine                     │
│                              ├─ actor.send(AddTransaction)          │
│                              └─ concurrency.withThrottle {          │
│                                   simulateNetworkCall()             │
│                                 }                                   │
│                                         │                           │
│                                    actor.send                       │
│                                         ▼                           │
│                           TransactionActor                          │
│                           Channel<TransactionMessage>               │
│                           single coroutine owns mutable list        │
│                           onStateChange → _state.update {}          │
│                                                                     │
│  ConcurrencyManager                                                 │
│  ├─ Mutex       → withExclusiveAccess {} (protects droppedCount)   │
│  └─ Semaphore(5)→ withThrottle {}        (caps network calls)      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## End-to-End Data Flow

```
Every 3 ms:
  Producer creates Transaction(id=UUID, status=PENDING)
         │
         ▼
  ringBuffer.tryEmit(tx)               ← never suspends (DROP_OLDEST)
         │
         ├─ buffer not full → tx queued
         └─ buffer full     → oldest tx dropped, droppedCount++
                                        (Mutex protects the increment)
         │
         ▼  flow.collect { tx }
  Consumer coroutine receives tx
         │
         ├──────────────────────────────────────────────────────────┐
         │                                                          │
         ▼                                                          ▼
  actor.send(AddTransaction(tx))             launch {
         │                                     concurrency.withThrottle {
         │                                       activeNetworkCalls++
         │                                       delay(50–200 ms)
         │                                       actor.send(UpdateStatus(…))
         │                                       activeNetworkCalls--
         │                                     }
         │                                   }
         │
         ▼
  Actor coroutine (one at a time, no races):
    AddTransaction  → list.add(0, tx)          prepend newest-first
    UpdateStatus    → list[i] = tx.copy(…)     mutate by UUID
    ClearAll        → list.clear()
         │
         ▼  onStateChange(list.toList())       immutable snapshot
  _state.update { copy(transactions = …) }    atomic CAS loop
  recomputeFiltered()                          pre-compute filter once
         │
         ▼
  StateFlow<TransactionState> emits new state
         │
         ▼  collectAsStateWithLifecycle()
  Compose recomposes only changed nodes
```

---

## Pattern 1 — Ring Buffer

**File:** `buffer/TransactionRingBuffer.kt`

A ring buffer (circular buffer) is a fixed-capacity queue where inserting past the end automatically evicts the oldest entry. Memory usage is constant regardless of producer speed.

```
Capacity = 3, buffer full:

  [ TX-001 | TX-002 | TX-003 ]
    oldest            newest

tryEmit(TX-004):
  TX-001 evicted (oldest, most stale)
  TX-004 inserted

  [ TX-002 | TX-003 | TX-004 ]
```

Implemented with `MutableSharedFlow`:

```kotlin
MutableSharedFlow<Transaction>(
    replay = 0,                                   // no history for late collectors
    extraBufferCapacity = 200,                    // ring size
    onBufferOverflow = BufferOverflow.DROP_OLDEST // eviction policy
)
```

**Why `replay = 0`?**
After a screen rotation, the new collector should receive only future events. Replaying 200 stale transactions would cause a burst recomposition with outdated data.

**Why `DROP_OLDEST` and not `DROP_LATEST` or `SUSPEND`?**
- `DROP_LATEST` discards the newest event — the opposite of what we want.
- `SUSPEND` pauses the producer coroutine when the buffer is full, coupling producer speed to consumer speed and risking UI-thread stalls.
- `DROP_OLDEST` always makes room for the newest event. `tryEmit()` never suspends and always returns `true`.

**Why `tryEmit()` and not `emit()` (suspend)?**
`emit()` would pause the producer whenever the buffer is full. `tryEmit()` returns immediately — back-pressure is handled by the buffer, not by blocking the producer.

---

## Pattern 2 — Actor Model

**File:** `actor/TransactionActor.kt`

An actor is a concurrency pattern where all mutations to shared state are routed through a single sequential processor (the actor), eliminating the need for locks at the call site.

```
Coroutine A  ──┐
Coroutine B  ──┼──► Channel<TransactionMessage> ──► actor coroutine ──► mutableList
Coroutine C  ──┘          (UNLIMITED capacity)       (one at a time)
```

**Why not `synchronized` or `Mutex`?**

| Approach | Thread behaviour | Risk |
|---|---|---|
| `synchronized {}` | Blocks the OS thread | Thread pool starvation, ANR |
| `Mutex.withLock {}` | Suspends coroutine (safe) | Must be applied at every call site |
| Actor | No lock needed at all | State is confined to one coroutine — concurrent access is structurally impossible |

**Message protocol — sealed interface:**
```kotlin
sealed interface TransactionMessage
data class AddTransaction(val transaction: Transaction) : TransactionMessage
data class UpdateStatus(val id: String, val status: TransactionStatus) : TransactionMessage
object ClearAll : TransactionMessage
```

The sealed modifier forces the `when` expression in the actor loop to handle every message type — missing a case is a compile error.

**Why `Channel.UNLIMITED`?**
The ring buffer limits total event throughput. Messages that survive the buffer must not be dropped again — unlimited capacity guarantees all of them reach the actor.

**Why `Channel + launch` instead of `actor {}`?**
Kotlin's `actor {}` builder is `@ObsoleteCoroutinesApi` and may be removed. The manual pattern is identical in behaviour and uses only stable APIs.

---

## Pattern 3 — Mutex and Semaphore

**File:** `concurrency/ConcurrencyManager.kt`

Both primitives are **coroutine-aware**: they suspend the calling coroutine (returning the thread to the pool) rather than blocking the thread.

### Mutex — exclusive write access

```kotlin
// ONE coroutine at a time inside this block.
// Others suspend; the thread is free for other work while waiting.
mutex.withLock {
    droppedCount++  // safe — no concurrent access possible
}
```

Compare with Java's `synchronized`:
```java
// WRONG on Android — parks the OS thread for the entire duration.
synchronized (lock) { droppedCount++; }
// Under high contention this starves the thread pool → UI jank → ANR.
```

### Semaphore — throttle concurrent I/O

```
Semaphore(permits = 5)

In-flight calls: [A] [B] [C] [D] [E]   ← 5 permits taken
Call F arrives → suspends (no permit available)
Call A completes → permit released → Call F resumes
```

Without the semaphore, 333 tx/sec × 125 ms average latency = ~41 concurrent network calls at steady state. This exhausts a typical HTTP connection pool (default size 5), causing connection timeouts and cascading failures.

```kotlin
class ConcurrencyManager(maxConcurrentRequests: Int = 5) {
    private val mutex     = Mutex()
    private val semaphore = Semaphore(permits = maxConcurrentRequests)

    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = mutex.withLock { block() }
    suspend fun <T> withThrottle(block: suspend () -> T): T = semaphore.withPermit { block() }
}
```

---

## Pattern 4 — Compose Performance

### 4a. Deferred State Reads — skip Composition during animation

**File:** `ui/TransactionItem.kt`

Compose has three phases per frame: **Composition → Layout → Draw**.
Composition is the most expensive. Reading a rapidly-changing value during Composition schedules a recomposition on every change.

```kotlin
// BAD: reads slideOffset during Composition phase.
// Triggers full recomposition 60 times/second while animation plays.
Modifier.offset(x = slideOffset.dp)

// GOOD: lambda runs during Layout phase, not Composition.
// Composition is skipped entirely while the animation plays.
Modifier.offset { IntOffset(slideOffset.roundToInt(), 0) }
```

At 60 fps with 20 visible items, the difference is 1 200 recompositions/second saved.

The same technique applies to: `Modifier.drawBehind {}`, `Modifier.graphicsLayer {}`.

### 4b. `derivedStateOf` — coarse-grained recomposition

**File:** `ui/StatsBar.kt`

`listState.firstVisibleItemIndex` changes on every scroll pixel — hundreds of times per second during a fling. The "Scroll to Top" button only needs to know whether the user is past item 5.

```kotlin
// BAD: recomposes StatsBar on every scroll pixel.
val shouldShow = listState.firstVisibleItemIndex > 5

// GOOD: recomposes StatsBar only when the Boolean flips.
val showScrollToTop by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 5 }
}
```

`derivedStateOf` computes a derived value and only notifies subscribers when that derived value actually changes — not every time the input changes. During a full scroll from top to bottom: 2 recompositions, not hundreds.

**Rule of thumb:** use `derivedStateOf` when the output changes less often than the input.

### 4c. Stable Keys in `LazyColumn`

**File:** `ui/TransactionScreen.kt`

```kotlin
// BAD: no key — Compose uses list index as identity.
// Prepending 1 item shifts all indices by 1.
// Compose destroys and recreates every visible row composable.
LazyColumn {
    items(transactions) { tx -> TransactionItem(tx) }
}

// GOOD: UUID key — Compose tracks items by identity, not position.
// Prepending 1 item: only 1 new composable created; existing ones moved.
LazyColumn {
    items(transactions, key = { it.id }) { tx -> TransactionItem(tx) }
}
```

---

## Pattern 5 — MVI and StateFlow

**Files:** `mvi/TransactionIntent.kt`, `mvi/TransactionState.kt`, `mvi/TransactionViewModel.kt`

### Unidirectional data flow

```
User taps "Start"
      │
      ▼
UI emits TransactionIntent.StartSimulation
      │
      ▼
ViewModel.onIntent() processes the intent
      │
      ▼
_state.update { it.copy(isSimulationRunning = true) }
      │
      ▼
StateFlow<TransactionState> emits new snapshot
      │
      ▼
Compose recomposes only the affected nodes
```

The UI never writes state directly. Every mutation has a named intent as its cause — the system is fully traceable and testable.

### Why one `StateFlow<TransactionState>` instead of many flows?

```kotlin
// FRAGILE: multiple flows update independently.
// Between isRunning emitting and transactions emitting,
// the UI sees an inconsistent half-state.
val transactions: StateFlow<List<Transaction>>
val isRunning:    StateFlow<Boolean>

// CORRECT: one atomic snapshot. The UI always sees a consistent state.
val state: StateFlow<TransactionState>
```

`StateFlow.update {}` is a compare-and-set (CAS) loop — if two coroutines call it simultaneously, one retries with the latest value. No manual lock needed.

StateFlow also **conflates**: if the UI is slow to collect, it receives only the latest emitted state — like a ring buffer of size 1. Stale intermediate states are automatically dropped.

---

## Project Structure

```
android-jetpack/
├── build.gradle.kts                          Root — plugin declarations only
├── settings.gradle.kts                       Module list + repository configuration
├── gradle/
│   └── libs.versions.toml                   Version catalog — all library versions
└── app/
    ├── build.gradle.kts                      App build — compileSdk, dependencies
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/values/themes.xml
        │   └── kotlin/com/example/hptransactions/
        │       ├── MainActivity.kt
        │       ├── data/
        │       │   └── Transaction.kt          Domain model + status enum
        │       ├── buffer/
        │       │   └── TransactionRingBuffer.kt  Pattern 1
        │       ├── actor/
        │       │   └── TransactionActor.kt       Pattern 2
        │       ├── concurrency/
        │       │   └── ConcurrencyManager.kt     Pattern 3
        │       ├── mvi/
        │       │   ├── TransactionIntent.kt      Pattern 5 — user actions
        │       │   ├── TransactionState.kt       Pattern 5 — UI state snapshot
        │       │   └── TransactionViewModel.kt   Orchestrator — all patterns wired
        │       └── ui/
        │           ├── TransactionScreen.kt      Pattern 4c — stable keys
        │           ├── TransactionItem.kt        Pattern 4a — deferred state reads
        │           └── StatsBar.kt               Pattern 4b — derivedStateOf
        └── test/
            └── kotlin/com/example/hptransactions/
                ├── buffer/TransactionRingBufferTest.kt
                ├── actor/TransactionActorTest.kt
                ├── concurrency/ConcurrencyManagerTest.kt
                └── mvi/TransactionViewModelTest.kt
```

---

## Source Code Guide

### `data/Transaction.kt`

A Kotlin `data class` with all `val` fields. Structural equality (fields compared, not object reference) allows `StateFlow` and Compose to detect unchanged items and skip recomposition. The UUID `id` field serves as a stable, globally-unique identity for `LazyColumn` item keys without requiring a database sequence.

### `buffer/TransactionRingBuffer.kt`

Wraps `MutableSharedFlow` with `replay=0` (no history), configurable `extraBufferCapacity` (ring size), and `DROP_OLDEST` overflow policy. Exposes a read-only `flow` for consumers and a non-suspending `tryEmit()` for producers. The class is the only place in the codebase that creates `MutableSharedFlow` — consumers cannot bypass the overflow policy.

### `actor/TransactionActor.kt`

Defines `TransactionMessage` as a sealed interface covering `AddTransaction`, `UpdateStatus`, and `ClearAll`. The `transactionActor()` extension function creates a `Channel(UNLIMITED)` and launches a single coroutine that processes messages sequentially. All mutable state (`mutableListOf<Transaction>`) lives inside the `launch {}` block — structurally unreachable from outside, eliminating concurrent access by construction. After each mutation, `list.toList()` produces an immutable snapshot passed to the `onStateChange` callback.

### `concurrency/ConcurrencyManager.kt`

A thin, injectable wrapper around `Mutex` and `Semaphore`. Both use the coroutine-suspend (not thread-block) model. `withExclusiveAccess {}` serialises counter increments. `withThrottle {}` limits the number of concurrent simulated network calls to `maxConcurrentRequests`. The constructor parameter makes both limits configurable for tests without subclassing.

### `mvi/TransactionIntent.kt`

A sealed interface with four subtypes — one per user action. The sealed modifier guarantees exhaustive `when` handling in the ViewModel: adding a new intent without a corresponding handler is a compile error. `object` subtypes carry no data (zero allocation); the `data class` subtype `UpdateFilter` carries a `Boolean` payload.

### `mvi/TransactionState.kt`

An immutable `data class` — all `val` fields, no setters, no mutable collections. `filteredTransactions` is a pre-computed field (not a `get()` property) because it is read on every recomposition from two Composables. Computing it once per state update in the ViewModel and storing it here eliminates redundant O(n) filter passes in the render loop.

### `mvi/TransactionViewModel.kt`

The orchestrator. Initialises ring buffer, actor, and concurrency manager at construction time. The actor's `onStateChange` callback calls `_state.update {}` (CAS loop — no lock needed) followed by `recomputeFiltered()`. The producer coroutine uses `tryEmit()` and `delay(3)` (non-blocking). The consumer coroutine collects from the ring buffer, forwards to the actor, and spawns a child coroutine for each throttled network call. `clearAll()` routes through the actor so counter reset and list clear happen atomically in the same `onStateChange` invocation. `onCleared()` closes the actor channel to terminate its loop cleanly.

### `ui/TransactionItem.kt`

A single list row. Uses `Modifier.offset { IntOffset(…) }` (lambda overload) for the slide-in animation so the position value is read in the Layout phase — not during Composition — keeping the recomposition count at zero while the animation plays. Uses `animateColorAsState` for the status badge colour transition, correctly reading it during Composition because colour changes are infrequent and intentionally trigger recomposition.

### `ui/StatsBar.kt`

The stats header. Wraps the "should show scroll button" condition in `remember { derivedStateOf { … } }` so the Composable tracks the Boolean result, not the raw scroll index. The scroll button fades in/out only twice per scroll session regardless of how many pixels the user scrolls. Stat chips read pre-computed fields from `TransactionState` rather than performing live calculations.

### `ui/TransactionScreen.kt`

Root screen. Uses `collectAsStateWithLifecycle()` (pauses collection when backgrounded) instead of `collectAsState()`. Provides `key = { it.id }` to `LazyColumn.items()` so Compose identifies items by UUID — enabling O(1) composable creation per prepend. Delegates all state reads to the ViewModel; the Composable itself holds no mutable state. `ControlBar` and `EmptyState` are stateless private Composables for easier testing and preview.

---

## Test Suite

17 unit tests across 4 test classes. All run on the JVM — no emulator or device required.

```
./gradlew :app:testDebugUnitTest
```

| Test class | Tests | What is covered |
|---|---|---|
| `TransactionRingBufferTest` | 5 | End-to-end delivery, DROP_OLDEST eviction, tryEmit always true, in-order delivery, capacity-1 edge case |
| `TransactionActorTest` | 4 | Prepend order, status update by UUID, ClearAll, graceful no-op for unknown ID |
| `ConcurrencyManagerTest` | 2 | Mutex serialisation (max 1 concurrent), Semaphore cap (max N concurrent) |
| `TransactionViewModelTest` | 6 | Initial state, start sets running flag, stop clears running flag, clear resets list and counters, filter produces correct subset, network counter never goes negative |

**Test dependencies:**

| Library | Purpose |
|---|---|
| `kotlinx-coroutines-test` | `StandardTestDispatcher`, virtual-time control, `advanceUntilIdle()` |
| `app.cash.turbine` | `flow.test {}` / `awaitItem()` for asserting on Flow emissions |
| `JUnit 4` | Test runner and assertion functions |

---

## How to Build and Run

### Prerequisites

| Requirement | Version |
|---|---|
| Android Studio | Ladybug (2024.2.1) or newer |
| JDK | 17 |
| compileSdk | 35 |
| minSdk | 26 (Android 8.0 Oreo) |

### Build the debug APK

```bash
cd android-jetpack
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Install on a connected device or emulator

```bash
./gradlew :app:installDebug
```

### Run all unit tests

```bash
./gradlew :app:testDebugUnitTest
# HTML report: app/build/reports/tests/testDebugUnitTest/index.html
```

### Open in Android Studio

1. `File → Open` → select the `android-jetpack/` directory
2. Wait for Gradle sync
3. Press **Run ▶** to launch on a connected device or AVD

### What to observe while running

| What to watch | Where to look |
|---|---|
| Ring buffer drops oldest | "Dropped" counter in stats bar increments under sustained load |
| Semaphore cap enforced | "Network" counter in stats bar never exceeds 5 |
| No recompositions during scroll | Android Studio Layout Inspector → Recomposition Counts on `StatsBar` |
| No recompositions during animation | Layout Inspector → Recomposition Counts on `TransactionItem` while items slide in |
| Stable-key moves, not recreates | Layout Inspector — existing rows shift position without flashing |
| Filter works atomically | Tap "Failed" chip — list and "Showing" counter change simultaneously |
| Clear is atomic | Tap "Clear" — list empties and "Processed" and "Dropped" counters reset together |
