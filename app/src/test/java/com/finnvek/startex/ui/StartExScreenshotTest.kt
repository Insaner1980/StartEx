package com.finnvek.startex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.finnvek.startex.ui.screens.HomeScreen
import com.finnvek.startex.ui.theme.StartExTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
    }
}
