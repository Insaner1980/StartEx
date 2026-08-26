package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.data.local.AppEventEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.ui.screens.WatchScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WatchScreenAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun candidateContentRemainsCompleteAtMaximumFontScaleAndInRtl() {
        val candidate = candidate()
        // This scenario owns its constrained RTL composition.
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(480.dp)) {
                        WatchScreen(candidates = listOf(candidate), events = emptyList())
                    }
                }
            }
        }

        // CPD-ON
        compose.onNode(hasText("Candidates 1")).assertIsSelectable().assertIsSelected()
        compose.onNode(hasText("CND") and isHeading()).assertIsDisplayed()
        compose.onNode(hasContentDescription(candidate.mint)).assertIsDisplayed()

        val tabLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Candidates 1").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(tabLayouts)
        }
        assertFalse("Candidate tab text is visually truncated", tabLayouts.single().hasVisualOverflow)

        val details = compose.onNode(hasContentDescription("View details for CND"))
        val target = details.fetchSemanticsNode().touchBoundsInRoot
        val minimumTarget = with(compose.density) { 48.dp.toPx() }
        assertTrue("Details touch target is narrower than 48dp: $target", target.width >= minimumTarget)
        assertTrue("Details touch target is shorter than 48dp: $target", target.height >= minimumTarget)
    }

    @Test
    fun candidateDialogRestoresKeyboardFocusAndExposesExactEvidence() {
        val candidate = candidate()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    Box(modifier = Modifier.width(640.dp).height(360.dp)) {
                        WatchScreen(candidates = listOf(candidate), events = emptyList())
                    }
                }
            }
        }

        val details =
            compose
                .onNode(hasContentDescription("View details for CND"))
                .performScrollTo()
                .requestFocus()
                .assertIsFocused()
        details.performClick()

        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(candidate.mint).assertIsDisplayed()
        compose.onNodeWithText("ELIGIBLE").assertIsDisplayed()
        compose.onNodeWithText("PUMP_PORTAL_NEW_TOKEN").assertIsDisplayed()
        compose
            .onNodeWithText(
                "This is observed provider data, not a recommendation or proof that an exit route will remain available.",
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()

        details.assertIsFocused()
    }

    @Test
    fun tabsEmptyStatesAndEventSeverityHaveUnderstandableTextSemantics() {
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
                                relatedId = null,
                                createdAtMillis = 1,
                            ),
                        ),
                )
            }
        }

        compose.onNodeWithText("Rejected 0").performClick()
        compose.onNodeWithText("No rejected candidates").assertIsDisplayed()
        compose.onNodeWithText("Rejected or expired candidates appear here during a monitoring session.").assertIsDisplayed()

        compose.onNodeWithText("Events 1").performClick()
        compose.onNode(hasText("DISCOVERY") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Information").assertIsDisplayed()
    }

    // This explicit entity fixture keeps the accessibility scenario self-contained.
    // CPD-OFF
    private fun candidate() =
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
    // CPD-ON
}
