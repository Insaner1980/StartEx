package com.finnvek.startex.trading

import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.domain.TokenAmount
import java.math.BigInteger
import java.time.Duration

data class PaperExecutionConfig(
    val decisionToSubmitLatency: Duration,
    val executionSlippageBps: Int,
) {
    init {
        require(!decisionToSubmitLatency.isNegative)
        require(executionSlippageBps in 0..10_000)
    }
}

data class PaperCosts(
    val solCosts: TradeCosts = TradeCosts(),
    val tokenTransferFeeAtomic: BigInteger = BigInteger.ZERO,
) {
    init {
        require(tokenTransferFeeAtomic.signum() >= 0)
    }
}

data class PaperBuyQuote(
    val expectedOutputAtomic: BigInteger,
    val routeAvailable: Boolean,
) {
    init {
        require(expectedOutputAtomic.signum() >= 0)
    }
}

data class PaperSellQuote(
    val expectedSolOutput: Lamports,
    val routeAvailable: Boolean,
)

data class PaperBuyRequest(
    val input: Lamports,
    val tokenDecimals: Int,
    val initialQuote: PaperBuyQuote,
    val costs: PaperCosts,
) {
    init {
        require(tokenDecimals in 0..255)
    }
}

data class PaperSellRequest(
    val input: TokenAmount,
    val initialQuote: PaperSellQuote,
    val costs: PaperCosts,
    val reason: ExitReason,
)

interface PaperQuoteProvider {
    fun refreshBuy(request: PaperBuyRequest): PaperBuyQuote?

    fun refreshSell(request: PaperSellRequest): PaperSellQuote?
}

fun interface SimulationSleeper {
    fun wait(duration: Duration)
}

enum class PaperFailureReason {
    ROUTE_UNAVAILABLE,
    EXCESSIVE_FEES,
}

sealed interface PaperBuyOutcome {
    data class Filled(
        val received: TokenAmount,
        val totalSolDebited: LamportDelta,
        val costs: PaperCosts,
    ) : PaperBuyOutcome

    data class Rejected(
        val reason: PaperFailureReason,
    ) : PaperBuyOutcome
}

sealed interface PaperSellOutcome {
    data class Filled(
        val netSolReceived: LamportDelta,
        val costs: PaperCosts,
        val reason: ExitReason,
    ) : PaperSellOutcome

    data class Rejected(
        val failure: PaperFailureReason,
        val reason: ExitReason,
    ) : PaperSellOutcome
}

class PaperExecutionEngine(
    private val quoteProvider: PaperQuoteProvider,
    private val sleeper: SimulationSleeper,
) {
    fun executeBuy(
        request: PaperBuyRequest,
        config: PaperExecutionConfig,
    ): PaperBuyOutcome {
        if (!request.initialQuote.routeAvailable) return PaperBuyOutcome.Rejected(PaperFailureReason.ROUTE_UNAVAILABLE)
        sleeper.wait(config.decisionToSubmitLatency)
        val refreshed = quoteProvider.refreshBuy(request)
        if (refreshed == null || !refreshed.routeAvailable) {
            return PaperBuyOutcome.Rejected(PaperFailureReason.ROUTE_UNAVAILABLE)
        }
        val afterSlippage = applySlippage(refreshed.expectedOutputAtomic, config.executionSlippageBps)
        val received = afterSlippage - request.costs.tokenTransferFeeAtomic
        if (received.signum() <= 0) return PaperBuyOutcome.Rejected(PaperFailureReason.EXCESSIVE_FEES)
        val totalDebit = request.input.value.toBigInteger() + request.costs.solCosts.netLamports()
        return PaperBuyOutcome.Filled(
            received = TokenAmount.ofAtomic(received, request.tokenDecimals),
            totalSolDebited = LamportDelta(totalDebit),
            costs = request.costs,
        )
    }

    fun executeSell(
        request: PaperSellRequest,
        config: PaperExecutionConfig,
    ): PaperSellOutcome {
        if (!request.initialQuote.routeAvailable) {
            return PaperSellOutcome.Rejected(PaperFailureReason.ROUTE_UNAVAILABLE, request.reason)
        }
        sleeper.wait(config.decisionToSubmitLatency)
        val refreshed = quoteProvider.refreshSell(request)
        if (refreshed == null || !refreshed.routeAvailable) {
            return PaperSellOutcome.Rejected(PaperFailureReason.ROUTE_UNAVAILABLE, request.reason)
        }
        val afterSlippage =
            applySlippage(
                refreshed.expectedSolOutput.value.toBigInteger(),
                config.executionSlippageBps,
            )
        val netOutput = afterSlippage - request.costs.solCosts.netLamports()
        if (netOutput.signum() <= 0) {
            return PaperSellOutcome.Rejected(PaperFailureReason.EXCESSIVE_FEES, request.reason)
        }
        return PaperSellOutcome.Filled(
            netSolReceived = LamportDelta(netOutput),
            costs = request.costs,
            reason = request.reason,
        )
    }

    private fun applySlippage(
        amount: BigInteger,
        slippageBps: Int,
    ): BigInteger = amount * (BASIS_POINTS - slippageBps.toBigInteger()) / BASIS_POINTS

    private companion object {
        val BASIS_POINTS: BigInteger = BigInteger.valueOf(10_000)
    }
}
