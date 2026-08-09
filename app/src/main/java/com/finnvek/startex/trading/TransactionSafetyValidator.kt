package com.finnvek.startex.trading

import com.finnvek.startex.domain.Lamports
import java.math.BigInteger

enum class TransactionFormat {
    LEGACY,
    V0,
}

enum class AuthorityChange {
    WALLET_AUTHORITY,
    TOKEN_ACCOUNT_OWNER,
    MINT_AUTHORITY,
    FREEZE_AUTHORITY,
    CLOSE_AUTHORITY,
}

data class SolTransfer(
    val destination: String,
    val amount: Lamports,
)

@Suppress("LongParameterList")
data class ParsedUnsignedTransaction(
    val format: TransactionFormat,
    val requiredSigners: Set<String>,
    val appControlledSigners: Set<String>,
    val inputMint: String,
    val outputMint: String,
    val recipient: String,
    val inputAmount: BigInteger,
    val outputAmount: BigInteger,
    val totalSolDebit: Lamports,
    val priorityFee: Lamports,
    val solTransfers: List<SolTransfer>,
    val authorityChanges: Set<AuthorityChange>,
    val delegateApprovals: Set<String>,
    val closedTokenAccounts: Set<String>,
    val invokedProgramIds: Set<String>,
    val computeUnitLimit: Int,
    val computeUnitPriceMicroLamports: Long,
    val recentBlockhashUsable: Boolean,
    val addressLookupTables: List<String>,
    val addressLookupTablesResolved: Boolean,
) {
    init {
        require(inputAmount.signum() >= 0)
        require(outputAmount.signum() >= 0)
        require(computeUnitLimit >= 0)
        require(computeUnitPriceMicroLamports >= 0)
    }
}

@Suppress("LongParameterList")
data class TransactionIntent(
    val walletPublicKey: String,
    val inputMint: String,
    val outputMint: String,
    val expectedRecipient: String,
    val inputAmount: BigInteger,
    val inputAmountTolerance: BigInteger,
    val expectedOutputAmount: BigInteger,
    val outputAmountTolerance: BigInteger,
    val maximumSolDebit: Lamports,
    val maximumPriorityFee: Lamports,
    val expectedSolDestinations: Set<String>,
    val allowedProgramIds: Set<String>,
    val maximumComputeUnits: Int,
    val maximumComputeUnitPriceMicroLamports: Long,
    val programAllowlistVersion: String,
) {
    init {
        require(walletPublicKey.isNotBlank())
        require(inputMint.isNotBlank())
        require(outputMint.isNotBlank())
        require(expectedRecipient.isNotBlank())
        require(inputAmount.signum() >= 0)
        require(inputAmountTolerance.signum() >= 0)
        require(expectedOutputAmount.signum() >= 0)
        require(outputAmountTolerance.signum() >= 0)
        require(maximumComputeUnits >= 0)
        require(maximumComputeUnitPriceMicroLamports >= 0)
        require(programAllowlistVersion.isNotBlank())
    }
}

fun interface UnsignedTransactionParser {
    fun parse(serializedTransaction: ByteArray): ParsedUnsignedTransaction
}

enum class TransactionSafetyViolation {
    DECODE_FAILED,
    EXPECTED_SIGNER_MISSING,
    UNEXPECTED_APP_SIGNER,
    INPUT_MINT_MISMATCH,
    OUTPUT_MINT_MISMATCH,
    RECIPIENT_MISMATCH,
    INPUT_AMOUNT_MISMATCH,
    OUTPUT_AMOUNT_MISMATCH,
    MAXIMUM_SOL_DEBIT_EXCEEDED,
    PRIORITY_FEE_EXCEEDED,
    UNRELATED_SOL_TRANSFER,
    AUTHORITY_CHANGE,
    UNEXPECTED_DELEGATE,
    UNRELATED_ACCOUNT_CLOSE,
    UNKNOWN_PROGRAM,
    COMPUTE_UNIT_LIMIT_EXCEEDED,
    COMPUTE_UNIT_PRICE_EXCEEDED,
    BLOCKHASH_NOT_USABLE,
    UNRESOLVED_ADDRESS_LOOKUP_TABLE,
}

data class TransactionSafetyReport(
    val validatorVersion: String,
    val programAllowlistVersion: String,
    val violations: Set<TransactionSafetyViolation>,
) {
    val safe: Boolean = violations.isEmpty()
}

class TransactionSafetyValidator(
    private val parser: UnsignedTransactionParser,
    private val validatorVersion: String,
) {
    init {
        require(validatorVersion.isNotBlank())
    }

    fun validate(
        serializedTransaction: ByteArray,
        intent: TransactionIntent,
    ): TransactionSafetyReport {
        val transaction =
            try {
                parser.parse(serializedTransaction)
            } catch (_: Exception) {
                return report(intent, setOf(TransactionSafetyViolation.DECODE_FAILED))
            }
        val violations = linkedSetOf<TransactionSafetyViolation>()

        fun rejectIf(
            condition: Boolean,
            violation: TransactionSafetyViolation,
        ) {
            if (condition) violations += violation
        }

        rejectIf(
            intent.walletPublicKey !in transaction.requiredSigners,
            TransactionSafetyViolation.EXPECTED_SIGNER_MISSING,
        )
        rejectIf(
            transaction.appControlledSigners.any { it != intent.walletPublicKey },
            TransactionSafetyViolation.UNEXPECTED_APP_SIGNER,
        )
        rejectIf(transaction.inputMint != intent.inputMint, TransactionSafetyViolation.INPUT_MINT_MISMATCH)
        rejectIf(transaction.outputMint != intent.outputMint, TransactionSafetyViolation.OUTPUT_MINT_MISMATCH)
        rejectIf(transaction.recipient != intent.expectedRecipient, TransactionSafetyViolation.RECIPIENT_MISMATCH)
        rejectIf(
            transaction.inputAmount.subtract(intent.inputAmount).abs() > intent.inputAmountTolerance,
            TransactionSafetyViolation.INPUT_AMOUNT_MISMATCH,
        )
        rejectIf(
            transaction.outputAmount.subtract(intent.expectedOutputAmount).abs() > intent.outputAmountTolerance,
            TransactionSafetyViolation.OUTPUT_AMOUNT_MISMATCH,
        )
        rejectIf(
            transaction.totalSolDebit > intent.maximumSolDebit,
            TransactionSafetyViolation.MAXIMUM_SOL_DEBIT_EXCEEDED,
        )
        rejectIf(transaction.priorityFee > intent.maximumPriorityFee, TransactionSafetyViolation.PRIORITY_FEE_EXCEEDED)
        rejectIf(
            transaction.solTransfers.any { it.destination !in intent.expectedSolDestinations },
            TransactionSafetyViolation.UNRELATED_SOL_TRANSFER,
        )
        rejectIf(transaction.authorityChanges.isNotEmpty(), TransactionSafetyViolation.AUTHORITY_CHANGE)
        rejectIf(transaction.delegateApprovals.isNotEmpty(), TransactionSafetyViolation.UNEXPECTED_DELEGATE)
        rejectIf(transaction.closedTokenAccounts.isNotEmpty(), TransactionSafetyViolation.UNRELATED_ACCOUNT_CLOSE)
        rejectIf(
            transaction.invokedProgramIds.any { it !in intent.allowedProgramIds },
            TransactionSafetyViolation.UNKNOWN_PROGRAM,
        )
        rejectIf(
            transaction.computeUnitLimit > intent.maximumComputeUnits,
            TransactionSafetyViolation.COMPUTE_UNIT_LIMIT_EXCEEDED,
        )
        rejectIf(
            transaction.computeUnitPriceMicroLamports > intent.maximumComputeUnitPriceMicroLamports,
            TransactionSafetyViolation.COMPUTE_UNIT_PRICE_EXCEEDED,
        )
        rejectIf(!transaction.recentBlockhashUsable, TransactionSafetyViolation.BLOCKHASH_NOT_USABLE)
        rejectIf(
            transaction.format == TransactionFormat.V0 && !transaction.addressLookupTablesResolved,
            TransactionSafetyViolation.UNRESOLVED_ADDRESS_LOOKUP_TABLE,
        )
        return report(intent, violations)
    }

    private fun report(
        intent: TransactionIntent,
        violations: Set<TransactionSafetyViolation>,
    ) = TransactionSafetyReport(
        validatorVersion = validatorVersion,
        programAllowlistVersion = intent.programAllowlistVersion,
        violations = violations,
    )
}
