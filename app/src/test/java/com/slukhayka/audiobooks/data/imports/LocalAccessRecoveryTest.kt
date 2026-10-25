package com.slukhayka.audiobooks.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1230 — the shared local-access recovery seam. These tests pin the door
 * order (files/folders before the network) and the identity rule (exact bytes
 * only; a name or a count never proves the same rendition).
 */
class LocalAccessRecoveryTest {

    private fun localUnreadable(hasKnownFolder: Boolean = true) = LocalAccessRecovery.State(
        localBook = true,
        hasKnownFolder = hasKnownFolder,
        localFilesUnreadable = true,
        otherPlayableSource = false
    )

    @Test
    fun `file and folder doors come before the network`() {
        assertEquals(
            listOf(
                LocalAccessRecovery.Action.RESELECT_FILE,
                LocalAccessRecovery.Action.RESELECT_FOLDER,
                LocalAccessRecovery.Action.RESCAN_KNOWN_FOLDER,
                LocalAccessRecovery.Action.SOURCE_WATCH
            ),
            localUnreadable().actions()
        )
    }

    @Test
    fun `rescan is absent without a remembered folder but the network stays last`() {
        assertEquals(
            listOf(
                LocalAccessRecovery.Action.RESELECT_FILE,
                LocalAccessRecovery.Action.RESELECT_FOLDER,
                LocalAccessRecovery.Action.SOURCE_WATCH
            ),
            localUnreadable(hasKnownFolder = false).actions()
        )
    }

    @Test
    fun `a readable local book has no recovery panel`() {
        val healthy = localUnreadable().copy(localFilesUnreadable = false)
        assertFalse(healthy.recoverable)
        assertTrue(healthy.actions().isEmpty())
    }

    @Test
    fun `a playable other source keeps recovery out of the way`() {
        val remote = localUnreadable().copy(otherPlayableSource = true)
        assertFalse(remote.recoverable)
        assertTrue(remote.actions().isEmpty())
    }

    @Test
    fun `a streamed book is not a local-access failure`() {
        val streamed = localUnreadable().copy(localBook = false)
        assertFalse(streamed.recoverable)
        assertTrue(streamed.actions().isEmpty())
    }

    @Test
    fun `exact bytes relink the track with the same hash`() {
        val stored = listOf(
            LocalAccessRecovery.StoredTrack(trackIndex = 0, contentHash = "aaa"),
            LocalAccessRecovery.StoredTrack(trackIndex = 1, contentHash = "bbb")
        )
        val picked = listOf(
            // The name deliberately differs: file names are never identity.
            LocalAccessRecovery.PickedFile("renamed-01.mp3", "bbb", "content://tree/doc-2")
        )

        val plan = LocalAccessRecovery.match(stored, picked)

        assertTrue(plan.identityProven)
        assertEquals(listOf(1), plan.relinks.map { it.trackIndex })
        assertEquals("content://tree/doc-2", plan.relinks.single().picked.locator)
        assertTrue(plan.unprovenFiles.isEmpty())
    }

    @Test
    fun `a file with the same name but different bytes proves nothing`() {
        val stored = listOf(LocalAccessRecovery.StoredTrack(0, "aaa"))
        val picked = listOf(LocalAccessRecovery.PickedFile("chapter-01.mp3", "zzz"))

        val plan = LocalAccessRecovery.match(stored, picked)

        assertFalse(plan.identityProven)
        assertTrue(plan.relinks.isEmpty())
        assertEquals(1, plan.unprovenFiles.size)
    }

    @Test
    fun `an absent stored hash is never matched`() {
        val stored = listOf(
            LocalAccessRecovery.StoredTrack(0, null),
            LocalAccessRecovery.StoredTrack(1, "")
        )
        val picked = listOf(LocalAccessRecovery.PickedFile("chapter-01.mp3", "aaa"))

        val plan = LocalAccessRecovery.match(stored, picked)

        assertFalse(plan.identityProven)
        assertTrue(plan.relinks.isEmpty())
        assertEquals(1, plan.unprovenFiles.size)
    }

    @Test
    fun `unknown files are reported but never silently imported`() {
        val stored = listOf(LocalAccessRecovery.StoredTrack(0, "aaa"))
        val picked = listOf(
            LocalAccessRecovery.PickedFile("01.mp3", "aaa"),
            LocalAccessRecovery.PickedFile("02.mp3", "bbb"),
            LocalAccessRecovery.PickedFile("03.mp3", "ccc")
        )

        val plan = LocalAccessRecovery.match(stored, picked)

        assertTrue(plan.identityProven)
        assertEquals(1, plan.relinks.size)
        assertEquals(listOf("02.mp3", "03.mp3"), plan.unprovenFiles.map { it.fileName })
    }

    @Test
    fun `duplicate stored hashes relink each track to the same exact file`() {
        val stored = listOf(
            LocalAccessRecovery.StoredTrack(0, "same"),
            LocalAccessRecovery.StoredTrack(1, "same")
        )
        val picked = listOf(LocalAccessRecovery.PickedFile("one.mp3", "same"))

        val plan = LocalAccessRecovery.match(stored, picked)

        assertEquals(listOf(0, 1), plan.relinks.map { it.trackIndex })
        assertTrue(plan.relinks.all { it.picked.fileName == "one.mp3" })
    }
}
