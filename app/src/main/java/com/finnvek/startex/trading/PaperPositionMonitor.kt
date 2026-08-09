package com.finnvek.startex.trading

import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.TradeIntentEntity
import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.domain.TokenAmount
import com.finnvek.startex.domain.TokenProgram
import com.finnvek.startex.domain.toLongExactCompat
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.SwapOrder
import com.finnvek.startex.network.SwapOrderRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

data class PaperExitSafetyFacts(
    val score: Int,
    val tokenUnsafe: Boolean,
    val momentumCollapsed: Boolean,
    val liquidityCollapsed: Boolean,
    val suspiciousCreatorActivity: Boolean,
    val largeHolderSell: Boolean,
) {
    init {
        require(score in 0..100)
    }
}

fun interface PaperExitSafetySource {
    suspend fun facts(
        position: PositionEntity,
        now: Instant,
    ): PaperExitSafetyFacts?
}

fun interface PaperPositionQuoteSource {
    suspend fun quote(request: SwapOrderRequest): ProviderResult<SwapOrder>
}

interface PaperPositionPersistence {
    suspend fun save(position: PositionEntity)

    suspend fun saveAttempt(intent: TradeIntentEntity)

    suspend fun close(
        position: PositionEntity,
        intent: TradeIntentEntity,
        performanceEpochDay: Long,
        performanceDelta: PaperPerformanceDelta,
        performanceAtMillis: Long,
    )
}

fun interface PaperPositionDelay {
    suspend fun wait(duration: Duration)
}

sealed interface PaperPositionResult {
    data class Monitored(
        val position: PositionEntity,
    ) : PaperPositionResult

    data class Closed(
        val position: PositionEntity,
        val reasons: Set<ExitReason>,
        val attempts: Int,
    ) : PaperPositionResult

    data class ExitBlocked(
        val position: PositionEntity,
        val reasons: Set<ExitReason>,
        val attempts: Int,
    ) : PaperPositionResult
}

/** Serial paper-only exit monitor. It requests quotes but never signs, broadcasts, or calls swap execute. */
class PaperPositionMonitor(
    private val quotes: PaperPositionQuoteSource,
    private val safety: PaperExitSafetySource,
    private val persistence: PaperPositionPersistence,
    private val delay: PaperPositionDelay,
    private val clock: () -> Instant = Instant::now,
    private val decisionLatency: Duration = Duration.ofSeconds(2),
    private val finalStateGate: PaperFinalStateGate = PaperFinalStateGate(),
) {
    private val lock = Mutex()

    init {
        require(!decisionLatency.isNegative)
    }

    suspend fun check(
        position: PositionEntity,
        risk: RiskConfigEntity,
        sellNow: Boolean,
    ): PaperPositionResult =
        lock.withLock {
            require(position.mode == "PAPER")
            require(position.status in MONITORED_STATES)
            val now = clock()
            val policy = position.exitPolicy() ?: return@withLock blockInvalid(position, now)
            val initialOrder =
                quotes
                    .quote(position.quoteRequest(risk, risk.maximumSlippageBps))
                    .successValue()
                    ?.takeIf { it.matches(position) }
            val initialQuote = initialOrder?.toExecutableQuote(now, risk)
            val facts = safety.facts(position, now)
            val highest = maxOf(position.highestExecutableSellLamports, initialQuote?.grossOutput?.value ?: 0L)
            val lowest =
                when (val current = initialQuote?.grossOutput?.value) {
                    null -> position.lowestExecutableSellLamports
                    else -> minOf(position.lowestExecutableSellLamports, current)
                }
            val refreshed =
                position.copy(
                    status =
                        if (initialQuote == null && position.status == "EXIT_REQUESTED") {
                            "EXIT_BLOCKED"
                        } else {
                            position.status
                        },
                    latestSellQuoteLamports = initialQuote?.grossOutput?.value,
                    latestSellQuoteAtMillis = initialQuote?.observedAt?.toEpochMilli(),
                    highestExecutableSellLamports = highest,
                    lowestExecutableSellLamports = lowest,
                    routeAvailable = initialQuote != null,
                    updatedAtMillis = now.toEpochMilli(),
                )
            val domain = refreshed.toDomain(initialQuote) ?: return@withLock blockInvalid(refreshed, now)
            val safetyFacts =
                (facts ?: FAIL_CLOSED_SAFETY).let { current ->
                    current.copy(
                        liquidityCollapsed =
                            current.liquidityCollapsed ||
                                initialQuote?.grossOutput?.value?.let {
                                    it.toBigInteger() * BigInteger.valueOf(2) < highest.toBigInteger()
                                } == true,
                    )
                }
            val decision =
                ExitEngine(policy).evaluate(
                    domain,
                    ExitObservation(
                        now = now,
                        quote = initialQuote,
                        highestExecutableValue = Lamports.of(highest),
                        safetyScore = safetyFacts.score,
                        momentumCollapsed = safetyFacts.momentumCollapsed,
                        liquidityCollapsed = safetyFacts.liquidityCollapsed,
                        suspiciousCreatorActivity = safetyFacts.suspiciousCreatorActivity,
                        largeHolderSell = safetyFacts.largeHolderSell,
                        tokenUnsafe = safetyFacts.tokenUnsafe,
                        manualAction =
                            if (sellNow || position.status == "EXIT_REQUESTED") {
                                ManualExitAction.SELL_NOW
                            } else {
                                ManualExitAction.NONE
                            },
                    ),
                )
            if (!decision.exitRequested) {
                val monitored = refreshed.copy(status = "OPEN")
                persistence.save(monitored)
                return@withLock PaperPositionResult.Monitored(monitored)
            }

            executeExit(refreshed, risk, decision.reasons)
        }

    private suspend fun executeExit(
        position: PositionEntity,
        risk: RiskConfigEntity,
        reasons: Set<ExitReason>,
    ): PaperPositionResult {
        delay.wait(decisionLatency)
        val retryPolicy =
            ExitRetryPolicy(
                maximumImmediateAttempts = MAXIMUM_IMMEDIATE_ATTEMPTS,
                baseDelay = RETRY_BASE_DELAY,
                maximumDelay = RETRY_MAXIMUM_DELAY,
                slippageStepBps = RETRY_SLIPPAGE_STEP_BPS,
                maximumSlippageBps = risk.maximumSlippageBps,
                priorityFeeStep = Lamports.ZERO,
                maximumPriorityFee = Lamports.of(risk.maximumPriorityFeeLamports),
            )
        var attempts = 0
        var slippageBps = minOf(PAPER_EXECUTION_SLIPPAGE_BPS, risk.maximumSlippageBps)
        while (attempts < MAXIMUM_IMMEDIATE_ATTEMPTS) {
            attempts += 1
            val observedAt = clock()
            val order =
                quotes
                    .quote(position.quoteRequest(risk, slippageBps))
                    .successValue()
                    ?.takeIf { it.matches(position) }
            var attemptStatus = "PAPER_ROUTE_UNAVAILABLE"
            var attemptCosts = 0L
            if (order != null) {
                val estimatedCosts = order.estimatedCosts()
                val costs = estimatedCosts?.takeIf { order.costsAllowed(it, risk) }
                val gross = order.outAmountAtomic.toLongOrNull()
                val net =
                    if (costs != null && gross != null) {
                        paperNetOutput(gross, slippageBps, costs.netLamports())
                    } else {
                        null
                    }
                if (costs != null && net != null && net > 0) {
                    attemptCosts = costs.netLamports().toLongExactCompat()
                    persistence.saveAttempt(
                        position.attemptIntent(
                            attempt = attempts,
                            order = order,
                            costsLamports = attemptCosts,
                            status = "PAPER_QUOTE_READY",
                            now = observedAt,
                        ),
                    )
                    return closePosition(
                        position = position,
                        order = order,
                        netOutputLamports = net,
                        exitCostsLamports = attemptCosts,
                        reasons = reasons,
                        attempts = attempts,
                        now = observedAt,
                    )
                }
                attemptStatus = "PAPER_COST_BLOCKED"
                attemptCosts = estimatedCosts?.estimatedLamports() ?: 0L
            }
            persistence.saveAttempt(
                position.attemptIntent(attempts, order, attemptCosts, attemptStatus, observedAt),
            )
            val next = retryPolicy.nextAttempt(attempts, slippageBps, Lamports.ZERO) ?: break
            slippageBps = next.slippageBps
            delay.wait(next.delay)
        }

        val blockedAt = clock()
        val blocked =
            position.copy(
                status = "EXIT_BLOCKED",
                routeAvailable = false,
                exitReason = reasons.sortedBy(ExitReason::name).joinToString("|") { it.name },
                updatedAtMillis = blockedAt.toEpochMilli(),
            )
        persistence.save(blocked)
        return PaperPositionResult.ExitBlocked(blocked, reasons, attempts)
    }

    @Suppress("LongMethod")
    private suspend fun closePosition(
        position: PositionEntity,
        order: SwapOrder,
        netOutputLamports: Long,
        exitCostsLamports: Long,
        reasons: Set<ExitReason>,
        attempts: Int,
        now: Instant,
    ): PaperPositionResult.Closed {
        val day = now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        val grossOutput = requireNotNull(order.outAmountAtomic.toLongOrNull())
        val grossPnl = Math.subtractExact(grossOutput, position.netInputLamports)
        val netPnl = Math.subtractExact(netOutputLamports, position.grossInputLamports)
        val performanceDelta =
            PaperPerformanceDelta(
                grossPnlLamports = grossPnl,
                netPnlLamports = netPnl,
                totalFeesLamports = exitCostsLamports,
                closedTradeResult = if (netPnl >= 0) PaperClosedTradeResult.WIN else PaperClosedTradeResult.LOSS,
            )
        val reason = reasons.sortedBy(ExitReason::name).joinToString("|") { it.name }
        val closed =
            position.copy(
                status = "CLOSED",
                latestSellQuoteLamports = grossOutput,
                latestSellQuoteAtMillis = now.toEpochMilli(),
                highestExecutableSellLamports = maxOf(position.highestExecutableSellLamports, grossOutput),
                lowestExecutableSellLamports = minOf(position.lowestExecutableSellLamports, grossOutput),
                routeAvailable = true,
                reconciliationState = "RECONCILED",
                closedAtMillis = now.toEpochMilli(),
                exitReason = reason,
                updatedAtMillis = now.toEpochMilli(),
            )
        val intent =
            TradeIntentEntity(
                id = "paper-exit:${position.id}:filled",
                sessionId = position.sessionId,
                positionId = position.id,
                idempotencyKey = "paper:${position.id}:SELL",
                side = "SELL",
                mint = position.mint,
                requestedInputAtomic = position.tokenAmountAtomic,
                expectedOutputAtomic = netOutputLamports.toString(),
                maximumCostLamports = exitCostsLamports,
                paperFeeLamports = exitCostsLamports,
                providerRequestId = order.requestId,
                status = "PAPER_FILLED",
                createdAtMillis = now.toEpochMilli(),
                updatedAtMillis = now.toEpochMilli(),
            )
        finalStateGate.run {
            persistence.close(
                position = closed,
                intent = intent,
                performanceEpochDay = day,
                performanceDelta = performanceDelta,
                performanceAtMillis = now.toEpochMilli(),
            )
        }
        return PaperPositionResult.Closed(closed, reasons, attempts)
    }

    private fun PositionEntity.attemptIntent(
        attempt: Int,
        order: SwapOrder?,
        costsLamports: Long,
        status: String,
        now: Instant,
    ) = TradeIntentEntity(
        id = "paper-exit:$id:attempt:$attempt",
        sessionId = sessionId,
        positionId = id,
        idempotencyKey = "paper:$id:SELL:attempt:$attempt",
        side = "SELL",
        mint = mint,
        requestedInputAtomic = tokenAmountAtomic,
        expectedOutputAtomic = order?.outAmountAtomic ?: "0",
        maximumCostLamports = costsLamports,
        paperFeeLamports = 0,
        providerRequestId = order?.requestId,
        status = status,
        createdAtMillis = now.toEpochMilli(),
        updatedAtMillis = now.toEpochMilli(),
    )

    private suspend fun blockInvalid(
        position: PositionEntity,
        now: Instant,
    ): PaperPositionResult.ExitBlocked {
        val blocked =
            position.copy(
                status = "EXIT_BLOCKED",
                routeAvailable = false,
                exitReason = INVALID_POSITION_STATE,
                updatedAtMillis = now.toEpochMilli(),
            )
        persistence.save(blocked)
        return PaperPositionResult.ExitBlocked(
            position = blocked,
            reasons = setOf(ExitReason.TOKEN_UNSAFE),
            attempts = 0,
        )
    }

    private fun PositionEntity.exitPolicy(): ExitPolicy? =
        try {
            val values = Json.parseToJsonElement(exitRulesJson).jsonObject
            ExitPolicy(
                takeProfitBps = values.requiredInt("takeProfitBps"),
                hardStopLossBps = values.requiredInt("hardStopLossBps"),
                trailingActivationBps = values.requiredInt("trailingActivationBps"),
                trailingDistanceBps = values.requiredInt("trailingDistanceBps"),
                maximumHoldingTime = Duration.ofMillis(values.requiredLong("maximumHoldingMillis")),
                minimumSafetyScore = values.requiredInt("minimumExitSafetyScore"),
            )
        } catch (_: RuntimeException) {
            null
        }

    private fun PositionEntity.toDomain(quote: ExecutableSellQuote?): Position? =
        try {
            Position(
                id = id,
                openedAt = Instant.ofEpochMilli(openedAtMillis),
                actualSolInput = Lamports.of(netInputLamports),
                entryCosts = TradeCosts(platformFee = Lamports.of(entryCostLamports)),
                state = if (status == "EXIT_BLOCKED") PositionState.EXIT_BLOCKED else PositionState.OPEN,
                exitRulesVersion = exitRulesVersion,
                mint = mint,
                symbol = symbol,
                name = name,
                decimals = tokenDecimals,
                tokenProgram =
                    when (tokenProgram) {
                        LEGACY_TOKEN_PROGRAM_ID -> TokenProgram.LEGACY
                        TOKEN_2022_PROGRAM_ID -> TokenProgram.TOKEN_2022
                        else -> TokenProgram.UNKNOWN
                    },
                actualTokenAmount = TokenAmount.ofAtomic(tokenAmountAtomic.toBigInteger(), tokenDecimals),
                currentRawBalance = TokenAmount.ofAtomic(tokenAmountAtomic.toBigInteger(), tokenDecimals),
                latestSellQuote = quote,
                highestExecutableValue = Lamports.of(highestExecutableSellLamports),
                lowestExecutableValue = Lamports.of(lowestExecutableSellLamports),
                riskScore = 0,
                dataObservedAt = quote?.observedAt,
                routeAvailable = quote != null,
                reconciliationState = ReconciliationState.RECONCILED,
                mode = TradingMode.PAPER,
            )
        } catch (_: RuntimeException) {
            null
        }

    private fun PositionEntity.quoteRequest(
        risk: RiskConfigEntity,
        slippageBps: Int,
    ) = SwapOrderRequest(
        inputMint = mint,
        outputMint = WRAPPED_SOL_MINT,
        amountAtomic = tokenAmountAtomic.toLongOrNull() ?: 0,
        maximumSlippageBps = slippageBps,
        maximumPriorityFeeLamports = risk.maximumPriorityFeeLamports,
        maximumTotalFeeBps = minOf(MAXIMUM_TOTAL_FEE_BPS, risk.maximumFeePercentBps),
        maximumTransactionCostLamports = risk.maximumTransactionCostLamports,
    )

    private fun SwapOrder.matches(position: PositionEntity): Boolean =
        inputMint == position.mint &&
            outputMint == WRAPPED_SOL_MINT &&
            inAmountAtomic == position.tokenAmountAtomic &&
            outAmountAtomic.toLongOrNull()?.let { it > 0 } == true &&
            minimumOutAmountAtomic?.toLongOrNull()?.let { it > 0 } == true &&
            slippageBps != null

    private fun SwapOrder.toExecutableQuote(
        now: Instant,
        risk: RiskConfigEntity,
    ): ExecutableSellQuote? {
        val output = outAmountAtomic.toLongOrNull() ?: return null
        val costs = estimatedCosts()?.takeIf { costsAllowed(it, risk) } ?: return null
        return ExecutableSellQuote(
            grossOutput = Lamports.of(output),
            estimatedCosts = costs,
            observedAt = now,
            routeAvailable = true,
        )
    }

    private fun SwapOrder.estimatedCosts(): TradeCosts? {
        return try {
            if (
                feeMint != WRAPPED_SOL_MINT ||
                feeBps !in 0..MAXIMUM_TOTAL_FEE_BPS ||
                prioritizationFeeLamports < 0 ||
                signatureFeeLamports < 0 || rentFeeLamports < 0
            ) {
                return null
            }
            val output = outAmountAtomic.toBigIntegerOrNull() ?: return null
            val platformFee =
                output
                    .multiply(feeBps.toBigInteger())
                    .divide(BASIS_POINTS)
                    .toLongExactCompat()
            val costs =
                TradeCosts(
                    platformFee = Lamports.of(platformFee),
                    baseFee = Lamports.of(signatureFeeLamports),
                    priorityFee = Lamports.of(prioritizationFeeLamports),
                    associatedTokenRent = Lamports.of(rentFeeLamports),
                )
            costs
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun SwapOrder.costsAllowed(
        costs: TradeCosts,
        risk: RiskConfigEntity,
    ): Boolean =
        slippageBps?.let { it in 0..risk.maximumSlippageBps } == true &&
            feeBps <= minOf(MAXIMUM_TOTAL_FEE_BPS, risk.maximumFeePercentBps) &&
            costs.priorityFee <= Lamports.of(risk.maximumPriorityFeeLamports) &&
            costs.netLamports() <= risk.maximumTransactionCostLamports.toBigInteger()

    private fun TradeCosts.estimatedLamports(): Long? =
        try {
            netLamports().toLongExactCompat()
        } catch (_: ArithmeticException) {
            null
        }

    private fun <T> ProviderResult<T>.successValue(): T? =
        when (this) {
            is ProviderResult.Success -> value
            is ProviderResult.Failure -> null
        }

    private fun kotlinx.serialization.json.JsonObject.requiredInt(name: String): Int =
        get(name)?.jsonPrimitive?.int ?: throw IllegalArgumentException(name)

    private fun kotlinx.serialization.json.JsonObject.requiredLong(name: String): Long =
        get(name)?.jsonPrimitive?.content?.toLongOrNull() ?: throw IllegalArgumentException(name)

    private companion object {
        val BASIS_POINTS: BigInteger = BigInteger.valueOf(10_000)
        val RETRY_BASE_DELAY: Duration = Duration.ofMillis(250)
        val RETRY_MAXIMUM_DELAY: Duration = Duration.ofSeconds(1)
        val FAIL_CLOSED_SAFETY =
            PaperExitSafetyFacts(
                score = 0,
                tokenUnsafe = true,
                momentumCollapsed = false,
                liquidityCollapsed = false,
                suspiciousCreatorActivity = false,
                largeHolderSell = false,
            )
        val MONITORED_STATES = setOf("OPEN", "EXIT_REQUESTED", "EXIT_BLOCKED")
        const val PAPER_EXECUTION_SLIPPAGE_BPS = 100
        const val RETRY_SLIPPAGE_STEP_BPS = 100
        const val MAXIMUM_IMMEDIATE_ATTEMPTS = 3
        const val MAXIMUM_TOTAL_FEE_BPS = 2_000
        const val INVALID_POSITION_STATE = "PAPER_POSITION_STATE_INVALID"
        const val LEGACY_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnEKS3sZDJR9L"
    }
}

internal fun paperNetOutput(
    grossLamports: Long,
    slippageBps: Int,
    costsLamports: BigInteger,
): Long? {
    return try {
        if (grossLamports <= 0 || slippageBps !in 0..10_000 || costsLamports.signum() < 0) return null
        grossLamports
            .toBigInteger()
            .multiply((10_000 - slippageBps).toBigInteger())
            .divide(BigInteger.valueOf(10_000))
            .subtract(costsLamports)
            .toLongExactCompat()
    } catch (_: ArithmeticException) {
        null
    }
}
