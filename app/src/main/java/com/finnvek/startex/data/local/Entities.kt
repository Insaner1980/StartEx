package com.finnvek.startex.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wallet_profiles",
    indices = [Index(value = ["publicAddress"], unique = true)],
)
data class WalletProfileEntity(
    @PrimaryKey val id: Long = 1,
    val publicAddress: String,
    val derivationPath: String,
    val createdAtMillis: Long,
    val backupConfirmedAtMillis: Long?,
)

@Entity(
    tableName = "wallet_secret_envelopes",
    foreignKeys = [
        ForeignKey(
            entity = WalletProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["walletProfileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WalletSecretEnvelopeEntity(
    @PrimaryKey val walletProfileId: Long,
    val encryptedSecret: ByteArray,
    val secretIv: ByteArray,
    val secretEnvelopeVersion: Int,
    val keystoreAccessMode: String,
    val updatedAtMillis: Long,
)

@Entity(tableName = "provider_credentials")
data class ProviderCredentialEntity(
    @PrimaryKey val providerId: String,
    val encryptedApiKey: ByteArray,
    val apiKeyIv: ByteArray,
    val secretEnvelopeVersion: Int,
    val keystoreAccessMode: String,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "trusted_addresses",
    foreignKeys = [
        ForeignKey(
            entity = WalletProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["walletProfileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("walletProfileId"),
        Index(value = ["address"], unique = true),
    ],
)
data class TrustedAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walletProfileId: Long,
    val label: String,
    val address: String,
    val accountKind: String?,
    val createdAtMillis: Long,
    val lastVerifiedAtMillis: Long?,
    @ColumnInfo(defaultValue = "0") val isLocked: Boolean = false,
    val firstTransferVerifiedAtMillis: Long? = null,
)

@Entity(
    tableName = "strategy_configs",
    indices = [Index("createdAtMillis")],
)
data class StrategyConfigEntity(
    @PrimaryKey val version: Int,
    val minimumEntryScore: Int,
    val minimumObservationMillis: Long,
    val maximumCandidateAgeMillis: Long,
    val minimumLiquidityLamports: Long,
    val minimumSellOutputLamports: Long,
    val requiredSnapshotCount: Int,
    val takeProfitBps: Int,
    val hardStopLossBps: Int,
    val trailingActivationBps: Int,
    val trailingDistanceBps: Int,
    val minimumExitSafetyScore: Int,
    val weightsJson: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "risk_configs",
    indices = [Index("createdAtMillis")],
)
data class RiskConfigEntity(
    @PrimaryKey val version: Int,
    val maximumTradeLamports: Long,
    val maximumTradeEurCents: Long,
    val maximumExposureLamports: Long,
    val maximumOpenPositions: Int,
    val maximumTradesPerDay: Int,
    val maximumDailyLossLamports: Long,
    val maximumDailyFeesLamports: Long,
    val maximumConsecutiveLosses: Int,
    val lossCooldownMillis: Long,
    val failedTransactionCooldownMillis: Long,
    val minimumWalletReserveLamports: Long,
    val maximumSlippageBps: Int,
    val maximumPriorityFeeLamports: Long,
    val maximumTransactionCostLamports: Long,
    val maximumFeePercentBps: Int,
    val maximumHoldingMillis: Long,
    val maximumCandidateAgeMillis: Long,
    val maximumPreEntryPriceIncreaseBps: Int,
    val minimumDataFreshnessMillis: Long,
    val minimumProviderHealth: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "bot_sessions",
    foreignKeys = [
        ForeignKey(
            entity = StrategyConfigEntity::class,
            parentColumns = ["version"],
            childColumns = ["strategyVersion"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = RiskConfigEntity::class,
            parentColumns = ["version"],
            childColumns = ["riskVersion"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("status"),
        Index("startedAtMillis"),
        Index("strategyVersion"),
        Index("riskVersion"),
    ],
)
data class BotSessionEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val status: String,
    val strategyVersion: Int,
    val riskVersion: Int,
    val startedAtMillis: Long,
    val stoppedAtMillis: Long?,
    val stopReason: String?,
    @ColumnInfo(defaultValue = "0") val lastHeartbeatAtMillis: Long = startedAtMillis,
)

@Entity(
    tableName = "token_candidates",
    indices = [
        Index("state"),
        Index("discoveredAtMillis"),
        Index("lastUpdatedAtMillis"),
    ],
)
data class TokenCandidateEntity(
    @PrimaryKey val mint: String,
    val source: String,
    val discoverySignature: String,
    val creatorAddress: String?,
    val name: String?,
    val symbol: String?,
    val metadataUri: String?,
    val tokenProgram: String?,
    val state: String,
    val score: Int?,
    val rejectionCode: String?,
    val discoveredAtMillis: Long,
    val lastUpdatedAtMillis: Long,
)

@Entity(
    tableName = "token_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = TokenCandidateEntity::class,
            parentColumns = ["mint"],
            childColumns = ["candidateMint"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("candidateMint"),
        Index("capturedAtMillis"),
    ],
)
data class TokenSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val candidateMint: String,
    val capturedAtMillis: Long,
    val slot: Long?,
    val liquidityLamports: Long?,
    val marketCapLamports: Long?,
    val executableBuyLamports: Long?,
    val executableSellLamports: Long?,
    val holderCount: Int?,
    val topHolderShareBps: Int?,
    val routeAvailable: Boolean,
    val source: String,
)

@Entity(
    tableName = "decisions",
    foreignKeys = [
        ForeignKey(
            entity = BotSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TokenCandidateEntity::class,
            parentColumns = ["mint"],
            childColumns = ["candidateMint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StrategyConfigEntity::class,
            parentColumns = ["version"],
            childColumns = ["strategyVersion"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = RiskConfigEntity::class,
            parentColumns = ["version"],
            childColumns = ["riskVersion"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("candidateMint"),
        Index("strategyVersion"),
        Index("riskVersion"),
        Index("createdAtMillis"),
    ],
)
data class DecisionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val candidateMint: String,
    val strategyVersion: Int,
    val riskVersion: Int,
    val action: String,
    val score: Int,
    val factorsJson: String,
    val rejectionCodesJson: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "positions",
    foreignKeys = [
        ForeignKey(
            entity = BotSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TokenCandidateEntity::class,
            parentColumns = ["mint"],
            childColumns = ["mint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = DecisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryDecisionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("mint"),
        Index("entryDecisionId"),
        Index("status"),
        Index("openedAtMillis"),
    ],
)
data class PositionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val mint: String,
    val entryDecisionId: String,
    val status: String,
    val tokenAmountAtomic: String,
    val grossInputLamports: Long,
    val netInputLamports: Long,
    val latestSellQuoteLamports: Long?,
    val entrySignature: String?,
    val exitSignature: String?,
    val openedAtMillis: Long,
    val updatedAtMillis: Long,
    val closedAtMillis: Long?,
    val exitReason: String?,
    val mode: String,
    val symbol: String,
    val name: String,
    val tokenDecimals: Int,
    val tokenProgram: String,
    val entryCostLamports: Long,
    val exitRulesVersion: String,
    val exitRulesJson: String,
    val latestSellQuoteAtMillis: Long?,
    val highestExecutableSellLamports: Long,
    val lowestExecutableSellLamports: Long,
    val routeAvailable: Boolean,
    val reconciliationState: String,
)

@Entity(
    tableName = "trade_intents",
    foreignKeys = [
        ForeignKey(
            entity = BotSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PositionEntity::class,
            parentColumns = ["id"],
            childColumns = ["positionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("positionId"),
        Index("mint"),
        Index("status"),
        Index("createdAtMillis"),
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class TradeIntentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val positionId: String?,
    val idempotencyKey: String,
    val side: String,
    val mint: String,
    val requestedInputAtomic: String,
    val expectedOutputAtomic: String,
    val maximumCostLamports: Long,
    val paperFeeLamports: Long,
    val providerRequestId: String?,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "blockchain_transactions",
    foreignKeys = [
        ForeignKey(
            entity = TradeIntentEntity::class,
            parentColumns = ["id"],
            childColumns = ["intentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("intentId"),
        Index("idempotencyKey"),
        Index(value = ["signature"], unique = true),
        Index(value = ["serializedHash"], unique = true),
        Index("status"),
        Index("submittedAtMillis"),
    ],
)
data class BlockchainTransactionEntity(
    @PrimaryKey val id: String,
    val intentId: String?,
    val idempotencyKey: String,
    val signature: String?,
    val serializedHash: String,
    val status: String,
    val slot: Long?,
    val actualInputAtomic: String?,
    val actualOutputAtomic: String?,
    val router: String?,
    val validatorVersion: String,
    val validatorResult: String,
    val lastValidBlockHeight: Long?,
    val expiresAtMillis: Long?,
    val submittedAtMillis: Long,
    val confirmedAtMillis: Long?,
    val failureCode: String?,
)

@Entity(
    tableName = "fee_records",
    foreignKeys = [
        ForeignKey(
            entity = BlockchainTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("transactionId"),
        Index("recordedAtMillis"),
    ],
)
data class FeeRecordEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val feeType: String,
    val mint: String?,
    val amountAtomic: String,
    val lamportsEquivalent: Long?,
    val recordedAtMillis: Long,
)

@Entity(
    tableName = "daily_performance",
    primaryKeys = ["epochDay", "mode"],
    indices = [Index("updatedAtMillis")],
)
data class DailyPerformanceEntity(
    val epochDay: Long,
    val grossPnlLamports: Long,
    val netPnlLamports: Long,
    val totalFeesLamports: Long,
    val tradeCount: Int,
    val winCount: Int,
    val lossCount: Int,
    val consecutiveLosses: Int,
    val updatedAtMillis: Long,
    val mode: String = MODE_PAPER,
    val lastLossAtMillis: Long? = null,
    val circuitBreakerReason: String? = null,
    val circuitBreakerActivatedAtMillis: Long? = null,
    val circuitBreakerResetAfterMillis: Long? = null,
) {
    companion object {
        const val MODE_PAPER = "PAPER"
        const val MODE_LIVE = "LIVE"
    }
}

@Entity(
    tableName = "provider_health",
    indices = [Index("state"), Index("updatedAtMillis")],
)
data class ProviderHealthEntity(
    @PrimaryKey val provider: String,
    val state: String,
    val consecutiveFailures: Int,
    val lastSuccessAtMillis: Long?,
    val lastFailureAtMillis: Long?,
    val latencyMillis: Long?,
    val retryAfterMillis: Long?,
    val lastFailureCode: String?,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "app_events",
    indices = [
        Index("severity"),
        Index("category"),
        Index("createdAtMillis"),
    ],
)
data class AppEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val severity: String,
    val category: String,
    val code: String,
    val redactedMessage: String?,
    val relatedId: String?,
    val createdAtMillis: Long,
)
