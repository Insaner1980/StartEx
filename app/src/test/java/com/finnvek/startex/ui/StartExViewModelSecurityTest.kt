package com.finnvek.startex.ui

import androidx.lifecycle.viewModelScope
import com.finnvek.startex.R
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.settings.OperatingMode
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.security.CipherPurpose
import com.finnvek.startex.security.PreparedCipherOperation
import com.finnvek.startex.wallet.LocalWallet
import com.finnvek.startex.wallet.MAX_MNEMONIC_INPUT_CHAR_COUNT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

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
    fun cancellingWalletSetupRejectsLateCreateAndRestoreResults() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application, mainDispatcher)
            val authentication = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.beginWalletCreation()
            viewModel.onAuthenticationSucceeded(authentication.await() as StartExUiEvent.Authenticate)
            assertSame(WalletSetupState.Working, viewModel.walletSetup.value)
            viewModel.dismissWalletSetup()
            mainDispatcher.scheduler.advanceUntilIdle()
            assertSame(WalletSetupState.Closed, viewModel.walletSetup.value)

            viewModel.beginWalletRestore()
            viewModel.previewRestoredWallet(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
                    .toCharArray(),
            )
            assertSame(WalletSetupState.Working, viewModel.walletSetup.value)
            viewModel.dismissWalletSetup()
            mainDispatcher.scheduler.advanceUntilIdle()
            assertSame(WalletSetupState.Closed, viewModel.walletSetup.value)

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // Each restore-security scenario owns its mutable secret and database lifecycle.
    // CPD-OFF
    @Test
    fun biometricCancellationDiscardsThePendingRestore() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application, mainDispatcher)
            viewModel.restoreLocalWallet = { LocalWallet(ByteArray(32) { 1 }) }
            viewModel.prepareWalletEncryption = ::preparedEncryptionOperation

            viewModel.beginWalletRestore()
            viewModel.previewRestoredWallet("first restore".toCharArray())
            mainDispatcher.scheduler.advanceUntilIdle()
            val reviewed = viewModel.walletSetup.value as WalletSetupState.ReviewWallet
            val authentication = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
            viewModel.requestWalletSave(restoredBackupConfirmed = true)
            val request = authentication.await() as StartExUiEvent.Authenticate
            assertTrue(viewModel.onAuthenticationPromptStarted(request))

            viewModel.onAuthenticationFailed(request)

            assertSame(WalletSetupState.Closed, viewModel.walletSetup.value)
            assertNull(application.repository.observeWalletProfile().first())
            viewModel.beginWalletRestore()
            viewModel.restoreLocalWallet = { LocalWallet(ByteArray(32) { 2 }) }
            viewModel.previewRestoredWallet("second restore".toCharArray())
            mainDispatcher.scheduler.advanceUntilIdle()
            val replacement = viewModel.walletSetup.value as WalletSetupState.ReviewWallet
            assertNotEquals(reviewed.publicAddress, replacement.publicAddress)

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun dismissedRestoreRejectsItsAuthenticationDuringTheNextAttempt() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application, mainDispatcher)
            viewModel.restoreLocalWallet = { LocalWallet(ByteArray(32) { 1 }) }
            viewModel.prepareWalletEncryption = ::preparedEncryptionOperation

            viewModel.beginWalletRestore()
            viewModel.previewRestoredWallet("first restore".toCharArray())
            mainDispatcher.scheduler.advanceUntilIdle()
            val authentication = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
            viewModel.requestWalletSave(restoredBackupConfirmed = true)
            val staleRequest = authentication.await() as StartExUiEvent.Authenticate
            viewModel.dismissWalletSetup()

            viewModel.restoreLocalWallet = { LocalWallet(ByteArray(32) { 2 }) }
            viewModel.beginWalletRestore()
            viewModel.previewRestoredWallet("second restore".toCharArray())
            mainDispatcher.scheduler.advanceUntilIdle()
            val replacement = viewModel.walletSetup.value

            assertFalse(viewModel.onAuthenticationPromptStarted(staleRequest))
            viewModel.onAuthenticationFailed(staleRequest)
            assertEquals(replacement, viewModel.walletSetup.value)
            viewModel.onAuthenticationSucceeded(staleRequest)
            assertEquals(replacement, viewModel.walletSetup.value)
            assertNull(application.repository.observeWalletProfile().first())

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun invalidRestoreClearsCallerInputAndDoesNotPersistWallet() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application, mainDispatcher)
            val phrase =
                "xxxxx abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
                    .toCharArray()

            viewModel.beginWalletRestore()
            viewModel.previewRestoredWallet(phrase)
            mainDispatcher.scheduler.advanceUntilIdle()

            assertTrue(phrase.all { it == '0' })
            assertEquals(
                WalletSetupState.RestoreInput(R.string.restore_phrase_invalid),
                viewModel.walletSetup.value,
            )
            assertNull(application.repository.observeWalletProfile().first())
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun overlongRestoreFailsBeforeLibraryWorkAndLeavesNoUsableWallet() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application, mainDispatcher)
            var restoreCalls = 0
            viewModel.restoreLocalWallet = {
                restoreCalls += 1
                error("Overlong input reached the mnemonic library")
            }
            val phrase = CharArray(MAX_MNEMONIC_INPUT_CHAR_COUNT + 100_000) { 'x' }

            viewModel.beginWalletRestore()
            viewModel.previewRestoredWallet(phrase)

            assertTrue(phrase.all { it == '0' })
            assertEquals(0, restoreCalls)
            assertEquals(
                WalletSetupState.RestoreInput(R.string.restore_phrase_invalid),
                viewModel.walletSetup.value,
            )
            assertFalse(viewModel.state.value.walletUnlocked)
            assertNull(viewModel.state.value.message)
            assertNull(application.repository.observeWalletProfile().first())
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun mnemonicLibraryExceptionUsesGenericFailureAndLeavesNoUsableWallet() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application, mainDispatcher)
            viewModel.restoreLocalWallet = { throw IllegalStateException("Library failure with sensitive input") }
            val phrase =
                "legal winner thank year wave sausage worth useful legal winner thank yellow"
                    .toCharArray()

            viewModel.beginWalletRestore()
            viewModel.previewRestoredWallet(phrase)
            mainDispatcher.scheduler.advanceUntilIdle()

            assertTrue(phrase.all { it == '0' })
            assertEquals(
                WalletSetupState.RestoreInput(R.string.restore_phrase_invalid),
                viewModel.walletSetup.value,
            )
            assertFalse(viewModel.state.value.walletUnlocked)
            assertNull(viewModel.state.value.message)
            assertNull(application.repository.observeWalletProfile().first())
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // CPD-ON
    @Test
    fun activityStopClearsARevealedMnemonicBuffer() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            val viewModel = StartExViewModel(application)
            val phrase = "one two three".toCharArray()
            viewModel.replaceWalletOverlay(WalletOverlay.RevealedMnemonic(phrase))

            viewModel.onActivityStopped()

            assertSame(WalletOverlay.None, viewModel.walletOverlay.value)
            assertTrue(phrase.all { it == '0' })
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun cancellationAfterUnlockRestoreClosesTheUnclaimedWallet() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            val restoreDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val viewModel = StartExViewModel(application, restoreDispatcher)
            val restoredWallet = LocalWallet(ByteArray(32) { 1 })
            val restoreCompleted = CountDownLatch(1)
            val phrase = "cancelled unlock".toCharArray()
            viewModel.restoreLocalWallet = {
                restoredWallet.also { restoreCompleted.countDown() }
            }

            try {
                val restoreJob =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        viewModel.restoreWalletForUnlock(phrase)
                    }
                assertTrue(restoreCompleted.await(5, TimeUnit.SECONDS))

                restoreJob.cancel()
                runCurrent()
                restoreJob.join()

                assertTrue(phrase.all { it == '0' })
                assertTrue(runCatching { restoredWallet.sign(byteArrayOf(1)) }.exceptionOrNull() is IllegalStateException)
            } finally {
                viewModel.viewModelScope.cancel()
                restoreDispatcher.close()
            }
        }

    @Test
    fun rapidOnboardingCompletionCreatesDefaultsOnce() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setOnboardingComplete(false)
            val viewModel = StartExViewModel(application)

            repeat(3) { viewModel.completeOnboarding() }

            application.settings.settings.first { it.onboardingComplete }
            val strategies =
                application.repository
                    .observeStrategies()
                    .first()
            val risks =
                application.repository
                    .observeRisks()
                    .first()
            assertEquals(1, strategies.size)
            assertEquals(1, risks.size)
            viewModel.viewModelScope.cancel()
            application.settings.setOnboardingComplete(false)
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun incompleteDefaultConfigurationKeepsOnboardingOpenWithVisibleFailure() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setOnboardingComplete(false)
            application.database.configDao().insertRisk(DefaultConfiguration.risk(System.currentTimeMillis()))
            val viewModel = StartExViewModel(application)
            val failedState =
                async(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.state.first { it.loaded && it.message == R.string.onboarding_save_failed }
                }

            viewModel.completeOnboarding()

            failedState.await()
            val settings =
                application.settings.settings
                    .first()
            assertEquals(false, settings.onboardingComplete)
            assertNull(application.repository.observeLatestStrategy().first())
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
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

    // Security state-transition tests intentionally keep setup and cleanup local to each scenario.
    // CPD-OFF
    @Test
    fun staleTrustedAddressActionsRemainVisibleAsFailures() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }

            viewModel.requestDeleteTrustedAddress(id = 7, finalCharacters = "")
            assertEquals(
                R.string.trusted_address_change_failed,
                viewModel.state.first { it.message != null }.message,
            )
            viewModel.clearMessage()

            viewModel.requestUnlockTrustedAddress(id = 7, finalCharacters = "1111")
            assertEquals(
                R.string.trusted_address_change_failed,
                viewModel.state.first { it.message != null }.message,
            )

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
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
            val events = mutableListOf<StartExUiEvent>()
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect(events::add)
                }

            viewModel.recoverMonitoring()
            viewModel.requestAuthenticatedMonitoringStop()
            runCurrent()

            assertEquals(
                AuthenticationPurpose.RecoverMonitoring,
                (events.single() as StartExUiEvent.Authenticate).purpose,
            )
            viewModel.onAuthenticationSucceeded(events.single() as StartExUiEvent.Authenticate)
            runCurrent()
            assertEquals(
                "needs-auth",
                (events.last() as StartExUiEvent.RecoverMonitoringService).sessionId,
            )
            collector.cancel()
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun attentionStopRunsOnlyAfterItsOwnAuthentication() =
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
                    id = "stop-needs-auth",
                    mode = "PAPER",
                    status = "NEEDS_ATTENTION",
                    strategyVersion = 1,
                    riskVersion = 1,
                    startedAtMillis = now,
                    stoppedAtMillis = null,
                    stopReason = "RECOVERY_AUTHENTICATION_REQUIRED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.monitorState == MonitorState.NeedsAttention }
            val authentication = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.requestAuthenticatedMonitoringStop()

            val request = authentication.await() as StartExUiEvent.Authenticate
            assertEquals(AuthenticationPurpose.StopMonitoring, request.purpose)
            val continuation = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
            viewModel.onAuthenticationSucceeded(request)
            val stopEvent = continuation.await()
            assertEquals(
                "stop-needs-auth",
                (stopEvent as StartExUiEvent.StopAuthenticatedMonitoringService).sessionId,
            )

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // CPD-ON
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

    @Test
    fun recoveryAuthenticationCannotActOnAReplacementSession() =
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
                    id = "original-session",
                    mode = "PAPER",
                    status = "NEEDS_ATTENTION",
                    strategyVersion = 1,
                    riskVersion = 1,
                    startedAtMillis = now,
                    stoppedAtMillis = now,
                    stopReason = "RECOVERY_AUTHENTICATION_REQUIRED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.monitorState == MonitorState.NeedsAttention }
            val events = mutableListOf<StartExUiEvent>()
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect(events::add)
                }

            viewModel.recoverMonitoring()
            runCurrent()
            val request = events.single() as StartExUiEvent.Authenticate
            application.repository.updateSessionStatus(
                id = "original-session",
                status = "STOPPED",
                stoppedAtMillis = now + 1,
                stopReason = "USER_REQUESTED",
                lastHeartbeatAtMillis = now + 1,
            )
            viewModel.state.first { it.monitorState == MonitorState.Stopped }
            application.repository.saveSession(
                BotSessionEntity(
                    id = "replacement-session",
                    mode = "PAPER",
                    status = "NEEDS_ATTENTION",
                    strategyVersion = 1,
                    riskVersion = 1,
                    startedAtMillis = now + 2,
                    stoppedAtMillis = now + 2,
                    stopReason = "RECOVERY_AUTHENTICATION_REQUIRED",
                    lastHeartbeatAtMillis = now + 2,
                ),
            )
            viewModel.state.first { it.monitorState == MonitorState.NeedsAttention }

            viewModel.onAuthenticationSucceeded(request)
            runCurrent()

            assertEquals(listOf(request), events)
            assertEquals(R.string.monitoring_state_changed, viewModel.state.value.message)
            collector.cancel()
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun stopAuthenticationCannotActOnAReplacementSession() =
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
                    id = "original-stop-session",
                    mode = "PAPER",
                    status = "NEEDS_ATTENTION",
                    strategyVersion = 1,
                    riskVersion = 1,
                    startedAtMillis = now,
                    stoppedAtMillis = now,
                    stopReason = "RECOVERY_AUTHENTICATION_REQUIRED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.monitorState == MonitorState.NeedsAttention }
            val events = mutableListOf<StartExUiEvent>()
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect(events::add)
                }

            viewModel.requestAuthenticatedMonitoringStop()
            runCurrent()
            val request = events.single() as StartExUiEvent.Authenticate
            application.repository.updateSessionStatus(
                id = "original-stop-session",
                status = "STOPPED",
                stoppedAtMillis = now + 1,
                stopReason = "USER_REQUESTED",
                lastHeartbeatAtMillis = now + 1,
            )
            viewModel.state.first { it.monitorState == MonitorState.Stopped }
            application.repository.saveSession(
                BotSessionEntity(
                    id = "replacement-stop-session",
                    mode = "PAPER",
                    status = "NEEDS_ATTENTION",
                    strategyVersion = 1,
                    riskVersion = 1,
                    startedAtMillis = now + 2,
                    stoppedAtMillis = now + 2,
                    stopReason = "RECOVERY_AUTHENTICATION_REQUIRED",
                    lastHeartbeatAtMillis = now + 2,
                ),
            )
            viewModel.state.first { it.monitorState == MonitorState.NeedsAttention }

            viewModel.onAuthenticationSucceeded(request)
            runCurrent()

            assertEquals(listOf(request), events)
            assertEquals(R.string.monitoring_state_changed, viewModel.state.value.message)
            collector.cancel()
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // CPD-ON
    // Concurrent Demo transition tests intentionally keep their independent lifecycle setup local.
    // CPD-OFF
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

    @Test
    fun repeatedProviderTestCancelsTheStaleCallback() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }
            val firstStarted = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val secondResult = CompletableDeferred<ProviderResult<*>>()
            var calls = 0
            viewModel.providerReadOnlyTest = {
                calls += 1
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                } else {
                    secondResult.await()
                }
            }

            viewModel.testProvider(ProviderId.HELIUS)
            firstStarted.await()
            assertEquals(setOf(ProviderId.HELIUS), viewModel.providerTestsInProgress.value)
            viewModel.testProvider(ProviderId.HELIUS)
            firstCancelled.await()
            secondResult.complete(ProviderResult.Failure(ProviderError.Unauthorized(ProviderId.HELIUS)))

            val state =
                viewModel.state.first { current ->
                    current.providerHealth.any { it.provider == ProviderId.HELIUS.name }
                }
            assertEquals(2, calls)
            assertEquals("OFFLINE", state.providerHealth.single().state)
            assertEquals("Unauthorized", state.providerHealth.single().lastFailureCode)
            assertEquals(R.string.provider_test_failed, state.message)
            viewModel.providerTestsInProgress.first { it.isEmpty() }

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun demoTransitionCancelsAnInFlightProviderTest() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            viewModel.providerReadOnlyTest = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }

            viewModel.testProvider(ProviderId.HELIUS)
            started.await()
            viewModel.setDemoMode(true)

            cancelled.await()
            val state = viewModel.state.first { it.demoMode }
            assertTrue(state.providerHealth.isEmpty())
            assertNull(application.sessionApiKeys.apiKeyFor(ProviderId.HELIUS))

            viewModel.viewModelScope.cancel()
            application.settings.setDemoMode(false)
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun latestDemoSelectionWinsConcurrentTransitions() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }

            viewModel.setDemoMode(true)
            viewModel.setDemoMode(false)

            val state =
                viewModel.state.first {
                    !it.demoMode && it.message == R.string.demo_disabled
                }
            assertEquals(TradingMode.Paper, state.mode)

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun authenticationFromBeforeARapidDemoCycleCannotContinue() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            withContext(Dispatchers.Default) {
                viewModel.state.first { it.loaded && !it.demoMode }
            }
            val authentication = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.beginWalletCreation()
            val request = authentication.await() as StartExUiEvent.Authenticate
            viewModel.setDemoMode(true)
            withContext(Dispatchers.Default) {
                viewModel.state.first { it.demoMode }
            }
            viewModel.setDemoMode(false)
            withContext(Dispatchers.Default) {
                viewModel.state.first { it.loaded && !it.demoMode }
            }
            viewModel.onAuthenticationSucceeded(request)
            runCurrent()

            assertSame(WalletSetupState.Closed, viewModel.walletSetup.value)
            assertNull(
                withContext(Dispatchers.Default) {
                    application.repository.observeWalletProfile().first()
                },
            )
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    // CPD-ON
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

    @Test
    fun historyExportRunsOnceReportsFailuresAndAllowsRetry() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }
            val events = mutableListOf<StartExUiEvent.ShareText>()
            val eventCollector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { event ->
                        if (event is StartExUiEvent.ShareText) events += event
                    }
                }
            val releaseExport = CompletableDeferred<Unit>()
            var exportCalls = 0
            viewModel.historyExportText = { json ->
                exportCalls += 1
                releaseExport.await()
                if (json) "{}" else "csv"
            }

            viewModel.exportHistory(json = true)
            viewModel.exportHistory(json = false)
            runCurrent()

            assertEquals(1, exportCalls)
            releaseExport.complete(Unit)
            runCurrent()
            assertEquals(listOf("StartEx history JSON"), events.map(StartExUiEvent.ShareText::title))

            events.clear()
            viewModel.historyExportText = { throw IllegalStateException("serialization failed") }
            viewModel.exportHistory(json = false)
            runCurrent()
            assertEquals(R.string.history_export_failed, viewModel.state.value.message)
            assertTrue(events.isEmpty())

            viewModel.clearMessage()
            viewModel.historyExportText = { "csv" }
            viewModel.exportHistory(json = false)
            runCurrent()
            assertEquals(listOf("StartEx history CSV"), events.map(StartExUiEvent.ShareText::title))

            viewModel.onHistoryShareFailed()
            runCurrent()
            assertEquals(R.string.history_share_failed, viewModel.state.value.message)

            eventCollector.cancel()
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun cancellingTheViewModelDiscardsAnInMemoryHistoryExport() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }
            val events = mutableListOf<StartExUiEvent>()
            val eventCollector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect(events::add)
                }
            val releaseExport = CompletableDeferred<Unit>()
            viewModel.historyExportText = {
                releaseExport.await()
                "{}"
            }

            viewModel.exportHistory(json = true)
            runCurrent()
            viewModel.viewModelScope.cancel()
            releaseExport.complete(Unit)
            runCurrent()

            assertTrue(events.isEmpty())
            assertNull(viewModel.state.value.message)
            eventCollector.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun rapidDemoCycleDiscardsAStaleHistoryExportAndAllowsAFreshRetry() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            val viewModel = StartExViewModel(application)
            viewModel.state.first { it.loaded && !it.demoMode }
            val events = mutableListOf<StartExUiEvent.ShareText>()
            val eventCollector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { event ->
                        if (event is StartExUiEvent.ShareText) events += event
                    }
                }
            val releaseExport = CompletableDeferred<Unit>()
            viewModel.historyExportText = {
                releaseExport.await()
                "stale"
            }

            viewModel.exportHistory(json = true)
            runCurrent()
            viewModel.setDemoMode(true)
            viewModel.state.first { it.demoMode }
            viewModel.setDemoMode(false)
            viewModel.state.first { it.loaded && !it.demoMode }
            releaseExport.complete(Unit)
            runCurrent()

            assertTrue(events.isEmpty())
            viewModel.historyExportText = { "fresh" }
            viewModel.exportHistory(json = false)
            runCurrent()
            assertEquals(listOf("fresh"), events.map(StartExUiEvent.ShareText::text))

            eventCollector.cancel()
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

    @Test
    fun watchObservesPersistedProductionCandidateStates() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            application.settings.setDemoMode(false)
            listOf("DISCOVERED", "POSITION_OPEN").forEachIndexed { index, candidateState ->
                application.repository.saveCandidate(
                    TokenCandidateEntity(
                        mint = "mint-$index",
                        source = "PUMP_PORTAL_NEW_TOKEN",
                        discoverySignature = "signature-$index",
                        creatorAddress = null,
                        name = null,
                        symbol = null,
                        metadataUri = null,
                        tokenProgram = null,
                        state = candidateState,
                        score = null,
                        rejectionCode = null,
                        discoveredAtMillis = index.toLong(),
                        lastUpdatedAtMillis = index.toLong(),
                    ),
                )
            }
            val viewModel = StartExViewModel(application)

            val state = viewModel.state.first { it.loaded && it.candidates.size == 2 }

            assertEquals(setOf("DISCOVERED", "POSITION_OPEN"), state.candidates.map { it.state }.toSet())
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    private fun preparedEncryptionOperation(): PreparedCipherOperation =
        PreparedCipherOperation(
            Cipher.getInstance("AES/GCM/NoPadding"),
            CipherPurpose.ENCRYPT,
        )
}
