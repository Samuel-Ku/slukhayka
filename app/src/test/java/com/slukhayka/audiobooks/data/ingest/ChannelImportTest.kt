package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec-53 T10 — the channel selection rule and the paced walk. The channel
 * read itself is a seam (faked here); NewPipe mapping stays behind the
 * production fetcher like the T1 spike's engine mapping.
 */
class ChannelImportTest {

    private fun video(id: String, url: String = "https://www.youtube.com/watch?v=$id") =
        ChannelListItem(id, ChannelItemKind.VIDEO, "Відео $id", url, durationSeconds = 60L)

    private fun playlist(id: String) =
        ChannelListItem(
            id,
            ChannelItemKind.PLAYLIST,
            "Плейлист $id",
            "https://www.youtube.com/playlist?list=$id"
        )

    // --- selection ----------------------------------------------------------

    @Test
    fun `member videos of ticked playlists stay out unless asked back`() {
        val pl = playlist("PL1")
        val member = video("aaa111BBB22", "https://www.youtube.com/watch?v=aaa111BBB22")
        val free = video("zzz999YYY11", "https://www.youtube.com/watch?v=zzz999YYY11")
        val members = mapOf("PL1" to setOf("https://www.youtube.com/watch?v=aaa111BBB22"))

        val heldBack = ChannelSelectionPolicy.resolve(listOf(pl, member, free), members, includeSkipped = false)

        assertEquals(listOf(pl, free), heldBack.toAdd)
        assertEquals(listOf("aaa111BBB22"), heldBack.skippedVideoIds)

        val askedBack = ChannelSelectionPolicy.resolve(listOf(pl, member, free), members, includeSkipped = true)

        assertEquals(listOf(pl, member, free), askedBack.toAdd)
        assertTrue(askedBack.skippedVideoIds.isEmpty())
    }

    @Test
    fun `videos outside every ticked playlist always go in`() {
        val resolved = ChannelSelectionPolicy.resolve(
            listOf(video("a1"), video("b2")),
            playlistMembers = mapOf("PL9" to setOf("https://www.youtube.com/watch?v=c3")),
            includeSkipped = false
        )

        assertEquals(2, resolved.toAdd.size)
        assertTrue(resolved.skippedVideoIds.isEmpty())
    }

    @Test
    fun `last N takes the freshest rows in order`() {
        val items = List(5) { video("v$it") }

        assertEquals(listOf("v0", "v1", "v2"), ChannelSelectionPolicy.lastN(items, 3).map { it.id })
        assertEquals(items, ChannelSelectionPolicy.lastN(items, 99))
        assertTrue(ChannelSelectionPolicy.lastN(items, 0).isEmpty())
    }

    // --- session ------------------------------------------------------------

    private fun session(
        submitted: MutableList<String>,
        pauses: MutableList<Long> = mutableListOf()
    ) = ChannelImportSession(
        submit = {
            submitted += it
            ListenerSubmissionFlow.Start.Unsupported
        },
        pacing = PacingPolicy(),
        pauseMillis = { pauses += it },
        nowMillis = { 0L }
    )

    @Test
    fun `every picked item walks the ordinary door in order`() = runTest {
        val submitted = mutableListOf<String>()
        val pauses = mutableListOf<Long>()
        val items = listOf(video("a1"), playlist("PL1"), video("b2"))

        val results = session(submitted, pauses).run(items)

        assertEquals(
            listOf(
                "https://www.youtube.com/watch?v=a1",
                "https://www.youtube.com/playlist?list=PL1",
                "https://www.youtube.com/watch?v=b2"
            ),
            submitted
        )
        assertEquals(3, results.size)
        assertTrue("the human rhythm waits between items", pauses.size == 2 && pauses.all { it > 0 })
    }

    @Test
    fun `progress is visible during the walk and gone after`() = runTest {
        val submitted = mutableListOf<String>()
        lateinit var s: ChannelImportSession
        val observed = mutableListOf<ChannelImportSession.Progress?>()
        s = ChannelImportSession(
            submit = {
                observed += s.progress.value
                submitted += it
                ListenerSubmissionFlow.Start.Unsupported
            },
            pacing = PacingPolicy(),
            pauseMillis = {},
            nowMillis = { 0L }
        )
        val items = listOf(video("a1"), video("b2"))

        s.run(items)

        assertEquals(
            listOf(
                ChannelImportSession.Progress(0, 2, "Відео a1"),
                ChannelImportSession.Progress(1, 2, "Відео b2")
            ),
            observed
        )
        assertEquals(items.map { it.url }, submitted)
        assertNull(s.progress.value)
    }

    @Test
    fun `stopping lands between items with no partial trace`() = runTest {
        val submitted = mutableListOf<String>()
        val enteredSecond = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = ChannelImportSession(
            submit = {
                submitted += it
                if (submitted.size == 2) {
                    enteredSecond.complete(Unit)
                    // Park: the walk resumes only when the test body acts.
                    release.await()
                }
                ListenerSubmissionFlow.Start.Unsupported
            },
            pacing = PacingPolicy(PacingParams(burstLimit = 1000)),
            pauseMillis = {},
            nowMillis = { 0L }
        )
        val items = List(5) { video("v$it") }

        val runJob = launch { s.run(items) }
        enteredSecond.await()
        runJob.cancel()
        runJob.join()

        // A supervisor scope treats the child's cancellation as handled, so
        // join() reports completion instead of rethrowing (the seeder's
        // cancellation test relies on the same rule): the contract is read
        // off the job state and the visible traces.
        assertTrue("the stopped walk reports cancellation", runJob.isCancelled)
        assertEquals(2, submitted.size)
        assertNull("no stale progress survives the stop", s.progress.value)
    }

    @Test
    fun `an empty selection runs nothing`() = runTest {
        val submitted = mutableListOf<String>()

        assertTrue(session(submitted).run(emptyList()).isEmpty())
        assertTrue(submitted.isEmpty())
    }
}
