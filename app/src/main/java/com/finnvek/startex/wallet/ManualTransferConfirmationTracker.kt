package com.finnvek.startex.wallet

import com.finnvek.startex.network.HeliusRpcProvider
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.network.ProviderResult
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
            when (val result = rpcProvider.getSignatureStatus(signature)) {
                is ProviderResult.Failure -> {
                    return ManualTransferTrackingResult.ProviderFailure(result.error)
                }

                is ProviderResult.Success -> {
                    val rpcStatus = result.value
                    if (rpcStatus == null) {
                        if (
                            lastStatus != null &&
                            lastStatus.ordinal > ManualTransferStatus.SUBMITTED.ordinal
                        ) {
                            return invalidProviderResponse("signatureStatus.regressed")
                        }
                        when (val blockHeight = rpcProvider.getBlockHeight()) {
                            is ProviderResult.Failure -> {
                                return ManualTransferTrackingResult.ProviderFailure(blockHeight.error)
                            }

                            is ProviderResult.Success -> {
                                if (blockHeight.value < 0) {
                                    return invalidProviderResponse("blockHeight")
                                }
                                if (blockHeight.value > lastValidBlockHeight) {
                                    return ManualTransferTrackingResult.Expired
                                }
                            }
                        }
                    } else {
                        if (rpcStatus.slot < 0) return invalidProviderResponse("signatureStatus.slot")
                        if (rpcStatus.hasError) {
                            return ManualTransferTrackingResult.ChainRejected(rpcStatus.slot)
                        }
                        val observedStatus =
                            ManualTransferStatus.fromRpc(
                                value = rpcStatus.confirmationStatus,
                                confirmations = rpcStatus.confirmations,
                            )
                                ?: return invalidProviderResponse("confirmationStatus")
                        if (lastStatus == null || observedStatus.ordinal > lastStatus.ordinal) {
                            val persisted =
                                onProgress(
                                    ManualTransferConfirmation(
                                        status = observedStatus,
                                        slot = rpcStatus.slot,
                                        observedAtMillis = result.receivedAtMillis,
                                    ),
                                )
                            if (!persisted) return ManualTransferTrackingResult.PersistenceFailed
                            lastStatus = observedStatus
                        }
                        if (lastStatus == ManualTransferStatus.FINALIZED) {
                            return ManualTransferTrackingResult.Finalized
                        }
                    }
                }
            }
            waitForNextPoll()
        }
    }

    private fun invalidProviderResponse(field: String): ManualTransferTrackingResult.ProviderFailure =
        ManualTransferTrackingResult.ProviderFailure(
            ProviderError.InvalidResponse(ProviderId.HELIUS, field),
        )

    private companion object {
        const val CONFIRMATION_POLL_INTERVAL_MILLIS = 2_000L
    }
}
