package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.ui.screens.OnboardingScreen
import com.finnvek.startex.ui.screens.ProviderSetupScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderSetupAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun providerFieldsStayDistinctAndKeyboardReachableAtMaximumScaleInRtlLandscape() {
        compose.setContent {
            val density = LocalDensity.current
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(180.dp)) {
                        ProviderSetupScreen(
                            configuredProviders = emptySet(),
                            activatedProviders = emptySet(),
                            providerHealth = emptyList(),
                            onSave = { _, _ -> },
                            onRemove = {},
                            onTest = {},
                            onBack = {},
                        )
                    }
                }
            }
        }

        val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        val heliusField =
            compose.onNode(
                hasText("Helius API key") and
                    hasSetTextAction() and
                    password and
                    hasImeAction(ImeAction.Done),
            )
        heliusField.performScrollTo().assertIsDisplayed()
        compose
            .onAllNodesWithText(
                "Enter an API key to enable saving. The stored value is never displayed again.",
            )[0]
            .assertIsDisplayed()
        heliusField.performTextInput("secret")
        heliusField.requestFocus().assertIsFocused()
        heliusField.performKeyInput { pressKey(Key.Tab) }
        val firstSave = compose.onAllNodesWithText("Save key")[0].assertIsFocused()

        val minimumTarget = with(compose.density) { 48.dp.toPx() }
        assertTrue(firstSave.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTarget)
        compose.onNode(hasText("PumpPortal API key") and password).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Jupiter API key") and password).performScrollTo().assertIsDisplayed()
        val back = compose.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
        assertTrue(back.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTarget)
    }

    @Test
    fun providerStatesAndReadOnlyTestProgressAreExplicit() {
        compose.setContent {
            StartExTheme {
                ProviderSetupScreen(
                    configuredProviders = CredentialProviders.toSet(),
                    activatedProviders = CredentialProviders.toSet(),
                    providerHealth = emptyList(),
                    testingProviders = setOf(ProviderId.HELIUS),
                    onSave = { _, _ -> },
                    onRemove = {},
                    onTest = {},
                    onBack = {},
                )
            }
        }

        compose.onAllNodesWithText("CHECK PENDING")[0].assertIsDisplayed()
        compose
            .onNodeWithText("Helius read-only test in progress")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        compose
            .onNodeWithText(
                "PumpPortal is verified only by the monitoring WebSocket connection; " +
                    "saving a key does not mark it healthy.",
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Run Jupiter read-only test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Run Kraken read-only test").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun providerPanelReturnRestoresOnboardingTriggerFocus() {
        compose.setContent {
            var showProviders by remember { mutableStateOf(false) }
            var restoreFocus by remember { mutableStateOf(false) }
            val providersFocusRequester = remember { FocusRequester() }
            val onboardingStateHolder = rememberSaveableStateHolder()
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            LaunchedEffect(showProviders) {
                if (!showProviders && restoreFocus) {
                    withFrameNanos { }
                    providersFocusRequester.requestFocus()
                    restoreFocus = false
                }
            }
            StartExTheme {
                if (showProviders) {
                    ProviderSetupScreen(
                        configuredProviders = emptySet(),
                        activatedProviders = emptySet(),
                        providerHealth = emptyList(),
                        onSave = { _, _ -> },
                        onRemove = {},
                        onTest = {},
                        onBack = { showProviders = false },
                    )
                } else {
                    onboardingStateHolder.SaveableStateProvider("onboarding") {
                        OnboardingScreen(
                            state = PersistedAppState(loaded = true),
                            onCreateWallet = {},
                            onRestoreWallet = {},
                            onConfigureProviders = {
                                restoreFocus = true
                                showProviders = true
                            },
                            onTrustedAddresses = {},
                            onRequestSecurityMode = { _, _, _ -> },
                            onComplete = {},
                            providersFocusRequester = providersFocusRequester,
                        )
                    }
                }
            }
        }

        repeat(4) {
            compose.onNodeWithTag("onboarding_acknowledge").performScrollTo().performClick()
            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
        }
        val trigger =
            compose
                .onNodeWithText("Configure providers")
                .performScrollTo()
                .requestFocus()
                .assertIsFocused()
        trigger.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Provider setup").assertTextContains("Provider setup")
        compose.onNodeWithText("Back").performScrollTo().performClick()

        compose.onNodeWithText("Configure providers").assertIsFocused()
    }
}
