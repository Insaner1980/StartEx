package com.finnvek.startex.service

import com.finnvek.startex.StartExApplication
import com.finnvek.startex.data.local.PositionEntity
import java.math.BigDecimal

internal enum class ForegroundStatus {
    PAPER_ACTIVE,
    LIVE_ACTIVE,
    PAUSED,
    PROTECTING_POSITION,
    NEEDS_ATTENTION,
}

internal data class ForegroundNotificationModel(
    val mode: String,
    val sessionStatus: String,
    val protectingOnly: Boolean,
    val openPositionCount: Int,
    val sessionPnlLamports: Long?,
    val lastMarketSuccessAtMillis: Long?,
    val nowMillis: Long,
) {
    val status: ForegroundStatus
        get() =
            when {
                sessionStatus == "PAUSED" -> ForegroundStatus.PAUSED
                sessionStatus == "NEEDS_ATTENTION" -> ForegroundStatus.NEEDS_ATTENTION
                protectingOnly || sessionStatus == "PROTECTING" -> ForegroundStatus.PROTECTING_POSITION
                mode == "LIVE" -> ForegroundStatus.LIVE_ACTIVE
                else -> ForegroundStatus.PAPER_ACTIVE
            }

    val pnlText: String
        get() = sessionPnlLamports?.let(::formatSignedSol) ?: "Unavailable"

    val marketAgeText: String
        get() {
            val lastSuccess = lastMarketSuccessAtMillis ?: return "Unavailable"
            if (lastSuccess > nowMillis) return "Unavailable"
            val seconds = (nowMillis - lastSuccess) / 1_000
            return when {
                seconds < 60 -> "${seconds}s ago"
                seconds < 3_600 -> "${seconds / 60}m ago"
                else -> "${seconds / 3_600}h ago"
            }
        }
}

internal enum class AlertKind {
    BOT_STARTED,
    BOT_PAUSED,
    BOT_STOPPED,
    BOT_RECOVERED,
    BUY_SUBMITTED,
    BUY_CONFIRMED,
    BUY_FAILED,
    BUY_UNCERTAIN,
    SELL_SUBMITTED,
    SELL_CONFIRMED,
    SELL_FAILED,
    SELL_BLOCKED,
    SELL_RETRYING,
    EMERGENCY_EXIT,
    DAILY_LOSS,
    CIRCUIT_BREAKER,
    PROVIDER_STALE,
    PROVIDER_UNAVAILABLE,
    PROVIDER_QUOTA,
    MONITORING_LOST,
    LOW_RESERVE,
    INCOMING_SOL,
    MANUAL_TRANSFER_CONFIRMED,
    MANUAL_TRANSFER_FAILED,
    MANUAL_TRANSFER_UNCERTAIN,
    AUTHENTICATION_REQUIRED,
}

internal data class ServiceAlert(
    val kind: AlertKind,
    val channelId: String,
    val notificationId: Int,
    val dedupeKey: String,
)

internal class ServiceNotificationPolicy {
    @Suppress("LongMethod", "UNUSED_PARAMETER")
    fun alert(
        severity: String,
        category: String,
        code: String,
        relatedId: String?,
    ): ServiceAlert? {
        val kind =
            when (code) {
                "MONITORING_STARTED" -> AlertKind.BOT_STARTED
                "USER_PAUSED" -> AlertKind.BOT_PAUSED
                "USER_REQUESTED", "POSITIONS_CLOSED" -> AlertKind.BOT_STOPPED
                "MONITOR_ONLY_RECOVERY", "PROVIDER_HEALTH_RECOVERY" -> AlertKind.BOT_RECOVERED
                "LIVE_BUY_SUBMITTED" -> AlertKind.BUY_SUBMITTED
                "LIVE_BUY_CONFIRMED", "PAPER_POSITION_OPENED" -> AlertKind.BUY_CONFIRMED
                "LIVE_BUY_FAILED" -> AlertKind.BUY_FAILED
                "LIVE_BUY_UNCERTAIN" -> AlertKind.BUY_UNCERTAIN
                "LIVE_SELL_SUBMITTED", "PAPER_SELL_NOW_REQUESTED" -> AlertKind.SELL_SUBMITTED
                "LIVE_SELL_CONFIRMED", "PAPER_POSITION_CLOSED" -> AlertKind.SELL_CONFIRMED
                "LIVE_SELL_FAILED" -> AlertKind.SELL_FAILED
                "LIVE_SELL_BLOCKED", "PAPER_EXIT_BLOCKED" -> AlertKind.SELL_BLOCKED
                "LIVE_SELL_RETRYING", "PAPER_EXIT_RETRYING" -> AlertKind.SELL_RETRYING
                "PAPER_EMERGENCY_EXIT_REQUESTED" -> AlertKind.EMERGENCY_EXIT
                "DAILY_LOSS_LIMIT_REACHED" -> AlertKind.DAILY_LOSS
                "CIRCUIT_BREAKER_ACTIVE" -> AlertKind.CIRCUIT_BREAKER
                "STALE_DATA" -> AlertKind.PROVIDER_STALE
                "PROVIDER_UNAVAILABLE" -> AlertKind.PROVIDER_UNAVAILABLE
                "RATE_LIMITED", "PROVIDER_QUOTA_EXHAUSTED" -> AlertKind.PROVIDER_QUOTA
                "OPEN_POSITION_MONITORING_LOST" -> AlertKind.MONITORING_LOST
                "LOW_SOL_RESERVE" -> AlertKind.LOW_RESERVE
                "INCOMING_SOL_DETECTED" -> AlertKind.INCOMING_SOL
                "MANUAL_TRANSFER_CONFIRMED" -> AlertKind.MANUAL_TRANSFER_CONFIRMED
                "MANUAL_TRANSFER_FAILED" -> AlertKind.MANUAL_TRANSFER_FAILED
                "MANUAL_TRANSFER_UNCERTAIN" -> AlertKind.MANUAL_TRANSFER_UNCERTAIN
                "RECOVERY_AUTHENTICATION_REQUIRED" -> AlertKind.AUTHENTICATION_REQUIRED
                else -> null
            } ?: return null
        if (category == "CANDIDATE") return null

        val channel =
            when (kind) {
                AlertKind.BUY_SUBMITTED,
                AlertKind.BUY_CONFIRMED,
                AlertKind.BUY_FAILED,
                AlertKind.BUY_UNCERTAIN,
                AlertKind.SELL_SUBMITTED,
                AlertKind.SELL_CONFIRMED,
                AlertKind.SELL_FAILED,
                AlertKind.SELL_RETRYING,
                AlertKind.INCOMING_SOL,
                AlertKind.MANUAL_TRANSFER_CONFIRMED,
                AlertKind.MANUAL_TRANSFER_FAILED,
                AlertKind.MANUAL_TRANSFER_UNCERTAIN,
                -> StartExApplication.CHANNEL_TRADES

                AlertKind.PROVIDER_STALE,
                AlertKind.PROVIDER_UNAVAILABLE,
                AlertKind.PROVIDER_QUOTA,
                -> StartExApplication.CHANNEL_PROVIDER

                AlertKind.SELL_BLOCKED,
                AlertKind.EMERGENCY_EXIT,
                AlertKind.DAILY_LOSS,
                AlertKind.CIRCUIT_BREAKER,
                AlertKind.MONITORING_LOST,
                AlertKind.LOW_RESERVE,
                AlertKind.AUTHENTICATION_REQUIRED,
                -> StartExApplication.CHANNEL_CRITICAL

                AlertKind.BOT_STARTED,
                AlertKind.BOT_PAUSED,
                AlertKind.BOT_STOPPED,
                AlertKind.BOT_RECOVERED,
                -> StartExApplication.CHANNEL_BOT_STATUS
            }
        val subject = relatedId.orEmpty().take(128)
        val key = "${kind.name}:$subject"
        val id = ALERT_NOTIFICATION_ID_BASE + (key.hashCode() and Int.MAX_VALUE) % ALERT_NOTIFICATION_ID_RANGE
        return ServiceAlert(kind, channel, id, key)
    }

    private companion object {
        const val ALERT_NOTIFICATION_ID_BASE = 2_000
        const val ALERT_NOTIFICATION_ID_RANGE = 1_000_000
    }
}

internal fun paperEmergencyExitUpdates(
    positions: List<PositionEntity>,
    sessionId: String,
    nowMillis: Long,
): List<PositionEntity> =
    positions.mapNotNull { position ->
        if (
            position.sessionId != sessionId ||
            position.mode != "PAPER" ||
            position.status !in setOf("OPEN", "EXIT_REQUESTED", "EXIT_BLOCKED")
        ) {
            null
        } else {
            position.copy(
                status = "EXIT_REQUESTED",
                exitReason = "EMERGENCY_EXIT",
                updatedAtMillis = nowMillis,
            )
        }
    }

private fun formatSignedSol(lamports: Long): String {
    val sol =
        BigDecimal
            .valueOf(lamports)
            .movePointLeft(9)
            .stripTrailingZeros()
            .toPlainString()
    return if (lamports > 0) "+$sol SOL" else "$sol SOL"
}
