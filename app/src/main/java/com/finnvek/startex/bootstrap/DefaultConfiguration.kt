package com.finnvek.startex.bootstrap

import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity

object DefaultConfiguration {
    fun strategy(createdAtMillis: Long) =
        StrategyConfigEntity(
            version = 1,
            minimumEntryScore = 75,
            minimumObservationMillis = 60_000,
            maximumCandidateAgeMillis = 5 * 60_000,
            minimumLiquidityLamports = 5_000_000_000,
            minimumSellOutputLamports = 5_000_000,
            requiredSnapshotCount = 3,
            takeProfitBps = 2_500,
            hardStopLossBps = 1_500,
            trailingActivationBps = 1_500,
            trailingDistanceBps = 800,
            minimumExitSafetyScore = 50,
            weightsJson = DEFAULT_WEIGHTS,
            createdAtMillis = createdAtMillis,
        )

    fun risk(createdAtMillis: Long) =
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
            lossCooldownMillis = 60 * 60_000,
            failedTransactionCooldownMillis = 15 * 60_000,
            minimumWalletReserveLamports = 5_000_000,
            maximumSlippageBps = 300,
            maximumPriorityFeeLamports = 500_000,
            maximumTransactionCostLamports = 1_000_000,
            maximumFeePercentBps = 2_000,
            maximumHoldingMillis = 10 * 60_000,
            maximumCandidateAgeMillis = 5 * 60_000,
            maximumPreEntryPriceIncreaseBps = 2_000,
            minimumDataFreshnessMillis = 15_000,
            minimumProviderHealth = "HEALTHY",
            createdAtMillis = createdAtMillis,
        )

    private const val DEFAULT_WEIGHTS =
        "{\"buyerGrowth\":15,\"organicActivity\":15,\"holderGrowth\":10," +
            "\"liquidityGrowth\":15,\"concentration\":15,\"routeQuality\":15," +
            "\"roundTripCost\":10,\"dataConsistency\":5}"
}
