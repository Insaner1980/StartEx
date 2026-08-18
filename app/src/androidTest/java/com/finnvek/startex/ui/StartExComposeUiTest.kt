package com.finnvek.startex.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.data.local.TrustedAddressEntity
import com.finnvek.startex.ui.screens.ConfigurationEditorScreen
import com.finnvek.startex.ui.screens.HistoryScreen
import com.finnvek.startex.ui.screens.OnboardingScreen
import com.finnvek.startex.ui.screens.PreflightScreen
import com.finnvek.startex.ui.screens.WalletFlowScreen
import com.finnvek.startex.ui.screens.WalletOverlayScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StartExComposeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingContainsExactlyNineAcknowledgedSteps() {
        var completed = false
        compose.setContent {
            StartExTheme {
                OnboardingScreen(
                    state = PersistedAppState(loaded = true),
                    onCreateWallet = {},
                    onRestoreWallet = {},
                    onConfigureProviders = {},
                    onTrustedAddresses = {},
                    onRequestSecurityMode = { _, _, _ -> },
                    onComplete = { completed = true },
                )
            }
        }

        compose.onNodeWithTag("onboarding_progress").assertTextContains("Step 1 of 9")
        compose.onNodeWithTag("onboarding_next").assertIsNotEnabled()
        repeat(8) {
            compose.onNodeWithTag("onboarding_acknowledge").performClick()
            compose.onNodeWithTag("onboarding_next").assertIsEnabled().performClick()
        }
        compose.onNodeWithTag("onboarding_progress").assertTextContains("Step 9 of 9")
        compose.onNodeWithTag("onboarding_acknowledge").performClick()
        compose.onNodeWithTag("onboarding_next").performClick()
        assertTrue(completed)
    }

    @Test
    fun preflightBlocksStartWhenRequirementsAreMissing() {
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

        compose.onNodeWithText("Start Paper monitoring").assertIsNotEnabled()
        compose.onNodeWithText("Setup is incomplete. Monitoring remains stopped.").assertExists()
    }

    @Test
    fun restoredWalletRequiresDedicatedAndOfflineBackupConfirmations() {
        compose.setContent {
            StartExTheme {
                WalletFlowScreen(
                    state =
                        WalletSetupState.ReviewWallet(
                            publicAddress = "11111111111111111111111111111111",
                            restored = true,
                        ),
                    onMnemonicSave = {},
                    onVerifyBackup = { _ -> },
                    onRestore = { _ -> },
                    onSave = { _ -> },
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText("Authenticate and save").assertIsNotEnabled()
        compose
            .onNodeWithText("This is a dedicated low-balance wallet and I understand funds can be lost.")
            .performClick()
        compose.onNodeWithText("Authenticate and save").assertIsNotEnabled()
        compose
            .onNodeWithText("I have an offline copy of this recovery phrase. StartEx cannot recover it.")
            .performClick()
        compose.onNodeWithText("Authenticate and save").assertIsEnabled()
    }

    @Test
    fun sendReviewNeverClaimsExecutionWhenExecutorIsUnavailable() {
        val trusted =
            TrustedAddressEntity(
                id = 7,
                walletProfileId = 1,
                label = "Cold wallet",
                address = "11111111111111111111111111111111",
                accountKind = "SOLANA",
                createdAtMillis = 1,
                lastVerifiedAtMillis = 1,
                isLocked = true,
            )
        compose.setContent {
            StartExTheme {
                WalletOverlayScreen(
                    overlay = WalletOverlay.Send,
                    state = PersistedAppState(loaded = true, trustedAddresses = listOf(trusted)),
                    transferState = WalletTransferState.Editing,
                    trustedAddresses = listOf(trusted),
                    onDismiss = {},
                    onRefreshBalance = {},
                    onPrepareTransfer = { _, _ -> },
                    onSubmitTransfer = {},
                    onResetTransfer = {},
                    onAddTrustedAddress = { _, _, _ -> },
                    onDeleteTrustedAddress = { _, _ -> },
                    onUnlockTrustedAddress = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Review transaction").assertIsNotEnabled()
        compose.onNodeWithText("Cold wallet").assertExists()
    }

    // CPD-OFF
    @Test
    fun receiveScreenIdentifiesMainnetForTheSharedAddress() {
        val address = "11111111111111111111111111111111"
        compose.setContent {
            StartExTheme {
                WalletOverlayScreen(
                    overlay = WalletOverlay.Receive,
                    state = PersistedAppState(loaded = true, walletAddress = address),
                    transferState = WalletTransferState.Editing,
                    trustedAddresses = emptyList(),
                    onDismiss = {},
                    onRefreshBalance = {},
                    onPrepareTransfer = { _, _ -> },
                    onSubmitTransfer = {},
                    onResetTransfer = {},
                    onAddTrustedAddress = { _, _, _ -> },
                    onDeleteTrustedAddress = { _, _ -> },
                    onUnlockTrustedAddress = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Solana mainnet").assertExists()
        compose.onNodeWithText(address).assertExists()
    }

    // CPD-ON
    @Test
    fun historyShowsPersistedTradeFieldsInsteadOfGenericEvents() {
        val trade =
            TradeExportRow(
                intentId = "trade-1",
                side = "BUY",
                mint = "Mint111111111111111111111111111111111111",
                mode = "PAPER",
                symbol = "ABC",
                exitReason = null,
                openedAtMillis = null,
                closedAtMillis = null,
                highestExecutableSellLamports = null,
                lowestExecutableSellLamports = null,
                strategyVersion = 2,
                entryScore = 81,
                decisionFactorsJson = null,
                rejectionCodesJson = null,
                transactionSignature = null,
                transactionStatus = "PAPER_FILLED",
                requestedInputAtomic = "1",
                expectedOutputAtomic = "1",
                actualInputAtomic = "1",
                actualOutputAtomic = "1",
                totalFeeLamports = 0,
                createdAtMillis = 1_786_276_800_000,
                confirmedAtMillis = null,
            )
        compose.setContent {
            StartExTheme {
                HistoryScreen(
                    state = PersistedAppState(loaded = true, tradeHistory = listOf(trade)),
                    onExportCsv = {},
                    onExportJson = {},
                )
            }
        }

        compose.onNodeWithText("ABC").assertExists()
        compose.onNodeWithText("BUY · PAPER_FILLED").assertExists()
        compose.onNodeWithText("Entry score / strategy").assertExists()
    }

    @Test
    fun configurationEditorShowsCurrentExactFieldsAndSaveAction() {
        compose.setContent {
            StartExTheme {
                ConfigurationEditorScreen(
                    state =
                        PersistedAppState(
                            loaded = true,
                            risk = DefaultConfiguration.risk(1),
                            strategy = DefaultConfiguration.strategy(1),
                        ),
                    saveState = ConfigurationSaveState.Idle,
                    onSave = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Maximum trade").assertExists()
        compose.onNodeWithText("Save new versions").assertExists()
    }
}
