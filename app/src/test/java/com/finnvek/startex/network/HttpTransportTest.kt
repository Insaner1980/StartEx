package com.finnvek.startex.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpTransportTest {
    @Test
    fun `bounded response reader accepts a body at the limit`() {
        val body = "1234".toResponseBody("application/json".toMediaType())

        assertEquals("1234", readBoundedBody(body, maximumBytes = 4))
    }

    @Test
    fun `bounded response reader rejects a body over the limit`() {
        val body = "12345".toResponseBody("application/json".toMediaType())

        assertThrows(ResponseTooLargeException::class.java) {
            readBoundedBody(body, maximumBytes = 4)
        }
    }
}
