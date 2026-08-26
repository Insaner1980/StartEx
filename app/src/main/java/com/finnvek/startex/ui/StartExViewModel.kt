package com.finnvek.startex.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finnvek.startex.R
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.AppEventEntity
import com.finnvek.startex.data.local.BlockchainTransactionEntity
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderCredentialEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.data.local.TrustedAddressEntity
import com.finnvek.startex.data.local.WalletProfileEntity
import com.finnvek.startex.data.local.WalletSecretEnvelopeEntity
import com.finnvek.startex.data.local.isFreshHealthy
import com.finnvek.startex.data.settings.AppSettings
import com.finnvek.startex.data.settings.OperatingMode
import com.finnvek.startex.device.DeviceHealthSnapshot
import com.finnvek.startex.domain.ConfigurationEditResult
import com.finnvek.startex.domain.ConfigurationEditor
import com.finnvek.startex.domain.RiskConfigurationInput
import com.finnvek.startex.domain.StrategyConfigurationInput
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.RpcAddressSignature
import com.finnvek.startex.network.RpcTokenHolding
import com.finnvek.startex.network.SwapOrderRequest
import com.finnvek.startex.parseSolAmount
import com.finnvek.startex.security.AndroidKeystoreSecretCipher
import com.finnvek.startex.security.CorruptedSecretEnvelopeException
import com.finnvek.startex.security.KeystoreAccessMode
import com.finnvek.startex.security.PreparedCipherOperation
import com.finnvek.startex.security.SecretEnvelope
import com.finnvek.startex.security.SecretEnvelopeDatabaseException
import com.finnvek.startex.security.SecretEnvelopeFailure
import com.finnvek.startex.security.TrustedAddressMutation
import com.finnvek.startex.security.TrustedAddressPolicy
import com.finnvek.startex.security.WalletAccessMode
import com.finnvek.startex.security.WalletAccessPolicy
import com.finnvek.startex.security.classifySecretEnvelopeFailure
import com.finnvek.startex.security.clearSecret
import com.finnvek.startex.security.persistedWalletAccessMode
import com.finnvek.startex.service.AppNotificationDispatcher
import com.finnvek.startex.service.SessionHeartbeatFreshnessPolicy
import com.finnvek.startex.wallet.LocalWallet
import com.finnvek.startex.wallet.LocalWalletFactory
import com.finnvek.startex.wallet.MAX_MNEMONIC_INPUT_CHAR_COUNT
import com.finnvek.startex.wallet.ManualTransferConfirmationTracker
import com.finnvek.startex.wallet.ManualTransferStatus
import com.finnvek.startex.wallet.ManualTransferTrackingResult
import com.finnvek.startex.wallet.MnemonicBackupChallenge
import com.finnvek.startex.wallet.NewLocalWallet
import com.finnvek.startex.wallet.PreparedSolTransfer
import com.finnvek.startex.wallet.SignedSolTransfer
import com.finnvek.startex.wallet.SolTransferFailureReason
import com.finnvek.startex.wallet.SolTransferResult
import com.finnvek.startex.wallet.SolanaAddressValidator
import com.finnvek.startex.wallet.SolanaWalletDerivationPath
import com.finnvek.startex.wallet.WalletSecretCodec
import com.finnvek.startex.wallet.WalletTransferCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.math.BigDecimal
import java.math.RoundingMode

private const val SOL_DECIMAL_PLACES = 9
private const val HEALTHY = "HEALTHY"
private val REQUIRED_HEALTH_PROVIDERS = ProviderId.entries.toSet()
private val STARTUP_TEST_PROVIDERS = setOf(ProviderId.HELIUS, ProviderId.JUPITER, ProviderId.KRAKEN)

@Stable
@Suppress("LargeClass", "TooManyFunctions")
class StartExViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as StartExApplication
    private val walletFactory = LocalWalletFactory()
    private val walletTransferCoordinator = WalletTransferCoordinator(app.heliusRpc)
    private val manualTransferConfirmationTracker = ManualTransferConfirmationTracker(app.heliusRpc)
    private val walletAccess = WalletAccessPolicy()
    private val trustedAddressPolicy = TrustedAddressPolicy(SolanaAddressValidator())
    private val heartbeatFreshnessPolicy = SessionHeartbeatFreshnessPolicy()
    private val walletUnlocked = MutableStateFlow(false)
    private val message = MutableStateFlow<Int?>(null)
    private val walletBalance = MutableStateFlow(WalletBalanceState())
    private val walletData = MutableStateFlow(WalletDataState())
    private val activatedProviders = MutableStateFlow<Set<ProviderId>>(emptySet())
    private val _providerTestsInProgress = MutableStateFlow<Set<ProviderId>>(emptySet())
    val providerTestsInProgress: StateFlow<Set<ProviderId>> = _providerTestsInProgress.asStateFlow()
    private val deviceHealth = MutableStateFlow<DeviceHealthSnapshot?>(null)
    private val transferReconciliationMutex = Mutex()
    private val walletModeMutex = Mutex()
    private val demoModeMutex = Mutex()
    private val runtime = MutableStateFlow(RuntimeState(null, emptyList(), loaded = false))
    private val _startupError = MutableStateFlow<Int?>(null)
    val startupError: StateFlow<Int?> = _startupError.asStateFlow()

    private val _walletSetup = MutableStateFlow<WalletSetupState>(WalletSetupState.Closed)
    val walletSetup: StateFlow<WalletSetupState> = _walletSetup.asStateFlow()

    private val _walletOverlay = MutableStateFlow<WalletOverlay>(WalletOverlay.None)
    val walletOverlay: StateFlow<WalletOverlay> = _walletOverlay.asStateFlow()

    private val _walletTransfer = MutableStateFlow<WalletTransferState>(WalletTransferState.Editing)
    val walletTransfer: StateFlow<WalletTransferState> = _walletTransfer.asStateFlow()

    private val _configurationSave = MutableStateFlow<ConfigurationSaveState>(ConfigurationSaveState.Idle)
    val configurationSave: StateFlow<ConfigurationSaveState> = _configurationSave.asStateFlow()

    private val _events = Channel<StartExUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var pendingWallet: PendingWallet? = null
    private var runtimeWallet: LocalWallet? = null
    private var pendingTrustedAddress: PendingTrustedAddress? = null
    private var pendingTrustedAddressDeleteId: Long? = null
    private var pendingTrustedAddressUnlockId: Long? = null
    private var pendingRevealEnvelope: WalletEnvelope? = null
    private var pendingTransfer: PreparedSolTransfer? = null
    private var transferAuthenticationPending = false
    private var transferJob: Job? = null
    private var transferReconciliationJob: Job? = null
    private var walletDataStaleJob: Job? = null
    private var deviceHealthRefreshJob: Job? = null
    private var historyExportJob: Job? = null
    private var onboardingCompletionJob: Job? = null
    private val providerActionJobs = mutableMapOf<ProviderId, MutableSet<Job>>()
    private var lockAfterTransfer = false
    private var walletLockGeneration = 0L
    private var pendingSecurityRewrap: PendingSecurityRewrap? = null
    private var securityModeRequestPending = false
    private var pendingSellPositionId: String? = null
    private var mnemonicRevealGeneration = 0L
    private var pendingMnemonicRevealGeneration: Long? = null
    private var emergencyExitAuthenticationPending = false
    private var walletCreationAuthenticationPending = false
    private var pendingSecureSessionAction: PendingSecureSessionAction? = null
    private var pendingAuthenticationRequest: StartExUiEvent.Authenticate? = null
    private var walletSetupGeneration = 0L
    private var walletSaveAuthenticationPending = false
    private var walletSetupDispatcher: CoroutineDispatcher = Dispatchers.Default
    private var pendingDemoMode: Boolean? = null
    private var demoModeGeneration = 0L
    private var deviceHealthRefreshGeneration = 0L
    internal var deviceHealthSnapshot: suspend (Long?, Long?) -> DeviceHealthSnapshot =
        { providerRtt, lastEventAt -> app.deviceHealth.snapshot(providerRtt, lastEventAt) }
    internal var historyExportText: suspend (Boolean) -> String = { json ->
        if (json) app.repository.exportHistoryJson() else app.repository.exportHistoryCsv()
    }
    internal var providerReadOnlyTest: suspend (ProviderId) -> ProviderResult<*> = ::runProviderReadOnlyTest
    internal var restoreLocalWallet: (CharArray) -> LocalWallet = walletFactory::restore
    internal var prepareWalletEncryption: () -> PreparedCipherOperation = {
        AndroidKeystoreSecretCipher.secureSession().prepareEncryption()
    }

    internal constructor(
        application: Application,
        walletSetupDispatcher: CoroutineDispatcher,
    ) : this(application) {
        this.walletSetupDispatcher = walletSetupDispatcher
    }

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val ready =
                runCatching {
                    app.repository.expireStaleSessions(
                        heartbeatFreshnessPolicy.staleCutoffMillis(now),
                        now,
                    )
                }.isSuccess
            if (!ready) {
                _startupError.value = R.string.session_state_load_failed
                return@launch
            }
            combine(
                app.repository.observeActiveSession(ACTIVE_SESSION_STATES),
                app.repository.observeOpenPositions(OPEN_POSITION_STATES),
            ) { session, positions -> RuntimeState(session, positions, loaded = true) }
                .collectLatest { current -> runtime.value = current }
        }
        viewModelScope.launch {
            combine(
                app.repository.observeConfiguredProviderIds(),
                app.settings.settings,
            ) { providerIds, settings -> providerIds to settings.demoMode }
                .collectLatest { (providerIds, demoMode) ->
                    if (demoMode) {
                        CredentialProviders.forEach { provider -> app.sessionApiKeys.remove(provider) }
                        activatedProviders.value = emptySet()
                    } else {
                        hydrateSessionApiKeys(providerIds)
                    }
                }
        }
        viewModelScope.launch {
            combine(
                app.settings.settings,
                app.repository.observeWalletSecretEnvelopeAccessMode(),
            ) { settings, storedWalletAccessMode -> settings to persistedWalletAccessMode(storedWalletAccessMode) }
                .collectLatest { (settings, walletAccessMode) ->
                    if (pendingDemoMode == settings.demoMode) pendingDemoMode = null
                    val unattended = walletAccessMode == WalletAccessMode.UNATTENDED
                    if (settings.unattendedMode != unattended) {
                        runCatching { app.settings.setSecurityMode(unattended) }
                    }
                    if (settings.demoMode) {
                        transferReconciliationJob?.cancel()
                        lockWalletNow()
                    } else if (unattended && runtimeWallet == null) {
                        unlockUnattendedWalletOnStartup()
                    }
                }
        }
    }

    private val walletStorage =
        combine(
            app.repository.observeWalletProfile(),
            app.repository.observeWalletSecretEnvelopeAccessMode(),
        ) { wallet, accessMode -> wallet to accessMode }

    private val setup =
        combine(
            app.settings.settings,
            walletStorage,
            app.repository.observeProviderHealth(),
            app.repository.observeRisks(),
            app.repository.observeStrategies(),
        ) { settings, walletStorage, providers, risks, strategies ->
            SetupState(settings, walletStorage.first, walletStorage.second, providers, risks, strategies)
        }

    private val content =
        combine(
            app.repository.observeCandidates(CANDIDATE_STATES),
            app.repository.observeTrustedAddresses(),
            app.repository.observeRecentEvents(RECENT_EVENT_LIMIT),
            app.repository.observeConfiguredProviderIds(),
        ) { candidates, trustedAddresses, events, configuredProviders ->
            ContentState(candidates, trustedAddresses, events, configuredProviders)
        }

    private val ledger =
        combine(
            app.repository.observeDailyPerformance(),
            app.repository.observeTradeHistory(TRADE_HISTORY_LIMIT),
        ) { dailyPerformance, tradeHistory -> LedgerState(dailyPerformance, tradeHistory) }

    private val transient =
        combine(
            walletUnlocked,
            walletBalance,
            activatedProviders,
            message,
            walletData,
        ) { unlocked, balance, activated, currentMessage, chainData ->
            TransientState(unlocked, balance, activated, currentMessage, chainData)
        }

    private val baseState =
        combine(
            setup,
            runtime,
            content,
            transient,
            ledger,
        ) { setup, runtime, content, transient, ledger ->
            toState(setup, runtime, content, transient, ledger)
        }

    private val freshnessClock =
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(1_000)
            }
        }

    val state: StateFlow<PersistedAppState> =
        combine(baseState, deviceHealth, freshnessClock) { current, health, now ->
            current.withProviderFreshness(now).copy(deviceHealth = health)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PersistedAppState(),
        )

    fun completeOnboarding() {
        if (onboardingCompletionJob?.isActive == true) return
        onboardingCompletionJob =
            viewModelScope.launch {
                try {
                    if (!ensureDefaultConfiguration()) {
                        message.value = R.string.onboarding_save_failed
                        return@launch
                    }
                    app.settings.setOnboardingComplete(true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message.value = R.string.onboarding_save_failed
                }
            }
    }

    fun clearMessage() {
        message.value = null
    }

    fun refreshDeviceHealth(invalidateCurrent: Boolean = true) {
        val generation = ++deviceHealthRefreshGeneration
        deviceHealthRefreshJob?.cancel()
        if (invalidateCurrent) deviceHealth.value = null
        deviceHealthRefreshJob =
            viewModelScope.launch(walletSetupDispatcher) {
                val current = state.value
                val providerRtt =
                    current.providerHealth
                        .filter { it.latencyMillis != null }
                        .maxByOrNull { it.updatedAtMillis }
                        ?.latencyMillis
                val lastEventAt = current.events.maxOfOrNull { it.createdAtMillis }
                val snapshot =
                    try {
                        deviceHealthSnapshot(providerRtt, lastEventAt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                if (generation == deviceHealthRefreshGeneration) {
                    deviceHealth.value = snapshot
                }
            }
    }

    fun beginWalletCreation() {
        if (blockDemoAction()) return
        if (state.value.walletAddress != null) {
            message.value = R.string.wallet_replacement_unsupported
            return
        }
        if (walletCreationAuthenticationPending) return
        walletCreationAuthenticationPending = true
        if (!_events.trySend(authenticationRequest(AuthenticationPurpose.CreateWallet)).isSuccess) {
            walletCreationAuthenticationPending = false
            message.value = R.string.authentication_failed
        }
    }

    private fun createWalletAfterAuthentication() {
        if (!walletCreationAuthenticationPending) return
        walletCreationAuthenticationPending = false
        walletSetupGeneration += 1
        val generation = walletSetupGeneration
        clearPendingWallet()
        replaceWalletSetup(WalletSetupState.Working)
        viewModelScope.launch(walletSetupDispatcher) {
            publishCreatedWallet(generation, runCatching(::buildPendingWallet))
        }
    }

    private fun buildPendingWallet(): NewPendingWallet? {
        val created = walletFactory.create()
        var challenge: MnemonicBackupChallenge? = null
        var ownershipTransferred = false
        try {
            if (state.value.demoMode) return null
            val challengeMnemonic = created.mnemonic
            val createdChallenge =
                try {
                    MnemonicBackupChallenge.random(challengeMnemonic)
                } finally {
                    challengeMnemonic.fill('0')
                }
            challenge = createdChallenge
            return NewPendingWallet(created, createdChallenge).also {
                ownershipTransferred = true
            }
        } finally {
            if (!ownershipTransferred) {
                challenge?.close()
                created.close()
            }
        }
    }

    private suspend fun publishCreatedWallet(
        generation: Long,
        result: Result<NewPendingWallet?>,
    ) {
        // Wallet creation and restoration retain separate secret-lifecycle and error-state handling.
        // CPD-OFF
        val pending = result.getOrNull()
        var published = false
        try {
            withContext(Dispatchers.Main.immediate) {
                if (generation != walletSetupGeneration) return@withContext
                result
                    .onSuccess {
                        if (pending == null) return@onSuccess
                        pendingWallet = pending
                        published = true
                        replaceWalletSetup(
                            WalletSetupState.Mnemonic(
                                phrase = pending.mnemonic(),
                                publicAddress = pending.publicAddress,
                            ),
                        )
                    }.onFailure {
                        clearPendingWallet()
                        replaceWalletSetup(WalletSetupState.Closed)
                        message.value = R.string.wallet_create_failed
                    }
            }
        } finally {
            if (!published) pending?.close()
        }
        // CPD-ON
    }

    fun showBackupChallenge() {
        val pending = pendingWallet as? NewPendingWallet ?: return
        replaceWalletSetup(WalletSetupState.BackupChallenge(pending.challenge.wordNumbers))
    }

    fun verifyBackupChallenge(answers: Map<Int, String>) {
        val pending = pendingWallet as? NewPendingWallet ?: return
        val secretAnswers = answers.mapValues { (_, answer) -> answer.trim().toCharArray() }
        val correct =
            try {
                pending.challenge.verify(secretAnswers)
            } finally {
                secretAnswers.values.forEach { it.fill('0') }
            }
        replaceWalletSetup(
            if (correct) {
                WalletSetupState.ReviewWallet(pending.publicAddress, restored = false)
            } else {
                WalletSetupState.BackupChallenge(
                    wordNumbers = pending.challenge.wordNumbers,
                    error = R.string.backup_quiz_incorrect,
                )
            },
        )
    }

    fun beginWalletRestore() {
        if (blockDemoAction()) return
        walletSetupGeneration += 1
        walletSaveAuthenticationPending = false
        clearPendingWallet()
        replaceWalletSetup(WalletSetupState.RestoreInput())
    }

    fun previewRestoredWallet(phrase: CharArray) {
        if (blockDemoAction()) {
            phrase.fill('0')
            return
        }
        walletSetupGeneration += 1
        val generation = walletSetupGeneration
        clearPendingWallet()
        if (phrase.size > MAX_MNEMONIC_INPUT_CHAR_COUNT) {
            phrase.fill('0')
            replaceWalletSetup(WalletSetupState.RestoreInput(R.string.restore_phrase_invalid))
            return
        }
        replaceWalletSetup(WalletSetupState.Working)
        val input = phrase.copyOf()
        phrase.fill('0')
        val restoreJob =
            viewModelScope.launch(walletSetupDispatcher) {
                try {
                    val result =
                        runCatching {
                            val wallet = restoreLocalWallet(input)
                            if (state.value.demoMode) {
                                wallet.close()
                                return@runCatching null
                            }
                            RestoredPendingWallet(wallet, input)
                        }
                    val pending = result.getOrNull()
                    var published = false
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != walletSetupGeneration) return@withContext
                            result
                                .onSuccess {
                                    if (pending == null) return@onSuccess
                                    pendingWallet = pending
                                    published = true
                                    replaceWalletSetup(
                                        WalletSetupState.ReviewWallet(pending.publicAddress, restored = true),
                                    )
                                }.onFailure {
                                    clearPendingWallet()
                                    replaceWalletSetup(WalletSetupState.RestoreInput(R.string.restore_phrase_invalid))
                                }
                        }
                    } finally {
                        if (!published) pending?.close()
                    }
                } finally {
                    input.fill('0')
                }
            }
        restoreJob.invokeOnCompletion { input.fill('0') }
    }

    fun requestWalletSave(restoredBackupConfirmed: Boolean) {
        if (blockDemoAction()) return
        val pending = pendingWallet ?: return
        if (pending is RestoredPendingWallet && !restoredBackupConfirmed) return
        if (state.value.walletAddress?.let { it != pending.publicAddress } == true) {
            message.value = R.string.wallet_replacement_unsupported
            return
        }
        if (walletSaveAuthenticationPending) return
        walletSaveAuthenticationPending = true
        runCatching {
            prepareWalletEncryption()
        }.onSuccess { operation ->
            val sent =
                _events
                    .trySend(
                        authenticationRequest(
                            purpose = AuthenticationPurpose.SaveWallet,
                            operation = operation,
                            walletSetupGeneration = walletSetupGeneration,
                        ),
                    ).isSuccess
            if (!sent) {
                walletSaveAuthenticationPending = false
                message.value = R.string.authentication_failed
            }
        }.onFailure {
            walletSaveAuthenticationPending = false
            message.value = R.string.biometric_unavailable
        }
    }

    fun dismissWalletSetup() {
        walletSetupGeneration += 1
        walletSaveAuthenticationPending = false
        clearPendingWallet()
        replaceWalletSetup(WalletSetupState.Closed)
    }

    @Suppress("TooGenericExceptionCaught")
    fun requestWalletUnlock() {
        if (blockDemoAction()) return
        if (pendingSecureSessionAction != null) return
        pendingSecureSessionAction = PendingSecureSessionAction(AuthenticationPurpose.UnlockWallet)
        viewModelScope.launch {
            try {
                val stored =
                    loadWalletEnvelope() ?: run {
                        pendingSecureSessionAction = null
                        message.value = R.string.wallet_secret_missing
                        return@launch
                    }
                val cipher = cipherFor(stored.accessMode)
                val operation = cipher.prepareDecryption(stored.envelope)
                if (cipher.requiresBiometricAuthentication) {
                    _events.send(
                        authenticationRequest(
                            purpose = AuthenticationPurpose.UnlockWallet,
                            operation = operation,
                        ),
                    )
                } else {
                    pendingSecureSessionAction = null
                    unlockWallet(cipher, operation, stored.envelope)
                }
            } catch (cancelled: CancellationException) {
                pendingSecureSessionAction = null
                throw cancelled
            } catch (error: Exception) {
                pendingSecureSessionAction = null
                message.value = walletEnvelopeFailureMessage(classifySecretEnvelopeFailure(error))
            }
        }
    }

    fun lockWallet() {
        if (_walletTransfer.value == WalletTransferState.Submitting && transferJob?.isActive == true) {
            lockAfterTransfer = true
            return
        }
        lockWalletNow()
    }

    private fun lockWalletNow() {
        walletLockGeneration += 1
        invalidateMnemonicReveal()
        lockAfterTransfer = false
        dismissWalletOverlay()
        walletDataStaleJob?.cancel()
        walletBalance.value = WalletBalanceState()
        walletData.value = WalletDataState()
        runtimeWallet?.close()
        runtimeWallet = null
        walletAccess.lock()
        walletUnlocked.value = false
        if (
            _walletSetup.value !is WalletSetupState.Closed &&
            _walletSetup.value !is WalletSetupState.Saving
        ) {
            dismissWalletSetup()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun requestMnemonicReveal() {
        if (blockDemoAction()) return
        if (pendingMnemonicRevealGeneration != null) return
        val generation = ++mnemonicRevealGeneration
        pendingMnemonicRevealGeneration = generation
        viewModelScope.launch {
            try {
                val stored =
                    loadWalletEnvelope() ?: run {
                        clearPendingMnemonicReveal(generation)
                        message.value = R.string.wallet_secret_missing
                        return@launch
                    }
                val cipher = cipherFor(stored.accessMode)
                if (cipher.requiresBiometricAuthentication) {
                    val operation = cipher.prepareDecryption(stored.envelope)
                    _events.send(
                        authenticationRequest(
                            purpose = AuthenticationPurpose.RevealMnemonic,
                            operation = operation,
                        ),
                    )
                } else {
                    pendingRevealEnvelope = stored
                    _events.send(authenticationRequest(AuthenticationPurpose.RevealMnemonic))
                }
            } catch (cancelled: CancellationException) {
                clearPendingMnemonicReveal(generation)
                throw cancelled
            } catch (error: Exception) {
                clearPendingMnemonicReveal(generation)
                message.value = walletEnvelopeFailureMessage(classifySecretEnvelopeFailure(error))
            }
        }
    }

    private fun clearPendingMnemonicReveal(generation: Long) {
        if (pendingMnemonicRevealGeneration == generation) {
            pendingMnemonicRevealGeneration = null
        }
    }

    fun showWalletOverlay(overlay: WalletOverlay) {
        if (state.value.demoMode) {
            message.value = R.string.demo_action_unavailable
            return
        }
        if (overlay == WalletOverlay.Send) clearPendingTransfer()
        replaceWalletOverlay(overlay)
    }

    fun dismissWalletOverlay() {
        clearPendingTransfer()
        replaceWalletOverlay(WalletOverlay.None)
    }

    internal fun onActivityStopped() {
        invalidateMnemonicReveal()
        if (_walletSetup.value is WalletSetupState.Mnemonic) dismissWalletSetup()
        if (_walletOverlay.value is WalletOverlay.RevealedMnemonic) dismissWalletOverlay()
    }

    fun requestAddTrustedAddress(
        label: String,
        address: String,
        locked: Boolean,
    ) {
        if (blockDemoAction()) return
        if (label.isBlank()) {
            message.value = R.string.trusted_label_required
            return
        }
        if (SolanaAddressValidator().normalize(address) == null) {
            message.value = R.string.trusted_address_invalid
            return
        }
        pendingTrustedAddressDeleteId = null
        pendingTrustedAddressUnlockId = null
        pendingTrustedAddress = PendingTrustedAddress(label.trim(), address.trim(), locked)
        _events.trySend(authenticationRequest(AuthenticationPurpose.AddTrustedAddress))
    }

    fun requestDeleteTrustedAddress(
        id: Long,
        finalCharacters: String,
    ) {
        if (blockDemoAction()) return
        val address =
            state.value.trustedAddresses.firstOrNull { it.id == id }
                ?: run {
                    message.value = R.string.trusted_address_change_failed
                    return
                }
        if (address.isLocked && finalCharacters != address.address.takeLast(4)) {
            message.value = R.string.confirm_last_four_mismatch
            return
        }
        pendingTrustedAddress = null
        pendingTrustedAddressUnlockId = null
        pendingTrustedAddressDeleteId = id
        _events.trySend(authenticationRequest(AuthenticationPurpose.DeleteTrustedAddress))
    }

    fun requestUnlockTrustedAddress(
        id: Long,
        finalCharacters: String,
    ) {
        if (blockDemoAction()) return
        val address =
            state.value.trustedAddresses.firstOrNull { it.id == id && it.isLocked }
                ?: run {
                    message.value = R.string.trusted_address_change_failed
                    return
                }
        if (finalCharacters != address.address.takeLast(4)) {
            message.value = R.string.confirm_last_four_mismatch
            return
        }
        pendingTrustedAddress = null
        pendingTrustedAddressDeleteId = null
        pendingTrustedAddressUnlockId = id
        _events.trySend(authenticationRequest(AuthenticationPurpose.UnlockTrustedAddress))
    }

    fun prepareSolTransfer(
        trustedAddressId: Long,
        amountSol: String,
    ) {
        if (blockDemoAction()) {
            _walletTransfer.value = WalletTransferState.Failed(R.string.demo_action_unavailable)
            return
        }
        if (_walletTransfer.value != WalletTransferState.Editing || transferJob?.isActive == true) return
        val current = state.value
        val stateBlockMessage =
            manualTransferStateBlockMessage(
                current.monitorState,
                current.openPositions.isNotEmpty(),
            )
        if (stateBlockMessage != null) {
            _walletTransfer.value = WalletTransferState.Failed(stateBlockMessage)
            return
        }
        val destination =
            current.trustedAddresses.firstOrNull { it.id == trustedAddressId }
                ?: run {
                    _walletTransfer.value = WalletTransferState.Failed(R.string.trusted_address_invalid)
                    return
                }
        val amountLamports =
            amountSol.toLamportsOrNull()
                ?: run {
                    _walletTransfer.value = WalletTransferState.Failed(R.string.amount_invalid)
                    return
                }
        val sourceAddress = current.walletAddress
        val risk = current.risk
        if (!current.walletUnlocked || runtimeWallet == null || sourceAddress == null || risk == null) {
            _walletTransfer.value = WalletTransferState.Failed(R.string.transfer_preflight_failed)
            return
        }
        clearPendingTransfer()
        _walletTransfer.value = WalletTransferState.Preparing
        transferJob =
            viewModelScope.launch {
                prepareSolTransferAfterPreflight(sourceAddress, destination, amountLamports, risk)
            }
    }

    private suspend fun prepareSolTransferAfterPreflight(
        sourceAddress: String,
        destination: TrustedAddressEntity,
        amountLamports: Long,
        risk: RiskConfigEntity,
    ) {
        val reconciled =
            try {
                reconcileUnresolvedManualTransfer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                false
            }
        if (!reconciled) {
            if (transferReconciliationJob?.isActive != true) {
                _walletTransfer.value =
                    WalletTransferState.Failed(
                        R.string.transfer_unresolved_blocked,
                    )
            }
            return
        }
        when (
            val result =
                walletTransferCoordinator.prepare(
                    sourceAddress = sourceAddress,
                    destinationAddress = destination.address,
                    amountLamports = amountLamports,
                    feeCapLamports = risk.maximumTransactionCostLamports,
                    reserveLamports = risk.minimumWalletReserveLamports,
                )
        ) {
            is SolTransferResult.Prepared -> {
                pendingTransfer = result.transfer
                saveProviderHealth(ProviderId.HELIUS, System.currentTimeMillis(), null)
                _walletTransfer.value =
                    WalletTransferState.Review(
                        trustedAddressId = destination.id,
                        destinationLabel = destination.label,
                        destinationAddress = result.transfer.destinationAddress,
                        amountLamports = result.transfer.amountLamports,
                        estimatedFeeLamports = result.transfer.estimatedFeeLamports,
                        reserveLamports = risk.minimumWalletReserveLamports,
                        lastValidBlockHeight = result.transfer.lastValidBlockHeight,
                        requiresAddressVerification = destination.firstTransferVerifiedAtMillis == null,
                    )
            }

            is SolTransferResult.Failure -> {
                result.providerError?.let { saveProviderHealth(ProviderId.HELIUS, null, it) }
                _walletTransfer.value = WalletTransferState.Failed(transferFailureMessage(result.reason))
            }

            else -> {
                _walletTransfer.value = WalletTransferState.Failed(R.string.transfer_prepare_failed)
            }
        }
    }

    fun requestSolTransferSubmit(finalCharacters: String) {
        val review = _walletTransfer.value as? WalletTransferState.Review ?: return
        if (pendingTransfer == null || transferAuthenticationPending) return
        val current = state.value
        val stateBlockMessage =
            manualTransferStateBlockMessage(
                current.monitorState,
                current.openPositions.isNotEmpty(),
            )
        if (stateBlockMessage != null) {
            clearPendingTransfer()
            _walletTransfer.value = WalletTransferState.Failed(stateBlockMessage)
            return
        }
        if (
            review.requiresAddressVerification &&
            finalCharacters != review.destinationAddress.takeLast(4)
        ) {
            message.value = R.string.confirm_last_four_mismatch
            return
        }
        transferAuthenticationPending = true
        if (!_events.trySend(authenticationRequest(AuthenticationPurpose.SubmitTransfer)).isSuccess) {
            transferAuthenticationPending = false
            message.value = R.string.authentication_failed
        }
    }

    fun resetSolTransfer() {
        clearPendingTransfer()
    }

    fun saveProviderKey(
        provider: ProviderId,
        apiKey: CharArray,
    ) {
        if (blockDemoAction()) {
            apiKey.fill('0')
            return
        }
        require(provider in CredentialProviders) { "Provider does not accept a user API key" }
        if (apiKey.none { !it.isWhitespace() }) {
            apiKey.fill('0')
            message.value = R.string.provider_key_required
            return
        }
        val key = apiKey.copyOf()
        apiKey.fill('0')
        val job =
            launchProviderAction(provider) {
                val sessionKey = key.copyOf()
                try {
                    val encoded = WalletSecretCodec.encodeAndClear(key)
                    try {
                        val cipher = AndroidKeystoreSecretCipher.unattended()
                        val envelope =
                            cipher.encrypt(
                                operation = cipher.prepareEncryption(),
                                secret = encoded,
                                publicAddress = providerEnvelopeId(provider),
                            )
                        val ciphertext = envelope.ciphertext
                        val iv = envelope.iv
                        try {
                            app.repository.saveProviderCredential(
                                ProviderCredentialEntity(
                                    providerId = provider.name,
                                    encryptedApiKey = ciphertext,
                                    apiKeyIv = iv,
                                    secretEnvelopeVersion = envelope.version,
                                    keystoreAccessMode = KeystoreAccessMode.UNATTENDED.name,
                                    updatedAtMillis = System.currentTimeMillis(),
                                ),
                            )
                        } finally {
                            ciphertext.clearSecret()
                            iv.clearSecret()
                        }
                    } finally {
                        encoded.clearSecret()
                    }
                    app.sessionApiKeys.put(provider, sessionKey)
                    activatedProviders.value = activatedProviders.value + provider
                    message.value = R.string.provider_key_saved
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    app.sessionApiKeys.remove(provider)
                    activatedProviders.value = activatedProviders.value - provider
                    message.value = R.string.provider_key_save_failed
                } finally {
                    sessionKey.fill('0')
                }
            }
        job.invokeOnCompletion { key.fill('0') }
    }

    fun removeProviderKey(provider: ProviderId) {
        if (blockDemoAction()) return
        require(provider in CredentialProviders) { "Provider does not accept a user API key" }
        launchProviderAction(provider) {
            app.sessionApiKeys.remove(provider)
            activatedProviders.value = activatedProviders.value - provider
            try {
                app.repository.deleteProviderCredential(provider.name)
                message.value = R.string.provider_key_removed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value = R.string.provider_key_remove_failed
            }
        }
    }

    fun setDemoMode(enabled: Boolean) {
        val current = state.value
        if ((pendingDemoMode ?: state.value.demoMode) == enabled) return
        if (enabled && current.monitorState != MonitorState.Stopped) {
            message.value = R.string.demo_requires_stopped_monitoring
            return
        }
        val generation = ++demoModeGeneration
        pendingDemoMode = enabled
        viewModelScope.launch {
            demoModeMutex.withLock {
                applyDemoModeChange(generation, enabled)
            }
        }
    }

    private suspend fun applyDemoModeChange(
        generation: Long,
        enabled: Boolean,
    ) {
        if (generation != demoModeGeneration) return
        try {
            if (enabled) cancelProviderActions()
            app.settings.setDemoMode(enabled)
            if (enabled) {
                transferReconciliationJob?.cancel()
                lockWalletNow()
                app.sessionApiKeys.clear()
                activatedProviders.value = emptySet()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == demoModeGeneration) {
                pendingDemoMode = null
                message.value = R.string.demo_change_failed
            }
            return
        }
        if (generation != demoModeGeneration) return
        walletBalance.value = WalletBalanceState()
        walletDataStaleJob?.cancel()
        walletData.value = WalletDataState()
        message.value = if (enabled) R.string.demo_enabled else R.string.demo_disabled
        if (!enabled) unlockUnattendedWalletOnStartup()
    }

    @Suppress("ThrowsCount")
    fun requestSecurityMode(
        unattended: Boolean,
        dedicatedWalletAcknowledged: Boolean,
        reducedSecurityAcknowledged: Boolean,
    ) {
        if (blockDemoAction() || securityModeRequestPending || pendingSecurityRewrap != null) return
        val current = state.value
        if (current.unattendedMode == unattended) return
        if (current.monitorState != MonitorState.Stopped) {
            message.value = R.string.security_mode_requires_stopped_monitoring
            return
        }
        if (
            unattended &&
            !canEnableUnattendedMode(
                walletConfigured = current.walletAddress != null,
                risk = current.risk,
                monitorState = current.monitorState,
                dedicatedWalletAcknowledged = dedicatedWalletAcknowledged,
                reducedSecurityAcknowledged = reducedSecurityAcknowledged,
            )
        ) {
            message.value = R.string.unattended_requirements_missing
            return
        }
        securityModeRequestPending = true
        viewModelScope.launch {
            completeSecurityModeRequest(unattended)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun completeSecurityModeRequest(unattended: Boolean) {
        try {
            val (rewrap, operation) = prepareSecurityModeRewrap(unattended)
            pendingSecurityRewrap = rewrap
            _events.send(
                authenticationRequest(
                    purpose = AuthenticationPurpose.ChangeSecurityMode,
                    operation = operation,
                ),
            )
        } catch (cancelled: CancellationException) {
            pendingSecurityRewrap = null
            throw cancelled
        } catch (error: Exception) {
            pendingSecurityRewrap = null
            val failure = classifySecretEnvelopeFailure(error)
            message.value =
                if (failure == SecretEnvelopeFailure.UNKNOWN) {
                    R.string.security_mode_change_failed
                } else {
                    walletEnvelopeFailureMessage(failure)
                }
        } finally {
            securityModeRequestPending = false
        }
    }

    private suspend fun prepareSecurityModeRewrap(unattended: Boolean): Pair<PendingSecurityRewrap, PreparedCipherOperation> {
        val profile = loadWalletProfileForSecurityMode()
        val stored = loadWalletEnvelope() ?: error("Wallet envelope is missing")
        val targetMode =
            if (unattended) {
                KeystoreAccessMode.UNATTENDED
            } else {
                KeystoreAccessMode.BIOMETRIC_EACH_USE
            }
        require(stored.accessMode != targetMode)
        val operation =
            if (stored.accessMode == KeystoreAccessMode.BIOMETRIC_EACH_USE) {
                AndroidKeystoreSecretCipher.secureSession().prepareDecryption(stored.envelope)
            } else {
                AndroidKeystoreSecretCipher.secureSession().prepareEncryption()
            }
        return PendingSecurityRewrap(profile, stored, targetMode) to operation
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadWalletProfileForSecurityMode(): WalletProfileEntity =
        try {
            app.repository.walletProfile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SecretEnvelopeDatabaseException(error)
        } ?: error("Wallet profile is missing")

    fun refreshWalletBalance() {
        val current = state.value
        if (current.demoMode || !current.walletUnlocked) return
        val address = current.walletAddress ?: return
        val expectedLockGeneration = walletLockGeneration
        if (ProviderId.HELIUS !in current.activatedProviders) {
            walletBalance.value = WalletBalanceState(error = R.string.balance_requires_active_helius)
            return
        }
        if (walletBalance.value.loading) return
        walletBalance.value = walletBalance.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = app.heliusRpc.getBalance(address)
            if (!hasWalletDataAccess(address, expectedLockGeneration)) return@launch
            when (result) {
                is ProviderResult.Success -> {
                    walletBalance.value =
                        WalletBalanceState(
                            lamports = result.value.lamports,
                            slot = result.value.slot,
                        )
                    saveProviderHealth(ProviderId.HELIUS, result.receivedAtMillis, null)
                    if (hasWalletDataAccess(address, expectedLockGeneration)) {
                        refreshFiatBalance(
                            lamports = result.value.lamports,
                            slot = result.value.slot,
                            address = address,
                            expectedLockGeneration = expectedLockGeneration,
                        )
                    }
                }

                is ProviderResult.Failure -> {
                    walletBalance.value = WalletBalanceState(error = R.string.balance_refresh_failed)
                    saveProviderHealth(ProviderId.HELIUS, null, result.error)
                }
            }
        }
    }

    fun refreshWalletData() {
        if (state.value.demoMode) return
        refreshWalletBalance()
        refreshWalletChainData()
    }

    private fun refreshWalletChainData() {
        val current = state.value
        if (!current.walletUnlocked) return
        val address = current.walletAddress ?: return
        val expectedLockGeneration = walletLockGeneration
        if (ProviderId.HELIUS !in current.activatedProviders) {
            walletData.value =
                walletData.value.copy(
                    loading = false,
                    error = R.string.wallet_data_requires_active_helius,
                    stale = walletData.value.updatedAtMillis != null,
                )
            return
        }
        if (walletData.value.loading) return
        walletData.value = walletData.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val (holdingsResult, activityResult) =
                coroutineScope {
                    val holdings = async { app.heliusRpc.getTokenHoldings(address) }
                    val activity = async { app.heliusRpc.getSignaturesForAddress(address) }
                    holdings.await() to activity.await()
                }
            if (!hasWalletDataAccess(address, expectedLockGeneration)) return@launch
            if (
                holdingsResult is ProviderResult.Success &&
                activityResult is ProviderResult.Success
            ) {
                val updatedAt =
                    maxOf(
                        holdingsResult.receivedAtMillis,
                        activityResult.receivedAtMillis,
                    )
                walletData.value =
                    WalletDataState(
                        holdings = holdingsResult.value.holdings.map(RpcTokenHolding::toUiHolding),
                        holdingsSlot = holdingsResult.value.slot,
                        activity = activityResult.value.map(RpcAddressSignature::toUiActivity),
                        updatedAtMillis = updatedAt,
                    )
                scheduleWalletDataStaleness(updatedAt)
                runCatching { saveProviderHealth(ProviderId.HELIUS, updatedAt, null) }
            } else {
                val error =
                    (holdingsResult as? ProviderResult.Failure)?.error
                        ?: (activityResult as? ProviderResult.Failure)?.error
                walletData.value =
                    walletData.value.copy(
                        loading = false,
                        error = R.string.wallet_data_refresh_failed,
                        stale = walletData.value.updatedAtMillis != null,
                    )
                if (error != null) {
                    runCatching { saveProviderHealth(ProviderId.HELIUS, null, error) }
                }
            }
        }
    }

    private fun scheduleWalletDataStaleness(updatedAtMillis: Long) {
        walletDataStaleJob?.cancel()
        walletDataStaleJob =
            viewModelScope.launch {
                delay(WALLET_DATA_STALE_MILLIS)
                if (walletData.value.updatedAtMillis == updatedAtMillis) {
                    walletData.value = walletData.value.copy(stale = true)
                }
            }
    }

    fun testProvider(provider: ProviderId) {
        if (blockDemoAction()) return
        if (provider == ProviderId.PUMP_PORTAL) {
            message.value = R.string.provider_pump_test_at_monitor_start
            return
        }
        launchProviderAction(provider) {
            _providerTestsInProgress.update { it + provider }
            try {
                val result = providerReadOnlyTest(provider)
                if (state.value.demoMode) return@launchProviderAction
                when (result) {
                    is ProviderResult.Success -> {
                        saveProviderHealth(provider, result.receivedAtMillis, null)
                        message.value = R.string.provider_test_succeeded
                    }

                    is ProviderResult.Failure -> {
                        saveProviderHealth(provider, null, result.error)
                        message.value = R.string.provider_test_failed
                    }
                }
            } finally {
                _providerTestsInProgress.update { it - provider }
            }
        }
    }

    private suspend fun runProviderReadOnlyTest(provider: ProviderId): ProviderResult<*> =
        when (provider) {
            ProviderId.HELIUS -> {
                app.heliusRpc.getLatestBlockhash()
            }

            ProviderId.JUPITER -> {
                app.jupiterSwap.order(
                    SwapOrderRequest(
                        inputMint = WRAPPED_SOL_MINT,
                        outputMint = USDC_MINT,
                        amountAtomic = JUPITER_TEST_LAMPORTS,
                    ),
                )
            }

            ProviderId.KRAKEN -> {
                app.fiatRates.solEurRate()
            }

            ProviderId.PUMP_PORTAL -> {
                error("PumpPortal has no standalone read-only test")
            }
        }

    fun resetConfigurationSaveState() {
        if (_configurationSave.value !is ConfigurationSaveState.Saving) {
            _configurationSave.value = ConfigurationSaveState.Idle
        }
    }

    fun saveConfiguration(
        riskInput: RiskConfigurationInput,
        strategyInput: StrategyConfigurationInput,
    ) {
        if (blockDemoAction()) {
            _configurationSave.value = ConfigurationSaveState.Failed
            return
        }
        if (_configurationSave.value is ConfigurationSaveState.Saving) return
        val currentState = state.value
        if (!currentState.configurationEditingAllowed) {
            _configurationSave.value = ConfigurationSaveState.SessionActive
            message.value = R.string.configuration_session_active
            return
        }
        val currentRisk = currentState.risk
        val currentStrategy = currentState.strategy
        if (currentRisk == null || currentStrategy == null) {
            _configurationSave.value = ConfigurationSaveState.Failed
            return
        }
        val now = System.currentTimeMillis()
        val editedRisk =
            when (val result = ConfigurationEditor.editRisk(currentRisk, riskInput, now)) {
                is ConfigurationEditResult.Invalid -> {
                    _configurationSave.value = ConfigurationSaveState.Invalid(result.error)
                    return
                }

                is ConfigurationEditResult.Updated -> {
                    result.entity
                }
            }
        val editedStrategy =
            when (
                val result = ConfigurationEditor.editStrategy(currentStrategy, strategyInput, now)
            ) {
                is ConfigurationEditResult.Invalid -> {
                    _configurationSave.value = ConfigurationSaveState.Invalid(result.error)
                    return
                }

                is ConfigurationEditResult.Updated -> {
                    result.entity
                }
            }
        _configurationSave.value = ConfigurationSaveState.Saving
        viewModelScope.launch {
            runCatching {
                app.repository.saveConfig(editedStrategy, editedRisk)
            }.onSuccess { saved ->
                if (saved) {
                    _configurationSave.value = ConfigurationSaveState.Saved
                    message.value = R.string.configuration_saved
                } else {
                    _configurationSave.value = ConfigurationSaveState.SessionActive
                    message.value = R.string.configuration_session_active
                }
            }.onFailure {
                _configurationSave.value = ConfigurationSaveState.Failed
            }
        }
    }

    fun startMonitoring() {
        if (blockDemoAction()) return
        viewModelScope.launch {
            ensureDefaultConfiguration()
            val current = state.value
            if (
                current.demoMode ||
                current.mode != TradingMode.Paper ||
                current.monitorState != MonitorState.Stopped
            ) {
                return@launch
            }
            _events.send(StartExUiEvent.StartMonitoringService)
        }
    }

    internal fun onMonitoringServiceStartFailed() {
        message.value = R.string.monitoring_start_failed
    }

    fun pauseMonitoring() {
        if (state.value.demoMode) return
        if (state.value.monitorState == MonitorState.Running) {
            _events.trySend(StartExUiEvent.PauseMonitoringService)
        }
    }

    fun resumeMonitoring() {
        if (state.value.demoMode) return
        if (state.value.monitorState == MonitorState.Paused) {
            if (state.value.secureSession) {
                requestMonitoringRecoveryAuthentication()
            } else {
                _events.trySend(StartExUiEvent.ResumeMonitoringService)
            }
        }
    }

    fun stopMonitoring() {
        if (state.value.demoMode) return
        _events.trySend(StartExUiEvent.StopMonitoringService)
    }

    fun recoverMonitoring() {
        if (state.value.demoMode) return
        if (state.value.monitorState != MonitorState.NeedsAttention) return
        requestMonitoringRecoveryAuthentication()
    }

    private fun requestMonitoringRecoveryAuthentication() {
        if (pendingSecureSessionAction != null) return
        val sessionId = runtime.value.session?.id ?: return
        pendingSecureSessionAction =
            PendingSecureSessionAction(
                purpose = AuthenticationPurpose.RecoverMonitoring,
                sessionId = sessionId,
            )
        if (!_events.trySend(authenticationRequest(AuthenticationPurpose.RecoverMonitoring)).isSuccess) {
            pendingSecureSessionAction = null
            message.value = R.string.authentication_failed
        }
    }

    fun requestAuthenticatedMonitoringStop() {
        if (state.value.demoMode || state.value.monitorState != MonitorState.NeedsAttention) return
        if (pendingSecureSessionAction != null) return
        val sessionId = runtime.value.session?.id ?: return
        pendingSecureSessionAction =
            PendingSecureSessionAction(
                purpose = AuthenticationPurpose.StopMonitoring,
                sessionId = sessionId,
            )
        if (!_events.trySend(authenticationRequest(AuthenticationPurpose.StopMonitoring)).isSuccess) {
            pendingSecureSessionAction = null
            message.value = R.string.authentication_failed
        }
    }

    fun requestSellNow(positionId: String) {
        if (pendingSellPositionId != null) return
        val position = state.value.openPositions.firstOrNull { it.id == positionId }
        if (!canRequestSellNow(position, state.value.monitorState, state.value.demoMode)) {
            message.value =
                if (state.value.demoMode) {
                    R.string.demo_action_unavailable
                } else {
                    R.string.sell_now_unavailable
                }
            return
        }
        pendingSellPositionId = positionId
        if (!_events.trySend(authenticationRequest(AuthenticationPurpose.SellNow)).isSuccess) {
            pendingSellPositionId = null
            message.value = R.string.authentication_failed
        }
    }

    fun requestStopAfterClose() {
        val current = state.value
        if (!canRequestStopAfterClose(current.openPositions, current.monitorState, current.demoMode)) {
            message.value =
                if (current.demoMode) {
                    R.string.demo_action_unavailable
                } else {
                    R.string.stop_after_close_unavailable
                }
            return
        }
        _events.trySend(StartExUiEvent.StopAfterClose)
    }

    fun requestEmergencyExit() {
        if (emergencyExitAuthenticationPending) return
        if (!canRequestEmergencyExit(state.value.openPositions, state.value.monitorState, state.value.demoMode)) {
            message.value =
                if (state.value.demoMode) {
                    R.string.demo_action_unavailable
                } else {
                    R.string.emergency_exit_unavailable
                }
            return
        }
        emergencyExitAuthenticationPending = true
        if (!_events.trySend(authenticationRequest(AuthenticationPurpose.EmergencyExit)).isSuccess) {
            emergencyExitAuthenticationPending = false
            message.value = R.string.authentication_failed
        }
    }

    fun exportHistory(json: Boolean) {
        if (blockDemoAction()) return
        if (historyExportJob != null) return
        val expectedDemoModeGeneration = demoModeGeneration
        historyExportJob =
            viewModelScope.launch {
                try {
                    val text = historyExportText(json)
                    if (
                        expectedDemoModeGeneration != demoModeGeneration ||
                        !historyExportAllowed
                    ) {
                        return@launch
                    }
                    _events.send(
                        StartExUiEvent.ShareText(
                            title = if (json) "StartEx history JSON" else "StartEx history CSV",
                            text = text,
                            mimeType = if (json) "application/json" else "text/csv",
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message.value = R.string.history_export_failed
                } finally {
                    historyExportJob = null
                }
            }
    }

    internal fun onHistoryShareFailed() {
        message.value = R.string.history_share_failed
    }

    internal val historyExportAllowed: Boolean
        get() = !demoModeActiveOrPending()

    internal val authenticationInProgress: Boolean
        get() = pendingAuthenticationRequest != null

    internal fun onAuthenticationPromptStarted(request: StartExUiEvent.Authenticate): Boolean {
        if (!isCurrentWalletSaveRequest(request)) return false
        pendingAuthenticationRequest = request
        return true
    }

    internal fun takePendingAuthenticationRequest(): StartExUiEvent.Authenticate? =
        pendingAuthenticationRequest.also { pendingAuthenticationRequest = null }

    fun onAuthenticationSucceeded(request: StartExUiEvent.Authenticate) {
        if (rejectAuthenticationForDemoChange(request)) return
        when (request.purpose) {
            AuthenticationPurpose.CreateWallet -> {
                createWalletAfterAuthentication()
            }

            AuthenticationPurpose.SaveWallet -> {
                if (!isCurrentWalletSaveRequest(request)) return
                walletSaveAuthenticationPending = false
                request.operation?.let(::persistPendingWallet)
            }

            AuthenticationPurpose.UnlockWallet -> {
                if (pendingSecureSessionAction?.purpose != AuthenticationPurpose.UnlockWallet) return
                pendingSecureSessionAction = null
                request.operation?.let(::completeSecureUnlock)
            }

            AuthenticationPurpose.RevealMnemonic -> {
                completeMnemonicReveal(request.operation)
            }

            AuthenticationPurpose.AddTrustedAddress -> {
                addPendingTrustedAddress()
            }

            AuthenticationPurpose.DeleteTrustedAddress -> {
                deletePendingTrustedAddress()
            }

            AuthenticationPurpose.UnlockTrustedAddress -> {
                unlockPendingTrustedAddress()
            }

            AuthenticationPurpose.SubmitTransfer -> {
                transferAuthenticationPending = false
                submitPendingTransfer()
            }

            AuthenticationPurpose.ChangeSecurityMode -> {
                request.operation?.let(::completeSecurityModeChange)
            }

            AuthenticationPurpose.SellNow -> {
                completeSellNowAuthentication()
            }

            AuthenticationPurpose.EmergencyExit -> {
                completeEmergencyExitAuthentication()
            }

            AuthenticationPurpose.RecoverMonitoring -> {
                completeMonitoringRecoveryAuthentication()
            }

            AuthenticationPurpose.StopMonitoring -> {
                completeMonitoringStopAuthentication()
            }
        }
    }

    private fun rejectAuthenticationForDemoChange(request: StartExUiEvent.Authenticate): Boolean {
        val demoModeActive = demoModeActiveOrPending()
        if (request.demoModeGeneration == demoModeGeneration && !demoModeActive) return false
        onAuthenticationFailed(request)
        message.value =
            if (demoModeActive) {
                R.string.demo_action_unavailable
            } else {
                R.string.demo_state_changed
            }
        return true
    }

    private fun completeSellNowAuthentication() {
        val positionId = pendingSellPositionId ?: return
        pendingSellPositionId = null
        val position = state.value.openPositions.firstOrNull { it.id == positionId }
        if (!canRequestSellNow(position, state.value.monitorState, state.value.demoMode)) {
            message.value = R.string.sell_now_unavailable
            return
        }
        walletAccess.onAuthenticationSucceeded()
        _events.trySend(StartExUiEvent.SellNow(positionId))
    }

    private fun completeEmergencyExitAuthentication() {
        if (!emergencyExitAuthenticationPending) return
        emergencyExitAuthenticationPending = false
        if (!canRequestEmergencyExit(
                state.value.openPositions,
                state.value.monitorState,
                state.value.demoMode,
            )
        ) {
            message.value = R.string.emergency_exit_unavailable
            return
        }
        walletAccess.onAuthenticationSucceeded()
        _events.trySend(StartExUiEvent.EmergencyExit)
    }

    private fun completeMonitoringRecoveryAuthentication() {
        val pending = pendingSecureSessionAction
        if (pending?.purpose != AuthenticationPurpose.RecoverMonitoring) return
        pendingSecureSessionAction = null
        val current = runtime.value.session
        if (
            current?.id != pending.sessionId ||
            projectedMonitorState(demo = false, session = current) !in
            setOf(MonitorState.Paused, MonitorState.NeedsAttention)
        ) {
            message.value = R.string.monitoring_state_changed
            return
        }
        _events.trySend(StartExUiEvent.RecoverMonitoringService(requireNotNull(pending.sessionId)))
    }

    private fun completeMonitoringStopAuthentication() {
        val pending = pendingSecureSessionAction
        if (pending?.purpose != AuthenticationPurpose.StopMonitoring) return
        pendingSecureSessionAction = null
        val current = runtime.value.session
        if (
            current?.id == pending.sessionId &&
            projectedMonitorState(demo = false, session = current) == MonitorState.NeedsAttention
        ) {
            _events.trySend(StartExUiEvent.StopAuthenticatedMonitoringService(requireNotNull(pending.sessionId)))
        } else {
            message.value = R.string.monitoring_state_changed
        }
    }

    fun onAuthenticationFailed(
        request: StartExUiEvent.Authenticate,
        @StringRes failureMessage: Int = R.string.authentication_failed,
    ) {
        if (request.purpose == AuthenticationPurpose.SaveWallet && !isCurrentWalletSaveRequest(request)) return
        val discardWalletSetup = request.purpose == AuthenticationPurpose.SaveWallet
        pendingAuthenticationRequest = null
        walletAccess.onAuthenticationRejected()
        walletCreationAuthenticationPending = false
        pendingTrustedAddress = null
        pendingTrustedAddressDeleteId = null
        pendingTrustedAddressUnlockId = null
        invalidateMnemonicReveal()
        pendingSecurityRewrap = null
        pendingSellPositionId = null
        emergencyExitAuthenticationPending = false
        transferAuthenticationPending = false
        pendingSecureSessionAction = null
        if (discardWalletSetup) dismissWalletSetup()
        message.value = failureMessage
    }

    private fun persistPendingWallet(operation: PreparedCipherOperation) {
        val pending = pendingWallet ?: return
        replaceWalletSetup(WalletSetupState.Saving)
        viewModelScope.launch {
            runCatching {
                val phrase = pending.mnemonic()
                val encoded = WalletSecretCodec.encodeAndClear(phrase)
                try {
                    val cipher = AndroidKeystoreSecretCipher.secureSession()
                    val envelope = cipher.encrypt(operation, encoded, pending.publicAddress)
                    val ciphertext = envelope.ciphertext
                    val iv = envelope.iv
                    try {
                        val now = System.currentTimeMillis()
                        app.repository.saveWallet(
                            profile =
                                WalletProfileEntity(
                                    publicAddress = pending.publicAddress,
                                    derivationPath = SolanaWalletDerivationPath.VALUE,
                                    createdAtMillis = now,
                                    backupConfirmedAtMillis = now,
                                ),
                            envelope =
                                WalletSecretEnvelopeEntity(
                                    walletProfileId = WALLET_PROFILE_ID,
                                    encryptedSecret = ciphertext,
                                    secretIv = iv,
                                    secretEnvelopeVersion = envelope.version,
                                    keystoreAccessMode = KeystoreAccessMode.BIOMETRIC_EACH_USE.name,
                                    updatedAtMillis = now,
                                ),
                        )
                        runCatching { app.settings.setSecurityMode(unattended = false) }
                    } finally {
                        ciphertext.clearSecret()
                        iv.clearSecret()
                    }
                } finally {
                    encoded.clearSecret()
                }
            }.onSuccess {
                clearPendingWallet()
                walletBalance.value = WalletBalanceState()
                walletDataStaleJob?.cancel()
                walletData.value = WalletDataState()
                replaceWalletSetup(WalletSetupState.Closed)
                message.value = R.string.wallet_saved_locked
            }.onFailure {
                replaceWalletSetup(
                    WalletSetupState.ReviewWallet(
                        publicAddress = pending.publicAddress,
                        restored = pending is RestoredPendingWallet,
                    ),
                )
                message.value = R.string.wallet_save_failed
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun completeSecureUnlock(operation: PreparedCipherOperation) {
        val expectedLockGeneration = walletLockGeneration
        viewModelScope.launch {
            try {
                val stored =
                    loadWalletEnvelope() ?: run {
                        message.value = R.string.wallet_secret_missing
                        return@launch
                    }
                if (stored.accessMode != KeystoreAccessMode.BIOMETRIC_EACH_USE) return@launch
                unlockWallet(
                    cipher = AndroidKeystoreSecretCipher.secureSession(),
                    operation = operation,
                    envelope = stored.envelope,
                    expectedLockGeneration = expectedLockGeneration,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                walletAccess.onAuthenticationRejected()
                walletUnlocked.value = false
                message.value = walletEnvelopeFailureMessage(classifySecretEnvelopeFailure(error))
            }
        }
    }

    private fun completeSecurityModeChange(operation: PreparedCipherOperation) {
        val pending = pendingSecurityRewrap ?: return
        pendingSecurityRewrap = null
        pendingSellPositionId = null
        viewModelScope.launch {
            val failure = rewrapWallet(pending, operation)
            if (failure != null) {
                message.value =
                    if (failure == SecretEnvelopeFailure.UNKNOWN) {
                        R.string.security_mode_change_failed
                    } else {
                        walletEnvelopeFailureMessage(failure)
                    }
                return@launch
            }
            if (pending.targetMode == KeystoreAccessMode.UNATTENDED) {
                unlockUnattendedWalletOnStartup()
                message.value = R.string.unattended_enabled
            } else {
                lockWalletNow()
                message.value = R.string.secure_session_enabled
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun rewrapWallet(
        pending: PendingSecurityRewrap,
        authenticatedOperation: PreparedCipherOperation,
    ): SecretEnvelopeFailure? =
        walletModeMutex.withLock {
            try {
                val sourceCipher = cipherFor(pending.source.accessMode)
                val decryptOperation =
                    if (pending.source.accessMode == KeystoreAccessMode.BIOMETRIC_EACH_USE) {
                        authenticatedOperation
                    } else {
                        sourceCipher.prepareDecryption(pending.source.envelope)
                    }
                val encoded = sourceCipher.decrypt(decryptOperation, pending.source.envelope)
                try {
                    persistRewrappedWallet(pending, authenticatedOperation, encoded)
                } finally {
                    encoded.clearSecret()
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                classifySecretEnvelopeFailure(error)
            }
        }

    private suspend fun persistRewrappedWallet(
        pending: PendingSecurityRewrap,
        authenticatedOperation: PreparedCipherOperation,
        encoded: ByteArray,
    ) {
        val targetCipher = cipherFor(pending.targetMode)
        val encryptOperation =
            if (pending.targetMode == KeystoreAccessMode.BIOMETRIC_EACH_USE) {
                authenticatedOperation
            } else {
                targetCipher.prepareEncryption()
            }
        val wrapped = targetCipher.encrypt(encryptOperation, encoded, pending.profile.publicAddress)
        val ciphertext = wrapped.ciphertext
        val iv = wrapped.iv
        try {
            app.repository.saveWallet(
                pending.profile,
                WalletSecretEnvelopeEntity(
                    walletProfileId = pending.profile.id,
                    encryptedSecret = ciphertext,
                    secretIv = iv,
                    secretEnvelopeVersion = wrapped.version,
                    keystoreAccessMode = pending.targetMode.name,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            runCatching {
                app.settings.setSecurityMode(
                    unattended = pending.targetMode == KeystoreAccessMode.UNATTENDED,
                )
            }
        } finally {
            ciphertext.clearSecret()
            iv.clearSecret()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun unlockUnattendedWalletOnStartup() {
        if (state.value.demoMode || runtimeWallet != null) return
        walletModeMutex.withLock {
            if (runtimeWallet != null) return@withLock
            try {
                val stored =
                    loadWalletEnvelope() ?: run {
                        message.value = R.string.wallet_secret_missing
                        return@withLock
                    }
                if (stored.accessMode != KeystoreAccessMode.UNATTENDED) return@withLock
                val cipher = AndroidKeystoreSecretCipher.unattended()
                unlockWallet(
                    cipher = cipher,
                    operation = cipher.prepareDecryption(stored.envelope),
                    envelope = stored.envelope,
                    announce = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                walletUnlocked.value = false
                message.value = walletEnvelopeFailureMessage(classifySecretEnvelopeFailure(error))
            }
        }
    }

    private suspend fun unlockWallet(
        cipher: AndroidKeystoreSecretCipher,
        operation: PreparedCipherOperation,
        envelope: SecretEnvelope,
        announce: Boolean = true,
        expectedLockGeneration: Long = walletLockGeneration,
    ) {
        runCatching {
            val encoded = cipher.decrypt(operation, envelope)
            val phrase = decodeStoredWalletSecret(encoded)
            restoreWalletForUnlock(phrase)
        }.onSuccess { wallet ->
            if (state.value.demoMode || walletLockGeneration != expectedLockGeneration) {
                wallet.close()
                return@onSuccess
            }
            runtimeWallet?.close()
            runtimeWallet = wallet
            walletAccess.onAuthenticationSucceeded()
            walletUnlocked.value = true
            if (announce) message.value = R.string.wallet_unlocked
        }.onFailure { error ->
            walletAccess.onAuthenticationRejected()
            walletUnlocked.value = false
            message.value = walletEnvelopeFailureMessage(classifySecretEnvelopeFailure(error))
        }
    }

    internal suspend fun restoreWalletForUnlock(phrase: CharArray): LocalWallet {
        var unclaimedWallet: LocalWallet? = null
        return try {
            withContext(walletSetupDispatcher) {
                restoreLocalWallet(phrase).also { unclaimedWallet = it }
            }.also { unclaimedWallet = null }
        } finally {
            unclaimedWallet?.close()
            phrase.fill('0')
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun completeMnemonicReveal(operation: PreparedCipherOperation?) {
        val generation = pendingMnemonicRevealGeneration ?: return
        pendingMnemonicRevealGeneration = null
        viewModelScope.launch {
            try {
                val stored =
                    pendingRevealEnvelope ?: loadWalletEnvelope() ?: run {
                        message.value = R.string.wallet_secret_missing
                        return@launch
                    }
                pendingRevealEnvelope = null
                val cipher = cipherFor(stored.accessMode)
                val decryptOperation = operation ?: cipher.prepareDecryption(stored.envelope)
                val encoded = cipher.decrypt(decryptOperation, stored.envelope)
                val phrase = decodeStoredWalletSecret(encoded)
                if (generation == mnemonicRevealGeneration && walletUnlocked.value) {
                    replaceWalletOverlay(WalletOverlay.RevealedMnemonic(phrase))
                } else {
                    phrase.fill('0')
                }
            } catch (cancelled: CancellationException) {
                pendingRevealEnvelope = null
                throw cancelled
            } catch (error: Exception) {
                pendingRevealEnvelope = null
                message.value = walletEnvelopeFailureMessage(classifySecretEnvelopeFailure(error))
            }
        }
    }

    private fun addPendingTrustedAddress() {
        val pending = pendingTrustedAddress ?: return
        pendingTrustedAddress = null
        walletAccess.onAuthenticationSucceeded()
        val result =
            trustedAddressPolicy.validateMutation(
                label = pending.label,
                address = pending.address,
                hasCurrentAuthentication = true,
                isLocked = pending.locked,
            )
        if (result !is TrustedAddressMutation.Allowed) {
            message.value = R.string.trusted_address_invalid
            return
        }
        viewModelScope.launch {
            runCatching {
                app.repository.addTrustedAddress(
                    TrustedAddressEntity(
                        walletProfileId = WALLET_PROFILE_ID,
                        label = result.address.label,
                        address = result.address.address,
                        accountKind = "SOLANA",
                        createdAtMillis = System.currentTimeMillis(),
                        lastVerifiedAtMillis = System.currentTimeMillis(),
                        isLocked = result.address.isLocked,
                    ),
                )
            }.onSuccess {
                message.value = R.string.trusted_address_saved
            }.onFailure {
                message.value = R.string.trusted_address_save_failed
            }
        }
    }

    private fun deletePendingTrustedAddress() {
        val id = pendingTrustedAddressDeleteId ?: return
        pendingTrustedAddressDeleteId = null
        walletAccess.onAuthenticationSucceeded()
        viewModelScope.launch {
            runCatching { app.repository.deleteTrustedAddress(id) }
                .onSuccess { deleted ->
                    message.value =
                        if (deleted) {
                            R.string.trusted_address_deleted
                        } else {
                            R.string.trusted_address_change_failed
                        }
                }.onFailure { message.value = R.string.trusted_address_change_failed }
        }
    }

    private fun unlockPendingTrustedAddress() {
        val id = pendingTrustedAddressUnlockId ?: return
        pendingTrustedAddressUnlockId = null
        walletAccess.onAuthenticationSucceeded()
        viewModelScope.launch {
            runCatching { app.repository.setTrustedAddressLocked(id, false) }
                .onSuccess { unlocked ->
                    message.value =
                        if (unlocked) {
                            R.string.trusted_address_unlocked
                        } else {
                            R.string.trusted_address_change_failed
                        }
                }.onFailure { message.value = R.string.trusted_address_change_failed }
        }
    }

    private fun submitPendingTransfer() {
        val submission = pendingTransferSubmission() ?: return
        _walletTransfer.value = WalletTransferState.Submitting
        transferJob =
            viewModelScope.launch {
                try {
                    executePendingTransfer(submission)
                } finally {
                    transferJob = null
                    if (lockAfterTransfer) lockWalletNow()
                }
            }
    }

    private fun pendingTransferSubmission(): PendingTransferSubmission? {
        val review = _walletTransfer.value as? WalletTransferState.Review ?: return null
        val prepared = pendingTransfer ?: return null
        val current = state.value
        val stateBlockMessage =
            manualTransferStateBlockMessage(
                current.monitorState,
                current.openPositions.isNotEmpty(),
            )
        if (stateBlockMessage != null) {
            clearPendingTransfer()
            _walletTransfer.value = WalletTransferState.Failed(stateBlockMessage)
            return null
        }
        val currentRisk = current.risk
        val currentDestination = current.trustedAddresses.firstOrNull { it.id == review.trustedAddressId }
        if (
            current.walletAddress != prepared.sourceAddress ||
            currentDestination?.address != prepared.destinationAddress ||
            currentRisk == null ||
            currentRisk.maximumTransactionCostLamports != prepared.feeCapLamports ||
            currentRisk.minimumWalletReserveLamports != prepared.reserveLamports ||
            review.destinationAddress != prepared.destinationAddress ||
            review.amountLamports != prepared.amountLamports ||
            review.estimatedFeeLamports != prepared.estimatedFeeLamports
        ) {
            clearPendingTransfer()
            _walletTransfer.value = WalletTransferState.Failed(R.string.transfer_state_changed)
            return null
        }
        val wallet =
            runtimeWallet ?: run {
                clearPendingTransfer()
                _walletTransfer.value = WalletTransferState.Failed(R.string.wallet_unlock_failed)
                return null
            }
        return PendingTransferSubmission(review, prepared, wallet)
    }

    private suspend fun executePendingTransfer(submission: PendingTransferSubmission) {
        if (!verifyFirstTransfer(submission.review)) return
        pendingTransfer = null
        var writeAheadRecord: BlockchainTransactionEntity? = null
        val result =
            walletTransferCoordinator.submit(submission.prepared, submission.wallet) { signed ->
                val record =
                    signed.toManualTransferTransaction(
                        status = "SIGNED_NOT_BROADCAST",
                        failureCode = null,
                        nowMillis = System.currentTimeMillis(),
                    )
                try {
                    app.repository.saveBlockchainTransaction(record)
                    writeAheadRecord = record
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    false
                }
            }
        when (result) {
            is SolTransferResult.Submitted -> {
                handleSubmittedTransfer(result, writeAheadRecord)
            }

            is SolTransferResult.Uncertain -> {
                handleUncertainTransfer(result, writeAheadRecord)
            }

            is SolTransferResult.Failure -> {
                handleRejectedTransfer(result, writeAheadRecord)
            }

            is SolTransferResult.Prepared -> {
                _walletTransfer.value = WalletTransferState.Failed(R.string.transfer_submit_failed)
            }
        }
    }

    private suspend fun verifyFirstTransfer(review: WalletTransferState.Review): Boolean {
        if (!review.requiresAddressVerification) return true
        val markedVerified =
            runCatching {
                app.repository.markFirstTransferVerified(
                    review.trustedAddressId,
                    System.currentTimeMillis(),
                )
            }.getOrDefault(false)
        if (!markedVerified) {
            pendingTransfer = null
            _walletTransfer.value = WalletTransferState.Failed(R.string.trusted_address_change_failed)
        }
        return markedVerified
    }

    private suspend fun handleSubmittedTransfer(
        result: SolTransferResult.Submitted,
        writeAheadRecord: BlockchainTransactionEntity?,
    ) {
        val submittedRecord = writeAheadRecord?.copy(status = "SUBMITTED", failureCode = null)
        val persisted = updateManualTransfer(writeAheadRecord, status = "SUBMITTED", failureCode = null)
        _walletTransfer.value =
            if (persisted) {
                WalletTransferState.Submitted(result.signature, ManualTransferStatus.SUBMITTED)
            } else {
                WalletTransferState.Uncertain(result.signature, R.string.transfer_persistence_failed)
            }
        if (persisted) {
            startManualTransferReconciliation(checkNotNull(submittedRecord))
        } else {
            notifyManualTransfer("SUBMISSION_UNCERTAIN", writeAheadRecord?.id)
        }
    }

    private suspend fun handleUncertainTransfer(
        result: SolTransferResult.Uncertain,
        writeAheadRecord: BlockchainTransactionEntity?,
    ) {
        val failureCode = result.providerError?.javaClass?.simpleName
        val uncertainRecord = writeAheadRecord?.copy(status = "SUBMISSION_UNCERTAIN", failureCode = failureCode)
        val persisted =
            updateManualTransfer(
                writeAheadRecord,
                status = "SUBMISSION_UNCERTAIN",
                failureCode = failureCode,
            )
        _walletTransfer.value =
            WalletTransferState.Uncertain(
                localSignature = result.localSignature,
                message = if (persisted) R.string.transfer_uncertain_body else R.string.transfer_persistence_failed,
            )
        notifyManualTransfer("SUBMISSION_UNCERTAIN", writeAheadRecord?.id)
        if (persisted) startManualTransferReconciliation(checkNotNull(uncertainRecord))
        refreshWalletBalance()
    }

    private suspend fun handleRejectedTransfer(
        result: SolTransferResult.Failure,
        writeAheadRecord: BlockchainTransactionEntity?,
    ) {
        if (writeAheadRecord != null) {
            updateManualTransfer(
                writeAheadRecord,
                status = "SUBMISSION_REJECTED",
                failureCode = result.reason.name,
            )
        }
        notifyManualTransfer("SUBMISSION_REJECTED", writeAheadRecord?.id)
        result.providerError?.let { saveProviderHealth(ProviderId.HELIUS, null, it) }
        _walletTransfer.value = WalletTransferState.Failed(transferFailureMessage(result.reason))
    }

    private suspend fun updateManualTransfer(
        record: BlockchainTransactionEntity?,
        status: String,
        failureCode: String?,
    ): Boolean {
        if (record == null) return false
        return runCatching {
            app.repository.saveBlockchainTransaction(
                record.copy(status = status, failureCode = failureCode),
            )
        }.isSuccess
    }

    private suspend fun reconcileUnresolvedManualTransfer(): Boolean {
        if (transferReconciliationJob?.isActive == true) return false
        val transaction =
            runCatching {
                app.repository.latestManualTransactionByStatuses(UNRESOLVED_MANUAL_TRANSFER_STATES)
            }.getOrElse { return false } ?: return true
        startManualTransferReconciliation(transaction)
        return false
    }

    private fun startManualTransferReconciliation(transaction: BlockchainTransactionEntity) {
        if (transferReconciliationJob?.isActive == true) return
        val signature = transaction.signature ?: return
        val lastValidBlockHeight = transaction.lastValidBlockHeight ?: return
        _walletTransfer.value = transaction.toWalletTransferState(signature)
        transferReconciliationJob =
            viewModelScope.launch {
                try {
                    transferReconciliationMutex.withLock {
                        trackManualTransfer(transaction, signature, lastValidBlockHeight)
                    }
                } finally {
                    transferReconciliationJob = null
                }
            }
    }

    private suspend fun trackManualTransfer(
        transaction: BlockchainTransactionEntity,
        signature: String,
        lastValidBlockHeight: Long,
    ) {
        var current = transaction
        val result =
            manualTransferConfirmationTracker.track(
                signature = signature,
                lastValidBlockHeight = lastValidBlockHeight,
                initialStatus = ManualTransferStatus.fromPersisted(transaction.status),
                onProgress = { confirmation ->
                    val next =
                        current.copy(
                            status = confirmation.status.name,
                            slot = confirmation.slot,
                            confirmedAtMillis =
                                if (
                                    confirmation.status == ManualTransferStatus.CONFIRMED ||
                                    confirmation.status == ManualTransferStatus.FINALIZED
                                ) {
                                    current.confirmedAtMillis ?: confirmation.observedAtMillis
                                } else {
                                    current.confirmedAtMillis
                                },
                            failureCode = null,
                        )
                    val persisted =
                        runCatching { app.repository.saveBlockchainTransaction(next) }.isSuccess
                    if (persisted) {
                        current = next
                        _walletTransfer.value =
                            WalletTransferState.Submitted(
                                signature = signature,
                                status = confirmation.status,
                            )
                        runCatching {
                            saveProviderHealth(ProviderId.HELIUS, confirmation.observedAtMillis, null)
                        }
                    }
                    persisted
                },
            )

        when (result) {
            ManualTransferTrackingResult.Finalized -> {
                notifyManualTransfer("FINALIZED", current.id)
                refreshWalletBalance()
            }

            is ManualTransferTrackingResult.ChainRejected -> {
                val persisted =
                    updateManualTransfer(
                        current.copy(slot = result.slot),
                        status = "CHAIN_REJECTED",
                        failureCode = "CHAIN_REJECTED",
                    )
                _walletTransfer.value =
                    if (persisted) {
                        notifyManualTransfer("CHAIN_REJECTED", current.id)
                        WalletTransferState.Rejected(
                            signature = signature,
                            message = R.string.transfer_chain_rejected,
                        )
                    } else {
                        WalletTransferState.Uncertain(
                            localSignature = signature,
                            message = R.string.transfer_persistence_failed,
                        )
                    }
                refreshWalletBalance()
            }

            ManualTransferTrackingResult.Expired -> {
                val persisted =
                    updateManualTransfer(
                        current,
                        status = "EXPIRED_UNCONFIRMED",
                        failureCode = "BLOCKHASH_EXPIRED_WITHOUT_SIGNATURE_STATUS",
                    )
                _walletTransfer.value =
                    if (persisted) {
                        notifyManualTransfer("EXPIRED_UNCONFIRMED", current.id)
                        WalletTransferState.Rejected(
                            signature = signature,
                            message = R.string.transfer_expired_unconfirmed,
                        )
                    } else {
                        WalletTransferState.Uncertain(
                            localSignature = signature,
                            message = R.string.transfer_persistence_failed,
                        )
                    }
                refreshWalletBalance()
            }

            is ManualTransferTrackingResult.ProviderFailure -> {
                runCatching { saveProviderHealth(ProviderId.HELIUS, null, result.error) }
                if (current.status != "SUBMISSION_UNCERTAIN") {
                    notifyManualTransfer("SUBMISSION_UNCERTAIN", current.id)
                }
                _walletTransfer.value =
                    WalletTransferState.Uncertain(
                        localSignature = signature,
                        message = R.string.transfer_confirmation_unavailable,
                    )
                refreshWalletBalance()
            }

            ManualTransferTrackingResult.PersistenceFailed -> {
                notifyManualTransfer("SUBMISSION_UNCERTAIN", current.id)
                _walletTransfer.value =
                    WalletTransferState.Uncertain(
                        localSignature = signature,
                        message = R.string.transfer_persistence_failed,
                    )
            }
        }
    }

    private fun notifyManualTransfer(
        status: String,
        relatedId: String?,
    ) {
        val code = manualTransferNotificationCode(status) ?: return
        AppNotificationDispatcher.notify(
            context = app,
            severity =
                when (code) {
                    "MANUAL_TRANSFER_CONFIRMED" -> "INFO"
                    "MANUAL_TRANSFER_UNCERTAIN" -> "WARNING"
                    else -> "ERROR"
                },
            category = "WALLET",
            code = code,
            relatedId = relatedId,
        )
    }

    private suspend fun ensureDefaultConfiguration(): Boolean {
        val (strategy, risk) = app.repository.latestConfiguration()
        if (strategy != null || risk != null) return strategy != null && risk != null
        val now = System.currentTimeMillis()
        return app.repository.saveConfig(
            strategy = DefaultConfiguration.strategy(now),
            risk = DefaultConfiguration.risk(now),
        )
    }

    private suspend fun hydrateSessionApiKeys(providerIds: List<String>) {
        CredentialProviders.forEach { provider ->
            if (provider.name !in providerIds) {
                app.sessionApiKeys.remove(provider)
                activatedProviders.value = activatedProviders.value - provider
                return@forEach
            }
            val result = app.restoreSessionApiKey(provider)
            if (result.restored) {
                activatedProviders.value = activatedProviders.value + provider
            } else {
                app.sessionApiKeys.remove(provider)
                activatedProviders.value = activatedProviders.value - provider
                message.value =
                    result.failure
                        ?.let(::providerEnvelopeFailureMessage)
                        ?: R.string.provider_key_restore_failed
            }
        }
        if (ProviderId.HELIUS in activatedProviders.value) {
            runCatching { reconcileUnresolvedManualTransfer() }
        }
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun loadWalletEnvelope(): WalletEnvelope? {
        val address =
            try {
                app.repository
                    .observeWalletProfile()
                    .first()
                    ?.publicAddress
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw SecretEnvelopeDatabaseException(error)
            } ?: return null
        val entity =
            try {
                app.repository.walletSecretEnvelope()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw SecretEnvelopeDatabaseException(error)
            } ?: return null
        val storedIv = entity.secretIv
        val storedCiphertext = entity.encryptedSecret
        val envelope =
            try {
                try {
                    SecretEnvelope(
                        version = entity.secretEnvelopeVersion,
                        publicAddress = address,
                        iv = storedIv,
                        ciphertext = storedCiphertext,
                    )
                } catch (error: IllegalArgumentException) {
                    throw CorruptedSecretEnvelopeException(error)
                }
            } finally {
                storedIv.clearSecret()
                storedCiphertext.clearSecret()
            }
        val accessMode =
            KeystoreAccessMode.entries.firstOrNull { it.name == entity.keystoreAccessMode }
                ?: throw CorruptedSecretEnvelopeException()
        return WalletEnvelope(
            envelope = envelope,
            accessMode = accessMode,
        )
    }

    private fun cipherFor(accessMode: KeystoreAccessMode): AndroidKeystoreSecretCipher =
        if (accessMode == KeystoreAccessMode.UNATTENDED) {
            AndroidKeystoreSecretCipher.unattended()
        } else {
            AndroidKeystoreSecretCipher.secureSession()
        }

    @Suppress("TooGenericExceptionCaught")
    private fun decodeStoredWalletSecret(encoded: ByteArray): CharArray =
        try {
            WalletSecretCodec.decodeAndClear(encoded)
        } catch (error: Exception) {
            throw CorruptedSecretEnvelopeException(error)
        }

    private fun toState(
        setup: SetupState,
        runtime: RuntimeState,
        content: ContentState,
        transient: TransientState,
        ledger: LedgerState,
    ): PersistedAppState {
        val demo = setup.settings.demoMode
        val unattended =
            persistedWalletAccessMode(setup.walletAccessMode) ==
                WalletAccessMode.UNATTENDED
        val balance = liveContent(demo, transient.balance, DEMO_WALLET_BALANCE)
        val chainData = liveContent(demo, transient.walletData, DEMO_WALLET_DATA)
        val configured = configuredProviders(content.configuredProviders)
        val activeSession = runtime.session.takeUnless { demo }
        return PersistedAppState(
            loaded = runtime.loaded,
            onboardingComplete = setup.settings.onboardingComplete,
            mode =
                if (demo || setup.settings.operatingMode == OperatingMode.PAPER) {
                    TradingMode.Paper
                } else {
                    TradingMode.Live
                },
            secureSession = !unattended,
            unattendedMode = unattended,
            demoMode = demo,
            walletAddress = if (demo) null else setup.wallet?.publicAddress,
            walletBackupConfirmed = setup.wallet?.backupConfirmedAtMillis != null,
            walletUnlocked = !demo && setup.wallet != null && transient.unlocked,
            walletBalanceLamports = balance.lamports,
            walletBalanceEur = balance.eur,
            walletBalanceSlot = balance.slot,
            walletBalanceLoading = balance.loading,
            walletBalanceError = balance.error,
            tokenHoldings = chainData.holdings,
            tokenHoldingsSlot = chainData.holdingsSlot,
            recentWalletActivity = chainData.activity,
            walletDataLoading = chainData.loading,
            walletDataError = chainData.error,
            walletDataUpdatedAtMillis = chainData.updatedAtMillis,
            walletDataStale = chainData.stale,
            configuredProviders = liveContent(demo, configured, emptySet()),
            activatedProviders = liveContent(demo, transient.activatedProviders, emptySet()),
            providerHealth = liveContent(demo, setup.providers, emptyList()),
            providersHealthy = providersHealthy(demo, configured, setup.providers),
            providersReadyForStart =
                providersReadyForStart(demo, configured, transient.activatedProviders, setup.providers),
            risk = selectedRisk(setup.risks, activeSession),
            strategy = selectedStrategy(setup.strategies, activeSession),
            dailyPerformance = liveContent(demo, ledger.dailyPerformance, emptyList()),
            tradeHistory = liveContent(demo, ledger.tradeHistory, emptyList()),
            monitorState = projectedMonitorState(demo, runtime.session),
            activeSessionId = activeSession?.id,
            openPositions = liveContent(demo, runtime.positions, emptyList()),
            candidates = liveContent(demo, content.candidates, emptyList()),
            trustedAddresses = liveContent(demo, content.trustedAddresses, emptyList()),
            events = liveContent(demo, content.events, emptyList()),
            message = transient.message,
        )
    }

    private fun configuredProviders(storedProviders: List<String>): Set<ProviderId> =
        storedProviders
            .mapNotNull { stored -> runCatching { ProviderId.valueOf(stored) }.getOrNull() }
            .filter { it in CredentialProviders }
            .toSet()

    private fun providersHealthy(
        demo: Boolean,
        configured: Set<ProviderId>,
        health: List<ProviderHealthEntity>,
    ): Boolean =
        !demo && CredentialProviders.all { it in configured } &&
            REQUIRED_HEALTH_PROVIDERS.all { required ->
                health.any { it.provider == required.name && it.state == HEALTHY }
            }

    private fun providersReadyForStart(
        demo: Boolean,
        configured: Set<ProviderId>,
        activated: Set<ProviderId>,
        health: List<ProviderHealthEntity>,
    ): Boolean =
        !demo && CredentialProviders.all { it in configured && it in activated } &&
            STARTUP_TEST_PROVIDERS.all { required ->
                health.any { it.provider == required.name && it.state == HEALTHY }
            }

    private fun selectedRisk(
        risks: List<RiskConfigEntity>,
        activeSession: BotSessionEntity?,
    ): RiskConfigEntity? =
        activeSession?.let { session -> risks.firstOrNull { it.version == session.riskVersion } }
            ?: risks.firstOrNull()

    private fun selectedStrategy(
        strategies: List<StrategyConfigEntity>,
        activeSession: BotSessionEntity?,
    ): StrategyConfigEntity? =
        activeSession?.let { session -> strategies.firstOrNull { it.version == session.strategyVersion } }
            ?: strategies.firstOrNull()

    private fun projectedMonitorState(
        demo: Boolean,
        session: BotSessionEntity?,
    ): MonitorState =
        if (demo) {
            MonitorState.Stopped
        } else {
            monitorStateFor(session, System.currentTimeMillis(), heartbeatFreshnessPolicy)
        }

    private fun <T> liveContent(
        demo: Boolean,
        value: T,
        demoValue: T,
    ): T = if (demo) demoValue else value

    private suspend fun saveProviderHealth(
        provider: ProviderId,
        successAtMillis: Long?,
        error: ProviderError?,
    ) {
        if (state.value.demoMode) return
        val now = System.currentTimeMillis()
        app.repository.updateProviderHealth(provider.name) { previous ->
            ProviderHealthEntity(
                provider = provider.name,
                state =
                    when (error) {
                        null -> HEALTHY
                        is ProviderError.RateLimited -> "RATE_LIMITED"
                        else -> "OFFLINE"
                    },
                consecutiveFailures = if (error == null) 0 else (previous?.consecutiveFailures ?: 0) + 1,
                lastSuccessAtMillis = successAtMillis ?: previous?.lastSuccessAtMillis,
                lastFailureAtMillis = if (error == null) previous?.lastFailureAtMillis else now,
                latencyMillis = null,
                retryAfterMillis = error?.retryAfterMillis,
                lastFailureCode = error?.javaClass?.simpleName,
                updatedAtMillis = now,
            )
        }
    }

    private suspend fun refreshFiatBalance(
        lamports: Long,
        slot: Long,
        address: String,
        expectedLockGeneration: Long,
    ) {
        if (!hasWalletDataAccess(address, expectedLockGeneration)) return
        when (val result = app.fiatRates.solEurRate()) {
            is ProviderResult.Success -> {
                if (!hasWalletDataAccess(address, expectedLockGeneration)) return
                val sol =
                    BigDecimal
                        .valueOf(lamports)
                        .divide(BigDecimal.valueOf(LAMPORTS_PER_SOL), SOL_DECIMAL_PLACES, RoundingMode.DOWN)
                walletBalance.value =
                    WalletBalanceState(
                        lamports = lamports,
                        eur = sol.multiply(result.value.eurPerSol),
                        slot = slot,
                    )
                saveProviderHealth(ProviderId.KRAKEN, result.receivedAtMillis, null)
            }

            is ProviderResult.Failure -> {
                if (!hasWalletDataAccess(address, expectedLockGeneration)) return
                saveProviderHealth(ProviderId.KRAKEN, null, result.error)
            }
        }
    }

    private fun hasWalletDataAccess(
        address: String,
        expectedLockGeneration: Long,
    ): Boolean {
        val current = state.value
        return !current.demoMode &&
            current.walletUnlocked &&
            current.walletAddress == address &&
            walletLockGeneration == expectedLockGeneration
    }

    private fun replaceWalletSetup(next: WalletSetupState) {
        (_walletSetup.value as? WalletSetupState.Mnemonic)?.phrase?.fill('0')
        _walletSetup.value = next
    }

    internal fun replaceWalletOverlay(next: WalletOverlay) {
        (_walletOverlay.value as? WalletOverlay.RevealedMnemonic)?.phrase?.fill('0')
        _walletOverlay.value = next
    }

    private fun invalidateMnemonicReveal() {
        mnemonicRevealGeneration += 1
        pendingMnemonicRevealGeneration = null
        pendingRevealEnvelope = null
    }

    private fun clearPendingWallet() {
        pendingWallet?.close()
        pendingWallet = null
    }

    private fun clearPendingTransfer() {
        transferJob?.cancel()
        transferJob = null
        pendingTransfer = null
        transferAuthenticationPending = false
        if (transferReconciliationJob?.isActive != true) {
            _walletTransfer.value = WalletTransferState.Editing
        }
    }

    private fun launchProviderAction(
        provider: ProviderId,
        action: suspend () -> Unit,
    ): Job {
        val previous = providerActionJobs[provider].orEmpty().toList()
        previous.forEach(Job::cancel)
        val job =
            viewModelScope.launch {
                previous.forEach { it.join() }
                action()
            }
        providerActionJobs.getOrPut(provider, ::mutableSetOf).add(job)
        job.invokeOnCompletion {
            providerActionJobs[provider]?.let { jobs ->
                jobs.remove(job)
                if (jobs.isEmpty()) providerActionJobs.remove(provider)
            }
        }
        return job
    }

    private suspend fun cancelProviderActions() {
        val jobs = providerActionJobs.values.flatMap { it.toList() }
        jobs.forEach(Job::cancel)
        jobs.forEach { it.join() }
    }

    private fun blockDemoAction(): Boolean {
        if (!demoModeActiveOrPending()) return false
        message.value = R.string.demo_action_unavailable
        return true
    }

    private fun authenticationRequest(
        purpose: AuthenticationPurpose,
        operation: PreparedCipherOperation? = null,
        walletSetupGeneration: Long? = null,
    ): StartExUiEvent.Authenticate =
        StartExUiEvent.Authenticate(
            purpose = purpose,
            operation = operation,
            demoModeGeneration = demoModeGeneration,
            walletSetupGeneration = walletSetupGeneration,
        )

    private fun isCurrentWalletSaveRequest(request: StartExUiEvent.Authenticate): Boolean =
        request.purpose != AuthenticationPurpose.SaveWallet ||
            (
                request.walletSetupGeneration == walletSetupGeneration &&
                    walletSaveAuthenticationPending &&
                    pendingWallet != null &&
                    _walletSetup.value is WalletSetupState.ReviewWallet
            )

    private fun demoModeActiveOrPending(): Boolean = pendingDemoMode == true || state.value.demoMode

    override fun onCleared() {
        pendingAuthenticationRequest = null
        _events.cancel()
        walletSetupGeneration += 1
        walletLockGeneration += 1
        invalidateMnemonicReveal()
        walletDataStaleJob?.cancel()
        deviceHealthRefreshGeneration += 1
        deviceHealthRefreshJob?.cancel()
        transferReconciliationJob?.cancel()
        pendingSecurityRewrap = null
        clearPendingWallet()
        clearPendingTransfer()
        runtimeWallet?.close()
        runtimeWallet = null
        replaceWalletSetup(WalletSetupState.Closed)
        replaceWalletOverlay(WalletOverlay.None)
    }

    private data class SetupState(
        val settings: AppSettings,
        val wallet: WalletProfileEntity?,
        val walletAccessMode: String?,
        val providers: List<ProviderHealthEntity>,
        val risks: List<RiskConfigEntity>,
        val strategies: List<StrategyConfigEntity>,
    )

    private data class RuntimeState(
        val session: BotSessionEntity?,
        val positions: List<PositionEntity>,
        val loaded: Boolean,
    )

    private data class ContentState(
        val candidates: List<TokenCandidateEntity>,
        val trustedAddresses: List<TrustedAddressEntity>,
        val events: List<AppEventEntity>,
        val configuredProviders: List<String>,
    )

    private data class LedgerState(
        val dailyPerformance: List<DailyPerformanceEntity>,
        val tradeHistory: List<TradeExportRow>,
    )

    private data class TransientState(
        val unlocked: Boolean,
        val balance: WalletBalanceState,
        val activatedProviders: Set<ProviderId>,
        val message: Int?,
        val walletData: WalletDataState,
    )

    private data class WalletBalanceState(
        val lamports: Long? = null,
        val eur: BigDecimal? = null,
        val slot: Long? = null,
        val loading: Boolean = false,
        val error: Int? = null,
    )

    private data class WalletDataState(
        val holdings: List<WalletTokenHolding> = emptyList(),
        val holdingsSlot: Long? = null,
        val activity: List<WalletActivity> = emptyList(),
        val loading: Boolean = false,
        val error: Int? = null,
        val updatedAtMillis: Long? = null,
        val stale: Boolean = false,
    )

    private data class WalletEnvelope(
        val envelope: SecretEnvelope,
        val accessMode: KeystoreAccessMode,
    )

    private data class PendingSecurityRewrap(
        val profile: WalletProfileEntity,
        val source: WalletEnvelope,
        val targetMode: KeystoreAccessMode,
    )

    private data class PendingTransferSubmission(
        val review: WalletTransferState.Review,
        val prepared: PreparedSolTransfer,
        val wallet: LocalWallet,
    )

    private data class PendingSecureSessionAction(
        val purpose: AuthenticationPurpose,
        val sessionId: String? = null,
    )

    private data class PendingTrustedAddress(
        val label: String,
        val address: String,
        val locked: Boolean,
    )

    private sealed interface PendingWallet : Closeable {
        val publicAddress: String

        fun mnemonic(): CharArray
    }

    private class NewPendingWallet(
        private val created: NewLocalWallet,
        val challenge: MnemonicBackupChallenge,
    ) : PendingWallet {
        override val publicAddress: String = created.wallet.publicAddress

        override fun mnemonic(): CharArray = created.mnemonic

        override fun close() {
            challenge.close()
            created.close()
        }
    }

    private class RestoredPendingWallet(
        private val wallet: LocalWallet,
        phrase: CharArray,
    ) : PendingWallet {
        private val phrase = phrase.copyOf()
        override val publicAddress: String = wallet.publicAddress

        override fun mnemonic(): CharArray = phrase.copyOf()

        override fun close() {
            phrase.fill('0')
            wallet.close()
        }
    }

    private companion object {
        const val WALLET_PROFILE_ID = 1L
        const val RECENT_EVENT_LIMIT = 100
        const val TRADE_HISTORY_LIMIT = 250
        val ACTIVE_SESSION_STATES = listOf("RUNNING", "PAUSED", "PROTECTING", "NEEDS_ATTENTION")
        val OPEN_POSITION_STATES =
            listOf(
                "OPEN",
                "EXIT_REQUESTED",
                "EXIT_SUBMITTING",
                "EXIT_UNCERTAIN",
                "EXIT_BLOCKED",
            )
        val CANDIDATE_STATES =
            listOf(
                "DISCOVERED",
                "OBSERVING",
                "ELIGIBLE",
                "REJECTED",
                "EXPIRED",
                "POSITION_OPEN",
            )
        val UNRESOLVED_MANUAL_TRANSFER_STATES =
            listOf(
                "SIGNED_NOT_BROADCAST",
                "SUBMITTED",
                "SUBMISSION_UNCERTAIN",
                "PROCESSED",
                "CONFIRMED",
            )
        const val WRAPPED_SOL_MINT = "So11111111111111111111111111111111111111112"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val JUPITER_TEST_LAMPORTS = 1_000_000L
        const val LAMPORTS_PER_SOL = 1_000_000_000L
        const val WALLET_DATA_STALE_MILLIS = 60_000L

        val DEMO_WALLET_BALANCE =
            WalletBalanceState(
                lamports = 25_000_000,
                eur = BigDecimal("3.25"),
                slot = 250_000_000,
            )
        val DEMO_WALLET_DATA =
            WalletDataState(
                holdings =
                    listOf(
                        WalletTokenHolding(
                            mint = "DemoMintLocalOnly111111111111111111111111",
                            tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                            amountAtomic = java.math.BigInteger("12500000"),
                            decimals = 6,
                        ),
                    ),
                holdingsSlot = 250_000_000,
                activity =
                    listOf(
                        WalletActivity(
                            signature = "demo-signature-local-only",
                            slot = 249_999_950,
                            blockTimeMillis = 1_725_000_000_000,
                            failed = false,
                        ),
                    ),
                updatedAtMillis = 1_725_000_000_000,
            )

        fun providerEnvelopeId(provider: ProviderId) = "provider:${provider.name}"
    }
}

private fun PersistedAppState.withProviderFreshness(nowMillis: Long): PersistedAppState {
    val maximumAgeMillis = risk?.minimumDataFreshnessMillis ?: -1
    val displayHealth =
        providerHealth.map { health ->
            if (health.state == HEALTHY && !health.isFreshHealthy(nowMillis, maximumAgeMillis)) {
                health.copy(state = "STALE")
            } else {
                health
            }
        }
    val freshHealthy =
        displayHealth
            .filter { it.state == HEALTHY }
            .map { it.provider }
            .toSet()
    return copy(
        providerHealth = displayHealth,
        providersHealthy =
            !demoMode && CredentialProviders.all { it in configuredProviders } &&
                REQUIRED_HEALTH_PROVIDERS.all { it.name in freshHealthy },
        providersReadyForStart =
            !demoMode &&
                CredentialProviders.all {
                    it in configuredProviders && it in activatedProviders
                } && STARTUP_TEST_PROVIDERS.all { it.name in freshHealthy },
    )
}

private fun transferFailureMessage(reason: SolTransferFailureReason): Int =
    when (reason) {
        SolTransferFailureReason.INVALID_SOURCE,
        SolTransferFailureReason.INVALID_DESTINATION,
        SolTransferFailureReason.INVALID_AMOUNT,
        SolTransferFailureReason.INVALID_FEE_CAP,
        SolTransferFailureReason.INVALID_RESERVE,
        -> R.string.transfer_invalid_request

        SolTransferFailureReason.DESTINATION_LOOKUP_FAILED -> R.string.transfer_destination_check_failed

        SolTransferFailureReason.EXECUTABLE_DESTINATION,
        SolTransferFailureReason.NON_SYSTEM_DESTINATION,
        -> R.string.transfer_unsafe_destination

        SolTransferFailureReason.BALANCE_LOOKUP_FAILED -> R.string.transfer_balance_check_failed

        SolTransferFailureReason.INSUFFICIENT_BALANCE -> R.string.transfer_insufficient_balance

        SolTransferFailureReason.BLOCKHASH_LOOKUP_FAILED,
        SolTransferFailureReason.FEE_LOOKUP_FAILED,
        -> R.string.transfer_network_data_failed

        SolTransferFailureReason.FEE_CAP_EXCEEDED -> R.string.transfer_fee_cap_exceeded

        SolTransferFailureReason.BLOCKHASH_EXPIRED -> R.string.transfer_prepared_expired

        SolTransferFailureReason.FEE_CHANGED -> R.string.transfer_fee_changed

        SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED,
        SolTransferFailureReason.SIMULATION_FAILED,
        SolTransferFailureReason.SIMULATION_REJECTED,
        -> R.string.transfer_safety_check_failed

        SolTransferFailureReason.SIGNER_MISMATCH -> R.string.transfer_signer_mismatch

        SolTransferFailureReason.SIGNING_FAILED -> R.string.transfer_signing_failed

        SolTransferFailureReason.SUBMISSION_REJECTED -> R.string.transfer_submission_rejected

        SolTransferFailureReason.WRITE_AHEAD_FAILED -> R.string.transfer_write_ahead_failed

        SolTransferFailureReason.PREPARED_TRANSFER_CONSUMED -> R.string.transfer_already_consumed
    }

internal fun SignedSolTransfer.toManualTransferTransaction(
    status: String,
    failureCode: String?,
    nowMillis: Long,
): BlockchainTransactionEntity =
    BlockchainTransactionEntity(
        id = "manual:$idempotencyKey",
        intentId = null,
        idempotencyKey = idempotencyKey,
        signature = signature,
        serializedHash = serializedHash,
        status = status,
        slot = null,
        actualInputAtomic = null,
        actualOutputAtomic = null,
        router = "SYSTEM_PROGRAM",
        validatorVersion = "wallet-transfer-v1",
        validatorResult = "LOCAL_BUILD_AND_SIMULATION_PASSED",
        lastValidBlockHeight = lastValidBlockHeight,
        expiresAtMillis = null,
        submittedAtMillis = nowMillis,
        confirmedAtMillis = null,
        failureCode = failureCode,
    )

private fun String.toLamportsOrNull(): Long? =
    runCatching {
        parseSolAmount(this)?.movePointRight(SOL_DECIMAL_PLACES)?.longValueExact()
    }.getOrNull()

private fun BlockchainTransactionEntity.toWalletTransferState(signature: String): WalletTransferState {
    val trackedStatus = ManualTransferStatus.fromPersisted(status)
    return if (trackedStatus != null) {
        WalletTransferState.Submitted(signature = signature, status = trackedStatus)
    } else {
        WalletTransferState.Uncertain(
            localSignature = signature,
            message = R.string.transfer_uncertain_body,
        )
    }
}

internal fun RpcTokenHolding.toUiHolding(): WalletTokenHolding =
    WalletTokenHolding(
        mint = mint,
        tokenProgram = tokenProgram,
        amountAtomic = amountAtomic,
        decimals = decimals,
    )

internal fun RpcAddressSignature.toUiActivity(): WalletActivity =
    WalletActivity(
        signature = signature,
        slot = slot,
        blockTimeMillis = blockTimeMillis,
        failed = failed,
    )

internal fun monitorStateFor(
    session: BotSessionEntity?,
    nowMillis: Long,
    freshnessPolicy: SessionHeartbeatFreshnessPolicy = SessionHeartbeatFreshnessPolicy(),
): MonitorState {
    if (
        session != null &&
        session.status in setOf("RUNNING", "PROTECTING") &&
        freshnessPolicy.isStale(session.lastHeartbeatAtMillis, nowMillis)
    ) {
        return MonitorState.NeedsAttention
    }
    return when (session?.status) {
        "RUNNING" -> MonitorState.Running
        "PAUSED" -> MonitorState.Paused
        "PROTECTING", "NEEDS_ATTENTION" -> MonitorState.NeedsAttention
        else -> MonitorState.Stopped
    }
}

internal fun canRequestSellNow(
    position: PositionEntity?,
    monitorState: MonitorState,
    demoMode: Boolean,
): Boolean =
    !demoMode &&
        monitorState == MonitorState.Running &&
        position?.mode == "PAPER" &&
        position.status == "OPEN"

internal fun canRequestEmergencyExit(
    positions: List<PositionEntity>,
    monitorState: MonitorState,
    demoMode: Boolean,
): Boolean = canRequestPaperPositionAction(positions, monitorState, demoMode)

internal fun canRequestStopAfterClose(
    positions: List<PositionEntity>,
    monitorState: MonitorState,
    demoMode: Boolean,
): Boolean = canRequestPaperPositionAction(positions, monitorState, demoMode)

private fun canRequestPaperPositionAction(
    positions: List<PositionEntity>,
    monitorState: MonitorState,
    demoMode: Boolean,
): Boolean =
    !demoMode &&
        monitorState == MonitorState.Running &&
        positions.any { it.mode == "PAPER" && it.status in setOf("OPEN", "EXIT_REQUESTED", "EXIT_BLOCKED") }

@StringRes
internal fun manualTransferStateBlockMessage(
    monitorState: MonitorState,
    hasOpenPositions: Boolean,
): Int? =
    when {
        monitorState !in setOf(MonitorState.Stopped, MonitorState.Paused) -> R.string.transfer_pause_required
        hasOpenPositions -> R.string.transfer_open_positions_blocked
        else -> null
    }

internal fun manualTransferNotificationCode(status: String): String? =
    when (status) {
        "SUBMISSION_UNCERTAIN" -> {
            "MANUAL_TRANSFER_UNCERTAIN"
        }

        "CONFIRMED", "FINALIZED" -> {
            "MANUAL_TRANSFER_CONFIRMED"
        }

        "SUBMISSION_REJECTED", "CHAIN_REJECTED", "EXPIRED_UNCONFIRMED" -> {
            "MANUAL_TRANSFER_FAILED"
        }

        else -> {
            null
        }
    }
