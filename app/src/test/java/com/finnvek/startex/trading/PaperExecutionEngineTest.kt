package com.finnvek.startex.trading

import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.domain.TokenAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration

class PaperExecutionEngineTest {
    @Test
    fun `paper buy waits refreshes and applies pessimistic slippage and every known cost`() {
        val sleeper = RecordingSleeper()
        val provider =
            FixedPaperQuoteProvider(
                buyQuote =
                    PaperBuyQuote(
                        expectedOutputAtomic = BigInteger.valueOf(900_000),
                        routeAvailable = true,
                    ),
            )
        val engine = PaperExecutionEngine(provider, sleeper)
        val request =
            PaperBuyRequest(
                input = sol("0.05"),
                tokenDecimals = 6,
                initialQuote = PaperBuyQuote(BigInteger.valueOf(1_000_000), routeAvailable = true),
                costs =
                    PaperCosts(
                        solCosts =
                            TradeCosts(
                                baseFee = Lamports.of(5_000),
                                priorityFee = Lamports.of(2_000),
                                associatedTokenRent = Lamports.of(100_000),
                            ),
                        tokenTransferFeeAtomic = BigInteger.valueOf(1_000),
                    ),
            )

        val outcome =
            engine.executeBuy(
                request,
                PaperExecutionConfig(Duration.ofSeconds(2), executionSlippageBps = 100),
            )

        require(outcome is PaperBuyOutcome.Filled)
        assertEquals(listOf(Duration.ofSeconds(2)), sleeper.delays)
        assertEquals(BigInteger.valueOf(890_000), outcome.received.atomicUnits)
        assertEquals(BigInteger.valueOf(50_107_000), outcome.totalSolDebited.value)
        assertEquals(request.costs, outcome.costs)
    }

    @Test
    fun `paper sell uses refreshed full position quote instead of optimistic first quote`() {
        val provider =
            FixedPaperQuoteProvider(
                sellQuote = PaperSellQuote(sol("0.1"), routeAvailable = true),
            )
        val engine = PaperExecutionEngine(provider, RecordingSleeper())
        val request =
            PaperSellRequest(
                input = TokenAmount.ofAtomic(BigInteger.valueOf(1_000_000), decimals = 6),
                initialQuote = PaperSellQuote(sol("0.12"), routeAvailable = true),
                costs = PaperCosts(solCosts = TradeCosts(dexFee = sol("0.003"))),
                reason = ExitReason.HARD_STOP_LOSS,
            )

        val outcome =
            engine.executeSell(
                request,
                PaperExecutionConfig(Duration.ZERO, executionSlippageBps = 200),
            )

        require(outcome is PaperSellOutcome.Filled)
        assertEquals(BigInteger.valueOf(95_000_000), outcome.netSolReceived.value)
        assertEquals(ExitReason.HARD_STOP_LOSS, outcome.reason)
    }

    @Test
    fun `paper execution models a route disappearing after latency`() {
        val engine = PaperExecutionEngine(FixedPaperQuoteProvider(), RecordingSleeper())
        val request =
            PaperBuyRequest(
                input = sol("0.05"),
                tokenDecimals = 6,
                initialQuote = PaperBuyQuote(BigInteger.valueOf(1_000_000), routeAvailable = true),
                costs = PaperCosts(),
            )

        val outcome =
            engine.executeBuy(
                request,
                PaperExecutionConfig(Duration.ofSeconds(1), executionSlippageBps = 100),
            )

        assertTrue(outcome is PaperBuyOutcome.Rejected)
        assertEquals(PaperFailureReason.ROUTE_UNAVAILABLE, (outcome as PaperBuyOutcome.Rejected).reason)
    }

    private class RecordingSleeper : SimulationSleeper {
        val delays = mutableListOf<Duration>()

        override fun wait(duration: Duration) {
            delays += duration
        }
    }

    private class FixedPaperQuoteProvider(
        private val buyQuote: PaperBuyQuote? = null,
        private val sellQuote: PaperSellQuote? = null,
    ) : PaperQuoteProvider {
        override fun refreshBuy(request: PaperBuyRequest): PaperBuyQuote? = buyQuote

        override fun refreshSell(request: PaperSellRequest): PaperSellQuote? = sellQuote
    }

    private fun sol(value: String): Lamports = Lamports.fromSol(BigDecimal(value))
}
