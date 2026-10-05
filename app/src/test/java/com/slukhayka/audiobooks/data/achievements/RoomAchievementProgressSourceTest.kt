package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.testing.TestDataFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RoomAchievementProgressSourceTest {
    @Test fun `actual partial Room data never fabricates intent completion series or full downloads`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context,AudiobookDatabase::class.java).allowMainThreadQueries().build()
        val file = File.createTempFile("699-download-proof", ".mp3",context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        try {
            val store = RoomAchievementStore(database.achievementDao())
            val source = RoomAchievementProgressSource(database.achievementDao(),store,setOf("sluhay","youtube"))
            val empty = source.observe().first()
            assertEquals(0L,empty.explicitBooks); assertEquals(0L,empty.downloadedBooks)
            assertEquals(setOf("sluhay","youtube"),empty.registeredSourceIds)
            assertEquals(emptySet<AchievementSeriesMembership>(),empty.knownSeriesMemberships)
            val dao=database.audiobookDao()
            val book=TestDataFactory.dataBooks().first().copy(totalChapters=2)
            val chapters=TestDataFactory.dataChapters().filter { it.bookId==book.id }.take(2)
            dao.insertAudiobooks(listOf(book)); dao.insertChapters(chapters)
            dao.upsertLibraryEntry(book.id,book.id,false,123L,0f)
            dao.savePlaybackProgress(PlaybackProgressEntity("edition",book.id,0,0,123L,true))
            dao.upsertSeriesMember(SeriesMemberEntity(book.id,"partial-series",2))
            val sources=listOf("a","b").map { SourceEntity(it,book.id,type="sluhay",url="https://sluhay.com/$it") }
            dao.insertSources(sources)
            dao.insertTracks(listOf(SourceTrackEntity("a0","a",0,"url",file.absolutePath,isDownloaded=true),
                SourceTrackEntity("b1","b",1,"url",file.absolutePath,isDownloaded=true)))
            val partial=source.observe().first()
            assertEquals(0L,partial.explicitBooks); assertEquals(0L,partial.completedBooks); assertEquals(0L,partial.downloadedBooks)
            assertEquals(setOf(AchievementSeriesMembership("partial-series",book.id,2)),partial.knownSeriesMemberships)
            assertEquals(emptyList<AchievementDefinition>(),AchievementEvaluator.evaluate(partial,emptySet()))
            dao.updateLibraryEntryOrigin(book.id,"EXPLICIT_SAVE")
            dao.insertTracks(listOf(SourceTrackEntity("a1","a",1,"url",file.absolutePath,isDownloaded=true)))
            store.recordFact(AchievementFact.PLAYBACK_STARTED)
            val actual=source.observe().first()
            assertEquals(1L,actual.explicitBooks); assertEquals(1L,actual.downloadedBooks); assertEquals(1L,actual.playbackStarts)
            dao.updateBookStats(book.id,3,0L)
            assertEquals("missing known logical chapter is not a complete download",0L,source.observe().first().downloadedBooks)
            file.delete()
            assertEquals(0L,source.observe().first().downloadedBooks)
        } finally { database.close(); file.delete() }
    }
}
