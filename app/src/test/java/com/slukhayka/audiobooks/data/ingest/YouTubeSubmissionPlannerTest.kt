package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM fixture tests for the ADR-0035 / #604 submission planner.
 * The single-video fixture is a REAL `yt-dlp -J` capture (2026-09-07,
 * ozaZXk5Qcwc); the playlist fixture follows yt-dlp's flat-playlist entry
 * schema with the REAL video ids from the discussion (6XIPkMFZf-0,
 * biwxkjI06KA). No Android, no network, no database.
 */
class YouTubeSubmissionPlannerTest {

    private val singleVideoJson = """
        {
          "id": "ozaZXk5Qcwc",
          "title": "Звички невдах | Стівен Адамс | Аудіокнига українською повністю",
          "duration": 5000,
          "formats": []
        }
    """.trimIndent()

    private val playlistJson = """
        {
          "_type": "playlist",
          "id": "PLabcd1234",
          "title": "Гаррі Поттер 1 — АудіоКниги Українською",
          "entries": [
            {"_type": "url", "ie_key": "Youtube", "id": "6XIPkMFZf-0", "url": "https://www.youtube.com/watch?v=6XIPkMFZf-0", "title": "Гаррі Поттер 1. Розділ 1"},
            {"_type": "url", "ie_key": "Youtube", "id": "biwxkjI06KA", "url": "https://www.youtube.com/watch?v=biwxkjI06KA", "title": "Гаррі Поттер 1. Розділ 2"},
            {"_type": "url", "ie_key": "Youtube", "id": "DEADBEEF123", "url": "https://www.youtube.com/watch?v=DEADBEEF123", "title": "Гаррі Поттер 1. Розділ 3"}
          ]
        }
    """.trimIndent()

    // --- metadata parsing ---------------------------------------------------

    @Test
    fun `single video metadata has no entries`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(singleVideoJson)!!

        assertEquals("ozaZXk5Qcwc", metadata.id)
        assertEquals("Звички невдах | Стівен Адамс | Аудіокнига українською повністю", metadata.title)
        assertTrue(metadata.entries.isEmpty())
    }

    @Test
    fun `playlist metadata keeps ordered entries`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        assertEquals("Гаррі Поттер 1 — АудіоКниги Українською", metadata.title)
        assertEquals(3, metadata.entries.size)
        assertEquals("Гаррі Поттер 1. Розділ 1", metadata.entries[0].title)
        assertEquals("https://www.youtube.com/watch?v=biwxkjI06KA", metadata.entries[1].url)
    }

    @Test
    fun `broken metadata parses to null`() {
        assertNull(YouTubeSubmissionPlanner.parseMetadata("not json"))
        assertNull(YouTubeSubmissionPlanner.parseMetadata("{}"))
        assertNull(YouTubeSubmissionPlanner.parseMetadata("""{"formats":[]}"""))
    }

    // --- chapter topology (observed boundaries only, ADR-0014) --------------

    @Test
    fun `playlist plans one chapter per entry with watch urls`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!
        val plan = YouTubeSubmissionPlanner.plan("https://www.youtube.com/playlist?list=PLabcd1234", metadata, "@youtube")

        assertEquals(3, plan.chapters.size)
        assertEquals("Гаррі Поттер 1. Розділ 1", plan.chapters[0].title)
        assertEquals("https://www.youtube.com/watch?v=6XIPkMFZf-0", plan.chapters[0].watchUrl)
        assertEquals("https://www.youtube.com/watch?v=biwxkjI06KA", plan.chapters[1].watchUrl)
    }

    @Test
    fun `single video plans one whole-file chapter`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(singleVideoJson)!!
        val plan = YouTubeSubmissionPlanner.plan("https://www.youtube.com/watch?v=ozaZXk5Qcwc", metadata, "@youtube")

        // No observed boundaries → the file is ONE chapter (ADR-0014): nothing fabricated.
        assertEquals(1, plan.chapters.size)
        assertEquals("https://www.youtube.com/watch?v=ozaZXk5Qcwc", plan.chapters[0].watchUrl)
    }

    @Test
    fun `short youtu-be url normalises to the canonical watch url`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(singleVideoJson)!!
        val plan = YouTubeSubmissionPlanner.plan("https://youtu.be/ozaZXk5Qcwc", metadata, "@youtube")

        assertEquals("https://www.youtube.com/watch?v=ozaZXk5Qcwc", plan.chapters[0].watchUrl)
    }

    @Test
    fun `an entry without url or id is skipped - never fabricated`() {
        val json = """
            {
              "_type": "playlist",
              "id": "p1",
              "title": "Книга",
              "entries": [
                {"_type": "url", "ie_key": "Youtube", "id": "abc123", "url": "https://www.youtube.com/watch?v=abc123", "title": "Розділ 1"},
                {"_type": "url", "ie_key": "Youtube", "title": "Розділ 2 без адреси"}
              ]
            }
        """.trimIndent()
        val plan = YouTubeSubmissionPlanner.plan("https://www.youtube.com/playlist?list=p1", YouTubeSubmissionPlanner.parseMetadata(json)!!, "@youtube")

        assertEquals(1, plan.chapters.size)
        assertEquals("Розділ 1", plan.chapters[0].title)
    }

    // --- Work identity via TitleNormalizer (ADR-0035 п. 4) ------------------

    @Test
    fun `channel mark is cut from the work title and never invents an author`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!
        val plan = YouTubeSubmissionPlanner.plan("https://www.youtube.com/playlist?list=PLabcd1234", metadata, "@youtube")

        assertEquals("Гаррі Поттер 1", plan.title)
        assertNull(plan.author)
    }
}