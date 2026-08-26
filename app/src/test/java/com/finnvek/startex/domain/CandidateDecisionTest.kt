package com.finnvek.startex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class CandidateDecisionTest {
    @Test
    fun `candidate state machine permits the trading path and rejects a skipped state`() {
        assertEquals(
            CandidateState.OBSERVING,
            CandidateStateMachine.transition(CandidateState.DISCOVERED, CandidateState.OBSERVING),
        )
        assertEquals(
            CandidateState.ELIGIBLE,
            CandidateStateMachine.transition(CandidateState.OBSERVING, CandidateState.ELIGIBLE),
        )
        assertThrows(IllegalStateException::class.java) {
            CandidateStateMachine.transition(CandidateState.ELIGIBLE, CandidateState.POSITION_OPEN)
        }
        assertThrows(IllegalStateException::class.java) {
            CandidateStateMachine.transition(CandidateState.CLOSED, CandidateState.DISCOVERED)
        }
    }

    @Test
    fun `hard filters fail closed for an unsafe token and record every reason`() {
        val result =
            CandidateHardFilter.evaluate(
                safeSnapshot().copy(
                    tokenProgram = TokenProgram.TOKEN_2022,
                    hasDangerousTokenExtensions = true,
                    sellRouteAvailable = false,
                    dataAge = Duration.ofSeconds(31),
                ),
                safeFilterConfig(),
            )

        assertFalse(result.passed)
        assertEquals(
            setOf(
                HardRejectionReason.DANGEROUS_TOKEN_EXTENSION,
                HardRejectionReason.SELL_ROUTE_UNAVAILABLE,
                HardRejectionReason.STALE_DATA,
            ),
            result.reasons,
        )
    }

    @Test
    fun `hard filters fail closed when critical data is incomplete`() {
        val result =
            CandidateHardFilter.evaluate(
                safeSnapshot().copy(criticalDataComplete = false),
                safeFilterConfig(),
            )

        assertEquals(setOf(HardRejectionReason.INCOMPLETE_CRITICAL_DATA), result.reasons)
    }

    @Test
    fun `paper quote semantics never satisfy the live transaction proof by default`() {
        val result =
            CandidateHardFilter.evaluate(
                safeSnapshot().copy(
                    quoteAndTransactionDecodable = false,
                    paperQuoteSemanticsValidated = true,
                ),
                safeFilterConfig(),
            )

        assertEquals(setOf(HardRejectionReason.TRANSACTION_DECODE_FAILURE), result.reasons)
    }

    @Test
    fun `rule based score is deterministic and preserves strategy version and evidence`() {
        val engine =
            RuleBasedDecisionEngine(
                StrategyConfig(
                    version = "paper-v1",
                    weights =
                        mapOf(
                            ScoreFactor.BUYER_GROWTH to 3,
                            ScoreFactor.ROUTE_QUALITY to 1,
                        ),
                    minimumScore = 65,
                ),
                safeFilterConfig(),
            )
        val factors =
            mapOf(
                ScoreFactor.BUYER_GROWTH to BigDecimal("0.80"),
                ScoreFactor.ROUTE_QUALITY to BigDecimal("0.40"),
            )

        val result = engine.evaluate(safeSnapshot(), factors)

        assertTrue(result.mandatoryPass)
        assertTrue(result.eligible)
        assertEquals(70, result.score)
        assertEquals(100, result.dataCompletenessPercent)
        assertEquals(listOf(ScoreFactor.BUYER_GROWTH), result.positiveFactors)
        assertEquals(listOf(ScoreFactor.ROUTE_QUALITY), result.negativeFactors)
        assertEquals("paper-v1", result.strategyVersion)
        assertEquals(listOf("snapshot-1"), result.snapshotIds)
        assertEquals(listOf(Instant.parse("2026-08-09T10:00:00Z")), result.snapshotTimes)
    }

    @Test
    fun `decision does not invent a score when mandatory data fails`() {
        val engine =
            RuleBasedDecisionEngine(
                StrategyConfig(
                    version = "paper-v1",
                    weights = mapOf(ScoreFactor.BUYER_GROWTH to 1),
                    minimumScore = 50,
                ),
                safeFilterConfig(),
            )

        val result =
            engine.evaluate(
                safeSnapshot().copy(buyRouteAvailable = false),
                mapOf(ScoreFactor.BUYER_GROWTH to BigDecimal.ONE),
            )

        assertFalse(result.mandatoryPass)
        assertFalse(result.eligible)
        assertEquals(null, result.score)
        assertEquals(setOf(HardRejectionReason.BUY_ROUTE_UNAVAILABLE), result.hardRejectionReasons)
    }

    private fun safeFilterConfig() =
        CandidateFilterConfig(
            maximumCreatorHoldingPercent = BigDecimal("10"),
            maximumTopHolderPercent = BigDecimal("35"),
            minimumHolderCount = 20,
            minimumUniqueBuyers = 10,
            minimumBuySellCountRatio = BigDecimal("1.1"),
            minimumBuySellVolumeRatio = BigDecimal("1.1"),
            minimumLiquidity = Lamports.fromSol(BigDecimal("5")),
            maximumBuyPriceImpactBps = 500,
            maximumRoundTripLossBps = 1_500,
            maximumFeeRatioBps = 500,
            maximumTokenAge = Duration.ofMinutes(5),
            maximumPreEntryPriceIncreasePercent = BigDecimal("150"),
            maximumDataAge = Duration.ofSeconds(30),
        )

    private fun safeSnapshot() =
        CandidateSnapshot(
            snapshotId = "snapshot-1",
            observedAt = Instant.parse("2026-08-09T10:00:00Z"),
            criticalDataComplete = true,
            mintAddressValid = true,
            tokenProgram = TokenProgram.LEGACY,
            hasDangerousTokenExtensions = false,
            mintAuthorityRevoked = true,
            freezeAuthorityRevoked = true,
            providerWarning = false,
            creatorHoldingPercent = BigDecimal("5"),
            topHolderPercent = BigDecimal("20"),
            holderCount = 100,
            uniqueBuyers = 30,
            buyerGrowthPositive = true,
            buyCount = BigDecimal("60"),
            sellCount = BigDecimal("40"),
            buyVolume = BigDecimal("70"),
            sellVolume = BigDecimal("50"),
            organicActivityPresent = true,
            liquidity = Lamports.fromSol(BigDecimal("10")),
            liquidityChangeNegative = false,
            buyPriceImpactBps = 250,
            buyRouteAvailable = true,
            sellRouteAvailable = true,
            roundTripLossBps = 800,
            feeRatioBps = 100,
            tokenAge = Duration.ofMinutes(1),
            preEntryPriceIncreasePercent = BigDecimal("50"),
            suspiciousWalletActivity = false,
            dataAge = Duration.ofSeconds(5),
            providersAgree = true,
            programAllowlistCompatible = true,
            quoteAndTransactionDecodable = true,
            paperQuoteSemanticsValidated = false,
            unsupportedRouteBehavior = false,
        )
}
