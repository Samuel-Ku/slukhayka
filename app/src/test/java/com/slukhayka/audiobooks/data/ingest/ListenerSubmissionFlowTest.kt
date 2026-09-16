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
        watchSource: (suspend (String, String) -> Unit)? = null,
        online: Boolean = true
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

        /** Spec-53 T9 — the preview corrections each submit carried in. */
        val capturedEdits = mutableListOf<ListenerSubmissionFlow.PreviewEdits?>()

        /** Spec-53 T11 — the playlist selection each submit carried in. */
        val capturedSelections = mutableListOf<Set<String>?>()

        val flow = ListenerSubmissionFlow(
            fetchMetadata = { url -> fetchCalls++; fetchedUrls += url; metadataJson },
            importYouTube = { _, _, _, edits, selectedWatchUrls ->
                importCalls++
                capturedEdits += edits
                capturedSelections += selectedWatchUrls
                importOutcome
            },
            fetchTgIdentity = { tgCalls++; tgIdentity },
            importWatchingTelegram = importWatching,
            watchSource = watchSource,
            publisher = if (withSharedBase) publisher else null,
            verification = if (withSharedBase) verification else null,
            remainingToday = { remaining },
            submitterId = { submitter },
            store = stateStore,
            isOnline = { online }
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

        assertEquals(ListenerSubmissionFlow.Verdict.PendingModeration, verdict)
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
    fun `a queued candidate is not a published card, so there is nothing to correct`() = runTest {
        val harness = Harness()
        harness.flow.submit(youtube)
        assertEquals(
            ListenerSubmissionFlow.Verdict.PendingModeration,
            harness.flow.onPlaybackStarted("source-1")
        )
        val queued = harness.store.candidatePuts.size
        assertEquals("the verified submission is a queued candidate", 1, queued)

        // Moderation T1/T4 (#834/#837) — the queue belongs to the curator once
        // the document exists, and the local row is NOT published: there is no
        // client correction route, so the honest answer is "nothing pending to
        // update" instead of a silent rewrite of the queue document.
        val verdict = harness.flow.updatePublishedMetadata(
            bookId = "book-1",
            title = "Виправлена назва",
            author = "Справжній автор",
            narrator = null
        )

        assertEquals(ListenerSubmissionFlow.Verdict.NoPending, verdict)
        assertEquals("no second document appeared", queued, harness.store.candidatePuts.size)
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
    fun `a queued candidate stays queued when nothing can be corrected`() = runTest {
        val harness = Harness()
        harness.flow.submit(youtube)
        assertEquals(
            ListenerSubmissionFlow.Verdict.PendingModeration,
            harness.flow.onPlaybackStarted("source-1")
        )
        harness.store.throwOnPublishSubmission = true

        // #834/#837 — there is no published card yet (the candidate waits), so
        // the correction door honestly reports "nothing to update" and the
        // queued candidate is untouched by a failing write.
        val verdict = harness.flow.updatePublishedMetadata("book-1", "Нова назва", null, null)

        assertEquals(ListenerSubmissionFlow.Verdict.NoPending, verdict)
        assertEquals("the candidate is still queued", 1, harness.store.candidatePuts.size)
    }

    @Test
    fun `an offline paste is deferred instead of failing`() = runTest {
        val harness = Harness(online = false)

        val start = harness.flow.submit(youtube)

        assertEquals(ListenerSubmissionFlow.Start.Deferred(youtube), start)
        assertEquals(1, harness.flow.deferredSubmissions().size)
        assertEquals("nothing was fetched", 0, harness.fetchCalls)
        assertEquals("nothing was imported", 0, harness.importCalls)
        assertTrue(harness.flow.awaitingBookIds().isEmpty())
    }

    @Test
    fun `the same link pasted offline twice is one queue entry`() = runTest {
        val harness = Harness(online = false)

        harness.flow.submit("https://youtu.be/abc123XYZ89?si=one")
        harness.flow.submit("https://www.youtube.com/watch?v=abc123XYZ89&t=30")

        assertEquals(1, harness.flow.deferredSubmissions().size)
    }

    @Test
    fun `an explicit remove drops the queued link`() = runTest {
        val harness = Harness(online = false)
        harness.flow.submit(youtube)
        val queued = harness.flow.deferredSubmissions().single()

        harness.flow.removeDeferred(queued.sourceId)

        assertTrue(harness.flow.deferredSubmissions().isEmpty())
    }

    @Test
    fun `the queue processes once when the network returns`() = runTest {
        val online = java.util.concurrent.atomic.AtomicBoolean(false)
        val flow = ListenerSubmissionFlow(
            fetchMetadata = { """{"id":"v1","title":"Книга"}""" },
            importYouTube = { _, _, _, _, _ ->
                ListenerSubmissionFlow.ImportOutcome(
                    ListenerSubmissionFlow.ImportResult.IMPORTED,
                    bookId = "book-1",
                    sourceId = "source-1"
                )
            },
            fetchTgIdentity = { null },
            publisher = null,
            verification = null,
            remainingToday = { 10 },
            submitterId = { "uid-1" },
            store = InMemorySubmissionStateStore(),
            isOnline = { online.get() }
        )
        flow.submit(youtube)
        assertEquals(1, flow.deferredSubmissions().size)

        // Still offline: a pass processes nothing and retries nothing.
        assertTrue(flow.processDeferred().isEmpty())
        assertEquals(1, flow.deferredSubmissions().size)

        online.set(true)
        val outcomes = flow.processDeferred()

        assertTrue(outcomes.single() is ListenerSubmissionFlow.Start.Imported)
        assertTrue("the processed link left the queue", flow.deferredSubmissions().isEmpty())
        // A second pass has nothing to do — a processed link never runs twice.
        assertTrue(flow.processDeferred().isEmpty())
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
            importYouTube = { _, _, _, _, _ -> outcomes[index++] },
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

        assertEquals(ListenerSubmissionFlow.Verdict.PendingModeration, flow.onPlaybackStarted("source-1"))
        assertEquals(setOf("book-2"), flow.awaitingBookIds())

        assertEquals(ListenerSubmissionFlow.Verdict.PendingModeration, flow.onPlaybackStarted("source-2"))
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
            importYouTube = { _, _, _, _, _ ->
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
        assertEquals(ListenerSubmissionFlow.Verdict.PendingModeration, flow().onPlaybackStarted("source-1"))
        assertEquals(1, harnessStore.submissionPuts.size)
    }

    private val previewPlaylistJson = """
        {
          "id": "PLprev",
          "title": "Проста книга",
          "thumbnail": "https://i.ytimg.com/vi/x/hqdefault.jpg",
          "entries": [
            {"id": "aaa111BBB22", "url": "https://www.youtube.com/watch?v=aaa111BBB22", "title": "Розділ 1", "duration": 600},
            {"id": "bbb222CCC33", "url": "https://www.youtube.com/watch?v=bbb222CCC33", "title": "Розділ 2", "duration": 900}
          ]
        }
    """.trimIndent()

    private val previewVideoJson = """{"id":"vid1","title":"Одне відео","duration":3725}"""

    @Test
    fun `preview shows real engine data and nothing invented`() = runTest {
        val harness = Harness(metadataJson = previewPlaylistJson)

        val preview = harness.flow.previewSubmission("https://www.youtube.com/playlist?list=PLprev")!!

        assertEquals("https://www.youtube.com/playlist?list=PLprev", preview.url)
        assertEquals(ListenerSubmissionFlow.PreviewKind.YOUTUBE_PLAYLIST, preview.kind)
        assertEquals(2, preview.chapterCount)
        assertEquals(1500L, preview.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/x/hqdefault.jpg", preview.coverUrl)
        assertEquals("Проста книга", preview.title)
        assertEquals("zero side effects: no import, no budget, no store", 0, harness.importCalls)
        assertTrue(harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `single video preview carries its own duration`() = runTest {
        val harness = Harness(metadataJson = previewVideoJson)

        val preview = harness.flow.previewSubmission("https://youtu.be/abc123XYZ89")!!

        assertEquals(ListenerSubmissionFlow.PreviewKind.YOUTUBE_VIDEO, preview.kind)
        assertEquals(1, preview.chapterCount)
        assertEquals(3725L, preview.durationSeconds)
        assertNull(preview.coverUrl)
    }

    @Test
    fun `telegram preview is honest about having no audio`() = runTest {
        val harness = Harness(tgIdentity = tgIdentity)

        val preview = harness.flow.previewSubmission(tgPost)!!

        assertEquals(ListenerSubmissionFlow.PreviewKind.TELEGRAM_POST, preview.kind)
        assertEquals("Острів Дума", preview.title)
        assertEquals("Стівен Кінг", preview.author)
        assertEquals("https://cdn4.telesco.pe/file/cover.jpg", preview.coverUrl)
        assertNull(preview.durationSeconds)
        assertEquals(0, preview.chapterCount)
    }

    @Test
    fun `preview is null when there is nothing honest to show`() = runTest {
        assertNull(Harness().flow.previewSubmission("https://example.com/not-supported"))
        assertNull(Harness(online = false).flow.previewSubmission(youtube))
        assertNull(Harness(metadataJson = null).flow.previewSubmission(youtube))
    }

    @Test
    fun `preview edits ride the submit into the import door`() = runTest {
        val harness = Harness()
        val edits = ListenerSubmissionFlow.PreviewEdits(
            title = "Виправлена",
            author = "Автор",
            narrator = "Начитка"
        )

        harness.flow.submit(youtube, edits)

        assertEquals(listOf(edits), harness.capturedEdits)
    }

    @Test
    fun `one-tap submit carries no edits`() = runTest {
        val harness = Harness()

        harness.flow.submit(youtube)

        assertEquals(listOf(null), harness.capturedEdits)
    }

    @Test
    fun `channel links are named honestly`() {
        val flow = Harness().flow

        assertTrue(flow.isChannelLink("https://www.youtube.com/@SomeChannel"))
        assertTrue(flow.isChannelLink("https://www.youtube.com/channel/UCabc123/videos"))
        assertTrue(flow.isChannelLink("https://www.youtube.com/c/SomeName?si=tracking"))
        assertTrue(flow.isChannelLink("https://www.youtube.com/user/SomeName"))
        assertFalse(flow.isChannelLink("https://www.youtube.com/watch?v=abc123XYZ89"))
        assertFalse(flow.isChannelLink("https://www.youtube.com/playlist?list=PLprev"))
        assertFalse(flow.isChannelLink("https://youtu.be/abc123XYZ89"))
        assertFalse(flow.isChannelLink("https://t.me/bookchannel/42"))
        assertFalse(flow.isChannelLink("not a link"))
    }

    @Test
    fun `a channel link opens the card instead of importing`() = runTest {
        val harness = Harness()

        val start = harness.flow.submit("https://www.youtube.com/@SomeChannel")

        assertEquals(
            ListenerSubmissionFlow.Start.ChannelLink("https://www.youtube.com/@SomeChannel"),
            start
        )
        assertEquals("opening the card spends no budget and no fetch", 0, harness.fetchCalls)
        assertEquals(0, harness.importCalls)
    }

    @Test
    fun `playlist members are the import door's own watch urls`() = runTest {
        val harness = Harness(metadataJson = previewPlaylistJson)

        val members = harness.flow.playlistMemberUrls("https://www.youtube.com/playlist?list=PLprev")

        assertEquals(
            setOf(
                "https://www.youtube.com/watch?v=aaa111BBB22",
                "https://www.youtube.com/watch?v=bbb222CCC33"
            ),
            members
        )
        assertEquals("a read never imports", 0, harness.importCalls)
    }

    @Test
    fun `playlist members are null when the engine saw nothing`() = runTest {
        assertNull(Harness(metadataJson = null).flow.playlistMemberUrls(youtube))
        assertNull(Harness(online = false).flow.playlistMemberUrls(youtube))
    }

    @Test
    fun `a queued channel link waits for a human instead of a headless pass`() = runTest {
        val store = InMemorySubmissionStateStore()
        Harness(online = false, stateStore = store).flow
            .submit("https://www.youtube.com/@SomeChannel")

        val online = Harness(online = true, stateStore = store)
        val outcomes = online.flow.processDeferred()

        assertTrue("the channel row stays queued", outcomes.isEmpty())
        assertEquals(1, online.flow.deferredSubmissions().size)
    }

    @Test
    fun `a playlist preview carries its ordered pickable positions`() = runTest {
        val harness = Harness(metadataJson = previewPlaylistJson)

        val preview = harness.flow.previewSubmission("https://www.youtube.com/playlist?list=PLprev")!!

        assertEquals(
            listOf(
                ListenerSubmissionFlow.PreviewEntry(
                    watchUrl = "https://www.youtube.com/watch?v=aaa111BBB22",
                    title = "Розділ 1",
                    durationSeconds = 600L
                ),
                ListenerSubmissionFlow.PreviewEntry(
                    watchUrl = "https://www.youtube.com/watch?v=bbb222CCC33",
                    title = "Розділ 2",
                    durationSeconds = 900L
                )
            ),
            preview.entries
        )
    }

    @Test
    fun `a single video preview has nothing to pick`() = runTest {
        val harness = Harness(metadataJson = previewVideoJson)

        assertTrue(harness.flow.previewSubmission("https://youtu.be/abc123XYZ89")!!.entries.isEmpty())
    }

    @Test
    fun `the picked positions ride the submit into the import door`() = runTest {
        val harness = Harness()
        val picked = setOf("https://www.youtube.com/watch?v=aaa111BBB22")

        harness.flow.submit(youtube, selectedWatchUrls = picked)

        assertEquals(listOf(picked), harness.capturedSelections)
    }

    @Test
    fun `an empty selection is refused instead of importing the whole playlist`() = runTest {
        val harness = Harness()

        val start = harness.flow.submit(youtube, selectedWatchUrls = emptySet())

        assertEquals(
            ListenerSubmissionFlow.Start.Refused(
                ListenerSubmissionFlow.Reason.NO_PLAYABLE_TRACKS,
                10
            ),
            start
        )
        assertEquals("nothing reached the import door", 0, harness.importCalls)
        assertEquals("no metadata fetch was needed either", 0, harness.fetchCalls)
    }

    @Test
    fun `a one-tap submit carries no selection`() = runTest {
        val harness = Harness()

        harness.flow.submit(youtube)

        assertEquals(listOf(null), harness.capturedSelections)
    }

    // --- Spec-53 T12: publication deferred to tomorrow ----------------------

    /**
     * The flow over a clock the test can move: the same wiring the production
     * composition uses (one policy, one publisher, one carrier), so a day
     * boundary is a real boundary, not a stubbed verdict.
     */
    private class ClockHarness {
        var now = 10_000L
        val store = FakeSharedBookMetaStore()
        val verification = SubmissionVerification { now }
        val policy = SubmissionPolicy(store, verification) { now }
        val publisher = SubmissionPublisher(store, policy) { now }
        val stateStore = InMemorySubmissionStateStore()

        val flow = ListenerSubmissionFlow(
            fetchMetadata = { """{"id":"v1","title":"Гаррі Поттер 1 — АудіоКниги Українською"}""" },
            importYouTube = { _, _, _, _, _ ->
                ListenerSubmissionFlow.ImportOutcome(
                    ListenerSubmissionFlow.ImportResult.IMPORTED,
                    bookId = "book-1",
                    sourceId = "source-1"
                )
            },
            fetchTgIdentity = { null },
            publisher = publisher,
            verification = verification,
            remainingToday = { 10 },
            submitterId = { "uid-1" },
            store = stateStore
        )

        suspend fun spendTheDay() {
            repeat(SubmissionPolicy.DAILY_SUBMISSION_LIMIT.toInt()) {
                store.incrementSubmissionCount("uid-1", SubmissionPolicy.dayKeyOf(now))
            }
        }
    }

    @Test
    fun `a verdict on a spent day defers the publication instead of refusing it`() = runTest {
        val harness = ClockHarness()
        harness.spendTheDay()
        harness.flow.submit(youtube)

        val verdict = harness.flow.onPlaybackStarted("source-1")

        assertEquals(ListenerSubmissionFlow.Verdict.DeferredPublication, verdict)
        assertEquals(setOf("book-1"), harness.flow.deferredPublicationBookIds())
        assertTrue("nothing left the device", harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `a still-spent day keeps the promise waiting and writes nothing`() = runTest {
        val harness = ClockHarness()
        harness.spendTheDay()
        harness.flow.submit(youtube)
        harness.flow.onPlaybackStarted("source-1")

        val verdicts = harness.flow.publishDeferredPublications()

        assertEquals(listOf(ListenerSubmissionFlow.Verdict.DeferredPublication), verdicts)
        assertEquals(setOf("book-1"), harness.flow.deferredPublicationBookIds())
        assertTrue(harness.store.submissionPuts.isEmpty())
    }

    @Test
    fun `the next day publishes the stored verdict with no second playback`() = runTest {
        val harness = ClockHarness()
        harness.spendTheDay()
        harness.flow.submit(youtube)
        assertEquals(
            ListenerSubmissionFlow.Verdict.DeferredPublication,
            harness.flow.onPlaybackStarted("source-1")
        )

        // The day rolls over; NOTHING plays again.
        harness.now += 86_400_000L
        val verdicts = harness.flow.publishDeferredPublications()

        assertEquals(listOf(ListenerSubmissionFlow.Verdict.PendingModeration), verdicts)
        val publication = harness.store.submissionPuts.single()
        assertEquals(youtube, publication.sourceUrl)
        assertEquals("Гаррі Поттер 1", publication.title)
        assertTrue("the promise is settled", harness.flow.deferredPublicationBookIds().isEmpty())
        assertTrue(harness.flow.awaitingBookIds().isEmpty())
    }

    @Test
    fun `a document another device published settles the promise without a duplicate`() = runTest {
        val harness = ClockHarness()
        harness.spendTheDay()
        harness.flow.submit(youtube)
        harness.flow.onPlaybackStarted("source-1")

        // Another device got there first while this one waited.
        harness.store.publishSubmission(
            com.slukhayka.audiobooks.data.metadata.SubmissionPublication(
                sourceUrl = youtube,
                accessMode = com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode.YOUTUBE,
                title = "Гаррі Поттер 1",
                chapters = emptyList(),
                verifiedAt = harness.now,
                submittedAt = harness.now,
                submitterId = "uid-other"
            )
        )
        harness.now += 86_400_000L
        val verdicts = harness.flow.publishDeferredPublications()

        assertEquals(listOf(ListenerSubmissionFlow.Verdict.PendingModeration), verdicts)
        assertEquals("no duplicate document", 1, harness.store.submissionPuts.size)
        assertTrue(harness.flow.deferredPublicationBookIds().isEmpty())
    }
}
