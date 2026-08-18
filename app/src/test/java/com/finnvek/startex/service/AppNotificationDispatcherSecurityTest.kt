package com.finnvek.startex.service

import com.finnvek.startex.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
