package com.finnvek.startex

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = StartExApplication::class)
class StartExNotificationChannelTest {
    @Test
    fun `application creates required notification channels`() {
        val application = RuntimeEnvironment.getApplication() as StartExApplication
        val manager = application.getSystemService(NotificationManager::class.java)

        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(StartExApplication.CHANNEL_BOT_STATUS).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(StartExApplication.CHANNEL_TRADES).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(StartExApplication.CHANNEL_CRITICAL).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(StartExApplication.CHANNEL_PROVIDER).importance,
        )
        assertNotNull(manager.getNotificationChannel(StartExApplication.CHANNEL_CANDIDATES))
    }
}
