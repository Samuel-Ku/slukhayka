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
        online: Boolean = true,
        /** #1487 — the registry answer for the pasted book page, or null. */
        knownLink: KnownSourceLink? = null,
        /** #1487 — override the resolver (tests the REAL `sourceIdForUrl`). */
        knownResolver: ((String) -> KnownSourceLink?)? = null,
        /** #1487 slice 2 — the page the probe returns for an unknown host. */
        probedPage: ProbedPage? = null,
        probedImport: ListenerSubmissionFlow.ImportOutcome? =
            ListenerSubmissionFlow.ImportOutcome(
                result = ListenerSubmissionFlow.ImportResult.IMPORTED,
                bookId = "book-probed",
                sourceId = "unknown"
            ),
        /** #1487 — what the ordinary source import returns for that page. */
        knownImport: ListenerSubmissionFlow.ImportOutcome? = ListenerSubmissionFlow.ImportOutcome(
            result = ListenerSubmissionFlow.ImportResult.IMPORTED,
            bookId = "book-known",
            sourceId = "audiobookcoua"
        ),
        withKnownImporter: Boolean = true
    ) {
        val store = FakeSharedBookMetaStore()
        val verification = SubmissionVerification { 1_000L }
        val policy = SubmissionPolicy(store, verification) { 1_000L }
        val publisher = SubmissionPublisher(store, policy) { 1_000L }

        var fetchCalls = 0
        var importCalls = 0
        var tgCalls = 0

        /** #1487 — how often the known-source import seam was reached, and with what. */
        var knownImportCalls = 0
        val knownImportedUrls = mutableListOf<String>()

        /** #1487 slice 2 — how the single page request and the persistence went. */
        var probeCalls = 0
        val probedUrls = mutableListOf<String>()
        var probedImportCalls = 0
        val probedPages = mutableListOf<ProbedPage>()

        val probedImporter: (suspend (ProbedPage) -> ListenerSubmissionFlow.ImportOutcome?)? =
            { page ->
                probedImportCalls++
                probedPages += page
                probedImport
            }

        val knownImporter: (suspend (KnownSourceLink) -> ListenerSubmissionFlow.ImportOutcome?)? =
            if (withKnownImporter) {
                { link ->
                    knownImportCalls++
                    knownImportedUrls += link.url
                    knownImport
                }
            } else {
                null
            }

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
            isOnline = { online },
            resolveKnownSource = knownResolver ?: { url ->
                knownLink?.takeIf { KnownSourceLinkResolver.canonicalUrl(url) == it.url }
            },
            importKnownSource = knownImporter,
            probePage = { url ->
                probeCalls++
                probedUrls += url
                probedPage?.copy(url = url)
            },
            importProbedPage = probedImporter
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
        // #1487 slice 2 — an unknown http page is a probe candidate, not
        // "unsupported": the probe decides read/no-audio/playable.
        assertEquals(ListenerSubmissionFlow.Kind.PAGE, flow.classify("https://example.com/book.mp3"))
        assertEquals(ListenerSubmissionFlow.Kind.UNSUPPORTED, flow.classify("not a link"))
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
        harness.store.seedSubmission(
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
        // #1487 slice 2 — an http page is probed; a non-link stays unsupported.
        assertEquals(
            ListenerSubmissionFlow.Start.Refused(
                ListenerSubmissionFlow.Reason.PAGE_UNREADABLE,
                10,
                "https://example.com/book"
            ),
            harness.flow.submit("https://example.com/book")
        )
        assertEquals(ListenerSubmissionFlow.Start.Unsupported, harness.flow.submit("   "))
        assertEquals(ListenerSubmissionFlow.Start.Unsupported, harness.flow.submit("file:///tmp/book.mp3"))
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
        harness.store.seedSubmission(
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

    // --- #1487 (slice 1): a book page of a known source --------------------

    private val knownBook = KnownSourceLink(
        sourceId = "audiobookcoua",
        url = "https://audiobook.co.ua/doktor-son-stiven-king"
    )

    private val knownBookLink = "https://audiobook.co.ua/doktor-son-stiven-king/"

    @Test
    fun `a known source book page is classified as a source link`() {
        val harness = Harness(knownLink = knownBook)

        assertEquals(ListenerSubmissionFlow.Kind.SOURCE, harness.flow.classify(knownBookLink))
        assertEquals(ListenerSubmissionFlow.Kind.SOURCE, harness.flow.classify(knownBook.url))
        // The two existing paths are not reclassified by the new branch.
        assertEquals(ListenerSubmissionFlow.Kind.YOUTUBE, harness.flow.classify(youtube))
        assertEquals(ListenerSubmissionFlow.Kind.TELEGRAM, harness.flow.classify(tgPost))
    }

    @Test
    fun `telegram stays telegram even though the registry claims the t me host`() {
        // The regression the first review caught: the registry has a
        // `telegram` source with homeUrl t.me/slukhayka, so a bare resolver
        // check would hide the WORKING Telegram preview. The predicate must
        // keep classify's order — YouTube/Telegram first.
        val harness = Harness(
            knownResolver = { url ->
                KnownSourceLinkResolver.resolve(url) {
                    com.slukhayka.audiobooks.data.source.sourceIdForUrl(it)
                }
            }
        )

        assertFalse(harness.flow.isKnownSourceLink("https://t.me/slukhayka/42"))
        assertEquals(
            ListenerSubmissionFlow.Kind.TELEGRAM,
            harness.flow.classify("https://t.me/slukhayka/42")
        )
        assertFalse(harness.flow.isKnownSourceLink("https://youtu.be/abc12345678"))
        assertTrue(harness.flow.isKnownSourceLink("https://audiobook.co.ua/doktor-son"))
        assertEquals(
            ListenerSubmissionFlow.Kind.SOURCE,
            harness.flow.classify("https://audiobook.co.ua/doktor-son")
        )
    }

    @Test
    fun `a known source page imports like the ordinary door and never publishes`() = runTest {
        val harness = Harness(knownLink = knownBook)

        val start = harness.flow.submit(knownBookLink)

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        start as ListenerSubmissionFlow.Start.Imported
        assertEquals("book-known", start.bookId)
        assertEquals("audiobookcoua", start.sourceId)
        // Not a listener submission: the catalogue already has the book.
        assertFalse(start.publishable)
        assertFalse(start.alreadyInLibrary)
        assertTrue(start.knownSource)
        assertEquals(10, start.remainingToday)
        assertEquals(1, harness.knownImportCalls)
        assertEquals(listOf(knownBook.url), harness.knownImportedUrls)
        // The YouTube engine was never asked: the two paths stay separate.
        assertEquals(0, harness.fetchCalls)
        assertEquals(0, harness.importCalls)
    }

    @Test
    fun `a known source page already in the library says so`() = runTest {
        val harness = Harness(
            knownLink = knownBook,
            knownImport = ListenerSubmissionFlow.ImportOutcome(
                result = ListenerSubmissionFlow.ImportResult.ALREADY_ADDED,
                bookId = "book-known",
                sourceId = "audiobookcoua"
            )
        )

        val start = harness.flow.submit(knownBookLink)

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        assertTrue((start as ListenerSubmissionFlow.Start.Imported).alreadyInLibrary)
    }

    @Test
    fun `a failed known source import is refused rather than unsupported`() = runTest {
        val harness = Harness(
            knownLink = knownBook,
            knownImport = null
        )

        val start = harness.flow.submit(knownBookLink)

        assertTrue(start is ListenerSubmissionFlow.Start.Refused)
        assertEquals(
            ListenerSubmissionFlow.Reason.IMPORT_FAILED,
            (start as ListenerSubmissionFlow.Start.Refused).reason
        )
    }

    @Test
    fun `a known source page without an import seam is an honest import failure`() = runTest {
        val harness = Harness(knownLink = knownBook, withKnownImporter = false)

        val start = harness.flow.submit(knownBookLink)

        assertTrue(start is ListenerSubmissionFlow.Start.Refused)
        assertEquals(
            ListenerSubmissionFlow.Reason.IMPORT_FAILED,
            (start as ListenerSubmissionFlow.Start.Refused).reason
        )
    }

    @Test
    fun `a known source page pasted offline waits in the visible queue`() = runTest {
        val harness = Harness(knownLink = knownBook, online = false)

        val start = harness.flow.submit(knownBookLink)

        assertTrue(start is ListenerSubmissionFlow.Start.Deferred)
        assertEquals(
            listOf(knownBook.url),
            harness.flow.deferredSubmissions().map { it.url }
        )
        assertEquals(0, harness.knownImportCalls)
    }

    // --- #1487 (slice 2): a probed page of an unknown host -----------------

    private val probedBook = ProbedPage(
        url = "https://unknown-site.example/book",
        title = "Неначе сон",
        author = "Іван Франко",
        coverImageUrl = "https://unknown-site.example/cover.jpg",
        description = "Опис сторінки",
        audios = listOf(
            ProbedAudio("https://cdn.unknown-site.example/part-1.mp3", "Частина 1"),
            ProbedAudio("https://cdn.unknown-site.example/part-2.mp3", "Частина 2")
        )
    )

    @Test
    fun `an unknown page with audio becomes a local probed card`() = runTest {
        val harness = Harness(probedPage = probedBook)

        val start = harness.flow.submit("https://unknown-site.example/book")

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        start as ListenerSubmissionFlow.Start.Imported
        assertEquals("book-probed", start.bookId)
        assertEquals("unknown", start.sourceId)
        assertFalse(start.publishable)
        assertFalse(start.knownSource)
        assertTrue(start.probedPage)
        assertFalse(start.alreadyInLibrary)
        assertEquals(1, harness.probeCalls)
        assertEquals(listOf("https://unknown-site.example/book"), harness.probedUrls)
        assertEquals(1, harness.probedImportCalls)
        // The YouTube engine was never asked: unknown pages have their own door.
        assertEquals(0, harness.fetchCalls)
    }

    @Test
    fun `a probed page pasted in another shape is probed by its canonical url`() = runTest {
        val harness = Harness(probedPage = probedBook)

        harness.flow.submit("http://www.unknown-site.example/book/?utm_source=chat")

        assertEquals(1, harness.probeCalls)
        assertEquals(listOf("https://unknown-site.example/book"), harness.probedUrls)
    }

    @Test
    fun `a readable page without audio is refused with its browser door`() = runTest {
        val harness = Harness(
            probedPage = probedBook.copy(audios = emptyList())
        )

        val start = harness.flow.submit("https://unknown-site.example/book")

        assertEquals(
            ListenerSubmissionFlow.Start.Refused(
                ListenerSubmissionFlow.Reason.PAGE_NO_AUDIO,
                10,
                "https://unknown-site.example/book"
            ),
            start
        )
        // Nothing was persisted for a page with no audio.
        assertEquals(0, harness.probedImportCalls)
    }

    @Test
    fun `an unreadable page is refused with its browser door`() = runTest {
        val harness = Harness(probedPage = null)

        val start = harness.flow.submit("https://unknown-site.example/book")

        assertEquals(
            ListenerSubmissionFlow.Start.Refused(
                ListenerSubmissionFlow.Reason.PAGE_UNREADABLE,
                10,
                "https://unknown-site.example/book"
            ),
            start
        )
        assertEquals(0, harness.probedImportCalls)
    }

    @Test
    fun `a probed import failure is an honest import refusal`() = runTest {
        val harness = Harness(probedPage = probedBook, probedImport = null)

        val start = harness.flow.submit("https://unknown-site.example/book")

        assertEquals(
            ListenerSubmissionFlow.Start.Refused(
                ListenerSubmissionFlow.Reason.IMPORT_FAILED,
                10
            ),
            start
        )
    }

    @Test
    fun `an already imported probed page says so`() = runTest {
        val harness = Harness(
            probedPage = probedBook,
            probedImport = ListenerSubmissionFlow.ImportOutcome(
                result = ListenerSubmissionFlow.ImportResult.ALREADY_ADDED,
                bookId = "book-probed",
                sourceId = "unknown"
            )
        )

        val start = harness.flow.submit("https://unknown-site.example/book")

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        assertTrue((start as ListenerSubmissionFlow.Start.Imported).alreadyInLibrary)
        assertTrue(start.probedPage)
    }

    @Test
    fun `an unknown page pasted offline waits in the visible queue`() = runTest {
        val harness = Harness(probedPage = probedBook, online = false)

        val start = harness.flow.submit("https://unknown-site.example/book")

        assertTrue(start is ListenerSubmissionFlow.Start.Deferred)
        assertEquals(
            listOf("https://unknown-site.example/book"),
            harness.flow.deferredSubmissions().map { it.url }
        )
        assertEquals(0, harness.probeCalls)
    }

    @Test
    fun `a direct audio link becomes a one-chapter card without a page request`() = runTest {
        val harness = Harness()

        val start = harness.flow.submit("https://cdn.example/Part%20one.mp3")

        assertTrue(start is ListenerSubmissionFlow.Start.Imported)
        assertTrue((start as ListenerSubmissionFlow.Start.Imported).probedPage)
        // The file IS the chapter: no page was fetched for it.
        assertEquals(0, harness.probeCalls)
        assertEquals(1, harness.probedImportCalls)
        val page = harness.probedPages.single()
        assertEquals("Part one", page.title)
        assertEquals(listOf("https://cdn.example/Part%20one.mp3"), page.audios.map { it.url })
    }

    @Test
    fun `an unidentified page is refused instead of imported without a title`() = runTest {
        val harness = Harness(
            probedPage = probedBook.copy(title = null)
        )

        val start = harness.flow.submit("https://unknown-site.example/book")

        assertEquals(
            ListenerSubmissionFlow.Start.Refused(
                ListenerSubmissionFlow.Reason.PAGE_UNREADABLE,
                10,
                "https://unknown-site.example/book"
            ),
            start
        )
        assertEquals(0, harness.probedImportCalls)
    }
}
