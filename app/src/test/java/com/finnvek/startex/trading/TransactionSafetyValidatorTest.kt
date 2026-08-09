package com.finnvek.startex.trading

import com.finnvek.startex.domain.Lamports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class TransactionSafetyValidatorTest {
    @Test
    fun `accepts inspected legacy and v0 transactions that exactly match the intent`() {
        for (format in listOf(TransactionFormat.LEGACY, TransactionFormat.V0)) {
            val transaction =
                safeTransaction().copy(
                    format = format,
                    addressLookupTables = if (format == TransactionFormat.V0) listOf("alt-1") else emptyList(),
                    addressLookupTablesResolved = true,
                )
            val report = validator(transaction).validate(byteArrayOf(1), intent())

            assertTrue(report.safe)
            assertEquals("validator-v1", report.validatorVersion)
            assertEquals(emptySet<TransactionSafetyViolation>(), report.violations)
        }
    }

    @Test
    fun `rejects malicious authority signer program transfer and compute changes together`() {
        val malicious =
            safeTransaction().copy(
                appControlledSigners = setOf(WALLET, "unexpected-app-wallet"),
                inputMint = "wrong-input",
                totalSolDebit = Lamports.of(60_000_001),
                priorityFee = Lamports.of(1_000_001),
                solTransfers = listOf(SolTransfer("attacker", Lamports.of(1_000_000))),
                authorityChanges = setOf(AuthorityChange.WALLET_AUTHORITY),
                delegateApprovals = setOf("attacker"),
                closedTokenAccounts = setOf("unrelated-account"),
                invokedProgramIds = setOf("swap-program", "unknown-program"),
                computeUnitLimit = 400_001,
                computeUnitPriceMicroLamports = 5_001,
                recentBlockhashUsable = false,
            )

        val report = validator(malicious).validate(byteArrayOf(2), intent())

        assertFalse(report.safe)
        assertEquals(
            setOf(
                TransactionSafetyViolation.UNEXPECTED_APP_SIGNER,
                TransactionSafetyViolation.INPUT_MINT_MISMATCH,
                TransactionSafetyViolation.MAXIMUM_SOL_DEBIT_EXCEEDED,
                TransactionSafetyViolation.PRIORITY_FEE_EXCEEDED,
                TransactionSafetyViolation.UNRELATED_SOL_TRANSFER,
                TransactionSafetyViolation.AUTHORITY_CHANGE,
                TransactionSafetyViolation.UNEXPECTED_DELEGATE,
                TransactionSafetyViolation.UNRELATED_ACCOUNT_CLOSE,
                TransactionSafetyViolation.UNKNOWN_PROGRAM,
                TransactionSafetyViolation.COMPUTE_UNIT_LIMIT_EXCEEDED,
                TransactionSafetyViolation.COMPUTE_UNIT_PRICE_EXCEEDED,
                TransactionSafetyViolation.BLOCKHASH_NOT_USABLE,
            ),
            report.violations,
        )
    }

    @Test
    fun `v0 transaction fails closed when address lookup tables are unresolved`() {
        val transaction =
            safeTransaction().copy(
                format = TransactionFormat.V0,
                addressLookupTables = listOf("alt-1"),
                addressLookupTablesResolved = false,
            )

        val report = validator(transaction).validate(byteArrayOf(3), intent())

        assertEquals(setOf(TransactionSafetyViolation.UNRESOLVED_ADDRESS_LOOKUP_TABLE), report.violations)
    }

    @Test
    fun `decode failure is a validation failure and never reaches signing`() {
        val parser = UnsignedTransactionParser { error("malformed transaction") }

        val report = TransactionSafetyValidator(parser, "validator-v1").validate(byteArrayOf(4), intent())

        assertEquals(setOf(TransactionSafetyViolation.DECODE_FAILED), report.violations)
    }

    @Test
    fun `amount outside explicit tolerance fails closed`() {
        val transaction = safeTransaction().copy(inputAmount = BigInteger.valueOf(50_000_002))

        val report = validator(transaction).validate(byteArrayOf(5), intent())

        assertEquals(setOf(TransactionSafetyViolation.INPUT_AMOUNT_MISMATCH), report.violations)
    }

    private fun validator(transaction: ParsedUnsignedTransaction) =
        TransactionSafetyValidator(
            parser = UnsignedTransactionParser { transaction },
            validatorVersion = "validator-v1",
        )

    private fun intent() =
        TransactionIntent(
            walletPublicKey = WALLET,
            inputMint = "SOL",
            outputMint = "token-mint",
            expectedRecipient = WALLET,
            inputAmount = BigInteger.valueOf(50_000_000),
            inputAmountTolerance = BigInteger.ONE,
            expectedOutputAmount = BigInteger.valueOf(900_000),
            outputAmountTolerance = BigInteger.valueOf(10_000),
            maximumSolDebit = Lamports.of(60_000_000),
            maximumPriorityFee = Lamports.of(1_000_000),
            expectedSolDestinations = setOf("swap-vault"),
            allowedProgramIds = setOf("swap-program", "system-program", "compute-budget-program"),
            maximumComputeUnits = 400_000,
            maximumComputeUnitPriceMicroLamports = 5_000,
            programAllowlistVersion = "solana-programs-2026-08",
        )

    private fun safeTransaction() =
        ParsedUnsignedTransaction(
            format = TransactionFormat.LEGACY,
            requiredSigners = setOf(WALLET),
            appControlledSigners = setOf(WALLET),
            inputMint = "SOL",
            outputMint = "token-mint",
            recipient = WALLET,
            inputAmount = BigInteger.valueOf(50_000_000),
            outputAmount = BigInteger.valueOf(900_000),
            totalSolDebit = Lamports.of(55_000_000),
            priorityFee = Lamports.of(500_000),
            solTransfers = listOf(SolTransfer("swap-vault", Lamports.of(50_000_000))),
            authorityChanges = emptySet(),
            delegateApprovals = emptySet(),
            closedTokenAccounts = emptySet(),
            invokedProgramIds = setOf("swap-program", "system-program", "compute-budget-program"),
            computeUnitLimit = 350_000,
            computeUnitPriceMicroLamports = 4_000,
            recentBlockhashUsable = true,
            addressLookupTables = emptyList(),
            addressLookupTablesResolved = true,
        )

    private companion object {
        const val WALLET = "wallet-public-key"
    }
}
