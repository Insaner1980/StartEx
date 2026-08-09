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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Base58
import org.sol4k.PublicKey
import java.util.Base64

class WalletTransferCoordinatorTest {
    @Test
    fun `prepare constructs and simulates a known-good legacy System Program transfer`() =
        runTest {
            val provider = MockHeliusRpcProvider()
            val coordinator = WalletTransferCoordinator(provider)

            val result =
                coordinator.prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertTrue(result is SolTransferResult.Prepared)
            val prepared = (result as SolTransferResult.Prepared).transfer
            assertEquals(provider.simulatedTransactionBase64, prepared.simulatedTransactionBase64)
            assertEquals(BLOCKHASH, prepared.recentBlockhash)
            assertEquals(LAST_VALID_BLOCK_HEIGHT, prepared.lastValidBlockHeight)
            assertEquals(ESTIMATED_FEE, prepared.estimatedFeeLamports)
            assertTrue(prepared.idempotencyKey.isNotBlank())

            val serialized = Base64.getDecoder().decode(prepared.simulatedTransactionBase64)
            assertArrayEquals(
                serialized.copyOfRange(65, serialized.size),
                Base64.getDecoder().decode(checkNotNull(provider.feeMessageBase64)),
            )
            assertEquals(215, serialized.size)
            assertEquals(1, serialized[0].toInt())
            assertArrayEquals(ByteArray(64), serialized.copyOfRange(1, 65))
            assertArrayEquals(byteArrayOf(1, 0, 1, 3), serialized.copyOfRange(65, 69))
            assertArrayEquals(PublicKey(SOURCE).bytes(), serialized.copyOfRange(69, 101))
            assertArrayEquals(PublicKey(DESTINATION).bytes(), serialized.copyOfRange(101, 133))
            assertArrayEquals(ByteArray(32), serialized.copyOfRange(133, 165))
            assertArrayEquals(PublicKey(BLOCKHASH).bytes(), serialized.copyOfRange(165, 197))
            assertArrayEquals(byteArrayOf(1, 2, 2, 0, 1, 12), serialized.copyOfRange(197, 203))
            assertArrayEquals(
                byteArrayOf(2, 0, 0, 0, 0x15, 0xCD.toByte(), 0x5B, 0x07, 0, 0, 0, 0),
                serialized.copyOfRange(203, 215),
            )
        }

    @Test
    fun `invalid base58 destination fails before RPC access`() =
        runTest {
            val provider = MockHeliusRpcProvider()

            val result =
                WalletTransferCoordinator(provider).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = "not-a-solana-address",
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.INVALID_DESTINATION)
            assertTrue(provider.events.isEmpty())
        }

    @Test
    fun `executable destination is rejected before balance lookup`() =
        runTest {
            val provider =
                MockHeliusRpcProvider(
                    accountInfo =
                        RpcAccountInfo(
                            owner = SYSTEM_PROGRAM_ADDRESS,
                            executable = true,
                            lamports = 1,
                            slot = 10,
                        ),
                )

            val result =
                WalletTransferCoordinator(provider).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = PROGRAM_DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.EXECUTABLE_DESTINATION)
            assertEquals(listOf("account"), provider.events)
        }

    @Test
    fun `non-System-owned existing destination is rejected`() =
        runTest {
            val provider =
                MockHeliusRpcProvider(
                    accountInfo =
                        RpcAccountInfo(
                            owner = PublicKey(ByteArray(32) { 9 }).toBase58(),
                            executable = false,
                            lamports = 1,
                            slot = 10,
                        ),
                )

            val result =
                WalletTransferCoordinator(provider).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.NON_SYSTEM_DESTINATION)
        }

    @Test
    fun `balance must cover amount estimated fee and configured reserve`() =
        runTest {
            val requiredBalance = AMOUNT + ESTIMATED_FEE + RESERVE
            val provider = MockHeliusRpcProvider(balanceLamports = requiredBalance - 1)

            val result =
                WalletTransferCoordinator(provider).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.INSUFFICIENT_BALANCE)
            assertEquals(listOf("account", "blockhash", "fee", "balance"), provider.events)
        }

    @Test
    fun `fee estimate above the explicit cap is rejected before balance lookup`() =
        runTest {
            val provider =
                MockHeliusRpcProvider(
                    fee = RpcFeeForMessage(FEE_CAP + 1, slot = 13),
                )

            val result =
                WalletTransferCoordinator(provider).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.FEE_CAP_EXCEEDED)
            assertEquals(listOf("account", "blockhash", "fee"), provider.events)
        }

    @Test
    fun `null fee estimate fails closed`() =
        runTest {
            val result =
                WalletTransferCoordinator(MockHeliusRpcProvider(fee = null)).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.FEE_LOOKUP_FAILED)
        }

    @Test
    fun `negative fee estimate fails closed`() =
        runTest {
            val result =
                WalletTransferCoordinator(
                    MockHeliusRpcProvider(fee = RpcFeeForMessage(-1, slot = 13)),
                ).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.FEE_LOOKUP_FAILED)
        }

    @Test
    fun `simulation rejection prevents a prepared signing boundary`() =
        runTest {
            val provider =
                MockHeliusRpcProvider(
                    simulation = RpcSimulation.Rejected(slot = 20),
                )

            val result =
                WalletTransferCoordinator(provider).prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                )

            assertFailure(result, SolTransferFailureReason.SIMULATION_REJECTED)
            assertEquals(listOf("account", "blockhash", "fee", "balance", "simulate"), provider.events)
        }

    @Test
    fun `signing occurs only after successful simulation and submission happens once`() =
        runTest {
            val events = mutableListOf<String>()
            val provider = MockHeliusRpcProvider(events = events)
            val coordinator = WalletTransferCoordinator(provider)
            val prepared =
                coordinator.prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                ) as SolTransferResult.Prepared
            val signer = RecordingSigner(SOURCE, events)

            val writeAhead =
                SignedTransferWriteAhead {
                    events += "record"
                    true
                }
            val submitted = coordinator.submit(prepared.transfer, signer, writeAhead)
            val duplicate = coordinator.submit(prepared.transfer, signer, writeAhead)

            assertTrue(submitted is SolTransferResult.Submitted)
            assertFailure(duplicate, SolTransferFailureReason.PREPARED_TRANSFER_CONSUMED)
            assertEquals(
                listOf("account", "blockhash", "fee", "balance", "simulate", "sign", "record", "send"),
                events,
            )
            assertEquals(1, signer.signCount)
            assertEquals(1, provider.sendCount)
        }

    @Test
    fun `network uncertainty retains local reconciliation identifiers and blocks resubmission`() =
        runTest {
            val provider =
                MockHeliusRpcProvider(
                    sendFailure = ProviderError.SubmissionUncertain(ProviderId.HELIUS),
                )
            val coordinator = WalletTransferCoordinator(provider)
            val prepared =
                coordinator.prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                ) as SolTransferResult.Prepared
            val signer = RecordingSigner(SOURCE, provider.events)

            val result = coordinator.submit(prepared.transfer, signer, PASSING_WRITE_AHEAD)
            val duplicate = coordinator.submit(prepared.transfer, signer, PASSING_WRITE_AHEAD)

            assertTrue(result is SolTransferResult.Uncertain)
            val uncertain = result as SolTransferResult.Uncertain
            assertTrue(uncertain.localSignature.isNotBlank())
            assertTrue(uncertain.serializedHash.isNotBlank())
            assertEquals(prepared.transfer.idempotencyKey, uncertain.idempotencyKey)
            assertNotEquals(uncertain.serializedHash, uncertain.idempotencyKey)
            assertNotNull(uncertain.providerError)
            assertFailure(duplicate, SolTransferFailureReason.PREPARED_TRANSFER_CONSUMED)
            assertEquals(1, provider.sendCount)
            assertEquals(1, signer.signCount)
        }

    @Test
    fun `write ahead failure prevents broadcast`() =
        runTest {
            val events = mutableListOf<String>()
            val provider = MockHeliusRpcProvider(events = events)
            val coordinator = WalletTransferCoordinator(provider)
            val prepared =
                coordinator.prepare(
                    sourceAddress = SOURCE,
                    destinationAddress = DESTINATION,
                    amountLamports = AMOUNT,
                    feeCapLamports = FEE_CAP,
                    reserveLamports = RESERVE,
                ) as SolTransferResult.Prepared

            val result =
                coordinator.submit(
                    prepared.transfer,
                    RecordingSigner(SOURCE, events),
                    SignedTransferWriteAhead {
                        events += "record"
                        false
                    },
                )

            assertFailure(result, SolTransferFailureReason.WRITE_AHEAD_FAILED)
            assertEquals(0, provider.sendCount)
            assertEquals(
                listOf("account", "blockhash", "fee", "balance", "simulate", "sign", "record"),
                events,
            )
        }

    private fun assertFailure(
        result: SolTransferResult,
        expectedReason: SolTransferFailureReason,
    ) {
        assertTrue(result is SolTransferResult.Failure)
        assertEquals(expectedReason, (result as SolTransferResult.Failure).reason)
    }

    private class RecordingSigner(
        override val publicAddress: String,
        private val events: MutableList<String>,
    ) : SolTransferSigner {
        var signCount: Int = 0
            private set

        override fun sign(message: ByteArray): ByteArray {
            events += "sign"
            signCount++
            assertFalse(message.all { it == 0.toByte() })
            return ByteArray(64) { (it + 1).toByte() }
        }
    }

    private class MockHeliusRpcProvider(
        private val accountInfo: RpcAccountInfo? = null,
        private val balanceLamports: Long = AMOUNT + ESTIMATED_FEE + RESERVE,
        private val fee: RpcFeeForMessage? = RpcFeeForMessage(ESTIMATED_FEE, slot = 13),
        private val simulation: RpcSimulation = RpcSimulation.Succeeded(slot = 20, unitsConsumed = 150),
        private val sendFailure: ProviderError? = null,
        val events: MutableList<String> = mutableListOf(),
    ) : HeliusRpcProvider {
        var simulatedTransactionBase64: String? = null
            private set
        var feeMessageBase64: String? = null
            private set
        var sendCount: Int = 0
            private set

        override suspend fun getBalance(address: String): ProviderResult<RpcBalance> {
            events += "balance"
            return ProviderResult.Success(RpcBalance(balanceLamports, slot = 11))
        }

        override suspend fun getLatestBlockhash(): ProviderResult<RpcLatestBlockhash> {
            events += "blockhash"
            return ProviderResult.Success(
                RpcLatestBlockhash(BLOCKHASH, LAST_VALID_BLOCK_HEIGHT, slot = 12),
            )
        }

        override suspend fun getAccountInfo(address: String): ProviderResult<RpcAccountInfo?> {
            events += "account"
            return ProviderResult.Success(accountInfo)
        }

        override suspend fun getFeeForMessage(messageBase64: String): ProviderResult<RpcFeeForMessage?> {
            events += "fee"
            feeMessageBase64 = messageBase64
            return ProviderResult.Success(fee)
        }

        override suspend fun simulateTransaction(unsignedTransactionBase64: String): ProviderResult<RpcSimulation> {
            events += "simulate"
            simulatedTransactionBase64 = unsignedTransactionBase64
            return ProviderResult.Success(simulation)
        }

        override suspend fun getSignatureStatus(signature: String): ProviderResult<RpcSignatureStatus?> = ProviderResult.Success(null)

        override suspend fun sendTransaction(signedTransactionBase64: String): ProviderResult<String> {
            events += "send"
            sendCount++
            sendFailure?.let { return ProviderResult.Failure(it) }
            val serialized = Base64.getDecoder().decode(signedTransactionBase64)
            val localSignature = Base58.encode(serialized.copyOfRange(1, 65))
            return ProviderResult.Success(localSignature)
        }
    }

    private companion object {
        val PASSING_WRITE_AHEAD = SignedTransferWriteAhead { true }
        const val SOURCE = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        const val PROGRAM_DESTINATION = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
        val DESTINATION: String = PublicKey(ByteArray(32) { 2 }).toBase58()
        val BLOCKHASH: String = PublicKey(ByteArray(32) { 3 }).toBase58()
        const val SYSTEM_PROGRAM_ADDRESS = "11111111111111111111111111111111"
        const val AMOUNT = 123_456_789L
        const val FEE_CAP = 5_000L
        const val ESTIMATED_FEE = 4_000L
        const val RESERVE = 1_000_000L
        const val LAST_VALID_BLOCK_HEIGHT = 500_000L
    }
}
