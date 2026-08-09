package com.finnvek.startex.service

internal enum class PaperCandidateDispatch {
    ADMIT,
    DUPLICATE,
    FULL,
}

internal class PaperCandidateDispatchPolicy(
    private val maximumPending: Int,
) {
    private val pending = linkedSetOf<String>()

    init {
        require(maximumPending > 0)
    }

    @Synchronized
    fun tryAdmit(mint: String): PaperCandidateDispatch =
        when {
            mint in pending -> {
                PaperCandidateDispatch.DUPLICATE
            }

            pending.size >= maximumPending -> {
                PaperCandidateDispatch.FULL
            }

            else -> {
                pending += mint
                PaperCandidateDispatch.ADMIT
            }
        }

    @Synchronized
    fun complete(mint: String) {
        pending -= mint
    }
}
