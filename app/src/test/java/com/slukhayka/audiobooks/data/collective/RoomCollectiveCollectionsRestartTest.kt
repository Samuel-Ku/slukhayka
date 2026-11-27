package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controlled COLLECTIONS persistence fixture; no live source acquisition or App composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveCollectionsRestartTest {
    @Test
    fun `a complete last good collections block survives closing and reopening its file backed Room database`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "collective-collections-restart-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(databaseName)
        assertFalse("the fixture owns a new database path", file.exists())
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()

        val original = CollectiveFeedBlock(
            blockKey = "audiobookmp3|COLLECTIONS",
            sourceId = "audiobookmp3",
            kind = CollectiveBlockKind.COLLECTIONS,
            name = "Добірка для перевірки збереження",
            provenanceUrl = "https://audiobook-mp3.com/uk-genre-12-fantastyka",
            cards = listOf(
                CollectiveBlockCard(
                    sourceId = "audiobookmp3",
                    sourceUrl = "https://audiobook-mp3.com/uk-audio-100-klub-boyaguziv",
                    title = "Клуб боягузів",
                    author = "Леся Воронина",
                    coverUrl = "https://audiobook-mp3.com/images/persistence-fixture.webp"
                ),
                CollectiveBlockCard(
                    sourceId = "audiobookmp3",
                    sourceUrl = "https://audiobook-mp3.com/uk-audio-101-misto",
                    title = "Місто",
                    author = "Валер’ян Підмогильний",
                    coverUrl = null
                )
            ),
            fetchedAt = 1_700_000_000_000L,
            staleAfter = 1_700_086_400_000L,
            version = 7L,
            lastAttempt = CollectiveAttempt(1_700_000_000_000L, CollectiveAttemptStatus.SUCCESS)
        )
        // Independent full expected value: the later failure changes only the attempt.
        val retained = CollectiveFeedBlock(
            blockKey = "audiobookmp3|COLLECTIONS",
            sourceId = "audiobookmp3",
            kind = CollectiveBlockKind.COLLECTIONS,
            name = "Добірка для перевірки збереження",
            provenanceUrl = "https://audiobook-mp3.com/uk-genre-12-fantastyka",
            cards = listOf(
                CollectiveBlockCard(
                    sourceId = "audiobookmp3",
                    sourceUrl = "https://audiobook-mp3.com/uk-audio-100-klub-boyaguziv",
                    title = "Клуб боягузів",
                    author = "Леся Воронина",
                    coverUrl = "https://audiobook-mp3.com/images/persistence-fixture.webp"
                ),
                CollectiveBlockCard(
                    sourceId = "audiobookmp3",
                    sourceUrl = "https://audiobook-mp3.com/uk-audio-101-misto",
                    title = "Місто",
                    author = "Валер’ян Підмогильний",
                    coverUrl = null
                )
            ),
            fetchedAt = 1_700_000_000_000L,
            staleAfter = 1_700_086_400_000L,
            version = 7L,
            lastAttempt = CollectiveAttempt(1_700_100_000_000L, CollectiveAttemptStatus.TIMEOUT)
        )

        try {
            val first = open()
            try {
                assertEquals(file.canonicalPath, File(requireNotNull(first.openHelper.writableDatabase.path)).canonicalPath)
                val firstStore = RoomCollectiveFeedBlockStore(first.audiobookDao())
                assertNull(firstStore.active("audiobookmp3|COLLECTIONS"))
                assertTrue(firstStore.activate(original))
                assertEquals(original, firstStore.active("audiobookmp3|COLLECTIONS"))
                firstStore.recordAttempt("audiobookmp3|COLLECTIONS",
                    CollectiveAttempt(1_700_100_000_000L, CollectiveAttemptStatus.TIMEOUT))
                assertEquals(retained, firstStore.active("audiobookmp3|COLLECTIONS"))
            } finally {
                first.close()
            }
            assertFalse("the writer database is closed before reopening", first.isOpen)
            assertTrue("Room wrote a real database file", file.isFile && file.length() > 0L)

            val reopened = open()
            try {
                assertNotSame("the reopened database is a new instance", first, reopened)
                assertEquals(file.canonicalPath, File(requireNotNull(reopened.openHelper.writableDatabase.path)).canonicalPath)
                val dao = reopened.audiobookDao()
                val reopenedStore = RoomCollectiveFeedBlockStore(dao)
                assertEquals(retained, reopenedStore.active("audiobookmp3|COLLECTIONS"))
                val reopenedOverview = CollectiveOverviewBlocks(reopenedStore)
                assertEquals(listOf(retained), reopenedOverview.read(listOf("audiobookmp3")))
                assertNull(reopenedStore.active("sluhayua|COLLECTIONS"))
                assertTrue(dao.getAllAudiobooksOnce().isEmpty())
                assertTrue(dao.getAllChaptersOnce().isEmpty())
                assertTrue(dao.getSourcesByTypes(listOf("audiobookmp3")).isEmpty())
                assertEquals(0, dao.countWorks())
                assertEquals(0, dao.countWorkSources())
                assertEquals(0, dao.countLibraryEntries())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
