package com.finnvek.startex.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RetentionPolicyTest {
    @Test
    fun `snapshot cutoff is derived from configured full days`() {
        val policy = RetentionPolicy(snapshotDays = 7, maximumEvents = 1_000)

        assertEquals(395_200_000, policy.snapshotCutoffMillis(nowMillis = 1_000_000_000))
    }

    @Test
    fun `retention refuses unbounded or empty limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetentionPolicy(snapshotDays = 0, maximumEvents = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetentionPolicy(snapshotDays = 7, maximumEvents = 0)
        }
    }
}
