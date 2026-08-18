package com.finnvek.startex.ui

import com.finnvek.startex.R
import com.finnvek.startex.wallet.SignedSolTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualTransferTransactionTest {
    @Test
    fun signedTransferMapsToStableWriteAheadRecord() {
        val signed =
            SignedSolTransfer(
                signature = "signature",
                serializedHash = "serialized-hash",
                idempotencyKey = "idempotency-key",
                lastValidBlockHeight = 42,
            )

        val record =
            signed.toManualTransferTransaction(
                status = "SIGNED_NOT_BROADCAST",
                failureCode = null,
                nowMillis = 7,
            )

        assertEquals("manual:idempotency-key", record.id)
        assertNull(record.intentId)
        assertEquals("signature", record.signature)
        assertEquals("serialized-hash", record.serializedHash)
        assertEquals("idempotency-key", record.idempotencyKey)
        assertEquals("SIGNED_NOT_BROADCAST", record.status)
        assertEquals(42L, record.lastValidBlockHeight)
        assertEquals(7L, record.submittedAtMillis)
    }

    @Test
    fun manualTransferNotificationsNeverCallSubmissionConfirmed() {
        assertNull(manualTransferNotificationCode("SUBMITTED"))
        assertEquals(
            "MANUAL_TRANSFER_UNCERTAIN",
            manualTransferNotificationCode("SUBMISSION_UNCERTAIN"),
        )
        assertEquals("MANUAL_TRANSFER_CONFIRMED", manualTransferNotificationCode("FINALIZED"))
        assertEquals("MANUAL_TRANSFER_FAILED", manualTransferNotificationCode("CHAIN_REJECTED"))
    }

    @Test
    fun manualTransferStateGateRequiresStoppedOrPausedMonitoringWithoutOpenPositions() {
        assertNull(manualTransferStateBlockMessage(MonitorState.Stopped, hasOpenPositions = false))
        assertNull(manualTransferStateBlockMessage(MonitorState.Paused, hasOpenPositions = false))
        assertEquals(
            R.string.transfer_pause_required,
            manualTransferStateBlockMessage(MonitorState.Running, hasOpenPositions = false),
        )
        assertEquals(
            R.string.transfer_open_positions_blocked,
            manualTransferStateBlockMessage(MonitorState.Paused, hasOpenPositions = true),
        )
    }
}
