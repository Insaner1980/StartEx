package com.finnvek.startex.wallet

import com.finnvek.startex.network.HeliusRpcProvider
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.RpcLatestBlockhash
import com.finnvek.startex.network.RpcSimulation
import kotlinx.coroutines.CancellationException
import org.sol4k.Base58
import org.sol4k.PublicKey
import org.sol4k.Transaction
import org.sol4k.instruction.TransferInstruction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

interface SolTransferSigner {
    val publicAddress: String

    fun sign(message: ByteArray): ByteArray
}

enum class SolTransferFailureReason {
    INVALID_SOURCE,
    INVALID_DESTINATION,
    INVALID_AMOUNT,
    INVALID_FEE_CAP,
    INVALID_RESERVE,
    DESTINATION_LOOKUP_FAILED,
    EXECUTABLE_DESTINATION,
    NON_SYSTEM_DESTINATION,
    BALANCE_LOOKUP_FAILED,
    INSUFFICIENT_BALANCE,
    BLOCKHASH_LOOKUP_FAILED,
    FEE_LOOKUP_FAILED,
    FEE_CAP_EXCEEDED,
    BLOCKHASH_EXPIRED,
    FEE_CHANGED,
    TRANSACTION_CONSTRUCTION_FAILED,
    SIMULATION_FAILED,
    SIMULATION_REJECTED,
    SIGNER_MISMATCH,
    SIGNING_FAILED,
    SUBMISSION_REJECTED,
    WRITE_AHEAD_FAILED,
    PREPARED_TRANSFER_CONSUMED,
}

data class SignedSolTransfer(
    val signature: String,
    val serializedHash: String,
    val idempotencyKey: String,
    val lastValidBlockHeight: Long,
)

fun interface SignedTransferWriteAhead {
    suspend fun persist(transfer: SignedSolTransfer): Boolean
}

sealed interface SolTransferResult {
    data class Prepared(
        val transfer: PreparedSolTransfer,
    ) : SolTransferResult

    data class Submitted(
        val signature: String,
        val serializedHash: String,
        val idempotencyKey: String,
        val lastValidBlockHeight: Long,
    ) : SolTransferResult

    data class Uncertain(
        val localSignature: String,
        val serializedHash: String,
        val idempotencyKey: String,
        val lastValidBlockHeight: Long,
        val providerError: ProviderError?,
    ) : SolTransferResult

    data class Failure(
        val reason: SolTransferFailureReason,
        val providerError: ProviderError? = null,
    ) : SolTransferResult
}

class PreparedSolTransfer internal constructor(
    val sourceAddress: String,
    val destinationAddress: String,
    val amountLamports: Long,
    val recentBlockhash: String,
    val lastValidBlockHeight: Long,
    val estimatedFeeLamports: Long,
    val feeCapLamports: Long,
    val reserveLamports: Long,
    val simulatedTransactionBase64: String,
    val idempotencyKey: String,
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(): Boolean = consumed.compareAndSet(false, true)
}

class WalletTransferCoordinator(
    private val rpcProvider: HeliusRpcProvider,
    private val addressValidator: SolanaAddressValidator = SolanaAddressValidator(),
) {
    suspend fun prepare(
        sourceAddress: String,
        destinationAddress: String,
        amountLamports: Long,
        feeCapLamports: Long,
        reserveLamports: Long,
    ): SolTransferResult {
        val source =
            addressValidator.normalize(sourceAddress)
                ?: return failure(SolTransferFailureReason.INVALID_SOURCE)
        val destination =
            addressValidator.normalize(destinationAddress)
                ?: return failure(SolTransferFailureReason.INVALID_DESTINATION)
        if (amountLamports <= 0) return failure(SolTransferFailureReason.INVALID_AMOUNT)
        if (feeCapLamports < 0) return failure(SolTransferFailureReason.INVALID_FEE_CAP)
        if (reserveLamports < 0) return failure(SolTransferFailureReason.INVALID_RESERVE)

        validateDestination(destination)?.let { return it }

        val latestBlockhash =
            when (val result = rpcProvider.getLatestBlockhash()) {
                is ProviderResult.Failure -> {
                    return failure(SolTransferFailureReason.BLOCKHASH_LOOKUP_FAILED, result.error)
                }

                is ProviderResult.Success -> {
                    result.value
                }
            }
        if (
            addressValidator.normalize(latestBlockhash.blockhash) != latestBlockhash.blockhash ||
            latestBlockhash.lastValidBlockHeight < 0 ||
            latestBlockhash.slot < 0
        ) {
            return failure(SolTransferFailureReason.BLOCKHASH_LOOKUP_FAILED)
        }

        return buildAndSimulate(
            source = source,
            destination = destination,
            amountLamports = amountLamports,
            feeCapLamports = feeCapLamports,
            reserveLamports = reserveLamports,
            latestBlockhash = latestBlockhash,
        )
    }

    private suspend fun buildAndSimulate(
        source: String,
        destination: String,
        amountLamports: Long,
        feeCapLamports: Long,
        reserveLamports: Long,
        latestBlockhash: RpcLatestBlockhash,
    ): SolTransferResult {
        val serialized =
            try {
                buildSerializedTransfer(
                    source = source,
                    destination = destination,
                    amountLamports = amountLamports,
                    blockhash = latestBlockhash.blockhash,
                    signature = ByteArray(SIGNATURE_LENGTH),
                )
            } catch (_: RuntimeException) {
                return failure(SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED)
            }
        return try {
            val message = extractLegacyMessage(serialized)
            try {
                // Preparation and authenticated send independently repeat safety checks by design.
                // CPD-OFF
                val feeMessageBase64 = Base64.getEncoder().encodeToString(message)
                val fee =
                    when (val result = rpcProvider.getFeeForMessage(feeMessageBase64)) {
                        is ProviderResult.Failure -> {
                            return failure(SolTransferFailureReason.FEE_LOOKUP_FAILED, result.error)
                        }

                        is ProviderResult.Success -> {
                            result.value
                        }
                    }
                if (fee == null || fee.lamports < 0 || fee.slot < 0) {
                    return failure(SolTransferFailureReason.FEE_LOOKUP_FAILED)
                }
                if (fee.lamports > feeCapLamports) {
                    return failure(SolTransferFailureReason.FEE_CAP_EXCEEDED)
                }
                validateBalance(source, amountLamports, fee.lamports, reserveLamports)?.let { return it }

                val simulatedTransactionBase64 = Base64.getEncoder().encodeToString(serialized)
                when (val simulation = rpcProvider.simulateTransaction(simulatedTransactionBase64)) {
                    is ProviderResult.Failure -> {
                        failure(SolTransferFailureReason.SIMULATION_FAILED, simulation.error)
                    }

                    is ProviderResult.Success -> {
                        when (val value = simulation.value) {
                            is RpcSimulation.Rejected -> {
                                failure(SolTransferFailureReason.SIMULATION_REJECTED)
                            }

                            is RpcSimulation.Succeeded -> {
                                if (value.slot < 0 || value.unitsConsumed?.let { it < 0 } == true) {
                                    failure(SolTransferFailureReason.SIMULATION_FAILED)
                                } else {
                                    SolTransferResult.Prepared(
                                        PreparedSolTransfer(
                                            sourceAddress = source,
                                            destinationAddress = destination,
                                            amountLamports = amountLamports,
                                            recentBlockhash = latestBlockhash.blockhash,
                                            lastValidBlockHeight = latestBlockhash.lastValidBlockHeight,
                                            estimatedFeeLamports = fee.lamports,
                                            feeCapLamports = feeCapLamports,
                                            reserveLamports = reserveLamports,
                                            simulatedTransactionBase64 = simulatedTransactionBase64,
                                            idempotencyKey = sha256Hex(message),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                // CPD-ON
            } finally {
                message.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            failure(SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED)
        } finally {
            serialized.fill(0)
        }
    }

    private suspend fun validateDestination(destination: String): SolTransferResult.Failure? {
        val accountInfo =
            when (val result = rpcProvider.getAccountInfo(destination)) {
                is ProviderResult.Failure -> {
                    return failure(SolTransferFailureReason.DESTINATION_LOOKUP_FAILED, result.error)
                }

                is ProviderResult.Success -> {
                    result.value
                }
            } ?: return null
        if (accountInfo.slot < 0 || accountInfo.lamports < 0) {
            return failure(SolTransferFailureReason.DESTINATION_LOOKUP_FAILED)
        }
        if (accountInfo.executable) {
            return failure(SolTransferFailureReason.EXECUTABLE_DESTINATION)
        }
        if (accountInfo.owner != SYSTEM_PROGRAM_ADDRESS) {
            return failure(SolTransferFailureReason.NON_SYSTEM_DESTINATION)
        }
        return null
    }

    private suspend fun validateBalance(
        source: String,
        amountLamports: Long,
        estimatedFeeLamports: Long,
        reserveLamports: Long,
    ): SolTransferResult.Failure? {
        val balance =
            when (val result = rpcProvider.getBalance(source)) {
                is ProviderResult.Failure -> {
                    return failure(SolTransferFailureReason.BALANCE_LOOKUP_FAILED, result.error)
                }

                is ProviderResult.Success -> {
                    result.value
                }
            }
        if (balance.lamports < 0 || balance.slot < 0) {
            return failure(SolTransferFailureReason.BALANCE_LOOKUP_FAILED)
        }
        val requiredBalance =
            try {
                Math.addExact(Math.addExact(amountLamports, estimatedFeeLamports), reserveLamports)
            } catch (_: ArithmeticException) {
                return failure(SolTransferFailureReason.INSUFFICIENT_BALANCE)
            }
        if (balance.lamports < requiredBalance) {
            return failure(SolTransferFailureReason.INSUFFICIENT_BALANCE)
        }
        return null
    }

    suspend fun submit(
        prepared: PreparedSolTransfer,
        signer: SolTransferSigner,
        writeAhead: SignedTransferWriteAhead,
    ): SolTransferResult {
        if (addressValidator.normalize(signer.publicAddress) != prepared.sourceAddress) {
            return failure(SolTransferFailureReason.SIGNER_MISMATCH)
        }
        if (!prepared.consume()) {
            return failure(SolTransferFailureReason.PREPARED_TRANSFER_CONSUMED)
        }

        revalidate(prepared)?.let { return it }

        val simulated =
            try {
                Base64.getDecoder().decode(prepared.simulatedTransactionBase64)
            } catch (_: IllegalArgumentException) {
                return failure(SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED)
            }
        return try {
            val message =
                try {
                    extractLegacyMessage(simulated)
                } catch (_: RuntimeException) {
                    return failure(SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED)
                }
            try {
                val signature =
                    try {
                        signer.sign(message)
                    } catch (_: RuntimeException) {
                        return failure(SolTransferFailureReason.SIGNING_FAILED)
                    }
                try {
                    if (signature.size != SIGNATURE_LENGTH) {
                        return failure(SolTransferFailureReason.SIGNING_FAILED)
                    }
                    val localSignature = Base58.encode(signature)
                    val signed =
                        try {
                            buildSerializedTransfer(
                                source = prepared.sourceAddress,
                                destination = prepared.destinationAddress,
                                amountLamports = prepared.amountLamports,
                                blockhash = prepared.recentBlockhash,
                                signature = signature,
                            )
                        } catch (_: RuntimeException) {
                            return failure(SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED)
                        }
                    try {
                        val signedMessage = extractLegacyMessage(signed)
                        try {
                            if (!signedMessage.contentEquals(message)) {
                                return failure(SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED)
                            }
                        } finally {
                            signedMessage.fill(0)
                        }
                        val serializedHash = sha256Hex(signed)
                        val signedTransfer =
                            SignedSolTransfer(
                                signature = localSignature,
                                serializedHash = serializedHash,
                                idempotencyKey = prepared.idempotencyKey,
                                lastValidBlockHeight = prepared.lastValidBlockHeight,
                            )
                        val persisted =
                            try {
                                writeAhead.persist(signedTransfer)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: RuntimeException) {
                                false
                            }
                        if (!persisted) {
                            return failure(SolTransferFailureReason.WRITE_AHEAD_FAILED)
                        }
                        val signedTransactionBase64 = Base64.getEncoder().encodeToString(signed)
                        return submitSigned(prepared, localSignature, serializedHash, signedTransactionBase64)
                    } finally {
                        signed.fill(0)
                    }
                } finally {
                    signature.fill(0)
                }
            } finally {
                message.fill(0)
            }
        } finally {
            simulated.fill(0)
        }
    }

    private suspend fun revalidate(prepared: PreparedSolTransfer): SolTransferResult.Failure? {
        validateDestination(prepared.destinationAddress)?.let { return it }
        revalidateBlockHeight(prepared.lastValidBlockHeight)?.let { return it }
        val feeMessageBase64 =
            try {
                messageBase64(prepared)
            } catch (_: RuntimeException) {
                return failure(SolTransferFailureReason.TRANSACTION_CONSTRUCTION_FAILED)
            }
        revalidateFee(prepared, feeMessageBase64)?.let { return it }
        validateBalance(
            prepared.sourceAddress,
            prepared.amountLamports,
            prepared.estimatedFeeLamports,
            prepared.reserveLamports,
        )?.let { return it }
        return revalidateSimulation(prepared.simulatedTransactionBase64)
    }

    private suspend fun revalidateBlockHeight(lastValidBlockHeight: Long): SolTransferResult.Failure? =
        when (val blockHeight = rpcProvider.getBlockHeight()) {
            is ProviderResult.Failure -> {
                failure(SolTransferFailureReason.BLOCKHASH_LOOKUP_FAILED, blockHeight.error)
            }

            is ProviderResult.Success -> {
                when {
                    blockHeight.value < 0 -> failure(SolTransferFailureReason.BLOCKHASH_LOOKUP_FAILED)
                    blockHeight.value > lastValidBlockHeight -> failure(SolTransferFailureReason.BLOCKHASH_EXPIRED)
                    else -> null
                }
            }
        }

    private suspend fun revalidateFee(
        prepared: PreparedSolTransfer,
        feeMessageBase64: String,
    ): SolTransferResult.Failure? =
        when (val result = rpcProvider.getFeeForMessage(feeMessageBase64)) {
            is ProviderResult.Failure -> {
                failure(SolTransferFailureReason.FEE_LOOKUP_FAILED, result.error)
            }

            is ProviderResult.Success -> {
                when {
                    result.value == null || result.value.lamports < 0 || result.value.slot < 0 -> {
                        failure(SolTransferFailureReason.FEE_LOOKUP_FAILED)
                    }

                    result.value.lamports > prepared.feeCapLamports -> {
                        failure(SolTransferFailureReason.FEE_CAP_EXCEEDED)
                    }

                    result.value.lamports != prepared.estimatedFeeLamports -> {
                        failure(SolTransferFailureReason.FEE_CHANGED)
                    }

                    else -> {
                        null
                    }
                }
            }
        }

    private suspend fun revalidateSimulation(simulatedTransactionBase64: String): SolTransferResult.Failure? =
        when (val simulation = rpcProvider.simulateTransaction(simulatedTransactionBase64)) {
            is ProviderResult.Failure -> {
                failure(SolTransferFailureReason.SIMULATION_FAILED, simulation.error)
            }

            is ProviderResult.Success -> {
                when (val value = simulation.value) {
                    is RpcSimulation.Rejected -> {
                        failure(SolTransferFailureReason.SIMULATION_REJECTED)
                    }

                    is RpcSimulation.Succeeded -> {
                        if (value.slot < 0 || value.unitsConsumed?.let { it < 0 } == true) {
                            failure(SolTransferFailureReason.SIMULATION_FAILED)
                        } else {
                            null
                        }
                    }
                }
            }
        }

    private fun messageBase64(prepared: PreparedSolTransfer): String {
        val serialized = Base64.getDecoder().decode(prepared.simulatedTransactionBase64)
        return try {
            val message = extractLegacyMessage(serialized)
            try {
                Base64.getEncoder().encodeToString(message)
            } finally {
                message.fill(0)
            }
        } finally {
            serialized.fill(0)
        }
    }

    private suspend fun submitSigned(
        prepared: PreparedSolTransfer,
        localSignature: String,
        serializedHash: String,
        signedTransactionBase64: String,
    ): SolTransferResult =
        when (val submission = rpcProvider.sendTransaction(signedTransactionBase64)) {
            is ProviderResult.Success -> {
                if (submission.value == localSignature) {
                    SolTransferResult.Submitted(
                        signature = localSignature,
                        serializedHash = serializedHash,
                        idempotencyKey = prepared.idempotencyKey,
                        lastValidBlockHeight = prepared.lastValidBlockHeight,
                    )
                } else {
                    uncertain(prepared, localSignature, serializedHash, providerError = null)
                }
            }

            is ProviderResult.Failure -> {
                if (submission.error.leavesSubmissionUncertain()) {
                    uncertain(prepared, localSignature, serializedHash, submission.error)
                } else {
                    failure(SolTransferFailureReason.SUBMISSION_REJECTED, submission.error)
                }
            }
        }

    private fun uncertain(
        prepared: PreparedSolTransfer,
        localSignature: String,
        serializedHash: String,
        providerError: ProviderError?,
    ) = SolTransferResult.Uncertain(
        localSignature = localSignature,
        serializedHash = serializedHash,
        idempotencyKey = prepared.idempotencyKey,
        lastValidBlockHeight = prepared.lastValidBlockHeight,
        providerError = providerError,
    )

    private fun failure(
        reason: SolTransferFailureReason,
        providerError: ProviderError? = null,
    ) = SolTransferResult.Failure(reason, providerError)

    private companion object {
        const val SYSTEM_PROGRAM_ADDRESS = "11111111111111111111111111111111"
        const val SIGNATURE_LENGTH = 64
        const val LEGACY_SIGNATURE_PREFIX_LENGTH = 1
        const val LEGACY_MESSAGE_OFFSET = LEGACY_SIGNATURE_PREFIX_LENGTH + SIGNATURE_LENGTH

        fun buildSerializedTransfer(
            source: String,
            destination: String,
            amountLamports: Long,
            blockhash: String,
            signature: ByteArray,
        ): ByteArray {
            require(signature.size == SIGNATURE_LENGTH)
            val sourceKey = PublicKey(source)
            val transaction =
                Transaction(
                    blockhash,
                    TransferInstruction(sourceKey, PublicKey(destination), amountLamports),
                    sourceKey,
                )
            transaction.addSignature(Base58.encode(signature))
            return transaction.serialize()
        }

        fun extractLegacyMessage(serialized: ByteArray): ByteArray {
            require(serialized.size > LEGACY_MESSAGE_OFFSET)
            require(serialized[0].toInt() == 1) { "Transfer must contain one signature" }
            return serialized.copyOfRange(LEGACY_MESSAGE_OFFSET, serialized.size)
        }

        fun sha256Hex(value: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value)
            return try {
                digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            } finally {
                digest.fill(0)
            }
        }
    }
}

private fun ProviderError.leavesSubmissionUncertain(): Boolean =
    when (this) {
        is ProviderError.SubmissionUncertain,
        is ProviderError.NetworkUnavailable,
        is ProviderError.ConnectionClosed,
        is ProviderError.InvalidResponse,
        -> true

        is ProviderError.HttpFailure -> statusCode >= 500

        else -> false
    }
