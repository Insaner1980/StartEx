package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.ui.screens.HistoryScreen
import com.finnvek.startex.ui.screens.HomeScreen
import com.finnvek.startex.ui.screens.SettingsScreen
import com.finnvek.startex.ui.screens.WalletScreen
import com.finnvek.startex.ui.screens.WatchScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class DemoModeAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun demoEmptySurfacesExplainThatRealDataIsHidden() {
        val surface = mutableStateOf(DemoSurface.Home)
        // Accessibility scenarios intentionally declare their own constrained composition.
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(240.dp)) {
                        when (surface.value) {
                            DemoSurface.Home -> {
                                DemoHome()
                            }

                            DemoSurface.Watch -> {
                                WatchScreen(emptyList(), emptyList(), demoMode = true)
                            }

                            DemoSurface.History -> {
                                HistoryScreen(
                                    state = PersistedAppState(loaded = true, demoMode = true),
                                    onExportCsv = {},
                                    onExportJson = {},
                                )
                            }
                        }
                    }
                }
            }
        }

        // CPD-ON
        compose.onNodeWithText("No synthetic positions").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Demo never creates positions or starts monitoring. Real positions are not shown in Demo.")
            .performScrollTo()
            .assertIsDisplayed()

        compose.runOnIdle { surface.value = DemoSurface.Watch }
        compose.waitForIdle()
        compose
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("No synthetic monitoring activity"))
        compose.onNodeWithText("No synthetic monitoring activity").assertIsDisplayed()
        compose
            .onNodeWithText(
                "Demo does not create or show candidates, rejections or events. " +
                    "Stored monitoring activity is not shown in Demo.",
            ).performScrollTo()
            .assertIsDisplayed()

        compose.runOnIdle { surface.value = DemoSurface.History }
        compose.waitForIdle()
        compose
            .onNodeWithText("Recorded history is hidden while Demo is active")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Actual performance is hidden while Demo is active.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("History hidden in Demo").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun syntheticWalletRemainsExplicitAtMaximumScaleInNarrowRtlLandscape() {
        val mint = "DemoMintLocalOnly111111111111111111111111"
        val signature = "demo-signature-local-only"
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(240.dp)) {
                        WalletScreen(
                            state = demoWalletState(mint, signature),
                            onCreateWallet = {},
                            onRestoreWallet = {},
                            onReceive = {},
                            onSend = {},
                            onTrustedAddresses = {},
                            onReveal = {},
                            onLock = {},
                            onRefreshBalance = {},
                        )
                    }
                }
            }
        }

        // CPD-ON
        compose.onNodeWithText("Synthetic wallet preview").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("0.025 SOL", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic token holdings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic slot 250000000").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Fixed Demo preview; no provider refresh or chain observation was performed.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(mint).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Formatted 12.5", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic wallet activity").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SYNTHETIC").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription(signature)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic slot 249999950").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun keyboardToggleKeepsFocusAndDisabledActionsExplainDemoBlocker() {
        val demoMode = mutableStateOf(false)
        compose.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            StartExTheme {
                SettingsScreen(
                    state = PersistedAppState(loaded = true, demoMode = demoMode.value),
                    onProviders = {},
                    onPreflight = {},
                    onStop = {},
                    onSetDemoMode = { demoMode.value = it },
                    onRequestSecurityMode = { _, _, _ -> },
                    onStrategyAndRisk = {},
                    onBatteryOptimizationSettings = {},
                )
            }
        }

        val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val demoToggle =
            compose
                .onNode(hasText("Deterministic Demo") and switchRole)
                .requestFocus()
                .assertIsFocused()
        demoToggle.performKeyInput { pressKey(Key.Enter) }
        demoToggle.assertIsFocused().assertIsOn()

        val reason =
            "This action is unavailable in Demo. Disable Demo in Settings to use real wallet or provider operations."
        compose
            .onNode(hasText("Strategy and risk") and hasStateDescription(reason))
            .performScrollTo()
            .assertIsNotEnabled()
        assertTrue(demoToggle.fetchSemanticsNode().touchBoundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
    }

    private enum class DemoSurface { Home, Watch, History }

    @androidx.compose.runtime.Composable
    private fun DemoHome() {
        HomeScreen(
            state = PersistedAppState(loaded = true, demoMode = true, walletBalanceLamports = 25_000_000).homeScreenState(),
            onPreflight = {},
            onRecover = {},
            onPause = {},
            onResume = {},
            onStop = {},
            onSellNow = {},
            onEmergencyExit = {},
            onStopAfterClose = {},
        )
    }

    private fun demoWalletState(
        mint: String,
        signature: String,
    ) = PersistedAppState(
        loaded = true,
        demoMode = true,
        walletBalanceLamports = 25_000_000,
        walletBalanceEur = BigDecimal("3.25"),
        walletBalanceSlot = 250_000_000,
        tokenHoldings =
            listOf(
                WalletTokenHolding(
                    mint = mint,
                    tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                    amountAtomic = BigInteger("12500000"),
                    decimals = 6,
                ),
            ),
        tokenHoldingsSlot = 250_000_000,
        recentWalletActivity =
            listOf(
                WalletActivity(
                    signature = signature,
                    slot = 249_999_950,
                    blockTimeMillis = 1_725_000_000_000,
                    failed = false,
                ),
            ),
        walletDataUpdatedAtMillis = 1_725_000_000_000,
    )
}
