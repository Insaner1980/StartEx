package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.ui.screens.SettingsScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenContractTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun blockedNotificationsAreVisibleInSettings() {
        compose.setContent {
            StartExTheme {
                SettingsScreen(
                    state = PersistedAppState(loaded = true),
                    onProviders = {},
                    onPreflight = {},
                    onStop = {},
                    onSetDemoMode = {},
                    onRequestSecurityMode = { _, _, _ -> },
                    onStrategyAndRisk = {},
                    onBatteryOptimizationSettings = {},
                )
            }
        }

        compose
            .onNode(
                hasText("Action required") and
                    hasAnyAncestor(hasTestTag("settings_notification_status")),
                useUnmergedTree = true,
            ).assertExists()
    }

    @Test
    fun settingsExposeHonestActionsToggleRolesAndDisabledReasons() {
        compose.setContent {
            StartExTheme {
                SettingsScreen(
                    state = PersistedAppState(loaded = true, demoMode = true),
                    onProviders = {},
                    onPreflight = {},
                    onStop = {},
                    onSetDemoMode = {},
                    onRequestSecurityMode = { _, _, _ -> },
                    onStrategyAndRisk = {},
                    onBatteryOptimizationSettings = {},
                )
            }
        }

        val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onNode(hasText("Deterministic Demo") and switchRole).assertExists()
        compose.onNode(hasText("Security") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Trading mode") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Notifications") and hasClickAction()).assertDoesNotExist()

        val demoReason =
            "This action is unavailable in Demo. Disable Demo in Settings to use real wallet or provider operations."
        compose
            .onNode(hasText("Strategy and risk") and hasStateDescription(demoReason))
            .performScrollTo()
            .assertIsNotEnabled()

        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose
            .onNode(hasText("Secure session") and radioRole and hasStateDescription(demoReason))
            .performScrollTo()
            .assertIsNotEnabled()
        compose
            .onNode(hasText("Unattended restart") and radioRole and hasStateDescription(demoReason))
            .performScrollTo()
            .assertIsNotEnabled()
        compose
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
                useUnmergedTree = true,
            ).assertExists()
    }

    @Test
    fun securityRowsMeetMinimumHeightAndLargeRtlLandscapeKeepsSafetyContentReachable() {
        // This scenario owns its constrained RTL composition.
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(180.dp)) {
                        SettingsScreen(
                            state =
                                PersistedAppState(
                                    loaded = true,
                                    monitorState = MonitorState.Running,
                                    risk = DefaultConfiguration.risk(createdAtMillis = 0),
                                ),
                            onProviders = {},
                            onPreflight = {},
                            onStop = {},
                            onSetDemoMode = {},
                            onRequestSecurityMode = { _, _, _ -> },
                            onStrategyAndRisk = {},
                            onBatteryOptimizationSettings = {},
                        )
                    }
                }
            }
        }

        // CPD-ON
        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val minimumRowHeight = with(compose.density) { 56.dp.toPx() }
        val secureSession =
            compose
                .onNode(hasText("Secure session") and radioRole)
                .performScrollTo()
                .assertIsDisplayed()
        assertTrue(secureSession.fetchSemanticsNode().boundsInRoot.height >= minimumRowHeight)
        val unattended =
            compose
                .onNode(hasText("Unattended restart") and radioRole)
                .performScrollTo()
                .assertIsDisplayed()
        assertTrue(unattended.fetchSemanticsNode().boundsInRoot.height >= minimumRowHeight)

        compose
            .onNodeWithText("Current caps: 0.01 SOL total exposure · 0.015 SOL daily loss")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasText("Emergency controls") and isHeading()).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Stopping prevents new monitoring work. It cannot reverse a submitted transaction.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Stop monitoring").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun focusAndSemanticActivationRestoreSettingsFocusAfterPanelReturn() {
        compose.setContent {
            var showSettings by remember { mutableStateOf(true) }
            var restoreSettingsFocus by remember { mutableStateOf(false) }
            var demoMode by remember { mutableStateOf(false) }
            val strategyFocusRequester = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            LaunchedEffect(showSettings) {
                if (showSettings && restoreSettingsFocus) {
                    strategyFocusRequester.requestFocus()
                    restoreSettingsFocus = false
                }
            }
            StartExTheme {
                if (showSettings) {
                    SettingsScreen(
                        state = PersistedAppState(loaded = true, demoMode = demoMode),
                        onProviders = {},
                        onPreflight = {},
                        onStop = {},
                        onSetDemoMode = { demoMode = it },
                        onRequestSecurityMode = { _, _, _ -> },
                        onStrategyAndRisk = {
                            restoreSettingsFocus = true
                            showSettings = false
                        },
                        onBatteryOptimizationSettings = {},
                        strategyFocusRequester = strategyFocusRequester,
                    )
                } else {
                    Button(onClick = { showSettings = true }) { Text("Back to Settings") }
                }
            }
        }

        compose
            .onNode(hasText("Strategy and risk") and hasClickAction())
            .performScrollTo()
            .requestFocus()
            .assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Back to Settings").performClick()
        compose.onNode(hasText("Strategy and risk") and hasClickAction()).assertIsFocused()
    }
}
