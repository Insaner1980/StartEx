package com.finnvek.startex.data.settings

import androidx.datastore.core.CorruptionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppSettingsCorruptionTest {
    @Test
    fun `corruption is not treated as missing settings`() {
        assertFalse(isRecoverableSettingsReadFailure(CorruptionException("corrupted")))
        assertTrue(isRecoverableSettingsReadFailure(IOException("temporarily unavailable")))
    }
}
