package com.finnvek.startex

import android.content.ComponentName
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.finnvek.startex.service.TradingMonitorService
import com.finnvek.startex.ui.AuthenticationPurpose
import com.finnvek.startex.ui.StartExUiEvent
import com.finnvek.startex.ui.StartExViewModel
import com.finnvek.startex.ui.batteryOptimizationSettingsIntent
import com.finnvek.startex.ui.emergencyExitServiceIntent
import com.finnvek.startex.ui.sellNowServiceIntent
import com.finnvek.startex.ui.stopAfterCloseServiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun debugPreviewActivityIsNotExported() {
        val context = RuntimeEnvironment.getApplication()
        val activity =
            context.packageManager.getActivityInfo(
                ComponentName(context, "androidx.compose.ui.tooling.PreviewActivity"),
                0,
            )

        assertFalse(activity.exported)
    }

    @Test
    fun debugComposeTestHostActivityIsNotExported() {
        val context = RuntimeEnvironment.getApplication()
        val activity =
            context.packageManager.getActivityInfo(
                ComponentName(context, "androidx.activity.ComponentActivity"),
                0,
            )

        assertFalse(activity.exported)
    }

    @Test
    fun secureFlagIsSetBeforeTheActivityIsShown() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNotEquals(
            0,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
        assertTrue(activity.window.decorView.filterTouchesWhenObscured)
    }

    @Test
    fun systemBarIconsStayLightForTheAlwaysDarkTheme() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
    }

    @Suppress("DEPRECATION")
    @Test
    @Config(sdk = [29, 30, 31, 32, 33, 34, 35])
    fun mainActivityResizesForTheSoftwareKeyboardAcrossSupportedApis() {
        val context = RuntimeEnvironment.getApplication()
        val activity =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                0,
            )

        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }

    @Test
    fun pendingAuthenticationSurvivesRecreationAndClearsWithTheViewModel() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val firstViewModel = ViewModelProvider(controller.get())[StartExViewModel::class.java]
        firstViewModel.onAuthenticationPromptStarted(
            StartExUiEvent.Authenticate(AuthenticationPurpose.CreateWallet),
        )

        controller.recreate()

        val recreatedViewModel = ViewModelProvider(controller.get())[StartExViewModel::class.java]
        assertSame(firstViewModel, recreatedViewModel)
        assertTrue(recreatedViewModel.authenticationInProgress)

        controller.get().finish()
        controller.pause().stop().destroy()
        assertFalse(firstViewModel.authenticationInProgress)
    }

    @Test
    fun partiallyObscuredTouchesAreRejected() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val event = touchEvent(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)

        try {
            assertFalse(activity.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun fullyObscuredTouchesAreFilteredWhileUnobscuredTouchesRemainUsable() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var deliveredTouches = 0
        activity.setContentView(
            object : View(activity) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    deliveredTouches += 1
                    return true
                }
            },
        )
        val size = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        activity.window.decorView.measure(size, size)
        activity.window.decorView.layout(0, 0, 100, 100)
        val obscured = touchEvent(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        val unobscured = touchEvent(0)

        try {
            assertFalse(activity.dispatchTouchEvent(obscured))
            assertEquals(0, deliveredTouches)
            assertTrue(activity.dispatchTouchEvent(unobscured))
            assertEquals(1, deliveredTouches)
        } finally {
            obscured.recycle()
            unobscured.recycle()
        }
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

    private fun touchEvent(flags: Int): MotionEvent =
        MotionEvent.obtain(
            0,
            0,
            MotionEvent.ACTION_DOWN,
            1,
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            ),
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = 10f
                    y = 10f
                    pressure = 1f
                    size = 1f
                },
            ),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            flags,
        )
}
