package com.finnvek.startex.wallet

import java.io.Closeable
import java.security.SecureRandom

class MnemonicBackupChallenge private constructor(
    expectedWords: Map<Int, CharArray>,
) : Closeable {
    private val expectedWords = expectedWords.mapValues { (_, word) -> word.copyOf() }
    private var cleared = false

    val wordNumbers: List<Int> = expectedWords.keys.sorted()

    fun verify(answers: Map<Int, CharArray>): Boolean {
        check(!cleared) { "Backup challenge has been cleared" }
        if (answers.size != expectedWords.size) return false
        return expectedWords.all { (position, expected) ->
            answers[position]?.contentEquals(expected) == true
        }
    }

    override fun close() {
        expectedWords.values.forEach { it.fill('0') }
        cleared = true
    }

    companion object {
        fun random(
            mnemonic: CharArray,
            secureRandom: SecureRandom = SecureRandom(),
            questionCount: Int = DEFAULT_QUESTION_COUNT,
        ): MnemonicBackupChallenge {
            val words = splitWords(mnemonic)
            require(questionCount in 1..words.size) { "Invalid backup question count" }

            val positions = mutableSetOf<Int>()
            while (positions.size < questionCount) {
                positions += secureRandom.nextInt(words.size) + 1
            }
            return fromWords(words, positions)
        }

        internal fun forPositions(
            mnemonic: CharArray,
            positions: Set<Int>,
        ): MnemonicBackupChallenge {
            val words = splitWords(mnemonic)
            require(positions.isNotEmpty()) { "At least one backup position is required" }
            require(positions.all { it in 1..words.size }) { "Backup position is out of range" }
            return fromWords(words, positions)
        }

        private fun fromWords(
            words: List<CharArray>,
            positions: Set<Int>,
        ): MnemonicBackupChallenge =
            try {
                MnemonicBackupChallenge(
                    positions.associateWith { position -> words[position - 1] },
                )
            } finally {
                words.forEach { it.fill('0') }
            }

        private fun splitWords(mnemonic: CharArray): List<CharArray> {
            val words = mutableListOf<CharArray>()
            var start = -1
            mnemonic.forEachIndexed { index, character ->
                if (character.isWhitespace()) {
                    if (start >= 0) {
                        words += mnemonic.copyOfRange(start, index)
                        start = -1
                    }
                } else if (start < 0) {
                    start = index
                }
            }
            if (start >= 0) words += mnemonic.copyOfRange(start, mnemonic.size)
            require(words.isNotEmpty()) { "Mnemonic is empty" }
            return words
        }

        private const val DEFAULT_QUESTION_COUNT = 3
    }
}
