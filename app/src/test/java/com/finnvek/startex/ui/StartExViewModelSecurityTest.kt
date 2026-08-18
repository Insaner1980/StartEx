package com.finnvek.startex.ui

import androidx.lifecycle.viewModelScope
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.settings.OperatingMode
import com.finnvek.startex.network.ProviderId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun authenticationRequestSurvivesAnActivityCollectorGap() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            val viewModel = StartExViewModel(application)

            viewModel.beginWalletCreation()

            val authentication = viewModel.events.first() as StartExUiEvent.Authenticate
            viewModel.onAuthenticationPromptStarted(authentication)
            assertTrue(viewModel.authenticationInProgress)
            assertSame(authentication, viewModel.takePendingAuthenticationRequest())
            assertEquals(false, viewModel.authenticationInProgress)
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

    // CPD-OFF
    @Test
    fun secureSessionResumeRequiresFreshAuthentication() =
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
                    id = "paused-needs-auth",
                    mode = "PAPER",
                    status = "PAUSED",
                    strategyVersion = 1,
                    riskVersion = 1,
                    startedAtMillis = now,
                    stoppedAtMillis = null,
                    stopReason = "USER_PAUSED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.monitorState == MonitorState.Paused && it.secureSession }
            val authentication = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.resumeMonitoring()

            assertEquals(
                AuthenticationPurpose.RecoverMonitoring,
                (authentication.await() as StartExUiEvent.Authenticate).purpose,
            )
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // CPD-ON
    @Test
    fun demoModeBlocksServiceAndExportEventsAndClearsRuntimeProviderKeys() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }
            application.sessionApiKeys.put(ProviderId.HELIUS, "runtime-key".toCharArray())
            val events = mutableListOf<StartExUiEvent>()
            val eventCollector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }
            viewModel.beginWalletCreation()
            runCurrent()
            val authentication = events.single { it is StartExUiEvent.Authenticate } as StartExUiEvent.Authenticate

            viewModel.setDemoMode(true)
            viewModel.state.first { it.demoMode }
            viewModel.onAuthenticationSucceeded(authentication)
            viewModel.startMonitoring()
            viewModel.exportHistory(json = true)
            runCurrent()

            assertNull(application.sessionApiKeys.apiKeyFor(ProviderId.HELIUS))
            assertSame(WalletSetupState.Closed, viewModel.walletSetup.value)
            assertNull(application.repository.observeWalletProfile().first())
            assertTrue(events.none { it is StartExUiEvent.StartMonitoringService })
            assertTrue(events.none { it is StartExUiEvent.ShareText })
            eventCollector.cancel()
            viewModel.viewModelScope.cancel()
            application.settings.setDemoMode(false)
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // CPD-OFF
    @Test
    fun historyExportPermissionFailsClosedAcrossDemoTransitions() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }

            assertTrue(viewModel.historyExportAllowed)
            viewModel.setDemoMode(true)
            assertEquals(false, viewModel.historyExportAllowed)
            viewModel.state.first { it.demoMode }

            viewModel.setDemoMode(false)
            assertEquals(false, viewModel.historyExportAllowed)
            viewModel.state.first { it.loaded && !it.demoMode }
            assertTrue(viewModel.historyExportAllowed)

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // CPD-ON
    @Test
    fun staleUnattendedSettingFailsClosedWithoutAnUnattendedWalletEnvelope() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            application.settings.setSecurityMode(unattended = true)

            val viewModel = StartExViewModel(application)
            val state = viewModel.state.first { it.loaded && it.secureSession }

            assertTrue(state.secureSession)
            assertEquals(false, state.unattendedMode)
            assertEquals(
                false,
                application.settings.settings
                    .first { !it.unattendedMode }
                    .unattendedMode,
            )
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun demoAlwaysPresentsPaperInsteadOfAStaleLivePreference() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setOperatingMode(OperatingMode.LIVE)
            application.settings.setDemoMode(true)

            val viewModel = StartExViewModel(application)
            val state = viewModel.state.first { it.loaded && it.demoMode }

            assertEquals(TradingMode.Paper, state.mode)
            viewModel.viewModelScope.cancel()
            application.settings.setOperatingMode(OperatingMode.PAPER)
            application.settings.setDemoMode(false)
        }
}
