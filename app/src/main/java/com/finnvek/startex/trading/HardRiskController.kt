package com.finnvek.startex.trading

import com.finnvek.startex.domain.EurAmount
import com.finnvek.startex.domain.Lamports
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

enum class ProviderHealth {
    DOWN,
    DEGRADED,
    HEALTHY,
}

@Suppress("LongParameterList")
data class RiskLimits(
    val maximumSolPerTrade: Lamports,
    val maximumEurPerTrade: EurAmount,
    val maximumOpenExposure: Lamports,
    val maximumOpenPositions: Int,
    val maximumTradesPerRollingDay: Int,
    val maximumDailyRealizedLoss: Lamports,
    val maximumDailyFees: Lamports,
    val maximumConsecutiveLosses: Int,
    val lossCooldown: Duration,
    val failedTransactionCooldown: Duration,
    val minimumWalletReserve: Lamports,
    val maximumSlippageBps: Int,
    val maximumPriorityFee: Lamports,
    val maximumTransactionCost: Lamports,
    val maximumFeeRatioBps: Int,
    val maximumHoldingTime: Duration,
    val maximumCandidateAge: Duration,
    val maximumPreEntryPriceIncreasePercent: BigDecimal,
    val maximumDataAge: Duration,
    val minimumProviderHealth: ProviderHealth,
    val circuitResetDelay: Duration,
) {
    init {
        require(maximumOpenPositions in 1..2) { "Open position limit must be one or two" }
        require(maximumTradesPerRollingDay > 0)
        require(maximumConsecutiveLosses > 0)
        require(maximumSlippageBps in 0..10_000)
        require(maximumFeeRatioBps in 0..10_000)
        require(!lossCooldown.isNegative)
        require(!failedTransactionCooldown.isNegative)
        require(!maximumHoldingTime.isNegative)
        require(!maximumCandidateAge.isNegative)
        require(maximumPreEntryPriceIncreasePercent.signum() >= 0)
        require(!maximumDataAge.isNegative)
        require(!circuitResetDelay.isNegative)
    }
}

data class EntryRiskRequest(
    val tradeAmount: Lamports,
    val approximateEur: EurAmount?,
    val estimatedPriorityFee: Lamports,
    val estimatedTransactionCost: Lamports,
    val estimatedFeeRatioBps: Int,
    val slippageBps: Int,
    val plannedHoldingTime: Duration,
    val candidateAge: Duration,
    val preEntryPriceIncreasePercent: BigDecimal,
    val dataAge: Duration,
    val providerHealth: ProviderHealth,
)

data class RiskSnapshot(
    val now: Instant,
    val walletBalance: Lamports,
    val openExposure: Lamports,
    val openPositions: Int,
    val tradeTimes: List<Instant>,
    val dailyRealizedLoss: Lamports,
    val dailyFees: Lamports,
    val consecutiveLosses: Int,
    val lastLossAt: Instant?,
    val lastFailedTransactionAt: Instant?,
    val circuitBreaker: CircuitBreakerState,
)

enum class RiskRejectionReason {
    CIRCUIT_BREAKER_ACTIVE,
    TRADE_SIZE,
    EUR_TRADE_SIZE,
    TOTAL_EXPOSURE,
    OPEN_POSITION_LIMIT,
    ROLLING_TRADE_LIMIT,
    DAILY_LOSS_LIMIT,
    DAILY_FEE_LIMIT,
    CONSECUTIVE_LOSS_LIMIT,
    LOSS_COOLDOWN,
    FAILED_TRANSACTION_COOLDOWN,
    WALLET_RESERVE,
    SLIPPAGE_LIMIT,
    PRIORITY_FEE_LIMIT,
    TRANSACTION_COST_LIMIT,
    FEE_RATIO_LIMIT,
    HOLDING_TIME_LIMIT,
    CANDIDATE_AGE_LIMIT,
    PRE_ENTRY_PRICE_INCREASE_LIMIT,
    STALE_DATA,
    PROVIDER_HEALTH,
}

enum class CircuitBreakerReason {
    DAILY_LOSS,
    DAILY_FEES,
    PROVIDER_HEALTH,
}

data class CircuitBreakerState(
    val active: Boolean,
    val reason: CircuitBreakerReason?,
    val activatedAt: Instant?,
    val resetAfter: Instant?,
) {
    init {
        require(active == (reason != null && activatedAt != null && resetAfter != null))
    }

    fun reset(
        now: Instant,
        authenticated: Boolean,
    ): CircuitBreakerState {
        check(active) { "Circuit breaker is not active" }
        check(authenticated) { "Authenticated reset is required" }
        check(!now.isBefore(requireNotNull(resetAfter))) { "Circuit breaker reset time has not been reached" }
        return inactive()
    }

    companion object {
        fun inactive() =
            CircuitBreakerState(
                active = false,
                reason = null,
                activatedAt = null,
                resetAfter = null,
            )

        fun active(
            reason: CircuitBreakerReason,
            now: Instant,
            resetDelay: Duration,
        ) = CircuitBreakerState(
            active = true,
            reason = reason,
            activatedAt = now,
            resetAfter = now.plus(resetDelay),
        )
    }
}

data class RiskDecision(
    val reasons: Set<RiskRejectionReason>,
    val circuitBreaker: CircuitBreakerState,
) {
    val entryAllowed: Boolean = reasons.isEmpty()
    val exitProtectionAllowed: Boolean = true
}

class HardRiskController(
    private val limits: RiskLimits,
) {
    fun evaluateEntry(
        snapshot: RiskSnapshot,
        request: EntryRiskRequest,
    ): RiskDecision {
        val reasons = linkedSetOf<RiskRejectionReason>()

        fun rejectIf(
            condition: Boolean,
            reason: RiskRejectionReason,
        ) {
            if (condition) reasons += reason
        }

        rejectIf(snapshot.circuitBreaker.active, RiskRejectionReason.CIRCUIT_BREAKER_ACTIVE)
        rejectIf(request.tradeAmount > limits.maximumSolPerTrade, RiskRejectionReason.TRADE_SIZE)
        rejectIf(
            request.approximateEur?.value?.let { it > limits.maximumEurPerTrade.value } == true,
            RiskRejectionReason.EUR_TRADE_SIZE,
        )
        rejectIf(
            exceedsLimit(limits.maximumOpenExposure, listOf(snapshot.openExposure, request.tradeAmount)),
            RiskRejectionReason.TOTAL_EXPOSURE,
        )
        rejectIf(snapshot.openPositions >= limits.maximumOpenPositions, RiskRejectionReason.OPEN_POSITION_LIMIT)
        val rollingStart = snapshot.now.minus(ROLLING_DAY)
        rejectIf(
            snapshot.tradeTimes.count { !it.isBefore(rollingStart) } >= limits.maximumTradesPerRollingDay,
            RiskRejectionReason.ROLLING_TRADE_LIMIT,
        )
        rejectIf(snapshot.dailyRealizedLoss >= limits.maximumDailyRealizedLoss, RiskRejectionReason.DAILY_LOSS_LIMIT)
        val dailyFeeLimitBreached =
            exceedsLimit(
                limits.maximumDailyFees,
                listOf(snapshot.dailyFees, request.estimatedTransactionCost),
            )
        rejectIf(dailyFeeLimitBreached, RiskRejectionReason.DAILY_FEE_LIMIT)
        rejectIf(
            snapshot.consecutiveLosses >= limits.maximumConsecutiveLosses,
            RiskRejectionReason.CONSECUTIVE_LOSS_LIMIT,
        )
        rejectIf(
            snapshot.lastLossAt?.plus(limits.lossCooldown)?.let(snapshot.now::isBefore) == true,
            RiskRejectionReason.LOSS_COOLDOWN,
        )
        rejectIf(
            snapshot.lastFailedTransactionAt
                ?.plus(limits.failedTransactionCooldown)
                ?.let(snapshot.now::isBefore) == true,
            RiskRejectionReason.FAILED_TRANSACTION_COOLDOWN,
        )
        rejectIf(
            exceedsLimit(
                snapshot.walletBalance,
                listOf(request.tradeAmount, request.estimatedTransactionCost, limits.minimumWalletReserve),
            ),
            RiskRejectionReason.WALLET_RESERVE,
        )
        rejectIf(request.slippageBps > limits.maximumSlippageBps, RiskRejectionReason.SLIPPAGE_LIMIT)
        rejectIf(request.estimatedPriorityFee > limits.maximumPriorityFee, RiskRejectionReason.PRIORITY_FEE_LIMIT)
        rejectIf(
            request.estimatedTransactionCost > limits.maximumTransactionCost,
            RiskRejectionReason.TRANSACTION_COST_LIMIT,
        )
        rejectIf(request.estimatedFeeRatioBps > limits.maximumFeeRatioBps, RiskRejectionReason.FEE_RATIO_LIMIT)
        rejectIf(request.plannedHoldingTime > limits.maximumHoldingTime, RiskRejectionReason.HOLDING_TIME_LIMIT)
        rejectIf(request.candidateAge > limits.maximumCandidateAge, RiskRejectionReason.CANDIDATE_AGE_LIMIT)
        rejectIf(
            request.preEntryPriceIncreasePercent > limits.maximumPreEntryPriceIncreasePercent,
            RiskRejectionReason.PRE_ENTRY_PRICE_INCREASE_LIMIT,
        )
        rejectIf(request.dataAge > limits.maximumDataAge, RiskRejectionReason.STALE_DATA)
        rejectIf(request.providerHealth < limits.minimumProviderHealth, RiskRejectionReason.PROVIDER_HEALTH)

        val circuitBreaker =
            when {
                snapshot.circuitBreaker.active -> {
                    snapshot.circuitBreaker
                }

                snapshot.dailyRealizedLoss >= limits.maximumDailyRealizedLoss -> {
                    activeBreaker(CircuitBreakerReason.DAILY_LOSS, snapshot.now)
                }

                dailyFeeLimitBreached -> {
                    activeBreaker(CircuitBreakerReason.DAILY_FEES, snapshot.now)
                }

                request.providerHealth < limits.minimumProviderHealth -> {
                    activeBreaker(CircuitBreakerReason.PROVIDER_HEALTH, snapshot.now)
                }

                else -> {
                    CircuitBreakerState.inactive()
                }
            }
        return RiskDecision(reasons, circuitBreaker)
    }

    private fun activeBreaker(
        reason: CircuitBreakerReason,
        now: Instant,
    ): CircuitBreakerState = CircuitBreakerState.active(reason, now, limits.circuitResetDelay)

    private fun exceedsLimit(
        limit: Lamports,
        amounts: List<Lamports>,
    ): Boolean {
        var remaining = limit.value
        for (amount in amounts) {
            if (amount.value > remaining) return true
            remaining -= amount.value
        }
        return false
    }

    private companion object {
        val ROLLING_DAY: Duration = Duration.ofHours(24)
    }
}
