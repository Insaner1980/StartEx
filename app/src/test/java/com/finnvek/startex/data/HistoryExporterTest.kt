package com.finnvek.startex.data

import com.finnvek.startex.data.local.BlockchainTransactionEntity
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.DecisionEntity
import com.finnvek.startex.data.local.FeeRecordEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TokenSnapshotEntity
import com.finnvek.startex.data.local.TradeIntentEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExporterTest {
    @Test
    fun `analysis data keeps a rejected decision without a trade intent`() {
        val export =
            analysisExport(
                candidates = listOf(candidate(state = "REJECTED", rejectionCode = "LOW_LIQUIDITY")),
                snapshots = listOf(snapshot(id = 1, capturedAtMillis = 100), snapshot(id = 2, capturedAtMillis = 200)),
                decisions = listOf(decision(action = "REJECT", rejectionCodesJson = "[\"LOW_LIQUIDITY\"]")),
            )

        assertEquals(listOf("mint-1"), export.candidates.map(AnalysisCandidateRecord::mint))
        assertEquals(listOf(100L, 200L), export.snapshots.map(AnalysisSnapshotRecord::capturedAtMillis))
        assertEquals("LOW_LIQUIDITY", export.candidates.single().finalOutcome)
        assertEquals("PAPER", export.decisions.single().mode)
        assertEquals(3, export.decisions.single().strategyVersion)
        assertEquals(4, export.decisions.single().riskVersion)
        assertEquals("[\"LOW_LIQUIDITY\"]", export.decisions.single().rejectionCodesJson)
        assertTrue(export.tradeIntents.isEmpty())
    }

    @Test
    fun `json export uses separate arrays and includes execution analysis facts`() {
        val export =
            analysisExport(
                candidates = listOf(candidate()),
                snapshots = listOf(snapshot()),
                decisions = listOf(decision()),
                positions = listOf(position()),
                intents = listOf(intent()),
                transactions = listOf(transaction()),
                fees = listOf(fee()),
            )

        val root = Json.parseToJsonElement(HistoryExporter.toJson(export)).jsonObject

        assertEquals(1, root.getValue("candidates").jsonArray.size)
        assertEquals(1, root.getValue("snapshots").jsonArray.size)
        assertEquals(1, root.getValue("decisions").jsonArray.size)
        assertEquals(1, root.getValue("positions").jsonArray.size)
        assertEquals(1, root.getValue("tradeIntents").jsonArray.size)
        assertEquals(1, root.getValue("transactions").jsonArray.size)
        assertEquals(1, root.getValue("fees").jsonArray.size)

        val exportedPosition =
            root
                .getValue("positions")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("2000000", exportedPosition.getValue("maximumFavorableExcursionLamports").jsonPrimitive.content)
        assertEquals("2000000", exportedPosition.getValue("maximumAdverseExcursionLamports").jsonPrimitive.content)
        assertEquals("TAKE_PROFIT", exportedPosition.getValue("finalOutcome").jsonPrimitive.content)
        assertEquals("RECONCILED", exportedPosition.getValue("reconciliationState").jsonPrimitive.content)

        val exportedIntent =
            root
                .getValue("tradeIntents")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("PAPER", exportedIntent.getValue("mode").jsonPrimitive.content)
        assertEquals("12000000", exportedIntent.getValue("expectedOutputAtomic").jsonPrimitive.content)
        assertEquals(
            "17000",
            root
                .getValue("fees")
                .jsonArray
                .single()
                .jsonObject
                .getValue("lamportsEquivalent")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `csv export emits one escaped record-type row for every analysis record`() {
        val export =
            analysisExport(
                candidates = listOf(candidate(mint = "mint,with-comma")),
                snapshots = listOf(snapshot(candidateMint = "mint,with-comma")),
                decisions = listOf(decision(candidateMint = "mint,with-comma")),
                positions = listOf(position(mint = "mint,with-comma")),
                intents = listOf(intent(mint = "mint,with-comma")),
                transactions = listOf(transaction()),
                fees = listOf(fee()),
            )

        val lines = HistoryExporter.toCsv(export).lines()

        assertEquals(
            "record_type,record_id,candidate_mint,session_id,position_id,parent_id,mode,status," +
                "occurred_at_ms,strategy_version,risk_version,score,reasons_json,rejection_reasons_json," +
                "fee_lamports,maximum_favorable_excursion_lamports,maximum_adverse_excursion_lamports," +
                "final_outcome,transaction_signature,reconciliation_notes,details_json",
            lines.first(),
        )
        assertEquals(8, lines.size)
        assertTrue(lines.any { it.startsWith("candidate,mint,with-comma").not() && it.contains("\"mint,with-comma\"") })
        assertTrue(lines.any { it.startsWith("decision,decision-1,") && it.contains(",PAPER,ENTER,") })
        assertTrue(lines.any { it.startsWith("position,position-1,") && it.contains(",2000000,2000000,TAKE_PROFIT,") })
        assertTrue(lines.any { it.startsWith("fee,fee-1,") && it.contains(",17000,") })
    }

    private fun analysisExport(
        candidates: List<TokenCandidateEntity> = emptyList(),
        snapshots: List<TokenSnapshotEntity> = emptyList(),
        decisions: List<DecisionEntity> = emptyList(),
        positions: List<PositionEntity> = emptyList(),
        intents: List<TradeIntentEntity> = emptyList(),
        transactions: List<BlockchainTransactionEntity> = emptyList(),
        fees: List<FeeRecordEntity> = emptyList(),
    ): AnalysisExport =
        AnalysisExport.fromEntities(
            sessions = listOf(session()),
            candidates = candidates,
            snapshots = snapshots,
            decisions = decisions,
            positions = positions,
            tradeIntents = intents,
            transactions = transactions,
            fees = fees,
        )

    private fun session() =
        BotSessionEntity(
            id = "session-1",
            mode = "PAPER",
            status = "STOPPED",
            strategyVersion = 3,
            riskVersion = 4,
            startedAtMillis = 50,
            stoppedAtMillis = 500,
            stopReason = "USER_STOPPED",
            lastHeartbeatAtMillis = 500,
        )

    private fun candidate(
        mint: String = "mint-1",
        state: String = "ACCEPTED",
        rejectionCode: String? = null,
    ) = TokenCandidateEntity(
        mint = mint,
        source = "PUMPPORTAL",
        discoverySignature = "discovery-signature",
        creatorAddress = "creator-address",
        name = "Token One",
        symbol = "ONE",
        metadataUri = "https://metadata.invalid/one.json",
        tokenProgram = "TOKEN_PROGRAM",
        state = state,
        score = 82,
        rejectionCode = rejectionCode,
        discoveredAtMillis = 75,
        lastUpdatedAtMillis = 250,
    )

    private fun snapshot(
        id: Long = 1,
        candidateMint: String = "mint-1",
        capturedAtMillis: Long = 100,
    ) = TokenSnapshotEntity(
        id = id,
        candidateMint = candidateMint,
        capturedAtMillis = capturedAtMillis,
        slot = 123,
        liquidityLamports = 50_000_000,
        marketCapLamports = 100_000_000,
        executableBuyLamports = 10_000_000,
        executableSellLamports = 9_500_000,
        holderCount = 42,
        topHolderShareBps = 1_200,
        routeAvailable = true,
        source = "JUPITER",
    )

    private fun decision(
        candidateMint: String = "mint-1",
        action: String = "ENTER",
        rejectionCodesJson: String = "[]",
    ) = DecisionEntity(
        id = "decision-1",
        sessionId = "session-1",
        candidateMint = candidateMint,
        strategyVersion = 3,
        riskVersion = 4,
        action = action,
        score = 82,
        factorsJson = "{\"liquidity\":\"PASS\"}",
        rejectionCodesJson = rejectionCodesJson,
        createdAtMillis = 250,
    )

    private fun position(mint: String = "mint-1") =
        PositionEntity(
            id = "position-1",
            sessionId = "session-1",
            mint = mint,
            entryDecisionId = "decision-1",
            status = "CLOSED",
            tokenAmountAtomic = "5000000",
            grossInputLamports = 10_000_000,
            netInputLamports = 9_983_000,
            latestSellQuoteLamports = 12_000_000,
            entrySignature = "entry-signature",
            exitSignature = "exit-signature",
            openedAtMillis = 300,
            updatedAtMillis = 450,
            closedAtMillis = 450,
            exitReason = "TAKE_PROFIT",
            mode = "PAPER",
            symbol = "ONE",
            name = "Token One",
            tokenDecimals = 6,
            tokenProgram = "TOKEN_PROGRAM",
            entryCostLamports = 10_000_000,
            exitRulesVersion = "exit-v1",
            exitRulesJson = "{\"takeProfitBps\":2000}",
            latestSellQuoteAtMillis = 440,
            highestExecutableSellLamports = 12_000_000,
            lowestExecutableSellLamports = 8_000_000,
            routeAvailable = true,
            reconciliationState = "RECONCILED",
        )

    private fun intent(mint: String = "mint-1") =
        TradeIntentEntity(
            id = "intent-1",
            sessionId = "session-1",
            positionId = "position-1",
            idempotencyKey = "intent-key-1",
            side = "SELL",
            mint = mint,
            requestedInputAtomic = "5000000",
            expectedOutputAtomic = "12000000",
            maximumCostLamports = 20_000,
            paperFeeLamports = 17_000,
            providerRequestId = "provider-request-1",
            status = "PAPER_FILLED",
            createdAtMillis = 425,
            updatedAtMillis = 450,
        )

    private fun transaction() =
        BlockchainTransactionEntity(
            id = "transaction-1",
            intentId = "intent-1",
            idempotencyKey = "intent-key-1",
            signature = "transaction-signature",
            serializedHash = "serialized-hash",
            status = "FINALIZED",
            slot = 456,
            actualInputAtomic = "5000000",
            actualOutputAtomic = "11900000",
            router = "JUPITER",
            validatorVersion = "validator-v1",
            validatorResult = "PASSED",
            lastValidBlockHeight = 999,
            expiresAtMillis = 1_000,
            submittedAtMillis = 430,
            confirmedAtMillis = 440,
            failureCode = null,
        )

    private fun fee() =
        FeeRecordEntity(
            id = "fee-1",
            transactionId = "transaction-1",
            feeType = "NETWORK",
            mint = null,
            amountAtomic = "17000",
            lamportsEquivalent = 17_000,
            recordedAtMillis = 440,
        )
}
