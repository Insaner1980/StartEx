package com.finnvek.startex.wallet

import com.finnvek.startex.network.HeliusRpcProvider
import com.finnvek.startex.network.ProviderResult
import com.finnvek.startex.network.RpcAccountInfo
import com.finnvek.startex.network.RpcBalance
import com.finnvek.startex.network.RpcFeeForMessage
import com.finnvek.startex.network.RpcLatestBlockhash
import com.finnvek.startex.network.RpcSignatureStatus
import com.finnvek.startex.network.RpcSimulation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTransferConfirmationTrackerTest {
    @Test
    fun `processed transfer continues polling through confirmed and finalized`() =
        runTest {
            val provider =
                ConfirmationRpcProvider(
                    statuses =
                        listOf(
                            status("processed", slot = 101),
                            status("confirmed", slot = 101),
                            status("finalized", slot = 101),
                        ),
                )
            var waits = 0
            val observed = mutableListOf<ManualTransferConfirmation>()
            val tracker = ManualTransferConfirmationTracker(provider) { waits++ }

            val result =
                tracker.track(
                    signature = SIGNATURE,
                    lastValidBlockHeight = 500,
                    onProgress = { confirmation ->
                        observed += confirmation
                        true
                    },
                )

            assertEquals(ManualTransferTrackingResult.Finalized, result)
            assertEquals(
                listOf(
                    ManualTransferStatus.PROCESSED,
                    ManualTransferStatus.CONFIRMED,
                    ManualTransferStatus.FINALIZED,
                ),
                observed.map(ManualTransferConfirmation::status),
            )
            assertEquals(3, provider.signatureStatusRequests)
            assertEquals(2, waits)
        }

    @Test
    fun `persisted confirmed transfer resumes until finalized`() =
        runTest {
            val provider = ConfirmationRpcProvider(listOf(status("finalized", slot = 202)))
            val observed = mutableListOf<ManualTransferConfirmation>()

            val result =
                ManualTransferConfirmationTracker(provider) {}
                    .track(
                        signature = SIGNATURE,
                        lastValidBlockHeight = 500,
                        initialStatus = ManualTransferStatus.CONFIRMED,
                        onProgress = { confirmation ->
                            observed += confirmation
                            true
                        },
                    )

            assertEquals(ManualTransferTrackingResult.Finalized, result)
            assertEquals(listOf(ManualTransferStatus.FINALIZED), observed.map { it.status })
        }

    @Test
    fun `chain rejection terminates without reporting confirmation`() =
        runTest {
            val provider =
                ConfirmationRpcProvider(
                    listOf(
                        ProviderResult.Success(
                            RpcSignatureStatus(
                                slot = 303,
                                confirmationStatus = "processed",
                                confirmations = 0,
                                hasError = true,
                            ),
                        ),
                    ),
                )
            val observed = mutableListOf<ManualTransferConfirmation>()

            val result =
                ManualTransferConfirmationTracker(provider) {}
                    .track(
                        signature = SIGNATURE,
                        lastValidBlockHeight = 500,
                        onProgress = { confirmation ->
                            observed += confirmation
                            true
                        },
                    )

            assertEquals(ManualTransferTrackingResult.ChainRejected(slot = 303), result)
            assertTrue(observed.isEmpty())
        }

    private class ConfirmationRpcProvider(
        statuses: List<ProviderResult<RpcSignatureStatus?>>,
    ) : HeliusRpcProvider {
        private val statuses = ArrayDeque(statuses)
        var signatureStatusRequests: Int = 0
            private set

        override suspend fun getBalance(address: String): ProviderResult<RpcBalance> = error("Not used")

        override suspend fun getLatestBlockhash(): ProviderResult<RpcLatestBlockhash> = error("Not used")

        override suspend fun getFeeForMessage(messageBase64: String): ProviderResult<RpcFeeForMessage?> = error("Not used")

        override suspend fun getAccountInfo(address: String): ProviderResult<RpcAccountInfo?> = error("Not used")

        override suspend fun simulateTransaction(unsignedTransactionBase64: String): ProviderResult<RpcSimulation> = error("Not used")

        override suspend fun getSignatureStatus(signature: String): ProviderResult<RpcSignatureStatus?> {
            signatureStatusRequests++
            assertEquals(SIGNATURE, signature)
            return statuses.removeFirst()
        }

        override suspend fun sendTransaction(signedTransactionBase64: String): ProviderResult<String> = error("Not used")
    }

    private companion object {
        const val SIGNATURE = "manual-transfer-signature"

        fun status(
            confirmationStatus: String,
            slot: Long,
        ): ProviderResult<RpcSignatureStatus?> =
            ProviderResult.Success(
                RpcSignatureStatus(
                    slot = slot,
                    confirmationStatus = confirmationStatus,
                    confirmations = 1,
                    hasError = false,
                ),
            )
    }
}
