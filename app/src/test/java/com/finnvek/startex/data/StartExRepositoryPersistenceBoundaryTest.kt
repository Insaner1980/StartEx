package com.finnvek.startex.data

import android.content.Context
import androidx.room.Room
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.DecisionEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderCredentialEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.StartExDatabase
import com.finnvek.startex.data.local.TokenCandidateEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartExRepositoryPersistenceBoundaryTest {
    // CPD-OFF
    private lateinit var database: StartExDatabase
    private lateinit var repository: StartExRepository

    @Before
    fun createDatabase() {
        val context: Context = RuntimeEnvironment.getApplication().applicationContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, StartExDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = StartExRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }
    // CPD-ON

    @Test
    fun `emergency exit updates the session and eligible positions together`() =
        runBlocking {
            repository.saveConfig(
                DefaultConfiguration.strategy(createdAtMillis = 1),
                DefaultConfiguration.risk(createdAtMillis = 1),
            )
            repository.saveSession(session())
            repository.recordDecision(candidate(), decision())
            repository.savePosition(position(id = "open", status = "OPEN"))
            repository.savePosition(position(id = "closed", status = "CLOSED"))

            val updated = repository.requestPaperEmergencyExit("session", nowMillis = 10)

            assertEquals(1, updated)
            assertEquals("PROTECTING", database.botSessionDao().byId("session")?.status)
            assertEquals("EMERGENCY_EXIT_REQUESTED", database.botSessionDao().byId("session")?.stopReason)
            assertEquals("EXIT_REQUESTED", database.positionDao().byId("open")?.status)
            assertEquals("EMERGENCY_EXIT", database.positionDao().byId("open")?.exitReason)
            assertEquals("CLOSED", database.positionDao().byId("closed")?.status)
        }

    @Test
    fun `stop after close is accepted once and cannot revive a stopped session`() =
        runBlocking {
            repository.saveConfig(
                DefaultConfiguration.strategy(createdAtMillis = 1),
                DefaultConfiguration.risk(createdAtMillis = 1),
            )
            repository.saveSession(session())

            assertTrue(repository.requestStopAfterClose("session", nowMillis = 10))
            assertFalse(repository.requestStopAfterClose("session", nowMillis = 11))
            assertEquals("PROTECTING", database.botSessionDao().byId("session")?.status)

            repository.updateSessionStatus(
                id = "session",
                status = "STOPPED",
                stoppedAtMillis = 12,
                stopReason = "USER_REQUESTED",
                lastHeartbeatAtMillis = 12,
            )

            assertFalse(repository.requestStopAfterClose("session", nowMillis = 13))
            assertEquals("STOPPED", database.botSessionDao().byId("session")?.status)
            assertEquals("USER_REQUESTED", database.botSessionDao().byId("session")?.stopReason)
        }

    @Test
    fun `authenticated stop updates only its original active session`() =
        runBlocking {
            repository.saveConfig(
                DefaultConfiguration.strategy(createdAtMillis = 1),
                DefaultConfiguration.risk(createdAtMillis = 1),
            )
            repository.saveSession(session().copy(status = "NEEDS_ATTENTION"))
            repository.saveSession(
                session().copy(
                    id = "replacement",
                    status = "NEEDS_ATTENTION",
                    startedAtMillis = 2,
                ),
            )

            val stopped =
                repository.stopSession(
                    id = "session",
                    activeStates = listOf("RUNNING", "PAUSED", "PROTECTING", "NEEDS_ATTENTION"),
                    stoppedAtMillis = 10,
                    stopReason = "USER_REQUESTED",
                )

            assertEquals("session", stopped?.id)
            assertEquals("STOPPED", database.botSessionDao().byId("session")?.status)
            assertEquals("NEEDS_ATTENTION", database.botSessionDao().byId("replacement")?.status)
            assertNull(
                repository.stopSession(
                    id = "session",
                    activeStates = listOf("RUNNING", "PAUSED", "PROTECTING", "NEEDS_ATTENTION"),
                    stoppedAtMillis = 11,
                    stopReason = "USER_REQUESTED",
                ),
            )
        }

    @Test
    fun `provider health read modify writes are serialized`() =
        runBlocking {
            val start = CompletableDeferred<Unit>()
            coroutineScope {
                repeat(20) { index ->
                    launch(Dispatchers.IO) {
                        start.await()
                        repository.updateProviderHealth("HELIUS") { previous ->
                            ProviderHealthEntity(
                                provider = "HELIUS",
                                state = "DEGRADED",
                                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                                lastSuccessAtMillis = previous?.lastSuccessAtMillis,
                                lastFailureAtMillis = index.toLong(),
                                latencyMillis = null,
                                retryAfterMillis = null,
                                lastFailureCode = "TEST",
                                updatedAtMillis = index.toLong(),
                            )
                        }
                    }
                }
                start.complete(Unit)
            }

            assertEquals(20, repository.providerHealth().single().consecutiveFailures)
        }

    @Test
    fun `saving a provider credential atomically invalidates earlier health`() =
        runBlocking {
            repository.updateProviderHealth("HELIUS") {
                ProviderHealthEntity(
                    provider = "HELIUS",
                    state = "HEALTHY",
                    consecutiveFailures = 0,
                    lastSuccessAtMillis = 1,
                    lastFailureAtMillis = null,
                    latencyMillis = 10,
                    retryAfterMillis = null,
                    lastFailureCode = null,
                    updatedAtMillis = 1,
                )
            }

            repository.saveProviderCredential(
                ProviderCredentialEntity(
                    providerId = "HELIUS",
                    encryptedApiKey = byteArrayOf(1),
                    apiKeyIv = byteArrayOf(2),
                    secretEnvelopeVersion = 1,
                    keystoreAccessMode = "UNATTENDED",
                    updatedAtMillis = 2,
                ),
            )

            assertTrue(repository.providerHealth().isEmpty())
            assertEquals(2L, repository.providerCredential("HELIUS")?.updatedAtMillis)
        }

    private fun session() =
        BotSessionEntity(
            id = "session",
            mode = "PAPER",
            status = "RUNNING",
            strategyVersion = 1,
            riskVersion = 1,
            startedAtMillis = 1,
            stoppedAtMillis = null,
            stopReason = null,
            lastHeartbeatAtMillis = 1,
        )

    private fun candidate() =
        TokenCandidateEntity(
            mint = "mint",
            source = "TEST",
            discoverySignature = "signature",
            creatorAddress = null,
            name = "Token",
            symbol = "TOK",
            metadataUri = null,
            tokenProgram = "program",
            state = "ACCEPTED",
            score = 100,
            rejectionCode = null,
            discoveredAtMillis = 1,
            lastUpdatedAtMillis = 1,
        )

    private fun decision() =
        DecisionEntity(
            id = "decision",
            sessionId = "session",
            candidateMint = "mint",
            strategyVersion = 1,
            riskVersion = 1,
            action = "BUY",
            score = 100,
            factorsJson = "{}",
            rejectionCodesJson = "[]",
            createdAtMillis = 1,
        )

    private fun position(
        id: String,
        status: String,
    ) = PositionEntity(
        id = id,
        sessionId = "session",
        mint = "mint",
        entryDecisionId = "decision",
        status = status,
        tokenAmountAtomic = "1",
        grossInputLamports = 10,
        netInputLamports = 10,
        latestSellQuoteLamports = 11,
        entrySignature = null,
        exitSignature = null,
        openedAtMillis = 1,
        updatedAtMillis = 1,
        closedAtMillis = if (status == "CLOSED") 2 else null,
        exitReason = null,
        mode = "PAPER",
        symbol = "TOK",
        name = "Token",
        tokenDecimals = 6,
        tokenProgram = "program",
        entryCostLamports = 0,
        exitRulesVersion = "1",
        exitRulesJson = "{}",
        latestSellQuoteAtMillis = 1,
        highestExecutableSellLamports = 11,
        lowestExecutableSellLamports = 10,
        routeAvailable = true,
        reconciliationState = "PAPER",
    )
}
