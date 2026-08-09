package com.finnvek.startex.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.finnvek.startex.MainActivity
import com.finnvek.startex.R
import java.util.concurrent.ConcurrentHashMap

internal object AppNotificationDispatcher {
    private val policy = ServiceNotificationPolicy()
    private val lastAlertAtMillis = ConcurrentHashMap<String, Long>()

    @SuppressLint("MissingPermission")
    fun notify(
        context: Context,
        severity: String,
        category: String,
        code: String,
        relatedId: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val alert = policy.alert(severity, category, code, relatedId) ?: return
        if (!reserveAlert(alert.dedupeKey, nowMillis)) return
        if (!context.canPostAlerts()) {
            lastAlertAtMillis.remove(alert.dedupeKey, nowMillis)
            return
        }
        val (title, body) = alert.kind.textResources
        val openApp =
            PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                notificationOpenAppIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, alert.channelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(title))
                .setContentText(context.getString(body))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        NotificationManagerCompat.from(context).notify(alert.notificationId, notification)
    }

    private fun reserveAlert(
        key: String,
        nowMillis: Long,
    ): Boolean {
        while (true) {
            val previous = lastAlertAtMillis[key]
            if (
                previous != null &&
                nowMillis >= previous &&
                nowMillis - previous < ALERT_DEDUPE_MILLIS
            ) {
                return false
            }
            if (previous == null) {
                if (lastAlertAtMillis.putIfAbsent(key, nowMillis) == null) return true
            } else if (lastAlertAtMillis.replace(key, previous, nowMillis)) {
                return true
            }
        }
    }

    private fun Context.canPostAlerts(): Boolean =
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) &&
            NotificationManagerCompat.from(this).areNotificationsEnabled()

    private val AlertKind.textResources: Pair<Int, Int>
        get() =
            when (this) {
                AlertKind.BOT_STARTED -> {
                    R.string.notification_bot_started_title to R.string.notification_bot_started_body
                }

                AlertKind.BOT_PAUSED -> {
                    R.string.notification_bot_paused_title to R.string.notification_bot_paused_body
                }

                AlertKind.BOT_STOPPED -> {
                    R.string.notification_bot_stopped_title to R.string.notification_bot_stopped_body
                }

                AlertKind.BOT_RECOVERED -> {
                    R.string.notification_bot_recovered_title to R.string.notification_bot_recovered_body
                }

                AlertKind.BUY_SUBMITTED -> {
                    R.string.notification_buy_submitted_title to R.string.notification_buy_submitted_body
                }

                AlertKind.BUY_CONFIRMED -> {
                    R.string.notification_buy_confirmed_title to R.string.notification_buy_confirmed_body
                }

                AlertKind.BUY_FAILED -> {
                    R.string.notification_buy_failed_title to R.string.notification_buy_failed_body
                }

                AlertKind.BUY_UNCERTAIN -> {
                    R.string.notification_buy_uncertain_title to R.string.notification_buy_uncertain_body
                }

                AlertKind.SELL_SUBMITTED -> {
                    R.string.notification_sell_submitted_title to R.string.notification_sell_submitted_body
                }

                AlertKind.SELL_CONFIRMED -> {
                    R.string.notification_sell_confirmed_title to R.string.notification_sell_confirmed_body
                }

                AlertKind.SELL_FAILED -> {
                    R.string.notification_sell_failed_title to R.string.notification_sell_failed_body
                }

                AlertKind.SELL_BLOCKED -> {
                    R.string.notification_sell_blocked_title to R.string.notification_sell_blocked_body
                }

                AlertKind.SELL_RETRYING -> {
                    R.string.notification_sell_retrying_title to R.string.notification_sell_retrying_body
                }

                AlertKind.EMERGENCY_EXIT -> {
                    R.string.notification_emergency_exit_title to R.string.notification_emergency_exit_body
                }

                AlertKind.DAILY_LOSS -> {
                    R.string.notification_daily_loss_title to R.string.notification_daily_loss_body
                }

                AlertKind.CIRCUIT_BREAKER -> {
                    R.string.notification_circuit_title to R.string.notification_circuit_body
                }

                AlertKind.PROVIDER_STALE -> {
                    R.string.notification_provider_stale_title to R.string.notification_provider_stale_body
                }

                AlertKind.PROVIDER_UNAVAILABLE -> {
                    R.string.notification_provider_unavailable_title to
                        R.string.notification_provider_unavailable_body
                }

                AlertKind.PROVIDER_QUOTA -> {
                    R.string.notification_provider_quota_title to R.string.notification_provider_quota_body
                }

                AlertKind.MONITORING_LOST -> {
                    R.string.notification_monitoring_lost_title to R.string.notification_monitoring_lost_body
                }

                AlertKind.LOW_RESERVE -> {
                    R.string.notification_low_reserve_title to R.string.notification_low_reserve_body
                }

                AlertKind.INCOMING_SOL -> {
                    R.string.notification_incoming_sol_title to R.string.notification_incoming_sol_body
                }

                AlertKind.MANUAL_TRANSFER_CONFIRMED -> {
                    R.string.notification_transfer_confirmed_title to R.string.notification_transfer_confirmed_body
                }

                AlertKind.MANUAL_TRANSFER_FAILED -> {
                    R.string.notification_transfer_failed_title to R.string.notification_transfer_failed_body
                }

                AlertKind.MANUAL_TRANSFER_UNCERTAIN -> {
                    R.string.notification_transfer_uncertain_title to R.string.notification_transfer_uncertain_body
                }

                AlertKind.AUTHENTICATION_REQUIRED -> {
                    R.string.notification_auth_required_title to R.string.notification_auth_required_body
                }
            }

    private const val REQUEST_OPEN = 100
    private const val ALERT_DEDUPE_MILLIS = 15 * 60 * 1_000L
}

internal fun notificationOpenAppIntent(context: Context): Intent = Intent().setClass(context, MainActivity::class.java)
