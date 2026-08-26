package com.finnvek.startex.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

enum class CandidateState {
    DISCOVERED,
    OBSERVING,
    REJECTED,
    ELIGIBLE,
    ENTRY_QUEUED,
    ENTRY_SUBMITTED,
    POSITION_OPEN,
    EXIT_QUEUED,
    EXIT_SUBMITTED,
    CLOSED,
    QUARANTINED,
    EXPIRED,
    ROUTE_UNAVAILABLE,
    TRANSACTION_UNCERTAIN,
    FAILED,
}

object CandidateStateMachine {
    private val transitions =
        mapOf(
            CandidateState.DISCOVERED to setOf(CandidateState.OBSERVING, CandidateState.QUARANTINED, CandidateState.FAILED),
            CandidateState.OBSERVING to
                setOf(
                    CandidateState.REJECTED,
                    CandidateState.ELIGIBLE,
                    CandidateState.QUARANTINED,
                    CandidateState.EXPIRED,
                    CandidateState.ROUTE_UNAVAILABLE,
                    CandidateState.FAILED,
                ),
            CandidateState.ELIGIBLE to
                setOf(
                    CandidateState.ENTRY_QUEUED,
                    CandidateState.REJECTED,
                    CandidateState.EXPIRED,
                    CandidateState.ROUTE_UNAVAILABLE,
                    CandidateState.QUARANTINED,
                ),
            CandidateState.ENTRY_QUEUED to
                setOf(
                    CandidateState.ENTRY_SUBMITTED,
                    CandidateState.REJECTED,
                    CandidateState.ROUTE_UNAVAILABLE,
                    CandidateState.FAILED,
                ),
            CandidateState.ENTRY_SUBMITTED to
                setOf(
                    CandidateState.POSITION_OPEN,
                    CandidateState.TRANSACTION_UNCERTAIN,
                    CandidateState.FAILED,
                ),
            CandidateState.POSITION_OPEN to setOf(CandidateState.EXIT_QUEUED, CandidateState.FAILED),
            CandidateState.EXIT_QUEUED to
                setOf(
                    CandidateState.EXIT_SUBMITTED,
                    CandidateState.ROUTE_UNAVAILABLE,
                    CandidateState.FAILED,
                ),
            CandidateState.EXIT_SUBMITTED to
                setOf(
                    CandidateState.CLOSED,
                    CandidateState.TRANSACTION_UNCERTAIN,
                    CandidateState.FAILED,
                ),
            CandidateState.ROUTE_UNAVAILABLE to
                setOf(
                    CandidateState.OBSERVING,
                    CandidateState.EXIT_QUEUED,
                    CandidateState.EXPIRED,
                    CandidateState.FAILED,
                ),
            CandidateState.TRANSACTION_UNCERTAIN to
                setOf(
                    CandidateState.ENTRY_SUBMITTED,
                    CandidateState.POSITION_OPEN,
                    CandidateState.EXIT_SUBMITTED,
                    CandidateState.CLOSED,
                    CandidateState.FAILED,
                ),
        )

    fun transition(
        from: CandidateState,
        to: CandidateState,
    ): CandidateState {
        check(to in transitions.orEmpty(from)) { "Invalid candidate transition: $from -> $to" }
        return to
    }

    private fun <K, V> Map<K, Set<V>>.orEmpty(key: K): Set<V> = this[key].orEmpty()
}

enum class TokenProgram {
    LEGACY,
    TOKEN_2022,
    UNKNOWN,
}

@Suppress("LongParameterList")
data class CandidateFilterConfig(
    val maximumCreatorHoldingPercent: BigDecimal,
    val maximumTopHolderPercent: BigDecimal,
    val minimumHolderCount: Int,
    val minimumUniqueBuyers: Int,
    val minimumBuySellCountRatio: BigDecimal,
    val minimumBuySellVolumeRatio: BigDecimal,
    val minimumLiquidity: Lamports,
    val maximumBuyPriceImpactBps: Int,
    val maximumRoundTripLossBps: Int,
    val maximumFeeRatioBps: Int,
    val maximumTokenAge: Duration,
    val maximumPreEntryPriceIncreasePercent: BigDecimal,
    val maximumDataAge: Duration,
) {
    init {
        require(maximumCreatorHoldingPercent.signum() >= 0)
        require(maximumTopHolderPercent.signum() >= 0)
        require(minimumHolderCount >= 0)
        require(minimumUniqueBuyers >= 0)
        require(minimumBuySellCountRatio.signum() >= 0)
        require(minimumBuySellVolumeRatio.signum() >= 0)
        require(maximumBuyPriceImpactBps >= 0)
        require(maximumRoundTripLossBps >= 0)
        require(maximumFeeRatioBps >= 0)
        require(!maximumTokenAge.isNegative)
        require(maximumPreEntryPriceIncreasePercent.signum() >= 0)
        require(!maximumDataAge.isNegative)
    }
}

@Suppress("LongParameterList")
data class CandidateSnapshot(
    val snapshotId: String,
    val observedAt: Instant,
    val criticalDataComplete: Boolean,
    val mintAddressValid: Boolean,
    val tokenProgram: TokenProgram,
    val hasDangerousTokenExtensions: Boolean,
    val mintAuthorityRevoked: Boolean,
    val freezeAuthorityRevoked: Boolean,
    val providerWarning: Boolean,
    val creatorHoldingPercent: BigDecimal?,
    val topHolderPercent: BigDecimal?,
    val holderCount: Int,
    val uniqueBuyers: Int,
    val buyerGrowthPositive: Boolean,
    val buyCount: BigDecimal,
    val sellCount: BigDecimal,
    val buyVolume: BigDecimal,
    val sellVolume: BigDecimal,
    val organicActivityPresent: Boolean,
    val liquidity: Lamports,
    val liquidityChangeNegative: Boolean,
    val buyPriceImpactBps: Int,
    val buyRouteAvailable: Boolean,
    val sellRouteAvailable: Boolean,
    val roundTripLossBps: Int,
    val feeRatioBps: Int,
    val tokenAge: Duration,
    val preEntryPriceIncreasePercent: BigDecimal,
    val suspiciousWalletActivity: Boolean,
    val dataAge: Duration,
    val providersAgree: Boolean,
    val programAllowlistCompatible: Boolean,
    val quoteAndTransactionDecodable: Boolean,
    val paperQuoteSemanticsValidated: Boolean,
    val unsupportedRouteBehavior: Boolean,
)

enum class HardRejectionReason {
    INCOMPLETE_CRITICAL_DATA,
    INVALID_MINT,
    UNSUPPORTED_TOKEN_PROGRAM,
    DANGEROUS_TOKEN_EXTENSION,
    ACTIVE_MINT_AUTHORITY,
    ACTIVE_FREEZE_AUTHORITY,
    PROVIDER_WARNING,
    CREATOR_CONCENTRATION,
    TOP_HOLDER_CONCENTRATION,
    INSUFFICIENT_HOLDERS,
    INSUFFICIENT_UNIQUE_BUYERS,
    BUYER_GROWTH_STALLED,
    WEAK_BUY_SELL_COUNT_RATIO,
    WEAK_BUY_SELL_VOLUME_RATIO,
    INORGANIC_ACTIVITY,
    INSUFFICIENT_LIQUIDITY,
    LIQUIDITY_FALLING,
    EXCESSIVE_PRICE_IMPACT,
    BUY_ROUTE_UNAVAILABLE,
    SELL_ROUTE_UNAVAILABLE,
    EXCESSIVE_ROUND_TRIP_LOSS,
    EXCESSIVE_FEE_RATIO,
    CANDIDATE_TOO_OLD,
    ALREADY_PUMPED,
    SUSPICIOUS_WALLET_ACTIVITY,
    STALE_DATA,
    PROVIDER_DISAGREEMENT,
    PROGRAM_NOT_ALLOWED,
    TRANSACTION_DECODE_FAILURE,
    QUOTE_SEMANTICS_UNVALIDATED,
    UNSUPPORTED_ROUTE_BEHAVIOR,
}

data class HardFilterResult(
    val reasons: Set<HardRejectionReason>,
) {
    val passed: Boolean = reasons.isEmpty()
}

object CandidateHardFilter {
    fun evaluate(
        snapshot: CandidateSnapshot,
        config: CandidateFilterConfig,
        allowPaperQuoteOnly: Boolean = false,
    ): HardFilterResult {
        val reasons = linkedSetOf<HardRejectionReason>()

        fun rejectIf(
            condition: Boolean,
            reason: HardRejectionReason,
        ) {
            if (condition) reasons += reason
        }

        rejectIf(!snapshot.criticalDataComplete, HardRejectionReason.INCOMPLETE_CRITICAL_DATA)
        rejectIf(!snapshot.mintAddressValid, HardRejectionReason.INVALID_MINT)
        rejectIf(snapshot.tokenProgram == TokenProgram.UNKNOWN, HardRejectionReason.UNSUPPORTED_TOKEN_PROGRAM)
        rejectIf(snapshot.hasDangerousTokenExtensions, HardRejectionReason.DANGEROUS_TOKEN_EXTENSION)
        rejectIf(!snapshot.mintAuthorityRevoked, HardRejectionReason.ACTIVE_MINT_AUTHORITY)
        rejectIf(!snapshot.freezeAuthorityRevoked, HardRejectionReason.ACTIVE_FREEZE_AUTHORITY)
        rejectIf(snapshot.providerWarning, HardRejectionReason.PROVIDER_WARNING)
        rejectIf(
            snapshot.creatorHoldingPercent?.let { it > config.maximumCreatorHoldingPercent } == true,
            HardRejectionReason.CREATOR_CONCENTRATION,
        )
        rejectIf(
            snapshot.topHolderPercent?.let { it > config.maximumTopHolderPercent } == true,
            HardRejectionReason.TOP_HOLDER_CONCENTRATION,
        )
        rejectIf(snapshot.holderCount < config.minimumHolderCount, HardRejectionReason.INSUFFICIENT_HOLDERS)
        rejectIf(snapshot.uniqueBuyers < config.minimumUniqueBuyers, HardRejectionReason.INSUFFICIENT_UNIQUE_BUYERS)
        rejectIf(!snapshot.buyerGrowthPositive, HardRejectionReason.BUYER_GROWTH_STALLED)
        rejectIf(
            snapshot.buyCount < snapshot.sellCount.multiply(config.minimumBuySellCountRatio),
            HardRejectionReason.WEAK_BUY_SELL_COUNT_RATIO,
        )
        rejectIf(
            snapshot.buyVolume < snapshot.sellVolume.multiply(config.minimumBuySellVolumeRatio),
            HardRejectionReason.WEAK_BUY_SELL_VOLUME_RATIO,
        )
        rejectIf(!snapshot.organicActivityPresent, HardRejectionReason.INORGANIC_ACTIVITY)
        rejectIf(snapshot.liquidity < config.minimumLiquidity, HardRejectionReason.INSUFFICIENT_LIQUIDITY)
        rejectIf(snapshot.liquidityChangeNegative, HardRejectionReason.LIQUIDITY_FALLING)
        rejectIf(
            snapshot.buyPriceImpactBps > config.maximumBuyPriceImpactBps,
            HardRejectionReason.EXCESSIVE_PRICE_IMPACT,
        )
        rejectIf(!snapshot.buyRouteAvailable, HardRejectionReason.BUY_ROUTE_UNAVAILABLE)
        rejectIf(!snapshot.sellRouteAvailable, HardRejectionReason.SELL_ROUTE_UNAVAILABLE)
        rejectIf(
            snapshot.roundTripLossBps > config.maximumRoundTripLossBps,
            HardRejectionReason.EXCESSIVE_ROUND_TRIP_LOSS,
        )
        rejectIf(snapshot.feeRatioBps > config.maximumFeeRatioBps, HardRejectionReason.EXCESSIVE_FEE_RATIO)
        rejectIf(snapshot.tokenAge > config.maximumTokenAge, HardRejectionReason.CANDIDATE_TOO_OLD)
        rejectIf(
            snapshot.preEntryPriceIncreasePercent > config.maximumPreEntryPriceIncreasePercent,
            HardRejectionReason.ALREADY_PUMPED,
        )
        rejectIf(snapshot.suspiciousWalletActivity, HardRejectionReason.SUSPICIOUS_WALLET_ACTIVITY)
        rejectIf(snapshot.dataAge > config.maximumDataAge, HardRejectionReason.STALE_DATA)
        rejectIf(!snapshot.providersAgree, HardRejectionReason.PROVIDER_DISAGREEMENT)
        rejectIf(!snapshot.programAllowlistCompatible, HardRejectionReason.PROGRAM_NOT_ALLOWED)
        if (allowPaperQuoteOnly) {
            rejectIf(
                !snapshot.paperQuoteSemanticsValidated,
                HardRejectionReason.QUOTE_SEMANTICS_UNVALIDATED,
            )
        } else {
            rejectIf(!snapshot.quoteAndTransactionDecodable, HardRejectionReason.TRANSACTION_DECODE_FAILURE)
        }
        rejectIf(snapshot.unsupportedRouteBehavior, HardRejectionReason.UNSUPPORTED_ROUTE_BEHAVIOR)
        return HardFilterResult(reasons)
    }
}

enum class ScoreFactor {
    BUYER_GROWTH,
    BUY_SELL_IMBALANCE,
    ORGANIC_ACTIVITY,
    HOLDER_GROWTH,
    LIQUIDITY_GROWTH,
    CREATOR_CONCENTRATION,
    TOP_HOLDER_CONCENTRATION,
    PRICE_MOMENTUM,
    ALREADY_PUMPED,
    ROUTE_QUALITY,
    ROUND_TRIP_COST,
    DATA_CONSISTENCY,
    TOKEN_AGE,
    LARGE_SELL_ACTIVITY,
}

data class StrategyConfig(
    val version: String,
    val weights: Map<ScoreFactor, Int>,
    val minimumScore: Int,
) {
    init {
        require(version.isNotBlank()) { "Strategy version is required" }
        require(weights.isNotEmpty() && weights.values.all { it >= 0 } && weights.values.sum() > 0)
        require(minimumScore in 0..100)
    }
}

data class DecisionResult(
    val score: Int?,
    val mandatoryPass: Boolean,
    val eligible: Boolean,
    val dataCompletenessPercent: Int,
    val positiveFactors: List<ScoreFactor>,
    val negativeFactors: List<ScoreFactor>,
    val hardRejectionReasons: Set<HardRejectionReason>,
    val snapshotIds: List<String>,
    val snapshotTimes: List<Instant>,
    val strategyVersion: String,
)

fun interface DecisionEngine {
    fun evaluate(
        snapshot: CandidateSnapshot,
        factors: Map<ScoreFactor, BigDecimal>,
    ): DecisionResult
}

class RuleBasedDecisionEngine(
    private val strategy: StrategyConfig,
    private val filters: CandidateFilterConfig,
    private val allowPaperQuoteOnly: Boolean = false,
) : DecisionEngine {
    override fun evaluate(
        snapshot: CandidateSnapshot,
        factors: Map<ScoreFactor, BigDecimal>,
    ): DecisionResult {
        require(factors.values.all { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }) {
            "Score factors must be between zero and one"
        }
        val filterResult = CandidateHardFilter.evaluate(snapshot, filters, allowPaperQuoteOnly)
        val supplied = strategy.weights.keys.count(factors::containsKey)
        val completeness = supplied * 100 / strategy.weights.size
        val missingFactors = supplied != strategy.weights.size
        val rejectionReasons =
            filterResult.reasons.toMutableSet().apply {
                if (missingFactors) add(HardRejectionReason.INCOMPLETE_CRITICAL_DATA)
            }
        val canScore = rejectionReasons.isEmpty()
        val score = if (canScore) calculateScore(factors) else null
        val orderedFactors = strategy.weights.keys.toList()
        return DecisionResult(
            score = score,
            mandatoryPass = canScore,
            eligible = score != null && score >= strategy.minimumScore,
            dataCompletenessPercent = completeness,
            positiveFactors = orderedFactors.filter { factors[it]?.let { value -> value >= HALF } == true },
            negativeFactors = orderedFactors.filter { factors[it]?.let { value -> value < HALF } == true },
            hardRejectionReasons = rejectionReasons,
            snapshotIds = listOf(snapshot.snapshotId),
            snapshotTimes = listOf(snapshot.observedAt),
            strategyVersion = strategy.version,
        )
    }

    private fun calculateScore(factors: Map<ScoreFactor, BigDecimal>): Int {
        val weightedTotal =
            strategy.weights.entries.fold(BigDecimal.ZERO) { total, (factor, weight) ->
                total + factors.getValue(factor).multiply(BigDecimal.valueOf(weight.toLong()))
            }
        return weightedTotal
            .multiply(BigDecimal.valueOf(100))
            .divide(
                BigDecimal.valueOf(
                    strategy.weights.values
                        .sum()
                        .toLong(),
                ),
                0,
                RoundingMode.HALF_UP,
            ).intValueExact()
    }

    private companion object {
        val HALF = BigDecimal("0.5")
    }
}
