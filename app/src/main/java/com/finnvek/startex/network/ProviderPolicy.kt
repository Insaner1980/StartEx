package com.finnvek.startex.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.random.Random

class RetryPolicy(
    val initialDelayMillis: Long = 1_000,
    val maximumDelayMillis: Long = 60_000,
    private val jitter: (Long) -> Long = ::defaultJitter,
) {
    init {
        require(initialDelayMillis > 0)
        require(maximumDelayMillis >= initialDelayMillis)
    }

    fun delayMillis(
        failureCount: Int,
        retryAfterMillis: Long? = null,
    ): Long {
        require(failureCount > 0)
        require(retryAfterMillis == null || retryAfterMillis >= 0)
        val shift = (failureCount - 1).coerceAtMost(MAX_SHIFT)
        val factor = 1L shl shift
        val exponential =
            if (initialDelayMillis > maximumDelayMillis / factor) {
                maximumDelayMillis
            } else {
                initialDelayMillis * factor
            }
        if (retryAfterMillis != null) return max(exponential, retryAfterMillis)
        return jitter(exponential).coerceIn(exponential, maximumDelayMillis)
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}

private fun defaultJitter(delayMillis: Long): Long {
    val maximumJitter = minOf(delayMillis / 4, Long.MAX_VALUE - delayMillis)
    if (maximumJitter <= 0) return delayMillis
    return delayMillis + Random.Default.nextLong(maximumJitter + 1)
}

data class ProviderFreshness(
    val maxAgeMillis: Long,
) {
    init {
        require(maxAgeMillis > 0)
    }

    fun isFresh(
        receivedAtMillis: Long,
        nowMillis: Long,
    ): Boolean = nowMillis >= receivedAtMillis && nowMillis - receivedAtMillis <= maxAgeMillis
}

enum class ProviderHealthState {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
}

data class ProviderHealthSnapshot(
    val provider: ProviderId,
    val state: ProviderHealthState,
    val consecutiveFailures: Int,
    val lastSuccessAtMillis: Long?,
    val lastFailureAtMillis: Long?,
    val lastLatencyMillis: Long?,
    val retryAfterMillis: Long?,
)

class ProviderHealthTracker(
    private val unavailableAfterFailures: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val snapshots = mutableMapOf<ProviderId, ProviderHealthSnapshot>()

    init {
        require(unavailableAfterFailures > 1)
    }

    @Synchronized
    fun recordSuccess(
        provider: ProviderId,
        latencyMillis: Long,
    ) {
        require(latencyMillis >= 0)
        val previous = snapshot(provider)
        snapshots[provider] =
            previous.copy(
                state = ProviderHealthState.HEALTHY,
                consecutiveFailures = 0,
                lastSuccessAtMillis = clock(),
                lastLatencyMillis = latencyMillis,
                retryAfterMillis = null,
            )
    }

    @Synchronized
    fun recordFailure(
        provider: ProviderId,
        error: ProviderError,
    ) {
        val previous = snapshot(provider)
        val failures = previous.consecutiveFailures + 1
        snapshots[provider] =
            previous.copy(
                state =
                    if (failures >= unavailableAfterFailures) {
                        ProviderHealthState.UNAVAILABLE
                    } else {
                        ProviderHealthState.DEGRADED
                    },
                consecutiveFailures = failures,
                lastFailureAtMillis = clock(),
                retryAfterMillis = error.retryAfterMillis,
            )
    }

    @Synchronized
    fun snapshot(provider: ProviderId): ProviderHealthSnapshot =
        snapshots[provider]
            ?: ProviderHealthSnapshot(
                provider = provider,
                state = ProviderHealthState.UNAVAILABLE,
                consecutiveFailures = 0,
                lastSuccessAtMillis = null,
                lastFailureAtMillis = null,
                lastLatencyMillis = null,
                retryAfterMillis = null,
            )
}

fun interface RequestGate {
    suspend fun awaitTurn()

    data object None : RequestGate {
        override suspend fun awaitTurn() = Unit
    }
}

class FixedIntervalRequestGate(
    private val minimumIntervalMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) : RequestGate {
    private val mutex = Mutex()
    private var previousRequestAtMillis: Long? = null

    init {
        require(minimumIntervalMillis > 0)
    }

    override suspend fun awaitTurn() {
        mutex.withLock {
            val previous = previousRequestAtMillis
            if (previous != null) {
                val remaining = minimumIntervalMillis - (clock() - previous)
                if (remaining > 0) delay(remaining)
            }
            previousRequestAtMillis = clock()
        }
    }
}

internal val JupiterFreeRequestGate: RequestGate = FixedIntervalRequestGate(1_000)
internal val KrakenPublicRequestGate: RequestGate = FixedIntervalRequestGate(1_000)
