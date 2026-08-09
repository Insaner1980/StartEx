package com.finnvek.startex.trading

import com.finnvek.startex.domain.EurAmount
import com.finnvek.startex.domain.Lamports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class HardRiskControllerTest {
    @Test
    fun `hard risk layer reports every breached cap while keeping exits available`() {
        val now = Instant.parse("2026-08-09T12:00:00Z")
        val snapshot =
            safeRiskSnapshot(now).copy(
                openPositions = 1,
                dailyFees = sol("0.02"),
                consecutiveLosses = 3,
            )
        val request =
            safeEntryRequest().copy(
                tradeAmount = sol("0.2"),
                slippageBps = 900,
                providerHealth = ProviderHealth.DOWN,
            )

        val decision = HardRiskController(riskLimits()).evaluateEntry(snapshot, request)

        assertFalse(decision.entryAllowed)
        assertTrue(decision.exitProtectionAllowed)
        assertEquals(
            setOf(
                RiskRejectionReason.TRADE_SIZE,
                RiskRejectionReason.OPEN_POSITION_LIMIT,
                RiskRejectionReason.DAILY_FEE_LIMIT,
                RiskRejectionReason.CONSECUTIVE_LOSS_LIMIT,
                RiskRejectionReason.SLIPPAGE_LIMIT,
                RiskRejectionReason.PROVIDER_HEALTH,
            ),
            decision.reasons,
        )
        assertTrue(decision.circuitBreaker.active)
    }

    @Test
    fun `rolling daily trade limit ignores attempts older than twenty four hours`() {
        val now = Instant.parse("2026-08-09T12:00:00Z")
        val snapshot =
            safeRiskSnapshot(now).copy(
                tradeTimes =
                    listOf(
                        now.minus(Duration.ofHours(25)),
                        now.minus(Duration.ofHours(2)),
                        now.minus(Duration.ofHours(1)),
                    ),
            )

        val decision =
            HardRiskController(riskLimits().copy(maximumTradesPerRollingDay = 2))
                .evaluateEntry(snapshot, safeEntryRequest())

        assertEquals(setOf(RiskRejectionReason.ROLLING_TRADE_LIMIT), decision.reasons)
    }

    @Test
    fun `daily loss circuit breaker resets only after its time and authentication`() {
        val activatedAt = Instant.parse("2026-08-09T12:00:00Z")
        val breaker =
            CircuitBreakerState(
                active = true,
                reason = CircuitBreakerReason.DAILY_LOSS,
                activatedAt = activatedAt,
                resetAfter = activatedAt.plus(Duration.ofHours(12)),
            )

        assertThrows(IllegalStateException::class.java) {
            breaker.reset(activatedAt.plus(Duration.ofHours(13)), authenticated = false)
        }
        assertThrows(IllegalStateException::class.java) {
            breaker.reset(activatedAt.plus(Duration.ofHours(11)), authenticated = true)
        }
        assertEquals(
            CircuitBreakerState.inactive(),
            breaker.reset(activatedAt.plus(Duration.ofHours(12)), authenticated = true),
        )
    }

    @Test
    fun `safe request leaves circuit breaker inactive`() {
        val now = Instant.parse("2026-08-09T12:00:00Z")

        val decision =
            HardRiskController(riskLimits()).evaluateEntry(
                safeRiskSnapshot(now),
                safeEntryRequest(),
            )

        assertTrue(decision.entryAllowed)
        assertEquals(CircuitBreakerState.inactive(), decision.circuitBreaker)
    }

    @Test
    fun `projected transaction cost cannot exceed the daily fee cap`() {
        val now = Instant.parse("2026-08-09T12:00:00Z")
        val snapshot = safeRiskSnapshot(now).copy(dailyFees = sol("0.0195"))

        val decision =
            HardRiskController(riskLimits()).evaluateEntry(
                snapshot,
                safeEntryRequest().copy(estimatedTransactionCost = sol("0.001")),
            )

        assertEquals(setOf(RiskRejectionReason.DAILY_FEE_LIMIT), decision.reasons)
        assertEquals(CircuitBreakerReason.DAILY_FEES, decision.circuitBreaker.reason)
    }

    @Test
    fun `extreme exact amounts reject instead of overflowing`() {
        val now = Instant.parse("2026-08-09T12:00:00Z")
        val maximum = Lamports.of(Long.MAX_VALUE)
        val limits =
            riskLimits().copy(
                maximumSolPerTrade = maximum,
                maximumOpenExposure = maximum,
                maximumDailyFees = maximum,
                maximumTransactionCost = maximum,
                minimumWalletReserve = Lamports.ZERO,
            )
        val request =
            safeEntryRequest().copy(
                tradeAmount = maximum,
                estimatedPriorityFee = Lamports.ZERO,
                estimatedTransactionCost = Lamports.of(1),
            )

        val decision =
            HardRiskController(limits).evaluateEntry(
                safeRiskSnapshot(now).copy(walletBalance = maximum, openExposure = Lamports.of(1)),
                request,
            )

        assertTrue(RiskRejectionReason.TOTAL_EXPOSURE in decision.reasons)
        assertTrue(RiskRejectionReason.WALLET_RESERVE in decision.reasons)
    }

    private fun riskLimits() =
        RiskLimits(
            maximumSolPerTrade = sol("0.1"),
            maximumEurPerTrade = EurAmount.of(BigDecimal("20")),
            maximumOpenExposure = sol("0.2"),
            maximumOpenPositions = 1,
            maximumTradesPerRollingDay = 10,
            maximumDailyRealizedLoss = sol("0.05"),
            maximumDailyFees = sol("0.02"),
            maximumConsecutiveLosses = 3,
            lossCooldown = Duration.ofMinutes(30),
            failedTransactionCooldown = Duration.ofMinutes(5),
            minimumWalletReserve = sol("0.05"),
            maximumSlippageBps = 500,
            maximumPriorityFee = sol("0.001"),
            maximumTransactionCost = sol("0.01"),
            maximumFeeRatioBps = 500,
            maximumHoldingTime = Duration.ofMinutes(20),
            maximumCandidateAge = Duration.ofMinutes(5),
            maximumPreEntryPriceIncreasePercent = BigDecimal("100"),
            maximumDataAge = Duration.ofSeconds(30),
            minimumProviderHealth = ProviderHealth.HEALTHY,
            circuitResetDelay = Duration.ofHours(12),
        )

    private fun safeRiskSnapshot(now: Instant) =
        RiskSnapshot(
            now = now,
            walletBalance = sol("1"),
            openExposure = Lamports.ZERO,
            openPositions = 0,
            tradeTimes = emptyList(),
            dailyRealizedLoss = Lamports.ZERO,
            dailyFees = Lamports.ZERO,
            consecutiveLosses = 0,
            lastLossAt = null,
            lastFailedTransactionAt = null,
            circuitBreaker = CircuitBreakerState.inactive(),
        )

    private fun safeEntryRequest() =
        EntryRiskRequest(
            tradeAmount = sol("0.05"),
            approximateEur = EurAmount.of(BigDecimal("6.25")),
            estimatedPriorityFee = sol("0.0001"),
            estimatedTransactionCost = sol("0.001"),
            estimatedFeeRatioBps = 200,
            slippageBps = 300,
            plannedHoldingTime = Duration.ofMinutes(10),
            candidateAge = Duration.ofMinutes(1),
            preEntryPriceIncreasePercent = BigDecimal("25"),
            dataAge = Duration.ofSeconds(5),
            providerHealth = ProviderHealth.HEALTHY,
        )

    private fun sol(value: String): Lamports = Lamports.fromSol(BigDecimal(value))
}
