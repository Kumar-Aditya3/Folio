package com.folio.reader

import com.folio.reader.util.ScreenWorkGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ScreenWorkGateTest {
    @Test fun warmsOnceRetainsDataAndRefreshesOnlyWhileVisible() = runBlocking<Unit> {
        val gate = ScreenWorkGate()
        val source = MutableStateFlow(1)
        val subscriptions = AtomicInteger()
        val active = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val state = gate.warmThenVisible(flow { subscriptions.incrementAndGet(); active.incrementAndGet()
                try { emitAll(source) } finally { active.decrementAndGet() }
            }).stateIn(scope,SharingStarted.Eagerly,0)
            withTimeout(3000) { state.first { it == 1 }; while(active.get() != 0) delay(10) }
            source.value = 2; delay(50); assertEquals(1,state.value)
            val a = gate.acquire(); val b = gate.acquire()
            withTimeout(3000) { state.first { it == 2 } }
            a.close(); a.close(); source.value = 3
            withTimeout(3000) { state.first { it == 3 } }
            b.close(); withTimeout(3000) { while(active.get() != 0) delay(10) }
            source.value = 4; delay(50); assertEquals(3,state.value)
            assertEquals(2,subscriptions.get())
            gate.acquire().use { withTimeout(3000) { state.first { it == 4 } } }
        } finally { scope.cancel() }
    }
}
