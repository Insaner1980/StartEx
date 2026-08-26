package com.finnvek.startex.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import com.finnvek.startex.MainActivity
import com.finnvek.startex.StartExApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNotificationDispatcherSecurityTest {
    @Test
    fun `notification open intent explicitly targets MainActivity`() {
        val context = RuntimeEnvironment.getApplication()

        val intent = notificationOpenAppIntent(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
    }

    @Test
    @Config(sdk = [32])
    fun `pre Android 13 delivery depends on system notification setting`() {
        val application = RuntimeEnvironment.getApplication()
        val notificationManager = application.getSystemService(NotificationManager::class.java)

        shadowOf(notificationManager).setNotificationsEnabled(true)
        assertTrue(application.canPostNotifications())

        shadowOf(notificationManager).setNotificationsEnabled(false)
        assertFalse(application.canPostNotifications())
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 delivery requires runtime permission and system setting`() {
        val application = RuntimeEnvironment.getApplication()
        val shadowApplication = shadowOf(application)
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val shadowNotificationManager = shadowOf(notificationManager)
        shadowNotificationManager.setNotificationsEnabled(true)

        shadowApplication.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(application.canPostNotifications())

        shadowApplication.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(application.canPostNotifications())

        shadowNotificationManager.setNotificationsEnabled(false)
        assertFalse(application.canPostNotifications())
    }

    @Test
    @Config(sdk = [33], application = StartExApplication::class)
    fun `preflight requires foreground and critical notification channels`() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        shadowOf(notificationManager).setNotificationsEnabled(true)

        assertTrue(
            application.canPostNotifications(
                StartExApplication.CHANNEL_BOT_STATUS,
                StartExApplication.CHANNEL_CRITICAL,
            ),
        )

        notificationManager.deleteNotificationChannel(StartExApplication.CHANNEL_CRITICAL)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                StartExApplication.CHANNEL_CRITICAL,
                "Critical safety",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertFalse(
            application.canPostNotifications(
                StartExApplication.CHANNEL_BOT_STATUS,
                StartExApplication.CHANNEL_CRITICAL,
            ),
        )
    }

    // The permission-blocked and system-blocked scenarios intentionally repeat the full retry sequence.
    // CPD-OFF
    @Test
    @Config(sdk = [33], application = StartExApplication::class)
    fun `blocked critical alert can be retried after notification access is restored`() {
        val application = RuntimeEnvironment.getApplication()
        val shadowApplication = shadowOf(application)
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val shadowNotificationManager = shadowOf(notificationManager)
        val alert =
            requireNotNull(
                ServiceNotificationPolicy().alert(
                    severity = "ERROR",
                    category = "PAPER",
                    code = "PAPER_EXIT_BLOCKED",
                    relatedId = "permission-retry",
                ),
            )
        shadowNotificationManager.setNotificationsEnabled(true)
        shadowApplication.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        AppNotificationDispatcher.notify(
            application,
            "ERROR",
            "PAPER",
            "PAPER_EXIT_BLOCKED",
            "permission-retry",
            nowMillis = 1_000,
        )
        assertNull(shadowNotificationManager.getNotification(alert.notificationId))

        shadowApplication.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AppNotificationDispatcher.notify(
            application,
            "ERROR",
            "PAPER",
            "PAPER_EXIT_BLOCKED",
            "permission-retry",
            nowMillis = 1_000,
        )
        assertNotNull(shadowNotificationManager.getNotification(alert.notificationId))
    }
    // CPD-ON

    @Test
    @Config(sdk = [33], application = StartExApplication::class)
    fun `system blocked critical alert can be retried after notifications are enabled`() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val shadowNotificationManager = shadowOf(notificationManager)
        val alert =
            requireNotNull(
                ServiceNotificationPolicy().alert(
                    severity = "ERROR",
                    category = "PAPER",
                    code = "PAPER_EXIT_BLOCKED",
                    relatedId = "settings-retry",
                ),
            )
        shadowNotificationManager.setNotificationsEnabled(false)

        AppNotificationDispatcher.notify(
            application,
            "ERROR",
            "PAPER",
            "PAPER_EXIT_BLOCKED",
            "settings-retry",
            nowMillis = 2_000,
        )
        assertNull(shadowNotificationManager.getNotification(alert.notificationId))

        shadowNotificationManager.setNotificationsEnabled(true)
        AppNotificationDispatcher.notify(
            application,
            "ERROR",
            "PAPER",
            "PAPER_EXIT_BLOCKED",
            "settings-retry",
            nowMillis = 2_000,
        )
        assertNotNull(shadowNotificationManager.getNotification(alert.notificationId))
    }

    @Test
    fun `notification dedupe retains only subjects inside the active window`() {
        val alerts = ConcurrentHashMap(mapOf("expired" to 0L, "active" to 999L))

        pruneExpiredAlerts(alerts, nowMillis = 1_000, dedupeMillis = 1_000)

        assertEquals(mapOf("active" to 999L), alerts)
    }

    @Test
    fun `clock rollback does not retain future reservations`() {
        val alerts = ConcurrentHashMap(mapOf("future" to 2_000L, "current" to 1_000L))

        pruneExpiredAlerts(alerts, nowMillis = 1_000, dedupeMillis = 1_000)

        assertEquals(mapOf("current" to 1_000L), alerts)
    }
}
