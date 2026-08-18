package com.finnvek.startex.trading

import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.DecisionEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TokenSnapshotEntity
import com.finnvek.startex.domain.CandidateFilterConfig
import com.finnvek.startex.domain.EurAmount
import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.network.JupiterTokenAudit
import com.finnvek.startex.network.JupiterTokenSnapshot
import com.finnvek.startex.network.JupiterTokenStats
import com.finnvek.startex.network.JupiterTokensProvider
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

class PaperCandidateCoordinatorTest {
    @Test
    fun `completed mint history is a bounded access ordered LRU`() {
        val history = BoundedMintHistory(maximumSize = 2)

        assertTrue(history.add("mint-a"))
        assertTrue(history.add("mint-b"))
        assertEquals(false, history.add("mint-a"))
        assertTrue(history.add("mint-c"))
        assertTrue(history.add("mint-b"))
    }

    @Test
    fun `missing independent safety proof rejects after persisting the configured real observations`() =
        runTest {
            val clock = AdvancingClock(100_000)
            val quotes = RecordingQuoteSource()
            val persistence = RecordingPersistence()
            val coordinator =
                coordinator(
                    clock = clock,
                    tokens = SequenceTokens(clock),
                    quotes = quotes,
                    proofs = PaperCandidateSafetyProofSource { _, _ -> null },
                    persistence = persistence,
                )

            val result = coordinator.process(request())

            require(result is PaperCandidateResult.Rejected)
            assertTrue("SAFETY_PROOF_MISSING" in result.reasons)
            assertTrue("INCOMPLETE_CRITICAL_DATA" in result.reasons)
            assertEquals(2, result.snapshotCount)
            assertEquals(2, persistence.snapshots.size)
            assertEquals(listOf(1_000L), clock.waits)
            assertEquals(4, quotes.requests.size)
            assertTrue(quotes.requests.all { it.taker == null })
            assertEquals(
                "REJECT",
                persistence.decisions
                    .single()
                    .second.action,
            )
        }

    @Test
    fun `complete candidate applies domain decision and hard risk then requotes through paper engine`() =
        runTest {
            val clock = AdvancingClock(100_000)
            val quotes = RecordingQuoteSource()
            val persistence = RecordingPersistence(fillSupported = true)
            val coordinator =
                coordinator(
                    clock = clock,
                    tokens = SequenceTokens(clock),
                    quotes = quotes,
                    proofs = PaperCandidateSafetyProofSource { _, _ -> completeProof() },
                    persistence = persistence,
                    riskFacts = PaperRiskFactsSource { _, _, now -> safeRiskFacts(now) },
                )

            val result = coordinator.process(request())

            require(result is PaperCandidateResult.Filled)
            assertEquals(80, result.score)
            assertEquals(1, persistence.fills.size)
            assertEquals(
                "PAPER",
                persistence.fills
                    .single()
                    .position.mode,
            )
            assertEquals(
                "PAPER_FILLED",
                persistence.fills
                    .single()
                    .intent.status,
            )
            assertEquals(listOf(1_000L, 2_000L), clock.waits)
            assertEquals(7, quotes.requests.size)
            assertTrue(quotes.requests.all { it.taker == null })
            assertTrue(persistence.snapshots.all(TokenSnapshotEntity::routeAvailable))
            assertTrue(persistence.snapshots.all { it.source == "JUPITER_TOKENS_VALIDATED_QUOTE_PAIR" })
        }

    @Test
    fun `source quote claim alone cannot replace a validated paper quote pair`() =
        runTest {
            val clock = AdvancingClock(100_000)
            val quotes =
                RecordingQuoteSource { request, order ->
                    if (request.inputMint == MINT) order.copy(unsignedTransactionBase64 = "unexpected-transaction") else order
                }
            val persistence = RecordingPersistence(fillSupported = true)
            val coordinator =
                coordinator(
                    clock = clock,
                    tokens = SequenceTokens(clock),
                    quotes = quotes,
                    proofs =
                        PaperCandidateSafetyProofSource { _, _ ->
                            completeProof().copy(
                                quoteSemanticsValidated = true,
                                unsupportedRouteBehavior = false,
                            )
                        },
                    persistence = persistence,
                    riskFacts = PaperRiskFactsSource { _, _, now -> safeRiskFacts(now) },
                )

            val result = coordinator.process(request())

            require(result is PaperCandidateResult.Rejected)
            assertTrue("QUOTE_PAIR_UNVALIDATED" in result.reasons)
            assertTrue(persistence.fills.isEmpty())
            assertTrue(persistence.snapshots.none(TokenSnapshotEntity::routeAvailable))
            assertTrue(persistence.snapshots.all { it.source == "JUPITER_TOKENS_UNVALIDATED_QUOTE_PAIR" })
        }

    // CPD-OFF
    @Test
    fun `final state reread blocks entry after concurrent fees consume the daily limit`() =
        runTest {
            val clock = AdvancingClock(100_000)
            val persistence =
                RecordingPersistence(
                    fillSupported = true,
                    currentFacts = { _, baseline ->
                        baseline.copy(
                            snapshot = baseline.snapshot.copy(dailyFees = Lamports.of(4_990_000)),
                        )
                    },
                )
            val coordinator =
                coordinator(
                    clock = clock,
                    tokens = SequenceTokens(clock),
                    quotes = RecordingQuoteSource(),
                    proofs = PaperCandidateSafetyProofSource { _, _ -> completeProof() },
                    persistence = persistence,
                    riskFacts = PaperRiskFactsSource { _, _, now -> safeRiskFacts(now) },
                )

            val result = coordinator.process(request())

            require(result is PaperCandidateResult.Rejected)
            assertTrue("RISK_DAILY_FEE_LIMIT" in result.reasons)
            assertEquals(1, persistence.currentRiskReads)
            assertEquals(CircuitBreakerReason.DAILY_FEES, persistence.breakers.single().reason)
            assertTrue(persistence.fills.isEmpty())
            assertEquals(listOf(1_000L, 2_000L), clock.waits)
        }

    @Test
    fun `entry crossing UTC midnight persists only a new-day performance delta`() =
        runTest {
            val start = Instant.parse("2026-08-09T23:59:57.500Z")
            val clock = AdvancingClock(start.toEpochMilli())
            val priorDay = start.atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay()
            val priorPerformance =
                DailyPerformanceEntity(
                    epochDay = priorDay,
                    grossPnlLamports = 99,
                    netPnlLamports = -88,
                    totalFeesLamports = 77,
                    tradeCount = 6,
                    winCount = 2,
                    lossCount = 4,
                    consecutiveLosses = 3,
                    updatedAtMillis = start.toEpochMilli(),
                    lastLossAtMillis = start.minusSeconds(60).toEpochMilli(),
                )
            val persistence = RecordingPersistence(fillSupported = true)
            val coordinator =
                coordinator(
                    clock = clock,
                    tokens = SequenceTokens(clock),
                    quotes = RecordingQuoteSource(),
                    proofs = PaperCandidateSafetyProofSource { _, _ -> completeProof() },
                    persistence = persistence,
                    riskFacts =
                        PaperRiskFactsSource { _, _, now ->
                            safeRiskFacts(now).copy(dailyPerformance = priorPerformance)
                        },
                )

            val rolloverRequest =
                request().let { original ->
                    original.copy(
                        candidate =
                            original.candidate.copy(
                                discoveredAtMillis = start.minusSeconds(30).toEpochMilli(),
                                lastUpdatedAtMillis = start.minusSeconds(30).toEpochMilli(),
                            ),
                    )
                }
            val result = coordinator.process(rolloverRequest)

            require(result is PaperCandidateResult.Filled)
            val fill = persistence.fills.single()
            assertEquals(priorDay + 1, fill.performanceEpochDay)
            assertEquals(15_000L, fill.performanceDelta.totalFeesLamports)
            assertEquals(1, fill.performanceDelta.tradeCount)
            assertEquals(0L, fill.performanceDelta.grossPnlLamports)
            assertEquals(0L, fill.performanceDelta.netPnlLamports)
        }

    @Test
    fun `hard risk blocks an otherwise eligible paper fill before decision latency`() =
        runTest {
            val clock = AdvancingClock(100_000)
            val persistence = RecordingPersistence(fillSupported = true)
            val coordinator =
                coordinator(
                    clock = clock,
                    tokens = SequenceTokens(clock),
                    quotes = RecordingQuoteSource(),
                    proofs = PaperCandidateSafetyProofSource { _, _ -> completeProof() },
                    persistence = persistence,
                    riskFacts =
                        PaperRiskFactsSource { _, _, now ->
                            safeRiskFacts(now).let { facts ->
                                facts.copy(snapshot = facts.snapshot.copy(openPositions = 1))
                            }
                        },
                )

            val result = coordinator.process(request())

            require(result is PaperCandidateResult.Rejected)
            assertTrue("RISK_OPEN_POSITION_LIMIT" in result.reasons)
            assertTrue(persistence.fills.isEmpty())
            assertEquals(listOf(1_000L), clock.waits)
        }

    // CPD-ON
    private fun coordinator(
        clock: AdvancingClock,
        tokens: JupiterTokensProvider,
        quotes: PaperSwapQuoteSource,
        proofs: PaperCandidateSafetyProofSource,
        persistence: RecordingPersistence,
        riskFacts: PaperRiskFactsSource = PaperRiskFactsSource { _, _, _ -> null },
    ) = PaperCandidateCoordinator(
        tokens = tokens,
        quotes = quotes,
        safetyProofs = proofs,
        riskFacts = riskFacts,
        persistence = persistence,
        filterConfig = filterConfig(),
        clock = clock,
        delay = clock,
        decisionToSubmitLatency = Duration.ofSeconds(2),
    )

    private fun request() =
        PaperCandidateRequest(
            sessionId = "session-1",
            candidate =
                TokenCandidateEntity(
                    mint = MINT,
                    source = "PUMP_PORTAL_NEW_TOKEN",
                    discoverySignature = "discovery-signature",
                    creatorAddress = "creator",
                    name = "Fixture Token",
                    symbol = "FIX",
                    metadataUri = null,
                    tokenProgram = null,
                    state = "DISCOVERED",
                    score = null,
                    rejectionCode = null,
                    discoveredAtMillis = 90_000,
                    lastUpdatedAtMillis = 90_000,
                ),
            strategy =
                StrategyConfigEntity(
                    version = 1,
                    minimumEntryScore = 75,
                    minimumObservationMillis = 1_000,
                    maximumCandidateAgeMillis = 60_000,
                    minimumLiquidityLamports = 1,
                    minimumSellOutputLamports = 1,
                    requiredSnapshotCount = 2,
                    takeProfitBps = 2_000,
                    hardStopLossBps = 1_000,
                    trailingActivationBps = 1_000,
                    trailingDistanceBps = 500,
                    minimumExitSafetyScore = 60,
                    weightsJson = "{\"organicActivity\":1}",
                    createdAtMillis = 1,
                ),
            risk =
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
                    maximumCandidateAgeMillis = 60_000,
                    maximumPreEntryPriceIncreaseBps = 2_000,
                    minimumDataFreshnessMillis = 15_000,
                    minimumProviderHealth = "HEALTHY",
                    createdAtMillis = 1,
                ),
        )

    private fun filterConfig() =
        CandidateFilterConfig(
            maximumCreatorHoldingPercent = BigDecimal("10"),
            maximumTopHolderPercent = BigDecimal("30"),
            minimumHolderCount = 10,
            minimumUniqueBuyers = 5,
            minimumBuySellCountRatio = BigDecimal("0.5"),
            minimumBuySellVolumeRatio = BigDecimal("0.5"),
            minimumLiquidity = Lamports.of(1),
            maximumBuyPriceImpactBps = 300,
            maximumRoundTripLossBps = 1_000,
            maximumFeeRatioBps = 2_000,
            maximumTokenAge = Duration.ofMinutes(1),
            maximumPreEntryPriceIncreasePercent = BigDecimal("20"),
            maximumDataAge = Duration.ofSeconds(15),
        )

    private fun completeProof() =
        PaperCandidateSafetyProof(
            liquidity = Lamports.of(6_000_000_000),
            tokenExtensionsSafe = true,
            suspiciousWalletActivity = false,
            independentProvidersAgree = true,
            programAllowlisted = true,
            quoteSemanticsValidated = false,
            unsupportedRouteBehavior = true,
        )

    // CPD-OFF
    private fun safeRiskFacts(now: Instant) =
        PaperRiskFacts(
            snapshot =
                RiskSnapshot(
                    now = now,
                    walletBalance = Lamports.of(100_000_000),
                    openExposure = Lamports.ZERO,
                    openPositions = 0,
                    tradeTimes = emptyList(),
                    dailyRealizedLoss = Lamports.ZERO,
                    dailyFees = Lamports.ZERO,
                    consecutiveLosses = 0,
                    lastLossAt = null,
                    lastFailedTransactionAt = null,
                    circuitBreaker = CircuitBreakerState.inactive(),
                ),
            approximateTradeEur = EurAmount.of(BigDecimal.ONE),
            providerHealth = ProviderHealth.HEALTHY,
            dailyPerformance =
                DailyPerformanceEntity(
                    epochDay = 0,
                    grossPnlLamports = 0,
                    netPnlLamports = 0,
                    totalFeesLamports = 0,
                    tradeCount = 0,
                    winCount = 0,
                    lossCount = 0,
                    consecutiveLosses = 0,
                    updatedAtMillis = now.toEpochMilli(),
                ),
        )
    // CPD-ON

    private class AdvancingClock(
        private var nowMillis: Long,
    ) : PaperCoordinatorClock,
        PaperCoordinatorDelay {
        val waits = mutableListOf<Long>()

        override fun nowMillis(): Long = nowMillis

        override suspend fun wait(duration: Duration) {
            val millis = duration.toMillis()
            waits += millis
            nowMillis += millis
        }
    }

    private class SequenceTokens(
        private val clock: PaperCoordinatorClock,
    ) : JupiterTokensProvider {
        private var calls = 0

        override suspend fun tokenSnapshot(mint: String): ProviderResult<JupiterTokenSnapshot> {
            calls += 1
            return ProviderResult.Success(
                JupiterTokenSnapshot(
                    mint = mint,
                    name = "Fixture Token",
                    symbol = "FIX",
                    decimals = 6,
                    tokenProgram = LEGACY_TOKEN_PROGRAM,
                    holderCount = 100 + calls,
                    liquidityUsd = BigDecimal("1000"),
                    marketCapUsd = BigDecimal("10000"),
                    usdPrice = BigDecimal("0.01"),
                    organicScore = BigDecimal("80"),
                    organicScoreLabel = "high",
                    isVerified = false,
                    audit =
                        JupiterTokenAudit(
                            isSuspicious = false,
                            mintAuthorityDisabled = true,
                            freezeAuthorityDisabled = true,
                            topHoldersPercentage = BigDecimal("20"),
                            developerBalancePercentage = BigDecimal("2"),
                            developerMintCount = 1,
                        ),
                    stats5m =
                        JupiterTokenStats(
                            organicBuyVolumeUsd = BigDecimal("100"),
                            organicSellVolumeUsd = BigDecimal("50"),
                            buyCount = 20,
                            sellCount = 10,
                            traderCount = 15,
                            organicBuyerCount = 10 + calls,
                            netBuyerCount = 5,
                        ),
                    updatedAtMillis = clock.nowMillis(),
                ),
                receivedAtMillis = clock.nowMillis(),
            )
        }
    }

    private class RecordingQuoteSource(
        private val transform: (SwapOrderRequest, SwapOrder) -> SwapOrder = { _, order -> order },
    ) : PaperSwapQuoteSource {
        val requests = mutableListOf<SwapOrderRequest>()

        override suspend fun order(request: SwapOrderRequest): ProviderResult<SwapOrder> {
            requests += request
            val isBuy = request.inputMint == WRAPPED_SOL_MINT
            val output = if (isBuy) "2000000" else "9500000"
            val order =
                SwapOrder(
                    inputMint = request.inputMint,
                    outputMint = request.outputMint,
                    inAmountAtomic = request.amountAtomic.toString(),
                    outAmountAtomic = output,
                    minimumOutAmountAtomic = output,
                    slippageBps = 100,
                    router = "metis",
                    mode = "manual",
                    unsignedTransactionBase64 = null,
                    requestId = "request-${requests.size}",
                    lastValidBlockHeight = null,
                    expireAt = null,
                    feeBps = 0,
                    feeMint = WRAPPED_SOL_MINT,
                    priceImpactPercent = BigDecimal("0.5"),
                    signatureFeeLamports = 5_000,
                    prioritizationFeeLamports = 10_000,
                    rentFeeLamports = 0,
                )
            return ProviderResult.Success(transform(request, order))
        }
    }

    private class RecordingPersistence(
        private val fillSupported: Boolean = false,
        private val currentFacts: (Instant, PaperRiskFacts) -> PaperRiskFacts? = { _, baseline -> baseline },
    ) : PaperCandidatePersistence {
        val snapshots = mutableListOf<TokenSnapshotEntity>()
        val decisions = mutableListOf<Pair<TokenCandidateEntity, DecisionEntity>>()
        val fills = mutableListOf<PaperEntryFacts>()
        val breakers = mutableListOf<CircuitBreakerState>()
        var currentRiskReads = 0

        override suspend fun persistCandidate(candidate: TokenCandidateEntity) = Unit

        override suspend fun persistSnapshot(snapshot: TokenSnapshotEntity): Long {
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun persistDecision(
            candidate: TokenCandidateEntity,
            decision: DecisionEntity,
        ) {
            decisions += candidate to decision
        }

        override suspend fun currentRiskFacts(
            sessionId: String,
            risk: RiskConfigEntity,
            now: Instant,
            baseline: PaperRiskFacts,
        ): PaperRiskFacts? {
            currentRiskReads += 1
            return currentFacts(now, baseline)
        }

        override suspend fun persistCircuitBreaker(
            breaker: CircuitBreakerState,
            now: Instant,
        ) {
            breakers += breaker
        }

        override suspend fun persistPaperFill(facts: PaperEntryFacts): PaperFillPersistenceResult {
            if (!fillSupported) return PaperFillPersistenceResult.UNSUPPORTED
            fills += facts
            return PaperFillPersistenceResult.PERSISTED
        }
    }

    private companion object {
        const val MINT = "Mint333333333333333333333333333333333333333"
        const val LEGACY_TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    }
}
