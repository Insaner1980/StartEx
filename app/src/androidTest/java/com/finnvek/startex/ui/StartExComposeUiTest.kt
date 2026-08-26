package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.data.local.TrustedAddressEntity
import com.finnvek.startex.domain.ConfigurationErrorCode
import com.finnvek.startex.domain.ConfigurationField
import com.finnvek.startex.domain.ConfigurationFieldError
import com.finnvek.startex.ui.components.AddressText
import com.finnvek.startex.ui.components.MetricRow
import com.finnvek.startex.ui.components.ScreenColumn
import com.finnvek.startex.ui.components.ScreenHeader
import com.finnvek.startex.ui.components.SectionHeading
import com.finnvek.startex.ui.components.StatusPill
import com.finnvek.startex.ui.screens.ConfigurationEditorScreen
import com.finnvek.startex.ui.screens.HistoryScreen
import com.finnvek.startex.ui.screens.OnboardingScreen
import com.finnvek.startex.ui.screens.PreflightScreen
import com.finnvek.startex.ui.screens.ProviderSetupScreen
import com.finnvek.startex.ui.screens.SecurityModeControl
import com.finnvek.startex.ui.screens.WalletFlowScreen
import com.finnvek.startex.ui.screens.WalletOverlayScreen
import com.finnvek.startex.ui.screens.WalletScreen
import com.finnvek.startex.ui.screens.WatchScreen
import com.finnvek.startex.ui.theme.StartExAmber
import com.finnvek.startex.ui.theme.StartExText
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

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
        compose.onNodeWithTag("onboarding_next").performScrollTo().assertIsNotEnabled()
        repeat(8) {
            compose
                .onNodeWithTag("onboarding_acknowledge")
                .performScrollTo()
                .assertIsToggleable()
                .assertIsOff()
                .assertTextContains("I understand")
                .performClick()
                .assertIsOn()
            compose
                .onNodeWithTag("onboarding_next")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
        }
        compose.onNodeWithTag("onboarding_progress").assertTextContains("Step 9 of 9")
        compose.onNodeWithTag("onboarding_acknowledge").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
        assertTrue(completed)
    }

    @Test
    fun onboardingRestoresThePageStartAndShowsTheCompleteWalletAddress() {
        val address = "9xQeWvG816bUx9EPfEZCDEYvZXsKJe6PyKwNW5wZnWGD"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(360.dp)) {
                        OnboardingScreen(
                            state = PersistedAppState(loaded = true, walletAddress = address),
                            onCreateWallet = {},
                            onRestoreWallet = {},
                            onConfigureProviders = {},
                            onTrustedAddresses = {},
                            onRequestSecurityMode = { _, _, _ -> },
                            onComplete = {},
                        )
                    }
                }
            }
        }

        compose.onNode(hasText("Purpose and risk") and isHeading()).assertIsFocused()
        compose.onNodeWithTag("onboarding_acknowledge").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()

        compose.onNode(hasText("Create or restore a wallet") and isHeading()).assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText(address).performScrollTo().assertIsDisplayed()
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
        compose.onAllNodes(hasClickAction())[1].performScrollTo().performClick()
        compose.onNodeWithText("Authenticate and save").assertIsNotEnabled()
        compose.onAllNodes(hasClickAction())[2].performScrollTo().performClick()
        compose.onNodeWithText("Authenticate and save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun unattendedConfirmationSurvivesRestorationAndKeepsWarningReachable() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    ScreenColumn {
                        SecurityModeControl(
                            state = PersistedAppState(loaded = true),
                            onRequestSecurityMode = { _, _, _ -> },
                        )
                    }
                }
            }
        }
        compose.onAllNodes(hasClickAction())[1].performScrollTo().performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Enable unattended restart?").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText(
                "The wallet stays encrypted at rest, but an app or device compromise can expose " +
                    "a hot wallet that no longer requires a fresh biometric after restart.",
            ).assertExists()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("Authenticate and enable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        assertTouchTargetsDoNotOverlap("Authenticate and enable", "Cancel")
    }

    @Test
    fun cancellingUnattendedConfirmationClearsAcknowledgements() {
        compose.setContent {
            StartExTheme {
                SecurityModeControl(
                    state =
                        PersistedAppState(
                            loaded = true,
                            walletAddress = "11111111111111111111111111111111",
                            risk = DefaultConfiguration.risk(createdAtMillis = 1),
                        ),
                    onRequestSecurityMode = { _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Unattended mode").performClick()
        compose.onNodeWithText("I use a dedicated low-balance wallet for StartEx.").performClick()
        compose
            .onNodeWithText(
                "I understand this is less secure than biometric-each-use protection and the " +
                    "configured caps limit risk but do not prevent loss.",
            ).performClick()
        compose.onNodeWithText("Authenticate and enable").assertIsEnabled()

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Unattended mode").performClick()

        compose.onNodeWithText("Authenticate and enable").assertIsNotEnabled()
    }

    @Test
    fun trustedAddressDialogActionsDoNotOverlapAtDoubleFontScale() {
        val trusted = trustedAddress(isLocked = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                WalletOverlayTestContent(
                    overlay = WalletOverlay.TrustedAddresses,
                    trustedAddresses = listOf(trusted),
                )
            }
        }

        compose.onNodeWithContentDescription("Delete trusted address").performScrollTo().performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("Authenticate and continue").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertTouchTargetsDoNotOverlap("Authenticate and continue", "Cancel")
    }

    @Test
    fun lockedTrustedAddressDialogFocusesItsConfirmationInput() {
        val trusted = trustedAddress()
        compose.setContent {
            WalletOverlayTestContent(
                overlay = WalletOverlay.TrustedAddresses,
                trustedAddresses = listOf(trusted),
            )
        }

        compose.onNodeWithContentDescription("Unlock trusted address").performScrollTo().performClick()

        compose
            .onNode(
                hasText("Confirm final 4 address characters") and
                    hasSetTextAction() and
                    hasImeAction(ImeAction.Done),
            ).assertIsFocused()
    }

    @Test
    fun trustedAddressDialogRestoresFocusToItsTrigger() {
        compose.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            WalletOverlayTestContent(
                overlay = WalletOverlay.TrustedAddresses,
                trustedAddresses = listOf(trustedAddress()),
            )
        }
        val unlock =
            compose
                .onNodeWithContentDescription("Unlock trusted address")
                .performScrollTo()
                .requestFocus()
                .assertIsFocused()

        unlock.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Cancel").performClick()

        unlock.assertIsFocused()
    }

    @Test
    fun trustedAddressStatesAndExactAddressesRemainAvailableAtMaximumScaleInRtlLandscape() {
        val pendingAddress = "11111111111111111111111111111111"
        val verifiedAddress = "Vote111111111111111111111111111111111111111"
        // This scenario owns its constrained RTL composition.
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                Box(modifier = Modifier.width(320.dp).height(240.dp)) {
                    WalletOverlayTestContent(
                        overlay = WalletOverlay.TrustedAddresses,
                        trustedAddresses =
                            listOf(
                                trustedAddress(address = pendingAddress),
                                trustedAddress(
                                    id = 8,
                                    label = "Verified wallet",
                                    address = verifiedAddress,
                                    isLocked = false,
                                    firstTransferVerifiedAtMillis = 2,
                                ),
                            ),
                    )
                }
            }
        }

        // CPD-ON
        compose.onNodeWithText(pendingAddress).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Locked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("First transfer verification required").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(verifiedAddress).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unlocked").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Verified during an earlier authenticated transfer")
            .performScrollTo()
            .assertIsDisplayed()
        val unlockTarget =
            compose
                .onNodeWithContentDescription("Unlock trusted address")
                .performScrollTo()
                .fetchSemanticsNode()
                .touchBoundsInRoot
        val minimumTarget = with(compose.density) { 48.dp.toPx() }
        assertTrue("Unlock touch target is narrower than 48dp: $unlockTarget", unlockTarget.width >= minimumTarget)
        assertTrue("Unlock touch target is shorter than 48dp: $unlockTarget", unlockTarget.height >= minimumTarget)
    }

    @Test
    fun trustedAddressMismatchExplainsWhyConfirmationIsDisabled() {
        compose.setContent {
            WalletOverlayTestContent(
                overlay = WalletOverlay.TrustedAddresses,
                trustedAddresses = listOf(trustedAddress()),
            )
        }

        compose.onNodeWithContentDescription("Unlock trusted address").performScrollTo().performClick()
        compose
            .onNode(hasText("Confirm final 4 address characters") and hasSetTextAction())
            .performTextInput("abcd")

        compose
            .onNodeWithText("The final characters do not match.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Authenticate and continue").assertIsNotEnabled()
    }

    @Test
    fun invalidTrustedAddressRetainsInputAndFocusesTheAddressField() {
        var addRequested = false
        compose.setContent {
            WalletOverlayTestContent(
                overlay = WalletOverlay.TrustedAddresses,
                onAddTrustedAddress = { _, _, _ -> addRequested = true },
            )
        }
        compose
            .onNode(hasText("Label") and hasSetTextAction())
            .performTextInput("Cold wallet")
        val addressField = compose.onNode(hasText("Solana address") and hasSetTextAction())
        addressField.performTextInput("not-a-solana-address")

        compose.onNodeWithText("Authenticate and save").performScrollTo().performClick()

        addressField.assertIsFocused().assertTextContains("not-a-solana-address")
        compose.onNodeWithText("Enter a canonical Solana address.").assertExists()
        assertFalse(addRequested)
    }

    @Test
    fun trustedAddressSaveExplainsItsInitialDisabledState() {
        compose.setContent {
            WalletOverlayTestContent(overlay = WalletOverlay.TrustedAddresses)
        }

        compose
            .onNodeWithText("Enter a label and Solana address to enable saving.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Authenticate and save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun trustedAddressLockDefaultsOnEachNewEntry() {
        val requestedLockStates = mutableListOf<Boolean>()
        compose.setContent {
            WalletOverlayTestContent(
                overlay = WalletOverlay.TrustedAddresses,
                onAddTrustedAddress = { _, _, locked -> requestedLockStates += locked },
            )
        }
        val lockToggle =
            compose
                .onNodeWithText("Lock this trusted address")
                .performScrollTo()
                .assertIsToggleable()
                .assertIsOn()

        lockToggle.performClick().assertIsOff()
        compose
            .onNode(hasText("Label") and hasSetTextAction())
            .performTextInput("Unlocked wallet")
        compose
            .onNode(hasText("Solana address") and hasSetTextAction())
            .performTextInput("11111111111111111111111111111111")
        compose.onNodeWithText("Authenticate and save").performScrollTo().performClick()

        assertEquals(listOf(false), requestedLockStates)
        lockToggle.assertIsOn()
    }

    @Test
    fun sendReviewNeverClaimsExecutionWhenExecutorIsUnavailable() {
        val trusted = trustedAddress()
        compose.setContent {
            WalletOverlayTestContent(
                overlay = WalletOverlay.Send,
                state = PersistedAppState(loaded = true, trustedAddresses = listOf(trusted)),
                trustedAddresses = listOf(trusted),
            )
        }

        compose
            .onNodeWithText("Review transaction")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose
            .onNodeWithText("Cold wallet")
            .assertIsSelectable()
            .performClick()
            .assertIsSelected()
    }

    @Test
    fun solAmountDoneImePreparesTheSelectedAddress() {
        val trusted = trustedAddress()
        var prepared: Pair<Long, String>? = null
        compose.setContent {
            WalletOverlayTestContent(
                overlay = WalletOverlay.Send,
                state = PersistedAppState(loaded = true, trustedAddresses = listOf(trusted)),
                trustedAddresses = listOf(trusted),
                onPrepareTransfer = { id, amount -> prepared = id to amount },
            )
        }

        compose.onNodeWithText("Cold wallet").performClick()
        val amountField =
            compose.onNode(hasText("Amount (SOL)") and hasSetTextAction() and hasImeAction(ImeAction.Done))
        amountField.requestFocus()
        amountField.performTextInput("0,01")
        amountField.performImeAction()

        assertEquals(7L to "0,01", prepared)
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

        compose.onNodeWithContentDescription("QR code containing your Solana wallet address").assertIsDisplayed()
        compose.onNodeWithText("Solana mainnet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Copy address")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Address copied.").assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun receiveScreenAdaptsToNarrowLandscapeRtlAtMaximumFontScale() {
        val address = "11111111111111111111111111111111"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(240.dp).height(320.dp)) {
                        WalletOverlayScreen(
                            overlay = WalletOverlay.Receive,
                            state =
                                PersistedAppState(
                                    loaded = true,
                                    walletAddress = address,
                                    walletBalanceLoading = true,
                                ),
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
            }
        }

        val qr = compose.onNodeWithContentDescription("QR code containing your Solana wallet address")
        qr.assertIsDisplayed()
        val bounds = qr.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= 0.dp)
        assertTrue(bounds.right <= 240.dp)
        compose.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading balance…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Copy address").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun receiveScreenKeepsTheAddressAvailableWhenQrGenerationFails() {
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
                    qrBitmapFactory = { null },
                )
            }
        }

        compose.onNodeWithText("QR code unavailable").assertIsDisplayed()
        compose.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy address").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Share").performScrollTo().assertIsEnabled()
    }

    @Test
    fun receiveScreenRejectsAnInvalidLargeAddress() {
        compose.setContent {
            StartExTheme {
                WalletOverlayScreen(
                    overlay = WalletOverlay.Receive,
                    state = PersistedAppState(loaded = true, walletAddress = "x".repeat(100_000)),
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

        compose
            .onNodeWithText("Receiving is unavailable because the wallet address is missing or invalid.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Copy address").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Share").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun sendReviewInformationAndActionsRemainReachable() {
        val address = "11111111111111111111111111111111"
        compose.setContent {
            StartExTheme {
                WalletOverlayScreen(
                    overlay = WalletOverlay.Send,
                    state = PersistedAppState(loaded = true),
                    transferState =
                        WalletTransferState.Review(
                            trustedAddressId = 7,
                            destinationLabel = "Cold wallet",
                            destinationAddress = address,
                            amountLamports = 10_000_000,
                            estimatedFeeLamports = 5_000,
                            reserveLamports = 5_000_000,
                            lastValidBlockHeight = 123,
                            requiresAddressVerification = true,
                        ),
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

        compose.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Authenticate, sign and send").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Edit transfer").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("BUY, PAPER_FILLED").assertExists()
        compose.onNodeWithText("Entry score / strategy").assertExists()
    }

    @Test
    fun openCandidateDialogTracksTheLatestCandidateState() {
        val candidate =
            TokenCandidateEntity(
                mint = "Mint111111111111111111111111111111111111",
                source = "PUMP_PORTAL_NEW_TOKEN",
                discoverySignature = "signature",
                creatorAddress = null,
                name = "Candidate",
                symbol = "CND",
                metadataUri = null,
                tokenProgram = null,
                state = "ELIGIBLE",
                score = 80,
                rejectionCode = null,
                discoveredAtMillis = 1,
                lastUpdatedAtMillis = 1,
            )
        val candidates = mutableStateOf(listOf(candidate))
        compose.setContent {
            StartExTheme {
                WatchScreen(candidates = candidates.value, events = emptyList())
            }
        }

        compose.onNodeWithContentDescription("View candidate details").performClick()
        compose.onNodeWithText("ELIGIBLE").assertExists()

        compose.runOnIdle {
            candidates.value =
                listOf(
                    candidate.copy(
                        state = "REJECTED",
                        rejectionCode = "SCORE_BELOW_MINIMUM",
                        lastUpdatedAtMillis = 2,
                    ),
                )
        }

        compose.onNodeWithText("REJECTED").assertExists()
        compose.onNodeWithText("Rejected: SCORE BELOW MINIMUM").assertExists()
    }

    @Test
    fun configurationEditorShowsCurrentExactFieldsAndSaveAction() {
        compose.setContent {
            ConfigurationEditorTestContent()
        }

        compose.onNodeWithText("Maximum trade").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save new versions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun configurationDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ConfigurationEditorTestContent()
        }
        val maximumTrade = compose.onNode(hasText("Maximum trade") and hasSetTextAction())
        maximumTrade.performTextReplacement("0.02")

        restoration.emulateSavedInstanceStateRestore()

        maximumTrade.assertTextContains("0.02")
    }

    @Test
    fun configurationRelationshipErrorIsAssociatedWithTheFocusedField() {
        val message = "Maximum trade: conflicts with another limit. Check Maximum total exposure too."
        compose.setContent {
            StartExTheme {
                ConfigurationEditorScreen(
                    state =
                        PersistedAppState(
                            loaded = true,
                            risk = DefaultConfiguration.risk(1),
                            strategy = DefaultConfiguration.strategy(1),
                        ),
                    saveState =
                        ConfigurationSaveState.Invalid(
                            ConfigurationFieldError(
                                field = ConfigurationField.MAXIMUM_TRADE_SOL,
                                code = ConfigurationErrorCode.INCONSISTENT,
                                relatedField = ConfigurationField.MAXIMUM_EXPOSURE_SOL,
                            ),
                        ),
                    onSave = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose
            .onNode(hasText("Maximum trade") and hasSetTextAction() and hasImeAction(ImeAction.Next))
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))
        compose.onNodeWithText(message).assertIsDisplayed()
        compose
            .onNode(hasText("Minimum observation") and hasSetTextAction() and hasImeAction(ImeAction.Done))
            .assertExists()
    }

    @Test
    fun configurationEditorRemainsScrollableAndTouchableAtMaximumScaleInNarrowRtlLandscape() {
        // This scenario owns its constrained RTL composition.
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                Box(modifier = Modifier.width(320.dp).height(240.dp)) {
                    ConfigurationEditorTestContent()
                }
            }
        }

        // CPD-ON
        compose
            .onNode(hasText("Maximum trade") and hasSetTextAction() and hasImeAction(ImeAction.Next))
            .performScrollTo()
            .assertIsDisplayed()
        val save = compose.onNodeWithText("Save new versions").performScrollTo().assertIsDisplayed()
        val back = compose.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
        val minimumTarget = with(compose.density) { 48.dp.toPx() }
        assertTrue(save.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTarget)
        assertTrue(back.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTarget)
    }

    @Test
    fun mnemonicChallengeUsesPasswordInputsWithExplicitImeActions() {
        compose.setContent {
            StartExTheme {
                WalletFlowScreen(
                    state = WalletSetupState.BackupChallenge(listOf(3, 7, 12)),
                    onMnemonicSave = {},
                    onVerifyBackup = { _ -> },
                    onRestore = { _ -> },
                    onSave = { _ -> },
                    onCancel = {},
                )
            }
        }

        compose
            .onNode(
                hasText("Word 3") and
                    hasSetTextAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Password) and
                    hasImeAction(ImeAction.Next),
            ).assertExists()
        compose
            .onNode(
                hasText("Word 12") and
                    hasSetTextAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Password) and
                    hasImeAction(ImeAction.Done),
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Verify backup").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun restorePhraseIsClearedAndMaskedAfterStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StartExTheme {
                WalletFlowScreen(
                    state = WalletSetupState.RestoreInput(),
                    onMnemonicSave = {},
                    onVerifyBackup = { _ -> },
                    onRestore = { _ -> },
                    onSave = { _ -> },
                    onCancel = {},
                )
            }
        }
        compose
            .onNode(hasText("Recovery phrase") and hasSetTextAction())
            .performTextInput(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            )
        compose.onNodeWithContentDescription("Show recovery phrase").performClick()

        restoration.emulateSavedInstanceStateRestore()

        val phraseField =
            compose
                .onNode(
                    hasText("Recovery phrase") and
                        hasSetTextAction() and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
                ).assertExists()
        assertTrue(
            phraseField
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .isEmpty(),
        )
    }

    @Test
    fun providerKeysArePasswordInputsWithDoneImeActions() {
        compose.setContent {
            StartExTheme {
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

        val secureKeyFields =
            compose
                .onAllNodes(
                    hasText("API key", substring = true) and
                        hasSetTextAction() and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Password) and
                        hasImeAction(ImeAction.Done),
                ).fetchSemanticsNodes()
        assertEquals(3, secureKeyFields.size)
    }

    @Test
    fun criticalStatusWrapsWithoutVisualOverflowAtDoubleFontScale() {
        val status = "DETERMINISTIC SYNTHETIC DATA"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    Box(modifier = Modifier.width(356.dp)) {
                        StatusPill(
                            text = status,
                            color = StartExAmber,
                        )
                    }
                }
            }
        }

        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(status).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(results)
        }

        assertFalse(results.single().hasVisualOverflow)
        compose.onNode(hasStateDescription(status)).assertDoesNotExist()
    }

    @Test
    fun sharedScreenAndSectionTitlesExposeHeadingSemantics() {
        compose.setContent {
            StartExTheme {
                ScreenColumn {
                    ScreenHeader("Wallet")
                    SectionHeading("Balance")
                }
            }
        }

        compose.onNode(hasText("Wallet") and isHeading()).assertExists()
        compose.onNode(hasText("Balance") and isHeading()).assertExists()
    }

    @Test
    fun metricRowKeepsRtlLabelAndValueOnOppositeSides() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        MetricRow(label = "Net PnL", value = "+0.25 SOL")
                    }
                }
            }
        }

        val label = compose.onNodeWithText("Net PnL").fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("+0.25 SOL").fetchSemanticsNode().boundsInRoot

        assertTrue("RTL metric value and label meet or overlap: $value and $label", value.right < label.left)
    }

    @Test
    fun walletBackHeaderUsesVisibleColorInRtl() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StartExTheme {
                    WalletFlowScreen(
                        state = WalletSetupState.ReviewWallet("11111111111111111111111111111111", restored = false),
                        onMnemonicSave = {},
                        onVerifyBackup = {},
                        onRestore = {},
                        onSave = {},
                        onCancel = {},
                    )
                }
            }
        }

        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Review wallet").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(results)
        }

        assertEquals(
            StartExText,
            results
                .single()
                .layoutInput.style.color,
        )
    }

    @Test
    fun fullTransactionSignatureIsNotTruncatedAtDoubleFontScale() {
        val signature = "5".repeat(88)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    Box(modifier = Modifier.width(344.dp)) {
                        AddressText(
                            address = signature,
                            abbreviated = false,
                        )
                    }
                }
            }
        }

        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(signature).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) {
            it(results)
        }

        assertFalse(results.single().hasVisualOverflow)
    }

    @Test
    fun abbreviatedAddressExposesFullValueToAccessibility() {
        val address = "12345678901234567890"
        compose.setContent {
            StartExTheme {
                AddressText(address)
            }
        }

        compose.onNodeWithContentDescription(address).assertExists()
    }

    @Test
    fun bottomNavigationLabelsRemainReadableAtMaximumScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    StartExNavigationBar(
                        destination = AppDestination.Home,
                        onDestination = {},
                    )
                }
            }
        }

        listOf("Home", "Watch", "Wallet", "History", "Settings").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(results)
            }
            val result = results.single()
            assertTrue(
                "Navigation label overflows: $label",
                (0 until result.lineCount).all { line ->
                    !result.isLineEllipsized(line) &&
                        result.getLineLeft(line) >= 0f &&
                        result.getLineRight(line) <= result.size.width
                },
            )
        }
    }

    @Test
    fun walletAmountsWarningsAndActionsRemainReachableAtMaximumScale() {
        val solBalance = "1.23456789 SOL"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    WalletScreen(
                        state =
                            PersistedAppState(
                                loaded = true,
                                onboardingComplete = true,
                                walletAddress = "11111111111111111111111111111111",
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
                }
            }
        }

        compose.onNodeWithText(solBalance).performScrollTo().assertIsDisplayed()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(solBalance).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(results)
        }
        val balanceLayout = results.single()
        assertFalse(
            "Wallet amount overflows: size=${balanceLayout.size}, " +
                "lines=${balanceLayout.lineCount}, width=${balanceLayout.didOverflowWidth}, " +
                "height=${balanceLayout.didOverflowHeight}",
            balanceLayout.hasVisualOverflow,
        )
        compose.onNodeWithText("Receive").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Send").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reveal recovery phrase").performScrollTo().assertIsDisplayed()
    }

    private fun trustedAddress(
        id: Long = 7,
        label: String = "Cold wallet",
        address: String = "11111111111111111111111111111111",
        isLocked: Boolean = true,
        firstTransferVerifiedAtMillis: Long? = null,
    ) = TrustedAddressEntity(
        id = id,
        walletProfileId = 1,
        label = label,
        address = address,
        accountKind = "SOLANA",
        createdAtMillis = 1,
        lastVerifiedAtMillis = 1,
        isLocked = isLocked,
        firstTransferVerifiedAtMillis = firstTransferVerifiedAtMillis,
    )

    @Composable
    private fun WalletOverlayTestContent(
        overlay: WalletOverlay,
        state: PersistedAppState = PersistedAppState(loaded = true),
        trustedAddresses: List<TrustedAddressEntity> = emptyList(),
        onPrepareTransfer: (Long, String) -> Unit = { _, _ -> },
        onAddTrustedAddress: (String, String, Boolean) -> Unit = { _, _, _ -> },
    ) {
        StartExTheme {
            WalletOverlayScreen(
                overlay = overlay,
                state = state,
                transferState = WalletTransferState.Editing,
                trustedAddresses = trustedAddresses,
                onDismiss = {},
                onRefreshBalance = {},
                onPrepareTransfer = onPrepareTransfer,
                onSubmitTransfer = {},
                onResetTransfer = {},
                onAddTrustedAddress = onAddTrustedAddress,
                onDeleteTrustedAddress = { _, _ -> },
                onUnlockTrustedAddress = { _, _ -> },
            )
        }
    }

    @Composable
    private fun ConfigurationEditorTestContent() {
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

    private fun assertTouchTargetsDoNotOverlap(
        firstText: String,
        secondText: String,
    ) {
        val first = compose.onNodeWithText(firstText).fetchSemanticsNode().touchBoundsInRoot
        val second = compose.onNodeWithText(secondText).fetchSemanticsNode().touchBoundsInRoot
        val overlapWidth = minOf(first.right, second.right) - maxOf(first.left, second.left)
        val overlapHeight = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        assertTrue("Touch targets overlap: $first and $second", overlapWidth <= 0f || overlapHeight <= 0f)
    }
}
