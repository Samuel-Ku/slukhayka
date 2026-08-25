package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
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
            "SELECT availabilityAvailable, availabilityObservedAtMillis, availabilityTtlSeconds FROM edition_facets WHERE editionId='edition-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1_000L, cursor.getLong(1))
            assertEquals(3_600L, cursor.getLong(2))
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
}
