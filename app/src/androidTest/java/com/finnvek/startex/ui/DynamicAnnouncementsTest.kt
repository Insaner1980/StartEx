package com.finnvek.startex.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.finnvek.startex.R
import com.finnvek.startex.data.local.AppEventEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.ui.screens.HomeScreen
import com.finnvek.startex.ui.screens.ProviderSetupScreen
import com.finnvek.startex.ui.screens.WalletOverlayScreen
import com.finnvek.startex.ui.screens.WatchScreen
import com.finnvek.startex.ui.theme.StartExTheme
import com.finnvek.startex.wallet.ManualTransferStatus
import org.junit.Rule
import org.junit.Test

class DynamicAnnouncementsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun monitoringAndRecoveryChangesUsePoliteLiveRegions() {
        var state by mutableStateOf(PersistedAppState(loaded = true))
        compose.setContent {
            StartExTheme {
                HomeScreen(
                    state = state,
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

        compose.assertPoliteContainerWithText("NOT RUNNING")
        compose.runOnIdle { state = state.copy(monitorState = MonitorState.Running) }
        compose.assertPoliteContainerWithText("RUNNING")
        compose.runOnIdle { state = state.copy(monitorState = MonitorState.NeedsAttention) }
        compose.assertPoliteContainerWithText("Recovery required")
    }

    @Test
    fun providerFailureUsesAPoliteLiveRegion() {
        val health =
            ProviderHealthEntity(
                provider = ProviderId.HELIUS.name,
                state = "UNAVAILABLE",
                consecutiveFailures = 5,
                lastSuccessAtMillis = 1,
                lastFailureAtMillis = 2,
                latencyMillis = 10,
                retryAfterMillis = null,
                lastFailureCode = "NETWORK_UNAVAILABLE",
                updatedAtMillis = 2,
            )
        compose.setContent {
            StartExTheme {
                ProviderSetupScreen(
                    configuredProviders = setOf(ProviderId.HELIUS),
                    activatedProviders = setOf(ProviderId.HELIUS),
                    providerHealth = listOf(health),
                    onSave = { _, _ -> },
                    onRemove = {},
                    onTest = {},
                    onBack = {},
                )
            }
        }

        compose.assertPoliteContainerWithText("Provider unavailable")
    }

    @Test
    fun transferSubmissionUncertaintyAndFinalizationUsePoliteLiveRegions() {
        var transferState by mutableStateOf<WalletTransferState>(WalletTransferState.Submitting)
        compose.setContent {
            StartExTheme {
                WalletOverlayScreen(
                    overlay = WalletOverlay.Send,
                    state = PersistedAppState(loaded = true),
                    transferState = transferState,
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

        compose.assertPoliteText("Signing locally and submitting once…")
        compose.runOnIdle {
            transferState =
                WalletTransferState.Submitted(
                    signature = "signature",
                    status = ManualTransferStatus.SUBMITTED,
                )
        }
        compose.assertPoliteContainerWithText("Submitted to Solana RPC")
        compose.onNodeWithText("Open in Solscan").assertIsDisplayed()
        compose.runOnIdle {
            transferState =
                WalletTransferState.Uncertain(
                    localSignature = "signature",
                    message = R.string.transfer_uncertain_body,
                )
        }
        compose.assertPoliteContainerWithText("Submission needs attention")
        compose.runOnIdle {
            transferState =
                WalletTransferState.Submitted(
                    signature = "signature",
                    status = ManualTransferStatus.FINALIZED,
                )
        }
        compose.assertPoliteContainerWithText("Transfer finalized")
    }

    @Test
    fun noisyMarketDataIsNotALiveRegion() {
        compose.setContent {
            StartExTheme {
                WatchScreen(
                    candidates = emptyList(),
                    events =
                        listOf(
                            AppEventEntity(
                                severity = "INFO",
                                category = "DISCOVERY",
                                code = "CANDIDATE_DISCOVERED",
                                redactedMessage = null,
                                relatedId = "mint",
                                createdAtMillis = 1,
                            ),
                        ),
                )
            }
        }

        compose.onAllNodes(politeLiveRegion()).assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.assertPoliteText(text: String) {
        onNode(hasText(text) and politeLiveRegion()).assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.assertPoliteContainerWithText(text: String) {
        onNode(
            politeLiveRegion() and hasAnyDescendant(hasText(text)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun politeLiveRegion(): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
}
