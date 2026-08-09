package com.finnvek.startex.data

import android.content.Context
import androidx.room.Room
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.StartExDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartExRepositoryConfigurationSafetyTest {
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

    @Test
    fun `configuration save is rejected while a resumable session exists`() =
        runBlocking {
            listOf("RUNNING", "PAUSED", "PROTECTING", "NEEDS_ATTENTION").forEachIndexed { index, status ->
                database.clearAllTables()
                val firstStrategy = DefaultConfiguration.strategy(createdAtMillis = 1)
                val firstRisk = DefaultConfiguration.risk(createdAtMillis = 1)
                repository.saveConfig(firstStrategy, firstRisk)
                repository.saveSession(session(id = "session-$index", status = status))

                val saved =
                    repository.saveConfig(
                        firstStrategy.copy(version = 2, createdAtMillis = 2),
                        firstRisk.copy(version = 2, createdAtMillis = 2),
                    )

                assertEquals("$status must keep its pinned configuration effective", false, saved)
                assertEquals(1, database.configDao().latestStrategy()?.version)
                assertEquals(1, database.configDao().latestRisk()?.version)
            }
        }

    @Test
    fun `configuration version queries resolve pinned versions instead of latest versions`() =
        runBlocking {
            val firstStrategy = DefaultConfiguration.strategy(createdAtMillis = 1)
            val firstRisk = DefaultConfiguration.risk(createdAtMillis = 1)
            repository.saveConfig(firstStrategy, firstRisk)
            val session = session(id = "legacy-session", status = "NEEDS_ATTENTION")
            repository.saveSession(session)
            database.configDao().insertStrategy(firstStrategy.copy(version = 2, createdAtMillis = 2))
            database.configDao().insertRisk(firstRisk.copy(version = 2, createdAtMillis = 2))

            assertEquals(1, database.configDao().strategyByVersion(session.strategyVersion)?.version)
            assertEquals(1, database.configDao().riskByVersion(session.riskVersion)?.version)
            assertEquals(2, database.configDao().latestStrategy()?.version)
            assertEquals(2, database.configDao().latestRisk()?.version)
        }

    @Test
    fun `explicit stop ends a needs-attention session and releases the save gate`() =
        runBlocking {
            val firstStrategy = DefaultConfiguration.strategy(createdAtMillis = 1)
            val firstRisk = DefaultConfiguration.risk(createdAtMillis = 1)
            repository.saveConfig(firstStrategy, firstRisk)
            repository.saveSession(session(id = "stuck-session", status = "NEEDS_ATTENTION"))

            val stopped =
                repository.stopLatestSession(
                    activeStates = listOf("RUNNING", "PAUSED", "PROTECTING", "NEEDS_ATTENTION"),
                    stoppedAtMillis = 3,
                    stopReason = "USER_REQUESTED",
                )
            val saved =
                repository.saveConfig(
                    firstStrategy.copy(version = 2, createdAtMillis = 3),
                    firstRisk.copy(version = 2, createdAtMillis = 3),
                )

            assertEquals("stuck-session", stopped?.id)
            assertEquals("STOPPED", database.botSessionDao().byId("stuck-session")?.status)
            assertEquals(true, saved)
        }

    private fun session(
        id: String,
        status: String,
    ) = BotSessionEntity(
        id = id,
        mode = "PAPER",
        status = status,
        strategyVersion = 1,
        riskVersion = 1,
        startedAtMillis = 1,
        stoppedAtMillis = if (status == "NEEDS_ATTENTION") 2 else null,
        stopReason = if (status == "NEEDS_ATTENTION") "TEST_RECOVERY" else null,
        lastHeartbeatAtMillis = 1,
    )
}
