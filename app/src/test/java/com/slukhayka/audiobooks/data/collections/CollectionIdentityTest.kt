package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Spec-51 (#694) — the cross-platform voter-key vector is PINNED. */
class CollectionIdentityTest {

    @Test
    fun `the voter key vector is pinned`() {
        assertEquals(
            "59227a7cdf3aeba3d0b8cf3debadb35d64765c31f9cfa65e6de011bd76e19cd5",
            CollectionIdentity.voterKey("test-uid-1", "c1")
        )
        assertEquals(
            "64ed916b9a00c462aad96d297f8755f500e7ec759b63cedc9e4db761f0e2cd35",
            CollectionIdentity.voterKey("test-uid-1", "collection-1")
        )
    }

    @Test
    fun `the same person on two collections gets unrelated keys`() {
        assertNotEquals(
            CollectionIdentity.voterKey("uid", "c1"),
            CollectionIdentity.voterKey("uid", "c2")
        )
    }

    @Test
    fun `a blank identity is never a votable key`() {
        assertEquals("", CollectionIdentity.voterKey(null, "c1"))
        assertEquals("", CollectionIdentity.voterKey("  ", "c1"))
        assertEquals("", CollectionIdentity.voterKey("uid", " "))
    }
}
