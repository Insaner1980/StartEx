package com.finnvek.startex.trading

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AddressTableLookup
import com.solana.transaction.Instruction
import com.solana.transaction.Transaction
import com.solana.transaction.VersionedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class SolanaMobileUnsignedTransactionParserTest {
    @Test
    fun `parses official Solana Mobile legacy structure without deriving trade semantics from intent`() {
        val semanticResolver = CapturingSemanticResolver(semantics())
        val parser = parser(semanticResolver = semanticResolver)

        val parsed = parser.parse(legacyFixture())

        assertEquals(TransactionFormat.LEGACY, parsed.format)
        assertEquals(setOf(PAYER.address), parsed.requiredSigners)
        assertEquals(setOf(PAYER.address), parsed.appControlledSigners)
        assertEquals(setOf(SWAP_PROGRAM.address), parsed.invokedProgramIds)
        assertEquals(1_400_000, parsed.computeUnitLimit)
        assertEquals(0, parsed.computeUnitPriceMicroLamports)
        assertEquals(com.finnvek.startex.domain.Lamports.ZERO, parsed.priorityFee)
        assertEquals("input-from-semantics", parsed.inputMint)
        assertEquals(1, semanticResolver.lastStructure?.instructions?.size)
    }

    @Test
    fun `resolves v0 ALT accounts before resolving instruction program ids`() {
        val semanticResolver = CapturingSemanticResolver(semantics())
        val parser =
            parser(
                lookupResolver =
                    AddressLookupTableResolver { references ->
                        assertEquals(
                            listOf(AddressLookupReference(ALT_ACCOUNT.address, listOf(0), listOf(1))),
                            references,
                        )
                        listOf(
                            ResolvedAddressLookup(
                                tableAddress = ALT_ACCOUNT.address,
                                writableAddresses = listOf(LOADED_WRITABLE.address),
                                readOnlyAddresses = listOf(SWAP_PROGRAM.address),
                            ),
                        )
                    },
                semanticResolver = semanticResolver,
            )

        val parsed = parser.parse(v0AltFixture())

        assertEquals(TransactionFormat.V0, parsed.format)
        assertEquals(listOf(ALT_ACCOUNT.address), parsed.addressLookupTables)
        assertTrue(parsed.addressLookupTablesResolved)
        assertEquals(setOf(COMPUTE_BUDGET.address, SWAP_PROGRAM.address), parsed.invokedProgramIds)
        assertEquals(
            listOf(PAYER.address, COMPUTE_BUDGET.address, LOADED_WRITABLE.address, SWAP_PROGRAM.address),
            semanticResolver.lastStructure?.accountAddresses,
        )
        assertTrue(TransactionSafetyValidator(parser, "validator-v1").validate(v0AltFixture(), intent()).safe)
    }

    @Test
    fun `missing ALT resolution fails closed as decode failure`() {
        val parser =
            parser(
                lookupResolver = AddressLookupTableResolver { null },
                semanticResolver = CapturingSemanticResolver(semantics()),
            )

        val report = TransactionSafetyValidator(parser, "validator-v1").validate(v0AltFixture(), intent())

        assertEquals(setOf(TransactionSafetyViolation.DECODE_FAILED), report.violations)
    }

    @Test
    fun `missing Jupiter instruction semantics fails closed instead of copying the intent`() {
        val parser = parser(semanticResolver = CapturingSemanticResolver(null))

        val report = TransactionSafetyValidator(parser, "validator-v1").validate(legacyFixture(), intent())

        assertEquals(setOf(TransactionSafetyViolation.DECODE_FAILED), report.violations)
    }

    private fun parser(
        lookupResolver: AddressLookupTableResolver = AddressLookupTableResolver { emptyList() },
        semanticResolver: CapturingSemanticResolver,
    ) = SolanaMobileUnsignedTransactionParser(
        addressLookupTableResolver = lookupResolver,
        controlledAccountResolver =
            ControlledAccountResolver { signers ->
                signers.filterTo(mutableSetOf()) { it == PAYER.address }
            },
        instructionSemanticResolver = semanticResolver,
        recentBlockhashValidator = RecentBlockhashValidator { true },
    )

    private fun legacyFixture(): ByteArray =
        byteArrayOf(1) +
            ByteArray(Transaction.SIGNATURE_LENGTH_BYTES) +
            byteArrayOf(1, 0, 1, 2) +
            PAYER.bytes +
            SWAP_PROGRAM.bytes +
            BLOCKHASH.bytes +
            byteArrayOf(1, 1, 1, 0, 12) +
            "hello world ".encodeToByteArray()

    private fun v0AltFixture(): ByteArray =
        Transaction(
            signatures = listOf(ByteArray(Transaction.SIGNATURE_LENGTH_BYTES)),
            message =
                VersionedMessage(
                    version = 0,
                    signatureCount = 1u,
                    readOnlyAccounts = 0u,
                    readOnlyNonSigners = 1u,
                    accounts = listOf(PAYER, COMPUTE_BUDGET),
                    blockhash = BLOCKHASH,
                    instructions =
                        listOf(
                            Instruction(1u, byteArrayOf(), COMPUTE_LIMIT_DATA),
                            Instruction(1u, byteArrayOf(), COMPUTE_PRICE_DATA),
                            Instruction(3u, byteArrayOf(0, 2), byteArrayOf(42)),
                        ),
                    addressTableLookups =
                        listOf(
                            AddressTableLookup(
                                account = ALT_ACCOUNT,
                                writableIndexes = listOf(0u),
                                readOnlyIndexes = listOf(1u),
                            ),
                        ),
                ),
        ).serialize()

    private fun semantics() =
        ResolvedTransactionSemantics(
            inputMint = "input-from-semantics",
            outputMint = "output-from-semantics",
            recipient = PAYER.address,
            inputAmount = BigInteger.valueOf(50_000_000),
            outputAmount = BigInteger.valueOf(900_000),
            totalSolDebit = LamportsFixture.TOTAL_DEBIT,
            solTransfers = listOf(SolTransfer("swap-vault", LamportsFixture.INPUT)),
            authorityChanges = emptySet(),
            delegateApprovals = emptySet(),
            closedTokenAccounts = emptySet(),
        )

    private fun intent() =
        TransactionIntent(
            walletPublicKey = PAYER.address,
            inputMint = "input-from-semantics",
            outputMint = "output-from-semantics",
            expectedRecipient = PAYER.address,
            inputAmount = BigInteger.valueOf(50_000_000),
            inputAmountTolerance = BigInteger.ZERO,
            expectedOutputAmount = BigInteger.valueOf(900_000),
            outputAmountTolerance = BigInteger.ZERO,
            maximumSolDebit = LamportsFixture.MAXIMUM_DEBIT,
            maximumPriorityFee = LamportsFixture.MAXIMUM_PRIORITY_FEE,
            expectedSolDestinations = setOf("swap-vault"),
            allowedProgramIds = setOf(COMPUTE_BUDGET.address, SWAP_PROGRAM.address),
            maximumComputeUnits = 400_000,
            maximumComputeUnitPriceMicroLamports = 5_000,
            programAllowlistVersion = "solana-programs-2026-08",
        )

    private class CapturingSemanticResolver(
        private val result: ResolvedTransactionSemantics?,
    ) : InstructionSemanticResolver {
        var lastStructure: SolanaTransactionStructure? = null

        override fun resolve(structure: SolanaTransactionStructure): ResolvedTransactionSemantics? {
            lastStructure = structure
            return result
        }
    }

    private object LamportsFixture {
        val INPUT =
            com.finnvek.startex.domain.Lamports
                .of(50_000_000)
        val TOTAL_DEBIT =
            com.finnvek.startex.domain.Lamports
                .of(55_000_000)
        val MAXIMUM_DEBIT =
            com.finnvek.startex.domain.Lamports
                .of(60_000_000)
        val MAXIMUM_PRIORITY_FEE =
            com.finnvek.startex.domain.Lamports
                .of(2_000)
    }

    private companion object {
        val PAYER =
            SolanaPublicKey(
                java.util.Base64
                    .getDecoder()
                    .decode("XJy50755nz75BGthIrxe7XIQ9WkcMxgIOCmqEM30qq4="),
            )
        val COMPUTE_BUDGET = SolanaPublicKey.from("ComputeBudget111111111111111111111111111111")
        val SWAP_PROGRAM = SolanaPublicKey.from("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
        val ALT_ACCOUNT = SolanaPublicKey(ByteArray(32) { 5 })
        val LOADED_WRITABLE = SolanaPublicKey(ByteArray(32) { 6 })
        val BLOCKHASH = SolanaPublicKey(ByteArray(32))
        val COMPUTE_LIMIT_DATA = byteArrayOf(2, 0xE0.toByte(), 0x93.toByte(), 0x04, 0x00)
        val COMPUTE_PRICE_DATA = byteArrayOf(3, 0xA0.toByte(), 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    }
}
