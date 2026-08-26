package com.finnvek.startex.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import com.finnvek.startex.ui.screens.PreflightScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PreflightBackNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun androidBackDismissesPreflightPanel() {
        var backCalls = 0
        compose.setContent {
            StartExTheme {
                PreflightScreen(
                    state = PreflightState(),
                    mode = TradingMode.Paper,
                    onBack = { backCalls++ },
                    onStart = {},
                    onRequestNotifications = {},
                )
            }
        }

        pressBack()

        compose.runOnIdle { assertEquals(1, backCalls) }
    }
}
