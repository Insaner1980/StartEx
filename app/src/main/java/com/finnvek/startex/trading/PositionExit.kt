package com.finnvek.startex.trading

import com.finnvek.startex.domain.EurAmount
import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.domain.TokenAmount
import com.finnvek.startex.domain.TokenProgram
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import kotlin.math.min

data class TradeCosts(
    val dexFee: Lamports = Lamports.ZERO,
    val platformFee: Lamports = Lamports.ZERO,
    val baseFee: Lamports = Lamports.ZERO,
    val priorityFee: Lamports = Lamports.ZERO,
    val associatedTokenRent: Lamports = Lamports.ZERO,
    val transferFeeEquivalent: Lamports = Lamports.ZERO,
    val reclaimedRent: Lamports = Lamports.ZERO,
) {
    fun netLamports(): BigInteger =
        listOf(
            dexFee,
            platformFee,
            baseFee,
            priorityFee,
            associatedTokenRent,
            transferFeeEquivalent,
        ).fold(BigInteger.ZERO) { total, cost -> total + cost.value.toBigInteger() } - reclaimedRent.value.toBigInteger()
}

data class LamportDelta(
    val value: BigInteger,
)

data class ExecutableSellQuote(
    val grossOutput: Lamports,
    val estimatedCosts: TradeCosts,
    val observedAt: Instant,
    val routeAvailable: Boolean,
)

enum class PositionState {
    ENTRY_PENDING,
    OPEN,
    EXIT_QUEUED,
    EXIT_SUBMITTED,
    EXIT_BLOCKED,
    TRANSACTION_UNCERTAIN,
    CLOSED,
    FAILED,
}

enum class TradingMode {
    PAPER,
    LIVE,
}

enum class ReconciliationState {
    PENDING,
    CONFIRMED,
    UNCERTAIN,
    RECONCILED,
}

@Suppress("LongParameterList")
data class Position(
    val id: String,
    val openedAt: Instant,
    val actualSolInput: Lamports,
    val entryCosts: TradeCosts,
    val state: PositionState,
    val exitRulesVersion: String,
    val mint: String = "",
    val symbol: String = "",
    val name: String = "",
    val decimals: Int = 0,
    val tokenProgram: TokenProgram = TokenProgram.LEGACY,
    val actualTokenAmount: TokenAmount? = null,
    val currentRawBalance: TokenAmount? = null,
    val latestSellQuote: ExecutableSellQuote? = null,
    val approximateEurValue: EurAmount? = null,
    val highestExecutableValue: Lamports = Lamports.ZERO,
    val lowestExecutableValue: Lamports = Lamports.ZERO,
    val riskScore: Int = 0,
    val dataObservedAt: Instant? = null,
    val routeAvailable: Boolean = false,
    val transactionSignatures: List<String> = emptyList(),
    val reconciliationState: ReconciliationState = ReconciliationState.PENDING,
    val mode: TradingMode = TradingMode.PAPER,
) {
    init {
        require(id.isNotBlank())
        require(exitRulesVersion.isNotBlank())
        require(decimals in 0..255)
        require(riskScore in 0..100)
    }
}

object PositionStateMachine {
    private val transitions =
        mapOf(
            PositionState.ENTRY_PENDING to
                setOf(
                    PositionState.OPEN,
                    PositionState.TRANSACTION_UNCERTAIN,
                    PositionState.FAILED,
                ),
            PositionState.OPEN to setOf(PositionState.EXIT_QUEUED, PositionState.FAILED),
            PositionState.EXIT_QUEUED to
                setOf(
                    PositionState.EXIT_SUBMITTED,
                    PositionState.EXIT_BLOCKED,
                    PositionState.OPEN,
                ),
            PositionState.EXIT_SUBMITTED to
                setOf(
                    PositionState.CLOSED,
                    PositionState.EXIT_BLOCKED,
                    PositionState.TRANSACTION_UNCERTAIN,
                ),
            PositionState.EXIT_BLOCKED to setOf(PositionState.EXIT_QUEUED, PositionState.FAILED),
            PositionState.TRANSACTION_UNCERTAIN to
                setOf(
                    PositionState.OPEN,
                    PositionState.EXIT_SUBMITTED,
                    PositionState.CLOSED,
                    PositionState.FAILED,
                ),
        )

    fun transition(
        from: PositionState,
        to: PositionState,
    ): PositionState {
        check(to in transitions[from].orEmpty()) { "Invalid position transition: $from -> $to" }
        return to
    }
}

object PositionAccounting {
    fun unrealizedPnl(
        position: Position,
        quote: ExecutableSellQuote,
    ): LamportDelta {
        val output = quote.grossOutput.value.toBigInteger()
        val input = position.actualSolInput.value.toBigInteger()
        return LamportDelta(output - input - position.entryCosts.netLamports() - quote.estimatedCosts.netLamports())
    }
}

data class ExitPolicy(
    val takeProfitBps: Int,
    val hardStopLossBps: Int,
    val trailingActivationBps: Int,
    val trailingDistanceBps: Int,
    val maximumHoldingTime: Duration,
    val minimumSafetyScore: Int,
) {
    init {
        require(takeProfitBps >= 0)
        require(hardStopLossBps in 0..10_000)
        require(trailingActivationBps >= 0)
        require(trailingDistanceBps in 0..10_000)
        require(!maximumHoldingTime.isNegative)
        require(minimumSafetyScore in 0..100)
    }
}

enum class ManualExitAction {
    NONE,
    SELL_NOW,
    EMERGENCY_EXIT,
}

data class ExitObservation(
    val now: Instant,
    val quote: ExecutableSellQuote?,
    val highestExecutableValue: Lamports,
    val safetyScore: Int,
    val momentumCollapsed: Boolean = false,
    val liquidityCollapsed: Boolean = false,
    val suspiciousCreatorActivity: Boolean = false,
    val largeHolderSell: Boolean = false,
    val tokenUnsafe: Boolean = false,
    val manualAction: ManualExitAction = ManualExitAction.NONE,
) {
    init {
        require(safetyScore in 0..100)
    }
}

enum class ExitReason {
    TAKE_PROFIT,
    HARD_STOP_LOSS,
    TRAILING_STOP,
    MAXIMUM_HOLD,
    MOMENTUM_COLLAPSE,
    LIQUIDITY_COLLAPSE,
    SUSPICIOUS_CREATOR_ACTIVITY,
    LARGE_HOLDER_SELL,
    SAFETY_SCORE_DETERIORATED,
    TOKEN_UNSAFE,
    ROUTE_UNAVAILABLE,
    SELL_NOW,
    EMERGENCY_EXIT,
}

data class ExitDecision(
    val reasons: Set<ExitReason>,
    val routeBlocked: Boolean,
) {
    val exitRequested: Boolean = reasons.isNotEmpty()
}

class ExitEngine(
    private val policy: ExitPolicy,
) {
    fun evaluate(
        position: Position,
        observation: ExitObservation,
    ): ExitDecision {
        check(position.state == PositionState.OPEN || position.state == PositionState.EXIT_BLOCKED) {
            "Only active positions can be evaluated"
        }
        val reasons = linkedSetOf<ExitReason>()
        when (observation.manualAction) {
            ManualExitAction.EMERGENCY_EXIT -> reasons += ExitReason.EMERGENCY_EXIT
            ManualExitAction.SELL_NOW -> reasons += ExitReason.SELL_NOW
            ManualExitAction.NONE -> Unit
        }
        if (Duration.between(position.openedAt, observation.now) >= policy.maximumHoldingTime) {
            reasons += ExitReason.MAXIMUM_HOLD
        }
        if (observation.momentumCollapsed) reasons += ExitReason.MOMENTUM_COLLAPSE
        if (observation.liquidityCollapsed) reasons += ExitReason.LIQUIDITY_COLLAPSE
        if (observation.suspiciousCreatorActivity) reasons += ExitReason.SUSPICIOUS_CREATOR_ACTIVITY
        if (observation.largeHolderSell) reasons += ExitReason.LARGE_HOLDER_SELL
        if (observation.safetyScore < policy.minimumSafetyScore) reasons += ExitReason.SAFETY_SCORE_DETERIORATED
        if (observation.tokenUnsafe) reasons += ExitReason.TOKEN_UNSAFE

        val quote = observation.quote
        val routeBlocked = quote == null || !quote.routeAvailable
        if (routeBlocked) {
            reasons += ExitReason.ROUTE_UNAVAILABLE
        } else {
            evaluateValueRules(position, quote, observation.highestExecutableValue, reasons)
        }
        return ExitDecision(reasons, routeBlocked)
    }

    private fun evaluateValueRules(
        position: Position,
        quote: ExecutableSellQuote,
        highestExecutableValue: Lamports,
        reasons: MutableSet<ExitReason>,
    ) {
        val invested = position.actualSolInput.value.toBigInteger() + position.entryCosts.netLamports()
        val current = quote.grossOutput.value.toBigInteger() - quote.estimatedCosts.netLamports()
        if (current * BPS >= invested * (BPS + policy.takeProfitBps.toBigInteger())) {
            reasons += ExitReason.TAKE_PROFIT
        }
        if (current * BPS <= invested * (BPS - policy.hardStopLossBps.toBigInteger())) {
            reasons += ExitReason.HARD_STOP_LOSS
        }
        val highest = highestExecutableValue.value.toBigInteger()
        val trailingActivated = highest * BPS >= invested * (BPS + policy.trailingActivationBps.toBigInteger())
        val trailingReached = current * BPS <= highest * (BPS - policy.trailingDistanceBps.toBigInteger())
        if (trailingActivated && trailingReached) reasons += ExitReason.TRAILING_STOP
    }

    private companion object {
        val BPS: BigInteger = BigInteger.valueOf(10_000)
    }
}

data class ExitRetryPlan(
    val delay: Duration,
    val slippageBps: Int,
    val priorityFee: Lamports,
)

data class ExitRetryPolicy(
    val maximumImmediateAttempts: Int,
    val baseDelay: Duration,
    val maximumDelay: Duration,
    val slippageStepBps: Int,
    val maximumSlippageBps: Int,
    val priorityFeeStep: Lamports,
    val maximumPriorityFee: Lamports,
) {
    init {
        require(maximumImmediateAttempts > 0)
        require(!baseDelay.isNegative && !baseDelay.isZero)
        require(maximumDelay >= baseDelay)
        require(slippageStepBps >= 0)
        require(maximumSlippageBps in 0..10_000)
    }

    fun nextAttempt(
        completedAttempts: Int,
        currentSlippageBps: Int,
        currentPriorityFee: Lamports,
    ): ExitRetryPlan? {
        require(completedAttempts >= 0)
        if (completedAttempts >= maximumImmediateAttempts) return null
        val exponent = (completedAttempts - 1).coerceAtLeast(0).coerceAtMost(MAX_SHIFT)
        val multiplier = 1L shl exponent
        val calculatedDelay = baseDelay.multipliedBy(multiplier)
        return ExitRetryPlan(
            delay = if (calculatedDelay > maximumDelay) maximumDelay else calculatedDelay,
            slippageBps = min(maximumSlippageBps, Math.addExact(currentSlippageBps, slippageStepBps)),
            priorityFee = minOf(maximumPriorityFee, currentPriorityFee + priorityFeeStep),
        )
    }

    private companion object {
        const val MAX_SHIFT = 30
    }
}
