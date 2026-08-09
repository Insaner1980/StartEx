package com.finnvek.startex.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionReconcilerTest {
    @Test
    fun `timeout stays uncertain and blocks a duplicate submission`() {
        val transaction = uncertainTransaction()
        val decision =
            TransactionReconciler.reconcile(
                transaction,
                ReconciliationEvidence(
                    signatureStatus = SignatureStatus.UNKNOWN,
                    balancesChecked = true,
                    expectedBalancesObserved = false,
                    validityWindowExpired = false,
                ),
            )

        assertEquals(BlockchainTransactionState.UNCERTAIN, decision.nextState)
        assertFalse(decision.mayCreateFreshTransaction)
        assertFalse(SubmissionGuard.canCreateAttempt(transaction.idempotencyKey, listOf(transaction)))
    }

    @Test
    fun `observed balance effect proves an uncertain transaction landed`() {
        val decision =
            TransactionReconciler.reconcile(
                uncertainTransaction(),
                ReconciliationEvidence(
                    signatureStatus = SignatureStatus.UNKNOWN,
                    balancesChecked = true,
                    expectedBalancesObserved = true,
                    validityWindowExpired = false,
                ),
            )

        assertEquals(BlockchainTransactionState.RECONCILED, decision.nextState)
        assertFalse(decision.mayCreateFreshTransaction)
    }

    @Test
    fun `fresh transaction is allowed only after expiry no signature and checked unchanged balances`() {
        val decision =
            TransactionReconciler.reconcile(
                uncertainTransaction(),
                ReconciliationEvidence(
                    signatureStatus = SignatureStatus.NOT_FOUND,
                    balancesChecked = true,
                    expectedBalancesObserved = false,
                    validityWindowExpired = true,
                ),
            )

        assertEquals(BlockchainTransactionState.EXPIRED, decision.nextState)
        assertTrue(decision.mayCreateFreshTransaction)
        val expired = uncertainTransaction().copy(state = decision.nextState)
        assertTrue(SubmissionGuard.canCreateAttempt(expired.idempotencyKey, listOf(expired)))
    }

    @Test
    fun `confirmed reconciliation is idempotent`() {
        val confirmed = uncertainTransaction().copy(state = BlockchainTransactionState.RECONCILED)
        val decision =
            TransactionReconciler.reconcile(
                confirmed,
                ReconciliationEvidence(
                    signatureStatus = SignatureStatus.UNKNOWN,
                    balancesChecked = false,
                    expectedBalancesObserved = false,
                    validityWindowExpired = true,
                ),
            )

        assertEquals(BlockchainTransactionState.RECONCILED, decision.nextState)
        assertFalse(decision.mayCreateFreshTransaction)
    }

    @Test
    fun `transaction state machine does not bypass uncertain reconciliation`() {
        assertEquals(
            BlockchainTransactionState.UNCERTAIN,
            BlockchainTransactionStateMachine.transition(
                BlockchainTransactionState.SUBMITTED,
                BlockchainTransactionState.UNCERTAIN,
            ),
        )
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            BlockchainTransactionStateMachine.transition(
                BlockchainTransactionState.UNCERTAIN,
                BlockchainTransactionState.SUBMITTED,
            )
        }
    }

    private fun uncertainTransaction() =
        BlockchainTransaction(
            id = "tx-1",
            idempotencyKey = "position-1-entry",
            signature = "signature-1",
            serializedHash = "hash-1",
            state = BlockchainTransactionState.UNCERTAIN,
        )
}
