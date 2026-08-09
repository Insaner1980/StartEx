package com.finnvek.startex.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.CandidateInsertResult
import com.finnvek.startex.data.StartExRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class StartExDatabaseTest {
    private lateinit var database: StartExDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, StartExDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun trustedAddressRequiresAnExistingWalletProfile() {
        runBlocking {
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                runBlocking {
                    database.walletDao().insertTrustedAddress(
                        TrustedAddressEntity(
                            walletProfileId = 1,
                            label = "Personal wallet",
                            address = "Wallet11111111111111111111111111111111111111",
                            accountKind = "SYSTEM_ACCOUNT",
                            createdAtMillis = 1,
                            lastVerifiedAtMillis = 1,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun eventCleanupKeepsOnlyTheNewestConfiguredRows() =
        runBlocking {
            val repository = StartExRepository(database)
            repeat(5) { index ->
                repository.addEventBounded(
                    AppEventEntity(
                        severity = "INFO",
                        category = "TEST",
                        code = "EVENT_$index",
                        redactedMessage = null,
                        relatedId = null,
                        createdAtMillis = index.toLong(),
                    ),
                    maximumEvents = 2,
                )
            }

            assertEquals(
                listOf("EVENT_4", "EVENT_3"),
                database
                    .appEventDao()
                    .observeRecent(10)
                    .first()
                    .map { it.code },
            )
        }

    @Test
    fun candidateObservationIsBoundedAtTheDatabaseQuery() =
        runBlocking {
            repeat(5) { index ->
                database.candidateDao().upsert(candidate("mint-$index", "REJECTED", index.toLong()))
            }

            val observed = StartExRepository(database).observeCandidates(listOf("REJECTED"), limit = 2).first()

            assertEquals(listOf("mint-4", "mint-3"), observed.map { it.mint })
        }

    @Test
    fun candidateIngestFailsClosedWhenProtectedRowsFillTheCap() =
        runBlocking {
            val repository = StartExRepository(database)
            val protectedStates = listOf("OBSERVING")

            assertEquals(
                CandidateInsertResult.INSERTED,
                repository.insertCandidateBounded(candidate("mint-a", "OBSERVING", 1), 2, protectedStates),
            )
            assertEquals(
                CandidateInsertResult.INSERTED,
                repository.insertCandidateBounded(candidate("mint-b", "OBSERVING", 2), 2, protectedStates),
            )
            assertEquals(
                CandidateInsertResult.CAP_REACHED,
                repository.insertCandidateBounded(candidate("mint-c", "OBSERVING", 3), 2, protectedStates),
            )
            assertEquals(null, database.candidateDao().byMint("mint-c"))
        }

    @Test
    fun candidateIngestUsesTotalTableCapWhenActiveAndTerminalRowsAreMixed() =
        runBlocking {
            val repository = StartExRepository(database)
            val protectedStates = listOf("OBSERVING")
            database.candidateDao().upsert(candidate("mint-active", "OBSERVING", 1))
            database.candidateDao().upsert(candidate("mint-old", "REJECTED", 2))

            val result =
                repository.insertCandidateBounded(
                    candidate("mint-new", "DISCOVERED", 3),
                    maximumCandidates = 2,
                    protectedStates = protectedStates,
                )

            assertEquals(CandidateInsertResult.INSERTED, result)
            assertEquals("mint-active", database.candidateDao().byMint("mint-active")?.mint)
            assertEquals(null, database.candidateDao().byMint("mint-old"))
            assertEquals("mint-new", database.candidateDao().byMint("mint-new")?.mint)
            assertEquals(2, database.candidateDao().count())
        }

    @Test
    fun candidateIngestEvictsOldTerminalHistoryIncludingItsDecision() =
        runBlocking {
            val repository = StartExRepository(database)
            insertConfiguration(repository)
            val session = session("session-1", "STOPPED", heartbeatAtMillis = 1)
            database.botSessionDao().upsert(session)
            repository.recordDecision(
                candidate("mint-old", "REJECTED", 1),
                DecisionEntity(
                    id = "decision-old",
                    sessionId = session.id,
                    candidateMint = "mint-old",
                    strategyVersion = 1,
                    riskVersion = 1,
                    action = "REJECT",
                    score = 0,
                    factorsJson = "{}",
                    rejectionCodesJson = "[]",
                    createdAtMillis = 1,
                ),
            )

            val result =
                repository.insertCandidateBounded(
                    candidate("mint-new", "REJECTED", 2),
                    maximumCandidates = 1,
                    protectedStates = listOf("OBSERVING"),
                )

            assertEquals(CandidateInsertResult.INSERTED, result)
            assertEquals(null, database.candidateDao().byMint("mint-old"))
            assertEquals(emptyList<DecisionEntity>(), database.decisionDao().observeForMint("mint-old").first())
            assertEquals("mint-new", database.candidateDao().byMint("mint-new")?.mint)
        }

    @Test
    fun staleRunningAndProtectingSessionsExpireAtomically() =
        runBlocking {
            val repository = StartExRepository(database)
            insertConfiguration(repository)
            database.botSessionDao().upsert(session("running-stale", "RUNNING", heartbeatAtMillis = 9_999))
            database.botSessionDao().upsert(session("protecting-stale", "PROTECTING", heartbeatAtMillis = 1))
            database.botSessionDao().upsert(session("running-boundary", "RUNNING", heartbeatAtMillis = 10_000))
            database.botSessionDao().upsert(session("running-future", "RUNNING", heartbeatAtMillis = 100_001))
            database.botSessionDao().upsert(session("paused-old", "PAUSED", heartbeatAtMillis = 1))

            val expired = repository.expireStaleSessions(cutoffMillis = 10_000, nowMillis = 100_000)

            assertEquals(3, expired)
            assertEquals("NEEDS_ATTENTION", database.botSessionDao().byId("running-stale")?.status)
            assertEquals("STALE_HEARTBEAT", database.botSessionDao().byId("protecting-stale")?.stopReason)
            assertEquals(100_000L, database.botSessionDao().byId("protecting-stale")?.stoppedAtMillis)
            assertEquals("RUNNING", database.botSessionDao().byId("running-boundary")?.status)
            assertEquals("NEEDS_ATTENTION", database.botSessionDao().byId("running-future")?.status)
            assertEquals("PAUSED", database.botSessionDao().byId("paused-old")?.status)
        }

    @Test
    fun uncertainTransactionIsDurableBeforeASignatureIsKnown() =
        runBlocking {
            val transaction =
                BlockchainTransactionEntity(
                    id = "attempt-1",
                    intentId = null,
                    idempotencyKey = "send-1",
                    signature = null,
                    serializedHash = "serialized-hash-1",
                    status = "UNCERTAIN",
                    slot = null,
                    actualInputAtomic = null,
                    actualOutputAtomic = null,
                    router = "helius",
                    validatorVersion = "sol-send-v1",
                    validatorResult = "PASSED",
                    lastValidBlockHeight = 42,
                    expiresAtMillis = 10_000,
                    submittedAtMillis = 1,
                    confirmedAtMillis = null,
                    failureCode = null,
                )

            database.ledgerDao().upsertTransaction(transaction)

            assertEquals(transaction, database.ledgerDao().transactionBySerializedHash("serialized-hash-1"))
        }

    @Test
    fun latestUnresolvedManualTransactionIgnoresResolvedRows() =
        runBlocking {
            val unresolved =
                manualTransaction(
                    id = "manual-1",
                    status = "SIGNED_NOT_BROADCAST",
                    submittedAtMillis = 1,
                )
            val resolved =
                manualTransaction(
                    id = "manual-2",
                    status = "FINALIZED",
                    submittedAtMillis = 2,
                )
            database.ledgerDao().upsertTransaction(unresolved)
            database.ledgerDao().upsertTransaction(resolved)

            assertEquals(
                unresolved,
                database.ledgerDao().latestManualTransactionByStatuses(
                    listOf("SIGNED_NOT_BROADCAST", "SUBMISSION_UNCERTAIN"),
                ),
            )
        }

    @Test
    fun repositoryExposesIndependentConfigsDailyPerformanceAndBoundedTradeHistory() =
        runBlocking {
            val repository = StartExRepository(database)
            val firstStrategy = DefaultConfiguration.strategy(createdAtMillis = 1)
            val firstRisk = DefaultConfiguration.risk(createdAtMillis = 1)
            database.configDao().insertStrategy(firstStrategy)
            database.configDao().insertRisk(firstRisk)
            database.configDao().insertStrategy(firstStrategy.copy(version = 2, createdAtMillis = 2))
            database.configDao().insertRisk(firstRisk.copy(version = 2, createdAtMillis = 2))
            database.botSessionDao().upsert(
                session("session-2", "STOPPED", heartbeatAtMillis = 2).copy(
                    strategyVersion = 2,
                    riskVersion = 2,
                ),
            )
            val performance = DailyPerformanceEntity(1, 10, 8, 2, 1, 1, 0, 0, 2)
            val livePerformance = performance.copy(netPnlLamports = 99, mode = DailyPerformanceEntity.MODE_LIVE)
            repository.saveDailyPerformance(performance)
            repository.saveDailyPerformance(livePerformance)
            repeat(2) { index ->
                repository.createIntentOnce(
                    TradeIntentEntity(
                        id = "intent-$index",
                        sessionId = "session-2",
                        positionId = null,
                        idempotencyKey = "paper-history-$index",
                        side = "BUY",
                        mint = "mint-$index",
                        requestedInputAtomic = "1",
                        expectedOutputAtomic = "2",
                        maximumCostLamports = 1,
                        paperFeeLamports = 1,
                        providerRequestId = "request-$index",
                        status = "PAPER_FILLED",
                        createdAtMillis = index.toLong(),
                        updatedAtMillis = index.toLong(),
                    ),
                )
            }
            repository.savePaperExitAttempt(
                TradeIntentEntity(
                    id = "intent-cost-blocked",
                    sessionId = "session-2",
                    positionId = null,
                    idempotencyKey = "paper-history-cost-blocked",
                    side = "SELL",
                    mint = "mint-cost-blocked",
                    requestedInputAtomic = "1",
                    expectedOutputAtomic = "2",
                    maximumCostLamports = 42,
                    paperFeeLamports = 0,
                    providerRequestId = "request-cost-blocked",
                    status = "PAPER_COST_BLOCKED",
                    createdAtMillis = 3,
                    updatedAtMillis = 3,
                ),
            )

            assertEquals(2, repository.observeLatestStrategy().first()?.version)
            assertEquals(2, repository.observeLatestRisk().first()?.version)
            assertEquals(listOf(livePerformance, performance), repository.observeDailyPerformance().first())
            assertEquals(
                listOf(performance),
                repository.observeDailyPerformance(DailyPerformanceEntity.MODE_PAPER).first(),
            )
            assertEquals(performance, repository.dailyPerformance(1, DailyPerformanceEntity.MODE_PAPER))
            assertEquals(livePerformance, repository.dailyPerformance(1, DailyPerformanceEntity.MODE_LIVE))
            assertEquals(listOf("intent-1"), repository.observeTradeHistory(limit = 1).first().map { it.intentId })
            val blockedExport = database.ledgerDao().exportHistory().single { it.intentId == "intent-cost-blocked" }
            assertEquals("PAPER_COST_BLOCKED", blockedExport.intentStatus)
            assertEquals("PAPER_COST_BLOCKED", blockedExport.transactionStatus)
            assertEquals(0L, blockedExport.totalFeeLamports)
        }

    @Test
    fun analysisExportIncludesRejectedCandidateWithoutATradeIntent() =
        runBlocking {
            val repository = StartExRepository(database)
            insertConfiguration(repository)
            database.botSessionDao().upsert(session("session-export", "STOPPED", heartbeatAtMillis = 2))
            val rejected =
                candidate("mint-rejected", "REJECTED", 2).copy(
                    name = "Rejected token",
                    symbol = "NOPE",
                    score = 31,
                    rejectionCode = "LOW_LIQUIDITY",
                )
            repository.recordDecision(
                rejected,
                DecisionEntity(
                    id = "decision-rejected",
                    sessionId = "session-export",
                    candidateMint = rejected.mint,
                    strategyVersion = 1,
                    riskVersion = 1,
                    action = "REJECT",
                    score = 31,
                    factorsJson = "{\"liquidity\":\"FAIL\"}",
                    rejectionCodesJson = "[\"LOW_LIQUIDITY\"]",
                    createdAtMillis = 2,
                ),
            )
            repository.addSnapshot(
                TokenSnapshotEntity(
                    candidateMint = rejected.mint,
                    capturedAtMillis = 2,
                    slot = 123,
                    liquidityLamports = 1,
                    marketCapLamports = 2,
                    executableBuyLamports = 3,
                    executableSellLamports = 0,
                    holderCount = 4,
                    topHolderShareBps = 5_000,
                    routeAvailable = false,
                    source = "TEST",
                ),
            )

            val json = repository.exportHistoryJson()
            val csv = repository.exportHistoryCsv()

            assertTrue(json.contains("\"mint\": \"mint-rejected\""))
            assertTrue(json.contains("\"action\": \"REJECT\""))
            assertTrue(json.contains("\"snapshots\""))
            assertTrue(csv.lineSequence().any { it.startsWith("candidate,mint-rejected,") })
            assertTrue(csv.lineSequence().any { it.startsWith("decision,decision-rejected,") })
        }

    private suspend fun insertConfiguration(repository: StartExRepository) {
        repository.saveConfig(
            DefaultConfiguration.strategy(createdAtMillis = 1),
            DefaultConfiguration.risk(createdAtMillis = 1),
        )
    }

    private fun candidate(
        mint: String,
        state: String,
        updatedAtMillis: Long,
    ) = TokenCandidateEntity(
        mint = mint,
        source = "TEST",
        discoverySignature = "signature-$mint",
        creatorAddress = null,
        name = null,
        symbol = null,
        metadataUri = null,
        tokenProgram = null,
        state = state,
        score = null,
        rejectionCode = null,
        discoveredAtMillis = updatedAtMillis,
        lastUpdatedAtMillis = updatedAtMillis,
    )

    private fun session(
        id: String,
        status: String,
        heartbeatAtMillis: Long,
    ) = BotSessionEntity(
        id = id,
        mode = "PAPER",
        status = status,
        strategyVersion = 1,
        riskVersion = 1,
        startedAtMillis = 1,
        stoppedAtMillis = null,
        stopReason = null,
        lastHeartbeatAtMillis = heartbeatAtMillis,
    )

    private fun manualTransaction(
        id: String,
        status: String,
        submittedAtMillis: Long,
    ) = BlockchainTransactionEntity(
        id = id,
        intentId = null,
        idempotencyKey = "key-$id",
        signature = "signature-$id",
        serializedHash = "hash-$id",
        status = status,
        slot = null,
        actualInputAtomic = null,
        actualOutputAtomic = null,
        router = "SYSTEM_PROGRAM",
        validatorVersion = "wallet-transfer-v1",
        validatorResult = "LOCAL_BUILD_AND_SIMULATION_PASSED",
        lastValidBlockHeight = 42,
        expiresAtMillis = null,
        submittedAtMillis = submittedAtMillis,
        confirmedAtMillis = null,
        failureCode = null,
    )
}
