package com.finnvek.startex.trading

import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.TradeIntentEntity
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.SwapOrder
import com.finnvek.startex.network.SwapOrderRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class PaperPositionMonitorTest {
    @Test
    fun `take profit closes atomically after latency and a fresh full-position quote`() =
        runTest {
            val persistence = RecordingPersistence()
            val delay = RecordingDelay()
            val quotes =
                QueueQuoteSource(
                    ProviderResult.Success(order(outLamports = 13_000_000, requestId = "initial")),
                    ProviderResult.Success(order(outLamports = 12_000_000, requestId = "refreshed")),
                )
            val monitor = monitor(quotes, persistence, delay)

            val result = monitor.check(position(), risk(), sellNow = false)

            require(result is PaperPositionResult.Closed)
            assertEquals(2, quotes.requests.size)
            assertEquals(listOf(Duration.ofSeconds(2)), delay.durations)
            assertEquals("CLOSED", result.position.status)
            assertEquals(11_874_000L, persistence.closedIntent?.expectedOutputAtomic?.toLong())
            assertEquals("PAPER_FILLED", persistence.closedIntent?.status)
            assertEquals(1_874_000L, persistence.closedDelta?.netPnlLamports)
            assertEquals(1, persistence.closeCalls)
            assertEquals(listOf("PAPER_QUOTE_READY"), persistence.attempts.map { it.status })
        }

    @Test
    fun `cost blocked attempts retain the estimate without recording a paid paper fee`() =
        runTest {
            val persistence = RecordingPersistence()
            val delay = RecordingDelay()
            val blockedOrder =
                order(outLamports = 9_000_000, requestId = "cost-blocked").copy(
                    signatureFeeLamports = 1_000_001,
                    prioritizationFeeLamports = 0,
                )
            val quotes =
                QueueQuoteSource(
                    ProviderResult.Success(order(outLamports = 8_000_000, requestId = "initial")),
                    ProviderResult.Success(blockedOrder.copy(requestId = "blocked-1")),
                    ProviderResult.Success(blockedOrder.copy(requestId = "blocked-2")),
                    ProviderResult.Success(blockedOrder.copy(requestId = "blocked-3")),
                )
            val monitor = monitor(quotes, persistence, delay)

            val result = monitor.check(position(), risk(), sellNow = false)

            require(result is PaperPositionResult.ExitBlocked)
            assertEquals(3, persistence.attempts.size)
            assertTrue(persistence.attempts.all { it.status == "PAPER_COST_BLOCKED" })
            assertTrue(persistence.attempts.all { it.maximumCostLamports == 1_000_001L })
            assertTrue(persistence.attempts.all { it.paperFeeLamports == 0L })
        }

    @Test
    fun `disappearing route uses only bounded immediate quote retries and remains exit blocked`() =
        runTest {
            val persistence = RecordingPersistence()
            val delay = RecordingDelay()
            val quotes =
                QueueQuoteSource(
                    ProviderResult.Success(order(outLamports = 8_000_000, requestId = "initial")),
                    failure(),
                    failure(),
                    failure(),
                )
            val monitor = monitor(quotes, persistence, delay)

            val result = monitor.check(position(), risk(), sellNow = false)

            require(result is PaperPositionResult.ExitBlocked)
            assertEquals(3, result.attempts)
            assertEquals("EXIT_BLOCKED", result.position.status)
            assertEquals(4, quotes.requests.size)
            assertEquals(
                listOf(Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofMillis(500)),
                delay.durations,
            )
            assertEquals(0, persistence.closeCalls)
            assertEquals(3, persistence.attempts.size)
            assertTrue(persistence.attempts.all { it.status == "PAPER_ROUTE_UNAVAILABLE" })
        }

    @Test
    fun `missing safety evidence fails closed and requests a paper exit`() =
        runTest {
            val persistence = RecordingPersistence()
            val quotes =
                QueueQuoteSource(
                    ProviderResult.Success(order(outLamports = 10_000_000, requestId = "initial")),
                    ProviderResult.Success(order(outLamports = 9_900_000, requestId = "refreshed")),
                )
            val monitor =
                PaperPositionMonitor(
                    quotes = quotes,
                    safety = PaperExitSafetySource { _, _ -> null },
                    persistence = persistence,
                    delay = PaperPositionDelay { },
                    clock = { NOW },
                )

            val result = monitor.check(position(), risk(), sellNow = false)

            require(result is PaperPositionResult.Closed)
            assertTrue(ExitReason.TOKEN_UNSAFE in result.reasons)
            assertTrue(ExitReason.SAFETY_SCORE_DETERIORATED in result.reasons)
        }

    private fun monitor(
        quotes: QueueQuoteSource,
        persistence: RecordingPersistence,
        delay: RecordingDelay,
    ) = PaperPositionMonitor(
        quotes = quotes,
        safety = PaperExitSafetySource { _, _ -> safeFacts() },
        persistence = persistence,
        delay = delay,
        clock = { NOW },
    )

    private class QueueQuoteSource(
        vararg responses: ProviderResult<SwapOrder>,
    ) : PaperPositionQuoteSource {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<SwapOrderRequest>()

        override suspend fun quote(request: SwapOrderRequest): ProviderResult<SwapOrder> {
            requests += request
            return responses.removeFirst()
        }
    }

    private class RecordingDelay : PaperPositionDelay {
        val durations = mutableListOf<Duration>()

        override suspend fun wait(duration: Duration) {
            durations += duration
        }
    }

    private class RecordingPersistence : PaperPositionPersistence {
        var closeCalls = 0
        var closedIntent: TradeIntentEntity? = null
        var closedDelta: PaperPerformanceDelta? = null
        var closedEpochDay: Long? = null
        val attempts = mutableListOf<TradeIntentEntity>()

        override suspend fun save(position: PositionEntity) = Unit

        override suspend fun saveAttempt(intent: TradeIntentEntity) {
            attempts += intent
        }

        override suspend fun close(
            position: PositionEntity,
            intent: TradeIntentEntity,
            performanceEpochDay: Long,
            performanceDelta: PaperPerformanceDelta,
            performanceAtMillis: Long,
        ) {
            closeCalls += 1
            closedIntent = intent
            closedEpochDay = performanceEpochDay
            closedDelta = performanceDelta
        }
    }

    private fun position() =
        PositionEntity(
            id = "position-1",
            sessionId = "session-1",
            mint = MINT,
            entryDecisionId = "decision-1",
            status = "OPEN",
            tokenAmountAtomic = "1000000",
            grossInputLamports = 10_000_000,
            netInputLamports = 10_000_000,
            latestSellQuoteLamports = 10_000_000,
            entrySignature = null,
            exitSignature = null,
            openedAtMillis = NOW.minusSeconds(60).toEpochMilli(),
            updatedAtMillis = NOW.minusSeconds(1).toEpochMilli(),
            closedAtMillis = null,
            exitReason = null,
            mode = "PAPER",
            symbol = "FIX",
            name = "Fixture",
            tokenDecimals = 6,
            tokenProgram = LEGACY_TOKEN_PROGRAM,
            entryCostLamports = 0,
            exitRulesVersion = "1:1",
            exitRulesJson =
                "{\"takeProfitBps\":2000,\"hardStopLossBps\":1500," +
                    "\"trailingActivationBps\":1500,\"trailingDistanceBps\":800," +
                    "\"minimumExitSafetyScore\":50,\"maximumHoldingMillis\":600000}",
            latestSellQuoteAtMillis = NOW.minusSeconds(1).toEpochMilli(),
            highestExecutableSellLamports = 10_000_000,
            lowestExecutableSellLamports = 10_000_000,
            routeAvailable = true,
            reconciliationState = "RECONCILED",
        )

    // CPD-OFF
    private fun risk() =
        RiskConfigEntity(
            version = 1,
            maximumTradeLamports = 10_000_000,
            maximumTradeEurCents = 500,
            maximumExposureLamports = 10_000_000,
            maximumOpenPositions = 1,
            maximumTradesPerDay = 10,
            maximumDailyLossLamports = 15_000_000,
            maximumDailyFeesLamports = 5_000_000,
            maximumConsecutiveLosses = 2,
            lossCooldownMillis = 60_000,
            failedTransactionCooldownMillis = 60_000,
            minimumWalletReserveLamports = 5_000_000,
            maximumSlippageBps = 300,
            maximumPriorityFeeLamports = 500_000,
            maximumTransactionCostLamports = 1_000_000,
            maximumFeePercentBps = 2_000,
            maximumHoldingMillis = 600_000,
            maximumCandidateAgeMillis = 300_000,
            maximumPreEntryPriceIncreaseBps = 2_000,
            minimumDataFreshnessMillis = 15_000,
            minimumProviderHealth = "HEALTHY",
            createdAtMillis = NOW.toEpochMilli(),
        )

    // CPD-ON
    private fun order(
        outLamports: Long,
        requestId: String,
    ) = SwapOrder(
        inputMint = MINT,
        outputMint = WRAPPED_SOL_MINT,
        inAmountAtomic = "1000000",
        outAmountAtomic = outLamports.toString(),
        minimumOutAmountAtomic = (outLamports * 95 / 100).toString(),
        slippageBps = 100,
        router = "metis",
        mode = "manual",
        unsignedTransactionBase64 = null,
        requestId = requestId,
        lastValidBlockHeight = null,
        expireAt = null,
        feeBps = 0,
        feeMint = WRAPPED_SOL_MINT,
        priceImpactPercent = BigDecimal("0.1"),
        signatureFeeLamports = 5_000,
        prioritizationFeeLamports = 1_000,
        rentFeeLamports = 0,
    )

    private fun safeFacts() =
        PaperExitSafetyFacts(
            score = 100,
            tokenUnsafe = false,
            momentumCollapsed = false,
            liquidityCollapsed = false,
            suspiciousCreatorActivity = false,
            largeHolderSell = false,
        )

    private fun failure(): ProviderResult.Failure = ProviderResult.Failure(ProviderError.NetworkUnavailable(ProviderId.JUPITER))

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
        const val MINT = "11111111111111111111111111111112"
        const val LEGACY_TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    }
}
