package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSessionCookieDeletionTest {

    @Test
    fun `clearing a source emits expiry commands only for that source hosts`() {
        val commands = SourceSessionCookieDeletion.commandsFor(
            sourceId = "4read",
            cookieHeadersByHost = mapOf(
                "4read.org" to "cf_clearance=ok; session=abc",
                "reasd.org" to "player=ready",
                "sluhay.com" to "must-not-be-read"
            )
        )

        assertEquals(
            listOf(
                SourceSessionCookieDeletion.Command("https://4read.org/", "cf_clearance=; Max-Age=0; Path=/; Secure"),
                SourceSessionCookieDeletion.Command("https://4read.org/", "session=; Max-Age=0; Path=/; Secure"),
                SourceSessionCookieDeletion.Command("https://reasd.org/", "player=; Max-Age=0; Path=/; Secure")
            ),
            commands
        )
    }

    @Test
    fun `malformed cookie fragments cannot become deletion commands`() {
        val commands = SourceSessionCookieDeletion.commandsFor(
            sourceId = "sluhay",
            cookieHeadersByHost = mapOf("sluhay.com" to "valid=one; =bad; bad name=x; token")
        )

        assertEquals(1, commands.size)
        assertEquals("valid=; Max-Age=0; Path=/; Secure", commands.single().value)
        assertTrue(SourceSessionCookieDeletion.commandsFor("unknown", mapOf("x" to "a=b")).isEmpty())
    }
}
