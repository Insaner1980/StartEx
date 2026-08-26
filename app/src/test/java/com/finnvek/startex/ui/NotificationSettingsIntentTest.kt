package com.finnvek.startex.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
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
class NotificationSettingsIntentTest {
    @Test
    fun `notification action opens this apps Android settings`() {
        val context = RuntimeEnvironment.getApplication()
        var launchedIntent: Intent? = null

        val launched = openNotificationSettings(context) { launchedIntent = it }

        assertTrue(launched)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, launchedIntent?.action)
        assertEquals(context.packageName, launchedIntent?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `unavailable notification settings are reported`() {
        val context = RuntimeEnvironment.getApplication()

        val launched =
            openNotificationSettings(context) {
                throw ActivityNotFoundException("No matching settings activity")
            }

        assertFalse(launched)
    }
}
