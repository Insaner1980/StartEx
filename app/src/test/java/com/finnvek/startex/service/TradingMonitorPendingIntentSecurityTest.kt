package com.finnvek.startex.service

import android.content.Intent
import com.finnvek.startex.service.TradingMonitorService.Companion.ACTION_PAUSE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TradingMonitorPendingIntentSecurityTest {
    @Test
    fun `notification action explicitly targets the service and is bound to its session`() {
        val context = RuntimeEnvironment.getApplication()

        val intent = notificationServiceIntent(context, ACTION_PAUSE, "session-1")

        assertEquals(TradingMonitorService::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(ACTION_PAUSE, intent.action)
        assertEquals("startex://notification/session/session-1", intent.dataString)
    }

    @Test
    fun `notification action rejects a stale session or state`() {
        val context = RuntimeEnvironment.getApplication()
        val current = notificationServiceIntent(context, ACTION_PAUSE, "session-current")
        val stale = notificationServiceIntent(context, ACTION_PAUSE, "session-stale")

        assertTrue(acceptsNotificationAction(current, "session-current", expectedState = true))
        assertFalse(acceptsNotificationAction(stale, "session-current", expectedState = true))
        assertFalse(acceptsNotificationAction(current, "session-current", expectedState = false))
    }

    @Test
    fun `direct in-app service action keeps its existing routing`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, TradingMonitorService::class.java).setAction(ACTION_PAUSE)

        assertTrue(acceptsNotificationAction(intent, currentSessionId = null, expectedState = false))
    }
}
