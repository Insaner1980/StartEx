package com.finnvek.startex.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatteryOptimizationSettingsIntentTest {
    @Test
    fun opensTheSystemBatteryOptimizationList() {
        var launchedIntent: Intent? = null

        val launched = openBatteryOptimizationSettings { launchedIntent = it }

        assertTrue(launched)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, launchedIntent?.action)
        assertNull(launchedIntent?.data)
    }

    @Test
    fun reportsWhenTheSystemScreenIsUnavailable() {
        val launched =
            openBatteryOptimizationSettings {
                throw ActivityNotFoundException("No matching settings activity")
            }

        assertFalse(launched)
    }
}
