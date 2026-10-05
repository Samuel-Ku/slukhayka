package com.slukhayka.audiobooks.data.imports

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.achievements.*
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
import com.slukhayka.audiobooks.data.source.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AchievementImportProvenanceTest {
    private fun detail(title: String) = SourceBookDetail(title=title,author="Автор",narrator="Голос",
        url="https://sluhay.com/${title.hashCode()}",chapters=listOf(SourceChapter("Розділ","https://cdn.example.org/1.mp3")))
    private class Adapter(var page: SourceBookDetail) : SourceAdapter {
        override val sourceId="sluhay"
        var fail=false
        override suspend fun search(query: String)=emptyList<SourceBook>()
        override suspend fun fetchNew(limit: Int)=emptyList<SourceBook>()
        override suspend fun fetchBookPage(url: String): SourceBookDetail { if(fail) error("source unavailable"); return page }
        override suspend fun parseCapturedPage(html: String,url: String): SourceBookDetail? = if(fail) null else page
    }
    private fun verify(block: suspend (AudiobookDatabase,Context) -> Unit) = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AudiobookDatabase::class.java).allowMainThreadQueries().build()
        try { block(db,context) } finally { db.close() }
    }
    @Test fun `automatic and unknown entries never become personal merely because they are opened`() = verify { db,context ->
        val adapter=Adapter(detail("Авто-сід"))
        val imports=LibraryImport(db.audiobookDao(),context,listOf(adapter))
        val automatic=imports.importBookFromSource("sluhay",adapter.page,origin=LibraryEntryOrigin.AUTO_SEED)
        val unknown=imports.importBookFromSource("sluhay",detail("Невідоме"))
        val catalog=imports.importBookFromSource("sluhay",detail("Каталог"),origin=LibraryEntryOrigin.CATALOG_SYNC)
        var searchImports=0
        imports.importFromSourceUrl("sluhay",adapter.page.url,onNewBookImported={ searchImports++ })
        val dao=db.audiobookDao()
        assertEquals("AUTO_SEED",dao.libraryEntryById(automatic.id)!!.origin)
        assertEquals("UNKNOWN",dao.libraryEntryById(unknown.id)!!.origin)
        assertEquals("CATALOG_SYNC",dao.libraryEntryById(catalog.id)!!.origin)
        assertEquals(0,searchImports)
        val source=RoomAchievementProgressSource(db.achievementDao(),RoomAchievementStore(db.achievementDao()),setOf("sluhay"))
        assertEquals(0L,source.observe().first().explicitBooks)
    }
    @Test fun `successful new foreground live direct and captured imports prove their own search acceptance only once`() = verify { db,context ->
        val adapter=Adapter(detail("Пошук наживо"))
        val imports=LibraryImport(db.audiobookDao(),context,listOf(adapter))
        val accepted=mutableListOf<String>()
        val first=imports.importFromSourceUrl("sluhay",adapter.page.url,onNewBookImported={ accepted+=it.id })!!
        imports.importFromSourceUrl("sluhay",adapter.page.url,onNewBookImported={ accepted+=it.id })
        assertEquals(listOf(first.id),accepted)
        assertEquals("UNKNOWN",db.audiobookDao().libraryEntryById(first.id)!!.origin)
        val store=RoomAchievementStore(db.achievementDao())
        store.recordFact(AchievementFact.SEARCH_IMPORTED)
        val snapshot=RoomAchievementProgressSource(db.achievementDao(),store,setOf("sluhay")).observe().first()
        assertEquals(0L,snapshot.explicitBooks)
        // #701 — a search import now earns TWO awards on purpose: the first-step
        // one AND the «Глибокий пошук» mechanism award (the spec asks for both,
        // and they have different meanings). Assert both are present and that
        // nothing ELSE appeared, which is the contract this test protects.
        val earnedFromSearch = AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }.sorted()
        assertEquals(listOf("deep_search", "first_search_import"), earnedFromSearch)
        adapter.page=detail("Браузер прямо")
        val direct=imports.importBrowserSourceDirectPage("sluhay",adapter.page.url,onNewBookImported={ accepted+=it.id })!!
        assertEquals("UNKNOWN",db.audiobookDao().libraryEntryById(direct.id)!!.origin)
        adapter.page=detail("Явне додавання URL")
        val added=imports.importFromSourceUrl("sluhay",adapter.page.url,origin=LibraryEntryOrigin.EXPLICIT_SAVE)!!
        assertEquals("EXPLICIT_SAVE",db.audiobookDao().libraryEntryById(added.id)!!.origin)
        adapter.page=detail("Явне додавання браузера")
        val browserAdded=imports.importBrowserSourceDirectPage("sluhay",adapter.page.url,origin=LibraryEntryOrigin.EXPLICIT_SAVE)!!
        assertEquals("EXPLICIT_SAVE",db.audiobookDao().libraryEntryById(browserAdded.id)!!.origin)
        adapter.page=detail("Сторінка браузера")
        val captured=imports.importWebSourcePage("sluhay",adapter.page.url,"captured",onNewBookImported={ accepted+=it.id })!!
        assertEquals(listOf(first.id,direct.id,captured.id),accepted)
        adapter.fail=true
        assertNull(imports.importFromSourceUrl("sluhay","https://sluhay.com/failure",onNewBookImported={ accepted+=it.id }))
        assertEquals(3,accepted.size)
        // Real partial failure must not promote an entry before its chapter/track writes succeeded.
        adapter.fail=false; adapter.page=detail("Невдалий імпорт")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_chapter BEFORE INSERT ON chapters BEGIN SELECT RAISE(ABORT,'test write failure'); END")
        assertNull(imports.importFromSourceUrl("sluhay",adapter.page.url,origin=LibraryEntryOrigin.EXPLICIT_SAVE,onNewBookImported={ accepted+=it.id }))
        assertEquals(3,accepted.size)
        val incomplete=db.audiobookDao().getAllAudiobooks().first().first { it.title==adapter.page.title }
        assertEquals("UNKNOWN",db.audiobookDao().libraryEntryById(incomplete.id)!!.origin)
    }
    @Test fun `new explicit local and submitted books have recorded intent without invented playback`() = verify { db,context ->
        val imports=LibraryImport(db.audiobookDao(),context,emptyList())
        val local=imports.importLocalAudioStream("Особиста книга.mp3",byteArrayOf(1,2,3).inputStream())
        val preview=imports.importWatchingTelegram("https://t.me/books/1","Книга з допису","Автор",null,null,null)
        val youtube=imports.importSubmittedYouTube("https://www.youtube.com/watch?v=6XIPkMFZf-0",
            """{"id":"6XIPkMFZf-0","title":"Книга слухача","formats":[]}""","UC-listener")
        val dao=db.audiobookDao()
        assertEquals("EXPLICIT_IMPORT",dao.libraryEntryById(local.id)!!.origin)
        assertEquals("EXPLICIT_SAVE",dao.libraryEntryById(preview.bookId!!)!!.origin)
        assertEquals("EXPLICIT_SAVE",dao.libraryEntryById(youtube.bookId!!)!!.origin)
        val source=RoomAchievementProgressSource(db.achievementDao(),RoomAchievementStore(db.achievementDao()),setOf("youtube"))
        val snapshot=source.observe().first()
        assertEquals(3L,snapshot.explicitBooks)
        assertEquals(0L,snapshot.playbackStarts); assertEquals(0L,snapshot.offlinePlaybackStarts); assertEquals(0L,snapshot.downloadedBooks)
    }
}
