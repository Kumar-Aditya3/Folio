package com.folio.reader.util

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Warm once, retain the last stateIn result, then subscribe only while a screen is visible. */
class ScreenWorkGate {
    private val leases = MutableStateFlow(0)
    fun acquire(): AutoCloseable {
        leases.update { it + 1 }
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false,true)) leases.update { (it-1).coerceAtLeast(0) } }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> warmThenVisible(source: Flow<T>): Flow<T> = flow {
        val warmed = MutableStateFlow(false)
        emitAll(combine(leases,warmed) { count, ready -> count > 0 || !ready }
            .distinctUntilChanged().flatMapLatest { active ->
                if (active) source.onEach { warmed.value = true } else emptyFlow()
            })
    }
}
