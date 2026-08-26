package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.ui.screens.HomeScreen
import com.finnvek.startex.ui.screens.PreflightScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PreflightAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun checklistGroupsStatusAndReasonAndExplainsEveryDisabledStart() {
        compose.setContent {
            StartExTheme {
                PreflightScreen(
                    state = PreflightState(),
                    mode = TradingMode.Paper,
                    onBack = {},
                    onStart = {},
                    onRequestNotifications = {},
                )
            }
        }

        compose
            .onNode(
                hasText("Wallet created and unlocked") and
                    hasText("Blocking: action required") and
                    hasText("Create or unlock the dedicated wallet."),
            ).assertIsDisplayed()
        compose
            .onNode(
                hasText("Fee and exit reserve available") and
                    hasText("Optional: not required in Paper"),
            ).performScrollTo()
            .assertIsDisplayed()

        val disabledReason =
            "Start is disabled until these blocking checks pass: Wallet created and unlocked; " +
                "Recovery phrase backup confirmed; Required providers healthy; Risk limits configured; " +
                "Foreground and critical notifications allowed; Device and connection health ready."
        compose
            .onNodeWithText("Start Paper monitoring")
            .performScrollTo()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    disabledReason,
                ),
            )
    }

    @Test
    fun liveModeAndStartTimeProviderUncertaintyRemainExplicit() {
        val ready =
            PreflightState(
                walletReady = true,
                backupVerified = true,
                providersHealthy = true,
                limitsConfigured = true,
                reserveReady = true,
                reserveRequired = true,
                notificationsAllowed = true,
                pumpHealthCheckedOnStart = false,
                deviceHealthReady = true,
            )
        compose.setContent {
            StartExTheme {
                PreflightScreen(
                    state = ready,
                    mode = TradingMode.Live,
                    onBack = {},
                    onStart = {},
                    onRequestNotifications = {},
                )
            }
        }

        compose
            .onNode(
                hasText("Required providers healthy") and
                    hasText("Warning: checked on start") and
                    hasText(
                        "PumpPortal health is established by the monitoring WebSocket. " +
                            "Entries stay blocked until that connection produces fresh data.",
                    ),
            ).performScrollTo()
            .assertIsDisplayed()
        val liveReason = "Automated Live trading is unavailable in this build. Use Paper mode."
        compose.onNodeWithText(liveReason).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Start Paper monitoring")
            .performScrollTo()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    liveReason,
                ),
            )
    }

    @Test
    fun narrowRtlLandscapeSupportsLargeTextKeyboardAndHomeFocusRestoration() {
        compose.setContent {
            val density = LocalDensity.current
            val inputModeManager = LocalInputModeManager.current
            var showPreflight by remember { mutableStateOf(false) }
            var restoreHomeFocus by remember { mutableStateOf(false) }
            val preflightFocusRequester = remember { FocusRequester() }
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            LaunchedEffect(showPreflight) {
                if (!showPreflight && restoreHomeFocus) {
                    preflightFocusRequester.requestFocus()
                    restoreHomeFocus = false
                }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(180.dp)) {
                        if (showPreflight) {
                            PreflightScreen(
                                state = readyPaperPreflight(),
                                mode = TradingMode.Paper,
                                onBack = { showPreflight = false },
                                onStart = {},
                                onRequestNotifications = {},
                            )
                        } else {
                            HomeScreen(
                                state =
                                    PersistedAppState(
                                        loaded = true,
                                        onboardingComplete = true,
                                    ),
                                onPreflight = {
                                    restoreHomeFocus = true
                                    showPreflight = true
                                },
                                onRecover = {},
                                onPause = {},
                                onResume = {},
                                onStop = {},
                                onSellNow = {},
                                onEmergencyExit = {},
                                onStopAfterClose = {},
                                preflightFocusRequester = preflightFocusRequester,
                            )
                        }
                    }
                }
            }
        }

        val trigger =
            compose
                .onNodeWithText("Run preflight")
                .performScrollTo()
                .requestFocus()
                .assertIsFocused()
        trigger.performClick()

        val start =
            compose
                .onNodeWithText("Start Paper monitoring")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
                .requestFocus()
                .assertIsFocused()
        start.performKeyInput { pressKey(Key.Tab) }
        val back = compose.onNodeWithText("Back").assertIsFocused()
        val minimumTarget = with(compose.density) { 48.dp.toPx() }
        assertTrue(start.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTarget)
        assertTrue(back.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTarget)

        back.performClick()
        trigger.assertIsFocused()
    }

    private fun readyPaperPreflight() =
        PreflightState(
            walletReady = true,
            backupVerified = true,
            providersHealthy = true,
            limitsConfigured = true,
            notificationsAllowed = true,
            pumpHealthCheckedOnStart = true,
            deviceHealthReady = true,
        )
}
