package com.finnvek.startex.service

import com.finnvek.startex.network.RetryPolicy
import com.finnvek.startex.security.WalletAccessMode

enum class RecoveryAction {
    REQUIRE_AUTHENTICATION,
    RECONCILE_AND_MONITOR,
    MONITOR_WITH_ENTRIES_BLOCKED,
    NOTIFY_AND_STOP,
}

enum class SessionStartAction {
    START_MONITORING,
    LIVE_EXECUTION_LOCKED,
    NOTIFY_AND_STOP,
}

object MonitoringSessionPolicy {
    internal val recoverableSessionStatuses = listOf("RUNNING", "PAUSED", "PROTECTING", "NEEDS_ATTENTION")

    fun isRecoverableSessionStatus(status: String): Boolean = status in recoverableSessionStatuses

    fun startAction(
        paperMode: Boolean,
        providersConfigured: Boolean,
        riskLimitsValid: Boolean,
    ): SessionStartAction =
        when {
            !paperMode -> SessionStartAction.LIVE_EXECUTION_LOCKED
            !providersConfigured || !riskLimitsValid -> SessionStartAction.NOTIFY_AND_STOP
            else -> SessionStartAction.START_MONITORING
        }

    fun recoveryAction(
        accessMode: WalletAccessMode,
        recoveryAuthenticated: Boolean,
        providersConfigured: Boolean,
        providersHealthy: Boolean,
        riskLimitsValid: Boolean,
    ): RecoveryAction =
        when {
            accessMode == WalletAccessMode.SECURE_SESSION && !recoveryAuthenticated -> {
                RecoveryAction.REQUIRE_AUTHENTICATION
            }

            !providersConfigured || !riskLimitsValid -> {
                RecoveryAction.NOTIFY_AND_STOP
            }

            !providersHealthy -> {
                RecoveryAction.MONITOR_WITH_ENTRIES_BLOCKED
            }

            else -> {
                RecoveryAction.RECONCILE_AND_MONITOR
            }
        }
}

class DiscoveryReconnectPolicy(
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val maximumConsecutiveFailures: Int = 5,
) {
    init {
        require(maximumConsecutiveFailures > 0)
    }

    fun nextDelayMillis(
        failureCount: Int,
        retryAfterMillis: Long?,
    ): Long? {
        require(failureCount > 0)
        if (failureCount >= maximumConsecutiveFailures) return null

        return retryPolicy.delayMillis(failureCount, retryAfterMillis)
    }
}

class DiscoveryFreshnessPolicy(
    private val maximumIdleMillis: Long = 90_000,
) {
    init {
        require(maximumIdleMillis > 0)
    }

    fun isStale(
        lastEventAtMillis: Long,
        nowMillis: Long,
    ): Boolean = nowMillis > lastEventAtMillis && nowMillis - lastEventAtMillis > maximumIdleMillis
}

class SessionHeartbeatFreshnessPolicy(
    private val maximumAgeMillis: Long = 90_000,
) {
    init {
        require(maximumAgeMillis > 0)
    }

    fun staleCutoffMillis(nowMillis: Long): Long = (nowMillis - maximumAgeMillis).coerceAtLeast(0)

    fun isStale(
        lastHeartbeatAtMillis: Long,
        nowMillis: Long,
    ): Boolean = lastHeartbeatAtMillis < staleCutoffMillis(nowMillis) || lastHeartbeatAtMillis > nowMillis
}
