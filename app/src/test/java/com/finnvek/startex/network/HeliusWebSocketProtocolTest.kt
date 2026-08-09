package com.finnvek.startex.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeliusWebSocketProtocolTest {
    @Test
    fun `account subscription uses confirmed standard Solana RPC`() {
        val request =
            HeliusWebSocketJson.subscriptionRequest(
                requestId = 4,
                subscription = HeliusSubscription.Account("WalletFixture"),
            )

        assertTrue(request.contains("\"method\":\"accountSubscribe\""))
        assertTrue(request.contains("\"WalletFixture\""))
        assertTrue(request.contains("\"commitment\":\"confirmed\""))
        assertTrue(request.contains("\"encoding\":\"base64\""))
    }

    @Test
    fun `signature subscription disables received-only notifications`() {
        val request =
            HeliusWebSocketJson.subscriptionRequest(
                requestId = 5,
                subscription = HeliusSubscription.Signature("SignatureFixture"),
            )

        assertTrue(request.contains("\"method\":\"signatureSubscribe\""))
        assertTrue(request.contains("\"enableReceivedNotification\":false"))
    }

    @Test
    fun `acknowledgement binds request to provider subscription id`() {
        val result = HeliusWebSocketJson.parse("""{"jsonrpc":"2.0","result":77,"id":4}""")

        assertTrue(result is ProviderResult.Success)
        assertEquals(
            HeliusWireMessage.Acknowledgement(requestId = 4, subscriptionId = 77),
            (result as ProviderResult.Success).value,
        )
    }

    @Test
    fun `account notification preserves exact lamports and slot`() {
        val result = HeliusWebSocketJson.parse(fixture("helius/wss-account-notification.json"))

        assertTrue(result is ProviderResult.Success)
        assertEquals(
            HeliusWireMessage.AccountNotification(
                subscriptionId = 77,
                slot = 420_000_020,
                lamports = 7_654_321,
            ),
            (result as ProviderResult.Success).value,
        )
    }

    @Test
    fun `signature notification preserves confirmed failure state`() {
        val result = HeliusWebSocketJson.parse(fixture("helius/wss-signature-notification.json"))

        assertTrue(result is ProviderResult.Success)
        val event = (result as ProviderResult.Success).value as HeliusWireMessage.SignatureNotification
        assertEquals(78L, event.subscriptionId)
        assertEquals(420_000_021L, event.slot)
        assertFalse(event.failed)
    }

    @Test
    fun `oversized provider message fails before JSON parsing`() {
        val result = HeliusWebSocketJson.parse("x".repeat(64 * 1_024 + 1))

        assertTrue(result is ProviderResult.Failure)
        assertEquals("bodySize", (result as ProviderResult.Failure).error.invalidField)
    }
}
