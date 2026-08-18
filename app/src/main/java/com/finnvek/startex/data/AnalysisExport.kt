package com.finnvek.startex.data

import com.finnvek.startex.data.local.BlockchainTransactionEntity
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.DecisionEntity
import com.finnvek.startex.data.local.FeeRecordEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TokenSnapshotEntity
import com.finnvek.startex.data.local.TradeIntentEntity
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisExport(
    val candidates: List<AnalysisCandidateRecord>,
    val snapshots: List<AnalysisSnapshotRecord>,
    val decisions: List<AnalysisDecisionRecord>,
    val positions: List<AnalysisPositionRecord>,
    val tradeIntents: List<AnalysisTradeIntentRecord>,
    val transactions: List<AnalysisTransactionRecord>,
    val fees: List<AnalysisFeeRecord>,
) {
    companion object {
        fun fromEntities(
            sessions: List<BotSessionEntity>,
            candidates: List<TokenCandidateEntity>,
            snapshots: List<TokenSnapshotEntity>,
            decisions: List<DecisionEntity>,
            positions: List<PositionEntity>,
            tradeIntents: List<TradeIntentEntity>,
            transactions: List<BlockchainTransactionEntity>,
            fees: List<FeeRecordEntity>,
        ): AnalysisExport {
            val sessionsById = sessions.associateBy(BotSessionEntity::id)
            val decisionsById = decisions.associateBy(DecisionEntity::id)
            val positionsById = positions.associateBy(PositionEntity::id)
            val intentsById = tradeIntents.associateBy(TradeIntentEntity::id)
            val transactionsById = transactions.associateBy(BlockchainTransactionEntity::id)

            return AnalysisExport(
                candidates =
                    candidates
                        .sortedWith(compareBy(TokenCandidateEntity::discoveredAtMillis, TokenCandidateEntity::mint))
                        .map { AnalysisCandidateRecord.from(it) },
                snapshots =
                    snapshots
                        .sortedWith(compareBy(TokenSnapshotEntity::capturedAtMillis, TokenSnapshotEntity::id))
                        .map { AnalysisSnapshotRecord.from(it) },
                decisions =
                    decisions
                        .sortedWith(compareBy(DecisionEntity::createdAtMillis, DecisionEntity::id))
                        .map { decision ->
                            AnalysisDecisionRecord.from(decision, sessionsById[decision.sessionId]?.mode)
                        },
                positions =
                    positions
                        .sortedWith(compareBy(PositionEntity::openedAtMillis, PositionEntity::id))
                        .map { position ->
                            AnalysisPositionRecord.from(position, decisionsById[position.entryDecisionId])
                        },
                tradeIntents =
                    tradeIntents
                        .sortedWith(compareBy(TradeIntentEntity::createdAtMillis, TradeIntentEntity::id))
                        .map { intent -> AnalysisTradeIntentRecord.from(intent, sessionsById[intent.sessionId]) },
                transactions =
                    transactions
                        .sortedWith(
                            compareBy(
                                BlockchainTransactionEntity::submittedAtMillis,
                                BlockchainTransactionEntity::id,
                            ),
                        ).map { transaction ->
                            val intent = transaction.intentId?.let(intentsById::get)
                            val session = intent?.let { sessionsById[it.sessionId] }
                            val position = intent?.positionId?.let(positionsById::get)
                            AnalysisTransactionRecord.from(transaction, intent, session, position)
                        },
                fees =
                    fees
                        .sortedWith(compareBy(FeeRecordEntity::recordedAtMillis, FeeRecordEntity::id))
                        .map { fee ->
                            val transaction = transactionsById[fee.transactionId]
                            val intent = transaction?.intentId?.let(intentsById::get)
                            AnalysisFeeRecord.from(fee, intent, intent?.let { sessionsById[it.sessionId] })
                        },
            )
        }
    }
}

// CPD-OFF
@Serializable
data class AnalysisCandidateRecord(
    val mint: String,
    val source: String,
    val discoverySignature: String,
    val creatorAddress: String?,
    val name: String?,
    val symbol: String?,
    val metadataUri: String?,
    val tokenProgram: String?,
    val state: String,
    val score: Int?,
    val rejectionCode: String?,
    val discoveredAtMillis: Long,
    val lastUpdatedAtMillis: Long,
    val finalOutcome: String,
) {
    companion object {
        fun from(candidate: TokenCandidateEntity) =
            AnalysisCandidateRecord(
                mint = candidate.mint,
                source = candidate.source,
                discoverySignature = candidate.discoverySignature,
                creatorAddress = candidate.creatorAddress,
                name = candidate.name,
                symbol = candidate.symbol,
                metadataUri = candidate.metadataUri,
                tokenProgram = candidate.tokenProgram,
                state = candidate.state,
                score = candidate.score,
                rejectionCode = candidate.rejectionCode,
                discoveredAtMillis = candidate.discoveredAtMillis,
                lastUpdatedAtMillis = candidate.lastUpdatedAtMillis,
                finalOutcome = candidate.rejectionCode ?: candidate.state,
            )
    }
}

@Serializable
data class AnalysisSnapshotRecord(
    val id: Long,
    val candidateMint: String,
    val capturedAtMillis: Long,
    val slot: Long?,
    val liquidityLamports: Long?,
    val marketCapLamports: Long?,
    val executableBuyLamports: Long?,
    val executableSellLamports: Long?,
    val holderCount: Int?,
    val topHolderShareBps: Int?,
    val routeAvailable: Boolean,
    val source: String,
) {
    companion object {
        fun from(snapshot: TokenSnapshotEntity) =
            AnalysisSnapshotRecord(
                id = snapshot.id,
                candidateMint = snapshot.candidateMint,
                capturedAtMillis = snapshot.capturedAtMillis,
                slot = snapshot.slot,
                liquidityLamports = snapshot.liquidityLamports,
                marketCapLamports = snapshot.marketCapLamports,
                executableBuyLamports = snapshot.executableBuyLamports,
                executableSellLamports = snapshot.executableSellLamports,
                holderCount = snapshot.holderCount,
                topHolderShareBps = snapshot.topHolderShareBps,
                routeAvailable = snapshot.routeAvailable,
                source = snapshot.source,
            )
    }
}
// CPD-ON

@Serializable
data class AnalysisDecisionRecord(
    val id: String,
    val sessionId: String,
    val candidateMint: String,
    val mode: String?,
    val strategyVersion: Int,
    val riskVersion: Int,
    val action: String,
    val score: Int,
    val factorsJson: String,
    val rejectionCodesJson: String,
    val createdAtMillis: Long,
) {
    companion object {
        fun from(
            decision: DecisionEntity,
            mode: String?,
        ) = AnalysisDecisionRecord(
            id = decision.id,
            sessionId = decision.sessionId,
            candidateMint = decision.candidateMint,
            mode = mode,
            strategyVersion = decision.strategyVersion,
            riskVersion = decision.riskVersion,
            action = decision.action,
            score = decision.score,
            factorsJson = decision.factorsJson,
            rejectionCodesJson = decision.rejectionCodesJson,
            createdAtMillis = decision.createdAtMillis,
        )
    }
}

@Serializable
data class AnalysisPositionRecord(
    val id: String,
    val sessionId: String,
    val mint: String,
    val entryDecisionId: String,
    val mode: String,
    val strategyVersion: Int?,
    val riskVersion: Int?,
    val status: String,
    val symbol: String,
    val name: String,
    val tokenAmountAtomic: String,
    val tokenDecimals: Int,
    val tokenProgram: String,
    val grossInputLamports: Long,
    val netInputLamports: Long,
    val entryCostLamports: Long,
    val latestSellQuoteLamports: Long?,
    val latestSellQuoteAtMillis: Long?,
    val highestExecutableSellLamports: Long,
    val lowestExecutableSellLamports: Long,
    val maximumFavorableExcursionLamports: Long,
    val maximumAdverseExcursionLamports: Long,
    val entrySignature: String?,
    val exitSignature: String?,
    val openedAtMillis: Long,
    val updatedAtMillis: Long,
    val closedAtMillis: Long?,
    val exitReason: String?,
    val exitRulesVersion: String,
    val exitRulesJson: String,
    val routeAvailable: Boolean,
    val reconciliationState: String,
    val finalOutcome: String,
) {
    companion object {
        fun from(
            position: PositionEntity,
            entryDecision: DecisionEntity?,
        ) = AnalysisPositionRecord(
            id = position.id,
            sessionId = position.sessionId,
            mint = position.mint,
            entryDecisionId = position.entryDecisionId,
            mode = position.mode,
            strategyVersion = entryDecision?.strategyVersion,
            riskVersion = entryDecision?.riskVersion,
            status = position.status,
            symbol = position.symbol,
            name = position.name,
            tokenAmountAtomic = position.tokenAmountAtomic,
            tokenDecimals = position.tokenDecimals,
            tokenProgram = position.tokenProgram,
            grossInputLamports = position.grossInputLamports,
            netInputLamports = position.netInputLamports,
            entryCostLamports = position.entryCostLamports,
            latestSellQuoteLamports = position.latestSellQuoteLamports,
            latestSellQuoteAtMillis = position.latestSellQuoteAtMillis,
            highestExecutableSellLamports = position.highestExecutableSellLamports,
            lowestExecutableSellLamports = position.lowestExecutableSellLamports,
            maximumFavorableExcursionLamports =
                (position.highestExecutableSellLamports - position.entryCostLamports).coerceAtLeast(0),
            maximumAdverseExcursionLamports =
                (position.entryCostLamports - position.lowestExecutableSellLamports).coerceAtLeast(0),
            entrySignature = position.entrySignature,
            exitSignature = position.exitSignature,
            openedAtMillis = position.openedAtMillis,
            updatedAtMillis = position.updatedAtMillis,
            closedAtMillis = position.closedAtMillis,
            exitReason = position.exitReason,
            exitRulesVersion = position.exitRulesVersion,
            exitRulesJson = position.exitRulesJson,
            routeAvailable = position.routeAvailable,
            reconciliationState = position.reconciliationState,
            finalOutcome = position.exitReason ?: position.status,
        )
    }
}

// CPD-OFF
@Serializable
data class AnalysisTradeIntentRecord(
    val id: String,
    val sessionId: String,
    val positionId: String?,
    val idempotencyKey: String,
    val mode: String?,
    val strategyVersion: Int?,
    val riskVersion: Int?,
    val side: String,
    val mint: String,
    val requestedInputAtomic: String,
    val expectedOutputAtomic: String,
    val maximumCostLamports: Long,
    val paperFeeLamports: Long,
    val providerRequestId: String?,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val finalOutcome: String,
) {
    companion object {
        fun from(
            intent: TradeIntentEntity,
            session: BotSessionEntity?,
        ) = AnalysisTradeIntentRecord(
            id = intent.id,
            sessionId = intent.sessionId,
            positionId = intent.positionId,
            idempotencyKey = intent.idempotencyKey,
            mode = session?.mode,
            strategyVersion = session?.strategyVersion,
            riskVersion = session?.riskVersion,
            side = intent.side,
            mint = intent.mint,
            requestedInputAtomic = intent.requestedInputAtomic,
            expectedOutputAtomic = intent.expectedOutputAtomic,
            maximumCostLamports = intent.maximumCostLamports,
            paperFeeLamports = intent.paperFeeLamports,
            providerRequestId = intent.providerRequestId,
            status = intent.status,
            createdAtMillis = intent.createdAtMillis,
            updatedAtMillis = intent.updatedAtMillis,
            finalOutcome = intent.status,
        )
    }
}

@Serializable
data class AnalysisTransactionRecord(
    val id: String,
    val intentId: String?,
    val positionId: String?,
    val candidateMint: String?,
    val mode: String?,
    val strategyVersion: Int?,
    val riskVersion: Int?,
    val idempotencyKey: String,
    val signature: String?,
    val serializedHash: String,
    val status: String,
    val slot: Long?,
    val actualInputAtomic: String?,
    val actualOutputAtomic: String?,
    val router: String?,
    val validatorVersion: String,
    val validatorResult: String,
    val lastValidBlockHeight: Long?,
    val expiresAtMillis: Long?,
    val submittedAtMillis: Long,
    val confirmedAtMillis: Long?,
    val failureCode: String?,
    val reconciliationState: String?,
) {
    companion object {
        fun from(
            transaction: BlockchainTransactionEntity,
            intent: TradeIntentEntity?,
            session: BotSessionEntity?,
            position: PositionEntity?,
        ) = AnalysisTransactionRecord(
            id = transaction.id,
            intentId = transaction.intentId,
            positionId = intent?.positionId,
            candidateMint = intent?.mint,
            mode = session?.mode,
            strategyVersion = session?.strategyVersion,
            riskVersion = session?.riskVersion,
            idempotencyKey = transaction.idempotencyKey,
            signature = transaction.signature,
            serializedHash = transaction.serializedHash,
            status = transaction.status,
            slot = transaction.slot,
            actualInputAtomic = transaction.actualInputAtomic,
            actualOutputAtomic = transaction.actualOutputAtomic,
            router = transaction.router,
            validatorVersion = transaction.validatorVersion,
            validatorResult = transaction.validatorResult,
            lastValidBlockHeight = transaction.lastValidBlockHeight,
            expiresAtMillis = transaction.expiresAtMillis,
            submittedAtMillis = transaction.submittedAtMillis,
            confirmedAtMillis = transaction.confirmedAtMillis,
            failureCode = transaction.failureCode,
            reconciliationState = position?.reconciliationState,
        )
    }
}
// CPD-ON

@Serializable
data class AnalysisFeeRecord(
    val id: String,
    val transactionId: String,
    val candidateMint: String?,
    val mode: String?,
    val feeType: String,
    val mint: String?,
    val amountAtomic: String,
    val lamportsEquivalent: Long?,
    val recordedAtMillis: Long,
) {
    companion object {
        fun from(
            fee: FeeRecordEntity,
            intent: TradeIntentEntity?,
            session: BotSessionEntity?,
        ) = AnalysisFeeRecord(
            id = fee.id,
            transactionId = fee.transactionId,
            candidateMint = intent?.mint,
            mode = session?.mode,
            feeType = fee.feeType,
            mint = fee.mint,
            amountAtomic = fee.amountAtomic,
            lamportsEquivalent = fee.lamportsEquivalent,
            recordedAtMillis = fee.recordedAtMillis,
        )
    }
}
