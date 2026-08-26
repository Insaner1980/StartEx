package com.finnvek.startex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class UserFacingFormatTest {
    @Test
    fun `decimal separator follows the display locale without changing precision`() {
        val value = BigDecimal("1234.567890123")

        assertEquals("1234.567890123", formatUserNumber(value, 9, Locale.US))
        assertEquals("1234,567890123", formatUserNumber(value, 9, Locale.forLanguageTag("fi-FI")))
    }

    @Test
    fun `timestamp follows the display locale in the requested time zone`() {
        val timestamp = Instant.parse("2026-08-20T12:50:00Z").toEpochMilli()
        val us = formatUserTimestamp(timestamp, Locale.US, ZoneId.of("UTC"))
        val finnish = formatUserTimestamp(timestamp, Locale.forLanguageTag("fi-FI"), ZoneId.of("UTC"))

        assertNotEquals(us, finnish)
        assertTrue(us.contains("Aug"))
        assertTrue(finnish.contains("20.8.2026"))
    }

    @Test
    fun `SOL input accepts decimal comma or point without accepting grouping`() {
        assertEquals(BigDecimal("0.125"), parseSolAmount("0.125"))
        assertEquals(BigDecimal("0.125"), parseSolAmount("0,125"))
        assertEquals(BigDecimal("1.0000000000"), parseSolAmount("1,0000000000"))
        assertNull(parseSolAmount("1,234.5"))
        assertNull(parseSolAmount("0,0000000001"))
    }
}
