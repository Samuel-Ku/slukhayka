package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-49 T5 — the shared refusal aggregate shape: ONE anonymous document
 * per sourceId carrying a count only (no book, no listener, no URL —
 * refusals are a source property even in the data), plus the per-device
 * vote identity that makes «one device counts once» enforceable.
 * Pure JVM, no Firebase.
 */
class SourceRefusalCodecsTest {

    @Test
    fun `vote document id pins one vote per source and device`() {
        assertEquals("4read_uid-1", SourceRefusalVoteCodec.documentId("4read", "uid-1"))
    }

    @Test
    fun `vote payload carries source and device only`() {
        val map = SourceRefusalVoteCodec.toMap("4read", "uid-1")!!
        assertEquals(mapOf("sourceId" to "4read", "uid" to "uid-1"), map)
    }

    @Test
    fun `vote payload is never built for blank identities`() {
        assertNull(SourceRefusalVoteCodec.toMap("", "uid-1"))
        assertNull(SourceRefusalVoteCodec.toMap("4read", ""))
        assertNull(SourceRefusalVoteCodec.toMap("  ", "  "))
    }

    @Test
    fun `count document carries a count field only - never book identity`() {
        assertEquals(mapOf("count" to 3L), SourceRefusalCountCodec.toMap(3L))
        assertEquals(
            "refusals are a source property even in the data",
            setOf("count"),
            SourceRefusalCountCodec.toMap(1L).keys
        )
    }

    @Test
    fun `count round-trips`() {
        assertEquals(7L, SourceRefusalCountCodec.fromMap(SourceRefusalCountCodec.toMap(7L)))
    }

    @Test
    fun `corrupt count documents are a miss, never a crash`() {
        assertNull(SourceRefusalCountCodec.fromMap(emptyMap()))
        assertNull(SourceRefusalCountCodec.fromMap(mapOf("count" to "many")))
        assertNull(SourceRefusalCountCodec.fromMap(mapOf("count" to -1L)))
        assertNull(SourceRefusalCountCodec.fromMap(mapOf("other" to 1L)))
    }
}
