package com.finnvek.startex.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HttpTransportTest {
    @Test
    fun `cancelling execution cancels the in-flight call`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val observedCall = AtomicReference<okhttp3.Call>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        observedCall.set(chain.call())
                        entered.countDown()
                        release.await()
                        throw IOException("released")
                    }.build()
            val job =
                launch(Dispatchers.IO) {
                    OkHttpTransport(client).execute(Request.Builder().url("https://example.invalid/").build())
                }

            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                job.cancel()
                assertTrue(observedCall.get().isCanceled())
            } finally {
                release.countDown()
                job.cancelAndJoin()
            }
        }

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
