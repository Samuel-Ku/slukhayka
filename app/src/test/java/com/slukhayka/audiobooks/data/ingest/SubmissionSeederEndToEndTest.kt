package com.slukhayka.audiobooks.data.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.facets.InMemorySubmissionSyncCursorStore
import com.slukhayka.audiobooks.data.facets.RoomSubmissionProjectionWriter
import com.slukhayka.audiobooks.data.facets.SubmissionDeltaSync
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * ADR-0035 п. 12 / #608 — AC1 end-to-end: the curator's seeder walks a
 * channel, the verified items land in the SHARED base, the submission delta
 * lane of ANOTHER install materializes them into the merged catalog — a
 * second installation sees the seeded books (Works + Sources + tracks),
 * while an unverified item never leaves the shared base.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubmissionSeederEndToEndTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private val store = FakeSharedBookMetaStore()
    private var now = 10_000L
    private val verification = SubmissionVerification { now }
    private val policy = SubmissionPolicy(store, verification) { now }
    private val publisher = SubmissionPublisher(store, policy) { now }

    private val channelUrl = "https://www.youtube.com/@stivenkingua/videos"
    private val channelId = "@stivenkingua"
    private val submitterId = "curator-device-1"

    private val v1 = "https://www.youtube.com/watch?v=6XIPkMFZf-0"
    private val v2 = "https://www.youtube.com/watch?v=biwxkjI06KA"
    private val v3 = "https://www.youtube.com/watch?v=DEADBEEF123"

    private val flatChannelJson = """
        {
          "_type": "playlist",
          "id": "UUstivenkingua",
          "title": "Стівен Кінг українською",
          "entries": [
            {"_type": "url", "ie_key": "Youtube", "id": "6XIPkMFZf-0", "url": "$v1", "title": "Стівен Кінг - Острів Дума"},
            {"_type": "url", "ie_key": "Youtube", "id": "biwxkjI06KA", "url": "$v2", "title": "Стівен Кінг - Дівчинка, яка любила Тома Ґордона"},
            {"_type": "url", "ie_key": "Youtube", "id": "DEADBEEF123", "url": "$v3", "title": "Стівен Кінг - Мертва зона"}
          ]
        }
    """.trimIndent()

    private fun itemJson(id: String, title: String) =
        """{"id": "$id", "title": "$title", "duration": 5400}"""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a seeded channel reaches the merged catalog of another install`() = runBlocking {
        val seeder = SubmissionSeeder(
            publisher = publisher,
            pacing = PacingPolicy(PacingParams(minPauseMillis = 1, maxPauseMillis = 1), Random(1)),
            fetchFlatMetadata = { flatChannelJson },
            fetchItemMetadata = { url ->
                when (url) {
                    v1 -> itemJson("6XIPkMFZf-0", "Стівен Кінг - Острів Дума")
                    v2 -> itemJson("biwxkjI06KA", "Стівен Кінг - Дівчинка, яка любила Тома Ґордона")
                    v3 -> itemJson("DEADBEEF123", "Стівен Кінг - Мертва зона")
                    else -> null
                }
            },
            pauseMillis = {}
        )
        // v1 and v2 have REAL playback verdicts; v3 has none (AC2).
        verification.record(seeder.itemSourceId(v1), actualPlaybackStarted = true)
        verification.record(seeder.itemSourceId(v2), actualPlaybackStarted = true)

        val result = seeder.seedChannel(channelUrl, channelId, submitterId)

        assertEquals(3, result.walked)
        assertEquals(2, result.published)
        assertEquals(1, result.notVerified)
        assertEquals(2, store.submissionPuts.size)

        // The OTHER install's consumption lane materializes the delta.
        val deltaSync = SubmissionDeltaSync(
            sharedStore = store,
            projectionWriter = RoomSubmissionProjectionWriter(dao),
            cursorStore = InMemorySubmissionSyncCursorStore()
        )
        val chain = deltaSync.syncAvailablePages()

        assertEquals(2, chain.publicationsApplied)
        // Dozens of books appear in the merged catalog — here, both seeded
        // works are findable by their mergeKey with playable Sources.
        val king = MergeKey.keyFor("Острів Дума", "Стівен Кінг")
        val girl = MergeKey.keyFor("Дівчинка, яка любила Тома Ґордона", "Стівен Кінг")
        val islandWork = dao.findWorkByMergeKey(king)
        val girlWork = dao.findWorkByMergeKey(girl)
        assertTrue("the seeded Work materialized", islandWork != null && girlWork != null)

        val islandSources = dao.getSourcesForBookSync(islandWork!!.id)
        assertEquals(1, islandSources.size)
        assertEquals("youtube", islandSources.single().type)
        assertEquals(1, dao.getTracksForSourceSync(islandSources.single().id).size)
        assertTrue(
            "tracks stream through the resolver seam",
            dao.getTracksForSourceSync(islandSources.single().id).single().url!!.startsWith("https://www.youtube.com/watch?v=")
        )

        // The unverified third item is NOT in the catalog and never left the
        // shared base (AC2 + AC4: no partial trace).
        assertNull(dao.findWorkByMergeKey(MergeKey.keyFor("Мертва зона", "Стівен Кінг")))
        assertTrue(
            "no library rows were created by the shared delta",
            dao.getAllAudiobooksOnce().isEmpty()
        )
    }
}