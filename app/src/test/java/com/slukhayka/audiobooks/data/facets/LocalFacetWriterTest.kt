package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.metadata.FacetDurationBucket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalFacetWriterTest {
    private lateinit var db: AudiobookDatabase
    private lateinit var writer: LocalFacetWriter

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = RoomLocalFacetWriter(db.audiobookDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `bounded Work and Edition delta keeps raw provenance and replays idempotently`() = runBlocking {
        val delta = LocalFacetDelta(
            work = WorkFacetDelta(
                workId = "work-1",
                genres = listOf(
                    GenreFacetAssertion("ФАНТАСТИКА · sci-fi / Фентезі", "sluhay", 1234),
                    GenreFacetAssertion("фентезі", "4read", 1235)
                ),
                updatedAt = 1235
            ),
            editions = listOf(
                EditionFacetDelta(
                    editionId = "edition-1",
                    workId = "work-1",
                    language = "uk",
                    durationSeconds = 3600,
                    chapterCount = 12,
                    availabilityAvailable = true,
                    availabilityObservedAtMillis = 1_000,
                    availabilityTtlSeconds = 3_600,
                    updatedAt = 1235
                )
            )
        )

        writer.apply(listOf(delta))
        writer.apply(listOf(delta))

        assertEquals(
            listOf("fantasy", "science-fiction"),
            db.audiobookDao().observeGenreFacetOptions().first().map { it.id }.sorted()
        )
        assertTrue(db.audiobookDao().observeGenreFacetOptions().first().all { it.workCount == 1 })
        val assertions = db.audiobookDao().genreAssertionsForWork("work-1")
        assertEquals(3, assertions.size)
        assertEquals(setOf("sluhay", "4read"), assertions.map { it.sourceId }.toSet())
        assertEquals(
            setOf("ФАНТАСТИКА · sci-fi / Фентезі", "фентезі"),
            assertions.map { it.rawText }.toSet()
        )
        db.openHelper.writableDatabase.query(
            "SELECT availabilityAvailable, availabilityObservedAtMillis, availabilityTtlSeconds, durationSeconds, durationBucketId " +
                "FROM edition_facets WHERE editionId='edition-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1_000L, cursor.getLong(1))
            assertEquals(3_600L, cursor.getLong(2))
            assertEquals(3_600L, cursor.getLong(3))
            assertEquals(FacetDurationBucket.UNDER_FIVE_HOURS.wireName, cursor.getString(4))
        }
    }

    @Test
    fun `canonical remote bucket without seconds survives the frozen writer seam`() = runBlocking {
        writer.apply(
            listOf(
                LocalFacetDelta(
                    work = WorkFacetDelta("work-remote-duration"),
                    editions = listOf(
                        EditionFacetDelta(
                            editionId = "edition-remote-duration",
                            workId = "work-remote-duration",
                            durationBucketId = FacetDurationBucket.TEN_TO_TWENTY_HOURS.wireName,
                            updatedAt = 10
                        )
                    )
                )
            )
        )

        db.openHelper.writableDatabase.query(
            "SELECT durationSeconds, durationBucketId FROM edition_facets WHERE editionId='edition-remote-duration'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(FacetDurationBucket.TEN_TO_TWENTY_HOURS.wireName, cursor.getString(1))
        }
    }

    @Test
    fun `oversized batch is rejected before any row is committed`() = runBlocking {
        val oversized = (0..LocalFacetWriter.MAX_DELTAS_PER_BATCH).map { index ->
            LocalFacetDelta(WorkFacetDelta(workId = "work-$index"))
        }

        try {
            writer.apply(oversized)
            fail("oversized facet batch must be rejected")
        } catch (_: IllegalArgumentException) {
            // Public bound rejected the batch before the Room transaction.
        }
        assertTrue(db.audiobookDao().observeGenreFacetOptions().first().isEmpty())
    }

    @Test
    fun `partial Edition availability is rejected before the transaction commits`() = runBlocking {
        val invalid = LocalFacetDelta(
            work = WorkFacetDelta(workId = "work-partial"),
            editions = listOf(
                EditionFacetDelta(
                    editionId = "edition-partial",
                    workId = "work-partial",
                    availabilityObservedAtMillis = 1_000
                )
            )
        )

        try {
            writer.apply(listOf(invalid))
            fail("partial availability must not reach Room")
        } catch (_: IllegalArgumentException) {
            // The whole delta is rejected before the DAO transaction starts.
        }
        val outOfBounds = invalid.copy(
            editions = listOf(
                invalid.editions.single().copy(
                    availabilityAvailable = true,
                    availabilityTtlSeconds = EditionAvailabilityPolicy.MAX_TTL_SECONDS + 1
                )
            )
        )
        try {
            writer.apply(listOf(outOfBounds))
            fail("out-of-bounds availability TTL must not reach Room")
        } catch (_: IllegalArgumentException) {
            // Bounds use the same pre-transaction validation path.
        }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM work_facets WHERE workId='work-partial'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `Edition availability only advances to a strictly newer observation`() = runBlocking {
        suspend fun write(available: Boolean, observedAt: Long, ttl: Long) {
            writer.apply(
                listOf(
                    LocalFacetDelta(
                        work = WorkFacetDelta("work-availability"),
                        editions = listOf(
                            EditionFacetDelta(
                                editionId = "edition-availability",
                                workId = "work-availability",
                                availabilityAvailable = available,
                                availabilityObservedAtMillis = observedAt,
                                availabilityTtlSeconds = ttl,
                                updatedAt = observedAt
                            )
                        )
                    )
                )
            )
        }

        suspend fun stored(): Triple<Boolean, Long, Long> = db.openHelper.writableDatabase.query(
            "SELECT availabilityAvailable, availabilityObservedAtMillis, availabilityTtlSeconds FROM edition_facets WHERE editionId='edition-availability'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            Triple(cursor.getInt(0) != 0, cursor.getLong(1), cursor.getLong(2))
        }

        write(available = true, observedAt = 100, ttl = 10)
        write(available = false, observedAt = 99, ttl = 20)
        assertEquals(Triple(true, 100L, 10L), stored())

        write(available = false, observedAt = 100, ttl = 99)
        assertEquals(Triple(true, 100L, 10L), stored())

        write(available = false, observedAt = 101, ttl = 30)
        assertEquals(Triple(false, 101L, 30L), stored())
    }

    @Test
    fun `genre options expose the complete bounded dictionary`() = runBlocking {
        val deltas = (1..201).map { index ->
            LocalFacetDelta(
                WorkFacetDelta(
                    workId = "work-option-$index",
                    genres = listOf(GenreFacetAssertion("Жанр $index", "test", index.toLong()))
                )
            )
        }
        deltas.chunked(LocalFacetWriter.MAX_DELTAS_PER_BATCH).forEach { writer.apply(it) }

        assertEquals(201, db.audiobookDao().observeGenreFacetOptions().first().size)
    }

    @Test
    fun `strictly newer Source document replaces its whole canonical genre set`() = runBlocking {
        suspend fun replace(
            sourceId: String,
            documentUpdatedAt: Long,
            assertions: List<Triple<String, String, String>>
        ) {
            writer.apply(
                listOf(
                    LocalFacetDelta(
                        WorkFacetDelta(
                            workId = "work-remote",
                            genreSourceReplacements = listOf(
                                GenreSourceFacetReplacement(
                                    sourceId = sourceId,
                                    documentUpdatedAt = documentUpdatedAt,
                                    assertions = assertions.map { (genreId, rawText, assertionId) ->
                                        CanonicalGenreFacetAssertion(
                                            genreId = genreId,
                                            rawText = rawText,
                                            sourceId = sourceId,
                                            observedAt = documentUpdatedAt,
                                            assertionId = assertionId,
                                            documentUpdatedAt = documentUpdatedAt
                                        )
                                    }
                                )
                            )
                        )
                    )
                )
            )
        }

        replace(
            "remote-a",
            10,
            listOf(
                Triple("shared-fantasy", "Фентезі", "a-document"),
                Triple("shared-detective", "Детективи", "a-document")
            )
        )
        replace("remote-a", 9, listOf(Triple("shared-detective", "Детективи", "a-old")))
        replace("remote-a", 10, emptyList())
        assertEquals(
            setOf("shared-detective", "shared-fantasy"),
            db.audiobookDao().observeGenreFacetOptions().first().map { it.id }.toSet()
        )
        assertEquals(
            listOf("a-document", "a-document"),
            db.audiobookDao().genreAssertionsForWork("work-remote").map { it.assertionId }
        )

        replace("remote-a", 11, listOf(Triple("shared-detective", "Детективи", "shared-document")))
        replace(
            "remote-b",
            5,
            listOf(
                Triple("shared-detective", "Детективи", "shared-document"),
                Triple("shared-fantasy", "Фентезі", "b-fantasy")
            )
        )
        assertEquals(
            setOf("shared-detective", "shared-fantasy"),
            db.audiobookDao().observeGenreFacetOptions().first().map { it.id }.toSet()
        )
        assertTrue(db.audiobookDao().observeGenreFacetOptions().first().all { it.workCount == 1 })

        replace("remote-a", 12, emptyList())
        assertEquals(
            setOf("shared-detective", "shared-fantasy"),
            db.audiobookDao().observeGenreFacetOptions().first().map { it.id }.toSet()
        )
        val remainingAssertions = db.audiobookDao().genreAssertionsForWork("work-remote")
        assertEquals(setOf("b-fantasy", "shared-document"), remainingAssertions.map { it.assertionId }.toSet())
        assertTrue(remainingAssertions.all { it.sourceId == "remote-b" })
    }
}
