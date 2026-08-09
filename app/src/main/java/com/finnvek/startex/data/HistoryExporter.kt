package com.finnvek.startex.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object HistoryExporter {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }
    private val compactJson = Json { encodeDefaults = true }

    fun toJson(export: AnalysisExport): String = json.encodeToString(export)

    fun toCsv(export: AnalysisExport): String {
        val rows =
            buildList {
                addAll(export.candidates.map(::candidateRow))
                addAll(export.snapshots.map(::snapshotRow))
                addAll(export.decisions.map(::decisionRow))
                addAll(export.positions.map(::positionRow))
                addAll(export.tradeIntents.map(::intentRow))
                addAll(export.transactions.map(::transactionRow))
                addAll(export.fees.map(::feeRow))
            }
        return buildString {
            appendLine(HEADER)
            rows.forEachIndexed { index, row ->
                append(row.values().joinToString(",", transform = ::escapeCsv))
                if (index != rows.lastIndex) appendLine()
            }
        }
    }

    private fun candidateRow(record: AnalysisCandidateRecord) =
        CsvRecord(
            recordType = "candidate",
            recordId = record.mint,
            candidateMint = record.mint,
            status = record.state,
            occurredAtMillis = record.discoveredAtMillis.toString(),
            score = record.score?.toString().orEmpty(),
            rejectionReasonsJson =
                record.rejectionCode
                    ?.let { compactJson.encodeToString(listOf(it)) }
                    .orEmpty(),
            finalOutcome = record.finalOutcome,
            detailsJson = compactJson.encodeToString(record),
        )

    private fun snapshotRow(record: AnalysisSnapshotRecord) =
        CsvRecord(
            recordType = "snapshot",
            recordId = record.id.toString(),
            candidateMint = record.candidateMint,
            status = if (record.routeAvailable) "ROUTE_AVAILABLE" else "ROUTE_UNAVAILABLE",
            occurredAtMillis = record.capturedAtMillis.toString(),
            detailsJson = compactJson.encodeToString(record),
        )

    private fun decisionRow(record: AnalysisDecisionRecord) =
        CsvRecord(
            recordType = "decision",
            recordId = record.id,
            candidateMint = record.candidateMint,
            sessionId = record.sessionId,
            mode = record.mode.orEmpty(),
            status = record.action,
            occurredAtMillis = record.createdAtMillis.toString(),
            strategyVersion = record.strategyVersion.toString(),
            riskVersion = record.riskVersion.toString(),
            score = record.score.toString(),
            reasonsJson = record.factorsJson,
            rejectionReasonsJson = record.rejectionCodesJson,
            finalOutcome = record.action,
            detailsJson = compactJson.encodeToString(record),
        )

    private fun positionRow(record: AnalysisPositionRecord) =
        CsvRecord(
            recordType = "position",
            recordId = record.id,
            candidateMint = record.mint,
            sessionId = record.sessionId,
            positionId = record.id,
            mode = record.mode,
            status = record.status,
            occurredAtMillis = record.openedAtMillis.toString(),
            strategyVersion = record.strategyVersion?.toString().orEmpty(),
            riskVersion = record.riskVersion?.toString().orEmpty(),
            maximumFavorableExcursionLamports = record.maximumFavorableExcursionLamports.toString(),
            maximumAdverseExcursionLamports = record.maximumAdverseExcursionLamports.toString(),
            finalOutcome = record.finalOutcome,
            transactionSignature = listOfNotNull(record.entrySignature, record.exitSignature).joinToString("|"),
            reconciliationNotes = record.reconciliationState,
            detailsJson = compactJson.encodeToString(record),
        )

    private fun intentRow(record: AnalysisTradeIntentRecord) =
        CsvRecord(
            recordType = "trade_intent",
            recordId = record.id,
            candidateMint = record.mint,
            sessionId = record.sessionId,
            positionId = record.positionId.orEmpty(),
            mode = record.mode.orEmpty(),
            status = record.status,
            occurredAtMillis = record.createdAtMillis.toString(),
            strategyVersion = record.strategyVersion?.toString().orEmpty(),
            riskVersion = record.riskVersion?.toString().orEmpty(),
            feeLamports = record.paperFeeLamports.toString(),
            finalOutcome = record.finalOutcome,
            detailsJson = compactJson.encodeToString(record),
        )

    private fun transactionRow(record: AnalysisTransactionRecord) =
        CsvRecord(
            recordType = "transaction",
            recordId = record.id,
            candidateMint = record.candidateMint.orEmpty(),
            positionId = record.positionId.orEmpty(),
            parentId = record.intentId.orEmpty(),
            mode = record.mode.orEmpty(),
            status = record.status,
            occurredAtMillis = record.submittedAtMillis.toString(),
            strategyVersion = record.strategyVersion?.toString().orEmpty(),
            riskVersion = record.riskVersion?.toString().orEmpty(),
            finalOutcome = record.failureCode ?: record.status,
            transactionSignature = record.signature.orEmpty(),
            reconciliationNotes = record.reconciliationState ?: record.failureCode.orEmpty(),
            detailsJson = compactJson.encodeToString(record),
        )

    private fun feeRow(record: AnalysisFeeRecord) =
        CsvRecord(
            recordType = "fee",
            recordId = record.id,
            candidateMint = record.candidateMint.orEmpty(),
            parentId = record.transactionId,
            mode = record.mode.orEmpty(),
            occurredAtMillis = record.recordedAtMillis.toString(),
            feeLamports = record.lamportsEquivalent?.toString().orEmpty(),
            detailsJson = compactJson.encodeToString(record),
        )

    private fun escapeCsv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private data class CsvRecord(
        val recordType: String,
        val recordId: String,
        val candidateMint: String = "",
        val sessionId: String = "",
        val positionId: String = "",
        val parentId: String = "",
        val mode: String = "",
        val status: String = "",
        val occurredAtMillis: String = "",
        val strategyVersion: String = "",
        val riskVersion: String = "",
        val score: String = "",
        val reasonsJson: String = "",
        val rejectionReasonsJson: String = "",
        val feeLamports: String = "",
        val maximumFavorableExcursionLamports: String = "",
        val maximumAdverseExcursionLamports: String = "",
        val finalOutcome: String = "",
        val transactionSignature: String = "",
        val reconciliationNotes: String = "",
        val detailsJson: String,
    ) {
        fun values(): List<String> =
            listOf(
                recordType,
                recordId,
                candidateMint,
                sessionId,
                positionId,
                parentId,
                mode,
                status,
                occurredAtMillis,
                strategyVersion,
                riskVersion,
                score,
                reasonsJson,
                rejectionReasonsJson,
                feeLamports,
                maximumFavorableExcursionLamports,
                maximumAdverseExcursionLamports,
                finalOutcome,
                transactionSignature,
                reconciliationNotes,
                detailsJson,
            )
    }

    private const val HEADER =
        "record_type,record_id,candidate_mint,session_id,position_id,parent_id,mode,status," +
            "occurred_at_ms,strategy_version,risk_version,score,reasons_json,rejection_reasons_json," +
            "fee_lamports,maximum_favorable_excursion_lamports,maximum_adverse_excursion_lamports," +
            "final_outcome,transaction_signature,reconciliation_notes,details_json"
}
