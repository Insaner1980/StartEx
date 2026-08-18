package com.finnvek.startex.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StartExTopLevelRouteTest {
    @Test
    fun loadingWinsOverEveryOtherRoute() {
        assertEquals(
            TopLevelRoute.Loading,
            topLevelRoute(
                state = lockedState().copy(loaded = false),
                walletSetup = WalletSetupState.RestoreInput(),
                walletOverlay = WalletOverlay.Receive,
                panel = FullScreenPanel.Providers,
            ),
        )
    }

    @Test
    fun walletSetupWinsOverLockAndStaleOverlay() {
        assertEquals(
            TopLevelRoute.WalletSetup,
            topLevelRoute(
                state = lockedState(),
                walletSetup = WalletSetupState.RestoreInput(),
                walletOverlay = WalletOverlay.Receive,
                panel = FullScreenPanel.Configuration,
            ),
        )
    }

    @Test
    fun secureSessionLockWinsOverStaleOverlayPanelsAndOnboarding() {
        FullScreenPanel.entries.forEach { panel ->
            assertEquals(
                TopLevelRoute.SecureSessionLock,
                topLevelRoute(
                    state = lockedState(onboardingComplete = false),
                    walletSetup = WalletSetupState.Closed,
                    walletOverlay = WalletOverlay.Receive,
                    panel = panel,
                ),
            )
            assertEquals(
                TopLevelRoute.SecureSessionLock,
                topLevelRoute(
                    state = lockedState(onboardingComplete = false),
                    walletSetup = WalletSetupState.Closed,
                    walletOverlay = WalletOverlay.None,
                    panel = panel,
                ),
            )
        }
    }

    @Test
    fun unlockedRoutesKeepTheirExistingOrder() {
        val onboarding = unlockedState(onboardingComplete = false)
        assertEquals(
            TopLevelRoute.WalletOverlay,
            topLevelRoute(
                state = onboarding,
                walletSetup = WalletSetupState.Closed,
                walletOverlay = WalletOverlay.Receive,
                panel = FullScreenPanel.Providers,
            ),
        )
        assertEquals(
            TopLevelRoute.Providers,
            topLevelRoute(
                state = onboarding,
                walletSetup = WalletSetupState.Closed,
                walletOverlay = WalletOverlay.None,
                panel = FullScreenPanel.Providers,
            ),
        )
        assertEquals(
            TopLevelRoute.Configuration,
            topLevelRoute(
                state = onboarding,
                walletSetup = WalletSetupState.Closed,
                walletOverlay = WalletOverlay.None,
                panel = FullScreenPanel.Configuration,
            ),
        )
        assertEquals(
            TopLevelRoute.Onboarding,
            topLevelRoute(
                state = onboarding,
                walletSetup = WalletSetupState.Closed,
                walletOverlay = WalletOverlay.None,
                panel = FullScreenPanel.Preflight,
            ),
        )
        assertEquals(
            TopLevelRoute.Preflight,
            topLevelRoute(
                state = unlockedState(),
                walletSetup = WalletSetupState.Closed,
                walletOverlay = WalletOverlay.None,
                panel = FullScreenPanel.Preflight,
            ),
        )
        assertEquals(
            TopLevelRoute.Main,
            topLevelRoute(
                state = unlockedState(),
                walletSetup = WalletSetupState.Closed,
                walletOverlay = WalletOverlay.None,
                panel = FullScreenPanel.None,
            ),
        )
    }

    private fun lockedState(onboardingComplete: Boolean = true) =
        PersistedAppState(
            loaded = true,
            onboardingComplete = onboardingComplete,
            walletAddress = "11111111111111111111111111111111",
            walletUnlocked = false,
        )

    private fun unlockedState(onboardingComplete: Boolean = true) = lockedState(onboardingComplete).copy(walletUnlocked = true)
}
