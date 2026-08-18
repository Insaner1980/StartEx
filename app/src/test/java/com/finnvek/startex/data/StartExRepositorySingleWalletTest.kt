package com.finnvek.startex.data

import android.content.Context
import androidx.room.Room
import com.finnvek.startex.data.local.StartExDatabase
import com.finnvek.startex.data.local.WalletProfileEntity
import com.finnvek.startex.data.local.WalletSecretEnvelopeEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartExRepositorySingleWalletTest {
    // CPD-OFF
    private lateinit var database: StartExDatabase
    private lateinit var repository: StartExRepository

    @Before
    fun createDatabase() {
        val context: Context = RuntimeEnvironment.getApplication().applicationContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, StartExDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = StartExRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }
    // CPD-ON

    @Test
    fun `wallet save rejects replacement and additional profile ids`() =
        runBlocking {
            repository.saveWallet(profile(id = 1, address = "wallet-one"), envelope(profileId = 1, updatedAt = 1))

            val replacement =
                runCatching {
                    repository.saveWallet(
                        profile(id = 1, address = "wallet-two"),
                        envelope(profileId = 1, updatedAt = 2),
                    )
                }
            val additionalProfile =
                runCatching {
                    repository.saveWallet(
                        profile(id = 2, address = "wallet-two"),
                        envelope(profileId = 2, updatedAt = 2),
                    )
                }

            assertTrue(replacement.exceptionOrNull() is IllegalArgumentException)
            assertTrue(additionalProfile.exceptionOrNull() is IllegalArgumentException)
            assertEquals("wallet-one", repository.walletProfile()?.publicAddress)
            assertEquals(1L, repository.walletSecretEnvelope()?.updatedAtMillis)
        }

    @Test
    fun `wallet save allows re-encrypting the configured profile`() =
        runBlocking {
            val profile = profile(id = 1, address = "wallet-one")
            repository.saveWallet(profile, envelope(profileId = 1, updatedAt = 1))

            repository.saveWallet(profile, envelope(profileId = 1, updatedAt = 2))

            assertEquals("wallet-one", repository.walletProfile()?.publicAddress)
            assertEquals(2L, repository.walletSecretEnvelope()?.updatedAtMillis)
        }

    private fun profile(
        id: Long,
        address: String,
    ) = WalletProfileEntity(
        id = id,
        publicAddress = address,
        derivationPath = "m/44'/501'/0'/0'",
        createdAtMillis = 1,
        backupConfirmedAtMillis = 1,
    )

    private fun envelope(
        profileId: Long,
        updatedAt: Long,
    ) = WalletSecretEnvelopeEntity(
        walletProfileId = profileId,
        encryptedSecret = byteArrayOf(updatedAt.toByte()),
        secretIv = byteArrayOf(1),
        secretEnvelopeVersion = 1,
        keystoreAccessMode = "BIOMETRIC_EACH_USE",
        updatedAtMillis = updatedAt,
    )
}
