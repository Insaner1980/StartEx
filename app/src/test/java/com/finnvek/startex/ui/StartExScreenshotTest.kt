package com.finnvek.startex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.ui.screens.HomeScreen
import com.finnvek.startex.ui.screens.WalletScreen
import com.finnvek.startex.ui.theme.StartExTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class StartExScreenshotTest {
    @Test
    fun emptyHomeIsVisuallyStable() {
        captureRoboImage(filePath = "build/outputs/roborazzi/home-empty.png") {
            StartExTheme {
                Box(
                    modifier =
                        Modifier
                            .size(width = 412.dp, height = 915.dp)
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    HomeScreen(
                        state =
                            PersistedAppState(
                                loaded = true,
                                onboardingComplete = true,
                                walletAddress = null,
                            ),
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
            }
        }
        assertArrayEquals(
            File("src/test/screenshots/home-empty.png").readBytes(),
            File("build/outputs/roborazzi/home-empty.png").readBytes(),
        )
    }

    @Test
    fun rtlWalletTechnicalContentIsVisuallyStable() {
        captureRoboImage(filePath = "build/outputs/roborazzi/wallet-rtl.png") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StartExTheme {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 412.dp, height = 915.dp)
                                .background(MaterialTheme.colorScheme.background),
                    ) {
                        // Screenshot setup intentionally spells out the complete public screen contract.
                        // CPD-OFF
                        WalletScreen(
                            state =
                                PersistedAppState(
                                    loaded = true,
                                    onboardingComplete = true,
                                    walletAddress = "9xQeWvG816bUx9EPfEZCDEYvZXsKJe6PyKwNW5wZnWGD",
                                    walletUnlocked = true,
                                    walletBalanceLamports = 1_234_567_890,
                                    walletBalanceEur = BigDecimal("1234.56"),
                                ),
                            onCreateWallet = {},
                            onRestoreWallet = {},
                            onReceive = {},
                            onSend = {},
                            onTrustedAddresses = {},
                            onReveal = {},
                            onLock = {},
                            onRefreshBalance = {},
                        )
                        // CPD-ON
                    }
                }
            }
        }
        assertArrayEquals(
            File("src/test/screenshots/wallet-rtl.png").readBytes(),
            File("build/outputs/roborazzi/wallet-rtl.png").readBytes(),
        )
    }
}
