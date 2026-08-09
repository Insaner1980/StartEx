package com.finnvek.startex.trading

enum class BlockchainTransactionState {
    CREATED,
    SUBMITTED,
    UNCERTAIN,
    CONFIRMED,
    RECONCILED,
    EXPIRED,
    FAILED,
}

data class BlockchainTransaction(
    val id: String,
    val idempotencyKey: String,
    val signature: String?,
    val serializedHash: String?,
    val state: BlockchainTransactionState,
) {
    init {
        require(id.isNotBlank())
        require(idempotencyKey.isNotBlank())
        require(signature != null || serializedHash != null)
    }
}

object BlockchainTransactionStateMachine {
    private val transitions =
        mapOf(
            BlockchainTransactionState.CREATED to
                setOf(
                    BlockchainTransactionState.SUBMITTED,
                    BlockchainTransactionState.FAILED,
                ),
            BlockchainTransactionState.SUBMITTED to
                setOf(
                    BlockchainTransactionState.CONFIRMED,
                    BlockchainTransactionState.UNCERTAIN,
                    BlockchainTransactionState.FAILED,
                ),
            BlockchainTransactionState.UNCERTAIN to
                setOf(
                    BlockchainTransactionState.CONFIRMED,
                    BlockchainTransactionState.RECONCILED,
                    BlockchainTransactionState.EXPIRED,
                    BlockchainTransactionState.FAILED,
                ),
            BlockchainTransactionState.CONFIRMED to setOf(BlockchainTransactionState.RECONCILED),
        )

    fun transition(
        from: BlockchainTransactionState,
        to: BlockchainTransactionState,
    ): BlockchainTransactionState {
        check(to in transitions[from].orEmpty()) { "Invalid transaction transition: $from -> $to" }
        return to
    }
}

enum class SignatureStatus {
    CONFIRMED,
    FAILED,
    NOT_FOUND,
    UNKNOWN,
}

data class ReconciliationEvidence(
    val signatureStatus: SignatureStatus,
    val balancesChecked: Boolean,
    val expectedBalancesObserved: Boolean,
    val validityWindowExpired: Boolean,
) {
    init {
        require(!expectedBalancesObserved || balancesChecked) {
            "Expected balance changes require a completed balance check"
        }
    }
}

data class ReconciliationDecision(
    val nextState: BlockchainTransactionState,
    val mayCreateFreshTransaction: Boolean,
)

object TransactionReconciler {
    fun reconcile(
        transaction: BlockchainTransaction,
        evidence: ReconciliationEvidence,
    ): ReconciliationDecision {
        if (transaction.state == BlockchainTransactionState.RECONCILED) {
            return ReconciliationDecision(transaction.state, mayCreateFreshTransaction = false)
        }
        if (
            transaction.state == BlockchainTransactionState.FAILED ||
            transaction.state == BlockchainTransactionState.EXPIRED
        ) {
            return ReconciliationDecision(transaction.state, mayCreateFreshTransaction = true)
        }
        if (evidence.expectedBalancesObserved) {
            return ReconciliationDecision(BlockchainTransactionState.RECONCILED, mayCreateFreshTransaction = false)
        }
        if (evidence.signatureStatus == SignatureStatus.CONFIRMED) {
            return ReconciliationDecision(BlockchainTransactionState.CONFIRMED, mayCreateFreshTransaction = false)
        }
        if (transaction.state == BlockchainTransactionState.CONFIRMED) {
            return ReconciliationDecision(BlockchainTransactionState.CONFIRMED, mayCreateFreshTransaction = false)
        }
        val priorAttemptProvenInactive = evidence.balancesChecked && !evidence.expectedBalancesObserved
        if (evidence.signatureStatus == SignatureStatus.FAILED && priorAttemptProvenInactive) {
            return ReconciliationDecision(BlockchainTransactionState.FAILED, mayCreateFreshTransaction = true)
        }
        if (
            evidence.signatureStatus == SignatureStatus.NOT_FOUND &&
            evidence.validityWindowExpired &&
            priorAttemptProvenInactive
        ) {
            return ReconciliationDecision(BlockchainTransactionState.EXPIRED, mayCreateFreshTransaction = true)
        }
        return ReconciliationDecision(BlockchainTransactionState.UNCERTAIN, mayCreateFreshTransaction = false)
    }
}

object SubmissionGuard {
    fun canCreateAttempt(
        idempotencyKey: String,
        existingTransactions: List<BlockchainTransaction>,
    ): Boolean =
        existingTransactions
            .filter { it.idempotencyKey == idempotencyKey }
            .all { it.state == BlockchainTransactionState.FAILED || it.state == BlockchainTransactionState.EXPIRED }
}
