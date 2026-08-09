package com.finnvek.startex.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.finnvek.startex.data.settings.OperatingMode
import com.finnvek.startex.device.DeviceHealthEntryPolicy
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
import com.finnvek.startex.trading.PaperExitSafetyFacts
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
        when (intent?.action) {
            null -> startSession(recoveryAuthenticated = false, restartOnly = true)
            ACTION_START -> startSession(recoveryAuthenticated = false)
            ACTION_RECOVER_AUTHENTICATED -> recoverAuthenticated()
            ACTION_PAUSE -> requestStop(StopDirective.Paused)
            ACTION_RESUME -> startSession(recoveryAuthenticated = pausedSessionId != null)
            ACTION_SELL_NOW -> requestPaperSell(intent.getStringExtra(EXTRA_POSITION_ID))
            ACTION_EMERGENCY_EXIT -> requestEmergencyPaperExit()
            ACTION_STOP_AFTER_CLOSE -> requestStopAfterClose()
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
    ) {
        if (sessionJob?.isActive == true) return
        pausedSessionId = null
        protectingOnly.set(false)
        stopDirective.set(null)
        updateNotification(paused = false)
        sessionJob =
            scope.launch {
                runSession(recoveryAuthenticated, restartOnly)
            }
    }

    private fun recoverAuthenticated() {
        if (sessionJob?.isActive != true) {
            startSession(recoveryAuthenticated = true)
            return
        }
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
        if (sessionJob?.isActive != true) {
            finishService(null)
            return
        }
        protectingOnly.set(true)
        scope.launch {
            val active = app.repository.observeActiveSession(RECOVERABLE_SESSION_STATES).first() ?: return@launch
            val now = System.currentTimeMillis()
            app.repository.updateSessionStatus(
                id = active.id,
                status = STATUS_PROTECTING,
                stoppedAtMillis = null,
                stopReason = "STOP_AFTER_CLOSE_REQUESTED",
                lastHeartbeatAtMillis = now,
            )
            activeSessionSnapshot.set(
                active.copy(
                    status = STATUS_PROTECTING,
                    stopReason = "STOP_AFTER_CLOSE_REQUESTED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            recordEvent("INFO", "SESSION", "STOP_AFTER_CLOSE_REQUESTED", active.id, now)
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
            val updates =
                paperEmergencyExitUpdates(
                    positions = app.repository.openPositions(PAPER_POSITION_STATES),
                    sessionId = active.id,
                    nowMillis = now,
                )
            if (updates.isEmpty()) {
                requestStop(StopDirective.PositionsClosed)
                return@launch
            }
            protectingOnly.set(true)
            app.repository.updateSessionStatus(
                id = active.id,
                status = STATUS_PROTECTING,
                stoppedAtMillis = null,
                stopReason = "EMERGENCY_EXIT_REQUESTED",
                lastHeartbeatAtMillis = now,
            )
            activeSessionSnapshot.set(
                active.copy(
                    status = STATUS_PROTECTING,
                    stopReason = "EMERGENCY_EXIT_REQUESTED",
                    lastHeartbeatAtMillis = now,
                ),
            )
            updates.forEach { app.repository.savePosition(it) }
            recordEvent("WARN", "RISK", "PAPER_EMERGENCY_EXIT_REQUESTED", active.id, now)
        }
    }

    private fun requestStop(directive: StopDirective) {
        stopDirective.compareAndSet(null, directive)
        val activeJob = sessionJob
        if (activeJob?.isActive == true) {
            activeJob.cancel()
        } else if (directive is StopDirective.UserRequested && pausedSessionId != null) {
            stopPausedSession(directive)
        } else if (directive is StopDirective.UserRequested) {
            stopPersistedSession(directive)
        } else {
            finishService(directive.startId)
        }
    }

    private suspend fun runSession(
        recoveryAuthenticated: Boolean,
        restartOnly: Boolean,
    ) {
        var activeSession: BotSessionEntity? = null
        try {
            activeSession = prepareSession(recoveryAuthenticated, restartOnly) ?: return
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

    private fun stopPersistedSession(directive: StopDirective.UserRequested) {
        scope.launch {
            val now = System.currentTimeMillis()
            val stopped =
                app.repository.stopLatestSession(
                    activeStates = RECOVERABLE_SESSION_STATES,
                    stoppedAtMillis = now,
                    stopReason = directive.reason,
                )
            stopped?.let { session ->
                recordEvent("INFO", "SESSION", directive.reason, session.id, now)
            }
            finishService(directive.startId)
        }
    }

    private suspend fun prepareSession(
        recoveryAuthenticated: Boolean,
        restartOnly: Boolean,
    ): BotSessionEntity? {
        val now = System.currentTimeMillis()
        val latestStrategy = app.database.configDao().latestStrategy()
        val latestRisk = app.database.configDao().latestRisk()
        if (latestStrategy == null || latestRisk == null) {
            recordEvent("ERROR", "SESSION", "CONFIGURATION_MISSING", null, now)
            stopDirective.compareAndSet(null, StopDirective.PreflightFailed("CONFIGURATION_MISSING"))
            return null
        }

        val settings = app.settings.settings.first()
        val policy =
            RetentionPolicy(
                snapshotDays = settings.snapshotRetentionDays,
                maximumEvents = settings.maximumStoredEvents,
            )
        retentionPolicy = policy
        val heartbeatPolicy = SessionHeartbeatFreshnessPolicy()
        app.repository.expireStaleSessions(heartbeatPolicy.staleCutoffMillis(now), now)
        val recoverable = app.repository.observeActiveSession(RECOVERABLE_SESSION_STATES).first()
        if (restartOnly && recoverable?.status !in AUTOMATIC_RESTART_SESSION_STATES) {
            stopDirective.compareAndSet(null, StopDirective.RestartNotRequired)
            return null
        }
        val strategy =
            if (recoverable == null) {
                latestStrategy
            } else {
                app.database.configDao().strategyByVersion(recoverable.strategyVersion)
            }
        val risk =
            if (recoverable == null) {
                latestRisk
            } else {
                app.database.configDao().riskByVersion(recoverable.riskVersion)
            }
        if (strategy == null || risk == null) {
            return stopBeforeMonitoring(
                existingSession = recoverable,
                strategy = latestStrategy,
                risk = latestRisk,
                mode = settings.operatingMode.name,
                reason = "SESSION_CONFIGURATION_MISSING",
                nowMillis = now,
            )
        }
        val cleanup = app.repository.cleanup(policy, now, ACTIVE_CANDIDATE_STATES)
        if (cleanup.remainingCandidates >= policy.maximumCandidates) {
            return stopBeforeMonitoring(
                existingSession = recoverable,
                strategy = strategy,
                risk = risk,
                mode = settings.operatingMode.name,
                reason = "CANDIDATE_STORAGE_CAP_REACHED",
                nowMillis = now,
            )
        }
        val accessMode = settings.walletAccessMode()
        val configured = requiredProviderKeysConfigured()
        val healthy = requiredProvidersHealthy(risk, now)
        val validRisk = risk.isValidForMonitoring()

        val startAction =
            MonitoringSessionPolicy.startAction(
                paperMode = settings.operatingMode == OperatingMode.PAPER && recoverable?.mode != "LIVE",
                providersConfigured = configured,
                riskLimitsValid = validRisk,
            )
        if (startAction != SessionStartAction.START_MONITORING) {
            val reason =
                if (startAction == SessionStartAction.LIVE_EXECUTION_LOCKED) {
                    "LIVE_EXECUTION_LOCKED"
                } else {
                    "PREFLIGHT_FAILED"
                }
            return stopBeforeMonitoring(
                existingSession = recoverable,
                strategy = strategy,
                risk = risk,
                mode = settings.operatingMode.name,
                reason = reason,
                nowMillis = now,
            )
        }

        if (recoverable != null) {
            return recoverSession(
                session = recoverable,
                strategy = strategy,
                risk = risk,
                accessMode = accessMode,
                recoveryAuthenticated = recoveryAuthenticated,
                providersConfigured = configured,
                providersHealthy = healthy,
                riskLimitsValid = validRisk,
                nowMillis = now,
            )
        }

        val session =
            BotSessionEntity(
                id = UUID.randomUUID().toString(),
                mode = settings.operatingMode.name,
                status = STATUS_RUNNING,
                strategyVersion = strategy.version,
                riskVersion = risk.version,
                startedAtMillis = now,
                stoppedAtMillis = null,
                stopReason = null,
                lastHeartbeatAtMillis = now,
            )
        app.repository.saveSession(session)
        recordEvent("INFO", "SESSION", "MONITORING_STARTED", session.id, now)
        if (!healthy) recordEvent("WARN", "PROVIDER", "HEALTH_CHECK_PENDING", session.id, now)
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
        strategy: StrategyConfigEntity,
        risk: RiskConfigEntity,
        accessMode: WalletAccessMode,
        recoveryAuthenticated: Boolean,
        providersConfigured: Boolean,
        providersHealthy: Boolean,
        riskLimitsValid: Boolean,
        nowMillis: Long,
    ): BotSessionEntity? {
        val originalConfigurationAvailable =
            session.strategyVersion == strategy.version && session.riskVersion == risk.version
        val action =
            MonitoringSessionPolicy.recoveryAction(
                accessMode = accessMode,
                recoveryAuthenticated = recoveryAuthenticated,
                providersConfigured = providersConfigured,
                providersHealthy = providersHealthy,
                riskLimitsValid = riskLimitsValid && originalConfigurationAvailable,
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
                stoppedAtMillis = nowMillis,
                stopReason = reason,
                lastHeartbeatAtMillis = nowMillis,
            )
            recordEvent("ERROR", "RECOVERY", reason, session.id, nowMillis)
            stopDirective.compareAndSet(null, StopDirective.PreflightFailed(reason))
            return null
        }

        if (recoveryAuthenticated) {
            paperFinalStateGate.run {
                app.repository.resetPaperCircuitBreaker(Instant.ofEpochMilli(nowMillis), authenticated = true)
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
            lastHeartbeatAtMillis = nowMillis,
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
            nowMillis,
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
            lastHeartbeatAtMillis = nowMillis,
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
            launch { consumeEvents(session, events, candidates, dispatch) }
            launch { consumePaperCandidates(session, strategy, risk, candidates, dispatch, coordinator) }
            launch { monitorPaperPositions(risk, positionMonitor) }
            launch { heartbeat(session.id) }
            launch { probeHeliusContinuously() }
            launch { monitorWalletAccount(session) }
            launch { discover(session, events) }
        }

    private suspend fun discover(
        session: BotSessionEntity,
        events: Channel<ObservedPumpEvent>,
    ) {
        val provider: PumpPortalDiscoveryProvider = app.pumpPortal
        val reconnectPolicy = DiscoveryReconnectPolicy()
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            val disconnected = CompletableDeferred<DiscoveryDisconnect>()
            val listenerActive = AtomicBoolean(true)
            val firstEvent = AtomicBoolean(true)
            val lastEventAtMillis = AtomicLong(System.currentTimeMillis())
            val connectionResult =
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

            val connection =
                when (connectionResult) {
                    is ProviderResult.Success -> {
                        connectionResult.value
                    }

                    is ProviderResult.Failure -> {
                        val shouldContinue =
                            handleProviderFailure(
                                error = connectionResult.error,
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
                    disconnected = disconnected,
                    listenerActive = listenerActive,
                    lastEventAtMillis = lastEventAtMillis,
                )
            if (disconnect == DiscoveryDisconnect.BufferFull) {
                recordEvent(
                    severity = "ERROR",
                    category = "PROVIDER",
                    code = "DISCOVERY_BUFFER_FULL",
                    relatedId = session.id,
                    nowMillis = System.currentTimeMillis(),
                )
                requestStop(StopDirective.ProviderUnavailable("DISCOVERY_BUFFER_FULL"))
                return
            }

            if (!firstEvent.get()) consecutiveFailures = 0
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
                return
            }
            if (
                observed.firstOnConnection ||
                observed.observedAtMillis - lastPumpHealthAtMillis >= PROVIDER_HEALTH_WRITE_INTERVAL_MILLIS
            ) {
                recordPumpPortalHealthy(observed.observedAtMillis)
                lastPumpHealthAtMillis = observed.observedAtMillis
            }
            recordEvent(
                severity = "INFO",
                category = "DISCOVERY",
                code =
                    if (observed.event.kind == PumpPortalEventKind.NEW_TOKEN) {
                        "CANDIDATE_DISCOVERED"
                    } else {
                        "MIGRATION_DISCOVERED"
                    },
                relatedId = observed.event.mint,
                nowMillis = observed.observedAtMillis,
            )
            app.repository.touchSessionHeartbeat(session.id, observed.observedAtMillis)
            if (candidateInsert == CandidateInsertResult.INSERTED && !protectingOnly.get()) {
                when (dispatch.tryAdmit(observed.event.mint)) {
                    PaperCandidateDispatch.ADMIT -> {
                        if (candidates.trySend(observed.event.mint).isFailure) {
                            dispatch.complete(observed.event.mint)
                            rejectObservationQueueCandidate(observed.event.mint, observed.observedAtMillis)
                            recordEvent(
                                severity = "WARN",
                                category = "CANDIDATE",
                                code = "OBSERVATION_QUEUE_FULL",
                                relatedId = observed.event.mint,
                                nowMillis = observed.observedAtMillis,
                            )
                        }
                    }

                    PaperCandidateDispatch.DUPLICATE -> {
                        Unit
                    }

                    PaperCandidateDispatch.FULL -> {
                        rejectObservationQueueCandidate(observed.event.mint, observed.observedAtMillis)
                        recordEvent(
                            severity = "WARN",
                            category = "CANDIDATE",
                            code = "OBSERVATION_QUEUE_FULL",
                            relatedId = observed.event.mint,
                            nowMillis = observed.observedAtMillis,
                        )
                    }
                }
            }
        }
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
            object : JupiterTokensProvider {
                override suspend fun tokenSnapshot(mint: String): ProviderResult<JupiterTokenSnapshot> {
                    val startedAt = System.currentTimeMillis()
                    val result = app.jupiterTokens.tokenSnapshot(mint)
                    recordProviderResult(
                        provider = ProviderId.JUPITER,
                        result = result,
                        startedAtMillis = startedAt,
                    )
                    return result
                }
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
        evidenceSource =
            PaperCandidateSafetyEvidenceSource evidence@{ mint ->
                val candidate = app.database.candidateDao().byMint(mint) ?: return@evidence null
                if (
                    candidate.source !in PUMP_CANDIDATE_SOURCES ||
                    candidate.discoverySignature.isNullOrBlank()
                ) {
                    return@evidence null
                }
                val tokenStartedAt = System.currentTimeMillis()
                val tokenResult = app.jupiterTokens.tokenSnapshot(mint)
                recordProviderResult(ProviderId.JUPITER, tokenResult, tokenStartedAt)
                val tokenSuccess =
                    when (tokenResult) {
                        is ProviderResult.Success -> tokenResult
                        is ProviderResult.Failure -> return@evidence null
                    }

                val accountStartedAt = System.currentTimeMillis()
                val accountResult = app.heliusRpc.getAccountInfo(mint)
                recordProviderResult(ProviderId.HELIUS, accountResult, accountStartedAt)
                val accountSuccess =
                    when (accountResult) {
                        is ProviderResult.Success -> accountResult
                        is ProviderResult.Failure -> return@evidence null
                    }
                val account = accountSuccess.value ?: return@evidence null
                val token = tokenSuccess.value
                if (account.executable || account.owner != token.tokenProgram) return@evidence null

                PaperCandidateSafetyEvidence(
                    pumpMint = mint,
                    suspiciousWalletActivity =
                        token.audit.isSuspicious ||
                            !token.audit.mintAuthorityDisabled ||
                            !token.audit.freezeAuthorityDisabled,
                    quoteSemanticsValidated = false,
                    unsupportedRouteBehavior = true,
                    observedAt =
                        Instant.ofEpochMilli(
                            minOf(
                                candidate.discoveredAtMillis,
                                token.updatedAtMillis,
                                accountSuccess.receivedAtMillis,
                            ),
                        ),
                    // Parsed Token-2022 extensions are not exposed by the current RPC adapter.
                    // Leaving this absent deliberately makes Token-2022 candidates fail closed.
                    token2022ExtensionProof = null,
                )
            },
        solUsdRates =
            PaperSolUsdRateSource rate@{
                val startedAt = System.currentTimeMillis()
                val result = app.jupiterTokens.tokenSnapshot(WRAPPED_SOL_MINT)
                recordProviderResult(ProviderId.JUPITER, result, startedAt)
                val token =
                    when (result) {
                        is ProviderResult.Success -> result.value
                        is ProviderResult.Failure -> return@rate null
                    }
                if (
                    token.mint != WRAPPED_SOL_MINT ||
                    token.decimals != SOL_DECIMALS ||
                    token.tokenProgram != LEGACY_TOKEN_PROGRAM_ID
                ) {
                    return@rate null
                }
                val rate = token.usdPrice?.takeIf { it.signum() > 0 } ?: return@rate null
                // One WSOL represents one SOL, so its unit USD price is the exact USD/SOL conversion.
                PaperSolUsdRate(rate, Instant.ofEpochMilli(token.updatedAtMillis))
            },
        maximumSourceAge =
            Duration.ofMillis(
                minOf(strategy.maximumCandidateAgeMillis, risk.maximumCandidateAgeMillis),
            ),
    )

    private fun paperRiskFactsSource() =
        DefaultPaperRiskFactsSource(
            runtimeSnapshots =
                PaperRiskRuntimeSnapshotSource snapshot@{ sessionId, now ->
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
                    PaperRiskRuntimeSnapshot(
                        observedAt = now,
                        walletBalanceLamports = balance.lamports,
                        openPositions = app.repository.openPositions(PAPER_POSITION_STATES),
                        rollingTradeTimes =
                            app.repository
                                .tradeTimesSince(now.minus(Duration.ofHours(24)).toEpochMilli())
                                .map(Instant::ofEpochMilli),
                        dailyPerformance = performance,
                        providerHealth = app.repository.providerHealth(),
                        lastLossAt = performance.lastLossAtMillis?.let(Instant::ofEpochMilli),
                        lastFailedTransactionAt = failedAt?.let(Instant::ofEpochMilli),
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
                            PaperRiskRuntimeSnapshot(
                                observedAt = now,
                                walletBalanceLamports = baseline.snapshot.walletBalance.value,
                                openPositions = app.repository.openPositions(PAPER_POSITION_STATES),
                                rollingTradeTimes =
                                    app.repository
                                        .tradeTimesSince(now.minus(Duration.ofHours(24)).toEpochMilli())
                                        .map(Instant::ofEpochMilli),
                                dailyPerformance = performance,
                                providerHealth = app.repository.providerHealth(),
                                lastLossAt = performance.lastLossAtMillis?.let(Instant::ofEpochMilli),
                                lastFailedTransactionAt = failedAt?.let(Instant::ofEpochMilli),
                                circuitBreaker = circuitBreaker,
                            )
                        }
                    },
                solEurRates = PaperSolEurRateSource { null },
            ).facts(sessionId, risk, now) ?: return null
        return current.copy(approximateTradeEur = baseline.approximateTradeEur)
    }

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
                    val age = now.toEpochMilli() - token.updatedAtMillis
                    if (age !in 0..risk.minimumDataFreshnessMillis) return@safety null

                    val accountStartedAt = System.currentTimeMillis()
                    val accountResult = app.heliusRpc.getAccountInfo(position.mint)
                    recordProviderResult(ProviderId.HELIUS, accountResult, accountStartedAt)
                    val account =
                        when (accountResult) {
                            is ProviderResult.Success -> accountResult.value
                            is ProviderResult.Failure -> return@safety null
                        } ?: return@safety null
                    if (
                        account.executable ||
                        account.owner != token.tokenProgram ||
                        token.tokenProgram != LEGACY_TOKEN_PROGRAM_ID
                    ) {
                        return@safety null
                    }

                    val momentumCollapsed = token.stats5m.sellCount > token.stats5m.buyCount
                    val suspiciousCreator =
                        token.audit.developerBalancePercentage
                            ?.let { it > MAXIMUM_SAFE_DEVELOPER_PERCENT }
                            ?: true
                    val largeHolderSell = token.audit.topHoldersPercentage > MAXIMUM_SAFE_TOP_HOLDER_PERCENT
                    val tokenUnsafe =
                        token.audit.isSuspicious ||
                            !token.audit.mintAuthorityDisabled ||
                            !token.audit.freezeAuthorityDisabled
                    val concentrationPenalty =
                        token.audit.topHoldersPercentage
                            .setScale(0, java.math.RoundingMode.CEILING)
                            .intValueExact()
                            .coerceIn(0, 40)
                    val creatorPenalty =
                        token.audit.developerBalancePercentage
                            ?.setScale(0, java.math.RoundingMode.CEILING)
                            ?.intValueExact()
                            ?.coerceIn(0, 30)
                            ?: 30
                    PaperExitSafetyFacts(
                        score =
                            if (tokenUnsafe) {
                                0
                            } else {
                                (100 - concentrationPenalty - creatorPenalty - if (momentumCollapsed) 20 else 0)
                                    .coerceIn(0, 100)
                            },
                        tokenUnsafe = tokenUnsafe,
                        momentumCollapsed = momentumCollapsed,
                        liquidityCollapsed = false,
                        suspiciousCreatorActivity = suspiciousCreator,
                        largeHolderSell = largeHolderSell,
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

    @Suppress("LongMethod")
    private suspend fun monitorWalletAccount(session: BotSessionEntity) {
        val wallet = app.repository.walletProfile() ?: return
        val balanceStartedAt = System.currentTimeMillis()
        val balanceResult = app.heliusRpc.getBalance(wallet.publicAddress)
        recordProviderResult(ProviderId.HELIUS, balanceResult, balanceStartedAt)
        var lastBalance =
            when (val balance = balanceResult) {
                is ProviderResult.Success -> balance.value.lamports
                is ProviderResult.Failure -> null
            }
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
            val connectionResult =
                app.heliusWebSocket.connect(
                    subscriptions = setOf(HeliusSubscription.Account(wallet.publicAddress)),
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
            try {
                while (currentCoroutineContext().isActive) {
                    when (val signal = signals.receive()) {
                        is WalletRealtimeSignal.Failure -> {
                            val failedAt = System.currentTimeMillis()
                            recordProviderResult(
                                ProviderId.HELIUS,
                                ProviderResult.Failure(signal.error),
                                failedAt,
                            )
                            failures += 1
                            break
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
            } finally {
                listenerActive.set(false)
                connection.close()
            }
            delay(retryPolicy.delayMillis(failures.coerceAtLeast(1)))
        }
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
            !app.sessionApiKeys.apiKeyFor(provider).isNullOrBlank()
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
        val previous = app.database.providerHealthDao().byProvider(ProviderId.PUMP_PORTAL.name)
        app.repository.saveProviderHealth(
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
            ),
        )
    }

    private suspend fun recordProviderResult(
        provider: ProviderId,
        result: ProviderResult<*>,
        startedAtMillis: Long,
    ) {
        val now = System.currentTimeMillis()
        val previous = app.database.providerHealthDao().byProvider(provider.name)
        when (result) {
            is ProviderResult.Success -> {
                app.repository.saveProviderHealth(
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
                    ),
                )
            }

            is ProviderResult.Failure -> {
                val failures = (previous?.consecutiveFailures ?: 0) + 1
                app.repository.saveProviderHealth(
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
                    ),
                )
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
        }
        activeSessionSnapshot.get()?.let { refreshForegroundNotification(it) }
    }

    private suspend fun recordPumpPortalFailure(
        error: ProviderError,
        failureCount: Int,
        willRetry: Boolean,
        nowMillis: Long,
    ) {
        val previous = app.database.providerHealthDao().byProvider(ProviderId.PUMP_PORTAL.name)
        app.repository.saveProviderHealth(
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
            ),
        )
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
        val positions =
            app.repository
                .openPositions(PAPER_POSITION_STATES)
                .filter { it.sessionId == session.id }
        val pnl =
            runCatching {
                positions
                    .mapNotNull { position ->
                        position.latestSellQuoteLamports?.let { quote ->
                            Math.subtractExact(quote, position.netInputLamports)
                        }
                    }.takeIf { it.isNotEmpty() }
                    ?.fold(0L) { total, value -> Math.addExact(total, value) }
            }.getOrNull()
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
                nowMillis = System.currentTimeMillis(),
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
        val pauseOrResume =
            PendingIntent.getService(
                this,
                if (paused) REQUEST_RESUME else REQUEST_PAUSE,
                Intent(this, TradingMonitorService::class.java).setAction(
                    if (paused) ACTION_RESUME else ACTION_PAUSE,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, StartExApplication.CHANNEL_BOT_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(model.status.titleResource))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.monitor_notification_body_detail,
                    model.openPositionCount,
                    model.openPositionCount,
                    model.pnlText,
                    model.marketAgeText,
                ),
            ).setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (model.status in setOf(ForegroundStatus.PAPER_ACTIVE, ForegroundStatus.LIVE_ACTIVE)) {
                    addAction(0, getString(R.string.monitor_pause_action), pauseOrResume)
                } else if (model.status == ForegroundStatus.PAUSED) {
                    addAction(0, getString(R.string.monitor_resume_action), pauseOrResume)
                }
            }.addAction(0, getString(R.string.monitor_open_action), openApp)
            .build()
    }

    private data class ObservedPumpEvent(
        val event: PumpPortalEvent,
        val observedAtMillis: Long,
        val firstOnConnection: Boolean,
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

    private sealed class StopDirective(
        val reason: String,
        val sessionStatus: String,
        val startId: Int? = null,
    ) {
        data object UserRequested : StopDirective("USER_REQUESTED", STATUS_STOPPED)

        data object Paused : StopDirective("USER_PAUSED", STATUS_PAUSED)

        data object PositionsClosed : StopDirective("POSITIONS_CLOSED", STATUS_STOPPED)

        data object InvalidStart : StopDirective("INVALID_START", STATUS_NEEDS_ATTENTION)

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
        const val ACTION_PAUSE = "com.finnvek.startex.action.PAUSE_MONITORING"
        const val ACTION_RESUME = "com.finnvek.startex.action.RESUME_MONITORING"
        const val ACTION_SELL_NOW = "com.finnvek.startex.action.SELL_PAPER_POSITION_NOW"
        const val ACTION_EMERGENCY_EXIT = "com.finnvek.startex.action.EMERGENCY_EXIT_PAPER_AND_STOP"
        const val ACTION_STOP_AFTER_CLOSE = "com.finnvek.startex.action.STOP_AFTER_POSITIONS_CLOSE"
        const val ACTION_STOP = "com.finnvek.startex.action.STOP_MONITORING"
        const val EXTRA_POSITION_ID = "com.finnvek.startex.extra.POSITION_ID"

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
        private val MAXIMUM_SAFE_DEVELOPER_PERCENT = BigDecimal("10")
        private val MAXIMUM_SAFE_TOP_HOLDER_PERCENT = BigDecimal("30")
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

private val ForegroundStatus.titleResource: Int
    get() =
        when (this) {
            ForegroundStatus.PAPER_ACTIVE -> R.string.monitor_status_paper_active
            ForegroundStatus.LIVE_ACTIVE -> R.string.monitor_status_live_active
            ForegroundStatus.PAUSED -> R.string.monitor_status_paused
            ForegroundStatus.PROTECTING_POSITION -> R.string.monitor_status_protecting
            ForegroundStatus.NEEDS_ATTENTION -> R.string.monitor_status_attention
        }

private fun AppSettings.walletAccessMode(): WalletAccessMode =
    if (unattendedMode && !secureSession) WalletAccessMode.UNATTENDED else WalletAccessMode.SECURE_SESSION

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
