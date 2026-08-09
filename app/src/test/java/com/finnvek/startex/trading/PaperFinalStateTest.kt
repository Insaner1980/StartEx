package com.finnvek.startex.trading

import com.finnvek.startex.data.local.DailyPerformanceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PaperFinalStateTest {
    @Test
    fun `paper circuit breaker persists across days and only authenticated eligible reset clears it`() {
        val activatedAt = Instant.parse("2026-08-09T10:00:00Z")
        val breaker =
            CircuitBreakerState.active(
                CircuitBreakerReason.DAILY_FEES,
                activatedAt,
                Duration.ofHours(12),
            )
        val firstDay =
            applyPaperCircuitBreaker(
                current = null,
                epochDay = 1,
                breaker = breaker,
                nowMillis = activatedAt.toEpochMilli(),
            )
        val nextDay =
            applyPaperPerformanceDelta(
                current = null,
                epochDay = 2,
                delta = PaperPerformanceDelta(),
                nowMillis = activatedAt.plus(Duration.ofHours(10)).toEpochMilli(),
                previous = firstDay,
            )

        assertEquals(breaker, nextDay.paperCircuitBreakerState())
        assertEquals(
            null,
            nextDay.resetPaperCircuitBreaker(
                activatedAt.plus(Duration.ofHours(13)),
                authenticated = false,
            ),
        )
        assertEquals(
            null,
            nextDay.resetPaperCircuitBreaker(
                activatedAt.plus(Duration.ofHours(11)),
                authenticated = true,
            ),
        )
        val reset =
            requireNotNull(
                nextDay.resetPaperCircuitBreaker(
                    activatedAt.plus(Duration.ofHours(12)),
                    authenticated = true,
                ),
            )
        assertEquals(CircuitBreakerState.inactive(), reset.paperCircuitBreakerState())
    }

    @Test
    fun `new UTC day and entry updates preserve the global loss streak timestamp`() {
        val previous =
            DailyPerformanceEntity(
                epochDay = 1,
                grossPnlLamports = -100,
                netPnlLamports = -125,
                totalFeesLamports = 25,
                tradeCount = 1,
                winCount = 0,
                lossCount = 1,
                consecutiveLosses = 2,
                updatedAtMillis = 10,
                lastLossAtMillis = 9,
            )

        val result =
            applyPaperPerformanceDelta(
                current = null,
                epochDay = 2,
                delta = PaperPerformanceDelta(totalFeesLamports = 15, tradeCount = 1),
                nowMillis = 20,
                previous = previous,
            )

        assertEquals(2, result.consecutiveLosses)
        assertEquals(9L, result.lastLossAtMillis)
        assertEquals(20L, result.updatedAtMillis)
        assertEquals(15L, result.totalFeesLamports)
    }

    @Test
    fun `serialized entry and exit deltas both survive an interleaving`() =
        runTest {
            val gate = PaperFinalStateGate()
            var performance: DailyPerformanceEntity? = null
            val exitEntered = CompletableDeferred<Unit>()
            val releaseExit = CompletableDeferred<Unit>()

            val exit =
                launch {
                    gate.run {
                        exitEntered.complete(Unit)
                        releaseExit.await()
                        performance =
                            applyPaperPerformanceDelta(
                                current = performance,
                                epochDay = 1,
                                delta =
                                    PaperPerformanceDelta(
                                        grossPnlLamports = -100,
                                        netPnlLamports = -125,
                                        totalFeesLamports = 25,
                                        closedTradeResult = PaperClosedTradeResult.LOSS,
                                    ),
                                nowMillis = 2,
                            )
                    }
                }
            exitEntered.await()
            val entry =
                launch {
                    gate.run {
                        performance =
                            applyPaperPerformanceDelta(
                                current = performance,
                                epochDay = 1,
                                delta = PaperPerformanceDelta(totalFeesLamports = 15, tradeCount = 1),
                                nowMillis = 3,
                            )
                    }
                }

            releaseExit.complete(Unit)
            joinAll(exit, entry)

            val result = requireNotNull(performance)
            assertEquals(-100L, result.grossPnlLamports)
            assertEquals(-125L, result.netPnlLamports)
            assertEquals(40L, result.totalFeesLamports)
            assertEquals(2, result.tradeCount)
            assertEquals(1, result.lossCount)
            assertEquals(1, result.consecutiveLosses)
            assertEquals(DailyPerformanceEntity.MODE_PAPER, result.mode)
        }
}
