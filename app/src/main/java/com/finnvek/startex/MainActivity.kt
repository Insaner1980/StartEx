package com.finnvek.startex

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.finnvek.startex.ui.StartExApp
import com.finnvek.startex.ui.StartExUiEvent
import com.finnvek.startex.ui.StartExViewModel
import com.finnvek.startex.ui.theme.StartExTheme

class MainActivity : FragmentActivity() {
    private val viewModel by viewModels<StartExViewModel>()
    private var authenticationInProgress = false
    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticationInProgress = viewModel.authenticationInProgress
        biometricPrompt = createBiometricPrompt()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.setFilterTouchesWhenObscured(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            StartExTheme {
                StartExApp(
                    viewModel = viewModel,
                    onAuthenticate = ::authenticate,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onActivityStopped()
        if (viewModel.state.value.secureSession && !authenticationInProgress) {
            viewModel.lockWallet()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0) return false
        return super.dispatchTouchEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DEVICE_CREDENTIAL) return
        val request = viewModel.takePendingAuthenticationRequest() ?: return
        if (resultCode == Activity.RESULT_OK) {
            viewModel.onAuthenticationSucceeded(request)
        } else {
            viewModel.onAuthenticationFailed(request)
        }
        finishAuthentication()
    }

    private fun authenticate(
        request: StartExUiEvent.Authenticate,
        promptInfo: BiometricPrompt.PromptInfo,
    ) {
        if (!viewModel.onAuthenticationPromptStarted(request)) return
        authenticationInProgress = true
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && request.operation == null) {
            @Suppress("DEPRECATION")
            val credentialIntent =
                getSystemService(KeyguardManager::class.java)
                    ?.createConfirmDeviceCredentialIntent(promptInfo.title, promptInfo.subtitle)
            if (credentialIntent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(credentialIntent, REQUEST_DEVICE_CREDENTIAL)
                return
            }
        }
        if (request.operation == null) {
            biometricPrompt.authenticate(promptInfo)
        } else {
            biometricPrompt.authenticate(promptInfo, request.operation.cryptoObject)
        }
    }

    private fun createBiometricPrompt(): BiometricPrompt =
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val request = viewModel.takePendingAuthenticationRequest() ?: return
                    val authenticatedRequest =
                        request.operation?.let { operation ->
                            result.cryptoObject?.let(operation::bindAuthenticated)?.let { authenticated ->
                                request.copy(operation = authenticated)
                            }
                        } ?: if (request.operation == null) request else null
                    if (authenticatedRequest == null) {
                        viewModel.onAuthenticationFailed(request)
                    } else {
                        viewModel.onAuthenticationSucceeded(authenticatedRequest)
                    }
                    finishAuthentication()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    val request = viewModel.takePendingAuthenticationRequest() ?: return
                    viewModel.onAuthenticationFailed(
                        request,
                        biometricAuthenticationErrorMessage(errorCode),
                    )
                    finishAuthentication()
                }

                override fun onAuthenticationFailed() = Unit
            },
        )

    private fun finishAuthentication() {
        authenticationInProgress = false
        if (
            viewModel.state.value.secureSession &&
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            viewModel.lockWallet()
        }
    }

    private companion object {
        const val REQUEST_DEVICE_CREDENTIAL = 0x51A7
    }
}

internal fun biometricAuthenticationErrorMessage(errorCode: Int): Int =
    when (errorCode) {
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> R.string.authentication_locked_out

        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_TIMEOUT,
        -> R.string.authentication_temporarily_unavailable

        else -> R.string.authentication_failed
    }
