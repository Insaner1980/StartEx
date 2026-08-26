package com.finnvek.startex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import com.finnvek.startex.ui.screens.WalletFlowScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WalletSetupBackNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun androidBackCannotDismissWalletSaveInProgress() {
        var parentBackCalls = 0
        var cancelCalls = 0
        compose.setContent {
            StartExTheme {
                BackHandler { parentBackCalls++ }
                WalletFlowScreen(
                    state = WalletSetupState.Saving,
                    onMnemonicSave = {},
                    onVerifyBackup = {},
                    onRestore = {},
                    onSave = {},
                    onCancel = { cancelCalls++ },
                )
            }
        }

        pressBack()

        compose.runOnIdle {
            assertEquals(1, parentBackCalls)
            assertEquals(0, cancelCalls)
        }
    }
}
