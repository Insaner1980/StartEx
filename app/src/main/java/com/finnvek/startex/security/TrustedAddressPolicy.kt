package com.finnvek.startex.security

fun interface WalletAddressValidator {
    fun normalize(candidate: String): String?
}

data class TrustedAddress(
    val label: String,
    val address: String,
    val isLocked: Boolean = false,
)

enum class TrustedAddressRejection {
    AUTHENTICATION_REQUIRED,
    INVALID_ADDRESS,
    LABEL_REQUIRED,
    LOCKED_CHANGE_CONFIRMATION_REQUIRED,
}

enum class TrustedWithdrawalAuthorization {
    ALLOWED,
    AUTHENTICATION_REQUIRED,
    FINAL_CHARACTERS_MISMATCH,
}

sealed interface TrustedAddressMutation {
    data class Allowed(
        val address: TrustedAddress,
    ) : TrustedAddressMutation

    data class Rejected(
        val reason: TrustedAddressRejection,
    ) : TrustedAddressMutation
}

class TrustedAddressPolicy(
    private val addressValidator: WalletAddressValidator,
) {
    fun validateMutation(
        label: String,
        address: String,
        hasCurrentAuthentication: Boolean,
        existing: TrustedAddress? = null,
        confirmedLockedChange: Boolean = false,
        isLocked: Boolean = existing?.isLocked ?: false,
    ): TrustedAddressMutation {
        if (!hasCurrentAuthentication) {
            return TrustedAddressMutation.Rejected(TrustedAddressRejection.AUTHENTICATION_REQUIRED)
        }
        if (existing?.isLocked == true && !confirmedLockedChange) {
            return TrustedAddressMutation.Rejected(
                TrustedAddressRejection.LOCKED_CHANGE_CONFIRMATION_REQUIRED,
            )
        }

        val normalizedLabel = label.trim()
        if (normalizedLabel.isEmpty()) {
            return TrustedAddressMutation.Rejected(TrustedAddressRejection.LABEL_REQUIRED)
        }

        val normalizedAddress =
            addressValidator.normalize(address.trim())
                ?: return TrustedAddressMutation.Rejected(TrustedAddressRejection.INVALID_ADDRESS)

        return TrustedAddressMutation.Allowed(
            TrustedAddress(
                label = normalizedLabel,
                address = normalizedAddress,
                isLocked = isLocked,
            ),
        )
    }

    fun authorizeFirstWithdrawal(
        address: String,
        confirmedFinalCharacters: CharArray,
        hasCurrentAuthentication: Boolean,
    ): TrustedWithdrawalAuthorization {
        if (!hasCurrentAuthentication) {
            return TrustedWithdrawalAuthorization.AUTHENTICATION_REQUIRED
        }
        val expected = address.takeLast(FINAL_CHARACTER_COUNT).toCharArray()
        return if (confirmedFinalCharacters.contentEquals(expected)) {
            TrustedWithdrawalAuthorization.ALLOWED
        } else {
            TrustedWithdrawalAuthorization.FINAL_CHARACTERS_MISMATCH
        }
    }

    private companion object {
        const val FINAL_CHARACTER_COUNT = 4
    }
}
