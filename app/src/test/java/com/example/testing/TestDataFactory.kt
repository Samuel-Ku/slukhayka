package com.example.testing

import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.ListeningStatEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.data.db.SourceEntity
import com.example.data.source.streamOnlyFor

/**
 * Deterministic Room-entity fixtures for JVM unit tests (GitHub issue #6).
 *
 * Design decisions taken on the ticket:
 * - **Factory, not production seed re-use.** The catalogue is never seeded
 *   from a hardcoded 4read catalogue and hits the network in its `init` block.
 *   Reusing that would couple every test to production content that changes.
 *   This factory constructs entities directly -- no DAO, no database, no I/O.
 * - **Scope: 3 books x 3 chapters = 9 chapters.** Enough to exercise
 *   next/previous chapter, end-of-book, and multi-book list rendering without
 *   the ceremony of the full production catalogue.
 * - **No wall clock.** `BookmarkEntity` and `PlaybackProgressEntity` default
 *   their timestamps to `System.currentTimeMillis()`, which makes equality
 *   assertions and snapshot tests flaky. Every fixture passes [FIXED_CLOCK_MS]
 *   explicitly.
 * - **Unroutable stream URLs.** Hosts use the RFC 2606 reserved `.invalid` TLD,
 *   so a test that accidentally performs real I/O fails fast on DNS instead of
 *   quietly reaching the internet.
 */
object TestDataFactory {

    /** Number of books produced by [dataBooks]. */
    const val BOOK_COUNT: Int = 3

    /** Number of chapters produced per book by [dataChapters]. */
    const val CHAPTERS_PER_BOOK: Int = 3

    /** Total chapters produced by [dataChapters]: 9. */
    const val TOTAL_CHAPTERS: Int = BOOK_COUNT * CHAPTERS_PER_BOOK

    /** Frozen instant (2023-11-14T22:13:20Z) used for every timestamp. */
    const val FIXED_CLOCK_MS: Long = 1_700_000_000_000L

    /** Frozen ISO date matching [FIXED_CLOCK_MS], for listening stats. */
    const val FIXED_DATE_ISO: String = "2023-11-14"

    private const val BASE_CHAPTER_SECONDS: Long = 600L

    /**
     * Deliberately larger than `PER_CHAPTER_SECONDS_STEP * CHAPTERS_PER_BOOK` so
     * that no two of the nine chapters share a duration -- see
     * `TestDataFactoryTest.chapter durations differ...`.
     */
    private const val PER_BOOK_SECONDS_STEP: Long = 300L
    private const val PER_CHAPTER_SECONDS_STEP: Long = 60L
    // The host still ends in `.invalid` (RFC 2606 — unroutable, fails fast on
    // accidental real I/O) but contains `4read.org` so the library model's
    // `sourceIdForUrl` badges these 4read-catalogue fixtures honestly as
    // «4read» instead of «unknown» (spec-15 T6).
    private const val FIXTURE_HOST: String = "https://fixtures.4read.org.invalid"

    private data class BookSpec(
        val id: String,
        val title: String,
        val author: String,
        val genre: String,
        val isDownloaded: Boolean
    )

    private val bookSpecs: List<BookSpec> = listOf(
        BookSpec(
            id = "fixture-book-neuromancer",
            title = "Нейромант",
            author = "Вільям Гібсон",
            genre = "Cyberpunk",
            isDownloaded = true
        ),
        BookSpec(
            id = "fixture-book-1984",
            title = "1984",
            author = "Джордж Орвелл",
            genre = "Антиутопія",
            isDownloaded = false
        ),
        BookSpec(
            id = "fixture-book-fahrenheit",
            title = "451° за Фаренгейтом",
            author = "Рей Бредбері",
            genre = "Фантастика",
            isDownloaded = false
        )
    )

    /**
     * Three audiobooks with stable ids, ordered as the specs are declared.
     *
     * `totalDurationSeconds` and `totalChapters` are kept consistent with what
     * [dataChapters] produces for the same book, so a test can trust either
     * side of the relationship.
     */
    fun dataBooks(): List<AudiobookEntity> = bookSpecs.mapIndexed { bookIndex, spec ->
        // ADR-0009: downloadProgress / isFavorite / createdAt are @Ignore
        // projections (the columns live on the Library Entry row) — the
        // factory sets them in place so in-memory reads keep working.
        AudiobookEntity(
            id = spec.id,
            title = spec.title,
            author = spec.author,
            narrator = "Фікстура-читець ${bookIndex + 1}",
            description = "Детермінована фікстура для JVM-тестів: ${spec.title}.",
            coverDrawableRes = 0,
            coverImageUrl = null,
            genre = spec.genre,
            sourceUrl = "$FIXTURE_HOST/${spec.id}.html",
            isDownloaded = spec.isDownloaded,
            totalDurationSeconds = totalDurationSecondsOf(bookIndex),
            totalChapters = CHAPTERS_PER_BOOK,
            rating = 4.5f
        ).also {
            it.downloadProgress = if (spec.isDownloaded) 1.0f else 0f
            it.isFavorite = bookIndex == 0
            // The entity defaults createdAt to the wall clock — frozen here so
            // the determinism self-test (and every equality/snapshot assertion
            // downstream) stays stable, per this factory's no-wall-clock rule.
            it.createdAt = FIXED_CLOCK_MS
        }
    }

    /**
     * Nine chapters -- [CHAPTERS_PER_BOOK] for each entry of [audiobooks],
     * ordered by book then by `chapterIndex`.
     *
     * @param audiobooks books to generate chapters for; defaults to [dataBooks].
     */
    fun dataChapters(audiobooks: List<AudiobookEntity> = dataBooks()): List<ChapterEntity> =
        audiobooks.flatMap { book -> chaptersFor(book) }

    /**
     * The [CHAPTERS_PER_BOOK] chapters belonging to [book].
     *
     * Durations vary per book and per chapter so that off-by-one bugs in
     * chapter selection surface as a duration mismatch rather than passing
     * silently against uniform values.
     */
    fun chaptersFor(book: AudiobookEntity): List<ChapterEntity> {
        val bookIndex = bookSpecs.indexOfFirst { it.id == book.id }
        require(bookIndex >= 0) { "Unknown fixture book id: ${book.id}" }
        return (0 until CHAPTERS_PER_BOOK).map { chapterIndex ->
            ChapterEntity(
                id = "${book.id}-ch-${chapterIndex + 1}",
                bookId = book.id,
                chapterIndex = chapterIndex,
                title = "Глава ${chapterIndex + 1}",
                durationSeconds = chapterDurationSeconds(bookIndex, chapterIndex)
            )
        }
    }

    /**
     * One [SourceTrackEntity] per chapter of [book]'s [sourceId], unroutable
     * URLs — the physical playback fixture (ADR-0007).
     */
    fun tracksFor(book: AudiobookEntity, sourceId: String): List<com.example.data.db.SourceTrackEntity> =
        (0 until CHAPTERS_PER_BOOK).map { chapterIndex ->
            com.example.data.db.SourceTrackEntity(
                id = "$sourceId-tr-${chapterIndex + 1}",
                sourceId = sourceId,
                trackIndex = chapterIndex,
                url = "$FIXTURE_HOST/${book.id}/chapter-${chapterIndex + 1}.mp3"
            )
        }

    /**
     * One [PlaybackProgressEntity] per book, all frozen at [FIXED_CLOCK_MS].
     *
     * @param audiobooks books to record progress for.
     * @param chapterIndex chapter each book is parked on.
     * @param positionSeconds offset within that chapter.
     */
    fun seedPlaybackProgress(
        audiobooks: List<AudiobookEntity> = dataBooks(),
        chapterIndex: Int = 0,
        positionSeconds: Long = 0L,
        isCompleted: Boolean = false
    ): List<PlaybackProgressEntity> = audiobooks.map { book ->
        PlaybackProgressEntity(
            // ADR-0007: progress is keyed by the Edition (deterministic id
            // from the book's identity — the same id the import writes).
            editionId = com.example.data.EditionId.forBook(book.mergeKey, book.id),
            bookId = book.id,
            currentChapterIndex = chapterIndex,
            currentPositionSeconds = positionSeconds,
            lastListenedAt = FIXED_CLOCK_MS,
            isCompleted = isCompleted
        )
    }

    /**
     * One [SourceEntity] per book per source type, frozen at [FIXED_CLOCK_MS].
     *
     * Mirrors the source-row id shape ("`<type>-<bookId>`",
     * `streamOnly` from the T1-verified [streamOnlyFor] policy) so repository
     * tests can seed the `sources` table deterministically — `addedAt`
     * otherwise defaults to the wall clock and would make any order/equality
     * assertion over sources flaky (same class as the createdAt flake).
     */
    fun seedSources(
        audiobooks: List<AudiobookEntity> = dataBooks(),
        sourceIds: List<String> = listOf("4read", "soundbooks")
    ): List<SourceEntity> = audiobooks.flatMap { book ->
        sourceIds.map { sourceId ->
            SourceEntity(
                id = "$sourceId-${book.id}",
                bookId = book.id,
                type = sourceId,
                url = "$FIXTURE_HOST/$sourceId/${book.id}",
                streamOnly = streamOnlyFor(sourceId),
                addedAt = FIXED_CLOCK_MS
            )
        }
    }

    /**
     * One bookmark per book with stable, non-zero ids so Room's autogenerate
     * behaviour does not leak into assertions.
     */
    fun seedBookmarks(
        audiobooks: List<AudiobookEntity> = dataBooks(),
        chapterIndex: Int = 0,
        timestampSeconds: Long = 120L
    ): List<BookmarkEntity> = audiobooks.mapIndexed { bookIndex, book ->
        BookmarkEntity(
            id = (bookIndex + 1).toLong(),
            bookId = book.id,
            chapterIndex = chapterIndex,
            chapterTitle = "Глава ${chapterIndex + 1}",
            timestampSeconds = timestampSeconds,
            note = "Фікстурна закладка ${bookIndex + 1}",
            createdAt = FIXED_CLOCK_MS
        )
    }

    /** A single day of listening statistics, frozen at [FIXED_DATE_ISO]. */
    fun seedListeningStats(listenedSeconds: Long = 1_800L): List<ListeningStatEntity> =
        listOf(ListeningStatEntity(dateIso = FIXED_DATE_ISO, listenedSeconds = listenedSeconds))

    private fun chapterDurationSeconds(bookIndex: Int, chapterIndex: Int): Long =
        BASE_CHAPTER_SECONDS +
            (bookIndex * PER_BOOK_SECONDS_STEP) +
            (chapterIndex * PER_CHAPTER_SECONDS_STEP)

    private fun totalDurationSecondsOf(bookIndex: Int): Long =
        (0 until CHAPTERS_PER_BOOK).sumOf { chapterDurationSeconds(bookIndex, it) }
}
