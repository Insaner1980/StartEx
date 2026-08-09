package com.finnvek.startex.trading

import com.finnvek.startex.domain.Lamports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

class PositionExitEngineTest {
    @Test
    fun `unrealized PnL uses the full executable sell quote and all known costs`() {
        val position =
            openPosition(
                input = sol("0.1"),
                entryCosts = TradeCosts(baseFee = sol("0.001")),
            )
        val quote =
            ExecutableSellQuote(
                grossOutput = sol("0.12"),
                estimatedCosts =
                    TradeCosts(
                        dexFee = sol("0.001"),
                        priorityFee = sol("0.001"),
                    ),
                observedAt = NOW,
                routeAvailable = true,
            )

        assertEquals(BigInteger.valueOf(17_000_000), PositionAccounting.unrealizedPnl(position, quote).value)
    }

    @Test
    fun `position cannot close after a failed sell submission`() {
        assertEquals(
            PositionState.EXIT_QUEUED,
            PositionStateMachine.transition(PositionState.OPEN, PositionState.EXIT_QUEUED),
        )
        assertEquals(
            PositionState.EXIT_SUBMITTED,
            PositionStateMachine.transition(PositionState.EXIT_QUEUED, PositionState.EXIT_SUBMITTED),
        )
        assertEquals(
            PositionState.EXIT_BLOCKED,
            PositionStateMachine.transition(PositionState.EXIT_SUBMITTED, PositionState.EXIT_BLOCKED),
        )
        assertThrows(IllegalStateException::class.java) {
            PositionStateMachine.transition(PositionState.EXIT_BLOCKED, PositionState.CLOSED)
        }
    }

    @Test
    fun `exit engine triggers a trailing stop from executable value`() {
        val position = openPosition(input = sol("0.1"), entryCosts = TradeCosts())
        val policy = exitPolicy()
        val observation =
            ExitObservation(
                now = NOW.plusSeconds(90),
                quote = quote("0.112"),
                highestExecutableValue = sol("0.12"),
                safetyScore = 80,
            )

        val decision = ExitEngine(policy).evaluate(position, observation)

        assertTrue(decision.exitRequested)
        assertEquals(setOf(ExitReason.TRAILING_STOP), decision.reasons)
    }

    @Test
    fun `exit engine keeps monitoring and marks exit blocked when no route exists`() {
        val position = openPosition(input = sol("0.1"), entryCosts = TradeCosts())
        val observation =
            ExitObservation(
                now = NOW.plusSeconds(30),
                quote = null,
                highestExecutableValue = sol("0.1"),
                safetyScore = 80,
            )

        val decision = ExitEngine(exitPolicy()).evaluate(position, observation)

        assertTrue(decision.exitRequested)
        assertTrue(decision.routeBlocked)
        assertEquals(setOf(ExitReason.ROUTE_UNAVAILABLE), decision.reasons)
    }

    @Test
    fun `maximum hold and manual emergency exit are evaluated independently`() {
        val position = openPosition(input = sol("0.1"), entryCosts = TradeCosts())
        val observation =
            ExitObservation(
                now = NOW.plus(Duration.ofMinutes(10)),
                quote = quote("0.1"),
                highestExecutableValue = sol("0.1"),
                safetyScore = 80,
                manualAction = ManualExitAction.EMERGENCY_EXIT,
            )

        val decision =
            ExitEngine(exitPolicy().copy(maximumHoldingTime = Duration.ofMinutes(10)))
                .evaluate(position, observation)

        assertEquals(setOf(ExitReason.EMERGENCY_EXIT, ExitReason.MAXIMUM_HOLD), decision.reasons)
    }

    @Test
    fun `exit retry increases delay slippage and fee only to configured caps`() {
        val policy =
            ExitRetryPolicy(
                maximumImmediateAttempts = 4,
                baseDelay = Duration.ofSeconds(1),
                maximumDelay = Duration.ofSeconds(3),
                slippageStepBps = 100,
                maximumSlippageBps = 500,
                priorityFeeStep = sol("0.0002"),
                maximumPriorityFee = sol("0.0005"),
            )

        val plan =
            policy.nextAttempt(
                completedAttempts = 3,
                currentSlippageBps = 450,
                currentPriorityFee = sol("0.0004"),
            )

        requireNotNull(plan)
        assertEquals(Duration.ofSeconds(3), plan.delay)
        assertEquals(500, plan.slippageBps)
        assertEquals(sol("0.0005"), plan.priorityFee)
        assertNull(policy.nextAttempt(4, 500, sol("0.0005")))
    }

    @Test
    fun `healthy position remains open`() {
        val position = openPosition(input = sol("0.1"), entryCosts = TradeCosts())
        val decision =
            ExitEngine(exitPolicy()).evaluate(
                position,
                ExitObservation(
                    now = NOW.plusSeconds(30),
                    quote = quote("0.105"),
                    highestExecutableValue = sol("0.105"),
                    safetyScore = 80,
                ),
            )

        assertFalse(decision.exitRequested)
        assertEquals(emptySet<ExitReason>(), decision.reasons)
    }

    private fun openPosition(
        input: Lamports,
        entryCosts: TradeCosts,
    ) = Position(
        id = "position-1",
        openedAt = NOW,
        actualSolInput = input,
        entryCosts = entryCosts,
        state = PositionState.OPEN,
        exitRulesVersion = "exit-v1",
    )

    private fun quote(sol: String) =
        ExecutableSellQuote(
            grossOutput = this.sol(sol),
            estimatedCosts = TradeCosts(),
            observedAt = NOW,
            routeAvailable = true,
        )

    private fun exitPolicy() =
        ExitPolicy(
            takeProfitBps = 2_000,
            hardStopLossBps = 1_000,
            trailingActivationBps = 1_500,
            trailingDistanceBps = 500,
            maximumHoldingTime = Duration.ofMinutes(20),
            minimumSafetyScore = 50,
        )

    private fun sol(value: String): Lamports = Lamports.fromSol(BigDecimal(value))

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T10:00:00Z")
    }
}
