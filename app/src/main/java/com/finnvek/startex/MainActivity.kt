package com.finnvek.startex

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
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
        enableEdgeToEdge()
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
        if (viewModel.state.value.secureSession && !authenticationInProgress) {
            viewModel.lockWallet()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0) return false
        return super.dispatchTouchEvent(event)
    }

    private fun authenticate(
        request: StartExUiEvent.Authenticate,
        promptInfo: BiometricPrompt.PromptInfo,
    ) {
        authenticationInProgress = true
        viewModel.onAuthenticationPromptStarted(request)
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
                        viewModel.onAuthenticationFailed()
                    } else {
                        viewModel.onAuthenticationSucceeded(authenticatedRequest)
                    }
                    finishAuthentication()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    if (viewModel.takePendingAuthenticationRequest() == null) return
                    viewModel.onAuthenticationFailed()
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
}
