package com.finnvek.startex.trading

import com.finnvek.startex.data.local.DailyPerformanceEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class PaperFinalStateGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

enum class PaperClosedTradeResult {
    WIN,
    LOSS,
}

data class PaperPerformanceDelta(
    val grossPnlLamports: Long = 0,
    val netPnlLamports: Long = 0,
    val totalFeesLamports: Long = 0,
    val tradeCount: Int = 0,
    val closedTradeResult: PaperClosedTradeResult? = null,
) {
    init {
        require(totalFeesLamports >= 0)
        require(tradeCount >= 0)
    }
}

internal fun applyPaperPerformanceDelta(
    current: DailyPerformanceEntity?,
    epochDay: Long,
    delta: PaperPerformanceDelta,
    nowMillis: Long,
    previous: DailyPerformanceEntity? = null,
): DailyPerformanceEntity {
    val currentDay =
        current?.takeIf {
            it.epochDay == epochDay && it.mode == DailyPerformanceEntity.MODE_PAPER
        }
    val priorState =
        previous?.takeIf {
            it.epochDay < epochDay && it.mode == DailyPerformanceEntity.MODE_PAPER
        }
    val base =
        currentDay ?: DailyPerformanceEntity(
            epochDay = epochDay,
            grossPnlLamports = 0,
            netPnlLamports = 0,
            totalFeesLamports = 0,
            tradeCount = 0,
            winCount = 0,
            lossCount = 0,
            consecutiveLosses = priorState?.consecutiveLosses ?: 0,
            updatedAtMillis = nowMillis,
            mode = DailyPerformanceEntity.MODE_PAPER,
            lastLossAtMillis = priorState?.lastLossAtMillis,
            circuitBreakerReason = priorState?.circuitBreakerReason,
            circuitBreakerActivatedAtMillis = priorState?.circuitBreakerActivatedAtMillis,
            circuitBreakerResetAfterMillis = priorState?.circuitBreakerResetAfterMillis,
        )
    val wins = Math.addExact(base.winCount, if (delta.closedTradeResult == PaperClosedTradeResult.WIN) 1 else 0)
    val losses =
        Math.addExact(
            base.lossCount,
            if (delta.closedTradeResult == PaperClosedTradeResult.LOSS) 1 else 0,
        )
    val countedTrades = Math.addExact(base.tradeCount, delta.tradeCount)
    return base.copy(
        grossPnlLamports = Math.addExact(base.grossPnlLamports, delta.grossPnlLamports),
        netPnlLamports = Math.addExact(base.netPnlLamports, delta.netPnlLamports),
        totalFeesLamports = Math.addExact(base.totalFeesLamports, delta.totalFeesLamports),
        tradeCount = maxOf(countedTrades, Math.addExact(wins, losses)),
        winCount = wins,
        lossCount = losses,
        consecutiveLosses =
            when (delta.closedTradeResult) {
                PaperClosedTradeResult.WIN -> 0
                PaperClosedTradeResult.LOSS -> Math.addExact(base.consecutiveLosses, 1)
                null -> base.consecutiveLosses
            },
        lastLossAtMillis =
            when (delta.closedTradeResult) {
                PaperClosedTradeResult.WIN -> null
                PaperClosedTradeResult.LOSS -> nowMillis
                null -> base.lastLossAtMillis
            },
        updatedAtMillis = nowMillis,
    )
}

internal fun applyPaperCircuitBreaker(
    current: DailyPerformanceEntity?,
    epochDay: Long,
    breaker: CircuitBreakerState,
    nowMillis: Long,
    previous: DailyPerformanceEntity? = null,
): DailyPerformanceEntity {
    require(breaker.active)
    val base =
        applyPaperPerformanceDelta(
            current = current,
            epochDay = epochDay,
            delta = PaperPerformanceDelta(),
            nowMillis = nowMillis,
            previous = previous,
        )
    return base.copy(
        circuitBreakerReason = requireNotNull(breaker.reason).name,
        circuitBreakerActivatedAtMillis = requireNotNull(breaker.activatedAt).toEpochMilli(),
        circuitBreakerResetAfterMillis = requireNotNull(breaker.resetAfter).toEpochMilli(),
        updatedAtMillis = nowMillis,
    )
}

internal fun DailyPerformanceEntity.paperCircuitBreakerState(): CircuitBreakerState? {
    val reasonName = circuitBreakerReason
    val activatedAtMillis = circuitBreakerActivatedAtMillis
    val resetAfterMillis = circuitBreakerResetAfterMillis
    if (reasonName == null && activatedAtMillis == null && resetAfterMillis == null) {
        return CircuitBreakerState.inactive()
    }
    val reason = CircuitBreakerReason.entries.firstOrNull { it.name == (reasonName ?: return null) } ?: return null
    val activatedAt = Instant.ofEpochMilli(activatedAtMillis ?: return null)
    val resetAfter = Instant.ofEpochMilli(resetAfterMillis ?: return null)
    if (resetAfter.isBefore(activatedAt)) return null
    return runCatching {
        CircuitBreakerState(
            active = true,
            reason = reason,
            activatedAt = activatedAt,
            resetAfter = resetAfter,
        )
    }.getOrNull()
}

internal fun DailyPerformanceEntity.resetPaperCircuitBreaker(
    now: Instant,
    authenticated: Boolean,
): DailyPerformanceEntity? {
    val state = paperCircuitBreakerState() ?: return null
    if (!state.active) return this
    return runCatching { state.reset(now, authenticated) }
        .getOrNull()
        ?.let {
            copy(
                circuitBreakerReason = null,
                circuitBreakerActivatedAtMillis = null,
                circuitBreakerResetAfterMillis = null,
                updatedAtMillis = now.toEpochMilli(),
            )
        }
}
