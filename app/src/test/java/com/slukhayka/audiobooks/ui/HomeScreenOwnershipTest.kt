package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.collections.CuratorIdentity
import com.slukhayka.audiobooks.ui.screens.isOwnPublicCollection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1821 — the Listen collection page owns exactly when the curator id matches. */
class HomeScreenOwnershipTest {

    @Test
    fun `the curator author id is own`() {
        val uid = "listener-1"
        assertTrue(isOwnPublicCollection(CuratorIdentity.authorId(uid), uid))
    }

    @Test
    fun `another listener is not own`() {
        assertFalse(isOwnPublicCollection(CuratorIdentity.authorId("listener-2"), "listener-1"))
    }

    @Test
    fun `no profile or a blank id is never own`() {
        assertFalse(isOwnPublicCollection("", null))
        assertFalse(isOwnPublicCollection("", " "))
    }
}
