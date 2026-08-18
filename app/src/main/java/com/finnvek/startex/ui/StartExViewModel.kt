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
import com.finnvek.startex.security.AndroidKeystoreSecretCipher
import com.finnvek.startex.security.KeystoreAccessMode
import com.finnvek.startex.security.PreparedCipherOperation
import com.finnvek.startex.security.SecretEnvelope
import com.finnvek.startex.security.TrustedAddressMutation
import com.finnvek.startex.security.TrustedAddressPolicy
import com.finnvek.startex.security.WalletAccessMode
import com.finnvek.startex.security.WalletAccessPolicy
import com.finnvek.startex.security.clearSecret
import com.finnvek.startex.security.persistedWalletAccessMode
import com.finnvek.startex.service.AppNotificationDispatcher
import com.finnvek.startex.service.SessionHeartbeatFreshnessPolicy
import com.finnvek.startex.wallet.LocalWallet
import com.finnvek.startex.wallet.LocalWalletFactory
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
@Suppress("TooManyFunctions")
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
    private val deviceHealth = MutableStateFlow<DeviceHealthSnapshot?>(null)
    private val transferReconciliationMutex = Mutex()
    private val walletModeMutex = Mutex()
    private val runtime = MutableStateFlow(RuntimeState(null, emptyList(), loaded = false))

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
    private var lockAfterTransfer = false
    private var walletLockGeneration = 0L
    private var pendingSecurityRewrap: PendingSecurityRewrap? = null
    private var pendingSellPositionId: String? = null
    private var emergencyExitAuthenticationPending = false
    private var walletCreationAuthenticationPending = false
    private var recoveryAuthenticationPending = false
    private var pendingAuthenticationRequest: StartExUiEvent.Authenticate? = null
    private var observedDemoMode = false
    private var pendingDemoMode: Boolean? = null

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
                message.value = R.string.session_state_load_failed
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
                    observedDemoMode = settings.demoMode
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
        viewModelScope.launch {
            ensureDefaultConfiguration()
            app.settings.setOnboardingComplete(true)
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun refreshDeviceHealth() {
        viewModelScope.launch(Dispatchers.Default) {
            val current = state.value
            deviceHealth.value =
                app.deviceHealth.snapshot(
                    providerRttMillis =
                        current.providerHealth
                            .filter { it.latencyMillis != null }
                            .maxByOrNull { it.updatedAtMillis }
                            ?.latencyMillis,
                    lastEventAtMillis = current.events.maxOfOrNull { it.createdAtMillis },
                )
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
        if (!_events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.CreateWallet)).isSuccess) {
            walletCreationAuthenticationPending = false
            message.value = R.string.authentication_failed
        }
    }

    private fun createWalletAfterAuthentication() {
        if (!walletCreationAuthenticationPending) return
        walletCreationAuthenticationPending = false
        clearPendingWallet()
        replaceWalletSetup(WalletSetupState.Working)
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val created = walletFactory.create()
                if (state.value.demoMode) {
                    created.close()
                    return@runCatching
                }
                val challengeMnemonic = created.mnemonic
                val challenge =
                    try {
                        MnemonicBackupChallenge.random(challengeMnemonic)
                    } finally {
                        challengeMnemonic.fill('0')
                    }
                val pending = NewPendingWallet(created, challenge)
                pendingWallet = pending
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
        clearPendingWallet()
        replaceWalletSetup(WalletSetupState.RestoreInput())
    }

    fun previewRestoredWallet(phrase: CharArray) {
        if (blockDemoAction()) {
            phrase.fill('0')
            return
        }
        clearPendingWallet()
        replaceWalletSetup(WalletSetupState.Working)
        val input = phrase.copyOf()
        phrase.fill('0')
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val wallet = walletFactory.restore(input)
                if (state.value.demoMode) {
                    wallet.close()
                    return@runCatching
                }
                val pending = RestoredPendingWallet(wallet, input)
                pendingWallet = pending
                replaceWalletSetup(
                    WalletSetupState.ReviewWallet(pending.publicAddress, restored = true),
                )
            }.onFailure {
                clearPendingWallet()
                replaceWalletSetup(WalletSetupState.RestoreInput(R.string.restore_phrase_invalid))
            }
            input.fill('0')
        }
    }

    fun requestWalletSave(restoredBackupConfirmed: Boolean) {
        if (blockDemoAction()) return
        val pending = pendingWallet ?: return
        if (pending is RestoredPendingWallet && !restoredBackupConfirmed) return
        if (state.value.walletAddress?.let { it != pending.publicAddress } == true) {
            message.value = R.string.wallet_replacement_unsupported
            return
        }
        runCatching {
            AndroidKeystoreSecretCipher.secureSession().prepareEncryption()
        }.onSuccess { operation ->
            _events.trySend(
                StartExUiEvent.Authenticate(
                    purpose = AuthenticationPurpose.SaveWallet,
                    operation = operation,
                ),
            )
        }.onFailure {
            message.value = R.string.biometric_unavailable
        }
    }

    fun dismissWalletSetup() {
        clearPendingWallet()
        replaceWalletSetup(WalletSetupState.Closed)
    }

    fun requestWalletUnlock() {
        if (blockDemoAction()) return
        viewModelScope.launch {
            val stored =
                loadWalletEnvelope() ?: run {
                    message.value = R.string.wallet_secret_missing
                    return@launch
                }
            runCatching {
                val cipher = cipherFor(stored.accessMode)
                val operation = cipher.prepareDecryption(stored.envelope)
                if (cipher.requiresBiometricAuthentication) {
                    _events.send(
                        StartExUiEvent.Authenticate(
                            purpose = AuthenticationPurpose.UnlockWallet,
                            operation = operation,
                        ),
                    )
                } else {
                    unlockWallet(cipher, operation, stored.envelope)
                }
            }.onFailure {
                message.value = R.string.wallet_unlock_failed
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
        lockAfterTransfer = false
        dismissWalletOverlay()
        runtimeWallet?.close()
        runtimeWallet = null
        walletAccess.lock()
        walletUnlocked.value = false
        if (_walletSetup.value !is WalletSetupState.Closed) {
            clearPendingWallet()
            replaceWalletSetup(WalletSetupState.Closed)
        }
    }

    fun requestMnemonicReveal() {
        if (blockDemoAction()) return
        viewModelScope.launch {
            val stored =
                loadWalletEnvelope() ?: run {
                    message.value = R.string.wallet_secret_missing
                    return@launch
                }
            runCatching {
                val cipher = cipherFor(stored.accessMode)
                if (cipher.requiresBiometricAuthentication) {
                    val operation = cipher.prepareDecryption(stored.envelope)
                    _events.send(
                        StartExUiEvent.Authenticate(
                            purpose = AuthenticationPurpose.RevealMnemonic,
                            operation = operation,
                        ),
                    )
                } else {
                    pendingRevealEnvelope = stored
                    _events.send(StartExUiEvent.Authenticate(AuthenticationPurpose.RevealMnemonic))
                }
            }.onFailure {
                message.value = R.string.wallet_reveal_failed
            }
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
        _events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.AddTrustedAddress))
    }

    fun requestDeleteTrustedAddress(
        id: Long,
        finalCharacters: String,
    ) {
        if (blockDemoAction()) return
        val address = state.value.trustedAddresses.firstOrNull { it.id == id } ?: return
        if (address.isLocked && finalCharacters != address.address.takeLast(4)) {
            message.value = R.string.confirm_last_four_mismatch
            return
        }
        pendingTrustedAddress = null
        pendingTrustedAddressUnlockId = null
        pendingTrustedAddressDeleteId = id
        _events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.DeleteTrustedAddress))
    }

    fun requestUnlockTrustedAddress(
        id: Long,
        finalCharacters: String,
    ) {
        if (blockDemoAction()) return
        val address = state.value.trustedAddresses.firstOrNull { it.id == id && it.isLocked } ?: return
        if (finalCharacters != address.address.takeLast(4)) {
            message.value = R.string.confirm_last_four_mismatch
            return
        }
        pendingTrustedAddress = null
        pendingTrustedAddressDeleteId = null
        pendingTrustedAddressUnlockId = id
        _events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.UnlockTrustedAddress))
    }

    fun prepareSolTransfer(
        trustedAddressId: Long,
        amountSol: String,
    ) {
        if (blockDemoAction()) {
            _walletTransfer.value = WalletTransferState.Failed(R.string.demo_action_unavailable)
            return
        }
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
                    return@launch
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
                        _walletTransfer.value =
                            WalletTransferState.Failed(
                                transferFailureMessage(result.reason),
                            )
                    }

                    else -> {
                        _walletTransfer.value =
                            WalletTransferState.Failed(
                                R.string.transfer_prepare_failed,
                            )
                    }
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
        if (!_events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.SubmitTransfer)).isSuccess) {
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
        viewModelScope.launch {
            val sessionKey = key.copyOf()
            runCatching {
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
            }.onSuccess {
                message.value = R.string.provider_key_saved
            }.onFailure {
                app.sessionApiKeys.remove(provider)
                activatedProviders.value = activatedProviders.value - provider
                message.value = R.string.provider_key_save_failed
            }
            sessionKey.fill('0')
        }
    }

    fun removeProviderKey(provider: ProviderId) {
        if (blockDemoAction()) return
        require(provider in CredentialProviders) { "Provider does not accept a user API key" }
        viewModelScope.launch {
            app.sessionApiKeys.remove(provider)
            activatedProviders.value = activatedProviders.value - provider
            runCatching { app.repository.deleteProviderCredential(provider.name) }
                .onSuccess { message.value = R.string.provider_key_removed }
                .onFailure { message.value = R.string.provider_key_remove_failed }
        }
    }

    fun setDemoMode(enabled: Boolean) {
        val current = state.value
        if ((pendingDemoMode ?: observedDemoMode) == enabled) return
        if (enabled && current.monitorState != MonitorState.Stopped) {
            message.value = R.string.demo_requires_stopped_monitoring
            return
        }
        pendingDemoMode = enabled
        viewModelScope.launch {
            runCatching {
                app.settings.setDemoMode(enabled)
                if (enabled) {
                    transferReconciliationJob?.cancel()
                    lockWalletNow()
                    app.sessionApiKeys.clear()
                    activatedProviders.value = emptySet()
                }
            }.onSuccess {
                walletBalance.value = WalletBalanceState()
                walletDataStaleJob?.cancel()
                walletData.value = WalletDataState()
                message.value = if (enabled) R.string.demo_enabled else R.string.demo_disabled
                if (!enabled) unlockUnattendedWalletOnStartup()
            }.onFailure {
                pendingDemoMode = null
                message.value = R.string.demo_change_failed
            }
        }
    }

    fun requestSecurityMode(
        unattended: Boolean,
        dedicatedWalletAcknowledged: Boolean,
        reducedSecurityAcknowledged: Boolean,
    ) {
        if (blockDemoAction() || pendingSecurityRewrap != null) return
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
        viewModelScope.launch {
            runCatching {
                val profile =
                    app.repository.observeWalletProfile().first()
                        ?: error("Wallet profile is missing")
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
                pendingSecurityRewrap = PendingSecurityRewrap(profile, stored, targetMode)
                _events.send(
                    StartExUiEvent.Authenticate(
                        purpose = AuthenticationPurpose.ChangeSecurityMode,
                        operation = operation,
                    ),
                )
            }.onFailure {
                pendingSecurityRewrap = null
                message.value = R.string.security_mode_change_failed
            }
        }
    }

    fun refreshWalletBalance() {
        val current = state.value
        if (current.demoMode) return
        val address = current.walletAddress ?: return
        if (ProviderId.HELIUS !in current.activatedProviders) {
            walletBalance.value = WalletBalanceState(error = R.string.balance_requires_active_helius)
            return
        }
        if (walletBalance.value.loading) return
        walletBalance.value = walletBalance.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = app.heliusRpc.getBalance(address)
            if (state.value.demoMode) return@launch
            when (result) {
                is ProviderResult.Success -> {
                    walletBalance.value =
                        WalletBalanceState(
                            lamports = result.value.lamports,
                            slot = result.value.slot,
                        )
                    saveProviderHealth(ProviderId.HELIUS, result.receivedAtMillis, null)
                    refreshFiatBalance(result.value.lamports, result.value.slot)
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
        val address = current.walletAddress ?: return
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
            if (state.value.demoMode) return@launch
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
        viewModelScope.launch {
            val result: ProviderResult<*> =
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
                        message.value = R.string.provider_pump_test_at_monitor_start
                        return@launch
                    }
                }
            if (state.value.demoMode) return@launch
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
        if (state.value.monitorState != MonitorState.NeedsAttention || recoveryAuthenticationPending) return
        requestMonitoringRecoveryAuthentication()
    }

    private fun requestMonitoringRecoveryAuthentication() {
        if (recoveryAuthenticationPending) return
        recoveryAuthenticationPending = true
        if (!_events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.RecoverMonitoring)).isSuccess) {
            recoveryAuthenticationPending = false
            message.value = R.string.authentication_failed
        }
    }

    fun requestSellNow(positionId: String) {
        if (pendingSellPositionId != null) return
        val position = state.value.openPositions.firstOrNull { it.id == positionId }
        if (!canRequestSellNow(position, state.value.demoMode)) {
            message.value =
                if (state.value.demoMode) {
                    R.string.demo_action_unavailable
                } else {
                    R.string.sell_now_unavailable
                }
            return
        }
        pendingSellPositionId = positionId
        if (!_events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.SellNow)).isSuccess) {
            pendingSellPositionId = null
            message.value = R.string.authentication_failed
        }
    }

    fun requestStopAfterClose() {
        if (state.value.demoMode) {
            message.value = R.string.demo_action_unavailable
            return
        }
        if (state.value.openPositions.isEmpty()) {
            message.value = R.string.stop_after_close_unavailable
            return
        }
        _events.trySend(StartExUiEvent.StopAfterClose)
    }

    fun requestEmergencyExit() {
        if (emergencyExitAuthenticationPending) return
        if (!canRequestEmergencyExit(state.value.openPositions, state.value.demoMode)) {
            message.value =
                if (state.value.demoMode) {
                    R.string.demo_action_unavailable
                } else {
                    R.string.emergency_exit_unavailable
                }
            return
        }
        emergencyExitAuthenticationPending = true
        if (!_events.trySend(StartExUiEvent.Authenticate(AuthenticationPurpose.EmergencyExit)).isSuccess) {
            emergencyExitAuthenticationPending = false
            message.value = R.string.authentication_failed
        }
    }

    fun exportHistory(json: Boolean) {
        if (blockDemoAction()) return
        viewModelScope.launch {
            val text = if (json) app.repository.exportHistoryJson() else app.repository.exportHistoryCsv()
            if (!historyExportAllowed) return@launch
            _events.send(
                StartExUiEvent.ShareText(
                    title = if (json) "StartEx history JSON" else "StartEx history CSV",
                    text = text,
                    mimeType = if (json) "application/json" else "text/csv",
                ),
            )
        }
    }

    internal val historyExportAllowed: Boolean
        get() = !demoModeActiveOrPending()

    internal val authenticationInProgress: Boolean
        get() = pendingAuthenticationRequest != null

    internal fun onAuthenticationPromptStarted(request: StartExUiEvent.Authenticate) {
        pendingAuthenticationRequest = request
    }

    internal fun takePendingAuthenticationRequest(): StartExUiEvent.Authenticate? =
        pendingAuthenticationRequest.also { pendingAuthenticationRequest = null }

    fun onAuthenticationSucceeded(request: StartExUiEvent.Authenticate) {
        if (demoModeActiveOrPending()) {
            onAuthenticationFailed()
            message.value = R.string.demo_action_unavailable
            return
        }
        when (request.purpose) {
            AuthenticationPurpose.CreateWallet -> {
                createWalletAfterAuthentication()
            }

            AuthenticationPurpose.SaveWallet -> {
                request.operation?.let(::persistPendingWallet)
            }

            AuthenticationPurpose.UnlockWallet -> {
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
                val positionId = pendingSellPositionId ?: return
                pendingSellPositionId = null
                val position = state.value.openPositions.firstOrNull { it.id == positionId }
                if (!canRequestSellNow(position, state.value.demoMode)) {
                    message.value = R.string.sell_now_unavailable
                    return
                }
                walletAccess.onAuthenticationSucceeded()
                _events.trySend(StartExUiEvent.SellNow(positionId))
            }

            AuthenticationPurpose.EmergencyExit -> {
                if (!emergencyExitAuthenticationPending) return
                emergencyExitAuthenticationPending = false
                if (!canRequestEmergencyExit(state.value.openPositions, state.value.demoMode)) {
                    message.value = R.string.emergency_exit_unavailable
                    return
                }
                walletAccess.onAuthenticationSucceeded()
                _events.trySend(StartExUiEvent.EmergencyExit)
            }

            AuthenticationPurpose.RecoverMonitoring -> {
                if (!recoveryAuthenticationPending) return
                recoveryAuthenticationPending = false
                _events.trySend(StartExUiEvent.RecoverMonitoringService)
            }
        }
    }

    fun onAuthenticationFailed() {
        pendingAuthenticationRequest = null
        walletAccess.onAuthenticationRejected()
        walletCreationAuthenticationPending = false
        pendingTrustedAddress = null
        pendingTrustedAddressDeleteId = null
        pendingTrustedAddressUnlockId = null
        pendingRevealEnvelope = null
        pendingSecurityRewrap = null
        pendingSellPositionId = null
        emergencyExitAuthenticationPending = false
        transferAuthenticationPending = false
        recoveryAuthenticationPending = false
        message.value = R.string.authentication_failed
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

    private fun completeSecureUnlock(operation: PreparedCipherOperation) {
        val expectedLockGeneration = walletLockGeneration
        viewModelScope.launch {
            val stored = loadWalletEnvelope() ?: return@launch
            if (stored.accessMode != KeystoreAccessMode.BIOMETRIC_EACH_USE) return@launch
            unlockWallet(
                cipher = AndroidKeystoreSecretCipher.secureSession(),
                operation = operation,
                envelope = stored.envelope,
                expectedLockGeneration = expectedLockGeneration,
            )
        }
    }

    private fun completeSecurityModeChange(operation: PreparedCipherOperation) {
        val pending = pendingSecurityRewrap ?: return
        pendingSecurityRewrap = null
        pendingSellPositionId = null
        viewModelScope.launch {
            val changed =
                walletModeMutex.withLock {
                    runCatching {
                        val sourceCipher = cipherFor(pending.source.accessMode)
                        val targetCipher = cipherFor(pending.targetMode)
                        val decryptOperation =
                            if (
                                pending.source.accessMode == KeystoreAccessMode.BIOMETRIC_EACH_USE
                            ) {
                                operation
                            } else {
                                sourceCipher.prepareDecryption(pending.source.envelope)
                            }
                        val encoded = sourceCipher.decrypt(decryptOperation, pending.source.envelope)
                        try {
                            val encryptOperation =
                                if (
                                    pending.targetMode == KeystoreAccessMode.BIOMETRIC_EACH_USE
                                ) {
                                    operation
                                } else {
                                    targetCipher.prepareEncryption()
                                }
                            val wrapped =
                                targetCipher.encrypt(
                                    encryptOperation,
                                    encoded,
                                    pending.profile.publicAddress,
                                )
                            val ciphertext = wrapped.ciphertext
                            val iv = wrapped.iv
                            try {
                                val targetEnvelope =
                                    WalletSecretEnvelopeEntity(
                                        walletProfileId = pending.profile.id,
                                        encryptedSecret = ciphertext,
                                        secretIv = iv,
                                        secretEnvelopeVersion = wrapped.version,
                                        keystoreAccessMode = pending.targetMode.name,
                                        updatedAtMillis = System.currentTimeMillis(),
                                    )
                                app.repository.saveWallet(pending.profile, targetEnvelope)
                                runCatching {
                                    app.settings.setSecurityMode(
                                        unattended = pending.targetMode == KeystoreAccessMode.UNATTENDED,
                                    )
                                }
                            } finally {
                                ciphertext.clearSecret()
                                iv.clearSecret()
                            }
                        } finally {
                            encoded.clearSecret()
                        }
                    }.isSuccess
                }
            if (changed) {
                if (pending.targetMode == KeystoreAccessMode.UNATTENDED) {
                    unlockUnattendedWalletOnStartup()
                    message.value = R.string.unattended_enabled
                } else {
                    lockWalletNow()
                    message.value = R.string.secure_session_enabled
                }
            } else {
                message.value = R.string.security_mode_change_failed
            }
        }
    }

    private suspend fun unlockUnattendedWalletOnStartup() {
        if (state.value.demoMode || runtimeWallet != null) return
        walletModeMutex.withLock {
            if (runtimeWallet != null) return@withLock
            val stored = loadWalletEnvelope() ?: return@withLock
            if (stored.accessMode != KeystoreAccessMode.UNATTENDED) return@withLock
            val cipher = AndroidKeystoreSecretCipher.unattended()
            runCatching {
                unlockWallet(
                    cipher = cipher,
                    operation = cipher.prepareDecryption(stored.envelope),
                    envelope = stored.envelope,
                    announce = false,
                )
            }.onFailure {
                walletUnlocked.value = false
                message.value = R.string.wallet_unlock_failed
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
            val phrase = WalletSecretCodec.decodeAndClear(encoded)
            try {
                withContext(Dispatchers.Default) { walletFactory.restore(phrase) }
            } finally {
                phrase.fill('0')
            }
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
        }.onFailure {
            walletAccess.onAuthenticationRejected()
            walletUnlocked.value = false
            message.value = R.string.wallet_unlock_failed
        }
    }

    private fun completeMnemonicReveal(operation: PreparedCipherOperation?) {
        viewModelScope.launch {
            val stored = pendingRevealEnvelope ?: loadWalletEnvelope() ?: return@launch
            pendingRevealEnvelope = null
            runCatching {
                val cipher = cipherFor(stored.accessMode)
                val decryptOperation = operation ?: cipher.prepareDecryption(stored.envelope)
                val encoded = cipher.decrypt(decryptOperation, stored.envelope)
                WalletSecretCodec.decodeAndClear(encoded)
            }.onSuccess { phrase ->
                if (walletUnlocked.value) {
                    replaceWalletOverlay(WalletOverlay.RevealedMnemonic(phrase))
                } else {
                    phrase.fill('0')
                }
            }.onFailure {
                message.value = R.string.wallet_reveal_failed
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
        val review = _walletTransfer.value as? WalletTransferState.Review ?: return
        val prepared = pendingTransfer ?: return
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
        val wallet =
            runtimeWallet ?: run {
                clearPendingTransfer()
                _walletTransfer.value = WalletTransferState.Failed(R.string.wallet_unlock_failed)
                return
            }
        _walletTransfer.value = WalletTransferState.Submitting
        transferJob =
            viewModelScope.launch {
                try {
                    if (review.requiresAddressVerification) {
                        val markedVerified =
                            runCatching {
                                app.repository.markFirstTransferVerified(
                                    review.trustedAddressId,
                                    System.currentTimeMillis(),
                                )
                            }.getOrDefault(false)
                        if (!markedVerified) {
                            pendingTransfer = null
                            _walletTransfer.value =
                                WalletTransferState.Failed(
                                    R.string.trusted_address_change_failed,
                                )
                            return@launch
                        }
                    }
                    pendingTransfer = null
                    var writeAheadRecord: BlockchainTransactionEntity? = null
                    val result =
                        walletTransferCoordinator.submit(prepared, wallet) { signed ->
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
                            val submittedRecord =
                                writeAheadRecord?.copy(
                                    status = "SUBMITTED",
                                    failureCode = null,
                                )
                            val persisted =
                                updateManualTransfer(
                                    writeAheadRecord,
                                    status = "SUBMITTED",
                                    failureCode = null,
                                )
                            _walletTransfer.value =
                                if (persisted) {
                                    WalletTransferState.Submitted(
                                        signature = result.signature,
                                        status = ManualTransferStatus.SUBMITTED,
                                    )
                                } else {
                                    WalletTransferState.Uncertain(
                                        localSignature = result.signature,
                                        message = R.string.transfer_persistence_failed,
                                    )
                                }
                            if (!persisted) {
                                notifyManualTransfer("SUBMISSION_UNCERTAIN", writeAheadRecord?.id)
                            } else {
                                startManualTransferReconciliation(checkNotNull(submittedRecord))
                            }
                        }

                        is SolTransferResult.Uncertain -> {
                            val uncertainRecord =
                                writeAheadRecord?.copy(
                                    status = "SUBMISSION_UNCERTAIN",
                                    failureCode = result.providerError?.javaClass?.simpleName,
                                )
                            val persisted =
                                updateManualTransfer(
                                    writeAheadRecord,
                                    status = "SUBMISSION_UNCERTAIN",
                                    failureCode = result.providerError?.javaClass?.simpleName,
                                )
                            _walletTransfer.value =
                                WalletTransferState.Uncertain(
                                    localSignature = result.localSignature,
                                    message =
                                        if (persisted) {
                                            R.string.transfer_uncertain_body
                                        } else {
                                            R.string.transfer_persistence_failed
                                        },
                                )
                            notifyManualTransfer("SUBMISSION_UNCERTAIN", writeAheadRecord?.id)
                            if (persisted) {
                                startManualTransferReconciliation(checkNotNull(uncertainRecord))
                            }
                            refreshWalletBalance()
                        }

                        is SolTransferResult.Failure -> {
                            if (writeAheadRecord != null) {
                                updateManualTransfer(
                                    writeAheadRecord,
                                    status = "SUBMISSION_REJECTED",
                                    failureCode = result.reason.name,
                                )
                            }
                            notifyManualTransfer("SUBMISSION_REJECTED", writeAheadRecord?.id)
                            result.providerError?.let { saveProviderHealth(ProviderId.HELIUS, null, it) }
                            _walletTransfer.value =
                                WalletTransferState.Failed(
                                    transferFailureMessage(result.reason),
                                )
                        }

                        is SolTransferResult.Prepared -> {
                            _walletTransfer.value = WalletTransferState.Failed(R.string.transfer_submit_failed)
                        }
                    }
                } finally {
                    transferJob = null
                    if (lockAfterTransfer) lockWalletNow()
                }
            }
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

    private suspend fun ensureDefaultConfiguration() {
        val risk = app.repository.observeLatestRisk().first()
        val strategy = app.repository.observeLatestStrategy().first()
        if (risk == null && strategy == null) {
            val now = System.currentTimeMillis()
            app.repository.saveConfig(
                strategy = DefaultConfiguration.strategy(now),
                risk = DefaultConfiguration.risk(now),
            )
        }
    }

    private suspend fun hydrateSessionApiKeys(providerIds: List<String>) {
        CredentialProviders.forEach { provider ->
            if (provider.name !in providerIds) {
                app.sessionApiKeys.remove(provider)
                activatedProviders.value = activatedProviders.value - provider
                return@forEach
            }
            if (app.restoreSessionApiKey(provider)) {
                activatedProviders.value = activatedProviders.value + provider
            } else {
                app.sessionApiKeys.remove(provider)
                activatedProviders.value = activatedProviders.value - provider
                message.value = R.string.provider_key_restore_failed
            }
        }
        if (ProviderId.HELIUS in activatedProviders.value) {
            runCatching { reconcileUnresolvedManualTransfer() }
        }
    }

    private suspend fun loadWalletEnvelope(): WalletEnvelope? {
        val address =
            app.repository
                .observeWalletProfile()
                .first()
                ?.publicAddress ?: return null
        val entity = app.repository.walletSecretEnvelope() ?: return null
        val storedIv = entity.secretIv
        val storedCiphertext = entity.encryptedSecret
        val envelope =
            try {
                SecretEnvelope(
                    version = entity.secretEnvelopeVersion,
                    publicAddress = address,
                    iv = storedIv,
                    ciphertext = storedCiphertext,
                )
            } finally {
                storedIv.clearSecret()
                storedCiphertext.clearSecret()
            }
        return WalletEnvelope(
            envelope = envelope,
            accessMode =
                runCatching { KeystoreAccessMode.valueOf(entity.keystoreAccessMode) }
                    .getOrDefault(KeystoreAccessMode.BIOMETRIC_EACH_USE),
        )
    }

    private fun cipherFor(accessMode: KeystoreAccessMode): AndroidKeystoreSecretCipher =
        if (accessMode == KeystoreAccessMode.UNATTENDED) {
            AndroidKeystoreSecretCipher.unattended()
        } else {
            AndroidKeystoreSecretCipher.secureSession()
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
        val balance = if (demo) DEMO_WALLET_BALANCE else transient.balance
        val chainData = if (demo) DEMO_WALLET_DATA else transient.walletData
        val configured =
            content.configuredProviders
                .mapNotNull { stored -> runCatching { ProviderId.valueOf(stored) }.getOrNull() }
                .filterTo(mutableSetOf()) { it in CredentialProviders }
        val activeSession = if (demo) null else runtime.session
        val risk =
            if (activeSession == null) {
                setup.risks.firstOrNull()
            } else {
                setup.risks.firstOrNull { it.version == activeSession.riskVersion }
            }
        val strategy =
            if (activeSession == null) {
                setup.strategies.firstOrNull()
            } else {
                setup.strategies.firstOrNull { it.version == activeSession.strategyVersion }
            }
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
            configuredProviders = if (demo) emptySet() else configured,
            activatedProviders = if (demo) emptySet() else transient.activatedProviders,
            providerHealth = if (demo) emptyList() else setup.providers,
            providersHealthy =
                !demo && CredentialProviders.all { it in configured } &&
                    REQUIRED_HEALTH_PROVIDERS.all { required ->
                        setup.providers.any {
                            it.provider == required.name && it.state == HEALTHY
                        }
                    },
            providersReadyForStart =
                !demo &&
                    CredentialProviders.all {
                        it in configured && it in transient.activatedProviders
                    } &&
                    STARTUP_TEST_PROVIDERS.all { required ->
                        setup.providers.any {
                            it.provider == required.name && it.state == HEALTHY
                        }
                    },
            risk = risk,
            strategy = strategy,
            dailyPerformance = if (demo) emptyList() else ledger.dailyPerformance,
            tradeHistory = if (demo) emptyList() else ledger.tradeHistory,
            monitorState =
                if (demo) {
                    MonitorState.Stopped
                } else {
                    monitorStateFor(runtime.session, System.currentTimeMillis(), heartbeatFreshnessPolicy)
                },
            activeSessionId = if (demo) null else runtime.session?.id,
            openPositions = if (demo) emptyList() else runtime.positions,
            candidates = if (demo) emptyList() else content.candidates,
            trustedAddresses = if (demo) emptyList() else content.trustedAddresses,
            events = if (demo) emptyList() else content.events,
            message = transient.message,
        )
    }

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
    ) {
        when (val result = app.fiatRates.solEurRate()) {
            is ProviderResult.Success -> {
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
                saveProviderHealth(ProviderId.KRAKEN, null, result.error)
            }
        }
    }

    private fun replaceWalletSetup(next: WalletSetupState) {
        (_walletSetup.value as? WalletSetupState.Mnemonic)?.phrase?.fill('0')
        _walletSetup.value = next
    }

    private fun replaceWalletOverlay(next: WalletOverlay) {
        (_walletOverlay.value as? WalletOverlay.RevealedMnemonic)?.phrase?.fill('0')
        _walletOverlay.value = next
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

    private fun blockDemoAction(): Boolean {
        if (!demoModeActiveOrPending()) return false
        message.value = R.string.demo_action_unavailable
        return true
    }

    private fun demoModeActiveOrPending(): Boolean = pendingDemoMode == true || observedDemoMode || state.value.demoMode

    override fun onCleared() {
        pendingAuthenticationRequest = null
        _events.cancel()
        walletLockGeneration += 1
        walletDataStaleJob?.cancel()
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
                "NEW",
                "OBSERVING",
                "ELIGIBLE",
                "REJECTED",
                "EXPIRED",
                "ENTERED",
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
            .mapTo(mutableSetOf()) { it.provider }
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
        val sol = BigDecimal(trim())
        require(sol > BigDecimal.ZERO && sol.stripTrailingZeros().scale() <= SOL_DECIMAL_PLACES)
        sol.movePointRight(SOL_DECIMAL_PLACES).longValueExact()
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
    demoMode: Boolean,
): Boolean = !demoMode && position?.mode == "PAPER" && position.status == "OPEN"

internal fun canRequestEmergencyExit(
    positions: List<PositionEntity>,
    demoMode: Boolean,
): Boolean =
    !demoMode &&
        positions.any {
            it.mode == "PAPER" && it.status in setOf("OPEN", "EXIT_REQUESTED", "EXIT_BLOCKED")
        }

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
