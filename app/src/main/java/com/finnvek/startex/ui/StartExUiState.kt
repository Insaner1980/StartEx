package com.finnvek.startex.ui

import androidx.annotation.StringRes
import com.finnvek.startex.data.local.AppEventEntity
import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.data.local.TrustedAddressEntity
import com.finnvek.startex.device.DeviceHealthSnapshot
import com.finnvek.startex.domain.ConfigurationFieldError
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.security.PreparedCipherOperation
import com.finnvek.startex.wallet.ManualTransferStatus
import java.math.BigDecimal
import java.math.BigInteger

val CredentialProviders =
    listOf(
        ProviderId.HELIUS,
        ProviderId.PUMP_PORTAL,
        ProviderId.JUPITER,
    )

enum class AppDestination {
    Home,
    Watch,
    Wallet,
    History,
    Settings,
}

enum class TradingMode {
    Paper,
    Live,
}

enum class MonitorState {
    Stopped,
    Running,
    Paused,
    NeedsAttention,
}

data class WalletTokenHolding(
    val mint: String,
    val tokenProgram: String,
    val amountAtomic: BigInteger,
    val decimals: Int,
) {
    val isToken2022: Boolean
        get() = tokenProgram == TOKEN_2022_PROGRAM_ID

    val formattedAmount: String
        get() = BigDecimal(amountAtomic, decimals).stripTrailingZeros().toPlainString()
}

data class WalletActivity(
    val signature: String,
    val slot: Long,
    val blockTimeMillis: Long?,
    val failed: Boolean,
)

data class PreflightState(
    val walletReady: Boolean = false,
    val backupVerified: Boolean = false,
    val providersHealthy: Boolean = false,
    val limitsConfigured: Boolean = false,
    val reserveReady: Boolean = false,
    val reserveRequired: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val pumpHealthCheckedOnStart: Boolean = false,
    val deviceHealthReady: Boolean = false,
) {
    val isReady: Boolean
        get() =
            walletReady && backupVerified && providersHealthy && limitsConfigured &&
                (!reserveRequired || reserveReady) && notificationsAllowed && deviceHealthReady
}

sealed interface WalletSetupState {
    data object Closed : WalletSetupState

    data object Working : WalletSetupState

    data class Mnemonic(
        val phrase: CharArray,
        val publicAddress: String,
    ) : WalletSetupState

    data class BackupChallenge(
        val wordNumbers: List<Int>,
        @StringRes val error: Int? = null,
    ) : WalletSetupState

    data class RestoreInput(
        @StringRes val error: Int? = null,
    ) : WalletSetupState

    data class ReviewWallet(
        val publicAddress: String,
        val restored: Boolean,
    ) : WalletSetupState

    data object Saving : WalletSetupState
}

sealed interface WalletOverlay {
    data object None : WalletOverlay

    data object Receive : WalletOverlay

    data object Send : WalletOverlay

    data object TrustedAddresses : WalletOverlay

    data class RevealedMnemonic(
        val phrase: CharArray,
    ) : WalletOverlay
}

sealed interface WalletTransferState {
    data object Editing : WalletTransferState

    data object Preparing : WalletTransferState

    data class Review(
        val trustedAddressId: Long,
        val destinationLabel: String,
        val destinationAddress: String,
        val amountLamports: Long,
        val estimatedFeeLamports: Long,
        val reserveLamports: Long,
        val lastValidBlockHeight: Long,
        val requiresAddressVerification: Boolean,
    ) : WalletTransferState

    data object Submitting : WalletTransferState

    data class Submitted(
        val signature: String,
        val status: ManualTransferStatus = ManualTransferStatus.SUBMITTED,
    ) : WalletTransferState

    data class Uncertain(
        val localSignature: String,
        @StringRes val message: Int,
    ) : WalletTransferState

    data class Rejected(
        val signature: String,
        @StringRes val message: Int,
    ) : WalletTransferState

    data class Failed(
        @StringRes val message: Int,
    ) : WalletTransferState
}

enum class AuthenticationPurpose {
    CreateWallet,
    SaveWallet,
    UnlockWallet,
    RevealMnemonic,
    AddTrustedAddress,
    DeleteTrustedAddress,
    UnlockTrustedAddress,
    SubmitTransfer,
    ChangeSecurityMode,
    SellNow,
    EmergencyExit,
    RecoverMonitoring,
}

sealed interface StartExUiEvent {
    data class Authenticate(
        val purpose: AuthenticationPurpose,
        val operation: PreparedCipherOperation? = null,
    ) : StartExUiEvent

    data object StartMonitoringService : StartExUiEvent

    data object PauseMonitoringService : StartExUiEvent

    data object ResumeMonitoringService : StartExUiEvent

    data object RecoverMonitoringService : StartExUiEvent

    data object StopMonitoringService : StartExUiEvent

    data class SellNow(
        val positionId: String,
    ) : StartExUiEvent

    data object EmergencyExit : StartExUiEvent

    data object StopAfterClose : StartExUiEvent

    data class ShareText(
        val title: String,
        val text: String,
        val mimeType: String = "text/plain",
    ) : StartExUiEvent
}

data class PersistedAppState(
    val loaded: Boolean = false,
    val onboardingComplete: Boolean = false,
    val mode: TradingMode = TradingMode.Paper,
    val secureSession: Boolean = true,
    val unattendedMode: Boolean = false,
    val demoMode: Boolean = false,
    val walletAddress: String? = null,
    val walletBackupConfirmed: Boolean = false,
    val walletUnlocked: Boolean = false,
    val walletBalanceLamports: Long? = null,
    val walletBalanceEur: BigDecimal? = null,
    val walletBalanceSlot: Long? = null,
    val walletBalanceLoading: Boolean = false,
    @StringRes val walletBalanceError: Int? = null,
    val tokenHoldings: List<WalletTokenHolding> = emptyList(),
    val tokenHoldingsSlot: Long? = null,
    val recentWalletActivity: List<WalletActivity> = emptyList(),
    val walletDataLoading: Boolean = false,
    @StringRes val walletDataError: Int? = null,
    val walletDataUpdatedAtMillis: Long? = null,
    val walletDataStale: Boolean = false,
    val configuredProviders: Set<ProviderId> = emptySet(),
    val activatedProviders: Set<ProviderId> = emptySet(),
    val providerHealth: List<ProviderHealthEntity> = emptyList(),
    val deviceHealth: DeviceHealthSnapshot? = null,
    val providersHealthy: Boolean = false,
    val providersReadyForStart: Boolean = false,
    val risk: RiskConfigEntity? = null,
    val strategy: StrategyConfigEntity? = null,
    val dailyPerformance: List<DailyPerformanceEntity> = emptyList(),
    val tradeHistory: List<TradeExportRow> = emptyList(),
    val monitorState: MonitorState = MonitorState.Stopped,
    val activeSessionId: String? = null,
    val openPositions: List<PositionEntity> = emptyList(),
    val candidates: List<TokenCandidateEntity> = emptyList(),
    val trustedAddresses: List<TrustedAddressEntity> = emptyList(),
    val events: List<AppEventEntity> = emptyList(),
    @StringRes val message: Int? = null,
) {
    val unattendedRiskCapsReady: Boolean
        get() = hasRequiredUnattendedCaps(risk)

    val externalActionsAllowed: Boolean
        get() = !demoMode

    val configurationEditingAllowed: Boolean
        get() = loaded && activeSessionId == null && monitorState == MonitorState.Stopped
}

sealed interface ConfigurationSaveState {
    data object Idle : ConfigurationSaveState

    data object Saving : ConfigurationSaveState

    data object Saved : ConfigurationSaveState

    data object SessionActive : ConfigurationSaveState

    data class Invalid(
        val error: ConfigurationFieldError,
    ) : ConfigurationSaveState

    data object Failed : ConfigurationSaveState
}

internal fun hasRequiredUnattendedCaps(risk: RiskConfigEntity?): Boolean =
    risk?.let {
        it.maximumTradeLamports > 0 &&
            it.maximumExposureLamports >= it.maximumTradeLamports &&
            it.maximumDailyLossLamports > 0 &&
            it.maximumOpenPositions in 1..2
    } == true

internal fun canEnableUnattendedMode(
    walletConfigured: Boolean,
    risk: RiskConfigEntity?,
    monitorState: MonitorState,
    dedicatedWalletAcknowledged: Boolean,
    reducedSecurityAcknowledged: Boolean,
): Boolean =
    walletConfigured &&
        hasRequiredUnattendedCaps(risk) &&
        monitorState == MonitorState.Stopped &&
        dedicatedWalletAcknowledged &&
        reducedSecurityAcknowledged

private const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
