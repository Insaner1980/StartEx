package com.finnvek.startex.wallet

import com.finnvek.startex.security.WalletAddressValidator
import org.sol4k.PublicKey

class SolanaAddressValidator : WalletAddressValidator {
    override fun normalize(candidate: String): String? {
        val value = candidate.trim()
        if (value.isEmpty()) return null

        return try {
            val publicKey = PublicKey(value)
            publicKey.toBase58().takeIf { publicKey.bytes().size == PUBLIC_KEY_SIZE && it == value }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val PUBLIC_KEY_SIZE = 32
    }
}
