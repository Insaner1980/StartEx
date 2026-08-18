package com.finnvek.startex.trading

import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.network.JupiterTokenAudit
import com.finnvek.startex.network.JupiterTokenSnapshot
import com.finnvek.startex.network.JupiterTokenStats
import com.finnvek.startex.network.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class PaperRuntimeSourcesTest {
    @Test
    fun `legacy proof requires exact Pump and Jupiter mint agreement and converts liquidity`() =
        runTest {
            val source =
                safetySource(
                    evidence = safetyEvidence(),
                    rate = PaperSolUsdRate(BigDecimal("200"), NOW.minusSeconds(1)),
                )

            val proof = source.proof(MINT, token(liquidityUsd = BigDecimal("1000")))

            requireNotNull(proof)
            assertEquals(Lamports.of(5_000_000_000), proof.liquidity)
            assertTrue(proof.tokenExtensionsSafe)
            assertTrue(proof.independentProvidersAgree)
            assertTrue(proof.programAllowlisted)
            assertTrue(proof.mintAddressValid)
        }

    @Test
    fun `token 2022 is fail closed without a fresh extension proof`() =
        runTest {
            val missing =
                safetySource(evidence = safetyEvidence())
                    .proof(MINT, token(program = TOKEN_2022_PROGRAM_ID))
            val unsafe =
                safetySource(
                    evidence =
                        safetyEvidence(
                            extensionProof =
                                PaperToken2022ExtensionProof(
                                    mint = MINT,
                                    tokenProgram = TOKEN_2022_PROGRAM_ID,
                                    extensionsSafe = false,
                                    observedAt = NOW,
                                ),
                        ),
                ).proof(MINT, token(program = TOKEN_2022_PROGRAM_ID))
            val safe =
                safetySource(
                    evidence =
                        safetyEvidence(
                            extensionProof =
                                PaperToken2022ExtensionProof(
                                    mint = MINT,
                                    tokenProgram = TOKEN_2022_PROGRAM_ID,
                                    extensionsSafe = true,
                                    observedAt = NOW,
                                ),
                        ),
                ).proof(MINT, token(program = TOKEN_2022_PROGRAM_ID))
            val stale =
                safetySource(
                    evidence =
                        safetyEvidence(
                            extensionProof =
                                PaperToken2022ExtensionProof(
                                    mint = MINT,
                                    tokenProgram = TOKEN_2022_PROGRAM_ID,
                                    extensionsSafe = true,
                                    observedAt = NOW.minusSeconds(31),
                                ),
                        ),
                ).proof(MINT, token(program = TOKEN_2022_PROGRAM_ID))

            assertNull(missing)
            requireNotNull(unsafe)
            assertFalse(unsafe.tokenExtensionsSafe)
            requireNotNull(safe)
            assertTrue(safe.tokenExtensionsSafe)
            assertNull(stale)
        }

    @Test
    fun `safety proof rejects invalid provider agreement stale rates and unknown programs`() =
        runTest {
            val mismatched =
                safetySource(evidence = safetyEvidence(pumpMint = SYSTEM_PROGRAM))
                    .proof(MINT, token())
            val stale =
                safetySource(
                    evidence = safetyEvidence(),
                    rate = PaperSolUsdRate(BigDecimal("200"), NOW.minusSeconds(31)),
                ).proof(MINT, token())
            val unknownProgram =
                safetySource(evidence = safetyEvidence())
                    .proof(MINT, token(program = SYSTEM_PROGRAM))
            val invalidAddress =
                safetySource(evidence = safetyEvidence(pumpMint = "not-a-solana-address"))
                    .proof("not-a-solana-address", token().copy(mint = "not-a-solana-address"))

            assertNull(mismatched)
            assertNull(stale)
            assertNull(unknownProgram)
            assertNull(invalidAddress)
        }

    @Test
    fun `liquidity conversion uses BigDecimal and conservatively floors fractional lamports`() {
        assertEquals(
            Lamports.of(333_333_333),
            liquidityLamports(BigDecimal.ONE, BigDecimal("3")),
        )
        assertNull(liquidityLamports(BigDecimal.ONE, BigDecimal.ZERO))
        assertNull(liquidityLamports(BigDecimal("0.000000001"), BigDecimal("2")))
    }

    @Test
    fun `filter factory maps persisted limits and rejects invalid configuration`() {
        val config = PaperCandidateFilterConfigFactory.create(strategy(), risk())

        requireNotNull(config)
        assertEquals(Lamports.of(5_000_000_000), config.minimumLiquidity)
        assertEquals(300, config.maximumBuyPriceImpactBps)
        assertEquals(2_000, config.maximumFeeRatioBps)
        assertEquals(Duration.ofMinutes(5), config.maximumTokenAge)
        assertEquals(BigDecimal("20.00"), config.maximumPreEntryPriceIncreasePercent)
        assertEquals(Duration.ofSeconds(15), config.maximumDataAge)
        assertNull(PaperCandidateFilterConfigFactory.create(strategy(), risk().copy(maximumSlippageBps = -1)))
    }

    @Test
    fun `risk facts use exact EUR estimate and complete wallet position performance and health facts`() =
        runTest {
            val runtime = runtimeSnapshot(openPositions = listOf(position()))
            val source = riskSource(runtime)

            val facts = source.facts(SESSION_ID, risk(), NOW)

            requireNotNull(facts)
            assertEquals(Lamports.of(100_000_000), facts.snapshot.walletBalance)
            assertEquals(Lamports.of(11_000_000), facts.snapshot.openExposure)
            assertEquals(1, facts.snapshot.openPositions)
            assertEquals(Lamports.of(2_000_000), facts.snapshot.dailyRealizedLoss)
            assertEquals(Lamports.of(100_000), facts.snapshot.dailyFees)
            assertEquals(1, facts.snapshot.consecutiveLosses)
            assertEquals(0, BigDecimal("2.50123").compareTo(requireNotNull(facts.approximateTradeEur).value))
            assertEquals(ProviderHealth.HEALTHY, facts.providerHealth)
            assertEquals(runtime.dailyPerformance, facts.dailyPerformance)
        }

    @Test
    fun `risk facts expose persisted circuit breaker and reject a mismatched snapshot`() =
        runTest {
            val baseline = runtimeSnapshot()
            val breaker =
                CircuitBreakerState.active(
                    CircuitBreakerReason.DAILY_FEES,
                    NOW.minusSeconds(30),
                    Duration.ofHours(12),
                )
            val performance =
                applyPaperCircuitBreaker(
                    current = baseline.dailyPerformance,
                    epochDay = baseline.dailyPerformance.epochDay,
                    breaker = breaker,
                    nowMillis = NOW.toEpochMilli(),
                )
            val runtime =
                baseline.copy(
                    dailyPerformance = performance,
                    circuitBreaker = breaker,
                )

            val facts = riskSource(runtime).facts(SESSION_ID, risk(), NOW)

            requireNotNull(facts)
            assertEquals(breaker, facts.snapshot.circuitBreaker)
            assertNull(
                riskSource(runtime.copy(circuitBreaker = CircuitBreakerState.inactive()))
                    .facts(SESSION_ID, risk(), NOW),
            )
        }

    @Test
    fun `risk facts reject missing stale or invalid runtime inputs`() =
        runTest {
            val missingProvider =
                runtimeSnapshot(
                    providerHealth = healthyProviders().dropLast(1),
                )
            val staleRuntime = runtimeSnapshot(observedAt = NOW.minusSeconds(16))
            val invalidWallet = runtimeSnapshot(walletBalanceLamports = -1)

            assertNull(riskSource(null).facts(SESSION_ID, risk(), NOW))
            assertNull(riskSource(missingProvider).facts(SESSION_ID, risk(), NOW))
            assertNull(riskSource(staleRuntime).facts(SESSION_ID, risk(), NOW))
            assertNull(riskSource(invalidWallet).facts(SESSION_ID, risk(), NOW))
        }

    @Test
    fun `SOL-sized risk facts do not depend on a usable EUR rate`() =
        runTest {
            val unusableRates =
                listOf(
                    null,
                    PaperSolEurRate(BigDecimal("250"), NOW.minusSeconds(16)),
                    PaperSolEurRate(BigDecimal("250"), NOW.plusSeconds(1)),
                    PaperSolEurRate(BigDecimal("-1"), NOW),
                )

            unusableRates.forEach { rate ->
                val facts = riskSource(runtimeSnapshot(), rate).facts(SESSION_ID, risk(), NOW)

                requireNotNull(facts)
                assertNull(facts.approximateTradeEur)
            }
        }

    private fun safetySource(
        evidence: PaperCandidateSafetyEvidence?,
        rate: PaperSolUsdRate? = PaperSolUsdRate(BigDecimal("200"), NOW),
    ) = DefaultPaperCandidateSafetyProofSource(
        evidenceSource = PaperCandidateSafetyEvidenceSource { evidence },
        solUsdRates = PaperSolUsdRateSource { rate },
        maximumSourceAge = Duration.ofSeconds(30),
        clock = { NOW },
    )

    private fun safetyEvidence(
        pumpMint: String = MINT,
        extensionProof: PaperToken2022ExtensionProof? = null,
    ) = PaperCandidateSafetyEvidence(
        pumpMint = pumpMint,
        suspiciousWalletActivity = false,
        quoteSemanticsValidated = true,
        unsupportedRouteBehavior = false,
        observedAt = NOW,
        token2022ExtensionProof = extensionProof,
    )

    // CPD-OFF
    private fun token(
        program: String = LEGACY_TOKEN_PROGRAM_ID,
        liquidityUsd: BigDecimal = BigDecimal("1000"),
    ) = JupiterTokenSnapshot(
        mint = MINT,
        name = "Fixture Token",
        symbol = "FIX",
        decimals = 9,
        tokenProgram = program,
        holderCount = 100,
        liquidityUsd = liquidityUsd,
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
                developerBalancePercentage = BigDecimal("5"),
                developerMintCount = 1,
            ),
        stats5m =
            JupiterTokenStats(
                organicBuyVolumeUsd = BigDecimal("100"),
                organicSellVolumeUsd = BigDecimal("50"),
                buyCount = 20,
                sellCount = 10,
                traderCount = 15,
                organicBuyerCount = 12,
                netBuyerCount = 8,
            ),
        updatedAtMillis = NOW.minusSeconds(1).toEpochMilli(),
    )

    // CPD-ON
    private fun riskSource(
        runtime: PaperRiskRuntimeSnapshot?,
        rate: PaperSolEurRate? = PaperSolEurRate(BigDecimal("250.123"), NOW),
    ) = DefaultPaperRiskFactsSource(
        runtimeSnapshots = PaperRiskRuntimeSnapshotSource { _, _ -> runtime },
        solEurRates = PaperSolEurRateSource { rate },
    )

    private fun runtimeSnapshot(
        observedAt: Instant = NOW,
        walletBalanceLamports: Long = 100_000_000,
        openPositions: List<PositionEntity> = emptyList(),
        providerHealth: List<ProviderHealthEntity> = healthyProviders(),
    ) = PaperRiskRuntimeSnapshot(
        observedAt = observedAt,
        walletBalanceLamports = walletBalanceLamports,
        openPositions = openPositions,
        rollingTradeTimes = listOf(NOW.minusSeconds(60)),
        dailyPerformance =
            DailyPerformanceEntity(
                epochDay = NOW.epochDay(),
                grossPnlLamports = -1_900_000,
                netPnlLamports = -2_000_000,
                totalFeesLamports = 100_000,
                tradeCount = 1,
                winCount = 0,
                lossCount = 1,
                consecutiveLosses = 1,
                updatedAtMillis = NOW.toEpochMilli(),
                lastLossAtMillis = NOW.minusSeconds(60).toEpochMilli(),
            ),
        providerHealth = providerHealth,
        lastLossAt = NOW.minusSeconds(60),
        lastFailedTransactionAt = null,
        circuitBreaker = CircuitBreakerState.inactive(),
    )

    private fun healthyProviders() =
        REQUIRED_PROVIDERS.map { provider ->
            ProviderHealthEntity(
                provider = provider.name,
                state = "HEALTHY",
                consecutiveFailures = 0,
                lastSuccessAtMillis = NOW.toEpochMilli(),
                lastFailureAtMillis = null,
                latencyMillis = 100,
                retryAfterMillis = null,
                lastFailureCode = null,
                updatedAtMillis = NOW.toEpochMilli(),
            )
        }

    private fun position() =
        PositionEntity(
            id = "position-1",
            sessionId = SESSION_ID,
            mint = MINT,
            entryDecisionId = "decision-1",
            status = "OPEN",
            tokenAmountAtomic = "1",
            grossInputLamports = 11_000_000,
            netInputLamports = 10_000_000,
            latestSellQuoteLamports = 9_000_000,
            entrySignature = null,
            exitSignature = null,
            openedAtMillis = NOW.minusSeconds(60).toEpochMilli(),
            updatedAtMillis = NOW.toEpochMilli(),
            closedAtMillis = null,
            exitReason = null,
            mode = "PAPER",
            symbol = "FIX",
            name = "Fixture Token",
            tokenDecimals = 9,
            tokenProgram = LEGACY_TOKEN_PROGRAM_ID,
            entryCostLamports = 1_000_000,
            exitRulesVersion = "1:1",
            exitRulesJson = "{}",
            latestSellQuoteAtMillis = NOW.toEpochMilli(),
            highestExecutableSellLamports = 9_000_000,
            lowestExecutableSellLamports = 9_000_000,
            routeAvailable = true,
            reconciliationState = "RECONCILED",
        )

    private fun strategy() =
        StrategyConfigEntity(
            version = 1,
            minimumEntryScore = 75,
            minimumObservationMillis = 60_000,
            maximumCandidateAgeMillis = 300_000,
            minimumLiquidityLamports = 5_000_000_000,
            minimumSellOutputLamports = 5_000_000,
            requiredSnapshotCount = 3,
            takeProfitBps = 2_500,
            hardStopLossBps = 1_500,
            trailingActivationBps = 1_500,
            trailingDistanceBps = 800,
            minimumExitSafetyScore = 50,
            weightsJson = "{\"organicActivity\":1}",
            createdAtMillis = NOW.toEpochMilli(),
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
            lossCooldownMillis = 3_600_000,
            failedTransactionCooldownMillis = 900_000,
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
    private fun Instant.epochDay(): Long = atOffset(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay()

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T12:00:00Z")
        val REQUIRED_PROVIDERS = listOf(ProviderId.HELIUS, ProviderId.PUMP_PORTAL, ProviderId.JUPITER)
        const val SESSION_ID = "session-1"
        const val MINT = "So11111111111111111111111111111111111111112"
        const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
        const val LEGACY_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnEKS3sZDJR9L"
    }
}
