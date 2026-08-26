package com.finnvek.startex.wallet

import com.finnvek.startex.network.HeliusRpcProvider
import com.finnvek.startex.network.ProviderError
import com.finnvek.startex.network.ProviderId
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

    @Test
    fun `missing signature remains pending until a later finalized status`() =
        runTest {
            val provider =
                ConfirmationRpcProvider(
                    statuses = listOf(ProviderResult.Success(null), status("finalized", slot = 404)),
                    blockHeights = listOf(ProviderResult.Success(499)),
                )
            var waits = 0

            val result =
                ManualTransferConfirmationTracker(provider) { waits++ }
                    .track(SIGNATURE, lastValidBlockHeight = 500) { true }

            assertEquals(ManualTransferTrackingResult.Finalized, result)
            assertEquals(1, waits)
            assertEquals(1, provider.blockHeightRequests)
        }

    @Test
    fun `missing signature expires after the last valid block height`() =
        runTest {
            val provider =
                ConfirmationRpcProvider(
                    statuses = listOf(ProviderResult.Success(null)),
                    blockHeights = listOf(ProviderResult.Success(501)),
                )

            val result =
                ManualTransferConfirmationTracker(provider) {}
                    .track(SIGNATURE, lastValidBlockHeight = 500) { true }

            assertEquals(ManualTransferTrackingResult.Expired, result)
        }

    @Test
    fun `provider failure terminates tracking without waiting`() =
        runTest {
            val error = ProviderError.NetworkUnavailable(ProviderId.HELIUS)
            val provider = ConfirmationRpcProvider(listOf(ProviderResult.Failure(error)))
            var waits = 0

            val result =
                ManualTransferConfirmationTracker(provider) { waits++ }
                    .track(SIGNATURE, lastValidBlockHeight = 500) { true }

            assertEquals(ManualTransferTrackingResult.ProviderFailure(error), result)
            assertEquals(0, waits)
        }

    @Test
    fun `failed progress persistence stops before confirmation is advanced`() =
        runTest {
            val provider = ConfirmationRpcProvider(listOf(status("confirmed", slot = 505)))

            val result =
                ManualTransferConfirmationTracker(provider) {}
                    .track(SIGNATURE, lastValidBlockHeight = 500) { false }

            assertEquals(ManualTransferTrackingResult.PersistenceFailed, result)
        }

    @Test
    fun `already finalized transfer completes without an RPC request`() =
        runTest {
            val provider = ConfirmationRpcProvider(emptyList())

            val result =
                ManualTransferConfirmationTracker(provider) {}
                    .track(
                        signature = SIGNATURE,
                        lastValidBlockHeight = 500,
                        initialStatus = ManualTransferStatus.FINALIZED,
                    ) { true }

            assertEquals(ManualTransferTrackingResult.Finalized, result)
            assertEquals(0, provider.signatureStatusRequests)
        }

    @Test
    fun `missing status after confirmation is rejected as a regression`() =
        runTest {
            val provider = ConfirmationRpcProvider(listOf(ProviderResult.Success(null)))

            val result =
                ManualTransferConfirmationTracker(provider) {}
                    .track(
                        signature = SIGNATURE,
                        lastValidBlockHeight = 500,
                        initialStatus = ManualTransferStatus.CONFIRMED,
                    ) { true }

            assertInvalidResponse(result, "signatureStatus.regressed")
            assertEquals(0, provider.blockHeightRequests)
        }

    @Test
    fun `missing status propagates block height provider failure`() =
        runTest {
            val error = ProviderError.NetworkUnavailable(ProviderId.HELIUS)
            val provider =
                ConfirmationRpcProvider(
                    statuses = listOf(ProviderResult.Success(null)),
                    blockHeights = listOf(ProviderResult.Failure(error)),
                )

            val result =
                ManualTransferConfirmationTracker(provider) {}
                    .track(SIGNATURE, lastValidBlockHeight = 500) { true }

            assertEquals(ManualTransferTrackingResult.ProviderFailure(error), result)
        }

    @Test
    fun `invalid provider values terminate tracking`() =
        runTest {
            val invalidValues =
                listOf(
                    ConfirmationRpcProvider(
                        statuses = listOf(ProviderResult.Success(null)),
                        blockHeights = listOf(ProviderResult.Success(-1)),
                    ) to "blockHeight",
                    ConfirmationRpcProvider(listOf(status("confirmed", slot = -1))) to "signatureStatus.slot",
                    ConfirmationRpcProvider(listOf(status("unknown", slot = 606))) to "confirmationStatus",
                )

            invalidValues.forEach { (provider, field) ->
                val result =
                    ManualTransferConfirmationTracker(provider) {}
                        .track(SIGNATURE, lastValidBlockHeight = 500) { true }

                assertInvalidResponse(result, field)
            }
        }

    private fun assertInvalidResponse(
        result: ManualTransferTrackingResult,
        field: String,
    ) {
        require(result is ManualTransferTrackingResult.ProviderFailure)
        assertEquals(ProviderError.InvalidResponse(ProviderId.HELIUS, field), result.error)
    }

    private class ConfirmationRpcProvider(
        statuses: List<ProviderResult<RpcSignatureStatus?>>,
        blockHeights: List<ProviderResult<Long>> = emptyList(),
    ) : HeliusRpcProvider {
        private val statuses = ArrayDeque(statuses)
        private val blockHeights = ArrayDeque(blockHeights)
        var signatureStatusRequests: Int = 0
            private set
        var blockHeightRequests: Int = 0
            private set

        override suspend fun getBalance(address: String): ProviderResult<RpcBalance> = error("Not used")

        override suspend fun getLatestBlockhash(): ProviderResult<RpcLatestBlockhash> = error("Not used")

        override suspend fun getBlockHeight(): ProviderResult<Long> {
            blockHeightRequests++
            return blockHeights.removeFirst()
        }

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
