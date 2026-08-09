package com.finnvek.startex.security

enum class WalletAccessMode {
    SECURE_SESSION,
    UNATTENDED,
}

enum class ModeChangeResult {
    CHANGED,
    UNCHANGED,
    AUTHENTICATION_REQUIRED,
    PREREQUISITES_REQUIRED,
}

data class UnattendedPrerequisites(
    val dedicatedLowBalanceWalletConfirmed: Boolean,
    val riskAcknowledged: Boolean,
    val totalExposureCapLamports: Long,
    val dailyLossCapLamports: Long,
) {
    val areComplete: Boolean
        get() =
            dedicatedLowBalanceWalletConfirmed &&
                riskAcknowledged &&
                totalExposureCapLamports > 0 &&
                dailyLossCapLamports > 0
}

class WalletAccessPolicy(
    initialMode: WalletAccessMode = WalletAccessMode.SECURE_SESSION,
) {
    var mode: WalletAccessMode = initialMode
        private set

    var hasCurrentAuthentication: Boolean = false
        private set

    fun onAuthenticationSucceeded() {
        hasCurrentAuthentication = true
    }

    fun onAuthenticationRejected() {
        hasCurrentAuthentication = false
    }

    fun onAppBackgrounded() {
        if (mode == WalletAccessMode.SECURE_SESSION) {
            hasCurrentAuthentication = false
        }
    }

    fun onProcessStarted() {
        hasCurrentAuthentication = false
    }

    fun lock() {
        hasCurrentAuthentication = false
    }

    fun canSign(isForeground: Boolean): Boolean = mode == WalletAccessMode.UNATTENDED || (isForeground && hasCurrentAuthentication)

    fun setUnattendedEnabled(
        enabled: Boolean,
        prerequisites: UnattendedPrerequisites? = null,
    ): ModeChangeResult {
        val requestedMode = if (enabled) WalletAccessMode.UNATTENDED else WalletAccessMode.SECURE_SESSION
        if (requestedMode == mode) return ModeChangeResult.UNCHANGED
        if (!hasCurrentAuthentication) return ModeChangeResult.AUTHENTICATION_REQUIRED
        if (enabled && prerequisites?.areComplete != true) {
            return ModeChangeResult.PREREQUISITES_REQUIRED
        }

        mode = requestedMode
        hasCurrentAuthentication = false
        return ModeChangeResult.CHANGED
    }
}
