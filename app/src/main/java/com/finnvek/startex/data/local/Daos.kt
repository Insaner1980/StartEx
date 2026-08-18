package com.finnvek.startex.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet_profiles WHERE id = 1")
    fun observeProfile(): Flow<WalletProfileEntity?>

    @Query("SELECT * FROM wallet_profiles WHERE id = 1")
    suspend fun profile(): WalletProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: WalletProfileEntity)

    @Upsert
    suspend fun upsertSecretEnvelope(envelope: WalletSecretEnvelopeEntity)

    @Query("SELECT * FROM wallet_secret_envelopes WHERE walletProfileId = :walletProfileId")
    suspend fun secretEnvelope(walletProfileId: Long = 1): WalletSecretEnvelopeEntity?

    @Query("SELECT keystoreAccessMode FROM wallet_secret_envelopes WHERE walletProfileId = :walletProfileId")
    fun observeSecretEnvelopeAccessMode(walletProfileId: Long = 1): Flow<String?>

    @Query("DELETE FROM wallet_secret_envelopes WHERE walletProfileId = :walletProfileId")
    suspend fun deleteSecretEnvelope(walletProfileId: Long = 1): Int

    @Query("SELECT * FROM trusted_addresses ORDER BY label COLLATE NOCASE")
    fun observeTrustedAddresses(): Flow<List<TrustedAddressEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrustedAddress(address: TrustedAddressEntity): Long

    @Query("DELETE FROM trusted_addresses WHERE id = :id")
    suspend fun deleteTrustedAddress(id: Long): Int

    @Query(
        """
        UPDATE trusted_addresses
        SET isLocked = :isLocked
        WHERE id = :id
        """,
    )
    suspend fun setTrustedAddressLocked(
        id: Long,
        isLocked: Boolean,
    ): Int

    @Query(
        """
        UPDATE trusted_addresses
        SET firstTransferVerifiedAtMillis = :verifiedAtMillis
        WHERE id = :id
        """,
    )
    suspend fun markFirstTransferVerified(
        id: Long,
        verifiedAtMillis: Long,
    ): Int
}

@Dao
interface ProviderCredentialDao {
    @Upsert
    suspend fun upsert(credential: ProviderCredentialEntity)

    @Query("SELECT * FROM provider_credentials WHERE providerId = :providerId")
    suspend fun byProvider(providerId: String): ProviderCredentialEntity?

    @Query("DELETE FROM provider_credentials WHERE providerId = :providerId")
    suspend fun delete(providerId: String): Int

    @Query("SELECT providerId FROM provider_credentials ORDER BY providerId")
    fun observeConfiguredProviderIds(): Flow<List<String>>
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStrategy(config: StrategyConfigEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRisk(config: RiskConfigEntity)

    @Query("SELECT * FROM strategy_configs ORDER BY version DESC LIMIT 1")
    suspend fun latestStrategy(): StrategyConfigEntity?

    @Query("SELECT * FROM strategy_configs ORDER BY version DESC LIMIT 1")
    fun observeLatestStrategy(): Flow<StrategyConfigEntity?>

    @Query("SELECT * FROM strategy_configs WHERE version = :version")
    suspend fun strategyByVersion(version: Int): StrategyConfigEntity?

    @Query("SELECT * FROM strategy_configs ORDER BY version DESC")
    fun observeStrategies(): Flow<List<StrategyConfigEntity>>

    @Query("SELECT * FROM risk_configs ORDER BY version DESC LIMIT 1")
    suspend fun latestRisk(): RiskConfigEntity?

    @Query("SELECT * FROM risk_configs ORDER BY version DESC LIMIT 1")
    fun observeLatestRisk(): Flow<RiskConfigEntity?>

    @Query("SELECT * FROM risk_configs WHERE version = :version")
    suspend fun riskByVersion(version: Int): RiskConfigEntity?

    @Query("SELECT * FROM risk_configs ORDER BY version DESC")
    fun observeRisks(): Flow<List<RiskConfigEntity>>
}

@Dao
interface BotSessionDao {
    @Upsert
    suspend fun upsert(session: BotSessionEntity)

    @Query("SELECT * FROM bot_sessions WHERE status IN (:activeStates) ORDER BY startedAtMillis DESC LIMIT 1")
    fun observeActive(activeStates: List<String>): Flow<BotSessionEntity?>

    @Query("SELECT * FROM bot_sessions WHERE id = :id")
    suspend fun byId(id: String): BotSessionEntity?

    @Query("SELECT * FROM bot_sessions WHERE status IN (:activeStates) ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun latestByStatuses(activeStates: List<String>): BotSessionEntity?

    @Query("SELECT COUNT(*) FROM bot_sessions WHERE status IN (:statuses)")
    suspend fun countByStatuses(statuses: List<String>): Int

    @Query("SELECT * FROM bot_sessions ORDER BY startedAtMillis, id")
    suspend fun exportAll(): List<BotSessionEntity>

    @Query(
        """
        UPDATE bot_sessions
        SET status = :status,
            stoppedAtMillis = :stoppedAtMillis,
            stopReason = :stopReason,
            lastHeartbeatAtMillis = :lastHeartbeatAtMillis
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: String,
        status: String,
        stoppedAtMillis: Long?,
        stopReason: String?,
        lastHeartbeatAtMillis: Long,
    ): Int

    @Query("UPDATE bot_sessions SET lastHeartbeatAtMillis = :heartbeatAtMillis WHERE id = :id")
    suspend fun touchHeartbeat(
        id: String,
        heartbeatAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE bot_sessions
        SET status = 'NEEDS_ATTENTION',
            stoppedAtMillis = :nowMillis,
            stopReason = 'STALE_HEARTBEAT'
        WHERE status IN ('RUNNING', 'PROTECTING')
          AND (
              lastHeartbeatAtMillis < :cutoffMillis
              OR lastHeartbeatAtMillis > :nowMillis
          )
        """,
    )
    suspend fun expireStaleSessions(
        cutoffMillis: Long,
        nowMillis: Long,
    ): Int
}

@Dao
interface CandidateDao {
    @Upsert
    suspend fun upsert(candidate: TokenCandidateEntity)

    @Query("SELECT * FROM token_candidates WHERE mint = :mint")
    suspend fun byMint(mint: String): TokenCandidateEntity?

    @Query("SELECT * FROM token_candidates ORDER BY discoveredAtMillis, mint")
    suspend fun exportAll(): List<TokenCandidateEntity>

    @Query(
        """
        SELECT * FROM token_candidates
        WHERE state IN (:states)
        ORDER BY score DESC, discoveredAtMillis DESC
        LIMIT :limit
        """,
    )
    fun observeByStates(
        states: List<String>,
        limit: Int,
    ): Flow<List<TokenCandidateEntity>>

    @Query("SELECT COUNT(*) FROM token_candidates")
    suspend fun count(): Int

    @Query(
        """
        SELECT mint FROM token_candidates
        WHERE state NOT IN (:protectedStates)
          AND lastUpdatedAtMillis < :cutoffMillis
          AND mint NOT IN (SELECT mint FROM positions)
        ORDER BY lastUpdatedAtMillis ASC, mint ASC
        LIMIT :limit
        """,
    )
    suspend fun terminalMintsOlderThan(
        protectedStates: List<String>,
        cutoffMillis: Long,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT mint FROM token_candidates
        WHERE state NOT IN (:protectedStates)
          AND mint NOT IN (SELECT mint FROM positions)
        ORDER BY lastUpdatedAtMillis ASC, mint ASC
        LIMIT :limit
        """,
    )
    suspend fun oldestTerminalMints(
        protectedStates: List<String>,
        limit: Int,
    ): List<String>

    @Query("DELETE FROM token_candidates WHERE mint IN (:mints)")
    suspend fun deleteByMints(mints: List<String>): Int
}

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: TokenSnapshotEntity): Long

    @Query("SELECT * FROM token_snapshots WHERE candidateMint = :mint ORDER BY capturedAtMillis DESC LIMIT :limit")
    suspend fun recentForMint(
        mint: String,
        limit: Int,
    ): List<TokenSnapshotEntity>

    @Query("SELECT * FROM token_snapshots ORDER BY capturedAtMillis, id")
    suspend fun exportAll(): List<TokenSnapshotEntity>

    @Query("DELETE FROM token_snapshots WHERE capturedAtMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int
}

@Dao
interface DecisionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(decision: DecisionEntity)

    @Query("SELECT * FROM decisions WHERE candidateMint = :mint ORDER BY createdAtMillis DESC")
    fun observeForMint(mint: String): Flow<List<DecisionEntity>>

    @Query("SELECT * FROM decisions ORDER BY createdAtMillis, id")
    suspend fun exportAll(): List<DecisionEntity>

    @Query("DELETE FROM decisions WHERE candidateMint IN (:mints)")
    suspend fun deleteForCandidateMints(mints: List<String>): Int
}

@Dao
interface PositionDao {
    @Upsert
    suspend fun upsert(position: PositionEntity)

    @Query("SELECT * FROM positions WHERE id = :id")
    suspend fun byId(id: String): PositionEntity?

    @Query("SELECT * FROM positions WHERE status IN (:openStates) ORDER BY openedAtMillis")
    fun observeOpen(openStates: List<String>): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions WHERE status IN (:openStates) ORDER BY openedAtMillis")
    suspend fun open(openStates: List<String>): List<PositionEntity>

    @Query(
        """
        UPDATE positions
        SET status = 'EXIT_REQUESTED',
            exitReason = 'EMERGENCY_EXIT',
            updatedAtMillis = :nowMillis
        WHERE sessionId = :sessionId
          AND mode = 'PAPER'
          AND status IN (:eligibleStates)
        """,
    )
    suspend fun requestPaperEmergencyExit(
        sessionId: String,
        eligibleStates: List<String>,
        nowMillis: Long,
    ): Int

    @Query("SELECT * FROM positions ORDER BY openedAtMillis, id")
    suspend fun exportAll(): List<PositionEntity>
}

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIntent(intent: TradeIntentEntity)

    @Upsert
    suspend fun upsertIntent(intent: TradeIntentEntity)

    @Query("SELECT * FROM trade_intents WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun intentByIdempotencyKey(idempotencyKey: String): TradeIntentEntity?

    @Upsert
    suspend fun upsertTransaction(transaction: BlockchainTransactionEntity)

    @Query("SELECT * FROM blockchain_transactions WHERE signature = :signature")
    suspend fun transactionBySignature(signature: String): BlockchainTransactionEntity?

    @Query("SELECT * FROM blockchain_transactions WHERE serializedHash = :serializedHash")
    suspend fun transactionBySerializedHash(serializedHash: String): BlockchainTransactionEntity?

    @Query("SELECT * FROM blockchain_transactions WHERE idempotencyKey = :idempotencyKey ORDER BY submittedAtMillis")
    suspend fun transactionsByIdempotencyKey(idempotencyKey: String): List<BlockchainTransactionEntity>

    @Query(
        """
        SELECT * FROM blockchain_transactions
        WHERE intentId IS NULL AND status IN (:statuses)
        ORDER BY submittedAtMillis DESC
        LIMIT 1
        """,
    )
    suspend fun latestManualTransactionByStatuses(statuses: List<String>): BlockchainTransactionEntity?

    @Query(
        """
        SELECT createdAtMillis FROM trade_intents
        WHERE createdAtMillis >= :sinceMillis
          AND side = 'BUY'
          AND status NOT IN ('FAILED', 'PAPER_FAILED')
        ORDER BY createdAtMillis
        """,
    )
    suspend fun tradeTimesSince(sinceMillis: Long): List<Long>

    @Query(
        """
        SELECT updatedAtMillis FROM trade_intents
        WHERE status IN (:failedStatuses)
        ORDER BY updatedAtMillis DESC
        LIMIT 1
        """,
    )
    suspend fun latestFailedTradeAt(failedStatuses: List<String>): Long?

    @Upsert
    suspend fun upsertFees(fees: List<FeeRecordEntity>)

    @Query("SELECT * FROM trade_intents ORDER BY createdAtMillis, id")
    suspend fun exportIntents(): List<TradeIntentEntity>

    @Query("SELECT * FROM blockchain_transactions ORDER BY submittedAtMillis, id")
    suspend fun exportTransactions(): List<BlockchainTransactionEntity>

    @Query("SELECT * FROM fee_records ORDER BY recordedAtMillis, id")
    suspend fun exportFees(): List<FeeRecordEntity>

    @Query(
        """
        SELECT
            i.id AS intentId,
            i.side AS side,
            i.mint AS mint,
            COALESCE(p.mode, s.mode) AS mode,
            p.symbol AS symbol,
            p.exitReason AS exitReason,
            p.openedAtMillis AS openedAtMillis,
            p.closedAtMillis AS closedAtMillis,
            p.highestExecutableSellLamports AS highestExecutableSellLamports,
            p.lowestExecutableSellLamports AS lowestExecutableSellLamports,
            d.strategyVersion AS strategyVersion,
            d.score AS entryScore,
            d.factorsJson AS decisionFactorsJson,
            d.rejectionCodesJson AS rejectionCodesJson,
            t.signature AS transactionSignature,
            i.status AS intentStatus,
            COALESCE(t.status, i.status) AS transactionStatus,
            i.requestedInputAtomic AS requestedInputAtomic,
            i.expectedOutputAtomic AS expectedOutputAtomic,
            t.actualInputAtomic AS actualInputAtomic,
            t.actualOutputAtomic AS actualOutputAtomic,
            CASE
                WHEN t.id IS NULL THEN i.paperFeeLamports
                ELSE COALESCE(SUM(f.lamportsEquivalent), 0)
            END AS totalFeeLamports,
            i.createdAtMillis AS createdAtMillis,
            t.confirmedAtMillis AS confirmedAtMillis
        FROM trade_intents i
        LEFT JOIN bot_sessions s ON s.id = i.sessionId
        LEFT JOIN positions p ON p.id = i.positionId
        LEFT JOIN decisions d ON d.id = p.entryDecisionId
        LEFT JOIN blockchain_transactions t ON t.intentId = i.id
        LEFT JOIN fee_records f ON f.transactionId = t.id
        GROUP BY i.id, t.signature
        ORDER BY i.createdAtMillis DESC
        """,
    )
    suspend fun exportHistory(): List<TradeExportRow>

    @Query(
        """
        SELECT
            i.id AS intentId,
            i.side AS side,
            i.mint AS mint,
            COALESCE(p.mode, s.mode) AS mode,
            p.symbol AS symbol,
            p.exitReason AS exitReason,
            p.openedAtMillis AS openedAtMillis,
            p.closedAtMillis AS closedAtMillis,
            p.highestExecutableSellLamports AS highestExecutableSellLamports,
            p.lowestExecutableSellLamports AS lowestExecutableSellLamports,
            d.strategyVersion AS strategyVersion,
            d.score AS entryScore,
            d.factorsJson AS decisionFactorsJson,
            d.rejectionCodesJson AS rejectionCodesJson,
            t.signature AS transactionSignature,
            i.status AS intentStatus,
            COALESCE(t.status, i.status) AS transactionStatus,
            i.requestedInputAtomic AS requestedInputAtomic,
            i.expectedOutputAtomic AS expectedOutputAtomic,
            t.actualInputAtomic AS actualInputAtomic,
            t.actualOutputAtomic AS actualOutputAtomic,
            CASE
                WHEN t.id IS NULL THEN i.paperFeeLamports
                ELSE COALESCE(SUM(f.lamportsEquivalent), 0)
            END AS totalFeeLamports,
            i.createdAtMillis AS createdAtMillis,
            t.confirmedAtMillis AS confirmedAtMillis
        FROM trade_intents i
        LEFT JOIN bot_sessions s ON s.id = i.sessionId
        LEFT JOIN positions p ON p.id = i.positionId
        LEFT JOIN decisions d ON d.id = p.entryDecisionId
        LEFT JOIN blockchain_transactions t ON t.intentId = i.id
        LEFT JOIN fee_records f ON f.transactionId = t.id
        WHERE i.status = 'PAPER_FILLED'
           OR t.status IN ('CONFIRMED', 'FINALIZED', 'RECONCILED')
        GROUP BY i.id, t.signature
        ORDER BY i.createdAtMillis DESC
        LIMIT :limit
        """,
    )
    fun observeHistory(limit: Int): Flow<List<TradeExportRow>>
}

@Serializable
data class TradeExportRow(
    val intentId: String,
    val side: String,
    val mint: String,
    val mode: String,
    val symbol: String?,
    val exitReason: String?,
    val openedAtMillis: Long?,
    val closedAtMillis: Long?,
    val highestExecutableSellLamports: Long?,
    val lowestExecutableSellLamports: Long?,
    val strategyVersion: Int?,
    val entryScore: Int?,
    val decisionFactorsJson: String?,
    val rejectionCodesJson: String?,
    val transactionSignature: String?,
    val transactionStatus: String?,
    val requestedInputAtomic: String,
    val expectedOutputAtomic: String,
    val actualInputAtomic: String?,
    val actualOutputAtomic: String?,
    val totalFeeLamports: Long,
    val createdAtMillis: Long,
    val confirmedAtMillis: Long?,
    val intentStatus: String = "",
)

@Dao
interface DailyPerformanceDao {
    @Upsert
    suspend fun upsert(performance: DailyPerformanceEntity)

    @Query("SELECT * FROM daily_performance WHERE epochDay = :epochDay AND mode = :mode")
    suspend fun byDay(
        epochDay: Long,
        mode: String,
    ): DailyPerformanceEntity?

    @Query("SELECT * FROM daily_performance WHERE mode = :mode ORDER BY epochDay DESC LIMIT 1")
    suspend fun latest(mode: String): DailyPerformanceEntity?

    @Query("SELECT * FROM daily_performance WHERE mode = :mode ORDER BY epochDay DESC")
    fun observeByMode(mode: String): Flow<List<DailyPerformanceEntity>>

    @Query("SELECT * FROM daily_performance ORDER BY epochDay DESC, mode ASC")
    fun observeAll(): Flow<List<DailyPerformanceEntity>>
}

@Dao
interface ProviderHealthDao {
    @Upsert
    suspend fun upsert(health: ProviderHealthEntity)

    @Query("SELECT * FROM provider_health ORDER BY provider")
    fun observeAll(): Flow<List<ProviderHealthEntity>>

    @Query("SELECT * FROM provider_health ORDER BY provider")
    suspend fun all(): List<ProviderHealthEntity>

    @Query("SELECT * FROM provider_health WHERE provider = :provider")
    suspend fun byProvider(provider: String): ProviderHealthEntity?
}

@Dao
interface AppEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AppEventEntity): Long

    @Query("SELECT * FROM app_events ORDER BY createdAtMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AppEventEntity>>

    @Query("SELECT COUNT(*) FROM app_events")
    suspend fun count(): Int

    @Query(
        """
        DELETE FROM app_events
        WHERE id NOT IN (
            SELECT id FROM app_events ORDER BY createdAtMillis DESC, id DESC LIMIT :maximumRows
        )
        """,
    )
    suspend fun keepNewest(maximumRows: Int): Int
}
