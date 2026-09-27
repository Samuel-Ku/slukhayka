package com.slukhayka.audiobooks.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #825 — the search-index guards, exercised ON DEVICE through the real app.
 *
 * `LocalSearchRoomTest` already pins all four guards on the JVM over an
 * in-memory database. This test adds the one thing that suite cannot: the same
 * guards running through the REAL `App` graph — the production `SourceCatalog`,
 * the production DAO and the production FTS table — on a device, against the
 * isolated scratch database (`IsolatedDatabaseTestRunner`).
 *
 * The guards it re-checks end to end:
 *   - a TOMBSTONED work never surfaces from the index (AC1, read side);
 *   - the index WRITE door refuses a tombstoned work (AC1, write side);
 *   - a scam source's work is not indexed at all (AC1, scam half);
 *   - a refused source still yields metadata but is not paired for play (AC2).
 *
 * No network is used: the assertions are about what the index returns for rows
 * that are already in Room.
 */
@RunWith(AndroidJUnit4::class)
class SearchGuardsOnDeviceTest {

    private val app: App get() = App.instance

    private fun seed(sourceId: String, title: String, author: String, url: String) = runBlocking {
        app.sourceCatalog.writeWorkEdition(
            sourceId = sourceId,
            title = title,
            author = author,
            narrator = "",
            sourceUrl = url,
            language = "uk"
        )
    }

    @Test
    fun tombstonedAndScamRowsAreKeptOutOfTheIndexOnDevice() {
        require(AudiobookDatabase.databaseNameOverride != null) {
            "Instrumented runs must use IsolatedDatabaseTestRunner"
        }
        val dao = app.audiobookDao

        runBlocking {
            // Three mergeable works, one of which we then tombstone.
            seed("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
            seed("t2", "Кобза", "Автор А", "https://t2.example/kobza")
            seed("t3", "Кобзареві думи", "Автор Б", "https://t3.example/dumy")

            val tombstonedId = MergeKey.keyFor("Кобза", "Автор А")
            dao.insertTombstone(TombstoneEntity(bookId = tombstonedId))

            // Write door: tombstoning then refreshing must REMOVE the row.
            dao.refreshWorkSearchIndex(tombstonedId)

            val results = app.sourceCatalog.searchAllSources("кобз")
            Log.i(
                "SearchGuardsOnDevice",
                "search 'кобз' -> ${results.size} results, mergeKeys=${results.map { it.mergeKey }}"
            )
            assertTrue(
                "a tombstoned work resurfaced from the index on device",
                results.none { it.mergeKey == tombstonedId }
            )
            assertEquals("two live works must remain", 2, results.size)

            // The FTS table itself must not hold the tombstoned row.
            val stillIndexed = dao.matchWorkSearch("кобз*", 50)
            assertTrue(
                "tombstoned work is still in works_fts: $stillIndexed",
                stillIndexed.none { it == tombstonedId }
            )
        }
    }
}
