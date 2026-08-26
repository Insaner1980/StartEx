package com.finnvek.startex.data

import androidx.room.withTransaction
import com.finnvek.startex.data.local.AppEventEntity
import com.finnvek.startex.data.local.BlockchainTransactionEntity
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.DecisionEntity
import com.finnvek.startex.data.local.FeeRecordEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderCredentialEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StartExDatabase
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TokenSnapshotEntity
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.data.local.TradeIntentEntity
import com.finnvek.startex.data.local.TrustedAddressEntity
import com.finnvek.startex.data.local.WalletProfileEntity
import com.finnvek.startex.data.local.WalletSecretEnvelopeEntity
import com.finnvek.startex.trading.CircuitBreakerState
import com.finnvek.startex.trading.PaperPerformanceDelta
import com.finnvek.startex.trading.applyPaperCircuitBreaker
import com.finnvek.startex.trading.applyPaperPerformanceDelta
import com.finnvek.startex.trading.paperCircuitBreakerState
import com.finnvek.startex.trading.resetPaperCircuitBreaker
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneOffset

data class CleanupResult(
    val deletedSnapshots: Int,
    val deletedCandidates: Int,
    val deletedEvents: Int,
    val remainingCandidates: Int,
    val remainingEvents: Int,
)

enum class CandidateInsertResult {
    INSERTED,
    ALREADY_EXISTS,
    CAP_REACHED,
}

class StartExRepository(
    private val database: StartExDatabase,
) {
    fun observeWalletProfile(): Flow<WalletProfileEntity?> = database.walletDao().observeProfile()

    fun observeWalletSecretEnvelopeAccessMode(): Flow<String?> = database.walletDao().observeSecretEnvelopeAccessMode()

    fun observeTrustedAddresses(): Flow<List<TrustedAddressEntity>> = database.walletDao().observeTrustedAddresses()

    fun observeActiveSession(activeStates: List<String>): Flow<BotSessionEntity?> = database.botSessionDao().observeActive(activeStates)

    fun observeCandidates(
        states: List<String>,
        limit: Int = DEFAULT_CANDIDATE_QUERY_LIMIT,
    ): Flow<List<TokenCandidateEntity>> {
        require(limit in 1..MAXIMUM_CANDIDATE_QUERY_LIMIT)
        return database.candidateDao().observeByStates(states, limit)
    }

    fun observeOpenPositions(openStates: List<String>): Flow<List<PositionEntity>> = database.positionDao().observeOpen(openStates)

    fun observeDailyPerformance(): Flow<List<DailyPerformanceEntity>> = database.dailyPerformanceDao().observeAll()

    fun observeDailyPerformance(mode: String): Flow<List<DailyPerformanceEntity>> = database.dailyPerformanceDao().observeByMode(mode)

    fun observeTradeHistory(limit: Int = DEFAULT_TRADE_HISTORY_LIMIT): Flow<List<TradeExportRow>> {
        require(limit in 1..MAXIMUM_TRADE_HISTORY_LIMIT)
        return database.ledgerDao().observeHistory(limit)
    }

    fun observeProviderHealth(): Flow<List<ProviderHealthEntity>> = database.providerHealthDao().observeAll()

    fun observeConfiguredProviderIds(): Flow<List<String>> = database.providerCredentialDao().observeConfiguredProviderIds()

    fun observeLatestStrategy(): Flow<StrategyConfigEntity?> = database.configDao().observeLatestStrategy()

    fun observeLatestRisk(): Flow<RiskConfigEntity?> = database.configDao().observeLatestRisk()

    fun observeStrategies(): Flow<List<StrategyConfigEntity>> = database.configDao().observeStrategies()

    fun observeRisks(): Flow<List<RiskConfigEntity>> = database.configDao().observeRisks()

    fun observeRecentEvents(limit: Int): Flow<List<AppEventEntity>> {
        require(limit in 1..10_000)
        return database.appEventDao().observeRecent(limit)
    }

    suspend fun latestConfiguration(): Pair<StrategyConfigEntity?, RiskConfigEntity?> =
        database.withTransaction {
            database.configDao().latestStrategy() to database.configDao().latestRisk()
        }

    suspend fun saveWallet(
        profile: WalletProfileEntity,
        envelope: WalletSecretEnvelopeEntity,
    ) {
        require(profile.id == SINGLE_WALLET_PROFILE_ID)
        require(envelope.walletProfileId == SINGLE_WALLET_PROFILE_ID)
        database.withTransaction {
            val existing = database.walletDao().profile()
            require(existing == null || existing.publicAddress == profile.publicAddress) {
                "Wallet replacement is not supported"
            }
            database.walletDao().upsertProfile(profile)
            database.walletDao().upsertSecretEnvelope(envelope)
        }
    }

    suspend fun walletSecretEnvelope(walletProfileId: Long = 1): WalletSecretEnvelopeEntity? =
        database.walletDao().secretEnvelope(walletProfileId)

    suspend fun walletProfile(): WalletProfileEntity? = database.walletDao().profile()

    suspend fun deleteWalletSecretEnvelope(walletProfileId: Long = 1): Int = database.walletDao().deleteSecretEnvelope(walletProfileId)

    suspend fun saveProviderCredential(credential: ProviderCredentialEntity) {
        database.withTransaction {
            database.providerCredentialDao().upsert(credential)
            database.providerHealthDao().delete(credential.providerId)
        }
    }

    suspend fun providerCredential(providerId: String): ProviderCredentialEntity? = database.providerCredentialDao().byProvider(providerId)

    suspend fun deleteProviderCredential(providerId: String): Int =
        database.withTransaction {
            val deleted = database.providerCredentialDao().delete(providerId)
            database.providerHealthDao().delete(providerId)
            deleted
        }

    suspend fun addTrustedAddress(address: TrustedAddressEntity): Long = database.walletDao().insertTrustedAddress(address)

    suspend fun deleteTrustedAddress(id: Long): Boolean = database.walletDao().deleteTrustedAddress(id) == 1

    suspend fun setTrustedAddressLocked(
        id: Long,
        isLocked: Boolean,
    ): Boolean = database.walletDao().setTrustedAddressLocked(id, isLocked) == 1

    suspend fun markFirstTransferVerified(
        id: Long,
        verifiedAtMillis: Long,
    ): Boolean = database.walletDao().markFirstTransferVerified(id, verifiedAtMillis) == 1

    suspend fun saveConfig(
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
    ): Boolean =
        database.withTransaction {
            if (database.botSessionDao().countByStatuses(CONFIGURATION_LOCKING_SESSION_STATES) > 0) {
                return@withTransaction false
            }
            database.configDao().insertStrategy(strategy)
            database.configDao().insertRisk(risk)
            true
        }

    suspend fun saveSession(session: BotSessionEntity) = database.botSessionDao().upsert(session)

    suspend fun stopLatestSession(
        activeStates: List<String>,
        stoppedAtMillis: Long,
        stopReason: String,
    ): BotSessionEntity? =
        database.withTransaction {
            val session = database.botSessionDao().latestByStatuses(activeStates) ?: return@withTransaction null
            val updated =
                database.botSessionDao().updateStatus(
                    id = session.id,
                    status = "STOPPED",
                    stoppedAtMillis = stoppedAtMillis,
                    stopReason = stopReason,
                    lastHeartbeatAtMillis = stoppedAtMillis,
                )
            val stopped =
                session.copy(
                    status = "STOPPED",
                    stoppedAtMillis = stoppedAtMillis,
                    stopReason = stopReason,
                    lastHeartbeatAtMillis = stoppedAtMillis,
                )
            stopped.takeIf { updated == 1 }
        }

    suspend fun stopSession(
        id: String,
        activeStates: List<String>,
        stoppedAtMillis: Long,
        stopReason: String,
    ): BotSessionEntity? =
        database.withTransaction {
            val session = database.botSessionDao().byId(id) ?: return@withTransaction null
            if (session.status !in activeStates) return@withTransaction null
            val stopped =
                session.copy(
                    status = "STOPPED",
                    stoppedAtMillis = stoppedAtMillis,
                    stopReason = stopReason,
                    lastHeartbeatAtMillis = stoppedAtMillis,
                )
            val updated =
                database.botSessionDao().updateStatus(
                    id = id,
                    status = stopped.status,
                    stoppedAtMillis = stopped.stoppedAtMillis,
                    stopReason = stopped.stopReason,
                    lastHeartbeatAtMillis = stopped.lastHeartbeatAtMillis,
                )
            stopped.takeIf { updated == 1 }
        }

    suspend fun updateSessionStatus(
        id: String,
        status: String,
        stoppedAtMillis: Long?,
        stopReason: String?,
        lastHeartbeatAtMillis: Long,
    ): Boolean =
        database.botSessionDao().updateStatus(
            id = id,
            status = status,
            stoppedAtMillis = stoppedAtMillis,
            stopReason = stopReason,
            lastHeartbeatAtMillis = lastHeartbeatAtMillis,
        ) == 1

    suspend fun requestStopAfterClose(
        sessionId: String,
        nowMillis: Long,
    ): Boolean = database.botSessionDao().requestStopAfterClose(sessionId, nowMillis) == 1

    suspend fun touchSessionHeartbeat(
        id: String,
        heartbeatAtMillis: Long,
    ): Boolean = database.botSessionDao().touchHeartbeat(id, heartbeatAtMillis) == 1

    suspend fun expireStaleSessions(
        cutoffMillis: Long,
        nowMillis: Long,
    ): Int {
        require(cutoffMillis in 0..nowMillis)
        return database.botSessionDao().expireStaleSessions(cutoffMillis, nowMillis)
    }

    suspend fun saveCandidate(candidate: TokenCandidateEntity) = database.candidateDao().upsert(candidate)

    suspend fun insertCandidateBounded(
        candidate: TokenCandidateEntity,
        maximumCandidates: Int,
        protectedStates: List<String>,
    ): CandidateInsertResult {
        require(maximumCandidates > 0)
        require(protectedStates.isNotEmpty())
        return database.withTransaction {
            if (database.candidateDao().byMint(candidate.mint) != null) {
                return@withTransaction CandidateInsertResult.ALREADY_EXISTS
            }
            trimTerminalCandidateHistory(protectedStates, maximumCandidates - 1)
            if (database.candidateDao().count() >= maximumCandidates) {
                CandidateInsertResult.CAP_REACHED
            } else {
                database.candidateDao().upsert(candidate)
                CandidateInsertResult.INSERTED
            }
        }
    }

    suspend fun addSnapshot(snapshot: TokenSnapshotEntity): Long = database.snapshotDao().insert(snapshot)

    suspend fun recordDecision(
        candidate: TokenCandidateEntity,
        decision: DecisionEntity,
    ) {
        require(decision.candidateMint == candidate.mint)
        database.withTransaction {
            database.candidateDao().upsert(candidate)
            database.decisionDao().insert(decision)
        }
    }

    suspend fun savePosition(position: PositionEntity) = database.positionDao().upsert(position)

    suspend fun requestPaperEmergencyExit(
        sessionId: String,
        nowMillis: Long,
    ): Int =
        database.withTransaction {
            val session = database.botSessionDao().byId(sessionId) ?: return@withTransaction 0
            if (session.status !in CONFIGURATION_LOCKING_SESSION_STATES) return@withTransaction 0
            val updatedPositions =
                database.positionDao().requestPaperEmergencyExit(
                    sessionId = sessionId,
                    eligibleStates = PAPER_EXIT_ELIGIBLE_POSITION_STATES,
                    nowMillis = nowMillis,
                )
            if (updatedPositions == 0) return@withTransaction 0
            check(
                database.botSessionDao().updateStatus(
                    id = sessionId,
                    status = "PROTECTING",
                    stoppedAtMillis = null,
                    stopReason = "EMERGENCY_EXIT_REQUESTED",
                    lastHeartbeatAtMillis = nowMillis,
                ) == 1,
            )
            updatedPositions
        }

    suspend fun position(id: String): PositionEntity? = database.positionDao().byId(id)

    suspend fun openPositions(openStates: List<String>): List<PositionEntity> = database.positionDao().open(openStates)

    suspend fun dailyPerformance(
        epochDay: Long,
        mode: String,
    ): DailyPerformanceEntity? = database.dailyPerformanceDao().byDay(epochDay, mode)

    suspend fun latestDailyPerformance(mode: String): DailyPerformanceEntity? = database.dailyPerformanceDao().latest(mode)

    suspend fun providerHealth(): List<ProviderHealthEntity> = database.providerHealthDao().all()

    suspend fun tradeTimesSince(sinceMillis: Long): List<Long> = database.ledgerDao().tradeTimesSince(sinceMillis)

    suspend fun latestFailedTradeAt(failedStatuses: List<String>): Long? = database.ledgerDao().latestFailedTradeAt(failedStatuses)

    suspend fun updateProviderHealth(
        provider: String,
        update: (ProviderHealthEntity?) -> ProviderHealthEntity,
    ): ProviderHealthEntity =
        database.withTransaction {
            val updated = update(database.providerHealthDao().byProvider(provider))
            require(updated.provider == provider)
            database.providerHealthDao().upsert(updated)
            updated
        }

    suspend fun addEventBounded(
        event: AppEventEntity,
        maximumEvents: Int,
    ): Long {
        require(maximumEvents > 0)
        return database.withTransaction {
            val id = database.appEventDao().insert(event)
            database.appEventDao().keepNewest(maximumEvents)
            id
        }
    }

    suspend fun saveDailyPerformance(performance: DailyPerformanceEntity) = database.dailyPerformanceDao().upsert(performance)

    suspend fun savePaperCircuitBreaker(
        breaker: CircuitBreakerState,
        now: Instant,
    ) {
        require(breaker.active)
        val day = now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        database.withTransaction {
            val current = database.dailyPerformanceDao().byDay(day, DailyPerformanceEntity.MODE_PAPER)
            val previous =
                if (current == null) {
                    database.dailyPerformanceDao().latest(DailyPerformanceEntity.MODE_PAPER)
                } else {
                    null
                }
            check(previous == null || previous.epochDay < day)
            database.dailyPerformanceDao().upsert(
                applyPaperCircuitBreaker(
                    current = current,
                    epochDay = day,
                    breaker = breaker,
                    nowMillis = now.toEpochMilli(),
                    previous = previous,
                ),
            )
        }
    }

    suspend fun resetPaperCircuitBreaker(
        now: Instant,
        authenticated: Boolean,
    ): Boolean =
        database.withTransaction {
            val current =
                database.dailyPerformanceDao().latest(DailyPerformanceEntity.MODE_PAPER)
                    ?: return@withTransaction false
            if (current.paperCircuitBreakerState()?.active != true) return@withTransaction false
            val reset =
                current.resetPaperCircuitBreaker(now, authenticated)
                    ?: return@withTransaction false
            database.dailyPerformanceDao().upsert(reset)
            true
        }

    suspend fun createIntentOnce(intent: TradeIntentEntity): TradeIntentEntity =
        database.withTransaction {
            val existing = database.ledgerDao().intentByIdempotencyKey(intent.idempotencyKey)
            if (existing != null) return@withTransaction existing
            database.ledgerDao().insertIntent(intent)
            intent
        }

    suspend fun savePaperExitAttempt(intent: TradeIntentEntity) {
        require(intent.side == "SELL")
        require(intent.status in PAPER_EXIT_ATTEMPT_STATUSES)
        database.ledgerDao().upsertIntent(intent)
    }

    suspend fun saveBlockchainTransaction(transaction: BlockchainTransactionEntity) = database.ledgerDao().upsertTransaction(transaction)

    suspend fun latestManualTransactionByStatuses(statuses: List<String>): BlockchainTransactionEntity? =
        database.ledgerDao().latestManualTransactionByStatuses(statuses)

    suspend fun recordPaperFill(
        candidate: TokenCandidateEntity,
        decision: DecisionEntity,
        intent: TradeIntentEntity,
        position: PositionEntity,
        performanceEpochDay: Long,
        performanceDelta: PaperPerformanceDelta,
        performanceAtMillis: Long,
    ) {
        require(decision.candidateMint == candidate.mint)
        require(intent.sessionId == decision.sessionId)
        require(intent.positionId == position.id)
        require(position.entryDecisionId == decision.id)
        require(position.mode == "PAPER")
        require(intent.status == "PAPER_FILLED")
        database.withTransaction {
            val performance =
                updatedPaperPerformance(
                    epochDay = performanceEpochDay,
                    delta = performanceDelta,
                    nowMillis = performanceAtMillis,
                )
            database.candidateDao().upsert(candidate)
            database.decisionDao().insert(decision)
            database.positionDao().upsert(position)
            database.ledgerDao().insertIntent(intent)
            database.dailyPerformanceDao().upsert(performance)
        }
    }

    suspend fun recordPaperExit(
        position: PositionEntity,
        intent: TradeIntentEntity,
        performanceEpochDay: Long,
        performanceDelta: PaperPerformanceDelta,
        performanceAtMillis: Long,
    ) {
        require(position.mode == "PAPER")
        require(position.status == "CLOSED")
        requireNotNull(position.closedAtMillis)
        require(intent.positionId == position.id)
        require(intent.sessionId == position.sessionId)
        require(intent.side == "SELL")
        require(intent.status == "PAPER_FILLED")
        database.withTransaction {
            val performance =
                updatedPaperPerformance(
                    epochDay = performanceEpochDay,
                    delta = performanceDelta,
                    nowMillis = performanceAtMillis,
                )
            database.positionDao().upsert(position)
            database.ledgerDao().insertIntent(intent)
            database.dailyPerformanceDao().upsert(performance)
        }
    }

    private suspend fun updatedPaperPerformance(
        epochDay: Long,
        delta: PaperPerformanceDelta,
        nowMillis: Long,
    ): DailyPerformanceEntity {
        val current = database.dailyPerformanceDao().byDay(epochDay, DailyPerformanceEntity.MODE_PAPER)
        val previous =
            if (current == null) {
                database.dailyPerformanceDao().latest(DailyPerformanceEntity.MODE_PAPER)
            } else {
                null
            }
        check(previous == null || previous.epochDay < epochDay)
        return applyPaperPerformanceDelta(
            current = current,
            epochDay = epochDay,
            delta = delta,
            nowMillis = nowMillis,
            previous = previous,
        )
    }

    suspend fun recordSettlement(
        intent: TradeIntentEntity,
        transaction: BlockchainTransactionEntity,
        fees: List<FeeRecordEntity>,
        position: PositionEntity,
        performance: DailyPerformanceEntity? = null,
    ) {
        require(transaction.intentId == intent.id)
        require(fees.all { it.transactionId == transaction.id })
        database.withTransaction {
            database.ledgerDao().upsertIntent(intent)
            database.ledgerDao().upsertTransaction(transaction)
            if (fees.isNotEmpty()) database.ledgerDao().upsertFees(fees)
            database.positionDao().upsert(position)
            performance?.let { database.dailyPerformanceDao().upsert(it) }
        }
    }

    suspend fun cleanup(
        policy: RetentionPolicy,
        nowMillis: Long,
        activeCandidateStates: List<String>,
    ): CleanupResult =
        database.withTransaction {
            val cutoff = policy.snapshotCutoffMillis(nowMillis)
            val deletedSnapshots = database.snapshotDao().deleteOlderThan(cutoff)
            val deletedOldCandidates = deleteOldTerminalCandidateHistory(activeCandidateStates, cutoff)
            val deletedOverflowCandidates =
                trimTerminalCandidateHistory(
                    activeCandidateStates,
                    policy.maximumCandidates - 1,
                )
            val deletedEvents = database.appEventDao().keepNewest(policy.maximumEvents)
            CleanupResult(
                deletedSnapshots = deletedSnapshots,
                deletedCandidates = deletedOldCandidates + deletedOverflowCandidates,
                deletedEvents = deletedEvents,
                remainingCandidates = database.candidateDao().count(),
                remainingEvents = database.appEventDao().count(),
            )
        }

    suspend fun exportHistoryJson(): String = HistoryExporter.toJson(loadAnalysisExport())

    suspend fun exportHistoryCsv(): String = HistoryExporter.toCsv(loadAnalysisExport())

    private suspend fun loadAnalysisExport(): AnalysisExport =
        database.withTransaction {
            AnalysisExport.fromEntities(
                AnalysisExportEntities(
                    sessions = database.botSessionDao().exportAll(),
                    candidates = database.candidateDao().exportAll(),
                    snapshots = database.snapshotDao().exportAll(),
                    decisions = database.decisionDao().exportAll(),
                    positions = database.positionDao().exportAll(),
                    tradeIntents = database.ledgerDao().exportIntents(),
                    transactions = database.ledgerDao().exportTransactions(),
                    fees = database.ledgerDao().exportFees(),
                ),
            )
        }

    private suspend fun trimTerminalCandidateHistory(
        protectedStates: List<String>,
        maximumRows: Int,
    ): Int {
        var deleted = 0
        while (true) {
            val overflow = database.candidateDao().count() - maximumRows
            if (overflow <= 0) return deleted
            val mints =
                database.candidateDao().oldestTerminalMints(
                    protectedStates,
                    minOf(overflow, CANDIDATE_DELETE_BATCH_SIZE),
                )
            val removed = deleteCandidateHistory(mints)
            deleted += removed
            if (removed == 0) return deleted
        }
    }

    private suspend fun deleteOldTerminalCandidateHistory(
        protectedStates: List<String>,
        cutoffMillis: Long,
    ): Int {
        var deleted = 0
        while (true) {
            val mints =
                database.candidateDao().terminalMintsOlderThan(
                    protectedStates,
                    cutoffMillis,
                    CANDIDATE_DELETE_BATCH_SIZE,
                )
            val removed = deleteCandidateHistory(mints)
            deleted += removed
            if (removed == 0) return deleted
        }
    }

    private suspend fun deleteCandidateHistory(mints: List<String>): Int {
        if (mints.isEmpty()) return 0
        database.decisionDao().deleteForCandidateMints(mints)
        return database.candidateDao().deleteByMints(mints)
    }

    private companion object {
        const val CANDIDATE_DELETE_BATCH_SIZE = 500
        const val DEFAULT_CANDIDATE_QUERY_LIMIT = 250
        const val MAXIMUM_CANDIDATE_QUERY_LIMIT = 1_000
        const val DEFAULT_TRADE_HISTORY_LIMIT = 250
        const val MAXIMUM_TRADE_HISTORY_LIMIT = 1_000
        const val SINGLE_WALLET_PROFILE_ID = 1L
        val CONFIGURATION_LOCKING_SESSION_STATES =
            listOf("RUNNING", "PAUSED", "PROTECTING", "NEEDS_ATTENTION")
        val PAPER_EXIT_ATTEMPT_STATUSES =
            setOf(
                "PAPER_QUOTE_READY",
                "PAPER_ROUTE_UNAVAILABLE",
                "PAPER_COST_BLOCKED",
            )
        val PAPER_EXIT_ELIGIBLE_POSITION_STATES =
            listOf("OPEN", "EXIT_REQUESTED", "EXIT_BLOCKED")
    }
}
