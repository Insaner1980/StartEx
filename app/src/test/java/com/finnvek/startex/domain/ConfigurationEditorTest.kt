package com.finnvek.startex.domain

import com.finnvek.startex.bootstrap.DefaultConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationEditorTest {
    @Test
    fun `risk edit parses exact values versions the entity and preserves untouched limits`() {
        val previous = DefaultConfiguration.risk(createdAtMillis = 1)

        val updated =
            ConfigurationEditor
                .editRisk(
                    previous = previous,
                    input =
                        riskInput(
                            trade = "0.123456789",
                            exposure = "0.5",
                            dailyLoss = "0.25",
                            reserve = "0.01",
                            slippage = "1.25",
                            holdMinutes = "30",
                        ),
                    nowMillis = NOW,
                ).updated()

        assertEquals(2, updated.version)
        assertEquals(123_456_789L, updated.maximumTradeLamports)
        assertEquals(500_000_000L, updated.maximumExposureLamports)
        assertEquals(250_000_000L, updated.maximumDailyLossLamports)
        assertEquals(10_000_000L, updated.minimumWalletReserveLamports)
        assertEquals(125, updated.maximumSlippageBps)
        assertEquals(30 * 60_000L, updated.maximumHoldingMillis)
        assertEquals(previous.maximumDailyFeesLamports, updated.maximumDailyFeesLamports)
        assertEquals(NOW, updated.createdAtMillis)
    }

    @Test
    fun `risk safe range boundaries are inclusive`() {
        val minimum =
            ConfigurationEditor.editRisk(
                DefaultConfiguration.risk(1),
                riskInput(
                    trade = "0.001",
                    exposure = "0.001",
                    positions = "1",
                    dailyLoss = "0.001",
                    reserve = "0.001",
                    slippage = "0.1",
                    holdMinutes = "1",
                ),
                NOW,
            )
        val maximum =
            ConfigurationEditor.editRisk(
                DefaultConfiguration.risk(1),
                riskInput(
                    trade = "1",
                    exposure = "2",
                    positions = "2",
                    dailyLoss = "2",
                    reserve = "1",
                    slippage = "5",
                    holdMinutes = "60",
                ),
                NOW,
            )

        assertTrue(minimum is ConfigurationEditResult.Updated)
        assertTrue(maximum is ConfigurationEditResult.Updated)
    }

    @Test
    fun `current default daily loss may exceed exposure and still round trips safely`() {
        val previous = DefaultConfiguration.risk(1)

        val updated =
            ConfigurationEditor
                .editRisk(
                    previous,
                    riskInput(
                        trade = "0.01",
                        exposure = "0.01",
                        dailyLoss = "0.015",
                        reserve = "0.005",
                    ),
                    NOW,
                ).updated()

        assertEquals(previous.maximumTradeLamports, updated.maximumTradeLamports)
        assertEquals(previous.maximumExposureLamports, updated.maximumExposureLamports)
        assertEquals(previous.maximumDailyLossLamports, updated.maximumDailyLossLamports)
        assertEquals(previous.minimumWalletReserveLamports, updated.minimumWalletReserveLamports)
    }

    @Test
    fun `risk parser reports required format precision range and overflow errors`() {
        assertError(
            ConfigurationEditor.editRisk(DefaultConfiguration.risk(1), riskInput(trade = ""), NOW),
            ConfigurationField.MAXIMUM_TRADE_SOL,
            ConfigurationErrorCode.REQUIRED,
        )
        assertError(
            ConfigurationEditor.editRisk(DefaultConfiguration.risk(1), riskInput(trade = "1e-2"), NOW),
            ConfigurationField.MAXIMUM_TRADE_SOL,
            ConfigurationErrorCode.INVALID_FORMAT,
        )
        assertError(
            ConfigurationEditor.editRisk(DefaultConfiguration.risk(1), riskInput(trade = "0,01"), NOW),
            ConfigurationField.MAXIMUM_TRADE_SOL,
            ConfigurationErrorCode.INVALID_FORMAT,
        )
        assertError(
            ConfigurationEditor.editRisk(DefaultConfiguration.risk(1), riskInput(trade = "0.0000000001"), NOW),
            ConfigurationField.MAXIMUM_TRADE_SOL,
            ConfigurationErrorCode.TOO_PRECISE,
        )
        assertError(
            ConfigurationEditor.editRisk(DefaultConfiguration.risk(1), riskInput(positions = "3"), NOW),
            ConfigurationField.MAXIMUM_OPEN_POSITIONS,
            ConfigurationErrorCode.OUT_OF_RANGE,
        )
        assertError(
            ConfigurationEditor.editRisk(
                DefaultConfiguration.risk(1),
                riskInput(exposure = "9".repeat(65)),
                NOW,
            ),
            ConfigurationField.MAXIMUM_EXPOSURE_SOL,
            ConfigurationErrorCode.OVERFLOW,
        )
    }

    @Test
    fun `risk maximum trade cannot exceed total exposure`() {
        assertError(
            ConfigurationEditor.editRisk(
                DefaultConfiguration.risk(1),
                riskInput(trade = "0.2", exposure = "0.1"),
                NOW,
            ),
            ConfigurationField.MAXIMUM_TRADE_SOL,
            ConfigurationErrorCode.INCONSISTENT,
            ConfigurationField.MAXIMUM_EXPOSURE_SOL,
        )
    }

    @Test
    fun `strategy edit converts exact percentages and observation seconds`() {
        val previous = DefaultConfiguration.strategy(createdAtMillis = 1)

        val updated =
            ConfigurationEditor
                .editStrategy(
                    previous,
                    strategyInput(
                        score = "80",
                        takeProfit = "25.25",
                        hardStop = "12.5",
                        trailingActivation = "20",
                        trailingDistance = "7.75",
                        observationSeconds = "45",
                    ),
                    NOW,
                ).updated()

        assertEquals(2, updated.version)
        assertEquals(80, updated.minimumEntryScore)
        assertEquals(2_525, updated.takeProfitBps)
        assertEquals(1_250, updated.hardStopLossBps)
        assertEquals(2_000, updated.trailingActivationBps)
        assertEquals(775, updated.trailingDistanceBps)
        assertEquals(45_000L, updated.minimumObservationMillis)
        assertEquals(previous.weightsJson, updated.weightsJson)
        assertEquals(NOW, updated.createdAtMillis)
    }

    @Test
    fun `strategy safe range boundaries are inclusive while trailing distance stays below activation`() {
        val minimum =
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(
                    score = "50",
                    takeProfit = "1",
                    hardStop = "0.5",
                    trailingActivation = "1",
                    trailingDistance = "0.5",
                    observationSeconds = "15",
                ),
                NOW,
            )
        val maximum =
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(
                    score = "100",
                    takeProfit = "100",
                    hardStop = "50",
                    trailingActivation = "100",
                    trailingDistance = "50",
                    observationSeconds = "300",
                ),
                NOW,
            )

        assertTrue(minimum is ConfigurationEditResult.Updated)
        assertTrue(maximum is ConfigurationEditResult.Updated)
    }

    @Test
    fun `strategy parser rejects noncanonical integers and over-precise percentages`() {
        assertError(
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(score = "075"),
                NOW,
            ),
            ConfigurationField.MINIMUM_SCORE,
            ConfigurationErrorCode.INVALID_FORMAT,
        )
        assertError(
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(takeProfit = "25.001"),
                NOW,
            ),
            ConfigurationField.TAKE_PROFIT_PERCENT,
            ConfigurationErrorCode.TOO_PRECISE,
        )
        assertError(
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(observationSeconds = "30.0"),
                NOW,
            ),
            ConfigurationField.OBSERVATION_SECONDS,
            ConfigurationErrorCode.INVALID_FORMAT,
        )
    }

    @Test
    fun `strategy cross-field exit and candidate-age limits fail closed`() {
        assertError(
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(takeProfit = "10", hardStop = "11"),
                NOW,
            ),
            ConfigurationField.HARD_STOP_PERCENT,
            ConfigurationErrorCode.INCONSISTENT,
            ConfigurationField.TAKE_PROFIT_PERCENT,
        )
        assertError(
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(takeProfit = "10", hardStop = "5", trailingActivation = "11"),
                NOW,
            ),
            ConfigurationField.TRAILING_ACTIVATION_PERCENT,
            ConfigurationErrorCode.INCONSISTENT,
            ConfigurationField.TAKE_PROFIT_PERCENT,
        )
        assertError(
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1),
                strategyInput(trailingActivation = "8", trailingDistance = "8"),
                NOW,
            ),
            ConfigurationField.TRAILING_DISTANCE_PERCENT,
            ConfigurationErrorCode.INCONSISTENT,
            ConfigurationField.TRAILING_ACTIVATION_PERCENT,
        )
        assertError(
            ConfigurationEditor.editStrategy(
                DefaultConfiguration.strategy(1).copy(maximumCandidateAgeMillis = 14_999),
                strategyInput(observationSeconds = "15"),
                NOW,
            ),
            ConfigurationField.OBSERVATION_SECONDS,
            ConfigurationErrorCode.INCONSISTENT,
        )
    }

    @Test
    fun `version and timestamp overflow fail with typed internal field errors`() {
        assertError(
            ConfigurationEditor.editRisk(
                DefaultConfiguration.risk(1).copy(version = Int.MAX_VALUE),
                riskInput(),
                NOW,
            ),
            ConfigurationField.VERSION,
            ConfigurationErrorCode.OVERFLOW,
        )
        assertError(
            ConfigurationEditor.editStrategy(DefaultConfiguration.strategy(1), strategyInput(), -1),
            ConfigurationField.CREATED_AT,
            ConfigurationErrorCode.OUT_OF_RANGE,
        )
    }

    private fun riskInput(
        trade: String = "0.01",
        exposure: String = "0.02",
        positions: String = "1",
        dailyLoss: String = "0.01",
        reserve: String = "0.005",
        slippage: String = "3",
        holdMinutes: String = "10",
    ) = RiskConfigurationInput(
        maximumTradeSol = trade,
        maximumExposureSol = exposure,
        maximumOpenPositions = positions,
        maximumDailyLossSol = dailyLoss,
        feeReserveSol = reserve,
        maximumSlippagePercent = slippage,
        maximumHoldMinutes = holdMinutes,
    )

    private fun strategyInput(
        score: String = "75",
        takeProfit: String = "25",
        hardStop: String = "15",
        trailingActivation: String = "15",
        trailingDistance: String = "8",
        observationSeconds: String = "60",
    ) = StrategyConfigurationInput(
        minimumScore = score,
        takeProfitPercent = takeProfit,
        hardStopPercent = hardStop,
        trailingActivationPercent = trailingActivation,
        trailingDistancePercent = trailingDistance,
        observationSeconds = observationSeconds,
    )

    private fun <T> ConfigurationEditResult<T>.updated(): T {
        require(this is ConfigurationEditResult.Updated)
        return entity
    }

    private fun assertError(
        result: ConfigurationEditResult<*>,
        field: ConfigurationField,
        code: ConfigurationErrorCode,
        relatedField: ConfigurationField? = null,
    ) {
        require(result is ConfigurationEditResult.Invalid)
        assertEquals(ConfigurationFieldError(field, code, relatedField), result.error)
    }

    private companion object {
        const val NOW = 1_786_276_800_000L
    }
}
