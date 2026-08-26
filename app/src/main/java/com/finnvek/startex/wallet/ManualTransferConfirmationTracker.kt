package com.finnvek.startex.wallet

import com.finnvek.startex.network.HeliusRpcProvider
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.RpcSignatureStatus
import kotlinx.coroutines.delay

enum class ManualTransferStatus {
    SUBMITTED,
    PROCESSED,
    CONFIRMED,
    FINALIZED,
    ;

    companion object {
        internal fun fromRpc(
            value: String?,
            confirmations: Long?,
        ): ManualTransferStatus? =
            when (value?.lowercase()) {
                "processed" -> {
                    PROCESSED
                }

                "confirmed" -> {
                    CONFIRMED
                }

                "finalized" -> {
                    FINALIZED
                }

                null -> {
                    when {
                        confirmations == null -> FINALIZED
                        confirmations == 0L -> PROCESSED
                        confirmations > 0L -> CONFIRMED
                        else -> null
                    }
                }

                else -> {
                    null
                }
            }

        internal fun fromPersisted(value: String): ManualTransferStatus? = entries.firstOrNull { it.name == value }
    }
}

internal data class ManualTransferConfirmation(
    val status: ManualTransferStatus,
    val slot: Long,
    val observedAtMillis: Long,
)

internal sealed interface ManualTransferTrackingResult {
    data object Finalized : ManualTransferTrackingResult

    data class ChainRejected(
        val slot: Long,
    ) : ManualTransferTrackingResult

    data object Expired : ManualTransferTrackingResult

    data class ProviderFailure(
        val error: ProviderError,
    ) : ManualTransferTrackingResult

    data object PersistenceFailed : ManualTransferTrackingResult
}

internal class ManualTransferConfirmationTracker(
    private val rpcProvider: HeliusRpcProvider,
    private val waitForNextPoll: suspend () -> Unit = { delay(CONFIRMATION_POLL_INTERVAL_MILLIS) },
) {
    suspend fun track(
        signature: String,
        lastValidBlockHeight: Long,
        initialStatus: ManualTransferStatus? = null,
        onProgress: suspend (ManualTransferConfirmation) -> Boolean,
    ): ManualTransferTrackingResult {
        require(signature.isNotBlank())
        require(lastValidBlockHeight >= 0)
        if (initialStatus == ManualTransferStatus.FINALIZED) {
            return ManualTransferTrackingResult.Finalized
        }

        var lastStatus = initialStatus
        while (true) {
            val progress = poll(signature, lastValidBlockHeight, lastStatus, onProgress)
            progress.terminalResult?.let { return it }
            lastStatus = progress.lastStatus
            waitForNextPoll()
        }
    }

    private suspend fun poll(
        signature: String,
        lastValidBlockHeight: Long,
        lastStatus: ManualTransferStatus?,
        onProgress: suspend (ManualTransferConfirmation) -> Boolean,
    ): TrackingProgress =
        when (val result = rpcProvider.getSignatureStatus(signature)) {
            is ProviderResult.Failure -> {
                TrackingProgress(lastStatus, ManualTransferTrackingResult.ProviderFailure(result.error))
            }

            is ProviderResult.Success -> {
                val rpcStatus = result.value
                if (rpcStatus == null) {
                    missingStatusProgress(lastValidBlockHeight, lastStatus)
                } else {
                    observedStatusProgress(rpcStatus, result.receivedAtMillis, lastStatus, onProgress)
                }
            }
        }

    private suspend fun missingStatusProgress(
        lastValidBlockHeight: Long,
        lastStatus: ManualTransferStatus?,
    ): TrackingProgress {
        if (lastStatus != null && lastStatus.ordinal > ManualTransferStatus.SUBMITTED.ordinal) {
            return TrackingProgress(lastStatus, invalidProviderResponse("signatureStatus.regressed"))
        }
        return when (val blockHeight = rpcProvider.getBlockHeight()) {
            is ProviderResult.Failure -> {
                TrackingProgress(lastStatus, ManualTransferTrackingResult.ProviderFailure(blockHeight.error))
            }

            is ProviderResult.Success -> {
                when {
                    blockHeight.value < 0 -> {
                        TrackingProgress(lastStatus, invalidProviderResponse("blockHeight"))
                    }

                    blockHeight.value > lastValidBlockHeight -> {
                        TrackingProgress(lastStatus, ManualTransferTrackingResult.Expired)
                    }

                    else -> {
                        TrackingProgress(lastStatus)
                    }
                }
            }
        }
    }

    private suspend fun observedStatusProgress(
        rpcStatus: RpcSignatureStatus,
        receivedAtMillis: Long,
        lastStatus: ManualTransferStatus?,
        onProgress: suspend (ManualTransferConfirmation) -> Boolean,
    ): TrackingProgress {
        if (rpcStatus.slot < 0) {
            return TrackingProgress(lastStatus, invalidProviderResponse("signatureStatus.slot"))
        }
        if (rpcStatus.hasError) {
            return TrackingProgress(lastStatus, ManualTransferTrackingResult.ChainRejected(rpcStatus.slot))
        }
        val observedStatus =
            ManualTransferStatus.fromRpc(rpcStatus.confirmationStatus, rpcStatus.confirmations)
                ?: return TrackingProgress(lastStatus, invalidProviderResponse("confirmationStatus"))
        val nextStatus =
            if (lastStatus == null || observedStatus.ordinal > lastStatus.ordinal) {
                val persisted =
                    onProgress(
                        ManualTransferConfirmation(observedStatus, rpcStatus.slot, receivedAtMillis),
                    )
                if (!persisted) {
                    return TrackingProgress(lastStatus, ManualTransferTrackingResult.PersistenceFailed)
                }
                observedStatus
            } else {
                lastStatus
            }
        val terminal =
            ManualTransferTrackingResult.Finalized.takeIf { nextStatus == ManualTransferStatus.FINALIZED }
        return TrackingProgress(nextStatus, terminal)
    }

    private fun invalidProviderResponse(field: String): ManualTransferTrackingResult.ProviderFailure =
        ManualTransferTrackingResult.ProviderFailure(
            ProviderError.InvalidResponse(ProviderId.HELIUS, field),
        )

    private data class TrackingProgress(
        val lastStatus: ManualTransferStatus?,
        val terminalResult: ManualTransferTrackingResult? = null,
    )

    private companion object {
        const val CONFIRMATION_POLL_INTERVAL_MILLIS = 2_000L
    }
}
