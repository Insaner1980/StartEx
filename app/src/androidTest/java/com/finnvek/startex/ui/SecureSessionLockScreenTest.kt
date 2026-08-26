package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.ui.screens.LockScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SecureSessionLockScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lockedScreenDoesNotExposeTheWalletAddress() {
        val address = "9xQeWvG816bUx9EPfEZCDEYvZXsKJe6PyKwNW5wZnWGD"
        compose.setContent {
            StartExTheme {
                LockScreen(
                    recoveryRequired = true,
                    onUnlock = {},
                    onRestore = {},
                    onRecover = {},
                    onStop = {},
                )
            }
        }

        compose.onNodeWithText("StartEx is locked").assertIsDisplayed()
        compose.onNodeWithText("A previous Paper session needs attention.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Resume recovery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop monitoring").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(address).assertDoesNotExist()
        compose.onNodeWithText(address.take(7), substring = true).assertDoesNotExist()
    }

    @Test
    fun recoveryWarningIsAnnouncedWhenAttentionBecomesRequired() {
        var recoveryRequired by mutableStateOf(false)
        compose.setContent {
            StartExTheme {
                LockScreen(
                    recoveryRequired = recoveryRequired,
                    onUnlock = {},
                    onRestore = {},
                    onRecover = {},
                    onStop = {},
                )
            }
        }

        compose.onNodeWithText("A previous Paper session needs attention.", substring = true).assertDoesNotExist()
        compose.runOnIdle { recoveryRequired = true }
        compose
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun actionsRemainReachableAtMaximumScaleInNarrowRtlLandscape() {
        // This scenario owns its constrained RTL composition.
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(320.dp)
                            .height(240.dp),
                ) {
                    StartExTheme {
                        LockScreen(
                            recoveryRequired = true,
                            onUnlock = {},
                            onRestore = {},
                            onRecover = {},
                            onStop = {},
                        )
                    }
                }
            }
        }

        // CPD-ON
        val minimumTarget = with(compose.density) { 48.dp.toPx() }
        listOf("Unlock wallet", "Restore wallet instead", "Resume recovery", "Stop monitoring").forEach { label ->
            val action = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            assertTrue(action.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTarget)
        }
    }
}
