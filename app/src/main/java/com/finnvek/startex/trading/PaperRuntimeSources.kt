package com.finnvek.startex.trading

import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.domain.CandidateFilterConfig
import com.finnvek.startex.domain.EurAmount
import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.domain.toLongExactCompat
import com.finnvek.startex.network.JupiterTokenSnapshot
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.network.RpcAccountInfo
import com.finnvek.startex.security.WalletAddressValidator
import com.finnvek.startex.wallet.SolanaAddressValidator
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

data class PaperSolUsdRate(
    val usdPerSol: BigDecimal,
    val observedAt: Instant,
)

fun interface PaperSolUsdRateSource {
    suspend fun latest(): PaperSolUsdRate?
}

data class PaperSolEurRate(
    val eurPerSol: BigDecimal,
    val observedAt: Instant,
)

fun interface PaperSolEurRateSource {
    suspend fun latest(): PaperSolEurRate?
}

data class PaperToken2022ExtensionProof(
    val mint: String,
    val tokenProgram: String,
    val extensionsSafe: Boolean,
    val observedAt: Instant,
)

data class PaperCandidateSafetyEvidence(
    val pumpMint: String,
    val suspiciousWalletActivity: Boolean,
    val quoteSemanticsValidated: Boolean,
    val unsupportedRouteBehavior: Boolean,
    val observedAt: Instant,
    val token2022ExtensionProof: PaperToken2022ExtensionProof? = null,
)

fun interface PaperCandidateSafetyEvidenceSource {
    suspend fun evidence(candidateMint: String): PaperCandidateSafetyEvidence?
}

class DefaultPaperCandidateSafetyProofSource(
    private val evidenceSource: PaperCandidateSafetyEvidenceSource,
    private val solUsdRates: PaperSolUsdRateSource,
    private val maximumSourceAge: Duration,
    private val addressValidator: WalletAddressValidator = SolanaAddressValidator(),
    private val clock: () -> Instant = Instant::now,
) : PaperCandidateSafetyProofSource {
    init {
        require(!maximumSourceAge.isNegative && !maximumSourceAge.isZero)
    }

    override suspend fun proof(
        mint: String,
        token: JupiterTokenSnapshot,
    ): PaperCandidateSafetyProof? {
        val now = clock()
        val evidence = evidenceSource.evidence(mint) ?: return null
        val rate = solUsdRates.latest() ?: return null
        if (!mintAgreementIsValid(mint, token, evidence)) return null
        if (!isFresh(Instant.ofEpochMilli(token.updatedAtMillis), now, maximumSourceAge)) return null
        if (!isFresh(evidence.observedAt, now, maximumSourceAge)) return null
        if (!isFresh(rate.observedAt, now, maximumSourceAge)) return null

        val extensionsSafe = tokenExtensionsSafe(token, evidence, now) ?: return null
        val liquidity = liquidityLamports(token.liquidityUsd, rate.usdPerSol) ?: return null
        return PaperCandidateSafetyProof(
            liquidity = liquidity,
            tokenExtensionsSafe = extensionsSafe,
            suspiciousWalletActivity = evidence.suspiciousWalletActivity,
            independentProvidersAgree = true,
            programAllowlisted = true,
            quoteSemanticsValidated = evidence.quoteSemanticsValidated,
            unsupportedRouteBehavior = evidence.unsupportedRouteBehavior,
            mintAddressValid = true,
        )
    }

    private fun mintAgreementIsValid(
        mint: String,
        token: JupiterTokenSnapshot,
        evidence: PaperCandidateSafetyEvidence,
    ): Boolean =
        mint == token.mint &&
            mint == evidence.pumpMint &&
            addressValidator.normalize(mint) == mint

    private fun tokenExtensionsSafe(
        token: JupiterTokenSnapshot,
        evidence: PaperCandidateSafetyEvidence,
        now: Instant,
    ): Boolean? {
        return when (token.tokenProgram) {
            LEGACY_TOKEN_PROGRAM_ID -> {
                true
            }

            TOKEN_2022_PROGRAM_ID -> {
                val proof = evidence.token2022ExtensionProof ?: return null
                if (
                    proof.mint != token.mint ||
                    proof.tokenProgram != TOKEN_2022_PROGRAM_ID ||
                    !isFresh(proof.observedAt, now, maximumSourceAge)
                ) {
                    return null
                }
                proof.extensionsSafe
            }

            else -> {
                null
            }
        }
    }
}

internal fun paperExitSafetyFacts(
    token: JupiterTokenSnapshot,
    account: RpcAccountInfo,
    nowMillis: Long,
    maximumAgeMillis: Long,
): PaperExitSafetyFacts? {
    val age = nowMillis - token.updatedAtMillis
    if (
        age !in 0..maximumAgeMillis ||
        account.executable ||
        account.owner != token.tokenProgram ||
        token.tokenProgram != LEGACY_TOKEN_PROGRAM_ID
    ) {
        return null
    }

    val momentumCollapsed = token.stats5m.sellCount > token.stats5m.buyCount
    val suspiciousCreator =
        token.audit.developerBalancePercentage
            ?.let { it > MAXIMUM_SAFE_DEVELOPER_PERCENT }
            ?: true
    val largeHolderSell = token.audit.topHoldersPercentage > MAXIMUM_SAFE_TOP_HOLDER_PERCENT
    val tokenUnsafe =
        token.audit.isSuspicious ||
            !token.audit.mintAuthorityDisabled ||
            !token.audit.freezeAuthorityDisabled
    val concentrationPenalty =
        token.audit.topHoldersPercentage
            .setScale(0, RoundingMode.CEILING)
            .intValueExact()
            .coerceIn(0, 40)
    val creatorPenalty =
        token.audit.developerBalancePercentage
            ?.setScale(0, RoundingMode.CEILING)
            ?.intValueExact()
            ?.coerceIn(0, 30)
            ?: 30

    return PaperExitSafetyFacts(
        score =
            if (tokenUnsafe) {
                0
            } else {
                (100 - concentrationPenalty - creatorPenalty - if (momentumCollapsed) 20 else 0)
                    .coerceIn(0, 100)
            },
        tokenUnsafe = tokenUnsafe,
        momentumCollapsed = momentumCollapsed,
        liquidityCollapsed = false,
        suspiciousCreatorActivity = suspiciousCreator,
        largeHolderSell = largeHolderSell,
    )
}

@Suppress("LongParameterList")
data class PaperCandidateFilterThresholds(
    val maximumCreatorHoldingPercent: BigDecimal = BigDecimal("10"),
    val maximumTopHolderPercent: BigDecimal = BigDecimal("30"),
    val minimumHolderCount: Int = 20,
    val minimumUniqueBuyers: Int = 10,
    val minimumBuySellCountRatio: BigDecimal = BigDecimal("1.1"),
    val minimumBuySellVolumeRatio: BigDecimal = BigDecimal("1.1"),
    val maximumRoundTripLossBps: Int = 1_500,
)

object PaperCandidateFilterConfigFactory {
    fun create(
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
        thresholds: PaperCandidateFilterThresholds = PaperCandidateFilterThresholds(),
    ): CandidateFilterConfig? {
        if (!filterInputsValid(strategy, risk, thresholds)) return null
        return try {
            CandidateFilterConfig(
                maximumCreatorHoldingPercent = thresholds.maximumCreatorHoldingPercent,
                maximumTopHolderPercent = thresholds.maximumTopHolderPercent,
                minimumHolderCount = thresholds.minimumHolderCount,
                minimumUniqueBuyers = thresholds.minimumUniqueBuyers,
                minimumBuySellCountRatio = thresholds.minimumBuySellCountRatio,
                minimumBuySellVolumeRatio = thresholds.minimumBuySellVolumeRatio,
                minimumLiquidity = Lamports.of(strategy.minimumLiquidityLamports),
                maximumBuyPriceImpactBps = risk.maximumSlippageBps,
                maximumRoundTripLossBps = thresholds.maximumRoundTripLossBps,
                maximumFeeRatioBps = risk.maximumFeePercentBps,
                maximumTokenAge =
                    Duration.ofMillis(
                        minOf(strategy.maximumCandidateAgeMillis, risk.maximumCandidateAgeMillis),
                    ),
                maximumPreEntryPriceIncreasePercent =
                    BigDecimal.valueOf(
                        risk.maximumPreEntryPriceIncreaseBps.toLong(),
                        BASIS_POINT_DECIMALS,
                    ),
                maximumDataAge = Duration.ofMillis(risk.minimumDataFreshnessMillis),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun filterInputsValid(
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
        thresholds: PaperCandidateFilterThresholds,
    ): Boolean =
        strategy.minimumLiquidityLamports > 0 &&
            strategy.maximumCandidateAgeMillis > 0 &&
            risk.maximumCandidateAgeMillis > 0 &&
            risk.minimumDataFreshnessMillis > 0 &&
            risk.maximumSlippageBps in 0..MAXIMUM_BASIS_POINTS &&
            risk.maximumFeePercentBps in 0..MAXIMUM_BASIS_POINTS &&
            risk.maximumPreEntryPriceIncreaseBps >= 0 &&
            thresholds.maximumCreatorHoldingPercent in BigDecimal.ZERO..MAXIMUM_PERCENT &&
            thresholds.maximumTopHolderPercent in BigDecimal.ZERO..MAXIMUM_PERCENT &&
            thresholds.minimumHolderCount > 0 &&
            thresholds.minimumUniqueBuyers > 0 &&
            thresholds.minimumBuySellCountRatio.signum() > 0 &&
            thresholds.minimumBuySellVolumeRatio.signum() > 0 &&
            thresholds.maximumRoundTripLossBps in 0..MAXIMUM_BASIS_POINTS
}

@Suppress("LongParameterList")
data class PaperRiskRuntimeSnapshot(
    val observedAt: Instant,
    val walletBalanceLamports: Long,
    val openPositions: List<PositionEntity>,
    val rollingTradeTimes: List<Instant>,
    val dailyPerformance: DailyPerformanceEntity,
    val providerHealth: List<ProviderHealthEntity>,
    val lastLossAt: Instant?,
    val lastFailedTransactionAt: Instant?,
    val circuitBreaker: CircuitBreakerState,
)

fun interface PaperRiskRuntimeSnapshotSource {
    suspend fun snapshot(
        sessionId: String,
        now: Instant,
    ): PaperRiskRuntimeSnapshot?
}

class DefaultPaperRiskFactsSource(
    private val runtimeSnapshots: PaperRiskRuntimeSnapshotSource,
    private val solEurRates: PaperSolEurRateSource,
    private val requiredProviders: Set<ProviderId> = DEFAULT_REQUIRED_PROVIDERS,
) : PaperRiskFactsSource {
    init {
        require(requiredProviders.isNotEmpty())
    }

    override suspend fun facts(
        sessionId: String,
        risk: RiskConfigEntity,
        now: Instant,
    ): PaperRiskFacts? {
        if (sessionId.isBlank() || !riskInputsValid(risk)) return null
        val runtime = runtimeSnapshots.snapshot(sessionId, now) ?: return null
        val eurRate = solEurRates.latest()
        val maximumAge = Duration.ofMillis(risk.minimumDataFreshnessMillis)
        if (!isFresh(runtime.observedAt, now, maximumAge)) return null
        if (!runtimeStateValid(runtime, now)) return null

        val openExposure = openExposure(runtime.openPositions) ?: return null
        val dailyLoss = dailyRealizedLoss(runtime.dailyPerformance.netPnlLamports) ?: return null
        val health = aggregateProviderHealth(runtime.providerHealth, now, maximumAge) ?: return null
        val approximateEur =
            eurRate
                ?.takeIf { isFresh(it.observedAt, now, maximumAge) && it.eurPerSol.signum() > 0 }
                ?.let { tradeEur(risk.maximumTradeLamports, it.eurPerSol) }
        return PaperRiskFacts(
            snapshot =
                RiskSnapshot(
                    now = now,
                    walletBalance = Lamports.of(runtime.walletBalanceLamports),
                    openExposure = openExposure,
                    openPositions = runtime.openPositions.size,
                    tradeTimes = runtime.rollingTradeTimes,
                    dailyRealizedLoss = dailyLoss,
                    dailyFees = Lamports.of(runtime.dailyPerformance.totalFeesLamports),
                    consecutiveLosses = runtime.dailyPerformance.consecutiveLosses,
                    lastLossAt = runtime.lastLossAt,
                    lastFailedTransactionAt = runtime.lastFailedTransactionAt,
                    circuitBreaker = runtime.circuitBreaker,
                ),
            approximateTradeEur = approximateEur,
            providerHealth = health,
            dailyPerformance = runtime.dailyPerformance,
        )
    }

    private fun aggregateProviderHealth(
        health: List<ProviderHealthEntity>,
        now: Instant,
        maximumAge: Duration,
    ): ProviderHealth? {
        if (health.map(ProviderHealthEntity::provider).distinct().size != health.size) return null
        val byProvider = health.associateBy(ProviderHealthEntity::provider)
        val states =
            requiredProviders.map { provider ->
                val fact = byProvider[provider.name] ?: return null
                providerHealth(fact, now, maximumAge) ?: return null
            }
        return states.minOrNull()
    }
}

private fun riskInputsValid(risk: RiskConfigEntity): Boolean =
    risk.maximumTradeLamports > 0 &&
        risk.maximumTradeEurCents > 0 &&
        risk.minimumDataFreshnessMillis > 0 &&
        ProviderHealth.entries.any { it.name == risk.minimumProviderHealth }

private fun runtimeStateValid(
    runtime: PaperRiskRuntimeSnapshot,
    now: Instant,
): Boolean {
    if (runtime.walletBalanceLamports < 0) return false
    if (!dailyPerformanceValid(runtime.dailyPerformance, now)) return false
    if (!openPositionsValid(runtime.openPositions, now)) return false
    val rollingStart = now.minus(ROLLING_DAY)
    if (runtime.rollingTradeTimes.any { it.isBefore(rollingStart) || it.isAfter(now) }) return false
    if (runtime.lastLossAt?.isAfter(now) == true || runtime.lastFailedTransactionAt?.isAfter(now) == true) return false
    val storedLastLoss = runtime.dailyPerformance.lastLossAtMillis?.let(Instant::ofEpochMilli)
    if (runtime.lastLossAt != storedLastLoss) return false
    val storedCircuitBreaker = runtime.dailyPerformance.paperCircuitBreakerState() ?: return false
    if (runtime.circuitBreaker != storedCircuitBreaker) return false
    return circuitBreakerValid(runtime.circuitBreaker, now)
}

private fun dailyPerformanceValid(
    performance: DailyPerformanceEntity,
    now: Instant,
): Boolean =
    performance.epochDay == now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() &&
        performance.mode == DailyPerformanceEntity.MODE_PAPER &&
        performance.totalFeesLamports >= 0 &&
        performance.tradeCount >= 0 &&
        performance.winCount >= 0 &&
        performance.lossCount >= 0 &&
        performance.consecutiveLosses >= 0 &&
        (performance.consecutiveLosses > 0) == (performance.lastLossAtMillis != null) &&
        performance.lastLossAtMillis?.let { it <= now.toEpochMilli() } != false &&
        performance.winCount.toLong() + performance.lossCount <= performance.tradeCount.toLong() &&
        performance.updatedAtMillis <= now.toEpochMilli()

private fun openPositionsValid(
    positions: List<PositionEntity>,
    now: Instant,
): Boolean {
    if (positions.map(PositionEntity::id).distinct().size != positions.size) return false
    return positions.all { position ->
        position.id.isNotBlank() &&
            position.status in ACTIVE_POSITION_STATES &&
            position.closedAtMillis == null &&
            position.grossInputLamports > 0 &&
            position.netInputLamports > 0 &&
            position.grossInputLamports >= position.netInputLamports &&
            position.entryCostLamports >= 0 &&
            position.openedAtMillis <= position.updatedAtMillis &&
            position.updatedAtMillis <= now.toEpochMilli()
    }
}

private fun circuitBreakerValid(
    circuitBreaker: CircuitBreakerState,
    now: Instant,
): Boolean {
    if (!circuitBreaker.active) return true
    val activatedAt = circuitBreaker.activatedAt ?: return false
    val resetAfter = circuitBreaker.resetAfter ?: return false
    return !activatedAt.isAfter(now) && !resetAfter.isBefore(activatedAt)
}

private fun providerHealth(
    fact: ProviderHealthEntity,
    now: Instant,
    maximumAge: Duration,
): ProviderHealth? {
    val updatedAt = Instant.ofEpochMilli(fact.updatedAtMillis)
    if (!isFresh(updatedAt, now, maximumAge) || fact.consecutiveFailures < 0) return null
    if (fact.latencyMillis?.let { it < 0 } == true || fact.retryAfterMillis?.let { it < 0 } == true) return null
    if (fact.lastSuccessAtMillis?.let { it > now.toEpochMilli() } == true) return null
    if (fact.lastFailureAtMillis?.let { it > now.toEpochMilli() } == true) return null
    return when (fact.state) {
        "HEALTHY" -> {
            fact.lastSuccessAtMillis
                ?.let(Instant::ofEpochMilli)
                ?.takeIf { isFresh(it, now, maximumAge) }
                ?.let { ProviderHealth.HEALTHY }
        }

        "DEGRADED", "RATE_LIMITED" -> {
            fact.lastFailureAtMillis
                ?.let(Instant::ofEpochMilli)
                ?.takeIf { isFresh(it, now, maximumAge) }
                ?.let { ProviderHealth.DEGRADED }
        }

        "UNAVAILABLE", "OFFLINE" -> {
            fact.lastFailureAtMillis
                ?.let(Instant::ofEpochMilli)
                ?.takeIf { isFresh(it, now, maximumAge) }
                ?.let { ProviderHealth.DOWN }
        }

        else -> {
            null
        }
    }
}

private fun openExposure(positions: List<PositionEntity>): Lamports? =
    try {
        Lamports.of(positions.fold(0L) { total, position -> Math.addExact(total, position.grossInputLamports) })
    } catch (_: ArithmeticException) {
        null
    }

private fun dailyRealizedLoss(netPnlLamports: Long): Lamports? =
    try {
        val loss =
            BigInteger
                .valueOf(netPnlLamports)
                .min(BigInteger.ZERO)
                .negate()
                .toLongExactCompat()
        Lamports.of(loss)
    } catch (_: ArithmeticException) {
        null
    }

internal fun liquidityLamports(
    liquidityUsd: BigDecimal,
    usdPerSol: BigDecimal,
): Lamports? {
    return try {
        if (liquidityUsd.signum() <= 0 || usdPerSol.signum() <= 0) return null
        val value =
            liquidityUsd
                .movePointRight(Lamports.SOL_DECIMALS)
                .divide(usdPerSol, 0, RoundingMode.DOWN)
                .longValueExact()
        if (value <= 0) return null
        Lamports.of(value)
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun tradeEur(
    tradeLamports: Long,
    eurPerSol: BigDecimal,
): EurAmount? {
    return try {
        if (tradeLamports <= 0 || eurPerSol.signum() <= 0) return null
        EurAmount.of(BigDecimal.valueOf(tradeLamports, Lamports.SOL_DECIMALS).multiply(eurPerSol))
    } catch (_: ArithmeticException) {
        null
    }
}

private fun isFresh(
    observedAt: Instant,
    now: Instant,
    maximumAge: Duration,
): Boolean = !observedAt.isAfter(now) && !observedAt.isBefore(now.minus(maximumAge))

private val DEFAULT_REQUIRED_PROVIDERS =
    setOf(
        ProviderId.HELIUS,
        ProviderId.PUMP_PORTAL,
        ProviderId.JUPITER,
    )
private val MAXIMUM_PERCENT = BigDecimal("100")
private val ROLLING_DAY: Duration = Duration.ofHours(24)
private val ACTIVE_POSITION_STATES =
    setOf(
        "OPEN",
        "EXIT_REQUESTED",
        "EXIT_SUBMITTING",
        "EXIT_UNCERTAIN",
        "EXIT_BLOCKED",
    )
private const val LEGACY_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
private val MAXIMUM_SAFE_DEVELOPER_PERCENT = BigDecimal("10")
private val MAXIMUM_SAFE_TOP_HOLDER_PERCENT = BigDecimal("30")
private const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnEKS3sZDJR9L"
private const val BASIS_POINT_DECIMALS = 2
private const val MAXIMUM_BASIS_POINTS = 10_000
