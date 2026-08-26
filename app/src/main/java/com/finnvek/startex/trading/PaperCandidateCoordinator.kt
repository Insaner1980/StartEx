package com.finnvek.startex.trading

import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.DecisionEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TokenSnapshotEntity
import com.finnvek.startex.data.local.TradeIntentEntity
import com.finnvek.startex.domain.CandidateFilterConfig
import com.finnvek.startex.domain.CandidateSnapshot
import com.finnvek.startex.domain.DecisionResult
import com.finnvek.startex.domain.EurAmount
import com.finnvek.startex.domain.HardRejectionReason
import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.domain.RuleBasedDecisionEngine
import com.finnvek.startex.domain.ScoreFactor
import com.finnvek.startex.domain.StrategyConfig
import com.finnvek.startex.domain.TokenProgram
import com.finnvek.startex.domain.toIntExactCompat
import com.finnvek.startex.domain.toLongExactCompat
import com.finnvek.startex.network.JupiterTokenSnapshot
import com.finnvek.startex.network.JupiterTokensProvider
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.SwapOrder
import com.finnvek.startex.network.SwapOrderRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

const val WRAPPED_SOL_MINT = "So11111111111111111111111111111111111111112"

data class PaperCandidateRequest(
    val sessionId: String,
    val candidate: TokenCandidateEntity,
    val strategy: StrategyConfigEntity,
    val risk: RiskConfigEntity,
)

sealed interface PaperCandidateResult {
    data class Rejected(
        val reasons: Set<String>,
        val snapshotCount: Int,
        val score: Int?,
    ) : PaperCandidateResult

    data class Filled(
        val positionId: String,
        val score: Int,
    ) : PaperCandidateResult

    data object Duplicate : PaperCandidateResult
}

data class PaperCandidateSafetyProof(
    val liquidity: Lamports,
    val tokenExtensionsSafe: Boolean,
    val suspiciousWalletActivity: Boolean,
    val independentProvidersAgree: Boolean,
    val programAllowlisted: Boolean,
    val quoteSemanticsValidated: Boolean,
    val unsupportedRouteBehavior: Boolean,
    val mintAddressValid: Boolean = true,
)

fun interface PaperCandidateSafetyProofSource {
    suspend fun proof(
        mint: String,
        token: JupiterTokenSnapshot,
    ): PaperCandidateSafetyProof?
}

data class PaperRiskFacts(
    val snapshot: RiskSnapshot,
    val approximateTradeEur: EurAmount?,
    val providerHealth: ProviderHealth,
    val dailyPerformance: DailyPerformanceEntity,
)

fun interface PaperRiskFactsSource {
    suspend fun facts(
        sessionId: String,
        risk: RiskConfigEntity,
        now: Instant,
    ): PaperRiskFacts?
}

fun interface PaperSwapQuoteSource {
    suspend fun order(request: SwapOrderRequest): ProviderResult<SwapOrder>
}

fun interface PaperCoordinatorClock {
    fun nowMillis(): Long
}

fun interface PaperCoordinatorDelay {
    suspend fun wait(duration: Duration)
}

data class PaperEntryFacts(
    val candidate: TokenCandidateEntity,
    val decision: DecisionEntity,
    val intent: TradeIntentEntity,
    val position: PositionEntity,
    val performanceEpochDay: Long,
    val performanceDelta: PaperPerformanceDelta,
    val performanceAtMillis: Long,
)

enum class PaperFillPersistenceResult {
    PERSISTED,
    UNSUPPORTED,
}

private sealed interface FinalPaperEntryOutcome {
    data class Persisted(
        val facts: PaperEntryFacts,
    ) : FinalPaperEntryOutcome

    data class Rejected(
        val reasons: Set<String>,
    ) : FinalPaperEntryOutcome

    data object Unsupported : FinalPaperEntryOutcome
}

interface PaperCandidatePersistence {
    suspend fun persistCandidate(candidate: TokenCandidateEntity)

    suspend fun persistSnapshot(snapshot: TokenSnapshotEntity): Long

    suspend fun persistDecision(
        candidate: TokenCandidateEntity,
        decision: DecisionEntity,
    )

    suspend fun currentRiskFacts(
        sessionId: String,
        risk: RiskConfigEntity,
        now: Instant,
        baseline: PaperRiskFacts,
    ): PaperRiskFacts?

    suspend fun persistCircuitBreaker(
        breaker: CircuitBreakerState,
        now: Instant,
    )

    suspend fun persistPaperFill(facts: PaperEntryFacts): PaperFillPersistenceResult
}

@Suppress("LongParameterList")
class PaperCandidateCoordinator(
    private val tokens: JupiterTokensProvider,
    private val quotes: PaperSwapQuoteSource,
    private val safetyProofs: PaperCandidateSafetyProofSource,
    private val riskFacts: PaperRiskFactsSource,
    private val persistence: PaperCandidatePersistence,
    private val filterConfig: CandidateFilterConfig?,
    private val clock: PaperCoordinatorClock = PaperCoordinatorClock { System.currentTimeMillis() },
    private val delay: PaperCoordinatorDelay,
    private val decisionToSubmitLatency: Duration = Duration.ofSeconds(2),
    private val finalStateGate: PaperFinalStateGate = PaperFinalStateGate(),
    maximumCompletedMints: Int = 250,
) {
    private val serialLock = Mutex()
    private val completedMints = BoundedMintHistory(maximumCompletedMints)

    init {
        require(!decisionToSubmitLatency.isNegative)
    }

    suspend fun process(request: PaperCandidateRequest): PaperCandidateResult =
        serialLock.withLock {
            if (!completedMints.add(request.candidate.mint)) return@withLock PaperCandidateResult.Duplicate
            processSerialized(request)
        }

    @Suppress("LongMethod")
    private suspend fun processSerialized(request: PaperCandidateRequest): PaperCandidateResult {
        val configurationErrors = validateConfiguration(request)
        if (configurationErrors.isNotEmpty()) return reject(request, configurationErrors, emptyList(), null)

        persistence.persistCandidate(
            request.candidate.copy(
                state = "OBSERVING",
                rejectionCode = null,
                lastUpdatedAtMillis = clock.nowMillis(),
            ),
        )
        val collection = collectObservations(request)
        val reasons = collection.reasons.toMutableSet()
        if (collection.observations.size != request.strategy.requiredSnapshotCount) {
            reasons += "OBSERVATION_INCOMPLETE"
        }
        if (filterConfig == null) reasons += "FILTER_CONFIGURATION_MISSING"
        if (collection.observations.any { it.proof == null }) reasons += "SAFETY_PROOF_MISSING"
        if (reasons.isNotEmpty() || filterConfig == null) {
            reasons += HardRejectionReason.INCOMPLETE_CRITICAL_DATA.name
            return reject(request, reasons, collection.observations, null)
        }

        val observationErrors = validateObservations(request, collection.observations)
        if (observationErrors.isNotEmpty()) {
            return reject(request, observationErrors, collection.observations, null)
        }
        val evaluation =
            evaluateCandidate(request, collection.observations, filterConfig)
                ?: return reject(
                    request,
                    setOf("STRATEGY_WEIGHTS_INVALID", HardRejectionReason.INCOMPLETE_CRITICAL_DATA.name),
                    collection.observations,
                    null,
                )
        val hardReasons = evaluation.decision.hardRejectionReasons.mapTo(linkedSetOf()) { it.name }
        if (hardReasons.isNotEmpty()) {
            return reject(request, hardReasons, collection.observations, evaluation.decision.score, evaluation)
        }
        if (!evaluation.decision.eligible) {
            return reject(
                request,
                setOf("SCORE_BELOW_MINIMUM"),
                collection.observations,
                evaluation.decision.score,
                evaluation,
            )
        }

        val facts =
            riskFacts.facts(request.sessionId, request.risk, Instant.ofEpochMilli(clock.nowMillis()))
                ?: return reject(
                    request,
                    setOf("RISK_FACTS_INCOMPLETE"),
                    collection.observations,
                    evaluation.decision.score,
                    evaluation,
                )
        val initialQuotes = collection.observations.last()
        val initialCosts =
            quoteCosts(initialQuotes.buy, request.candidate.mint)
                ?: return reject(
                    request,
                    setOf("QUOTE_COST_DATA_INVALID"),
                    collection.observations,
                    evaluation.decision.score,
                    evaluation,
                )
        val limits = request.risk.toRiskLimits()
        val controller = HardRiskController(limits)
        val initialRiskRequest = entryRiskRequest(request, evaluation, initialQuotes, initialCosts, facts)
        val initialRisk = controller.evaluateEntry(facts.snapshot, initialRiskRequest)
        val gatedInitialRisk =
            if (initialRisk.entryAllowed) {
                initialRisk
            } else {
                finalStateGate.run {
                    val now = Instant.ofEpochMilli(clock.nowMillis())
                    val currentFacts =
                        persistence.currentRiskFacts(
                            sessionId = request.sessionId,
                            risk = request.risk,
                            now = now,
                            baseline = facts,
                        ) ?: return@run null
                    val decision =
                        controller.evaluateEntry(
                            currentFacts.snapshot.copy(now = now),
                            entryRiskRequest(request, evaluation, initialQuotes, initialCosts, currentFacts),
                        )
                    if (decision.circuitBreaker.active) {
                        persistence.persistCircuitBreaker(decision.circuitBreaker, now)
                    }
                    decision
                }
            } ?: return reject(
                request,
                setOf("RISK_FACTS_INCOMPLETE"),
                collection.observations,
                evaluation.decision.score,
                evaluation,
            )
        if (!gatedInitialRisk.entryAllowed) {
            return reject(
                request,
                gatedInitialRisk.reasons.mapTo(linkedSetOf()) { "RISK_${it.name}" },
                collection.observations,
                evaluation.decision.score,
                evaluation,
            )
        }
        return executePaperBuy(request, collection.observations, evaluation, controller, initialCosts)
    }

    private fun validateConfiguration(request: PaperCandidateRequest): Set<String> =
        buildSet {
            addIfInvalid(request.sessionId.isBlank() || request.candidate.mint.isBlank(), "CONFIGURATION_INVALID")
            addIfInvalid(
                request.strategy.requiredSnapshotCount !in 2..MAXIMUM_OBSERVATIONS,
                "OBSERVATION_COUNT_INVALID",
            )
            addIfInvalid(
                request.strategy.minimumObservationMillis <= 0 ||
                    request.strategy.minimumObservationMillis > request.strategy.maximumCandidateAgeMillis,
                "OBSERVATION_INTERVAL_INVALID",
            )
            addIfInvalid(request.risk.maximumOpenPositions !in 1..2, "OPEN_POSITION_LIMIT_INVALID")
            addIfInvalid(request.risk.maximumTradeLamports <= 0, "TRADE_AMOUNT_INVALID")
            addIfInvalid(request.risk.maximumSlippageBps !in 0..10_000, "SLIPPAGE_CAP_INVALID")
            addIfInvalid(request.risk.maximumPriorityFeeLamports < 0, "PRIORITY_FEE_CAP_INVALID")
            addIfInvalid(request.risk.maximumTransactionCostLamports < 0, "TRANSACTION_COST_CAP_INVALID")
            addIfInvalid(request.risk.maximumFeePercentBps !in 0..10_000, "FEE_CAP_INVALID")
            addIfInvalid(request.strategy.minimumEntryScore !in 0..100, "SCORE_CONFIGURATION_INVALID")
            addIfInvalid(!request.strategy.exitRulesValid(), "EXIT_RULES_INVALID")
        }

    private suspend fun collectObservations(request: PaperCandidateRequest): ObservationCollection {
        val observations = mutableListOf<PaperObservation>()
        val reasons = linkedSetOf<String>()
        val interval = observationInterval(request.strategy)
        for (index in 0 until request.strategy.requiredSnapshotCount) {
            if (index > 0) delay.wait(interval)
            collectObservation(request, reasons)?.let(observations::add)
        }
        reasons.addIfInvalid(observationWindowTooShort(request, observations), "OBSERVATION_WINDOW_TOO_SHORT")
        return ObservationCollection(observations, reasons)
    }

    private suspend fun collectObservation(
        request: PaperCandidateRequest,
        reasons: MutableSet<String>,
    ): PaperObservation? {
        val token = tokenSnapshot(request, reasons) ?: return null
        val sourceProof = safetyProofs.proof(request.candidate.mint, token)
        val buy = quote(buyRequest(request), "BUY_QUOTE", reasons)
        val sell = sellQuote(request, buy, reasons)
        val quotePairValidated = buy != null && sell != null && validatedQuotePair(request, buy, sell)
        val proof = validatedProof(sourceProof, quotePairValidated)
        val capturedAt = clock.nowMillis()
        val snapshotId =
            persistence.persistSnapshot(
                token.toEntity(
                    mint = request.candidate.mint,
                    proof = proof,
                    buy = buy,
                    sell = sell,
                    quotePairValidated = quotePairValidated,
                    capturedAtMillis = capturedAt,
                ),
            )
        if (buy == null || sell == null) return null
        if (!quotePairValidated) {
            reasons += "QUOTE_PAIR_UNVALIDATED"
            return null
        }
        return PaperObservation(snapshotId.toString(), capturedAt, token, proof, buy, sell)
    }

    private suspend fun tokenSnapshot(
        request: PaperCandidateRequest,
        reasons: MutableSet<String>,
    ): JupiterTokenSnapshot? =
        when (val result = tokens.tokenSnapshot(request.candidate.mint)) {
            is ProviderResult.Failure -> {
                reasons += "TOKEN_${result.error.stableCode()}"
                null
            }

            is ProviderResult.Success -> {
                result.value
            }
        }

    private suspend fun quote(
        request: SwapOrderRequest,
        failurePrefix: String,
        reasons: MutableSet<String>,
    ): SwapOrder? =
        when (val result = quotes.order(request)) {
            is ProviderResult.Failure -> {
                reasons += "${failurePrefix}_${result.error.stableCode()}"
                null
            }

            is ProviderResult.Success -> {
                result.value
            }
        }

    private suspend fun sellQuote(
        request: PaperCandidateRequest,
        buy: SwapOrder?,
        reasons: MutableSet<String>,
    ): SwapOrder? {
        val amount = buy?.outAmountAtomic?.toLongOrNull()?.takeIf { it > 0 }
        if (buy != null && amount == null) reasons += "SELL_AMOUNT_UNSUPPORTED"
        return amount?.let { quote(sellRequest(request, it), "SELL_QUOTE", reasons) }
    }

    private fun validatedProof(
        proof: PaperCandidateSafetyProof?,
        quotePairValidated: Boolean,
    ): PaperCandidateSafetyProof? =
        proof?.copy(
            quoteSemanticsValidated = quotePairValidated,
            unsupportedRouteBehavior = !quotePairValidated,
        )

    private fun observationWindowTooShort(
        request: PaperCandidateRequest,
        observations: List<PaperObservation>,
    ): Boolean =
        observations.size >= 2 &&
            observations.last().capturedAtMillis - observations.first().capturedAtMillis <
            request.strategy.minimumObservationMillis

    private fun observationInterval(strategy: StrategyConfigEntity): Duration {
        val gaps = strategy.requiredSnapshotCount - 1L
        val millis = (strategy.minimumObservationMillis + gaps - 1L) / gaps
        return Duration.ofMillis(millis)
    }

    private fun buyRequest(request: PaperCandidateRequest) =
        SwapOrderRequest(
            inputMint = WRAPPED_SOL_MINT,
            outputMint = request.candidate.mint,
            amountAtomic = request.risk.maximumTradeLamports,
            taker = null,
            maximumSlippageBps = request.risk.maximumSlippageBps,
            maximumPriorityFeeLamports = request.risk.maximumPriorityFeeLamports,
            maximumTotalFeeBps = request.risk.maximumFeePercentBps,
            maximumTransactionCostLamports = request.risk.maximumTransactionCostLamports,
        )

    private fun sellRequest(
        request: PaperCandidateRequest,
        amountAtomic: Long,
    ) = SwapOrderRequest(
        inputMint = request.candidate.mint,
        outputMint = WRAPPED_SOL_MINT,
        amountAtomic = amountAtomic,
        taker = null,
        maximumSlippageBps = request.risk.maximumSlippageBps,
        maximumPriorityFeeLamports = request.risk.maximumPriorityFeeLamports,
        maximumTotalFeeBps = request.risk.maximumFeePercentBps,
        maximumTransactionCostLamports = request.risk.maximumTransactionCostLamports,
    )

    private fun validatedQuotePair(
        request: PaperCandidateRequest,
        buy: SwapOrder,
        sell: SwapOrder,
    ): Boolean {
        if (!buy.matchesBuy(request) || !sell.matchesSell(request, buy)) return false
        if (!quoteMetadataValid(buy, sell)) return false
        if (!quoteLimitsValid(request, buy, sell)) return false
        val buyCosts = quoteCosts(buy, request.candidate.mint) ?: return false
        val sellCosts = quoteCosts(sell, request.candidate.mint) ?: return false
        if (!quoteCostsValid(request, buyCosts, sellCosts)) return false
        val sellOutput = sell.outAmountAtomic.toLongOrNull() ?: return false
        return sellOutput >= request.strategy.minimumSellOutputLamports &&
            priceImpactBps(buy.priceImpactPercent) != null &&
            priceImpactBps(sell.priceImpactPercent) != null
    }

    private fun quoteMetadataValid(
        buy: SwapOrder,
        sell: SwapOrder,
    ): Boolean =
        buy.unsignedTransactionBase64 == null &&
            sell.unsignedTransactionBase64 == null &&
            buy.router in ALLOWED_QUOTE_ROUTERS &&
            sell.router in ALLOWED_QUOTE_ROUTERS &&
            buy.mode in ALLOWED_QUOTE_MODES &&
            sell.mode in ALLOWED_QUOTE_MODES &&
            buy.requestId.isNotBlank() &&
            sell.requestId.isNotBlank()

    private fun quoteLimitsValid(
        request: PaperCandidateRequest,
        buy: SwapOrder,
        sell: SwapOrder,
    ): Boolean {
        val buySlippage = buy.slippageBps ?: return false
        val sellSlippage = sell.slippageBps ?: return false
        return buySlippage in 0..request.risk.maximumSlippageBps &&
            sellSlippage in 0..request.risk.maximumSlippageBps &&
            buy.feeBps <= request.risk.maximumFeePercentBps &&
            sell.feeBps <= request.risk.maximumFeePercentBps &&
            buy.prioritizationFeeLamports <= request.risk.maximumPriorityFeeLamports &&
            sell.prioritizationFeeLamports <= request.risk.maximumPriorityFeeLamports
    }

    private fun quoteCostsValid(
        request: PaperCandidateRequest,
        buy: QuoteCosts,
        sell: QuoteCosts,
    ): Boolean =
        buy.totalSolLamports <= request.risk.maximumTransactionCostLamports &&
            sell.totalSolLamports <= request.risk.maximumTransactionCostLamports

    private fun validateObservations(
        request: PaperCandidateRequest,
        observations: List<PaperObservation>,
    ): Set<String> =
        buildSet {
            val first = observations.first()
            observations.forEach { observation -> validateObservation(request, first, observation, this) }
        }

    private fun validateObservation(
        request: PaperCandidateRequest,
        first: PaperObservation,
        observation: PaperObservation,
        reasons: MutableSet<String>,
    ) {
        val proof = observation.proof ?: return
        reasons.addIfInvalid(!tokenDataConsistent(request, first, observation), "TOKEN_DATA_INCONSISTENT")
        reasons.addIfInvalid(!tokenDataComplete(request, observation), "TOKEN_DATA_INCOMPLETE_OR_STALE")
        reasons.addIfInvalid(proof.liquidity.value <= 0, "LIQUIDITY_DATA_INVALID")
        reasons.addIfInvalid(
            request.candidate.discoveredAtMillis > observation.capturedAtMillis,
            "CANDIDATE_TIME_INVALID",
        )
        reasons.addIfInvalid(!quoteDataConsistent(request, observation), "QUOTE_DATA_INCONSISTENT")
        reasons.addIfInvalid(!slippageWithinCap(request, observation), "SLIPPAGE_CAP_EXCEEDED")
        reasons.addIfInvalid(!sellOutputSufficient(request, observation), "SELL_OUTPUT_INSUFFICIENT")
        reasons.addIfInvalid(!quoteCostDataValid(request, observation), "QUOTE_COST_DATA_INVALID")
    }

    private fun tokenDataConsistent(
        request: PaperCandidateRequest,
        first: PaperObservation,
        observation: PaperObservation,
    ): Boolean =
        observation.token.run {
            mint == request.candidate.mint &&
                mint == first.token.mint &&
                decimals == first.token.decimals &&
                tokenProgram == first.token.tokenProgram &&
                name == first.token.name &&
                symbol == first.token.symbol
        }

    private fun tokenDataComplete(
        request: PaperCandidateRequest,
        observation: PaperObservation,
    ): Boolean =
        observation.token.run {
            audit.developerBalancePercentage != null &&
                audit.developerMintCount != null &&
                stats5m.organicBuyerCount != null &&
                stats5m.netBuyerCount != null &&
                usdPrice != null &&
                updatedAtMillis <= observation.capturedAtMillis &&
                observation.capturedAtMillis - updatedAtMillis <= request.risk.minimumDataFreshnessMillis
        }

    private fun quoteDataConsistent(
        request: PaperCandidateRequest,
        observation: PaperObservation,
    ): Boolean = observation.buy.matchesBuy(request) && observation.sell.matchesSell(request, observation.buy)

    private fun slippageWithinCap(
        request: PaperCandidateRequest,
        observation: PaperObservation,
    ): Boolean =
        observation.buy.slippageBps != null &&
            observation.sell.slippageBps != null &&
            observation.buy.slippageBps in 0..request.risk.maximumSlippageBps &&
            observation.sell.slippageBps in 0..request.risk.maximumSlippageBps

    private fun sellOutputSufficient(
        request: PaperCandidateRequest,
        observation: PaperObservation,
    ): Boolean =
        observation.sell.outAmountAtomic
            .toLongOrNull()
            ?.let { it >= request.strategy.minimumSellOutputLamports } == true

    private fun quoteCostDataValid(
        request: PaperCandidateRequest,
        observation: PaperObservation,
    ): Boolean =
        quoteCosts(observation.buy, request.candidate.mint) != null &&
            quoteCosts(observation.sell, request.candidate.mint) != null &&
            priceImpactBps(observation.buy.priceImpactPercent) != null &&
            priceImpactBps(observation.sell.priceImpactPercent) != null

    private fun MutableSet<String>.addIfInvalid(
        invalid: Boolean,
        reason: String,
    ) {
        if (invalid) add(reason)
    }

    private fun evaluateCandidate(
        request: PaperCandidateRequest,
        observations: List<PaperObservation>,
        filters: CandidateFilterConfig,
    ): PaperEvaluation? {
        val strategy = request.strategy.toDomainStrategy() ?: return null
        val first = observations.first()
        val last = observations.last()
        val firstProof = requireNotNull(first.proof)
        val lastProof = requireNotNull(last.proof)
        val buyCosts = quoteCosts(last.buy, request.candidate.mint) ?: return null
        val sellCosts = quoteCosts(last.sell, request.candidate.mint) ?: return null
        val roundTripLossBps =
            roundTripLossBps(request.risk.maximumTradeLamports, last.sell, buyCosts, sellCosts)
                ?: return null
        val feeRatioBps = feeRatioBps(request.risk.maximumTradeLamports, buyCosts, sellCosts) ?: return null
        val priceIncrease = percentageIncrease(first.token.usdPrice, last.token.usdPrice) ?: return null
        val firstOrganicBuyerCount = requireNotNull(first.token.stats5m.organicBuyerCount)
        val lastOrganicBuyerCount = requireNotNull(last.token.stats5m.organicBuyerCount)
        val dataAge = Duration.ofMillis(last.capturedAtMillis - last.token.updatedAtMillis)
        val tokenAge = Duration.ofMillis(last.capturedAtMillis - request.candidate.discoveredAtMillis)
        val program = last.token.tokenProgram.toDomainTokenProgram()
        val snapshot =
            CandidateSnapshot(
                snapshotId = last.snapshotId,
                observedAt = Instant.ofEpochMilli(last.capturedAtMillis),
                criticalDataComplete = true,
                mintAddressValid = lastProof.mintAddressValid,
                tokenProgram = program,
                hasDangerousTokenExtensions = !lastProof.tokenExtensionsSafe,
                mintAuthorityRevoked = last.token.audit.mintAuthorityDisabled,
                freezeAuthorityRevoked = last.token.audit.freezeAuthorityDisabled,
                providerWarning = last.token.audit.isSuspicious,
                creatorHoldingPercent = last.token.audit.developerBalancePercentage,
                topHolderPercent = last.token.audit.topHoldersPercentage,
                holderCount = last.token.holderCount,
                uniqueBuyers = lastOrganicBuyerCount,
                buyerGrowthPositive = lastOrganicBuyerCount > firstOrganicBuyerCount,
                buyCount =
                    last.token.stats5m.buyCount
                        .toBigDecimal(),
                sellCount =
                    last.token.stats5m.sellCount
                        .toBigDecimal(),
                buyVolume = last.token.stats5m.organicBuyVolumeUsd,
                sellVolume = last.token.stats5m.organicSellVolumeUsd,
                organicActivityPresent =
                    last.token.organicScore.signum() > 0 &&
                        last.token.stats5m.organicBuyVolumeUsd
                            .signum() > 0,
                liquidity = lastProof.liquidity,
                liquidityChangeNegative = lastProof.liquidity < firstProof.liquidity,
                buyPriceImpactBps = priceImpactBps(last.buy.priceImpactPercent) ?: return null,
                buyRouteAvailable = true,
                sellRouteAvailable = true,
                roundTripLossBps = roundTripLossBps,
                feeRatioBps = feeRatioBps,
                tokenAge = tokenAge,
                preEntryPriceIncreasePercent = priceIncrease,
                suspiciousWalletActivity = lastProof.suspiciousWalletActivity,
                dataAge = dataAge,
                providersAgree = observations.all { it.proof?.independentProvidersAgree == true },
                programAllowlistCompatible = lastProof.programAllowlisted,
                quoteAndTransactionDecodable = false,
                paperQuoteSemanticsValidated = lastProof.quoteSemanticsValidated,
                unsupportedRouteBehavior = lastProof.unsupportedRouteBehavior,
            )
        val factors = factorValues(snapshot, first, last, firstProof, lastProof, filters)
        val result =
            RuleBasedDecisionEngine(strategy, filters, allowPaperQuoteOnly = true)
                .evaluate(snapshot, factors)
                .copy(
                    snapshotIds = observations.map(PaperObservation::snapshotId),
                    snapshotTimes = observations.map { Instant.ofEpochMilli(it.capturedAtMillis) },
                )
        return PaperEvaluation(
            decision = result,
            factors = factors,
            snapshot = snapshot,
            buyCosts = buyCosts,
            sellCosts = sellCosts,
            lastToken = last.token,
        )
    }

    @Suppress("LongParameterList")
    private fun factorValues(
        snapshot: CandidateSnapshot,
        first: PaperObservation,
        last: PaperObservation,
        firstProof: PaperCandidateSafetyProof,
        lastProof: PaperCandidateSafetyProof,
        filters: CandidateFilterConfig,
    ): Map<ScoreFactor, BigDecimal> {
        val buyCount =
            last.token.stats5m.buyCount
                .toBigDecimal()
        val sellCount =
            last.token.stats5m.sellCount
                .toBigDecimal()
        val totalCount = buyCount + sellCount
        val concentration = percentQuality(last.token.audit.topHoldersPercentage)
        val creatorConcentration = percentQuality(requireNotNull(last.token.audit.developerBalancePercentage))
        val momentum =
            inverseAgainstCap(
                snapshot.preEntryPriceIncreasePercent,
                filters.maximumPreEntryPriceIncreasePercent,
            )
        return mapOf(
            ScoreFactor.BUYER_GROWTH to
                growthFactor(
                    requireNotNull(first.token.stats5m.organicBuyerCount).toBigDecimal(),
                    requireNotNull(last.token.stats5m.organicBuyerCount).toBigDecimal(),
                ),
            ScoreFactor.BUY_SELL_IMBALANCE to if (totalCount.signum() == 0) ZERO else unit(ratio(buyCount, totalCount)),
            ScoreFactor.ORGANIC_ACTIVITY to unit(last.token.organicScore.movePointLeft(2)),
            ScoreFactor.HOLDER_GROWTH to
                growthFactor(
                    first.token.holderCount.toBigDecimal(),
                    last.token.holderCount.toBigDecimal(),
                ),
            ScoreFactor.LIQUIDITY_GROWTH to
                growthFactor(
                    firstProof.liquidity.value.toBigDecimal(),
                    lastProof.liquidity.value.toBigDecimal(),
                ),
            ScoreFactor.CREATOR_CONCENTRATION to creatorConcentration,
            ScoreFactor.TOP_HOLDER_CONCENTRATION to concentration,
            ScoreFactor.PRICE_MOMENTUM to momentum,
            ScoreFactor.ALREADY_PUMPED to momentum,
            ScoreFactor.ROUTE_QUALITY to
                inverseAgainstCap(
                    snapshot.buyPriceImpactBps.toBigDecimal(),
                    filters.maximumBuyPriceImpactBps.toBigDecimal(),
                ),
            ScoreFactor.ROUND_TRIP_COST to
                inverseAgainstCap(
                    snapshot.roundTripLossBps.toBigDecimal(),
                    filters.maximumRoundTripLossBps.toBigDecimal(),
                ),
            ScoreFactor.DATA_CONSISTENCY to if (snapshot.providersAgree) ONE else ZERO,
            ScoreFactor.TOKEN_AGE to
                inverseAgainstCap(
                    snapshot.tokenAge.toMillis().toBigDecimal(),
                    filters.maximumTokenAge.toMillis().toBigDecimal(),
                ),
            ScoreFactor.LARGE_SELL_ACTIVITY to
                if (totalCount.signum() == 0) {
                    ZERO
                } else {
                    unit(ONE - ratio(sellCount, totalCount))
                },
        )
    }

    @Suppress("LongMethod")
    private suspend fun executePaperBuy(
        candidateRequest: PaperCandidateRequest,
        observations: List<PaperObservation>,
        evaluation: PaperEvaluation,
        controller: HardRiskController,
        initialCosts: QuoteCosts,
    ): PaperCandidateResult {
        val initial = observations.last()
        var refreshed: RefreshedRoundTrip? = null
        val engine =
            PaperExecutionEngine(
                quoteProvider =
                    object : PaperQuoteProvider {
                        override fun refreshBuy(request: PaperBuyRequest): PaperBuyQuote? =
                            runBlocking {
                                val roundTrip =
                                    refreshRoundTrip(candidateRequest, initialCosts) ?: return@runBlocking null
                                refreshed = roundTrip
                                PaperBuyQuote(
                                    expectedOutputAtomic = roundTrip.buy.outAmountAtomic.toBigInteger(),
                                    routeAvailable = true,
                                )
                            }

                        override fun refreshSell(request: PaperSellRequest): PaperSellQuote? = null
                    },
                sleeper = SimulationSleeper { duration -> runBlocking { delay.wait(duration) } },
            )
        val outcome =
            engine.executeBuy(
                PaperBuyRequest(
                    input = Lamports.of(candidateRequest.risk.maximumTradeLamports),
                    tokenDecimals = initial.token.decimals,
                    initialQuote =
                        PaperBuyQuote(
                            expectedOutputAtomic = initial.buy.outAmountAtomic.toBigInteger(),
                            routeAvailable = true,
                        ),
                    costs = initialCosts.paperCosts,
                ),
                PaperExecutionConfig(
                    decisionToSubmitLatency = decisionToSubmitLatency,
                    executionSlippageBps = candidateRequest.risk.maximumSlippageBps,
                ),
            )
        if (outcome is PaperBuyOutcome.Rejected) {
            return reject(
                candidateRequest,
                setOf("PAPER_${outcome.reason.name}"),
                observations,
                evaluation.decision.score,
                evaluation,
            )
        }
        require(outcome is PaperBuyOutcome.Filled)
        val refreshedQuotes =
            refreshed ?: return reject(
                candidateRequest,
                setOf("PAPER_REFRESH_MISSING"),
                observations,
                evaluation.decision.score,
                evaluation,
            )
        val latestFacts =
            riskFacts.facts(
                candidateRequest.sessionId,
                candidateRequest.risk,
                Instant.ofEpochMilli(clock.nowMillis()),
            ) ?: return reject(
                candidateRequest,
                setOf("RISK_FACTS_INCOMPLETE"),
                observations,
                evaluation.decision.score,
                evaluation,
            )
        val finalQuotes =
            refreshActualFullSell(candidateRequest, outcome, refreshedQuotes) ?: return reject(
                candidateRequest,
                setOf("FULL_SELL_QUOTE_UNAVAILABLE"),
                observations,
                evaluation.decision.score,
                evaluation,
            )
        val finalOutcome =
            finalStateGate.run {
                val finalNow = Instant.ofEpochMilli(clock.nowMillis())
                val currentFacts =
                    persistence.currentRiskFacts(
                        sessionId = candidateRequest.sessionId,
                        risk = candidateRequest.risk,
                        now = finalNow,
                        baseline = latestFacts,
                    ) ?: return@run FinalPaperEntryOutcome.Rejected(setOf("RISK_FACTS_INCOMPLETE"))
                val finalObservation =
                    initial.copy(
                        capturedAtMillis = finalNow.toEpochMilli(),
                        buy = finalQuotes.buy,
                        sell = finalQuotes.sell,
                    )
                val finalRisk =
                    controller.evaluateEntry(
                        currentFacts.snapshot.copy(now = finalNow),
                        entryRiskRequest(
                            candidateRequest,
                            evaluation,
                            finalObservation,
                            finalQuotes.buyCosts,
                            currentFacts,
                        ),
                    )
                if (finalRisk.circuitBreaker.active) {
                    persistence.persistCircuitBreaker(finalRisk.circuitBreaker, finalNow)
                }
                if (!finalRisk.entryAllowed) {
                    return@run FinalPaperEntryOutcome.Rejected(
                        finalRisk.reasons.mapTo(linkedSetOf()) { "RISK_${it.name}" },
                    )
                }
                val fill =
                    buildPaperEntryFacts(
                        request = candidateRequest,
                        evaluation = evaluation,
                        outcome = outcome,
                        quotes = finalQuotes,
                        nowMillis = finalNow.toEpochMilli(),
                    )
                when (persistence.persistPaperFill(fill)) {
                    PaperFillPersistenceResult.PERSISTED -> FinalPaperEntryOutcome.Persisted(fill)
                    PaperFillPersistenceResult.UNSUPPORTED -> FinalPaperEntryOutcome.Unsupported
                }
            }
        return when (finalOutcome) {
            is FinalPaperEntryOutcome.Persisted -> {
                PaperCandidateResult.Filled(
                    positionId = finalOutcome.facts.position.id,
                    score = requireNotNull(evaluation.decision.score),
                )
            }

            is FinalPaperEntryOutcome.Rejected -> {
                reject(
                    candidateRequest,
                    finalOutcome.reasons,
                    observations,
                    evaluation.decision.score,
                    evaluation,
                )
            }

            FinalPaperEntryOutcome.Unsupported -> {
                reject(
                    candidateRequest,
                    setOf("PAPER_FILL_PERSISTENCE_UNAVAILABLE"),
                    observations,
                    evaluation.decision.score,
                    evaluation,
                )
            }
        }
    }

    private suspend fun refreshRoundTrip(
        request: PaperCandidateRequest,
        initialCosts: QuoteCosts,
    ): RefreshedRoundTrip? {
        val filters = requireNotNull(filterConfig)
        val buy =
            when (val result = quotes.order(buyRequest(request))) {
                is ProviderResult.Failure -> return null
                is ProviderResult.Success -> result.value
            }
        if (!buy.matchesBuy(request)) return null
        val buyCosts = quoteCosts(buy, request.candidate.mint) ?: return null
        if (buyCosts != initialCosts) return null
        val amount = buy.outAmountAtomic.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val sell =
            when (val result = quotes.order(sellRequest(request, amount))) {
                is ProviderResult.Failure -> return null
                is ProviderResult.Success -> result.value
            }
        if (!validatedQuotePair(request, buy, sell)) return null
        val sellCosts = quoteCosts(sell, request.candidate.mint) ?: return null
        val roundTripLoss =
            roundTripLossBps(request.risk.maximumTradeLamports, sell, buyCosts, sellCosts)
                ?: return null
        val feeRatio = feeRatioBps(request.risk.maximumTradeLamports, buyCosts, sellCosts) ?: return null
        val sellOutput = sell.outAmountAtomic.toLongOrNull() ?: return null
        if (
            buy.slippageBps == null || sell.slippageBps == null ||
            buy.slippageBps !in 0..request.risk.maximumSlippageBps ||
            sell.slippageBps !in 0..request.risk.maximumSlippageBps ||
            buy.priceImpactPercent.signum() < 0 ||
            (priceImpactBps(buy.priceImpactPercent) ?: return null) > filters.maximumBuyPriceImpactBps ||
            roundTripLoss > filters.maximumRoundTripLossBps ||
            feeRatio > filters.maximumFeeRatioBps ||
            sellOutput < request.strategy.minimumSellOutputLamports
        ) {
            return null
        }
        return RefreshedRoundTrip(buy, sell, buyCosts, sellCosts)
    }

    private suspend fun refreshActualFullSell(
        request: PaperCandidateRequest,
        outcome: PaperBuyOutcome.Filled,
        refreshed: RefreshedRoundTrip,
    ): RefreshedRoundTrip? {
        val amount =
            outcome.received.atomicUnits
                .toLongExactOrNull()
                ?.takeIf { it > 0 } ?: return null
        // CPD-OFF
        val sell =
            when (val result = quotes.order(sellRequest(request, amount))) {
                is ProviderResult.Failure -> return null
                is ProviderResult.Success -> result.value
            }
        // CPD-ON
        val expectedInput =
            refreshed.buy.copy(
                outAmountAtomic = amount.toString(),
                minimumOutAmountAtomic = amount.toString(),
            )
        if (!validatedQuotePair(request, expectedInput, sell)) return null
        val costs = quoteCosts(sell, request.candidate.mint) ?: return null
        val output = sell.outAmountAtomic.toLongOrNull() ?: return null
        val filters = requireNotNull(filterConfig)
        val roundTripLoss =
            roundTripLossBps(request.risk.maximumTradeLamports, sell, refreshed.buyCosts, costs)
                ?: return null
        val feeRatio = feeRatioBps(request.risk.maximumTradeLamports, refreshed.buyCosts, costs) ?: return null
        if (
            sell.slippageBps == null || sell.slippageBps !in 0..request.risk.maximumSlippageBps ||
            sell.priceImpactPercent.signum() < 0 ||
            roundTripLoss > filters.maximumRoundTripLossBps ||
            feeRatio > filters.maximumFeeRatioBps ||
            output < request.strategy.minimumSellOutputLamports
        ) {
            return null
        }
        return refreshed.copy(sell = sell, sellCosts = costs)
    }

    @Suppress("LongMethod")
    private fun buildPaperEntryFacts(
        request: PaperCandidateRequest,
        evaluation: PaperEvaluation,
        outcome: PaperBuyOutcome.Filled,
        quotes: RefreshedRoundTrip,
        nowMillis: Long,
    ): PaperEntryFacts {
        val now = nowMillis
        val decisionId = UUID.randomUUID().toString()
        val positionId = UUID.randomUUID().toString()
        val score = requireNotNull(evaluation.decision.score)
        val totalDebit = outcome.totalSolDebited.value.toLongExactCompat()
        val entryCost = Math.subtractExact(totalDebit, request.risk.maximumTradeLamports)
        val sellOutput = quotes.sell.outAmountAtomic.toLong()
        val decision =
            decisionEntity(
                request = request,
                action = "PAPER_BUY",
                score = score,
                reasons = emptySet(),
                evaluation = evaluation,
                nowMillis = now,
                id = decisionId,
            )
        val candidate =
            request.candidate.copy(
                tokenProgram = evaluation.snapshot.tokenProgram.name,
                state = "POSITION_OPEN",
                score = score,
                rejectionCode = null,
                lastUpdatedAtMillis = now,
            )
        val position =
            PositionEntity(
                id = positionId,
                sessionId = request.sessionId,
                mint = request.candidate.mint,
                entryDecisionId = decisionId,
                status = "OPEN",
                tokenAmountAtomic = outcome.received.atomicUnits.toString(),
                grossInputLamports = totalDebit,
                netInputLamports = request.risk.maximumTradeLamports,
                latestSellQuoteLamports = sellOutput,
                entrySignature = null,
                exitSignature = null,
                openedAtMillis = now,
                updatedAtMillis = now,
                closedAtMillis = null,
                exitReason = null,
                mode = "PAPER",
                symbol = evaluation.lastToken.symbol,
                name = evaluation.lastToken.name,
                tokenDecimals = evaluation.lastToken.decimals,
                tokenProgram = evaluation.lastToken.tokenProgram,
                entryCostLamports = entryCost,
                exitRulesVersion = "${request.strategy.version}:${request.risk.version}",
                exitRulesJson = request.exitRulesJson(),
                latestSellQuoteAtMillis = now,
                highestExecutableSellLamports = sellOutput,
                lowestExecutableSellLamports = sellOutput,
                routeAvailable = true,
                reconciliationState = "RECONCILED",
            )
        val intent =
            TradeIntentEntity(
                id = UUID.randomUUID().toString(),
                sessionId = request.sessionId,
                positionId = positionId,
                idempotencyKey = "paper:${request.sessionId}:${request.candidate.mint}:${request.strategy.version}:BUY",
                side = "BUY",
                mint = request.candidate.mint,
                requestedInputAtomic = request.risk.maximumTradeLamports.toString(),
                expectedOutputAtomic = outcome.received.atomicUnits.toString(),
                maximumCostLamports = request.risk.maximumTransactionCostLamports,
                providerRequestId = quotes.buy.requestId,
                status = "PAPER_FILLED",
                paperFeeLamports = entryCost,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        val performanceDay =
            Instant
                .ofEpochMilli(now)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toEpochDay()
        val performanceDelta =
            PaperPerformanceDelta(
                totalFeesLamports = entryCost,
                tradeCount = 1,
            )
        return PaperEntryFacts(
            candidate = candidate,
            decision = decision,
            intent = intent,
            position = position,
            performanceEpochDay = performanceDay,
            performanceDelta = performanceDelta,
            performanceAtMillis = now,
        )
    }

    private suspend fun reject(
        request: PaperCandidateRequest,
        reasons: Set<String>,
        observations: List<PaperObservation>,
        score: Int?,
        evaluation: PaperEvaluation? = null,
    ): PaperCandidateResult.Rejected {
        val orderedReasons = reasons.toSortedSet()
        val now = clock.nowMillis()
        val candidate =
            request.candidate.copy(
                tokenProgram = observations.lastOrNull()?.token?.tokenProgram ?: request.candidate.tokenProgram,
                state = "REJECTED",
                score = score,
                rejectionCode = orderedReasons.joinToString("|"),
                lastUpdatedAtMillis = now,
            )
        val decision =
            decisionEntity(
                request = request,
                action = "REJECT",
                score = score ?: 0,
                reasons = orderedReasons,
                evaluation = evaluation,
                nowMillis = now,
            )
        persistence.persistDecision(candidate, decision)
        return PaperCandidateResult.Rejected(
            reasons = orderedReasons,
            snapshotCount = observations.size,
            score = score,
        )
    }

    @Suppress("LongParameterList")
    private fun decisionEntity(
        request: PaperCandidateRequest,
        action: String,
        score: Int,
        reasons: Set<String>,
        evaluation: PaperEvaluation?,
        nowMillis: Long,
        id: String = UUID.randomUUID().toString(),
    ) = DecisionEntity(
        id = id,
        sessionId = request.sessionId,
        candidateMint = request.candidate.mint,
        strategyVersion = request.strategy.version,
        riskVersion = request.risk.version,
        action = action,
        score = score,
        factorsJson = evaluation.toFactorsJson(),
        rejectionCodesJson = JsonArray(reasons.sorted().map { JsonPrimitive(it) }).toString(),
        createdAtMillis = nowMillis,
    )

    private fun entryRiskRequest(
        request: PaperCandidateRequest,
        evaluation: PaperEvaluation,
        quotes: PaperObservation,
        costs: QuoteCosts,
        facts: PaperRiskFacts,
    ): EntryRiskRequest {
        val feeRatio =
            feeRatioBps(
                request.risk.maximumTradeLamports,
                costs,
                quoteCosts(quotes.sell, request.candidate.mint) ?: QuoteCosts.ZERO,
            ) ?: Int.MAX_VALUE
        return EntryRiskRequest(
            tradeAmount = Lamports.of(request.risk.maximumTradeLamports),
            approximateEur = facts.approximateTradeEur,
            estimatedPriorityFee = costs.paperCosts.solCosts.priorityFee,
            estimatedTransactionCost = Lamports.of(costs.totalSolLamports),
            estimatedFeeRatioBps = feeRatio,
            slippageBps = maxOf(requireNotNull(quotes.buy.slippageBps), requireNotNull(quotes.sell.slippageBps)),
            plannedHoldingTime = Duration.ofMillis(request.risk.maximumHoldingMillis),
            candidateAge = Duration.ofMillis(clock.nowMillis() - request.candidate.discoveredAtMillis),
            preEntryPriceIncreasePercent = evaluation.snapshot.preEntryPriceIncreasePercent,
            dataAge = Duration.ofMillis(clock.nowMillis() - evaluation.lastToken.updatedAtMillis),
            providerHealth = facts.providerHealth,
        )
    }

    private fun quoteCosts(
        order: SwapOrder,
        candidateMint: String,
    ): QuoteCosts? {
        return try {
            if (
                order.feeBps !in 0..10_000 ||
                order.signatureFeeLamports < 0 ||
                order.prioritizationFeeLamports < 0 ||
                order.rentFeeLamports < 0 ||
                order.priceImpactPercent.signum() < 0
            ) {
                return null
            }
            val feeLamports =
                when {
                    order.feeBps == 0 -> {
                        0L
                    }

                    order.feeMint == WRAPPED_SOL_MINT -> {
                        val basis =
                            when {
                                order.inputMint == WRAPPED_SOL_MINT -> order.inAmountAtomic.toBigIntegerOrNull()
                                order.outputMint == WRAPPED_SOL_MINT -> order.outAmountAtomic.toBigIntegerOrNull()
                                else -> null
                            } ?: return null
                        basis
                            .multiply(order.feeBps.toBigInteger())
                            .divide(BASIS_POINTS)
                            .toLongExactCompat()
                    }

                    order.feeMint == candidateMint -> {
                        return null
                    }

                    else -> {
                        return null
                    }
                }
            val total =
                listOf(
                    feeLamports,
                    order.signatureFeeLamports,
                    order.prioritizationFeeLamports,
                    order.rentFeeLamports,
                ).fold(BigInteger.ZERO) { sum, value -> sum + value.toBigInteger() }.toLongExactCompat()
            QuoteCosts(
                paperCosts =
                    PaperCosts(
                        solCosts =
                            TradeCosts(
                                platformFee = Lamports.of(feeLamports),
                                baseFee = Lamports.of(order.signatureFeeLamports),
                                priorityFee = Lamports.of(order.prioritizationFeeLamports),
                                associatedTokenRent = Lamports.of(order.rentFeeLamports),
                            ),
                    ),
                totalSolLamports = total,
            )
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun roundTripLossBps(
        inputLamports: Long,
        sell: SwapOrder,
        buyCosts: QuoteCosts,
        sellCosts: QuoteCosts,
    ): Int? {
        val sellOutput = sell.outAmountAtomic.toBigIntegerOrNull() ?: return null
        val input = inputLamports.toBigInteger()
        val totalCosts = buyCosts.totalSolLamports.toBigInteger() + sellCosts.totalSolLamports.toBigInteger()
        val loss = (input - sellOutput + totalCosts).max(BigInteger.ZERO)
        return ceilBps(loss, input)
    }

    private fun feeRatioBps(
        inputLamports: Long,
        buyCosts: QuoteCosts,
        sellCosts: QuoteCosts,
    ): Int? {
        if (inputLamports <= 0) return null
        val fees = buyCosts.totalSolLamports.toBigInteger() + sellCosts.totalSolLamports.toBigInteger()
        return ceilBps(fees, inputLamports.toBigInteger())
    }

    private fun ceilBps(
        value: BigInteger,
        denominator: BigInteger,
    ): Int? {
        if (denominator.signum() <= 0 || value.signum() < 0) return null
        val numerator = value * BASIS_POINTS
        return try {
            numerator.add(denominator - BigInteger.ONE).divide(denominator).toIntExactCompat()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun priceImpactBps(percent: BigDecimal): Int? =
        try {
            percent
                .movePointRight(2)
                .setScale(0, RoundingMode.CEILING)
                .intValueExact()
        } catch (_: ArithmeticException) {
            null
        }

    private fun percentageIncrease(
        first: BigDecimal?,
        last: BigDecimal?,
    ): BigDecimal? {
        if (first == null || last == null || first.signum() <= 0) return null
        return last
            .subtract(first)
            .max(BigDecimal.ZERO)
            .multiply(HUNDRED)
            .divide(first, DECIMAL_SCALE, RoundingMode.HALF_UP)
    }

    private fun growthFactor(
        first: BigDecimal,
        last: BigDecimal,
    ): BigDecimal {
        if (first.signum() < 0 || last <= first) return ZERO
        val denominator = first.max(ONE)
        return unit(last.subtract(first).divide(denominator, DECIMAL_SCALE, RoundingMode.HALF_UP))
    }

    private fun percentQuality(percent: BigDecimal): BigDecimal = unit(ONE - percent.movePointLeft(2))

    private fun inverseAgainstCap(
        value: BigDecimal,
        cap: BigDecimal,
    ): BigDecimal =
        when {
            value.signum() < 0 -> ZERO
            cap.signum() <= 0 -> if (value.signum() == 0) ONE else ZERO
            else -> unit(ONE - value.divide(cap, DECIMAL_SCALE, RoundingMode.HALF_UP))
        }

    private fun ratio(
        numerator: BigDecimal,
        denominator: BigDecimal,
    ): BigDecimal = numerator.divide(denominator, DECIMAL_SCALE, RoundingMode.HALF_UP)

    private fun unit(value: BigDecimal): BigDecimal = value.max(ZERO).min(ONE)

    private fun PaperEvaluation?.toFactorsJson(): String =
        buildJsonObject {
            put("completenessPercent", this@toFactorsJson?.decision?.dataCompletenessPercent ?: 0)
            put("mandatoryPass", this@toFactorsJson?.decision?.mandatoryPass ?: false)
            put("strategyVersion", this@toFactorsJson?.decision?.strategyVersion ?: "")
            put(
                "values",
                JsonObject(
                    this@toFactorsJson
                        ?.factors
                        .orEmpty()
                        .entries
                        .sortedBy { it.key.name }
                        .associate { (factor, value) -> factor.name to JsonPrimitive(value.toPlainString()) },
                ),
            )
            put(
                "snapshotIds",
                buildJsonArray {
                    this@toFactorsJson
                        ?.decision
                        ?.snapshotIds
                        .orEmpty()
                        .forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "snapshotTimes",
                buildJsonArray {
                    this@toFactorsJson?.decision?.snapshotTimes.orEmpty().forEach {
                        add(JsonPrimitive(it.toString()))
                    }
                },
            )
        }.toString()

    private data class ObservationCollection(
        val observations: List<PaperObservation>,
        val reasons: Set<String>,
    )

    private data class PaperObservation(
        val snapshotId: String,
        val capturedAtMillis: Long,
        val token: JupiterTokenSnapshot,
        val proof: PaperCandidateSafetyProof?,
        val buy: SwapOrder,
        val sell: SwapOrder,
    )

    private data class PaperEvaluation(
        val decision: DecisionResult,
        val factors: Map<ScoreFactor, BigDecimal>,
        val snapshot: CandidateSnapshot,
        val buyCosts: QuoteCosts,
        val sellCosts: QuoteCosts,
        val lastToken: JupiterTokenSnapshot,
    )

    private data class QuoteCosts(
        val paperCosts: PaperCosts,
        val totalSolLamports: Long,
    ) {
        companion object {
            val ZERO = QuoteCosts(PaperCosts(), 0)
        }
    }

    private data class RefreshedRoundTrip(
        val buy: SwapOrder,
        val sell: SwapOrder,
        val buyCosts: QuoteCosts,
        val sellCosts: QuoteCosts,
    )

    private companion object {
        const val MAXIMUM_OBSERVATIONS = 20
        const val DECIMAL_SCALE = 12
        val ZERO: BigDecimal = BigDecimal.ZERO
        val ONE: BigDecimal = BigDecimal.ONE
        val HUNDRED: BigDecimal = BigDecimal("100")
        val BASIS_POINTS: BigInteger = BigInteger.valueOf(10_000)
        val ALLOWED_QUOTE_ROUTERS = setOf("metis", "jupiterz", "dflow", "okx")
        val ALLOWED_QUOTE_MODES = setOf("ultra", "manual")
    }
}

private fun JupiterTokenSnapshot.toEntity(
    mint: String,
    proof: PaperCandidateSafetyProof?,
    buy: SwapOrder?,
    sell: SwapOrder?,
    quotePairValidated: Boolean,
    capturedAtMillis: Long,
) = TokenSnapshotEntity(
    candidateMint = mint,
    capturedAtMillis = capturedAtMillis,
    slot = null,
    liquidityLamports = proof?.liquidity?.value,
    marketCapLamports = null,
    executableBuyLamports = buy?.inAmountAtomic?.toLongOrNull(),
    executableSellLamports = sell?.outAmountAtomic?.toLongOrNull(),
    holderCount = holderCount,
    topHolderShareBps = topHoldersBps(audit.topHoldersPercentage),
    routeAvailable = quotePairValidated,
    source =
        if (quotePairValidated && proof?.quoteSemanticsValidated == true) {
            "JUPITER_TOKENS_VALIDATED_QUOTE_PAIR"
        } else {
            "JUPITER_TOKENS_UNVALIDATED_QUOTE_PAIR"
        },
)

private fun topHoldersBps(percent: BigDecimal): Int? =
    try {
        percent.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact()
    } catch (_: ArithmeticException) {
        null
    }

private fun SwapOrder.matchesBuy(request: PaperCandidateRequest): Boolean =
    inputMint == WRAPPED_SOL_MINT &&
        outputMint == request.candidate.mint &&
        inAmountAtomic == request.risk.maximumTradeLamports.toString() &&
        outAmountAtomic.toBigIntegerOrNull()?.signum() == 1 &&
        minimumOutAmountAtomic?.toBigIntegerOrNull()?.let { threshold ->
            threshold.signum() == 1 && threshold <= outAmountAtomic.toBigInteger()
        } == true

private fun SwapOrder.matchesSell(
    request: PaperCandidateRequest,
    buy: SwapOrder,
): Boolean =
    inputMint == request.candidate.mint &&
        outputMint == WRAPPED_SOL_MINT &&
        inAmountAtomic == buy.outAmountAtomic &&
        outAmountAtomic.toBigIntegerOrNull()?.signum() == 1 &&
        minimumOutAmountAtomic?.toBigIntegerOrNull()?.let { threshold ->
            threshold.signum() == 1 && threshold <= outAmountAtomic.toBigInteger()
        } == true

private fun StrategyConfigEntity.toDomainStrategy(): StrategyConfig? {
    return try {
        val root = Json.parseToJsonElement(weightsJson).jsonObject
        val weights = linkedMapOf<ScoreFactor, Int>()
        for ((name, element) in root) {
            val factor = name.toScoreFactor() ?: return null
            val weight = element.jsonPrimitive.intOrNull ?: return null
            if (weight < 0 || weights.put(factor, weight) != null) return null
        }
        StrategyConfig(
            version = version.toString(),
            weights = weights,
            minimumScore = minimumEntryScore,
        )
    } catch (_: RuntimeException) {
        null
    }
}

private fun String.toScoreFactor(): ScoreFactor? =
    when (this) {
        "buyerGrowth" -> ScoreFactor.BUYER_GROWTH
        "buySellImbalance" -> ScoreFactor.BUY_SELL_IMBALANCE
        "organicActivity" -> ScoreFactor.ORGANIC_ACTIVITY
        "holderGrowth" -> ScoreFactor.HOLDER_GROWTH
        "liquidityGrowth" -> ScoreFactor.LIQUIDITY_GROWTH
        "creatorConcentration" -> ScoreFactor.CREATOR_CONCENTRATION
        "concentration", "topHolderConcentration" -> ScoreFactor.TOP_HOLDER_CONCENTRATION
        "priceMomentum" -> ScoreFactor.PRICE_MOMENTUM
        "alreadyPumped" -> ScoreFactor.ALREADY_PUMPED
        "routeQuality" -> ScoreFactor.ROUTE_QUALITY
        "roundTripCost" -> ScoreFactor.ROUND_TRIP_COST
        "dataConsistency" -> ScoreFactor.DATA_CONSISTENCY
        "tokenAge" -> ScoreFactor.TOKEN_AGE
        "largeSellActivity" -> ScoreFactor.LARGE_SELL_ACTIVITY
        else -> ScoreFactor.entries.firstOrNull { it.name == this }
    }

private fun String.toDomainTokenProgram(): TokenProgram =
    when (this) {
        LEGACY_TOKEN_PROGRAM_ID -> TokenProgram.LEGACY
        TOKEN_2022_PROGRAM_ID -> TokenProgram.TOKEN_2022
        else -> TokenProgram.UNKNOWN
    }

private fun RiskConfigEntity.toRiskLimits() =
    RiskLimits(
        maximumSolPerTrade = Lamports.of(maximumTradeLamports),
        maximumEurPerTrade = EurAmount.of(BigDecimal.valueOf(maximumTradeEurCents, 2)),
        maximumOpenExposure = Lamports.of(maximumExposureLamports),
        maximumOpenPositions = maximumOpenPositions,
        maximumTradesPerRollingDay = maximumTradesPerDay,
        maximumDailyRealizedLoss = Lamports.of(maximumDailyLossLamports),
        maximumDailyFees = Lamports.of(maximumDailyFeesLamports),
        maximumConsecutiveLosses = maximumConsecutiveLosses,
        lossCooldown = Duration.ofMillis(lossCooldownMillis),
        failedTransactionCooldown = Duration.ofMillis(failedTransactionCooldownMillis),
        minimumWalletReserve = Lamports.of(minimumWalletReserveLamports),
        maximumSlippageBps = maximumSlippageBps,
        maximumPriorityFee = Lamports.of(maximumPriorityFeeLamports),
        maximumTransactionCost = Lamports.of(maximumTransactionCostLamports),
        maximumFeeRatioBps = maximumFeePercentBps,
        maximumHoldingTime = Duration.ofMillis(maximumHoldingMillis),
        maximumCandidateAge = Duration.ofMillis(maximumCandidateAgeMillis),
        maximumPreEntryPriceIncreasePercent = BigDecimal.valueOf(maximumPreEntryPriceIncreaseBps.toLong(), 2),
        maximumDataAge = Duration.ofMillis(minimumDataFreshnessMillis),
        minimumProviderHealth =
            ProviderHealth.entries.firstOrNull { it.name == minimumProviderHealth }
                ?: ProviderHealth.HEALTHY,
        circuitResetDelay = Duration.ofMillis(failedTransactionCooldownMillis),
    )

private fun StrategyConfigEntity.exitRulesValid(): Boolean =
    takeProfitBps >= 0 &&
        hardStopLossBps in 0..10_000 &&
        trailingActivationBps >= 0 &&
        trailingDistanceBps in 0..10_000 &&
        minimumExitSafetyScore in 0..100

private fun PaperCandidateRequest.exitRulesJson(): String =
    buildJsonObject {
        put("takeProfitBps", strategy.takeProfitBps)
        put("hardStopLossBps", strategy.hardStopLossBps)
        put("trailingActivationBps", strategy.trailingActivationBps)
        put("trailingDistanceBps", strategy.trailingDistanceBps)
        put("minimumExitSafetyScore", strategy.minimumExitSafetyScore)
        put("maximumHoldingMillis", risk.maximumHoldingMillis)
    }.toString()

private fun ProviderError.stableCode(): String =
    javaClass.simpleName
        .replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .uppercase()

private fun BigInteger.toLongExactOrNull(): Long? =
    try {
        toLongExactCompat()
    } catch (_: ArithmeticException) {
        null
    }

internal class BoundedMintHistory(
    private val maximumSize: Int,
) {
    private val mints = LinkedHashMap<String, Unit>(maximumSize, 0.75f, true)

    init {
        require(maximumSize > 0)
    }

    fun add(mint: String): Boolean {
        if (mints[mint] != null) return false
        mints[mint] = Unit
        if (mints.size > maximumSize) {
            val iterator = mints.entries.iterator()
            iterator.next()
            iterator.remove()
        }
        return true
    }
}

private const val LEGACY_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
private const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnEKS3sZDJR9L"
