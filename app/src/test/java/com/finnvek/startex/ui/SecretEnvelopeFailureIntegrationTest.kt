package com.finnvek.startex.ui

import androidx.lifecycle.viewModelScope
import com.finnvek.startex.R
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.data.local.ProviderCredentialEntity
import com.finnvek.startex.data.local.WalletProfileEntity
import com.finnvek.startex.data.local.WalletSecretEnvelopeEntity
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.security.KeystoreAccessMode
import com.finnvek.startex.security.SecretEnvelopeFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SecretEnvelopeFailureIntegrationTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `corrupted wallet row stays stored and reports recovery guidance`() =
        runTest {
            val application = cleanApplication()
            application.repository.saveWallet(walletProfile(), walletEnvelope(iv = ByteArray(11)))
            val viewModel = StartExViewModel(application, mainDispatcher)
            viewModel.state.first { it.loaded && it.walletAddress == WALLET_ADDRESS }

            viewModel.requestWalletUnlock()
            val state = viewModel.state.first { it.message == R.string.wallet_data_unreadable }

            assertEquals(false, state.walletUnlocked)
            assertNotNull(application.repository.walletSecretEnvelope())
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun `unsupported wallet version stays stored and requests an app update`() =
        runTest {
            val application = cleanApplication()
            application.repository.saveWallet(walletProfile(), walletEnvelope(version = 2))
            val viewModel = StartExViewModel(application, mainDispatcher)
            viewModel.state.first { it.loaded && it.walletAddress == WALLET_ADDRESS }

            viewModel.requestWalletUnlock()
            val state = viewModel.state.first { it.message == R.string.wallet_version_unsupported }

            assertEquals(false, state.walletUnlocked)
            assertNotNull(application.repository.walletSecretEnvelope())
            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    @Test
    fun `corrupted provider row stays stored and remains disabled`() =
        runTest {
            val application = cleanApplication()
            application.repository.saveProviderCredential(
                ProviderCredentialEntity(
                    providerId = ProviderId.HELIUS.name,
                    encryptedApiKey = ByteArray(48),
                    apiKeyIv = ByteArray(11),
                    secretEnvelopeVersion = 1,
                    keystoreAccessMode = KeystoreAccessMode.UNATTENDED.name,
                    updatedAtMillis = 1,
                ),
            )

            val result = application.restoreSessionApiKey(ProviderId.HELIUS)

            assertEquals(false, result.restored)
            assertEquals(SecretEnvelopeFailure.CORRUPTED_ROW, result.failure)
            assertEquals(null, application.sessionApiKeys.apiKeyFor(ProviderId.HELIUS))
            assertNotNull(application.repository.providerCredential(ProviderId.HELIUS.name))
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    private suspend fun cleanApplication(): StartExApplication {
        val application = RuntimeEnvironment.getApplication() as StartExApplication
        withContext(Dispatchers.IO) { application.database.clearAllTables() }
        application.settings.setDemoMode(false)
        return application
    }

    private fun walletProfile() =
        WalletProfileEntity(
            publicAddress = WALLET_ADDRESS,
            derivationPath = "m/44'/501'/0'/0'",
            createdAtMillis = 1,
            backupConfirmedAtMillis = 1,
        )

    private fun walletEnvelope(
        iv: ByteArray = ByteArray(12),
        version: Int = 1,
    ) = WalletSecretEnvelopeEntity(
        walletProfileId = 1,
        encryptedSecret = ByteArray(48),
        secretIv = iv,
        secretEnvelopeVersion = version,
        keystoreAccessMode = KeystoreAccessMode.BIOMETRIC_EACH_USE.name,
        updatedAtMillis = 1,
    )

    private companion object {
        const val WALLET_ADDRESS = "11111111111111111111111111111111"
    }
}
