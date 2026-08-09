package com.finnvek.startex.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finnvek.startex.R
import com.finnvek.startex.device.DeviceHealthEntryPolicy
import com.finnvek.startex.service.TradingMonitorService
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
import android.provider.Settings as AndroidSettings

private enum class FullScreenPanel {
    None,
    Preflight,
    Providers,
    Configuration,
}

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
fun StartExApp(viewModel: StartExViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val walletSetup by viewModel.walletSetup.collectAsStateWithLifecycle()
    val walletOverlay by viewModel.walletOverlay.collectAsStateWithLifecycle()
    val walletTransfer by viewModel.walletTransfer.collectAsStateWithLifecycle()
    val configurationSave by viewModel.configurationSave.collectAsStateWithLifecycle()
    var authenticationInProgress by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel, state.secureSession, authenticationInProgress) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (
                    event == Lifecycle.Event.ON_STOP &&
                    state.secureSession &&
                    !authenticationInProgress
                ) {
                    viewModel.lockWallet()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.events.collect { event ->
            when (event) {
                is StartExUiEvent.Authenticate -> {
                    authenticationInProgress = true
                    authenticate(
                        context = context,
                        request = event,
                        onSuccess = { request ->
                            authenticationInProgress = false
                            viewModel.onAuthenticationSucceeded(request)
                            if (
                                state.secureSession &&
                                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                            ) {
                                viewModel.lockWallet()
                            }
                        },
                        onFailure = {
                            authenticationInProgress = false
                            viewModel.onAuthenticationFailed()
                            if (
                                state.secureSession &&
                                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                            ) {
                                viewModel.lockWallet()
                            }
                        },
                    )
                }

                StartExUiEvent.StartMonitoringService -> {
                    startMonitoringService(context)
                }

                StartExUiEvent.PauseMonitoringService -> {
                    pauseMonitoringService(context)
                }

                StartExUiEvent.ResumeMonitoringService -> {
                    resumeMonitoringService(context)
                }

                StartExUiEvent.RecoverMonitoringService -> {
                    recoverMonitoringService(context)
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
                    shareText(context, event)
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
        viewModel = viewModel,
    )
}

@Composable
private fun StartExContent(
    state: PersistedAppState,
    walletSetup: WalletSetupState,
    walletOverlay: WalletOverlay,
    walletTransfer: WalletTransferState,
    configurationSave: ConfigurationSaveState,
    viewModel: StartExViewModel,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    var panel by rememberSaveable { mutableStateOf(FullScreenPanel.None) }
    var stopConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    var notificationsAllowed by rememberSaveable {
        mutableStateOf(context.hasNotificationPermission())
    }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> notificationsAllowed = granted }
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

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !state.loaded -> {
                LoadingScreen()
            }

            walletSetup !is WalletSetupState.Closed -> {
                WalletFlowScreen(
                    state = walletSetup,
                    onMnemonicSave = viewModel::showBackupChallenge,
                    onVerifyBackup = viewModel::verifyBackupChallenge,
                    onRestore = viewModel::previewRestoredWallet,
                    onSave = viewModel::requestWalletSave,
                    onCancel = viewModel::dismissWalletSetup,
                )
            }

            walletOverlay !is WalletOverlay.None -> {
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

            panel == FullScreenPanel.Providers -> {
                ProviderSetupScreen(
                    configuredProviders = state.configuredProviders,
                    activatedProviders = state.activatedProviders,
                    providerHealth = state.providerHealth,
                    onSave = viewModel::saveProviderKey,
                    onRemove = viewModel::removeProviderKey,
                    onTest = viewModel::testProvider,
                    onBack = { panel = FullScreenPanel.None },
                )
            }

            panel == FullScreenPanel.Configuration -> {
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

            !state.onboardingComplete -> {
                OnboardingScreen(
                    state = state,
                    onCreateWallet = viewModel::beginWalletCreation,
                    onRestoreWallet = viewModel::beginWalletRestore,
                    onConfigureProviders = { panel = FullScreenPanel.Providers },
                    onTrustedAddresses = {
                        viewModel.showWalletOverlay(WalletOverlay.TrustedAddresses)
                    },
                    onRequestSecurityMode = viewModel::requestSecurityMode,
                    onComplete = viewModel::completeOnboarding,
                )
            }

            !state.demoMode && state.walletAddress != null && !state.walletUnlocked -> {
                LockScreen(
                    address = state.walletAddress,
                    recoveryRequired = state.monitorState == MonitorState.NeedsAttention,
                    onUnlock = viewModel::requestWalletUnlock,
                    onRestore = viewModel::beginWalletRestore,
                    onStop = { stopConfirmationOpen = true },
                )
            }

            panel == FullScreenPanel.Preflight -> {
                PreflightScreen(
                    state = preflight,
                    mode = state.mode,
                    onBack = { panel = FullScreenPanel.None },
                    onStart = {
                        viewModel.startMonitoring()
                        panel = FullScreenPanel.None
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationsAllowed = true
                        }
                    },
                )
            }

            else -> {
                MainNavigation(
                    state = state,
                    destination = destination,
                    onDestination = { destination = it },
                    onPreflight = {
                        viewModel.refreshDeviceHealth()
                        panel = FullScreenPanel.Preflight
                    },
                    onProviders = { panel = FullScreenPanel.Providers },
                    onConfiguration = {
                        viewModel.resetConfigurationSaveState()
                        panel = FullScreenPanel.Configuration
                    },
                    onBatteryOptimizationSettings = { openBatteryOptimizationSettings(context) },
                    onPause = viewModel::pauseMonitoring,
                    onResume = viewModel::resumeMonitoring,
                    onStop = { stopConfirmationOpen = true },
                    viewModel = viewModel,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
        )
        if (stopConfirmationOpen) {
            AlertDialog(
                onDismissRequest = { stopConfirmationOpen = false },
                title = { Text(stringResource(R.string.stop_confirmation_title)) },
                text = {
                    Text(
                        if (state.openPositions.isEmpty()) {
                            stringResource(R.string.stop_confirmation_body)
                        } else {
                            pluralStringResource(
                                R.plurals.stop_confirmation_open_positions_body,
                                state.openPositions.size,
                                state.openPositions.size,
                            )
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            stopConfirmationOpen = false
                            viewModel.stopMonitoring()
                        },
                    ) {
                        Text(stringResource(R.string.stop_monitoring), color = StartExRed)
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
private fun MainNavigation(
    state: PersistedAppState,
    destination: AppDestination,
    onDestination: (AppDestination) -> Unit,
    onPreflight: () -> Unit,
    onProviders: () -> Unit,
    onConfiguration: () -> Unit,
    onBatteryOptimizationSettings: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    viewModel: StartExViewModel,
) {
    LaunchedEffect(
        destination,
        state.walletAddress,
        state.walletUnlocked,
        state.activatedProviders,
        state.demoMode,
    ) {
        if (destination == AppDestination.Settings) {
            viewModel.refreshDeviceHealth()
        }
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
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (destination) {
                AppDestination.Home -> {
                    HomeScreen(
                        state = state,
                        onPreflight = onPreflight,
                        onRecover = viewModel::recoverMonitoring,
                        onPause = onPause,
                        onResume = onResume,
                        onStop = onStop,
                        onSellNow = viewModel::requestSellNow,
                        onEmergencyExit = viewModel::requestEmergencyExit,
                        onStopAfterClose = viewModel::requestStopAfterClose,
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
                        state = state,
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
                    )
                }

                AppDestination.History -> {
                    HistoryScreen(
                        state = state,
                        onExportCsv = { viewModel.exportHistory(json = false) },
                        onExportJson = { viewModel.exportHistory(json = true) },
                    )
                }

                AppDestination.Settings -> {
                    SettingsScreen(
                        state = state,
                        onProviders = onProviders,
                        onPreflight = onPreflight,
                        onLock = viewModel::lockWallet,
                        onStop = onStop,
                        onSetDemoMode = viewModel::setDemoMode,
                        onRequestSecurityMode = viewModel::requestSecurityMode,
                        onStrategyAndRisk = onConfiguration,
                        onBatteryOptimizationSettings = onBatteryOptimizationSettings,
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

private fun authenticate(
    context: Context,
    request: StartExUiEvent.Authenticate,
    onSuccess: (StartExUiEvent.Authenticate) -> Unit,
    onFailure: () -> Unit,
) {
    val activity =
        context as? FragmentActivity ?: run {
            onFailure()
            return
        }
    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedRequest =
                        request.operation?.let { operation ->
                            val authenticatedOperation =
                                result.cryptoObject?.let(operation::bindAuthenticated)
                            if (authenticatedOperation == null) {
                                onFailure()
                                return
                            }
                            request.copy(operation = authenticatedOperation)
                        } ?: request
                    onSuccess(authenticatedRequest)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    onFailure()
                }

                override fun onAuthenticationFailed() = Unit
            },
        )
    val promptInfo =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(context.getString(authenticationTitle(request.purpose)))
            .setSubtitle(context.getString(R.string.authentication_subtitle))
            .apply {
                if (request.operation == null) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                } else {
                    setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    setNegativeButtonText(context.getString(R.string.cancel))
                }
            }.build()
    if (request.operation == null) {
        prompt.authenticate(promptInfo)
    } else {
        prompt.authenticate(promptInfo, request.operation.cryptoObject)
    }
}

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
    }

private fun startMonitoringService(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, TradingMonitorService::class.java).setAction(TradingMonitorService.ACTION_START),
    )
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

private fun recoverMonitoringService(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, TradingMonitorService::class.java)
            .setAction(TradingMonitorService.ACTION_RECOVER_AUTHENTICATED),
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

private fun openBatteryOptimizationSettings(context: Context) {
    context.startActivity(batteryOptimizationSettingsIntent())
}

internal fun batteryOptimizationSettingsIntent(): Intent = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

private fun shareText(
    context: Context,
    event: StartExUiEvent.ShareText,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = event.mimeType
            putExtra(Intent.EXTRA_TEXT, event.text)
        }
    context.startActivity(Intent.createChooser(intent, event.title))
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
