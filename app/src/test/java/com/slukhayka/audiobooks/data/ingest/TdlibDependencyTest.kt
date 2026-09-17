package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ADR-0050 / #829 — the pinned TDLib binding is really on the compile
 * classpath. This runs on the JVM and deliberately touches only the pure
 * `TdApi` data classes: loading `Client` itself would pull the JNI library in,
 * which is a device concern, not a unit-test one.
 */
class TdlibDependencyTest {

    @Test
    fun `the pinned TdApi binding is on the compile classpath`() {
        val request = org.drinkless.tdlib.TdApi.GetMessage(-1004476157917L, 42L)

        assertEquals(-1004476157917L, request.chatId)
        assertEquals(42L, request.messageId)
        assertNotNull("the TdApi.Message shape must be resolvable", org.drinkless.tdlib.TdApi.Message::class.java)
    }
}
