package com.finnvek.startex.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpPortalProtocolTest {
    @Test
    fun `discovery subscriptions contain only the two free feeds`() {
        assertEquals(
            listOf("{\"method\":\"subscribeNewToken\"}", "{\"method\":\"subscribeMigration\"}"),
            PumpPortalProtocol.discoverySubscriptions,
        )
    }

    @Test
    fun `subscription acknowledgement is not treated as a token event`() {
        assertTrue(
            PumpPortalJson.isSubscriptionAcknowledgement(
                """{"message":"Successfully subscribed to keys."}""",
            ),
        )
    }

    @Test
    fun `new-token fixture parses into a creation event`() {
        val result = PumpPortalJson.parseEvent(fixture("pumpportal/new-token.json"))

        assertTrue(result is ProviderResult.Success)
        val event = (result as ProviderResult.Success).value
        assertEquals(PumpPortalEventKind.NEW_TOKEN, event.kind)
        assertEquals("Mint111111111111111111111111111111111111111", event.mint)
        assertEquals("FIX", event.symbol)
    }

    @Test
    fun `migration fixture parses into a migration event`() {
        val result = PumpPortalJson.parseEvent(fixture("pumpportal/migration.json"))

        assertTrue(result is ProviderResult.Success)
        assertEquals(PumpPortalEventKind.MIGRATION, (result as ProviderResult.Success).value.kind)
    }

    @Test
    fun `missing mint fails closed`() {
        val result = PumpPortalJson.parseEvent(fixture("pumpportal/missing-mint.json"))

        assertTrue(result is ProviderResult.Failure)
        assertEquals("mint", (result as ProviderResult.Failure).error.invalidField)
    }

    @Test
    fun `oversized provider fields fail closed before persistence`() {
        val oversizedMint = "m".repeat(65)
        val result =
            PumpPortalJson.parseEvent(
                """{"signature":"sig","mint":"$oversizedMint","txType":"create"}""",
            )

        assertTrue(result is ProviderResult.Failure)
        assertEquals("mint", (result as ProviderResult.Failure).error.invalidField)
    }

    @Test
    fun `malformed decimal fails closed`() {
        val result =
            PumpPortalJson.parseEvent(
                """{"signature":"sig","mint":"mint","txType":"create","marketCapSol":"not-a-number"}""",
            )

        assertTrue(result is ProviderResult.Failure)
        assertEquals("marketCapSol", (result as ProviderResult.Failure).error.invalidField)
    }

    @Test
    fun `paid trade event is not accepted by discovery parser`() {
        val result =
            PumpPortalJson.parseEvent(
                """{"signature":"sig","mint":"mint","txType":"buy"}""",
            )

        assertTrue(result is ProviderResult.Failure)
        assertEquals("txType", (result as ProviderResult.Failure).error.invalidField)
    }

    @Test
    fun `unexpected remote close notifies the coordinator`() {
        var reported: ProviderError? = null
        val listener =
            object : PumpPortalEventListener {
                override fun onEvent(event: PumpPortalEvent) = Unit

                override fun onError(error: ProviderError) {
                    reported = error
                }
            }

        notifyPumpPortalClosed(expected = false, code = 1006, listener = listener)

        assertTrue(reported is ProviderError.ConnectionClosed)
        assertEquals(1006, (reported as ProviderError.ConnectionClosed).closeCode)
    }

    @Test
    fun `local close does not trigger reconnect callback`() {
        var errorCount = 0
        val listener =
            object : PumpPortalEventListener {
                override fun onEvent(event: PumpPortalEvent) = Unit

                override fun onError(error: ProviderError) {
                    errorCount++
                }
            }

        notifyPumpPortalClosed(expected = true, code = 1000, listener = listener)

        assertEquals(0, errorCount)
    }
}
