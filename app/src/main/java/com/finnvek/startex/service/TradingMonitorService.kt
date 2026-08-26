package com.finnvek.startex.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.finnvek.startex.MainActivity
import com.finnvek.startex.R
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.data.CandidateInsertResult
import com.finnvek.startex.data.RetentionPolicy
import com.finnvek.startex.data.local.AppEventEntity
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.DecisionEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TokenSnapshotEntity
import com.finnvek.startex.data.local.TradeIntentEntity
import com.finnvek.startex.data.settings.AppSettings
import com.finnvek.startex.device.DeviceHealthEntryPolicy
import com.finnvek.startex.formatUserNumber
import com.finnvek.startex.network.HeliusRealtimeEvent
import com.finnvek.startex.network.HeliusRealtimeListener
import com.finnvek.startex.network.HeliusSubscription
import com.finnvek.startex.network.JupiterTokenSnapshot
import com.finnvek.startex.network.JupiterTokensProvider
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.PumpPortalConnection
import com.finnvek.startex.network.PumpPortalDiscoveryProvider
import com.finnvek.startex.network.PumpPortalEvent
import com.finnvek.startex.network.PumpPortalEventKind
import com.finnvek.startex.network.PumpPortalEventListener
import com.finnvek.startex.network.RetryPolicy
import com.finnvek.startex.security.WalletAccessMode
import com.finnvek.startex.security.persistedWalletAccessMode
import com.finnvek.startex.trading.CircuitBreakerState
import com.finnvek.startex.trading.DefaultPaperCandidateSafetyProofSource
import com.finnvek.startex.trading.DefaultPaperRiskFactsSource
import com.finnvek.startex.trading.PaperCandidateCoordinator
import com.finnvek.startex.trading.PaperCandidateFilterConfigFactory
import com.finnvek.startex.trading.PaperCandidatePersistence
import com.finnvek.startex.trading.PaperCandidateRequest
import com.finnvek.startex.trading.PaperCandidateResult
import com.finnvek.startex.trading.PaperCandidateSafetyEvidence
import com.finnvek.startex.trading.PaperCandidateSafetyEvidenceSource
import com.finnvek.startex.trading.PaperCoordinatorDelay
import com.finnvek.startex.trading.PaperEntryFacts
import com.finnvek.startex.trading.PaperExitSafetySource
import com.finnvek.startex.trading.PaperFillPersistenceResult
import com.finnvek.startex.trading.PaperFinalStateGate
import com.finnvek.startex.trading.PaperPerformanceDelta
import com.finnvek.startex.trading.PaperPositionDelay
import com.finnvek.startex.trading.PaperPositionMonitor
import com.finnvek.startex.trading.PaperPositionPersistence
import com.finnvek.startex.trading.PaperPositionQuoteSource
import com.finnvek.startex.trading.PaperPositionResult
import com.finnvek.startex.trading.PaperRiskFacts
import com.finnvek.startex.trading.PaperRiskRuntimeSnapshot
import com.finnvek.startex.trading.PaperRiskRuntimeSnapshotSource
import com.finnvek.startex.trading.PaperSolEurRate
import com.finnvek.startex.trading.PaperSolEurRateSource
import com.finnvek.startex.trading.PaperSolUsdRate
import com.finnvek.startex.trading.PaperSolUsdRateSource
import com.finnvek.startex.trading.PaperSwapQuoteSource
import com.finnvek.startex.trading.WRAPPED_SOL_MINT
import com.finnvek.startex.trading.paperCircuitBreakerState
import com.finnvek.startex.trading.paperExitSafetyFacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TradingMonitorService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val stopDirective = AtomicReference<StopDirective?>()
    private val protectingOnly = AtomicBoolean(false)
    private val paperFinalStateGate = PaperFinalStateGate()
    private val activeSessionSnapshot = AtomicReference<BotSessionEntity?>()
    private val foregroundModel =
        AtomicReference(
            ForegroundNotificationModel(
                mode = "PAPER",
                sessionStatus = STATUS_NEEDS_ATTENTION,
                protectingOnly = false,
                openPositionCount = 0,
                sessionPnlLamports = null,
                lastMarketSuccessAtMillis = null,
                nowMillis = System.currentTimeMillis(),
            ),
        )

    @Volatile
    private var sessionJob: Job? = null

    @Volatile
    private var pausedSessionId: String? = null

    @Volatile
    private var retentionPolicy = RetentionPolicy()

    private val app: StartExApplication
        get() = application as StartExApplication

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            foregroundServiceType(),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val expectedSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        when (intent?.action) {
            null -> startSession(recoveryAuthenticated = false, restartOnly = true)
            ACTION_START -> startSession(recoveryAuthenticated = false)
            ACTION_RECOVER_AUTHENTICATED -> recoverAuthenticated(expectedSessionId)
            ACTION_PAUSE -> handlePauseAction(intent, startId)
            ACTION_RESUME -> handleResumeAction(intent, startId)
            ACTION_SELL_NOW -> requestPaperSell(intent.getStringExtra(EXTRA_POSITION_ID))
            ACTION_EMERGENCY_EXIT -> requestEmergencyPaperExit()
            ACTION_STOP_AFTER_CLOSE -> requestStopAfterClose()
            ACTION_STOP_AUTHENTICATED -> requestStop(StopDirective.UserRequested, expectedSessionId)
            ACTION_STOP -> requestStop(StopDirective.UserRequested)
            else -> requestStop(StopDirective.InvalidStart)
        }
        return START_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        requestStop(StopDirective.AndroidTimeout(startId))
    }

    override fun onDestroy() {
        stopDirective.compareAndSet(null, StopDirective.ServiceDestroyed)
        sessionJob?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(
        recoveryAuthenticated: Boolean,
        restartOnly: Boolean = false,
        expectedRecoverySessionId: String? = null,
    ) {
        if (sessionJob?.isActive == true) return
        pausedSessionId = null
        protectingOnly.set(false)
        stopDirective.set(null)
        updateNotification(paused = false)
        sessionJob =
            scope.launch {
                runSession(recoveryAuthenticated, restartOnly, expectedRecoverySessionId)
            }
    }

    private fun recoverAuthenticated(expectedSessionId: String?) {
        if (expectedSessionId.isNullOrBlank()) return
        if (sessionJob?.isActive != true) {
            startSession(
                recoveryAuthenticated = true,
                expectedRecoverySessionId = expectedSessionId,
            )
            return
        }
        if (activeSessionSnapshot.get()?.id != expectedSessionId) return
        scope.launch {
            val now = Instant.now()
            val reset =
                paperFinalStateGate.run {
                    app.repository.resetPaperCircuitBreaker(now, authenticated = true)
                }
            if (reset) {
                recordEvent(
                    severity = "INFO",
                    category = "RISK",
                    code = "CIRCUIT_BREAKER_RESET",
                    relatedId = null,
                    nowMillis = now.toEpochMilli(),
                )
            }
        }
    }

    private fun requestPaperSell(positionId: String?) {
        if (
            sessionJob?.isActive != true ||
            positionId.isNullOrBlank() ||
            positionId.length > MAXIMUM_POSITION_ID_LENGTH
        ) {
            if (sessionJob?.isActive != true) finishService(null)
            return
        }
        scope.launch {
            val position = app.repository.position(positionId) ?: return@launch
            if (position.mode != "PAPER" || position.status !in PAPER_POSITION_STATES) return@launch
            val now = System.currentTimeMillis()
            app.repository.savePosition(
                position.copy(
                    status = "EXIT_REQUESTED",
                    exitReason = "SELL_NOW",
                    updatedAtMillis = now,
                ),
            )
            recordEvent("WARN", "PAPER", "PAPER_SELL_NOW_REQUESTED", position.id, now)
        }
    }

    private fun requestStopAfterClose() {
        val activeSession = activeSessionSnapshot.get()
        if (sessionJob?.isActive != true || activeSession == null) {
            finishService(null)
            return
        }
        scope.launch {
            val now = System.currentTimeMillis()
            if (!app.repository.requestStopAfterClose(activeSession.id, now)) return@launch
            protectingOnly.set(true)
            activeSessionSnapshot.set(
                activeSession.copy(
                    status = STATUS_PROTECTING,
                    stopReason = "STOP_AFTER_CLOSE_REQUESTED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            recordEvent("INFO", "SESSION", "STOP_AFTER_CLOSE_REQUESTED", activeSession.id, now)
        }
    }

    private fun requestEmergencyPaperExit() {
        if (sessionJob?.isActive != true) {
            finishService(null)
            return
        }
        scope.launch {
            val active =
                app.repository.observeActiveSession(RECOVERABLE_SESSION_STATES).first()
                    ?: return@launch
            val now = System.currentTimeMillis()
            val updatedPositions = app.repository.requestPaperEmergencyExit(active.id, now)
            if (updatedPositions == 0) {
                requestStop(StopDirective.PositionsClosed)
                return@launch
            }
            protectingOnly.set(true)
            activeSessionSnapshot.set(
                active.copy(
                    status = STATUS_PROTECTING,
                    stopReason = "EMERGENCY_EXIT_REQUESTED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            recordEvent("WARN", "RISK", "PAPER_EMERGENCY_EXIT_REQUESTED", active.id, now)
        }
    }

    private fun requestStop(
        directive: StopDirective,
        expectedSessionId: String? = null,
    ) {
        if (expectedSessionId != null) {
            requestStopForExpectedSession(directive, expectedSessionId)
            return
        }
        stopDirective.compareAndSet(null, directive)
        val activeJob = sessionJob
        when {
            activeJob?.isActive == true -> activeJob.cancel()
            directive is StopDirective.UserRequested && pausedSessionId != null -> stopPausedSession(directive)
            directive is StopDirective.UserRequested -> stopPersistedSession(directive)
            else -> finishService(directive.startId)
        }
    }

    private fun requestStopForExpectedSession(
        directive: StopDirective,
        expectedSessionId: String,
    ) {
        if (expectedSessionId.isBlank()) return
        if (sessionJob?.isActive == true) {
            if (activeSessionSnapshot.get()?.id == expectedSessionId) requestStop(directive)
            return
        }
        if (pausedSessionId != null) {
            if (pausedSessionId == expectedSessionId) requestStop(directive)
            return
        }
        if (directive is StopDirective.UserRequested) {
            stopPersistedSession(directive, expectedSessionId)
        }
    }

    private fun handlePauseAction(
        intent: Intent,
        startId: Int,
    ) {
        if (
            acceptsNotificationAction(
                intent = intent,
                currentSessionId = activeSessionSnapshot.get()?.id,
                expectedState = sessionJob?.isActive == true,
            )
        ) {
            requestStop(StopDirective.Paused)
        } else {
            if (sessionJob?.isActive != true && pausedSessionId == null) finishService(startId)
        }
    }

    private fun handleResumeAction(
        intent: Intent,
        startId: Int,
    ) {
        if (
            acceptsNotificationAction(
                intent = intent,
                currentSessionId = pausedSessionId,
                expectedState = sessionJob?.isActive != true && pausedSessionId != null,
            )
        ) {
            startSession(recoveryAuthenticated = false)
        } else {
            if (sessionJob?.isActive != true && pausedSessionId == null) finishService(startId)
        }
    }

    private suspend fun runSession(
        recoveryAuthenticated: Boolean,
        restartOnly: Boolean,
        expectedRecoverySessionId: String?,
    ) {
        var activeSession: BotSessionEntity? = null
        try {
            activeSession =
                prepareSession(
                    recoveryAuthenticated,
                    restartOnly,
                    expectedRecoverySessionId,
                ) ?: return
            activeSessionSnapshot.set(activeSession)
            refreshForegroundNotification(activeSession)
            monitor(activeSession)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            stopDirective.compareAndSet(null, StopDirective.InternalFailure)
        } finally {
            withContext(NonCancellable) {
                val directive = stopDirective.get() ?: StopDirective.InternalFailure
                activeSession?.let { finalizeSession(it, directive) }
                if (directive is StopDirective.Paused && activeSession != null) {
                    pausedSessionId = activeSession.id
                    activeSessionSnapshot.set(activeSession.copy(status = STATUS_PAUSED))
                    updateNotification(paused = true)
                } else {
                    pausedSessionId = null
                    activeSessionSnapshot.set(null)
                    finishService(directive.startId)
                }
            }
            sessionJob = null
        }
    }

    private fun stopPausedSession(directive: StopDirective.UserRequested) {
        val sessionId = pausedSessionId ?: return finishService(directive.startId)
        pausedSessionId = null
        scope.launch {
            val now = System.currentTimeMillis()
            app.repository.updateSessionStatus(
                id = sessionId,
                status = directive.sessionStatus,
                stoppedAtMillis = now,
                stopReason = directive.reason,
                lastHeartbeatAtMillis = now,
            )
            recordEvent("INFO", "SESSION", directive.reason, sessionId, now)
            finishService(directive.startId)
        }
    }

    private fun stopPersistedSession(
        directive: StopDirective.UserRequested,
        expectedSessionId: String? = null,
    ) {
        scope.launch {
            val now = System.currentTimeMillis()
            val stopped =
                if (expectedSessionId == null) {
                    app.repository.stopLatestSession(
                        activeStates = RECOVERABLE_SESSION_STATES,
                        stoppedAtMillis = now,
                        stopReason = directive.reason,
                    )
                } else {
                    app.repository.stopSession(
                        id = expectedSessionId,
                        activeStates = RECOVERABLE_SESSION_STATES,
                        stoppedAtMillis = now,
                        stopReason = directive.reason,
                    )
                }
            stopped?.let { session ->
                recordEvent("INFO", "SESSION", directive.reason, session.id, now)
            }
            finishService(directive.startId)
        }
    }

    private suspend fun prepareSession(
        recoveryAuthenticated: Boolean,
        restartOnly: Boolean,
        expectedRecoverySessionId: String?,
    ): BotSessionEntity? {
        val now = System.currentTimeMillis()
        val settings = app.settings.settings.first()
        if (stopForDemoMode(settings, now)) return null
        val latest = latestConfiguration(now) ?: return null
        val preparation =
            prepareRecovery(
                settings,
                now,
                restartOnly,
                expectedRecoverySessionId,
            ) ?: return null
        val configuration = sessionConfiguration(preparation.recoverable, latest)
        if (configuration == null) {
            return stopBeforeMonitoring(
                existingSession = preparation.recoverable,
                strategy = latest.strategy,
                risk = latest.risk,
                mode = settings.operatingMode.name,
                reason = "SESSION_CONFIGURATION_MISSING",
                nowMillis = now,
            )
        }
        if (!candidateCapacityAvailable(preparation.policy, now)) {
            return stopBeforeMonitoring(
                existingSession = preparation.recoverable,
                strategy = configuration.strategy,
                risk = configuration.risk,
                mode = settings.operatingMode.name,
                reason = "CANDIDATE_STORAGE_CAP_REACHED",
                nowMillis = now,
            )
        }
        val preflight = monitoringPreflight(settings, preparation.recoverable, configuration.risk, now)
        if (preflight.startAction != SessionStartAction.START_MONITORING) {
            val reason =
                if (preflight.startAction == SessionStartAction.LIVE_EXECUTION_LOCKED) {
                    "LIVE_EXECUTION_LOCKED"
                } else {
                    "PREFLIGHT_FAILED"
                }
            return stopBeforeMonitoring(
                existingSession = preparation.recoverable,
                strategy = configuration.strategy,
                risk = configuration.risk,
                mode = settings.operatingMode.name,
                reason = reason,
                nowMillis = now,
            )
        }
        if (preparation.recoverable != null) {
            return recoverSession(
                session = preparation.recoverable,
                configuration = configuration,
                context = preflight.toRecoveryContext(recoveryAuthenticated, now),
            )
        }
        return createMonitoringSession(settings, configuration, preflight.providersHealthy, now)
    }

    private suspend fun stopForDemoMode(
        settings: AppSettings,
        nowMillis: Long,
    ): Boolean {
        if (!settings.demoMode) return false
        app.repository.observeActiveSession(RECOVERABLE_SESSION_STATES).first()?.let { session ->
            app.repository.updateSessionStatus(
                id = session.id,
                status = STATUS_NEEDS_ATTENTION,
                stoppedAtMillis = nowMillis,
                stopReason = StopDirective.ModeChanged.reason,
                lastHeartbeatAtMillis = nowMillis,
            )
        }
        stopDirective.compareAndSet(null, StopDirective.ModeChanged)
        return true
    }

    private suspend fun latestConfiguration(nowMillis: Long): SessionConfiguration? {
        val (strategy, risk) = app.repository.latestConfiguration()
        if (strategy != null && risk != null) return SessionConfiguration(strategy, risk)
        recordEvent("ERROR", "SESSION", "CONFIGURATION_MISSING", null, nowMillis)
        stopDirective.compareAndSet(null, StopDirective.PreflightFailed("CONFIGURATION_MISSING"))
        return null
    }

    private suspend fun prepareRecovery(
        settings: AppSettings,
        nowMillis: Long,
        restartOnly: Boolean,
        expectedRecoverySessionId: String?,
    ): SessionPreparation? {
        val policy = RetentionPolicy(settings.snapshotRetentionDays, settings.maximumStoredEvents)
        retentionPolicy = policy
        val heartbeatPolicy = SessionHeartbeatFreshnessPolicy()
        app.repository.expireStaleSessions(heartbeatPolicy.staleCutoffMillis(nowMillis), nowMillis)
        val recoverable = app.repository.observeActiveSession(RECOVERABLE_SESSION_STATES).first()
        if (expectedRecoverySessionId != null && recoverable?.id != expectedRecoverySessionId) {
            recordEvent("ERROR", "RECOVERY", "RECOVERY_STATE_CHANGED", expectedRecoverySessionId, nowMillis)
            stopDirective.compareAndSet(null, StopDirective.RestartNotRequired)
            return null
        }
        if (restartOnly && recoverable?.status !in AUTOMATIC_RESTART_SESSION_STATES) {
            stopDirective.compareAndSet(null, StopDirective.RestartNotRequired)
            return null
        }
        return SessionPreparation(policy, recoverable)
    }

    private suspend fun sessionConfiguration(
        recoverable: BotSessionEntity?,
        latest: SessionConfiguration,
    ): SessionConfiguration? {
        if (recoverable == null) return latest
        val strategy = app.database.configDao().strategyByVersion(recoverable.strategyVersion)
        val risk = app.database.configDao().riskByVersion(recoverable.riskVersion)
        return if (strategy == null || risk == null) null else SessionConfiguration(strategy, risk)
    }

    private suspend fun candidateCapacityAvailable(
        policy: RetentionPolicy,
        nowMillis: Long,
    ): Boolean =
        app.repository.cleanup(policy, nowMillis, ACTIVE_CANDIDATE_STATES).remainingCandidates <
            policy.maximumCandidates

    private suspend fun monitoringPreflight(
        settings: AppSettings,
        recoverable: BotSessionEntity?,
        risk: RiskConfigEntity,
        nowMillis: Long,
    ): MonitoringPreflight {
        val walletEnvelope = app.repository.walletSecretEnvelope()
        val accessMode = persistedWalletAccessMode(walletEnvelope?.keystoreAccessMode)
        val configured = requiredProviderKeysConfigured()
        val healthy = requiredProvidersHealthy(risk, nowMillis)
        val validRisk = risk.isValidForMonitoring()
        val freshStartPrerequisitesReady =
            !requiresFreshStartPrerequisites(recoverable) ||
                run {
                    val wallet = app.repository.walletProfile()
                    wallet != null &&
                        wallet.backupConfirmedAtMillis != null &&
                        walletEnvelope?.walletProfileId == wallet.id &&
                        canPostNotifications(
                            StartExApplication.CHANNEL_BOT_STATUS,
                            StartExApplication.CHANNEL_CRITICAL,
                        ) &&
                        DeviceHealthEntryPolicy.blockReason(currentDeviceHealth()) == null
                }
        val startAction =
            MonitoringSessionPolicy.startAction(
                operatingMode = settings.operatingMode,
                persistedSessionMode = recoverable?.mode,
                providersConfigured = configured,
                riskLimitsValid = validRisk,
                freshStartPrerequisitesReady = freshStartPrerequisitesReady,
            )
        return MonitoringPreflight(accessMode, configured, healthy, validRisk, startAction)
    }

    private suspend fun createMonitoringSession(
        settings: AppSettings,
        configuration: SessionConfiguration,
        providersHealthy: Boolean,
        nowMillis: Long,
    ): BotSessionEntity {
        val session =
            BotSessionEntity(
                id = UUID.randomUUID().toString(),
                mode = settings.operatingMode.name,
                status = STATUS_RUNNING,
                strategyVersion = configuration.strategy.version,
                riskVersion = configuration.risk.version,
                startedAtMillis = nowMillis,
                stoppedAtMillis = null,
                stopReason = null,
                lastHeartbeatAtMillis = nowMillis,
            )
        app.repository.saveSession(session)
        recordEvent("INFO", "SESSION", "MONITORING_STARTED", session.id, nowMillis)
        if (!providersHealthy) recordEvent("WARN", "PROVIDER", "HEALTH_CHECK_PENDING", session.id, nowMillis)
        return session
    }

    private suspend fun stopBeforeMonitoring(
        existingSession: BotSessionEntity?,
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
        mode: String,
        reason: String,
        nowMillis: Long,
    ): BotSessionEntity? {
        val session =
            existingSession ?: BotSessionEntity(
                id = UUID.randomUUID().toString(),
                mode = mode,
                status = STATUS_NEEDS_ATTENTION,
                strategyVersion = strategy.version,
                riskVersion = risk.version,
                startedAtMillis = nowMillis,
                stoppedAtMillis = nowMillis,
                stopReason = reason,
                lastHeartbeatAtMillis = nowMillis,
            ).also { app.repository.saveSession(it) }
        if (existingSession != null) {
            app.repository.updateSessionStatus(
                id = existingSession.id,
                status = STATUS_NEEDS_ATTENTION,
                stoppedAtMillis = nowMillis,
                stopReason = reason,
                lastHeartbeatAtMillis = nowMillis,
            )
        }
        recordEvent("ERROR", "SESSION", reason, session.id, nowMillis)
        stopDirective.compareAndSet(null, StopDirective.PreflightFailed(reason))
        return null
    }

    private suspend fun recoverSession(
        session: BotSessionEntity,
        configuration: SessionConfiguration,
        context: RecoveryContext,
    ): BotSessionEntity? {
        val originalConfigurationAvailable =
            session.strategyVersion == configuration.strategy.version && session.riskVersion == configuration.risk.version
        val action =
            MonitoringSessionPolicy.recoveryAction(
                accessMode = context.accessMode,
                recoveryAuthenticated = context.recoveryAuthenticated,
                providersConfigured = context.providersConfigured,
                providersHealthy = context.providersHealthy,
                riskLimitsValid = context.riskLimitsValid && originalConfigurationAvailable,
            )
        if (action == RecoveryAction.REQUIRE_AUTHENTICATION || action == RecoveryAction.NOTIFY_AND_STOP) {
            val reason =
                if (action == RecoveryAction.REQUIRE_AUTHENTICATION) {
                    "RECOVERY_AUTHENTICATION_REQUIRED"
                } else {
                    "RECOVERY_PREFLIGHT_FAILED"
                }
            app.repository.updateSessionStatus(
                id = session.id,
                status = STATUS_NEEDS_ATTENTION,
                stoppedAtMillis = context.nowMillis,
                stopReason = reason,
                lastHeartbeatAtMillis = context.nowMillis,
            )
            recordEvent("ERROR", "RECOVERY", reason, session.id, context.nowMillis)
            stopDirective.compareAndSet(null, StopDirective.PreflightFailed(reason))
            return null
        }

        if (context.recoveryAuthenticated) {
            paperFinalStateGate.run {
                app.repository.resetPaperCircuitBreaker(Instant.ofEpochMilli(context.nowMillis), authenticated = true)
            }
        }

        val recoveredStatus = if (session.status == STATUS_PROTECTING) STATUS_PROTECTING else STATUS_RUNNING
        protectingOnly.set(recoveredStatus == STATUS_PROTECTING)
        app.repository.updateSessionStatus(
            id = session.id,
            status = recoveredStatus,
            stoppedAtMillis = null,
            stopReason =
                if (recoveredStatus == STATUS_PROTECTING) {
                    "STOP_AFTER_CLOSE_REQUESTED"
                } else {
                    null
                },
            lastHeartbeatAtMillis = context.nowMillis,
        )
        recordEvent(
            "WARN",
            "RECOVERY",
            if (action == RecoveryAction.MONITOR_WITH_ENTRIES_BLOCKED) {
                "PROVIDER_HEALTH_RECOVERY"
            } else {
                "MONITOR_ONLY_RECOVERY"
            },
            session.id,
            context.nowMillis,
        )
        return session.copy(
            status = recoveredStatus,
            stoppedAtMillis = null,
            stopReason =
                if (recoveredStatus == STATUS_PROTECTING) {
                    "STOP_AFTER_CLOSE_REQUESTED"
                } else {
                    null
                },
            lastHeartbeatAtMillis = context.nowMillis,
        )
    }

    private suspend fun monitor(session: BotSessionEntity) =
        coroutineScope {
            val strategy = app.database.configDao().strategyByVersion(session.strategyVersion)
            val risk = app.database.configDao().riskByVersion(session.riskVersion)
            if (strategy == null || risk == null) {
                requestStop(StopDirective.InternalFailure)
                return@coroutineScope
            }
            val events = Channel<ObservedPumpEvent>(capacity = EVENT_BUFFER_CAPACITY)
            val candidates = Channel<String>(capacity = PAPER_CANDIDATE_BUFFER_CAPACITY)
            val dispatch = PaperCandidateDispatchPolicy(PAPER_CANDIDATE_BUFFER_CAPACITY)
            val coordinator = paperCandidateCoordinator(strategy, risk)
            val positionMonitor = paperPositionMonitor(risk)
            launch { stopWhenModeBecomesIncompatible(session) }
            launch { consumeEvents(session, events, candidates, dispatch) }
            launch { consumePaperCandidates(session, strategy, risk, candidates, dispatch, coordinator) }
            launch { monitorPaperPositions(risk, positionMonitor) }
            launch { heartbeat(session.id) }
            launch { probeHeliusContinuously() }
            launch { monitorWalletAccount(session) }
            launch { discover(session, events) }
        }

    private suspend fun stopWhenModeBecomesIncompatible(session: BotSessionEntity) {
        app.settings.settings.collect { settings ->
            if (!MonitoringSessionPolicy.isModeCompatible(settings.demoMode, settings.operatingMode, session.mode)) {
                stopDirective.compareAndSet(null, StopDirective.ModeChanged)
                throw IncompatibleModeException()
            }
        }
    }

    private suspend fun discover(
        session: BotSessionEntity,
        events: Channel<ObservedPumpEvent>,
    ) {
        val provider: PumpPortalDiscoveryProvider = app.pumpPortal
        val reconnectPolicy = DiscoveryReconnectPolicy()
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            val attempt = connectDiscovery(provider, events)

            val connection =
                when (val result = attempt.result) {
                    is ProviderResult.Success -> {
                        result.value
                    }

                    is ProviderResult.Failure -> {
                        val shouldContinue =
                            handleProviderFailure(
                                error = result.error,
                                failureCount = ++consecutiveFailures,
                                reconnectPolicy = reconnectPolicy,
                            )
                        if (!shouldContinue) return
                        continue
                    }
                }

            val disconnect =
                awaitDisconnect(
                    connection = connection,
                    disconnected = attempt.disconnected,
                    listenerActive = attempt.listenerActive,
                    lastEventAtMillis = attempt.lastEventAtMillis,
                )
            if (stopForDiscoveryBufferFull(session, disconnect)) return

            if (!attempt.firstEvent.get()) consecutiveFailures = 0
            val error = (disconnect as DiscoveryDisconnect.ProviderFailure).error
            val shouldContinue =
                handleProviderFailure(
                    error = error,
                    failureCount = ++consecutiveFailures,
                    reconnectPolicy = reconnectPolicy,
                )
            if (!shouldContinue) return
        }
    }

    private suspend fun connectDiscovery(
        provider: PumpPortalDiscoveryProvider,
        events: Channel<ObservedPumpEvent>,
    ): DiscoveryConnectionAttempt {
        val disconnected = CompletableDeferred<DiscoveryDisconnect>()
        val listenerActive = AtomicBoolean(true)
        val firstEvent = AtomicBoolean(true)
        val lastEventAtMillis = AtomicLong(System.currentTimeMillis())
        val result =
            provider.connect(
                object : PumpPortalEventListener {
                    override fun onEvent(event: PumpPortalEvent) {
                        if (!listenerActive.get()) return
                        val observed =
                            ObservedPumpEvent(
                                event = event,
                                observedAtMillis = System.currentTimeMillis(),
                                firstOnConnection = firstEvent.compareAndSet(true, false),
                            )
                        lastEventAtMillis.set(observed.observedAtMillis)
                        if (events.trySend(observed).isFailure) {
                            disconnected.complete(DiscoveryDisconnect.BufferFull)
                        }
                    }

                    override fun onError(error: ProviderError) {
                        if (listenerActive.get()) {
                            disconnected.complete(DiscoveryDisconnect.ProviderFailure(error))
                        }
                    }
                },
            )
        return DiscoveryConnectionAttempt(result, disconnected, listenerActive, firstEvent, lastEventAtMillis)
    }

    private suspend fun stopForDiscoveryBufferFull(
        session: BotSessionEntity,
        disconnect: DiscoveryDisconnect,
    ): Boolean {
        if (disconnect != DiscoveryDisconnect.BufferFull) return false
        recordEvent(
            severity = "ERROR",
            category = "PROVIDER",
            code = "DISCOVERY_BUFFER_FULL",
            relatedId = session.id,
            nowMillis = System.currentTimeMillis(),
        )
        requestStop(StopDirective.ProviderUnavailable("DISCOVERY_BUFFER_FULL"))
        return true
    }

    private suspend fun awaitDisconnect(
        connection: PumpPortalConnection,
        disconnected: CompletableDeferred<DiscoveryDisconnect>,
        listenerActive: AtomicBoolean,
        lastEventAtMillis: AtomicLong,
    ): DiscoveryDisconnect {
        try {
            val freshnessPolicy = DiscoveryFreshnessPolicy()
            while (currentCoroutineContext().isActive) {
                val providerDisconnect =
                    withTimeoutOrNull(STALE_CHECK_INTERVAL_MILLIS) {
                        disconnected.await()
                    }
                if (providerDisconnect != null) return providerDisconnect

                val now = System.currentTimeMillis()
                if (freshnessPolicy.isStale(lastEventAtMillis.get(), now)) {
                    return DiscoveryDisconnect.ProviderFailure(
                        ProviderError.StaleData(
                            provider = ProviderId.PUMP_PORTAL,
                            ageMillis = now - lastEventAtMillis.get(),
                        ),
                    )
                }
            }
            return DiscoveryDisconnect.ProviderFailure(
                ProviderError.NetworkUnavailable(ProviderId.PUMP_PORTAL),
            )
        } finally {
            listenerActive.set(false)
            connection.close()
        }
    }

    private suspend fun handleProviderFailure(
        error: ProviderError,
        failureCount: Int,
        reconnectPolicy: DiscoveryReconnectPolicy,
    ): Boolean {
        val now = System.currentTimeMillis()
        val reconnectDelay =
            reconnectPolicy.nextDelayMillis(
                failureCount = failureCount,
                retryAfterMillis = error.retryAfterMillis,
            )
        recordPumpPortalFailure(error, failureCount, reconnectDelay != null, now)
        if (reconnectDelay == null) {
            requestStop(StopDirective.ProviderUnavailable(error.redactedCode()))
            return false
        }
        delay(reconnectDelay)
        return true
    }

    private suspend fun consumeEvents(
        session: BotSessionEntity,
        events: Channel<ObservedPumpEvent>,
        candidates: Channel<String>,
        dispatch: PaperCandidateDispatchPolicy,
    ) {
        var lastPumpHealthAtMillis = 0L
        for (observed in events) {
            lastPumpHealthAtMillis =
                consumeEvent(session, observed, candidates, dispatch, lastPumpHealthAtMillis) ?: return
        }
    }

    private suspend fun consumeEvent(
        session: BotSessionEntity,
        observed: ObservedPumpEvent,
        candidates: Channel<String>,
        dispatch: PaperCandidateDispatchPolicy,
        lastPumpHealthAtMillis: Long,
    ): Long? {
        val candidateInsert = persistCandidate(observed)
        if (candidateInsert == CandidateInsertResult.CAP_REACHED) {
            recordEvent(
                severity = "ERROR",
                category = "STORAGE",
                code = "CANDIDATE_STORAGE_CAP_REACHED",
                relatedId = session.id,
                nowMillis = observed.observedAtMillis,
            )
            requestStop(StopDirective.StorageCapacity)
            return null
        }
        val latestHealthWrite = recordPumpHealthIfNeeded(observed, lastPumpHealthAtMillis)
        recordDiscoveryEvent(observed)
        app.repository.touchSessionHeartbeat(session.id, observed.observedAtMillis)
        if (candidateInsert == CandidateInsertResult.INSERTED && !protectingOnly.get()) {
            dispatchCandidate(observed, candidates, dispatch)
        }
        return latestHealthWrite
    }

    private suspend fun recordPumpHealthIfNeeded(
        observed: ObservedPumpEvent,
        lastPumpHealthAtMillis: Long,
    ): Long {
        val writeRequired =
            observed.firstOnConnection ||
                observed.observedAtMillis - lastPumpHealthAtMillis >= PROVIDER_HEALTH_WRITE_INTERVAL_MILLIS
        if (!writeRequired) return lastPumpHealthAtMillis
        recordPumpPortalHealthy(observed.observedAtMillis)
        return observed.observedAtMillis
    }

    private suspend fun recordDiscoveryEvent(observed: ObservedPumpEvent) {
        val code =
            if (observed.event.kind == PumpPortalEventKind.NEW_TOKEN) {
                "CANDIDATE_DISCOVERED"
            } else {
                "MIGRATION_DISCOVERED"
            }
        recordEvent("INFO", "DISCOVERY", code, observed.event.mint, observed.observedAtMillis)
    }

    private suspend fun dispatchCandidate(
        observed: ObservedPumpEvent,
        candidates: Channel<String>,
        dispatch: PaperCandidateDispatchPolicy,
    ) {
        val mint = observed.event.mint
        when (dispatch.tryAdmit(mint)) {
            PaperCandidateDispatch.ADMIT -> {
                if (candidates.trySend(mint).isFailure) {
                    dispatch.complete(mint)
                    rejectFullObservationQueue(observed)
                }
            }

            PaperCandidateDispatch.DUPLICATE -> {
                Unit
            }

            PaperCandidateDispatch.FULL -> {
                rejectFullObservationQueue(observed)
            }
        }
    }

    private suspend fun rejectFullObservationQueue(observed: ObservedPumpEvent) {
        rejectObservationQueueCandidate(observed.event.mint, observed.observedAtMillis)
        recordEvent(
            severity = "WARN",
            category = "CANDIDATE",
            code = "OBSERVATION_QUEUE_FULL",
            relatedId = observed.event.mint,
            nowMillis = observed.observedAtMillis,
        )
    }

    private suspend fun rejectObservationQueueCandidate(
        mint: String,
        nowMillis: Long,
    ) {
        val candidate = app.database.candidateDao().byMint(mint) ?: return
        app.repository.saveCandidate(
            candidate.copy(
                state = "REJECTED",
                rejectionCode = "OBSERVATION_QUEUE_FULL",
                lastUpdatedAtMillis = nowMillis,
            ),
        )
    }

    @Suppress("LongParameterList")
    private suspend fun consumePaperCandidates(
        session: BotSessionEntity,
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
        mints: Channel<String>,
        dispatch: PaperCandidateDispatchPolicy,
        coordinator: PaperCandidateCoordinator,
    ) {
        for (mint in mints) {
            try {
                if (protectingOnly.get()) continue
                val candidate = app.database.candidateDao().byMint(mint) ?: continue
                val deviceHealth = currentDeviceHealth()
                val deviceBlockReason = DeviceHealthEntryPolicy.blockReason(deviceHealth)
                if (deviceBlockReason != null) {
                    val now = System.currentTimeMillis()
                    app.repository.saveCandidate(
                        candidate.copy(
                            state = "REJECTED",
                            rejectionCode = "DEVICE_${deviceBlockReason.name}",
                            lastUpdatedAtMillis = now,
                        ),
                    )
                    recordEvent(
                        severity = "WARN",
                        category = "DEVICE",
                        code = "ENTRY_BLOCKED_${deviceBlockReason.name}",
                        relatedId = mint,
                        nowMillis = now,
                    )
                    continue
                }
                val result =
                    coordinator.process(
                        PaperCandidateRequest(
                            sessionId = session.id,
                            candidate = candidate,
                            strategy = strategy,
                            risk = risk,
                        ),
                    )
                when (result) {
                    PaperCandidateResult.Duplicate -> {
                        Unit
                    }

                    is PaperCandidateResult.Filled -> {
                        recordEvent(
                            severity = "INFO",
                            category = "PAPER",
                            code = "PAPER_POSITION_OPENED",
                            relatedId = result.positionId,
                            nowMillis = System.currentTimeMillis(),
                        )
                    }

                    is PaperCandidateResult.Rejected -> {
                        val now = System.currentTimeMillis()
                        val safetyCode =
                            when {
                                "RISK_DAILY_LOSS_LIMIT" in result.reasons -> "DAILY_LOSS_LIMIT_REACHED"

                                "RISK_CIRCUIT_BREAKER_ACTIVE" in result.reasons ||
                                    "RISK_DAILY_FEE_LIMIT" in result.reasons ||
                                    "RISK_PROVIDER_HEALTH" in result.reasons -> "CIRCUIT_BREAKER_ACTIVE"

                                "RISK_WALLET_RESERVE" in result.reasons -> "LOW_SOL_RESERVE"

                                else -> null
                            }
                        if (safetyCode != null) {
                            recordEvent("WARN", "RISK", safetyCode, null, now)
                        }
                        recordEvent("INFO", "CANDIDATE", "CANDIDATE_REJECTED", mint, now)
                    }
                }
            } finally {
                dispatch.complete(mint)
            }
        }
    }

    private suspend fun currentDeviceHealth() =
        app.deviceHealth.snapshot(
            providerRttMillis =
                app.repository
                    .providerHealth()
                    .filter { it.latencyMillis != null }
                    .maxByOrNull { it.updatedAtMillis }
                    ?.latencyMillis,
            lastEventAtMillis =
                app.repository
                    .observeRecentEvents(1)
                    .first()
                    .firstOrNull()
                    ?.createdAtMillis,
        )

    private fun paperCandidateCoordinator(
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
    ) = PaperCandidateCoordinator(
        tokens =
            JupiterTokensProvider { mint ->
                val startedAt = System.currentTimeMillis()
                val result = app.jupiterTokens.tokenSnapshot(mint)
                recordProviderResult(
                    provider = ProviderId.JUPITER,
                    result = result,
                    startedAtMillis = startedAt,
                )
                result
            },
        quotes =
            PaperSwapQuoteSource { request ->
                val startedAt = System.currentTimeMillis()
                val result = app.jupiterSwap.order(request)
                recordProviderResult(
                    provider = ProviderId.JUPITER,
                    result = result,
                    startedAtMillis = startedAt,
                )
                result
            },
        safetyProofs = paperCandidateSafetySource(strategy, risk),
        riskFacts = paperRiskFactsSource(),
        persistence =
            object : PaperCandidatePersistence {
                override suspend fun persistCandidate(candidate: TokenCandidateEntity) {
                    app.repository.saveCandidate(candidate)
                }

                override suspend fun persistSnapshot(snapshot: TokenSnapshotEntity): Long = app.repository.addSnapshot(snapshot)

                override suspend fun persistDecision(
                    candidate: TokenCandidateEntity,
                    decision: DecisionEntity,
                ) {
                    app.repository.recordDecision(candidate, decision)
                }

                override suspend fun currentRiskFacts(
                    sessionId: String,
                    risk: RiskConfigEntity,
                    now: Instant,
                    baseline: PaperRiskFacts,
                ): PaperRiskFacts? = currentPaperRiskFacts(sessionId, risk, now, baseline)

                override suspend fun persistCircuitBreaker(
                    breaker: CircuitBreakerState,
                    now: Instant,
                ) {
                    app.repository.savePaperCircuitBreaker(breaker, now)
                }

                override suspend fun persistPaperFill(facts: PaperEntryFacts): PaperFillPersistenceResult {
                    if (protectingOnly.get()) return PaperFillPersistenceResult.UNSUPPORTED
                    app.repository.recordPaperFill(
                        candidate = facts.candidate,
                        decision = facts.decision,
                        intent = facts.intent,
                        position = facts.position,
                        performanceEpochDay = facts.performanceEpochDay,
                        performanceDelta = facts.performanceDelta,
                        performanceAtMillis = facts.performanceAtMillis,
                    )
                    return PaperFillPersistenceResult.PERSISTED
                }
            },
        filterConfig = PaperCandidateFilterConfigFactory.create(strategy, risk),
        delay = PaperCoordinatorDelay { duration -> delay(duration.toMillis()) },
        finalStateGate = paperFinalStateGate,
    )

    private fun paperCandidateSafetySource(
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
    ) = DefaultPaperCandidateSafetyProofSource(
        evidenceSource = PaperCandidateSafetyEvidenceSource { mint -> paperCandidateSafetyEvidence(mint) },
        solUsdRates = PaperSolUsdRateSource { paperSolUsdRate() },
        maximumSourceAge =
            Duration.ofMillis(
                minOf(strategy.maximumCandidateAgeMillis, risk.maximumCandidateAgeMillis),
            ),
    )

    private suspend fun paperCandidateSafetyEvidence(mint: String): PaperCandidateSafetyEvidence? {
        val candidate = app.database.candidateDao().byMint(mint) ?: return null
        if (candidate.source !in PUMP_CANDIDATE_SOURCES || candidate.discoverySignature.isNullOrBlank()) return null

        val tokenStartedAt = System.currentTimeMillis()
        val tokenResult = app.jupiterTokens.tokenSnapshot(mint)
        recordProviderResult(ProviderId.JUPITER, tokenResult, tokenStartedAt)
        val token = (tokenResult as? ProviderResult.Success)?.value ?: return null

        val accountStartedAt = System.currentTimeMillis()
        val accountResult = app.heliusRpc.getAccountInfo(mint)
        recordProviderResult(ProviderId.HELIUS, accountResult, accountStartedAt)
        val accountSuccess = accountResult as? ProviderResult.Success ?: return null
        val account = accountSuccess.value ?: return null
        if (account.executable || account.owner != token.tokenProgram) return null

        return PaperCandidateSafetyEvidence(
            pumpMint = mint,
            suspiciousWalletActivity =
                token.audit.isSuspicious ||
                    !token.audit.mintAuthorityDisabled ||
                    !token.audit.freezeAuthorityDisabled,
            quoteSemanticsValidated = false,
            unsupportedRouteBehavior = true,
            observedAt =
                Instant.ofEpochMilli(
                    minOf(candidate.discoveredAtMillis, token.updatedAtMillis, accountSuccess.receivedAtMillis),
                ),
            // Parsed Token-2022 extensions are not exposed by the current RPC adapter.
            // Leaving this absent deliberately makes Token-2022 candidates fail closed.
            token2022ExtensionProof = null,
        )
    }

    private suspend fun paperSolUsdRate(): PaperSolUsdRate? {
        val startedAt = System.currentTimeMillis()
        val result = app.jupiterTokens.tokenSnapshot(WRAPPED_SOL_MINT)
        recordProviderResult(ProviderId.JUPITER, result, startedAt)
        val token = (result as? ProviderResult.Success)?.value ?: return null
        if (
            token.mint != WRAPPED_SOL_MINT ||
            token.decimals != SOL_DECIMALS ||
            token.tokenProgram != LEGACY_TOKEN_PROGRAM_ID
        ) {
            return null
        }
        val rate = token.usdPrice?.takeIf { it.signum() > 0 } ?: return null
        // One WSOL represents one SOL, so its unit USD price is the exact USD/SOL conversion.
        return PaperSolUsdRate(rate, Instant.ofEpochMilli(token.updatedAtMillis))
    }

    private fun paperRiskFactsSource() =
        DefaultPaperRiskFactsSource(
            runtimeSnapshots =
                PaperRiskRuntimeSnapshotSource snapshot@{ _, now ->
                    val wallet = app.repository.walletProfile() ?: return@snapshot null
                    val balanceStartedAt = System.currentTimeMillis()
                    val balanceResult = app.heliusRpc.getBalance(wallet.publicAddress)
                    recordProviderResult(ProviderId.HELIUS, balanceResult, balanceStartedAt)
                    val balance =
                        when (balanceResult) {
                            is ProviderResult.Success -> balanceResult.value
                            is ProviderResult.Failure -> return@snapshot null
                        }
                    val day = now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    val performance = paperDailyPerformance(day, now)
                    val failedAt = app.repository.latestFailedTradeAt(FAILED_TRADE_STATUSES)
                    val circuitBreaker = performance.paperCircuitBreakerState() ?: return@snapshot null
                    paperRiskRuntimeSnapshot(
                        now = now,
                        walletBalanceLamports = balance.lamports,
                        performance = performance,
                        failedAtMillis = failedAt,
                        circuitBreaker = circuitBreaker,
                    )
                },
            solEurRates =
                PaperSolEurRateSource rate@{
                    val startedAt = System.currentTimeMillis()
                    val result = app.fiatRates.solEurRate()
                    recordProviderResult(ProviderId.KRAKEN, result, startedAt)
                    val rate =
                        when (result) {
                            is ProviderResult.Success -> result.value
                            is ProviderResult.Failure -> return@rate null
                        }
                    PaperSolEurRate(rate.eurPerSol, Instant.ofEpochMilli(rate.observedAtMillis))
                },
        )

    private suspend fun currentPaperRiskFacts(
        sessionId: String,
        risk: RiskConfigEntity,
        now: Instant,
        baseline: PaperRiskFacts,
    ): PaperRiskFacts? {
        val day = now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        val performance = paperDailyPerformance(day, now)
        val failedAt = app.repository.latestFailedTradeAt(FAILED_TRADE_STATUSES)
        val circuitBreaker = performance.paperCircuitBreakerState() ?: return null
        val current =
            DefaultPaperRiskFactsSource(
                runtimeSnapshots =
                    PaperRiskRuntimeSnapshotSource { requestedSessionId, requestedNow ->
                        if (requestedSessionId != sessionId || requestedNow != now) {
                            null
                        } else {
                            paperRiskRuntimeSnapshot(
                                now = now,
                                walletBalanceLamports = baseline.snapshot.walletBalance.value,
                                performance = performance,
                                failedAtMillis = failedAt,
                                circuitBreaker = circuitBreaker,
                            )
                        }
                    },
                solEurRates = PaperSolEurRateSource { null },
            ).facts(sessionId, risk, now) ?: return null
        return current.copy(approximateTradeEur = baseline.approximateTradeEur)
    }

    private suspend fun paperRiskRuntimeSnapshot(
        now: Instant,
        walletBalanceLamports: Long,
        performance: DailyPerformanceEntity,
        failedAtMillis: Long?,
        circuitBreaker: CircuitBreakerState,
    ) = PaperRiskRuntimeSnapshot(
        observedAt = now,
        walletBalanceLamports = walletBalanceLamports,
        openPositions = app.repository.openPositions(PAPER_POSITION_STATES),
        rollingTradeTimes =
            app.repository
                .tradeTimesSince(now.minus(Duration.ofHours(24)).toEpochMilli())
                .map(Instant::ofEpochMilli),
        dailyPerformance = performance,
        providerHealth = app.repository.providerHealth(),
        lastLossAt = performance.lastLossAtMillis?.let(Instant::ofEpochMilli),
        lastFailedTransactionAt = failedAtMillis?.let(Instant::ofEpochMilli),
        circuitBreaker = circuitBreaker,
    )

    private suspend fun paperDailyPerformance(
        day: Long,
        now: Instant,
    ): DailyPerformanceEntity {
        app.repository.dailyPerformance(day, DailyPerformanceEntity.MODE_PAPER)?.let { return it }
        val latest = app.repository.latestDailyPerformance(DailyPerformanceEntity.MODE_PAPER)
        if (latest != null && latest.epochDay >= day) return latest
        val previous = latest?.takeIf { it.epochDay < day }
        return DailyPerformanceEntity(
            epochDay = day,
            grossPnlLamports = 0,
            netPnlLamports = 0,
            totalFeesLamports = 0,
            tradeCount = 0,
            winCount = 0,
            lossCount = 0,
            consecutiveLosses = previous?.consecutiveLosses ?: 0,
            updatedAtMillis = now.toEpochMilli(),
            mode = DailyPerformanceEntity.MODE_PAPER,
            lastLossAtMillis = previous?.lastLossAtMillis,
            circuitBreakerReason = previous?.circuitBreakerReason,
            circuitBreakerActivatedAtMillis = previous?.circuitBreakerActivatedAtMillis,
            circuitBreakerResetAfterMillis = previous?.circuitBreakerResetAfterMillis,
        )
    }

    @Suppress("LongMethod")
    private fun paperPositionMonitor(risk: RiskConfigEntity) =
        PaperPositionMonitor(
            quotes =
                PaperPositionQuoteSource { request ->
                    val startedAt = System.currentTimeMillis()
                    val result = app.jupiterSwap.order(request)
                    recordProviderResult(ProviderId.JUPITER, result, startedAt)
                    result
                },
            safety =
                PaperExitSafetySource safety@{ position, now ->
                    val tokenStartedAt = System.currentTimeMillis()
                    val tokenResult = app.jupiterTokens.tokenSnapshot(position.mint)
                    recordProviderResult(ProviderId.JUPITER, tokenResult, tokenStartedAt)
                    val token =
                        when (tokenResult) {
                            is ProviderResult.Success -> tokenResult.value
                            is ProviderResult.Failure -> return@safety null
                        }
                    val accountStartedAt = System.currentTimeMillis()
                    val accountResult = app.heliusRpc.getAccountInfo(position.mint)
                    recordProviderResult(ProviderId.HELIUS, accountResult, accountStartedAt)
                    val account =
                        when (accountResult) {
                            is ProviderResult.Success -> accountResult.value
                            is ProviderResult.Failure -> return@safety null
                        } ?: return@safety null
                    paperExitSafetyFacts(
                        token = token,
                        account = account,
                        nowMillis = now.toEpochMilli(),
                        maximumAgeMillis = risk.minimumDataFreshnessMillis,
                    )
                },
            persistence =
                object : PaperPositionPersistence {
                    override suspend fun save(position: PositionEntity) {
                        app.repository.savePosition(position)
                    }

                    override suspend fun saveAttempt(intent: TradeIntentEntity) {
                        app.repository.savePaperExitAttempt(intent)
                    }

                    override suspend fun close(
                        position: PositionEntity,
                        intent: TradeIntentEntity,
                        performanceEpochDay: Long,
                        performanceDelta: PaperPerformanceDelta,
                        performanceAtMillis: Long,
                    ) {
                        app.repository.recordPaperExit(
                            position = position,
                            intent = intent,
                            performanceEpochDay = performanceEpochDay,
                            performanceDelta = performanceDelta,
                            performanceAtMillis = performanceAtMillis,
                        )
                    }
                },
            delay = PaperPositionDelay { duration -> delay(duration.toMillis()) },
            finalStateGate = paperFinalStateGate,
        )

    private suspend fun monitorPaperPositions(
        risk: RiskConfigEntity,
        monitor: PaperPositionMonitor,
    ) {
        while (currentCoroutineContext().isActive) {
            val positions = app.repository.openPositions(PAPER_POSITION_STATES)
            if (positions.isEmpty() && protectingOnly.get()) {
                requestStop(StopDirective.PositionsClosed)
                return
            }
            for (position in positions) {
                when (val result = monitor.check(position, risk, sellNow = false)) {
                    is PaperPositionResult.Monitored -> {
                        Unit
                    }

                    is PaperPositionResult.Closed -> {
                        recordEvent(
                            severity = "INFO",
                            category = "PAPER",
                            code = "PAPER_POSITION_CLOSED",
                            relatedId = result.position.id,
                            nowMillis = System.currentTimeMillis(),
                        )
                    }

                    is PaperPositionResult.ExitBlocked -> {
                        recordEvent(
                            severity = "ERROR",
                            category = "PAPER",
                            code = "PAPER_EXIT_BLOCKED",
                            relatedId = result.position.id,
                            nowMillis = System.currentTimeMillis(),
                        )
                    }
                }
            }
            delay(POSITION_MONITOR_INTERVAL_MILLIS)
        }
    }

    private suspend fun monitorWalletAccount(session: BotSessionEntity) {
        val wallet = app.repository.walletProfile() ?: return
        var lastBalance = initialWalletBalance(wallet.publicAddress)
        val retryPolicy =
            RetryPolicy(
                initialDelayMillis = WALLET_REALTIME_INITIAL_RETRY_MILLIS,
                maximumDelayMillis = WALLET_REALTIME_MAXIMUM_RETRY_MILLIS,
            )
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val signals = Channel<WalletRealtimeSignal>(WALLET_REALTIME_BUFFER_CAPACITY)
            val listenerActive = AtomicBoolean(true)
            val startedAt = System.currentTimeMillis()
            val connectionResult = connectWalletAccount(wallet.publicAddress, signals, listenerActive)
            val connection =
                when (connectionResult) {
                    is ProviderResult.Success -> {
                        connectionResult.value
                    }

                    is ProviderResult.Failure -> {
                        recordProviderResult(ProviderId.HELIUS, connectionResult, startedAt)
                        failures += 1
                        delay(retryPolicy.delayMillis(failures, connectionResult.error.retryAfterMillis))
                        continue
                    }
                }
            val progress =
                try {
                    consumeWalletSignals(session, signals, lastBalance, failures)
                } finally {
                    listenerActive.set(false)
                    connection.close()
                }
            lastBalance = progress.lastBalance
            failures = progress.failures
            delay(retryPolicy.delayMillis(failures.coerceAtLeast(1)))
        }
    }

    private suspend fun initialWalletBalance(address: String): Long? {
        val startedAt = System.currentTimeMillis()
        val result = app.heliusRpc.getBalance(address)
        recordProviderResult(ProviderId.HELIUS, result, startedAt)
        return (result as? ProviderResult.Success)?.value?.lamports
    }

    private suspend fun connectWalletAccount(
        address: String,
        signals: Channel<WalletRealtimeSignal>,
        listenerActive: AtomicBoolean,
    ) = app.heliusWebSocket.connect(
        subscriptions = setOf(HeliusSubscription.Account(address)),
        listener =
            object : HeliusRealtimeListener {
                override fun onEvent(event: HeliusRealtimeEvent) {
                    if (listenerActive.get()) signals.trySend(WalletRealtimeSignal.Event(event))
                }

                override fun onError(error: ProviderError) {
                    if (listenerActive.get()) signals.trySend(WalletRealtimeSignal.Failure(error))
                }
            },
    )

    private suspend fun consumeWalletSignals(
        session: BotSessionEntity,
        signals: Channel<WalletRealtimeSignal>,
        initialBalance: Long?,
        initialFailures: Int,
    ): WalletMonitorProgress {
        var lastBalance = initialBalance
        var failures = initialFailures
        while (currentCoroutineContext().isActive) {
            when (val signal = signals.receive()) {
                is WalletRealtimeSignal.Failure -> {
                    val failedAt = System.currentTimeMillis()
                    recordProviderResult(
                        ProviderId.HELIUS,
                        ProviderResult.Failure(signal.error),
                        failedAt,
                    )
                    return WalletMonitorProgress(lastBalance, failures + 1)
                }

                is WalletRealtimeSignal.Event -> {
                    val event = signal.value
                    if (event !is HeliusRealtimeEvent.AccountChanged) continue
                    val now = System.currentTimeMillis()
                    recordProviderResult(
                        ProviderId.HELIUS,
                        ProviderResult.Success(event, now),
                        now,
                    )
                    recordEvent(
                        "INFO",
                        "WALLET",
                        walletAccountEventCode(lastBalance, event.lamports),
                        session.id,
                        now,
                    )
                    lastBalance = event.lamports
                    failures = 0
                }
            }
        }
        return WalletMonitorProgress(lastBalance, failures)
    }

    private suspend fun probeHeliusContinuously() {
        while (currentCoroutineContext().isActive) {
            val startedAt = System.currentTimeMillis()
            recordProviderResult(
                provider = ProviderId.HELIUS,
                result = app.heliusRpc.getLatestBlockhash(),
                startedAtMillis = startedAt,
            )
            delay(PROVIDER_HEALTH_INTERVAL_MILLIS)
        }
    }

    private suspend fun persistCandidate(observed: ObservedPumpEvent): CandidateInsertResult {
        val event = observed.event
        return app.repository.insertCandidateBounded(
            TokenCandidateEntity(
                mint = event.mint,
                source =
                    if (event.kind == PumpPortalEventKind.NEW_TOKEN) {
                        "PUMP_PORTAL_NEW_TOKEN"
                    } else {
                        "PUMP_PORTAL_MIGRATION"
                    },
                discoverySignature = event.signature,
                creatorAddress = event.creator,
                name = event.name,
                symbol = event.symbol,
                metadataUri = event.metadataUri,
                tokenProgram = null,
                state = "DISCOVERED",
                score = null,
                rejectionCode = null,
                discoveredAtMillis = observed.observedAtMillis,
                lastUpdatedAtMillis = observed.observedAtMillis,
            ),
            maximumCandidates = retentionPolicy.maximumCandidates,
            protectedStates = ACTIVE_CANDIDATE_STATES,
        )
    }

    private suspend fun heartbeat(sessionId: String) {
        while (currentCoroutineContext().isActive) {
            delay(HEARTBEAT_INTERVAL_MILLIS)
            if (!app.repository.touchSessionHeartbeat(sessionId, System.currentTimeMillis())) {
                requestStop(StopDirective.InternalFailure)
                return
            }
            activeSessionSnapshot.get()?.let { refreshForegroundNotification(it) }
        }
    }

    private suspend fun requiredProviderKeysConfigured(): Boolean =
        REQUIRED_KEY_PROVIDERS.all { provider ->
            !app.sessionApiKeys.apiKeyFor(provider).isNullOrBlank() || app.restoreSessionApiKey(provider).restored
        }

    private suspend fun requiredProvidersHealthy(
        risk: RiskConfigEntity,
        nowMillis: Long,
    ): Boolean {
        val health =
            app.repository
                .observeProviderHealth()
                .first()
                .associateBy(ProviderHealthEntity::provider)
        return REQUIRED_KEY_PROVIDERS.all { provider ->
            val snapshot = health[provider.name] ?: return@all false
            val lastSuccess = snapshot.lastSuccessAtMillis ?: return@all false
            snapshot.state == "HEALTHY" &&
                nowMillis >= lastSuccess &&
                nowMillis - lastSuccess <= risk.minimumDataFreshnessMillis
        }
    }

    private suspend fun recordPumpPortalHealthy(nowMillis: Long) {
        app.repository.updateProviderHealth(ProviderId.PUMP_PORTAL.name) { previous ->
            ProviderHealthEntity(
                provider = ProviderId.PUMP_PORTAL.name,
                state = "HEALTHY",
                consecutiveFailures = 0,
                lastSuccessAtMillis = nowMillis,
                lastFailureAtMillis = previous?.lastFailureAtMillis,
                latencyMillis = previous?.latencyMillis,
                retryAfterMillis = null,
                lastFailureCode = null,
                updatedAtMillis = nowMillis,
            )
        }
    }

    private suspend fun recordProviderResult(
        provider: ProviderId,
        result: ProviderResult<*>,
        startedAtMillis: Long,
    ) {
        val now = System.currentTimeMillis()
        val health =
            app.repository.updateProviderHealth(provider.name) { previous ->
                when (result) {
                    is ProviderResult.Success -> {
                        ProviderHealthEntity(
                            provider = provider.name,
                            state = "HEALTHY",
                            consecutiveFailures = 0,
                            lastSuccessAtMillis = result.receivedAtMillis,
                            lastFailureAtMillis = previous?.lastFailureAtMillis,
                            latencyMillis = (now - startedAtMillis).coerceAtLeast(0),
                            retryAfterMillis = null,
                            lastFailureCode = null,
                            updatedAtMillis = now,
                        )
                    }

                    is ProviderResult.Failure -> {
                        val failures = (previous?.consecutiveFailures ?: 0) + 1
                        ProviderHealthEntity(
                            provider = provider.name,
                            state =
                                if (failures >= PROVIDER_UNAVAILABLE_FAILURES) {
                                    "UNAVAILABLE"
                                } else {
                                    "DEGRADED"
                                },
                            consecutiveFailures = failures,
                            lastSuccessAtMillis = previous?.lastSuccessAtMillis,
                            lastFailureAtMillis = now,
                            latencyMillis = (now - startedAtMillis).coerceAtLeast(0),
                            retryAfterMillis = result.error.retryAfterMillis,
                            lastFailureCode = result.error.redactedCode(),
                            updatedAtMillis = now,
                        )
                    }
                }
            }
        if (result is ProviderResult.Failure) {
            val failures = health.consecutiveFailures
            recordEvent(
                severity = if (failures >= PROVIDER_UNAVAILABLE_FAILURES) "ERROR" else "WARN",
                category = "PROVIDER",
                code =
                    if (failures >= PROVIDER_UNAVAILABLE_FAILURES) {
                        "PROVIDER_UNAVAILABLE"
                    } else {
                        result.error.redactedCode()
                    },
                relatedId = provider.name,
                nowMillis = now,
            )
            if (
                failures >= PROVIDER_UNAVAILABLE_FAILURES &&
                app.repository.openPositions(PAPER_POSITION_STATES).isNotEmpty()
            ) {
                recordEvent(
                    severity = "ERROR",
                    category = "RISK",
                    code = "OPEN_POSITION_MONITORING_LOST",
                    relatedId = provider.name,
                    nowMillis = now,
                )
            }
        }
        activeSessionSnapshot.get()?.let { refreshForegroundNotification(it) }
    }

    private suspend fun recordPumpPortalFailure(
        error: ProviderError,
        failureCount: Int,
        willRetry: Boolean,
        nowMillis: Long,
    ) {
        app.repository.updateProviderHealth(ProviderId.PUMP_PORTAL.name) { previous ->
            ProviderHealthEntity(
                provider = ProviderId.PUMP_PORTAL.name,
                state = if (willRetry) "DEGRADED" else "UNAVAILABLE",
                consecutiveFailures = failureCount,
                lastSuccessAtMillis = previous?.lastSuccessAtMillis,
                lastFailureAtMillis = nowMillis,
                latencyMillis = previous?.latencyMillis,
                retryAfterMillis = error.retryAfterMillis,
                lastFailureCode = error.redactedCode(),
                updatedAtMillis = nowMillis,
            )
        }
        recordEvent(
            severity = if (willRetry) "WARN" else "ERROR",
            category = "PROVIDER",
            code = if (willRetry) error.redactedCode() else "PROVIDER_UNAVAILABLE",
            relatedId = ProviderId.PUMP_PORTAL.name,
            nowMillis = nowMillis,
        )
        if (!willRetry && app.repository.openPositions(PAPER_POSITION_STATES).isNotEmpty()) {
            recordEvent(
                severity = "ERROR",
                category = "RISK",
                code = "OPEN_POSITION_MONITORING_LOST",
                relatedId = ProviderId.PUMP_PORTAL.name,
                nowMillis = nowMillis,
            )
        }
        activeSessionSnapshot.get()?.let { refreshForegroundNotification(it) }
    }

    private suspend fun finalizeSession(
        session: BotSessionEntity,
        directive: StopDirective,
    ) {
        val now = System.currentTimeMillis()
        app.repository.updateSessionStatus(
            id = session.id,
            status = directive.sessionStatus,
            stoppedAtMillis = now,
            stopReason = directive.reason,
            lastHeartbeatAtMillis = now,
        )
        recordEvent(
            severity =
                if (directive.sessionStatus in setOf(STATUS_STOPPED, STATUS_PAUSED)) {
                    "INFO"
                } else {
                    "ERROR"
                },
            category = "SESSION",
            code = directive.reason,
            relatedId = session.id,
            nowMillis = now,
        )
    }

    private suspend fun recordEvent(
        severity: String,
        category: String,
        code: String,
        relatedId: String?,
        nowMillis: Long,
    ) {
        app.repository.addEventBounded(
            AppEventEntity(
                severity = severity,
                category = category,
                code = code,
                redactedMessage = null,
                relatedId = relatedId,
                createdAtMillis = nowMillis,
            ),
            maximumEvents = retentionPolicy.maximumEvents,
        )
        AppNotificationDispatcher.notify(this, severity, category, code, relatedId, nowMillis)
        if (code in FOREGROUND_REFRESH_EVENTS) {
            activeSessionSnapshot.get()?.let { refreshForegroundNotification(it) }
        }
    }

    private fun finishService(startId: Int?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private fun updateNotification(paused: Boolean) {
        foregroundModel.updateAndGet { current ->
            current.copy(
                sessionStatus = if (paused) STATUS_PAUSED else current.sessionStatus,
                sessionPnlLamports = if (paused) null else current.sessionPnlLamports,
                nowMillis = System.currentTimeMillis(),
            )
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            foregroundServiceType(),
        )
    }

    private suspend fun refreshForegroundNotification(session: BotSessionEntity) {
        val now = System.currentTimeMillis()
        val positions =
            app.repository
                .openPositions(PAPER_POSITION_STATES)
                .filter { it.sessionId == session.id }
        val maximumQuoteAgeMillis =
            app.database
                .configDao()
                .riskByVersion(session.riskVersion)
                ?.minimumDataFreshnessMillis
        val pnl =
            maximumQuoteAgeMillis?.let {
                sessionPnlLamports(positions, now, it)
            }
        val lastMarketSuccess =
            app.repository
                .providerHealth()
                .mapNotNull(ProviderHealthEntity::lastSuccessAtMillis)
                .maxOrNull()
        val model =
            ForegroundNotificationModel(
                mode = session.mode,
                sessionStatus = session.status,
                protectingOnly = protectingOnly.get(),
                openPositionCount = positions.size,
                sessionPnlLamports = pnl,
                lastMarketSuccessAtMillis = lastMarketSuccess,
                nowMillis = now,
            )
        foregroundModel.set(model)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            foregroundServiceType(),
        )
    }

    private fun notification(): Notification {
        val model = foregroundModel.get()
        val openApp =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val paused = model.status == ForegroundStatus.PAUSED
        val actionSessionId = activeSessionSnapshot.get()?.id
        val pauseOrResume =
            actionSessionId?.let { sessionId ->
                PendingIntent.getService(
                    this,
                    if (paused) REQUEST_RESUME else REQUEST_PAUSE,
                    notificationServiceIntent(
                        context = this,
                        action = if (paused) ACTION_RESUME else ACTION_PAUSE,
                        sessionId = sessionId,
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        return NotificationCompat
            .Builder(this, StartExApplication.CHANNEL_BOT_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(model.status.titleResource))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.monitor_notification_body_detail,
                    model.openPositionCount,
                    model.openPositionCount,
                    foregroundPnlText(model),
                    foregroundMarketAgeText(model),
                ),
            ).setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (
                    pauseOrResume != null &&
                    model.status in setOf(ForegroundStatus.PAPER_ACTIVE, ForegroundStatus.LIVE_ACTIVE)
                ) {
                    addAction(0, getString(R.string.monitor_pause_action), pauseOrResume)
                } else if (pauseOrResume != null && model.status == ForegroundStatus.PAUSED) {
                    addAction(0, getString(R.string.monitor_resume_action), pauseOrResume)
                }
            }.addAction(0, getString(R.string.monitor_open_action), openApp)
            .build()
    }

    private fun foregroundPnlText(model: ForegroundNotificationModel): String {
        val lamports = model.sessionPnlLamports ?: return getString(R.string.value_unavailable)
        val value =
            formatUserNumber(
                value = BigDecimal.valueOf(lamports).movePointLeft(9).stripTrailingZeros(),
                maximumFractionDigits = 9,
                locale = resources.configuration.locales[0],
            )
        return getString(
            if (lamports > 0) R.string.positive_sol_value else R.string.sol_balance_value,
            value,
        )
    }

    private fun foregroundMarketAgeText(model: ForegroundNotificationModel): String {
        val seconds = model.marketAgeSeconds ?: return getString(R.string.value_unavailable)
        val (resource, value) =
            when {
                seconds < 60 -> R.plurals.market_age_seconds to seconds
                seconds < 3_600 -> R.plurals.market_age_minutes to seconds / 60
                else -> R.plurals.market_age_hours to seconds / 3_600
            }
        return resources.getQuantityString(resource, if (value == 1L) 1 else 2, value)
    }

    private data class ObservedPumpEvent(
        val event: PumpPortalEvent,
        val observedAtMillis: Long,
        val firstOnConnection: Boolean,
    )

    private data class SessionConfiguration(
        val strategy: StrategyConfigEntity,
        val risk: RiskConfigEntity,
    )

    private data class SessionPreparation(
        val policy: RetentionPolicy,
        val recoverable: BotSessionEntity?,
    )

    private data class MonitoringPreflight(
        val accessMode: WalletAccessMode,
        val providersConfigured: Boolean,
        val providersHealthy: Boolean,
        val riskLimitsValid: Boolean,
        val startAction: SessionStartAction,
    ) {
        fun toRecoveryContext(
            recoveryAuthenticated: Boolean,
            nowMillis: Long,
        ) = RecoveryContext(
            accessMode = accessMode,
            recoveryAuthenticated = recoveryAuthenticated,
            providersConfigured = providersConfigured,
            providersHealthy = providersHealthy,
            riskLimitsValid = riskLimitsValid,
            nowMillis = nowMillis,
        )
    }

    private data class RecoveryContext(
        val accessMode: WalletAccessMode,
        val recoveryAuthenticated: Boolean,
        val providersConfigured: Boolean,
        val providersHealthy: Boolean,
        val riskLimitsValid: Boolean,
        val nowMillis: Long,
    )

    private data class DiscoveryConnectionAttempt(
        val result: ProviderResult<PumpPortalConnection>,
        val disconnected: CompletableDeferred<DiscoveryDisconnect>,
        val listenerActive: AtomicBoolean,
        val firstEvent: AtomicBoolean,
        val lastEventAtMillis: AtomicLong,
    )

    private data class WalletMonitorProgress(
        val lastBalance: Long?,
        val failures: Int,
    )

    private sealed interface WalletRealtimeSignal {
        data class Event(
            val value: HeliusRealtimeEvent,
        ) : WalletRealtimeSignal

        data class Failure(
            val error: ProviderError,
        ) : WalletRealtimeSignal
    }

    private sealed interface DiscoveryDisconnect {
        data class ProviderFailure(
            val error: ProviderError,
        ) : DiscoveryDisconnect

        data object BufferFull : DiscoveryDisconnect
    }

    private class IncompatibleModeException : RuntimeException()

    private sealed class StopDirective(
        val reason: String,
        val sessionStatus: String,
        val startId: Int? = null,
    ) {
        data object UserRequested : StopDirective("USER_REQUESTED", STATUS_STOPPED)

        data object Paused : StopDirective("USER_PAUSED", STATUS_PAUSED)

        data object PositionsClosed : StopDirective("POSITIONS_CLOSED", STATUS_STOPPED)

        data object InvalidStart : StopDirective("INVALID_START", STATUS_NEEDS_ATTENTION)

        data object ModeChanged : StopDirective("MODE_CHANGED", STATUS_NEEDS_ATTENTION)

        data object RestartNotRequired : StopDirective("RESTART_NOT_REQUIRED", STATUS_STOPPED)

        data object InternalFailure : StopDirective("INTERNAL_FAILURE", STATUS_NEEDS_ATTENTION)

        data object ServiceDestroyed : StopDirective("SERVICE_DESTROYED", STATUS_NEEDS_ATTENTION)

        data class AndroidTimeout(
            val timeoutStartId: Int,
        ) : StopDirective("ANDROID_TIMEOUT", STATUS_STOPPED, timeoutStartId)

        data class PreflightFailed(
            val code: String,
        ) : StopDirective(code, STATUS_NEEDS_ATTENTION)

        data class ProviderUnavailable(
            val code: String,
        ) : StopDirective(code, STATUS_NEEDS_ATTENTION)

        data object StorageCapacity :
            StopDirective("CANDIDATE_STORAGE_CAP_REACHED", STATUS_NEEDS_ATTENTION)
    }

    companion object {
        const val ACTION_START = "com.finnvek.startex.action.START_MONITORING"
        const val ACTION_RECOVER_AUTHENTICATED =
            "com.finnvek.startex.action.RECOVER_MONITORING_AUTHENTICATED"
        const val ACTION_STOP_AUTHENTICATED =
            "com.finnvek.startex.action.STOP_MONITORING_AUTHENTICATED"
        const val ACTION_PAUSE = "com.finnvek.startex.action.PAUSE_MONITORING"
        const val ACTION_RESUME = "com.finnvek.startex.action.RESUME_MONITORING"
        const val ACTION_SELL_NOW = "com.finnvek.startex.action.SELL_PAPER_POSITION_NOW"
        const val ACTION_EMERGENCY_EXIT = "com.finnvek.startex.action.EMERGENCY_EXIT_PAPER_AND_STOP"
        const val ACTION_STOP_AFTER_CLOSE = "com.finnvek.startex.action.STOP_AFTER_POSITIONS_CLOSE"
        const val ACTION_STOP = "com.finnvek.startex.action.STOP_MONITORING"
        const val EXTRA_POSITION_ID = "com.finnvek.startex.extra.POSITION_ID"
        const val EXTRA_SESSION_ID = "com.finnvek.startex.extra.SESSION_ID"

        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 100
        private const val REQUEST_PAUSE = 102
        private const val REQUEST_RESUME = 103
        private const val EVENT_BUFFER_CAPACITY = 64
        private const val PAPER_CANDIDATE_BUFFER_CAPACITY = 16
        private const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        private const val PROVIDER_HEALTH_INTERVAL_MILLIS = 10_000L
        private const val PROVIDER_HEALTH_WRITE_INTERVAL_MILLIS = 5_000L
        private const val POSITION_MONITOR_INTERVAL_MILLIS = 2_000L
        private const val WALLET_REALTIME_INITIAL_RETRY_MILLIS = 1_000L
        private const val WALLET_REALTIME_MAXIMUM_RETRY_MILLIS = 60_000L
        private const val WALLET_REALTIME_BUFFER_CAPACITY = 16
        private const val MAXIMUM_POSITION_ID_LENGTH = 128
        private const val SOL_DECIMALS = 9
        private const val PROVIDER_UNAVAILABLE_FAILURES = 5
        private const val STALE_CHECK_INTERVAL_MILLIS = 30_000L
        private const val STATUS_RUNNING = "RUNNING"
        private const val STATUS_PROTECTING = "PROTECTING"
        private const val STATUS_PAUSED = "PAUSED"
        private const val STATUS_STOPPED = "STOPPED"
        private const val STATUS_NEEDS_ATTENTION = "NEEDS_ATTENTION"
        private val RECOVERABLE_SESSION_STATES = MonitoringSessionPolicy.recoverableSessionStatuses
        private val AUTOMATIC_RESTART_SESSION_STATES = setOf("RUNNING", "PROTECTING")
        private val PAPER_POSITION_STATES = listOf("OPEN", "EXIT_REQUESTED", "EXIT_BLOCKED")
        private val FAILED_TRADE_STATUSES = listOf("FAILED", "PAPER_FAILED", "SUBMISSION_UNCERTAIN")
        private val PUMP_CANDIDATE_SOURCES = setOf("PUMP_PORTAL_NEW_TOKEN", "PUMP_PORTAL_MIGRATION")
        private const val LEGACY_TOKEN_PROGRAM_ID =
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        private val ACTIVE_CANDIDATE_STATES =
            listOf(
                "DISCOVERED",
                "OBSERVING",
                "ELIGIBLE",
                "ENTRY_QUEUED",
                "ENTRY_SUBMITTED",
                "POSITION_OPEN",
                "EXIT_QUEUED",
                "EXIT_SUBMITTED",
                "ROUTE_UNAVAILABLE",
                "TRANSACTION_UNCERTAIN",
            )
        private val REQUIRED_KEY_PROVIDERS =
            listOf(
                ProviderId.HELIUS,
                ProviderId.PUMP_PORTAL,
                ProviderId.JUPITER,
            )
        private val FOREGROUND_REFRESH_EVENTS =
            setOf(
                "MONITORING_STARTED",
                "USER_PAUSED",
                "STOP_AFTER_CLOSE_REQUESTED",
                "PAPER_POSITION_OPENED",
                "PAPER_POSITION_CLOSED",
                "PAPER_EXIT_BLOCKED",
                "PAPER_EMERGENCY_EXIT_REQUESTED",
                "PROVIDER_UNAVAILABLE",
                "RECOVERY_AUTHENTICATION_REQUIRED",
            )
    }
}

internal fun requiresFreshStartPrerequisites(recoverable: BotSessionEntity?): Boolean =
    recoverable == null || recoverable.stopReason == "PREFLIGHT_FAILED"

internal fun notificationServiceIntent(
    context: Context,
    action: String,
    sessionId: String,
): Intent {
    val data = Uri.Builder()
    data.scheme(NOTIFICATION_ACTION_SCHEME)
    data.authority(NOTIFICATION_ACTION_AUTHORITY)
    data.appendPath(NOTIFICATION_ACTION_SESSION_PATH)
    data.appendPath(sessionId)
    return Intent(context, TradingMonitorService::class.java)
        .setAction(action)
        .setData(data.build())
}

internal fun acceptsNotificationAction(
    intent: Intent,
    currentSessionId: String?,
    expectedState: Boolean,
): Boolean {
    val data = intent.data ?: return true
    val sessionId =
        data.pathSegments
            .takeIf {
                data.scheme == NOTIFICATION_ACTION_SCHEME &&
                    data.authority == NOTIFICATION_ACTION_AUTHORITY &&
                    it.size == 2 &&
                    it.first() == NOTIFICATION_ACTION_SESSION_PATH
            }?.last()
            ?: return false
    return expectedState && sessionId == currentSessionId
}

private const val NOTIFICATION_ACTION_SCHEME = "startex"
private const val NOTIFICATION_ACTION_AUTHORITY = "notification"
private const val NOTIFICATION_ACTION_SESSION_PATH = "session"

private val ForegroundStatus.titleResource: Int
    get() =
        when (this) {
            ForegroundStatus.PAPER_ACTIVE -> R.string.monitor_status_paper_active
            ForegroundStatus.LIVE_ACTIVE -> R.string.monitor_status_live_active
            ForegroundStatus.PAUSED -> R.string.monitor_status_paused
            ForegroundStatus.PROTECTING_POSITION -> R.string.monitor_status_protecting
            ForegroundStatus.NEEDS_ATTENTION -> R.string.monitor_status_attention
        }

private fun RiskConfigEntity.isValidForMonitoring(): Boolean =
    maximumTradeLamports > 0 &&
        maximumExposureLamports >= maximumTradeLamports &&
        maximumOpenPositions > 0 &&
        maximumTradesPerDay > 0 &&
        maximumDailyLossLamports > 0 &&
        maximumDailyFeesLamports >= 0 &&
        maximumConsecutiveLosses > 0 &&
        minimumWalletReserveLamports > 0 &&
        maximumSlippageBps in 1..10_000 &&
        maximumTransactionCostLamports > 0 &&
        maximumFeePercentBps in 1..10_000 &&
        maximumHoldingMillis > 0 &&
        minimumDataFreshnessMillis > 0

private fun ProviderError.redactedCode(): String =
    when (this) {
        is ProviderError.ConnectionClosed -> "CONNECTION_CLOSED"
        is ProviderError.ExecutionRejected -> "EXECUTION_REJECTED"
        is ProviderError.HttpFailure -> "HTTP_FAILURE"
        is ProviderError.InvalidRequest -> "INVALID_REQUEST"
        is ProviderError.InvalidResponse -> "INVALID_RESPONSE"
        is ProviderError.MissingApiKey -> "MISSING_API_KEY"
        is ProviderError.NetworkUnavailable -> "NETWORK_UNAVAILABLE"
        is ProviderError.RateLimited -> "RATE_LIMITED"
        is ProviderError.RemoteFailure -> "REMOTE_FAILURE"
        is ProviderError.StaleData -> "STALE_DATA"
        is ProviderError.SubmissionUncertain -> "SUBMISSION_UNCERTAIN"
        is ProviderError.Unauthorized -> "UNAUTHORIZED"
    }

internal fun walletAccountEventCode(
    previousLamports: Long?,
    currentLamports: Long,
): String {
    require(previousLamports == null || previousLamports >= 0)
    require(currentLamports >= 0)
    return if (previousLamports != null && currentLamports > previousLamports) {
        "INCOMING_SOL_DETECTED"
    } else {
        "ACCOUNT_CHANGED"
    }
}
