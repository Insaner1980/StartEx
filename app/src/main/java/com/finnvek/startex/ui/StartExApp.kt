package com.finnvek.startex.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.finnvek.startex.R
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.device.DeviceHealthEntryPolicy
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.service.TradingMonitorService
import com.finnvek.startex.service.canPostNotifications
import com.finnvek.startex.ui.screens.ConfigurationEditorScreen
import com.finnvek.startex.ui.screens.HistoryScreen
import com.finnvek.startex.ui.screens.HomeScreen
import com.finnvek.startex.ui.screens.LockScreen
import com.finnvek.startex.ui.screens.OnboardingScreen
import com.finnvek.startex.ui.screens.PreflightScreen
import com.finnvek.startex.ui.screens.ProviderSetupScreen
import com.finnvek.startex.ui.screens.SettingsScreen
import com.finnvek.startex.ui.screens.WalletFlowScreen
import com.finnvek.startex.ui.screens.WalletOverlayScreen
import com.finnvek.startex.ui.screens.WalletScreen
import com.finnvek.startex.ui.screens.WatchScreen
import com.finnvek.startex.ui.theme.StartExBackground
import com.finnvek.startex.ui.theme.StartExRed
import com.finnvek.startex.ui.theme.StartExSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

internal enum class FullScreenPanel {
    None,
    Preflight,
    Providers,
    Configuration,
}

internal enum class TopLevelRoute {
    LoadError,
    Loading,
    WalletSetup,
    SecureSessionLock,
    WalletOverlay,
    Providers,
    Configuration,
    Onboarding,
    Preflight,
    Main,
}

internal fun topLevelRoute(
    state: PersistedAppState,
    walletSetup: WalletSetupState,
    walletOverlay: WalletOverlay,
    panel: FullScreenPanel,
    startupError: Int? = null,
): TopLevelRoute {
    val walletLocked = !state.demoMode && state.walletAddress != null && !state.walletUnlocked
    return when {
        startupError != null -> TopLevelRoute.LoadError
        !state.loaded -> TopLevelRoute.Loading
        walletSetup !is WalletSetupState.Closed -> TopLevelRoute.WalletSetup
        walletLocked -> TopLevelRoute.SecureSessionLock
        walletOverlay !is WalletOverlay.None -> TopLevelRoute.WalletOverlay
        panel == FullScreenPanel.Providers -> TopLevelRoute.Providers
        panel == FullScreenPanel.Configuration -> TopLevelRoute.Configuration
        !state.onboardingComplete -> TopLevelRoute.Onboarding
        panel == FullScreenPanel.Preflight -> TopLevelRoute.Preflight
        else -> TopLevelRoute.Main
    }
}

internal fun stopConfirmationOpenPositionCount(
    route: TopLevelRoute,
    openPositionCount: Int,
): Int? = openPositionCount.takeIf { route == TopLevelRoute.Main }

private data class DestinationItem(
    val destination: AppDestination,
    val label: Int,
    val icon: ImageVector,
)

private val destinationItems =
    listOf(
        DestinationItem(AppDestination.Home, R.string.nav_home, Icons.Outlined.Home),
        DestinationItem(AppDestination.Watch, R.string.nav_watch, Icons.Outlined.Visibility),
        DestinationItem(AppDestination.Wallet, R.string.nav_wallet, Icons.Outlined.AccountBalanceWallet),
        DestinationItem(AppDestination.History, R.string.nav_history, Icons.Outlined.History),
        DestinationItem(AppDestination.Settings, R.string.nav_settings, Icons.Outlined.Settings),
    )

@Composable
internal fun StartExNavigationBar(
    destination: AppDestination,
    onDestination: (AppDestination) -> Unit,
) {
    NavigationBar(containerColor = StartExSurface) {
        destinationItems.forEach { item ->
            NavigationBarItem(
                selected = destination == item.destination,
                onClick = { onDestination(item.destination) },
                icon = { Icon(imageVector = item.icon, contentDescription = null) },
                label = { Text(stringResource(item.label)) },
                colors =
                    NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    ),
            )
        }
    }
}

@Composable
fun StartExApp(
    viewModel: StartExViewModel,
    onAuthenticate: (StartExUiEvent.Authenticate, BiometricPrompt.PromptInfo) -> Unit,
) {
    val context = LocalContext.current
    val currentAuthenticate by rememberUpdatedState(onAuthenticate)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startupError by viewModel.startupError.collectAsStateWithLifecycle()
    val walletSetup by viewModel.walletSetup.collectAsStateWithLifecycle()
    val walletOverlay by viewModel.walletOverlay.collectAsStateWithLifecycle()
    val walletTransfer by viewModel.walletTransfer.collectAsStateWithLifecycle()
    val configurationSave by viewModel.configurationSave.collectAsStateWithLifecycle()
    val providerTestsInProgress by viewModel.providerTestsInProgress.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, context) {
        viewModel.events.collect { event ->
            when (event) {
                is StartExUiEvent.Authenticate -> {
                    currentAuthenticate(event, authenticationPromptInfo(context, event))
                }

                StartExUiEvent.StartMonitoringService -> {
                    if (!startMonitoringService(context)) {
                        viewModel.onMonitoringServiceStartFailed()
                    }
                }

                StartExUiEvent.PauseMonitoringService -> {
                    pauseMonitoringService(context)
                }

                StartExUiEvent.ResumeMonitoringService -> {
                    resumeMonitoringService(context)
                }

                is StartExUiEvent.RecoverMonitoringService -> {
                    recoverMonitoringService(context, event.sessionId)
                }

                is StartExUiEvent.StopAuthenticatedMonitoringService -> {
                    stopAuthenticatedMonitoringService(context, event.sessionId)
                }

                StartExUiEvent.StopMonitoringService -> {
                    stopMonitoringService(context)
                }

                is StartExUiEvent.SellNow -> {
                    sellNow(context, event.positionId)
                }

                StartExUiEvent.EmergencyExit -> {
                    emergencyExit(context)
                }

                StartExUiEvent.StopAfterClose -> {
                    stopAfterClose(context)
                }

                is StartExUiEvent.ShareText -> {
                    if (viewModel.historyExportAllowed && !shareText(context, event)) {
                        viewModel.onHistoryShareFailed()
                    }
                }
            }
        }
    }

    StartExContent(
        state = state,
        walletSetup = walletSetup,
        walletOverlay = walletOverlay,
        walletTransfer = walletTransfer,
        configurationSave = configurationSave,
        providerTestsInProgress = providerTestsInProgress,
        startupError = startupError,
        viewModel = viewModel,
    )
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun StartExContent(
    state: PersistedAppState,
    walletSetup: WalletSetupState,
    walletOverlay: WalletOverlay,
    walletTransfer: WalletTransferState,
    configurationSave: ConfigurationSaveState,
    providerTestsInProgress: Set<ProviderId>,
    startupError: Int?,
    viewModel: StartExViewModel,
) {
    val context = LocalContext.current
    val batteryOptimizationUnavailable = stringResource(R.string.battery_optimization_unavailable)
    val notificationSettingsUnavailable = stringResource(R.string.notification_settings_unavailable)
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    var panel by rememberSaveable { mutableStateOf(FullScreenPanel.None) }
    var stopConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    val onboardingStateHolder = rememberSaveableStateHolder()
    val onboardingProvidersFocusRequester = remember { FocusRequester() }
    val receiveFocusRequester = remember { FocusRequester() }
    val homePreflightFocusRequester = remember { FocusRequester() }
    val settingsProvidersFocusRequester = remember { FocusRequester() }
    val settingsStrategyFocusRequester = remember { FocusRequester() }
    val settingsHealthFocusRequester = remember { FocusRequester() }
    var pendingPanelFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var restoreOnboardingProvidersFocus by remember { mutableStateOf(false) }
    var previousWalletOverlay by remember { mutableStateOf(walletOverlay) }
    var notificationsAllowed by rememberSaveable {
        mutableStateOf(
            context.canPostNotifications(
                StartExApplication.CHANNEL_BOT_STATUS,
                StartExApplication.CHANNEL_CRITICAL,
            ),
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed =
            context.canPostNotifications(
                StartExApplication.CHANNEL_BOT_STATUS,
                StartExApplication.CHANNEL_CRITICAL,
            )
    }
    state.message?.let { message ->
        val text = stringResource(message)
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    val preflight =
        PreflightState(
            walletReady = state.walletAddress != null && state.walletUnlocked,
            backupVerified = state.walletBackupConfirmed,
            providersHealthy = state.providersReadyForStart,
            limitsConfigured = state.risk != null,
            reserveReady = state.mode == TradingMode.Paper,
            reserveRequired = state.mode == TradingMode.Live,
            notificationsAllowed = notificationsAllowed,
            pumpHealthCheckedOnStart =
                state.providerHealth.any {
                    it.provider == "PUMP_PORTAL" && it.state == "HEALTHY"
                },
            deviceHealthReady =
                state.deviceHealth?.let {
                    DeviceHealthEntryPolicy.blockReason(it) == null
                } == true,
        )

    val route = topLevelRoute(state, walletSetup, walletOverlay, panel, startupError)
    LaunchedEffect(route) {
        if (route == TopLevelRoute.Preflight) {
            viewModel.refreshDeviceHealth()
            while (true) {
                delay(DEVICE_HEALTH_REFRESH_INTERVAL_MILLIS)
                viewModel.refreshDeviceHealth(invalidateCurrent = false)
            }
        }
    }
    LaunchedEffect(route, walletOverlay, destination) {
        if (
            route == TopLevelRoute.Main &&
            destination == AppDestination.Wallet &&
            previousWalletOverlay == WalletOverlay.Receive &&
            walletOverlay == WalletOverlay.None
        ) {
            receiveFocusRequester.requestFocus()
        }
        if (
            route == TopLevelRoute.Main &&
            (destination == AppDestination.Home || destination == AppDestination.Settings)
        ) {
            pendingPanelFocusRequester?.requestFocus()
            pendingPanelFocusRequester = null
        }
        if (route == TopLevelRoute.Onboarding && restoreOnboardingProvidersFocus) {
            withFrameNanos { }
            onboardingProvidersFocusRequester.requestFocus()
            restoreOnboardingProvidersFocus = false
        }
        previousWalletOverlay = walletOverlay
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (route == TopLevelRoute.Main) {
                            Modifier
                        } else {
                            Modifier.safeDrawingPadding().imePadding()
                        },
                    ),
        ) {
            when (route) {
                TopLevelRoute.LoadError -> {
                    StartupErrorScreen(requireNotNull(startupError))
                }

                TopLevelRoute.Loading -> {
                    LoadingScreen()
                }

                TopLevelRoute.WalletSetup -> {
                    WalletFlowScreen(
                        state = walletSetup,
                        onMnemonicSave = viewModel::showBackupChallenge,
                        onVerifyBackup = viewModel::verifyBackupChallenge,
                        onRestore = viewModel::previewRestoredWallet,
                        onSave = viewModel::requestWalletSave,
                        onCancel = viewModel::dismissWalletSetup,
                    )
                }

                TopLevelRoute.SecureSessionLock -> {
                    LockScreen(
                        recoveryRequired = state.monitorState == MonitorState.NeedsAttention,
                        onUnlock = viewModel::requestWalletUnlock,
                        onRestore = viewModel::beginWalletRestore,
                        onRecover = viewModel::recoverMonitoring,
                        onStop = { stopConfirmationOpen = true },
                    )
                }

                TopLevelRoute.WalletOverlay -> {
                    WalletOverlayScreen(
                        overlay = walletOverlay,
                        state = state,
                        transferState = walletTransfer,
                        trustedAddresses = state.trustedAddresses,
                        onDismiss = viewModel::dismissWalletOverlay,
                        onRefreshBalance = viewModel::refreshWalletBalance,
                        onPrepareTransfer = viewModel::prepareSolTransfer,
                        onSubmitTransfer = viewModel::requestSolTransferSubmit,
                        onResetTransfer = viewModel::resetSolTransfer,
                        onAddTrustedAddress = viewModel::requestAddTrustedAddress,
                        onDeleteTrustedAddress = viewModel::requestDeleteTrustedAddress,
                        onUnlockTrustedAddress = viewModel::requestUnlockTrustedAddress,
                    )
                }

                TopLevelRoute.Providers -> {
                    ProviderSetupScreen(
                        configuredProviders = state.configuredProviders,
                        activatedProviders = state.activatedProviders,
                        providerHealth = state.providerHealth,
                        testingProviders = providerTestsInProgress,
                        onSave = viewModel::saveProviderKey,
                        onRemove = viewModel::removeProviderKey,
                        onTest = viewModel::testProvider,
                        onBack = { panel = FullScreenPanel.None },
                    )
                }

                TopLevelRoute.Configuration -> {
                    ConfigurationEditorScreen(
                        state = state,
                        saveState = configurationSave,
                        onSave = viewModel::saveConfiguration,
                        onBack = {
                            viewModel.resetConfigurationSaveState()
                            panel = FullScreenPanel.None
                        },
                    )
                }

                TopLevelRoute.Onboarding -> {
                    onboardingStateHolder.SaveableStateProvider(TopLevelRoute.Onboarding) {
                        OnboardingScreen(
                            state = state,
                            onCreateWallet = viewModel::beginWalletCreation,
                            onRestoreWallet = viewModel::beginWalletRestore,
                            onConfigureProviders = {
                                restoreOnboardingProvidersFocus = true
                                panel = FullScreenPanel.Providers
                            },
                            onTrustedAddresses = {
                                viewModel.showWalletOverlay(WalletOverlay.TrustedAddresses)
                            },
                            onRequestSecurityMode = viewModel::requestSecurityMode,
                            onComplete = viewModel::completeOnboarding,
                            providersFocusRequester = onboardingProvidersFocusRequester,
                        )
                    }
                }

                TopLevelRoute.Preflight -> {
                    PreflightScreen(
                        state = preflight,
                        mode = state.mode,
                        onBack = { panel = FullScreenPanel.None },
                        onStart = {
                            viewModel.startMonitoring()
                            panel = FullScreenPanel.None
                        },
                        onRequestNotifications = {
                            if (!openNotificationSettings(context) { context.startActivity(it) }) {
                                snackbarScope.launch {
                                    snackbarHostState.showSnackbar(notificationSettingsUnavailable)
                                }
                            }
                        },
                    )
                }

                TopLevelRoute.Main -> {
                    MainNavigation(
                        state = state,
                        notificationsAllowed = notificationsAllowed,
                        destination = destination,
                        onDestination = { destination = it },
                        onPreflight = {
                            pendingPanelFocusRequester =
                                when (destination) {
                                    AppDestination.Home -> homePreflightFocusRequester
                                    AppDestination.Settings -> settingsHealthFocusRequester
                                    else -> null
                                }
                            panel = FullScreenPanel.Preflight
                        },
                        onProviders = {
                            pendingPanelFocusRequester = settingsProvidersFocusRequester
                            panel = FullScreenPanel.Providers
                        },
                        onConfiguration = {
                            pendingPanelFocusRequester = settingsStrategyFocusRequester
                            viewModel.resetConfigurationSaveState()
                            panel = FullScreenPanel.Configuration
                        },
                        onBatteryOptimizationSettings = {
                            if (!openBatteryOptimizationSettings { context.startActivity(it) }) {
                                snackbarScope.launch {
                                    snackbarHostState.showSnackbar(
                                        batteryOptimizationUnavailable,
                                    )
                                }
                            }
                        },
                        onPause = viewModel::pauseMonitoring,
                        onResume = viewModel::resumeMonitoring,
                        onStop = { stopConfirmationOpen = true },
                        receiveFocusRequester = receiveFocusRequester,
                        settingsProvidersFocusRequester = settingsProvidersFocusRequester,
                        settingsStrategyFocusRequester = settingsStrategyFocusRequester,
                        settingsHealthFocusRequester = settingsHealthFocusRequester,
                        homePreflightFocusRequester = homePreflightFocusRequester,
                        viewModel = viewModel,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (stopConfirmationOpen) {
            val visibleOpenPositionCount = stopConfirmationOpenPositionCount(route, state.openPositions.size)
            AlertDialog(
                onDismissRequest = { stopConfirmationOpen = false },
                title = { Text(stringResource(R.string.stop_confirmation_title)) },
                text = {
                    Text(
                        when {
                            visibleOpenPositionCount == null -> {
                                stringResource(R.string.stop_confirmation_locked_body)
                            }

                            visibleOpenPositionCount == 0 -> {
                                stringResource(R.string.stop_confirmation_body)
                            }

                            else -> {
                                pluralStringResource(
                                    R.plurals.stop_confirmation_open_positions_body,
                                    visibleOpenPositionCount,
                                    visibleOpenPositionCount,
                                )
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            stopConfirmationOpen = false
                            if (route == TopLevelRoute.SecureSessionLock) {
                                viewModel.requestAuthenticatedMonitoringStop()
                            } else {
                                viewModel.stopMonitoring()
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (route == TopLevelRoute.SecureSessionLock) {
                                    R.string.authenticate_and_stop_monitoring
                                } else {
                                    R.string.stop_monitoring
                                },
                            ),
                            color = StartExRed,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stopConfirmationOpen = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun MainNavigation(
    state: PersistedAppState,
    notificationsAllowed: Boolean,
    destination: AppDestination,
    onDestination: (AppDestination) -> Unit,
    onPreflight: () -> Unit,
    onProviders: () -> Unit,
    onConfiguration: () -> Unit,
    onBatteryOptimizationSettings: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    receiveFocusRequester: FocusRequester,
    settingsProvidersFocusRequester: FocusRequester,
    settingsStrategyFocusRequester: FocusRequester,
    settingsHealthFocusRequester: FocusRequester,
    homePreflightFocusRequester: FocusRequester,
    viewModel: StartExViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(destination, lifecycleOwner) {
        if (destination == AppDestination.Settings) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.refreshDeviceHealth(invalidateCurrent = false)
                    delay(DEVICE_HEALTH_REFRESH_INTERVAL_MILLIS)
                }
            }
        }
    }
    LaunchedEffect(
        destination,
        state.walletAddress,
        state.walletUnlocked,
        state.activatedProviders,
        state.demoMode,
    ) {
        if (
            !state.demoMode &&
            state.walletUnlocked &&
            state.walletAddress != null &&
            (destination == AppDestination.Home || destination == AppDestination.Wallet)
        ) {
            if (destination == AppDestination.Wallet) {
                viewModel.refreshWalletData()
            } else {
                viewModel.refreshWalletBalance()
            }
        }
    }
    Scaffold(
        containerColor = StartExBackground,
        bottomBar = {
            StartExNavigationBar(destination = destination, onDestination = onDestination)
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (destination) {
                AppDestination.Home -> {
                    HomeScreen(
                        state = state.homeScreenState(),
                        onPreflight = onPreflight,
                        onRecover = viewModel::recoverMonitoring,
                        onPause = onPause,
                        onResume = onResume,
                        onStop = onStop,
                        onSellNow = viewModel::requestSellNow,
                        onEmergencyExit = viewModel::requestEmergencyExit,
                        onStopAfterClose = viewModel::requestStopAfterClose,
                        preflightFocusRequester = homePreflightFocusRequester,
                    )
                }

                AppDestination.Watch -> {
                    WatchScreen(
                        candidates = state.candidates,
                        events = state.events,
                        demoMode = state.demoMode,
                    )
                }

                AppDestination.Wallet -> {
                    WalletScreen(
                        state = state.walletScreenState(),
                        onCreateWallet = viewModel::beginWalletCreation,
                        onRestoreWallet = viewModel::beginWalletRestore,
                        onReceive = { viewModel.showWalletOverlay(WalletOverlay.Receive) },
                        onSend = { viewModel.showWalletOverlay(WalletOverlay.Send) },
                        onTrustedAddresses = {
                            viewModel.showWalletOverlay(WalletOverlay.TrustedAddresses)
                        },
                        onReveal = viewModel::requestMnemonicReveal,
                        onLock = viewModel::lockWallet,
                        onRefreshBalance = viewModel::refreshWalletData,
                        receiveFocusRequester = receiveFocusRequester,
                    )
                }

                AppDestination.History -> {
                    HistoryScreen(
                        state = state.historyScreenState(),
                        onExportCsv = { viewModel.exportHistory(json = false) },
                        onExportJson = { viewModel.exportHistory(json = true) },
                    )
                }

                AppDestination.Settings -> {
                    SettingsScreen(
                        state = state,
                        notificationsAllowed = notificationsAllowed,
                        onProviders = onProviders,
                        onPreflight = onPreflight,
                        onStop = onStop,
                        onSetDemoMode = viewModel::setDemoMode,
                        onRequestSecurityMode = viewModel::requestSecurityMode,
                        onStrategyAndRisk = onConfiguration,
                        onBatteryOptimizationSettings = onBatteryOptimizationSettings,
                        providersFocusRequester = settingsProvidersFocusRequester,
                        strategyFocusRequester = settingsStrategyFocusRequester,
                        healthFocusRequester = settingsHealthFocusRequester,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun StartupErrorScreen(message: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = StartExRed,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun authenticationPromptInfo(
    context: Context,
    request: StartExUiEvent.Authenticate,
): BiometricPrompt.PromptInfo =
    BiometricPrompt.PromptInfo
        .Builder()
        .setTitle(context.getString(authenticationTitle(request.purpose)))
        .setSubtitle(context.getString(R.string.authentication_subtitle))
        .apply {
            if (request.operation == null) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    setNegativeButtonText(context.getString(R.string.cancel))
                } else {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                }
            } else {
                setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                setNegativeButtonText(context.getString(R.string.cancel))
            }
        }.build()

private fun authenticationTitle(purpose: AuthenticationPurpose): Int =
    when (purpose) {
        AuthenticationPurpose.CreateWallet -> R.string.authentication_create_wallet
        AuthenticationPurpose.SaveWallet -> R.string.authentication_save_wallet
        AuthenticationPurpose.UnlockWallet -> R.string.authentication_unlock_wallet
        AuthenticationPurpose.RevealMnemonic -> R.string.authentication_reveal_phrase
        AuthenticationPurpose.AddTrustedAddress -> R.string.authentication_trusted_address
        AuthenticationPurpose.DeleteTrustedAddress -> R.string.authentication_delete_trusted_address
        AuthenticationPurpose.UnlockTrustedAddress -> R.string.authentication_unlock_trusted_address
        AuthenticationPurpose.SubmitTransfer -> R.string.authentication_submit_transfer
        AuthenticationPurpose.ChangeSecurityMode -> R.string.authentication_change_security_mode
        AuthenticationPurpose.SellNow -> R.string.authentication_sell_now
        AuthenticationPurpose.EmergencyExit -> R.string.authentication_emergency_exit
        AuthenticationPurpose.RecoverMonitoring -> R.string.authentication_recover_monitoring
        AuthenticationPurpose.StopMonitoring -> R.string.authentication_stop_monitoring
    }

private fun startMonitoringService(context: Context): Boolean =
    startMonitoringService {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TradingMonitorService::class.java).setAction(TradingMonitorService.ACTION_START),
        )
    }

internal fun startMonitoringService(start: () -> Unit): Boolean =
    try {
        start()
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun stopMonitoringService(context: Context) {
    context.startService(
        Intent(context, TradingMonitorService::class.java).setAction(TradingMonitorService.ACTION_STOP),
    )
}

private fun pauseMonitoringService(context: Context) {
    context.startService(
        Intent(context, TradingMonitorService::class.java).setAction(TradingMonitorService.ACTION_PAUSE),
    )
}

private fun resumeMonitoringService(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, TradingMonitorService::class.java).setAction(TradingMonitorService.ACTION_RESUME),
    )
}

private fun recoverMonitoringService(
    context: Context,
    sessionId: String,
) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, TradingMonitorService::class.java)
            .setAction(TradingMonitorService.ACTION_RECOVER_AUTHENTICATED)
            .putExtra(TradingMonitorService.EXTRA_SESSION_ID, sessionId),
    )
}

private fun stopAuthenticatedMonitoringService(
    context: Context,
    sessionId: String,
) {
    context.startService(
        Intent(context, TradingMonitorService::class.java)
            .setAction(TradingMonitorService.ACTION_STOP_AUTHENTICATED)
            .putExtra(TradingMonitorService.EXTRA_SESSION_ID, sessionId),
    )
}

private fun sellNow(
    context: Context,
    positionId: String,
) {
    context.startService(sellNowServiceIntent(context, positionId))
}

private fun stopAfterClose(context: Context) {
    context.startService(stopAfterCloseServiceIntent(context))
}

private fun emergencyExit(context: Context) {
    context.startService(emergencyExitServiceIntent(context))
}

internal fun sellNowServiceIntent(
    context: Context,
    positionId: String,
): Intent =
    Intent(context, TradingMonitorService::class.java)
        .setAction(TradingMonitorService.ACTION_SELL_NOW)
        .putExtra(TradingMonitorService.EXTRA_POSITION_ID, positionId)

internal fun stopAfterCloseServiceIntent(context: Context): Intent =
    Intent(context, TradingMonitorService::class.java)
        .setAction(TradingMonitorService.ACTION_STOP_AFTER_CLOSE)

internal fun emergencyExitServiceIntent(context: Context): Intent =
    Intent(context, TradingMonitorService::class.java)
        .setAction(TradingMonitorService.ACTION_EMERGENCY_EXIT)

internal fun openBatteryOptimizationSettings(startActivity: (Intent) -> Unit): Boolean =
    try {
        startActivity(batteryOptimizationSettingsIntent())
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

internal fun batteryOptimizationSettingsIntent(): Intent = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

internal fun notificationSettingsIntent(context: Context): Intent =
    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)

internal fun openNotificationSettings(
    context: Context,
    startActivity: (Intent) -> Unit,
): Boolean =
    try {
        startActivity(notificationSettingsIntent(context))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

private const val DEVICE_HEALTH_REFRESH_INTERVAL_MILLIS = 5_000L

private fun shareText(
    context: Context,
    event: StartExUiEvent.ShareText,
): Boolean {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = event.mimeType
            putExtra(Intent.EXTRA_TEXT, event.text)
        }
    return try {
        context.startActivity(Intent.createChooser(intent, event.title))
        true
    } catch (_: RuntimeException) {
        false
    }
}
