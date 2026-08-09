package com.finnvek.startex.trading

import com.finnvek.startex.domain.Lamports
import com.finnvek.startex.domain.toLongExactCompat
import com.solana.transaction.LegacyMessage
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.VersionedMessage
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AddressLookupReference(
    val tableAddress: String,
    val writableIndexes: List<Int>,
    val readOnlyIndexes: List<Int>,
)

data class ResolvedAddressLookup(
    val tableAddress: String,
    val writableAddresses: List<String>,
    val readOnlyAddresses: List<String>,
)

fun interface AddressLookupTableResolver {
    fun resolve(references: List<AddressLookupReference>): List<ResolvedAddressLookup>?
}

fun interface ControlledAccountResolver {
    fun resolveAppControlledSigners(requiredSigners: Set<String>): Set<String>?
}

fun interface RecentBlockhashValidator {
    fun isUsable(blockhash: String): Boolean
}

data class SolanaCompiledInstruction(
    val programId: String,
    val accountAddresses: List<String>,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is SolanaCompiledInstruction &&
                    programId == other.programId &&
                    accountAddresses == other.accountAddresses &&
                    data.contentEquals(other.data)
            )

    override fun hashCode(): Int {
        var result = programId.hashCode()
        result = 31 * result + accountAddresses.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class SolanaTransactionStructure(
    val format: TransactionFormat,
    val requiredSigners: Set<String>,
    val staticAccountAddresses: List<String>,
    val accountAddresses: List<String>,
    val instructions: List<SolanaCompiledInstruction>,
    val recentBlockhash: String,
    val addressLookupReferences: List<AddressLookupReference>,
    val computeUnitLimit: Int,
    val computeUnitPriceMicroLamports: Long,
)

data class ResolvedTransactionSemantics(
    val inputMint: String,
    val outputMint: String,
    val recipient: String,
    val inputAmount: BigInteger,
    val outputAmount: BigInteger,
    val totalSolDebit: Lamports,
    val solTransfers: List<SolTransfer>,
    val authorityChanges: Set<AuthorityChange>,
    val delegateApprovals: Set<String>,
    val closedTokenAccounts: Set<String>,
)

fun interface InstructionSemanticResolver {
    fun resolve(structure: SolanaTransactionStructure): ResolvedTransactionSemantics?
}

class SolanaMobileUnsignedTransactionParser(
    private val addressLookupTableResolver: AddressLookupTableResolver,
    private val controlledAccountResolver: ControlledAccountResolver,
    private val instructionSemanticResolver: InstructionSemanticResolver,
    private val recentBlockhashValidator: RecentBlockhashValidator,
) : UnsignedTransactionParser {
    override fun parse(serializedTransaction: ByteArray): ParsedUnsignedTransaction {
        val transaction = Transaction.from(serializedTransaction)
        val message = transaction.message
        val format = transactionFormat(message)
        val staticAccounts = message.accounts.map { it.address }
        val signatureCount = message.signatureCount.toInt()
        require(signatureCount <= staticAccounts.size) { "Signer count exceeds static account count" }
        require(transaction.signatures.size == signatureCount) { "Signature list does not match message header" }
        val requiredSigners = staticAccounts.take(signatureCount).toSet()
        val lookupReferences = lookupReferences(message)
        val resolvedLookups = resolveLookups(lookupReferences)
        val accountAddresses =
            staticAccounts +
                resolvedLookups.flatMap(ResolvedAddressLookup::writableAddresses) +
                resolvedLookups.flatMap(ResolvedAddressLookup::readOnlyAddresses)
        val instructions = resolveInstructions(message, accountAddresses)
        val computeBudget = parseComputeBudget(instructions)
        val structure =
            SolanaTransactionStructure(
                format = format,
                requiredSigners = requiredSigners,
                staticAccountAddresses = staticAccounts,
                accountAddresses = accountAddresses,
                instructions = instructions,
                recentBlockhash = message.blockhash.address,
                addressLookupReferences = lookupReferences,
                computeUnitLimit = computeBudget.unitLimit,
                computeUnitPriceMicroLamports = computeBudget.unitPriceMicroLamports,
            )
        val semantics =
            requireNotNull(instructionSemanticResolver.resolve(structure)) {
                "Transaction instruction semantics could not be proven"
            }
        val appControlledSigners =
            requireNotNull(
                controlledAccountResolver.resolveAppControlledSigners(requiredSigners),
            ) { "App-controlled signer ownership could not be resolved" }
        require(requiredSigners.containsAll(appControlledSigners)) {
            "Controlled account resolver returned a non-signer"
        }
        return ParsedUnsignedTransaction(
            format = format,
            requiredSigners = requiredSigners,
            appControlledSigners = appControlledSigners,
            inputMint = semantics.inputMint,
            outputMint = semantics.outputMint,
            recipient = semantics.recipient,
            inputAmount = semantics.inputAmount,
            outputAmount = semantics.outputAmount,
            totalSolDebit = semantics.totalSolDebit,
            priorityFee = computeBudget.priorityFee,
            solTransfers = semantics.solTransfers,
            authorityChanges = semantics.authorityChanges,
            delegateApprovals = semantics.delegateApprovals,
            closedTokenAccounts = semantics.closedTokenAccounts,
            invokedProgramIds = instructions.mapTo(linkedSetOf(), SolanaCompiledInstruction::programId),
            computeUnitLimit = computeBudget.unitLimit,
            computeUnitPriceMicroLamports = computeBudget.unitPriceMicroLamports,
            recentBlockhashUsable = recentBlockhashValidator.isUsable(message.blockhash.address),
            addressLookupTables = lookupReferences.map(AddressLookupReference::tableAddress),
            addressLookupTablesResolved = true,
        )
    }

    private fun transactionFormat(message: Message): TransactionFormat =
        when (message) {
            is LegacyMessage -> {
                TransactionFormat.LEGACY
            }

            is VersionedMessage -> {
                require(message.version.toInt() == 0) { "Unsupported transaction version: ${message.version}" }
                TransactionFormat.V0
            }
        }

    private fun lookupReferences(message: Message): List<AddressLookupReference> =
        when (message) {
            is LegacyMessage -> {
                emptyList()
            }

            is VersionedMessage -> {
                message.addressTableLookups.map { lookup ->
                    AddressLookupReference(
                        tableAddress = lookup.account.address,
                        writableIndexes = lookup.writableIndexes.map { it.toInt() },
                        readOnlyIndexes = lookup.readOnlyIndexes.map { it.toInt() },
                    )
                }
            }
        }

    private fun resolveLookups(references: List<AddressLookupReference>): List<ResolvedAddressLookup> {
        if (references.isEmpty()) return emptyList()
        val resolved =
            requireNotNull(addressLookupTableResolver.resolve(references)) {
                "Address lookup tables could not be resolved"
            }
        require(resolved.size == references.size) { "Address lookup table count mismatch" }
        references.zip(resolved).forEach { (reference, lookup) ->
            require(lookup.tableAddress == reference.tableAddress) { "Resolved lookup table address mismatch" }
            require(lookup.writableAddresses.size == reference.writableIndexes.size) {
                "Resolved writable lookup count mismatch"
            }
            require(lookup.readOnlyAddresses.size == reference.readOnlyIndexes.size) {
                "Resolved read-only lookup count mismatch"
            }
        }
        return resolved
    }

    private fun resolveInstructions(
        message: Message,
        accountAddresses: List<String>,
    ): List<SolanaCompiledInstruction> =
        message.instructions.map { instruction ->
            val programIndex = instruction.programIdIndex.toInt()
            val programId =
                accountAddresses.getOrNull(programIndex)
                    ?: throw IllegalArgumentException("Program index is outside resolved accounts")
            val instructionAccounts =
                instruction.accountIndices.map { rawIndex ->
                    val index = rawIndex.toInt() and UNSIGNED_BYTE_MASK
                    accountAddresses.getOrNull(index)
                        ?: throw IllegalArgumentException("Instruction account index is outside resolved accounts")
                }
            SolanaCompiledInstruction(programId, instructionAccounts, instruction.data.copyOf())
        }

    private fun parseComputeBudget(instructions: List<SolanaCompiledInstruction>): ComputeBudget {
        var unitLimit: Int? = null
        var unitPrice: Long? = null
        instructions.filter { it.programId == COMPUTE_BUDGET_PROGRAM_ID }.forEach { instruction ->
            val discriminator =
                instruction.data.firstOrNull()?.toInt()
                    ?: throw IllegalArgumentException("Empty compute-budget instruction")
            when (discriminator) {
                SET_COMPUTE_UNIT_LIMIT -> {
                    require(unitLimit == null) { "Duplicate compute-unit limit" }
                    require(instruction.data.size == COMPUTE_UNIT_LIMIT_SIZE) { "Invalid compute-unit limit" }
                    unitLimit = readUnsignedInt(instruction.data)
                }

                SET_COMPUTE_UNIT_PRICE -> {
                    require(unitPrice == null) { "Duplicate compute-unit price" }
                    require(instruction.data.size == COMPUTE_UNIT_PRICE_SIZE) { "Invalid compute-unit price" }
                    unitPrice = readUnsignedLong(instruction.data)
                }

                else -> {
                    throw IllegalArgumentException("Unsupported compute-budget instruction")
                }
            }
        }
        val resolvedLimit = unitLimit ?: DEFAULT_COMPUTE_UNIT_LIMIT
        val resolvedPrice = unitPrice ?: 0L
        val priorityFee =
            BigInteger
                .valueOf(resolvedLimit.toLong())
                .multiply(BigInteger.valueOf(resolvedPrice))
                .add(MICRO_LAMPORTS_PER_LAMPORT.subtract(BigInteger.ONE))
                .divide(MICRO_LAMPORTS_PER_LAMPORT)
                .toLongExactCompat()
        return ComputeBudget(resolvedLimit, resolvedPrice, Lamports.of(priorityFee))
    }

    private fun readUnsignedInt(data: ByteArray): Int {
        val value =
            ByteBuffer
                .wrap(data, 1, Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and UNSIGNED_INT_MASK
        require(value <= Int.MAX_VALUE) { "Compute-unit limit does not fit Int" }
        return value.toInt()
    }

    private fun readUnsignedLong(data: ByteArray): Long {
        val value =
            ByteBuffer
                .wrap(data, 1, Long.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .long
        require(value >= 0) { "Compute-unit price does not fit Long" }
        return value
    }

    private data class ComputeBudget(
        val unitLimit: Int,
        val unitPriceMicroLamports: Long,
        val priorityFee: Lamports,
    )

    private companion object {
        const val COMPUTE_BUDGET_PROGRAM_ID = "ComputeBudget111111111111111111111111111111"
        const val SET_COMPUTE_UNIT_LIMIT = 2
        const val SET_COMPUTE_UNIT_PRICE = 3
        const val COMPUTE_UNIT_LIMIT_SIZE = 5
        const val COMPUTE_UNIT_PRICE_SIZE = 9
        const val DEFAULT_COMPUTE_UNIT_LIMIT = 1_400_000
        const val UNSIGNED_BYTE_MASK = 0xff
        const val UNSIGNED_INT_MASK = 0xffff_ffffL
        val MICRO_LAMPORTS_PER_LAMPORT: BigInteger = BigInteger.valueOf(1_000_000)
    }
}
