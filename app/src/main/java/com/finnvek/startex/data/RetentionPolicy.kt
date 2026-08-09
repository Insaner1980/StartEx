package com.finnvek.startex.data

data class RetentionPolicy(
    val snapshotDays: Int = 7,
    val maximumEvents: Int = 1_000,
    val maximumCandidates: Int = 250,
) {
    init {
        require(snapshotDays in 1..MAXIMUM_RETENTION_DAYS)
        require(maximumEvents in 1..MAXIMUM_EVENTS)
        require(maximumCandidates in 25..MAXIMUM_CANDIDATES)
    }

    fun snapshotCutoffMillis(nowMillis: Long): Long = (nowMillis - snapshotDays * MILLIS_PER_DAY).coerceAtLeast(0)

    private companion object {
        const val MAXIMUM_RETENTION_DAYS = 365
        const val MAXIMUM_EVENTS = 100_000
        const val MAXIMUM_CANDIDATES = 5_000
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
