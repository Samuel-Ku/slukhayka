package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-51 (#691) — the pinned hash vector. The AC is explicit: the raw uid must
 * never appear in a public document id, and the derivation is frozen by a test
 * so a silent change cannot re-identify existing curators.
 */
class CuratorIdentityTest {

    @Test
    fun `the author id is the pinned sha256 of the uid`() {
        assertEquals(
            "0e83e6fd82eb2043d313b8e60c6b7308ac6fbca98e950985867ee69766404352",
            CuratorIdentity.authorId("test-uid-1")
        )
        assertEquals(
            "10f6f120daeddab1f80b3d1a6eff827ecd7ed6538e10a0169301cf8b308af39f",
            CuratorIdentity.authorId("firebase-uid-abc")
        )
    }

    @Test
    fun `the raw uid never appears in the derived id`() {
        val uid = "firebase-uid-abc"
        val id = CuratorIdentity.authorId(uid)
        assertFalse(id.contains(uid))
        assertEquals(64, id.length)
    }

    @Test
    fun `a blank uid is not publishable and has no author id`() {
        assertEquals("", CuratorIdentity.authorId(null))
        assertEquals("", CuratorIdentity.authorId("   "))
        assertFalse(CuratorIdentity.isPublishable(null))
        assertTrue(CuratorIdentity.isPublishable("test-uid-1"))
    }
}
