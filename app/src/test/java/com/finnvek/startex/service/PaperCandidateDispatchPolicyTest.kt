package com.finnvek.startex.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperCandidateDispatchPolicyTest {
    @Test
    fun `dispatch admits each mint once and releases bounded capacity after completion`() {
        val policy = PaperCandidateDispatchPolicy(maximumPending = 2)

        assertEquals(PaperCandidateDispatch.ADMIT, policy.tryAdmit("mint-a"))
        assertEquals(PaperCandidateDispatch.DUPLICATE, policy.tryAdmit("mint-a"))
        assertEquals(PaperCandidateDispatch.ADMIT, policy.tryAdmit("mint-b"))
        assertEquals(PaperCandidateDispatch.FULL, policy.tryAdmit("mint-c"))

        policy.complete("mint-a")

        assertEquals(PaperCandidateDispatch.ADMIT, policy.tryAdmit("mint-c"))
    }
}
