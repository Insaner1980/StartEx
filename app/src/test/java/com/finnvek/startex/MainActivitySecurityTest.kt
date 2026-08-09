package com.finnvek.startex

import android.provider.Settings
import android.view.WindowManager
import com.finnvek.startex.service.TradingMonitorService
import com.finnvek.startex.ui.batteryOptimizationSettingsIntent
import com.finnvek.startex.ui.emergencyExitServiceIntent
import com.finnvek.startex.ui.sellNowServiceIntent
import com.finnvek.startex.ui.stopAfterCloseServiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivitySecurityTest {
    @Test
    fun secureFlagIsSetBeforeTheActivityIsShown() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNotEquals(
            0,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    @Test
    fun batterySettingsIntentOnlyOpensTheSystemSettingsList() {
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            batteryOptimizationSettingsIntent().action,
        )
        assertNull(batteryOptimizationSettingsIntent().data)
    }

    @Test
    fun paperExitIntentsAreExplicitAndCarryOnlyTheRequiredPositionId() {
        val context = RuntimeEnvironment.getApplication()
        val sell = sellNowServiceIntent(context, "position-7")
        val stopAfterClose = stopAfterCloseServiceIntent(context)
        val emergencyExit = emergencyExitServiceIntent(context)

        assertEquals(TradingMonitorService.ACTION_SELL_NOW, sell.action)
        assertEquals("position-7", sell.getStringExtra(TradingMonitorService.EXTRA_POSITION_ID))
        assertEquals(TradingMonitorService::class.java.name, sell.component?.className)
        assertEquals(TradingMonitorService.ACTION_STOP_AFTER_CLOSE, stopAfterClose.action)
        assertEquals(TradingMonitorService::class.java.name, stopAfterClose.component?.className)
        assertEquals(TradingMonitorService.ACTION_EMERGENCY_EXIT, emergencyExit.action)
        assertEquals(TradingMonitorService::class.java.name, emergencyExit.component?.className)
    }
}
