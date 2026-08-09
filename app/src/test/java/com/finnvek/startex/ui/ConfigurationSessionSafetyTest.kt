package com.finnvek.startex.ui

import androidx.lifecycle.viewModelScope
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.ui.screens.configurationInputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationSessionSafetyTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `active session exposes its pinned configuration as the effective values`() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            val firstStrategy = DefaultConfiguration.strategy(createdAtMillis = 1)
            val firstRisk = DefaultConfiguration.risk(createdAtMillis = 1)
            application.repository.saveConfig(firstStrategy, firstRisk)
            application.repository.saveSession(session("effective-values", "PAUSED"))
            application.database.configDao().insertStrategy(firstStrategy.copy(version = 2, createdAtMillis = 2))
            application.database.configDao().insertRisk(firstRisk.copy(version = 2, createdAtMillis = 2))
            val viewModel = StartExViewModel(application)

            val state = viewModel.state.first { it.loaded && it.activeSessionId == "effective-values" }

            assertEquals(1, state.strategy?.version)
            assertEquals(1, state.risk?.version)
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun `view model cannot save new configuration during an active session`() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            val strategy = DefaultConfiguration.strategy(createdAtMillis = 1)
            val risk = DefaultConfiguration.risk(createdAtMillis = 1)
            application.repository.saveConfig(strategy, risk)
            application.repository.saveSession(session("save-gate", "RUNNING", System.currentTimeMillis()))
            val viewModel = StartExViewModel(application)
            val state = viewModel.state.first { it.loaded && it.activeSessionId == "save-gate" }
            val inputs = configurationInputs(requireNotNull(state.risk), requireNotNull(state.strategy))

            viewModel.saveConfiguration(inputs.risk, inputs.strategy)
            advanceUntilIdle()

            assertEquals(
                1,
                application.database
                    .configDao()
                    .latestStrategy()
                    ?.version,
            )
            assertEquals(
                1,
                application.database
                    .configDao()
                    .latestRisk()
                    ?.version,
            )
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    private fun session(
        id: String,
        status: String,
        heartbeatAtMillis: Long = 1,
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
}
