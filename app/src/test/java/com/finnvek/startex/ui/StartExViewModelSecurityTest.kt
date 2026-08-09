package com.finnvek.startex.ui

import androidx.lifecycle.viewModelScope
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class StartExViewModelSecurityTest {
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
    fun walletCreationRequiresAuthenticationBeforeMnemonicGeneration() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            val viewModel = StartExViewModel(application)
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.beginWalletCreation()

            assertEquals(
                AuthenticationPurpose.CreateWallet,
                (event.await() as StartExUiEvent.Authenticate).purpose,
            )
            assertSame(WalletSetupState.Closed, viewModel.walletSetup.value)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun circuitBreakerRecoveryRequiresFreshAuthentication() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            val now = System.currentTimeMillis()
            application.repository.saveConfig(
                DefaultConfiguration.strategy(now),
                DefaultConfiguration.risk(now),
            )
            application.repository.saveSession(
                BotSessionEntity(
                    id = "needs-auth",
                    mode = "PAPER",
                    status = "NEEDS_ATTENTION",
                    strategyVersion = 1,
                    riskVersion = 1,
                    startedAtMillis = now,
                    stoppedAtMillis = null,
                    stopReason = "CIRCUIT_BREAKER",
                    lastHeartbeatAtMillis = now,
                ),
            )
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.monitorState == MonitorState.NeedsAttention }
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.recoverMonitoring()

            assertEquals(
                AuthenticationPurpose.RecoverMonitoring,
                (event.await() as StartExUiEvent.Authenticate).purpose,
            )
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }
}
