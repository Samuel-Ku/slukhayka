package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionPublicationCodec
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-601 T3/T5 — the listener-submission flow: a pasted link becomes a
 * local Source, ONLY a real playback event publishes, and every failure
 * degrades honestly. The fakes are the shared-base fake and the counted
 * seam lambdas — no network, no Android.
 */
class ListenerSubmissionFlowTest {

    private class Harness(
        metadataJson: String? = """{"id":"v1","title":"Гаррі Поттер 1 — АудіоКниги Українською"}""",
        importOutcome: ListenerSubmissionFlow.ImportOutcome = ListenerSubmissionFlow.ImportOutcome(
            result = ListenerSubmissionFlow.ImportResult.IMPORTED,
            bookId = "book-1",
            sourceId = "source-1"
        ),
        tgIdentity: ListenerSubmissionFlow.TgIdentity? = null,
        remaining: Int = 10,
        submitter: String? = "uid-1",
        withSharedBase: Boolean = true,
        stateStore: SubmissionStateStore = InMemorySubmissionStateStore(),
        importWatching: (suspend (String, ListenerSubmissionFlow.TgIdentity) -> ListenerSubmissionFlow.WatchingImport)? = null,
        watchSource: (suspend (String, String) -> Unit)? = null
    ) {
        val store = FakeSharedBookMetaStore()
        val verification = SubmissionVerification { 1_000L }
        val policy = SubmissionPolicy(store, verification) { 1_000L }
        val publisher = SubmissionPublisher(store, policy) { 1_000L }

        var fetchCalls = 0
        var importCalls = 0
        var tgCalls = 0

        /** Spec-53 T6 — exactly which url reached the engine. */
        val fetchedUrls = mutableListOf<String>()

        val flow = ListenerSubmissionFlow(
            fetchMetadata = { url -> fetchCalls++; fetchedUrls += url; metadataJson },
            importYouTube = { _, _, _ -> importCalls++; importOutcome },
            fetchTgIdentity = { tgCalls++; tgIdentity },
            importWatchingTelegram = importWatching,
            watchSource = watchSource,
            publisher = if (withSharedBase) publisher else null,
            verification = if (withSharedBase) verification else null,
            remainingToday = { remaining },
            submitterId = { submitter },
            store = stateStore
        )
    }

    private val youtube = "https://www.youtube.com/playlist?list=PLabcd1234"
    private val tgPost = "https://t.me/bookchannel/42"

    private val tgIdentity = ListenerSubmissionFlow.TgIdentity(
        title = "Острів Дума",
        author = "Стівен Кінг",
        narrator = "Сергій Філатов",
        coverUrl = "https://cdn4.telesco.pe/file/cover.jpg",
        description = "Опис посту"
    )

    @Test
    fun `classifies the supported hosts honestly`() {
        val flow = Harness().flow
        assertEquals(ListenerSubmissionFlow.Kind.YOUTUBE, flow.classify("https://youtu.be/abc12345678"))
        assertEquals(ListenerSubmissionFlow.Kind.YOUTUBE, flow.classify("https://m.youtube.com/watch?v=x"))
        assertEquals(ListenerSubmissionFlow.Kind.TELEGRAM, flow.classify("https://t.me/channel/1"))
        assertEquals(ListenerSubmissionFlow.Kind.UNSUPPORTED, flow.classify("https://example.com/book.mp3"))
    }

    @Test
    fun `youtube link imports locally and waits for the verdict`() = runTest {
        val harness = Harness()

        val start = harness.flow.submit(youtube)

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        start as ListenerSubmissionFlow.Start.Imported
        assertEquals("book-1", start.bookId)
        assertEquals("source-1", start.sourceId)
        assertTrue(start.publishable)
        assertEquals(1, harness.fetchCalls)
        assertEquals(1, harness.importCalls)
        assertEquals("nothing is published before playback", 0, harness.store.submissionPuts.size)
    }

    @Test
    fun `daily limit refuses before any fetch or import`() = runTest {
        val harness = Harness(remaining = 0)

        val start = harness.flow.submit(youtube)

        assertEquals(
            ListenerSubmissionFlow.Start.Refused(ListenerSubmissionFlow.Reason.DAILY_LIMIT_REACHED, 0),
            start
        )
        assertEquals(0, harness.fetchCalls)
        assertEquals(0, harness.importCalls)
    }

    @Test
    fun `broken metadata and import failures keep their honest reasons`() = runTest {
        assertEquals(
            ListenerSubmissionFlow.Start.Refused(ListenerSubmissionFlow.Reason.METADATA_FAILED, 10),
            Harness(metadataJson = null).flow.submit(youtube)
        )
        assertEquals(
            ListenerSubmissionFlow.Start.Refused(ListenerSubmissionFlow.Reason.NO_PLAYABLE_TRACKS, 10),
            Harness(
                importOutcome = ListenerSubmissionFlow.ImportOutcome(
                    ListenerSubmissionFlow.ImportResult.NO_PLAYABLE_TRACKS
                )
            ).flow.submit(youtube)
        )
        assertEquals(
            ListenerSubmissionFlow.Start.Refused(ListenerSubmissionFlow.Reason.IMPORT_FAILED, 10),
            Harness(
                importOutcome = ListenerSubmissionFlow.ImportOutcome(
                    ListenerSubmissionFlow.ImportResult.IMPORTED
                )
            ).flow.submit(youtube)
        )
    }

    @Test
    fun `the real playing event publishes exactly once`() = runTest {
        val harness = Harness()
        harness.flow.submit(youtube)

        val verdict = harness.flow.onPlaybackStarted("source-1")

        assertEquals(ListenerSubmissionFlow.Verdict.Published, verdict)
        val publication = harness.store.submissionPuts.single()
        assertEquals(youtube, publication.sourceUrl)
        assertEquals(SubmissionAccessMode.YOUTUBE, publication.accessMode)
        assertEquals("Гаррі Поттер 1", publication.title)
        assertEquals(1, publication.chapters.size)
        assertEquals(1_000L, publication.verifiedAt)

        // The pending slot cleared: a second playing event publishes nothing.
        assertEquals(ListenerSubmissionFlow.Verdict.NoPending, harness.flow.onPlaybackStarted("source-1"))
        assertEquals(1, harness.store.submissionPuts.size)
    }

    @Test
    fun `a verdict for another source or without a submission is a no-op`() = runTest {
        val harness = Harness()

        assertEquals(ListenerSubmissionFlow.Verdict.NoPending, harness.flow.onPlaybackStarted("source-1"))

        harness.flow.submit(youtube)
        assertEquals(ListenerSubmissionFlow.Verdict.NoPending, harness.flow.onPlaybackStarted("other-source"))
        assertTrue(harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `a publish after the same url was already published is refused honestly`() = runTest {
        val harness = Harness()
        harness.store.publishSubmission(
            com.slukhayka.audiobooks.data.metadata.SubmissionPublication(
                sourceUrl = youtube,
                accessMode = SubmissionAccessMode.YOUTUBE,
                title = "Стара публікація",
                chapters = emptyList(),
                verifiedAt = 1L,
                submittedAt = 1L,
                submitterId = "uid-other"
            )
        )
        harness.flow.submit(youtube)

        assertEquals(
            ListenerSubmissionFlow.Verdict.Refused(ListenerSubmissionFlow.Reason.ALREADY_PUBLISHED),
            harness.flow.onPlaybackStarted("source-1")
        )
    }

    @Test
    fun `a missing shared base still imports locally and never publishes`() = runTest {
        val harness = Harness(withSharedBase = false)

        val start = harness.flow.submit(youtube)
        start as ListenerSubmissionFlow.Start.Imported
        assertFalse(start.publishable)
        assertEquals(
            ListenerSubmissionFlow.Verdict.NoPending,
            harness.flow.onPlaybackStarted("source-1")
        )
        assertTrue(harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `a missing profile still imports locally`() = runTest {
        val harness = Harness(submitter = null)
        val start = harness.flow.submit(youtube)
        start as ListenerSubmissionFlow.Start.Imported
        assertFalse(start.publishable)
        assertTrue(harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `every youtube shape reaches the engine as one canonical url`() = runTest {
        val harness = Harness()

        harness.flow.submit("https://youtu.be/abc123XYZ89?si=tracking")
        harness.flow.submit("https://music.youtube.com/watch?v=abc123XYZ89&t=42")

        assertEquals(
            listOf("https://www.youtube.com/watch?v=abc123XYZ89"),
            harness.fetchedUrls.distinct()
        )
        assertEquals(2, harness.fetchedUrls.size)
    }

    @Test
    fun `a link already in my library is a friendly state, not a refusal`() = runTest {
        val harness = Harness(
            importOutcome = ListenerSubmissionFlow.ImportOutcome(
                result = ListenerSubmissionFlow.ImportResult.ALREADY_ADDED,
                bookId = "book-1",
                sourceId = "source-1"
            )
        )

        val start = harness.flow.submit(youtube)

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        assertTrue((start as ListenerSubmissionFlow.Start.Imported).alreadyInLibrary)
    }

    @Test
    fun `a fresh import is never marked as already in the library`() = runTest {
        val start = Harness().flow.submit(youtube)

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        assertFalse((start as ListenerSubmissionFlow.Start.Imported).alreadyInLibrary)
    }

    @Test
    fun `a published submission can be corrected in the shared base`() = runTest {
        val harness = Harness()
        harness.flow.submit(youtube)
        assertEquals(ListenerSubmissionFlow.Verdict.Published, harness.flow.onPlaybackStarted("source-1"))
        val before = harness.store.submissionPuts.size

        val verdict = harness.flow.updatePublishedMetadata(
            bookId = "book-1",
            title = "Виправлена назва",
            author = "Справжній автор",
            narrator = null
        )

        assertEquals(ListenerSubmissionFlow.Verdict.Published, verdict)
        assertEquals(before + 1, harness.store.submissionPuts.size)
        // The document identity never moves: the correction lands under the
        // SAME normalized url the publication used.
        assertEquals(youtube, harness.store.submissionPuts.last().sourceUrl)
        val stored = harness.store.getSubmission(youtube)
        assertEquals("Виправлена назва", stored?.title)
        assertEquals("Справжній автор", stored?.author)
    }

    @Test
    fun `an unpublished book has nothing to update in the shared base`() = runTest {
        val harness = Harness()
        harness.flow.submit(youtube)

        assertNull(harness.flow.publishedSubmission("book-1"))
        assertEquals(
            ListenerSubmissionFlow.Verdict.NoPending,
            harness.flow.updatePublishedMetadata("book-1", "Назва", null, null)
        )
        assertTrue(harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `a shared-base failure keeps the publication published`() = runTest {
        val harness = Harness()
        harness.flow.submit(youtube)
        harness.flow.onPlaybackStarted("source-1")
        harness.store.throwOnPublishSubmission = true

        val verdict = harness.flow.updatePublishedMetadata("book-1", "Нова назва", null, null)

        assertEquals(
            ListenerSubmissionFlow.Verdict.Refused(ListenerSubmissionFlow.Reason.SHARED_BASE_UNAVAILABLE),
            verdict
        )
        assertNotNull("the row stays published", harness.flow.publishedSubmission("book-1"))
    }

    @Test
    fun `telegram post publishes metadata-only with the adapter parse as the quality bar`() = runTest {
        val harness = Harness(tgIdentity = tgIdentity)

        val start = harness.flow.submit(tgPost)

        assertEquals(ListenerSubmissionFlow.Start.MetadataPublished(), start)
        val publication = harness.store.submissionPuts.single()
        assertEquals(SubmissionAccessMode.TG_PREVIEW, publication.accessMode)
        assertEquals("Острів Дума", publication.title)
        assertEquals("Стівен Кінг", publication.author)
        assertEquals("Сергій Філатов", publication.narrator)
        assertEquals("https://cdn4.telesco.pe/file/cover.jpg", publication.coverUrl)
        assertEquals(0L, publication.verifiedAt)
        assertTrue("the preview exposes no audio", publication.chapters.isEmpty())
    }

    @Test
    fun `telegram without a parse or a shared base is refused honestly`() = runTest {
        assertEquals(
            ListenerSubmissionFlow.Start.Refused(ListenerSubmissionFlow.Reason.METADATA_FAILED, 10),
            Harness(tgIdentity = null).flow.submit(tgPost)
        )
        assertEquals(
            ListenerSubmissionFlow.Start.Refused(ListenerSubmissionFlow.Reason.SHARED_BASE_UNAVAILABLE, 10),
            Harness(tgIdentity = tgIdentity, withSharedBase = false).flow.submit(tgPost)
        )
    }

    @Test
    fun `a telegram post becomes my own card and arms the source watch`() = runTest {
        val cards = mutableListOf<String>()
        val watched = mutableListOf<Pair<String, String>>()
        val harness = Harness(
            tgIdentity = tgIdentity,
            importWatching = { _, identity ->
                cards += identity.title
                ListenerSubmissionFlow.WatchingImport(
                    bookId = "tg-book-1",
                    mergeKey = "mk-1",
                    workId = "work-1"
                )
            },
            watchSource = { mergeKey, workId -> watched += mergeKey to workId }
        )

        val start = harness.flow.submit(tgPost)

        assertEquals(ListenerSubmissionFlow.Start.MetadataPublished("tg-book-1", "mk-1"), start)
        assertEquals(listOf("Острів Дума"), cards)
        assertEquals(listOf("mk-1" to "work-1"), watched)
        assertTrue(harness.flow.watchingBookIds().contains("tg-book-1"))
        assertTrue("the card is not awaiting a verdict", harness.flow.awaitingBookIds().isEmpty())
    }

    @Test
    fun `a telegram without the card seam keeps the metadata-only path`() = runTest {
        val harness = Harness(tgIdentity = tgIdentity)

        assertEquals(
            ListenerSubmissionFlow.Start.MetadataPublished(),
            harness.flow.submit(tgPost)
        )
        assertTrue(harness.flow.watchingBookIds().isEmpty())
    }

    @Test
    fun `a failing telegram card never unpublishes the metadata`() = runTest {
        val harness = Harness(
            tgIdentity = tgIdentity,
            importWatching = { _, _ -> throw IllegalStateException("no card") }
        )

        val start = harness.flow.submit(tgPost)

        assertEquals(ListenerSubmissionFlow.Start.MetadataPublished(), start)
        assertEquals("the metadata-only publication stands", 1, harness.store.submissionPuts.size)
    }

    @Test
    fun `telegram duplicate is refused from the shared base`() = runTest {
        val harness = Harness(tgIdentity = tgIdentity)
        harness.flow.submit(tgPost)

        assertEquals(
            ListenerSubmissionFlow.Start.Refused(ListenerSubmissionFlow.Reason.ALREADY_PUBLISHED, 10),
            harness.flow.submit(tgPost)
        )
        assertEquals(1, harness.store.submissionPuts.size)
    }

    @Test
    fun `unsupported links and blanks are refused without any work`() = runTest {
        val harness = Harness()
        assertEquals(ListenerSubmissionFlow.Start.Unsupported, harness.flow.submit("https://example.com/x.mp3"))
        assertEquals(ListenerSubmissionFlow.Start.Unsupported, harness.flow.submit("   "))
        assertEquals(0, harness.fetchCalls)
        assertEquals(0, harness.importCalls)
    }

    @Test
    fun `the tg preview url points an exact post at its embed view`() {
        assertEquals(
            "https://t.me/bookchannel/42?embed=1",
            tgPreviewFetchUrl("https://t.me/bookchannel/42")
        )
        assertEquals(
            "https://t.me/s/bookchannel",
            tgPreviewFetchUrl("https://t.me/s/bookchannel")
        )
        assertNull(tgPreviewFetchUrl("https://youtube.com/watch?v=x"))
    }

    @Test
    fun `metadata failure never leaves a pending verdict`() = runTest {
        val harness = Harness(metadataJson = null)
        harness.flow.submit(youtube)
        assertEquals(
            ListenerSubmissionFlow.Verdict.NoPending,
            harness.flow.onPlaybackStarted("source-1")
        )
        assertTrue(harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `a consumed publication keeps its serialized identity stable across the flow`() = runTest {
        val harness = Harness()
        harness.flow.submit(youtube)
        harness.flow.onPlaybackStarted("source-1")
        val documentId = SubmissionPublicationCodec.documentId(youtube)
        assertEquals(
            documentId,
            SubmissionPublicationCodec.documentId(harness.store.submissionPuts.single().sourceUrl)
        )
    }

    @Test
    fun `two awaiting submissions settle independently`() = runTest {
        val store = InMemorySubmissionStateStore()
        val harnessStore = FakeSharedBookMetaStore()
        val verification = SubmissionVerification { 1_000L }
        val policy = SubmissionPolicy(harnessStore, verification) { 1_000L }
        val publisher = SubmissionPublisher(harnessStore, policy) { 1_000L }
        var index = 0
        val outcomes = listOf(
            ListenerSubmissionFlow.ImportOutcome(
                ListenerSubmissionFlow.ImportResult.IMPORTED, bookId = "book-1", sourceId = "source-1"
            ),
            ListenerSubmissionFlow.ImportOutcome(
                ListenerSubmissionFlow.ImportResult.IMPORTED, bookId = "book-2", sourceId = "source-2"
            )
        )
        val flow = ListenerSubmissionFlow(
            fetchMetadata = { """{"id":"v1","title":"Книга"}""" },
            importYouTube = { _, _, _ -> outcomes[index++] },
            fetchTgIdentity = { null },
            publisher = publisher,
            verification = verification,
            remainingToday = { 10 },
            submitterId = { "uid-1" },
            store = store
        )
        val second = "https://www.youtube.com/playlist?list=PLsecond"

        flow.submit(youtube)
        flow.submit(second)

        assertEquals(setOf("book-1", "book-2"), flow.awaitingBookIds())

        assertEquals(ListenerSubmissionFlow.Verdict.Published, flow.onPlaybackStarted("source-1"))
        assertEquals(setOf("book-2"), flow.awaitingBookIds())

        assertEquals(ListenerSubmissionFlow.Verdict.Published, flow.onPlaybackStarted("source-2"))
        assertTrue(flow.awaitingBookIds().isEmpty())
        assertEquals(2, harnessStore.submissionPuts.size)
    }

    @Test
    fun `a restart sharing the store still settles the awaiting submission`() = runTest {
        val store = InMemorySubmissionStateStore()
        val harnessStore = FakeSharedBookMetaStore()
        val verification = SubmissionVerification { 1_000L }
        val policy = SubmissionPolicy(harnessStore, verification) { 1_000L }
        val publisher = SubmissionPublisher(harnessStore, policy) { 1_000L }
        fun flow() = ListenerSubmissionFlow(
            fetchMetadata = { """{"id":"v1","title":"Книга"}""" },
            importYouTube = { _, _, _ ->
                ListenerSubmissionFlow.ImportOutcome(
                    ListenerSubmissionFlow.ImportResult.IMPORTED, bookId = "book-1", sourceId = "source-1"
                )
            },
            fetchTgIdentity = { null },
            publisher = publisher,
            verification = verification,
            remainingToday = { 10 },
            submitterId = { "uid-1" },
            store = store
        )
        flow().submit(youtube)

        // The process restarted: a fresh flow over the same store.
        assertEquals(ListenerSubmissionFlow.Verdict.Published, flow().onPlaybackStarted("source-1"))
        assertEquals(1, harnessStore.submissionPuts.size)
    }
}
