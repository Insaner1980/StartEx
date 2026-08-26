package com.finnvek.startex.wallet

import com.finnvek.startex.security.clearSecret
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.ceil

object WalletSecretCodec {
    fun encodeAndClear(mnemonic: CharArray): ByteArray {
        val encoder =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output =
            ByteBuffer.allocate(
                maxOf(1, ceil(mnemonic.size * encoder.maxBytesPerChar().toDouble()).toInt()),
            )

        return try {
            require(mnemonic.isNotEmpty()) { "Mnemonic is empty" }
            encoder.encode(CharBuffer.wrap(mnemonic), output, true).requireCompleted()
            encoder.flush(output).requireCompleted()
            output.flip()
            ByteArray(output.remaining()).also { output[it] }
        } finally {
            mnemonic.fill('0')
            output.array().clearSecret()
        }
    }

    fun decodeAndClear(encodedMnemonic: ByteArray): CharArray {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output =
            CharBuffer.allocate(
                maxOf(1, ceil(encodedMnemonic.size * decoder.maxCharsPerByte().toDouble()).toInt()),
            )

        return try {
            require(encodedMnemonic.isNotEmpty()) { "Encoded mnemonic is empty" }
            decoder.decode(ByteBuffer.wrap(encodedMnemonic), output, true).requireCompleted()
            decoder.flush(output).requireCompleted()
            output.flip()
            CharArray(output.remaining()).also { output[it] }
        } finally {
            encodedMnemonic.clearSecret()
            output.array().fill('0')
        }
    }

    private fun CoderResult.requireCompleted() {
        if (isError) throwException()
        check(isUnderflow) { "Wallet secret buffer capacity was exceeded" }
    }
}
