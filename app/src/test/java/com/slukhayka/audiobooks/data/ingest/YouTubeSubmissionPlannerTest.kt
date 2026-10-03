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

    // --- Spec-53 T11: playlist position selection ---------------------------

    @Test
    fun `a selection keeps only the picked positions`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234",
            metadata,
            "@youtube",
            selectedWatchUrls = setOf("https://www.youtube.com/watch?v=biwxkjI06KA")
        )

        assertEquals(1, plan.chapters.size)
        assertEquals("https://www.youtube.com/watch?v=biwxkjI06KA", plan.chapters.single().watchUrl)
    }

    @Test
    fun `a filtered chapter keeps its original playlist number`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234",
            metadata,
            "@youtube",
            selectedWatchUrls = setOf("https://www.youtube.com/watch?v=DEADBEEF123")
        )

        // The third entry stays "Розділ 3", not "Розділ 1" after filtering.
        assertEquals("Гаррі Поттер 1. Розділ 3", plan.chapters.single().title)
    }

    @Test
    fun `an empty selection plans nothing instead of the whole playlist`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234",
            metadata,
            "@youtube",
            selectedWatchUrls = emptySet()
        )

        assertTrue(plan.chapters.isEmpty())
    }

    @Test
    fun `a null selection keeps the whole playlist`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234",
            metadata,
            "@youtube"
        )

        assertEquals(3, plan.chapters.size)
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

    @Test
    fun `thumbnail survives parsing, absence stays absent`() {
        val withCover = YouTubeSubmissionPlanner.parseMetadata(
            """{"id":"x","title":"Книга","thumbnail":"https://i.ytimg.com/vi/x/hqdefault.jpg"}"""
        )!!

        assertEquals("https://i.ytimg.com/vi/x/hqdefault.jpg", withCover.coverUrl)
        assertNull(YouTubeSubmissionPlanner.parseMetadata(singleVideoJson)!!.coverUrl)
    }

    // --- #1051 — the listener's own order ----------------------------------

    /**
     * #1051 — a playlist that arrives newest-first can be put right by hand.
     *
     * The report: «остання частина книги це 1 розділ в додатку». The source
     * order is OBSERVED (ADR-0014) and we reproduce it faithfully, so the cure
     * is the listener's explicit order — never a guess from titles, which is
     * what ADR-0035 forbids.
     */
    @Test
    fun `an explicit order decides which entry is read first`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!
        val reversed = listOf(
            "https://www.youtube.com/watch?v=DEADBEEF123",
            "https://www.youtube.com/watch?v=biwxkjI06KA",
            "https://www.youtube.com/watch?v=6XIPkMFZf-0"
        )

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234",
            metadata,
            "@youtube",
            explicitOrder = reversed
        )

        assertEquals(
            listOf("DEADBEEF123", "biwxkjI06KA", "6XIPkMFZf-0"),
            plan.chapters.map { it.watchUrl.substringAfter("v=") }
        )
    }

    /**
     * The numbering rule the ticket left open, and the answer: a REORDER takes
     * the new position, because a label that contradicted the hand would be
     * the app arguing with the listener. Filtering keeps the observed one —
     * the test below pins that half, so the two rules are provably distinct.
     */
    @Test
    fun `a reordered untitled entry is numbered by its NEW position`() {
        val untitled = """
            {
              "_type": "playlist",
              "id": "PLx",
              "title": "Книга",
              "entries": [
                {"_type": "url", "id": "AAA", "url": "https://www.youtube.com/watch?v=AAA", "title": ""},
                {"_type": "url", "id": "BBB", "url": "https://www.youtube.com/watch?v=BBB", "title": ""}
              ]
            }
        """.trimIndent()
        val metadata = YouTubeSubmissionPlanner.parseMetadata(untitled)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLx",
            metadata,
            "@youtube",
            explicitOrder = listOf(
                "https://www.youtube.com/watch?v=BBB",
                "https://www.youtube.com/watch?v=AAA"
            )
        )

        // BBB now plays first, so it must READ as the first.
        assertEquals("Розділ 1", plan.chapters[0].title)
        assertEquals("https://www.youtube.com/watch?v=BBB", plan.chapters[0].watchUrl)
        assertEquals("Розділ 2", plan.chapters[1].title)
    }

    /**
     * The contrast that gives the rule above its meaning, and the half that
     * must NOT change: filtering alone keeps the OBSERVED position, so a
     * picked «Розділ 3» still reads as the third and the listener recognises
     * the part they chose.
     */
    @Test
    fun `filtering alone still numbers by the OBSERVED position`() {
        val untitled = """
            {
              "_type": "playlist",
              "id": "PLx",
              "title": "Книга",
              "entries": [
                {"_type": "url", "id": "AAA", "url": "https://www.youtube.com/watch?v=AAA", "title": ""},
                {"_type": "url", "id": "BBB", "url": "https://www.youtube.com/watch?v=BBB", "title": ""},
                {"_type": "url", "id": "CCC", "url": "https://www.youtube.com/watch?v=CCC", "title": ""}
              ]
            }
        """.trimIndent()
        val metadata = YouTubeSubmissionPlanner.parseMetadata(untitled)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLx",
            metadata,
            "@youtube",
            // Pick the LAST position only — the number must not collapse to 1.
            selectedWatchUrls = setOf("https://www.youtube.com/watch?v=CCC")
        )

        assertEquals(1, plan.chapters.size)
        assertEquals("Розділ 3", plan.chapters[0].title)
    }

    /**
     * A reorder is NOT a way to smuggle in an entry the listener never picked:
     * membership still comes from the selection, so the two inputs stay
     * independent.
     */
    @Test
    fun `an order never adds an entry the selection excluded`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234",
            metadata,
            "@youtube",
            selectedWatchUrls = setOf("https://www.youtube.com/watch?v=6XIPkMFZf-0"),
            explicitOrder = listOf(
                "https://www.youtube.com/watch?v=DEADBEEF123",
                "https://www.youtube.com/watch?v=6XIPkMFZf-0"
            )
        )

        assertEquals(1, plan.chapters.size)
        assertEquals("https://www.youtube.com/watch?v=6XIPkMFZf-0", plan.chapters[0].watchUrl)
    }

    /**
     * A partial order narrows nothing: an entry the list does not mention keeps
     * its observed place AFTER the ordered ones, so a dropped look can never
     * silently drop a chapter.
     */
    @Test
    fun `an entry missing from the order keeps its place after the ordered ones`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234",
            metadata,
            "@youtube",
            explicitOrder = listOf("https://www.youtube.com/watch?v=biwxkjI06KA")
        )

        assertEquals(3, plan.chapters.size)
        assertEquals("https://www.youtube.com/watch?v=biwxkjI06KA", plan.chapters[0].watchUrl)
        assertEquals(
            listOf("6XIPkMFZf-0", "DEADBEEF123"),
            plan.chapters.drop(1).map { it.watchUrl.substringAfter("v=") }
        )
    }

    /** No order given = the observed order, exactly as before. */
    @Test
    fun `no explicit order keeps the observed order`() {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(playlistJson)!!

        val plan = YouTubeSubmissionPlanner.plan(
            "https://www.youtube.com/playlist?list=PLabcd1234", metadata, "@youtube"
        )

        assertEquals(
            listOf("6XIPkMFZf-0", "biwxkjI06KA", "DEADBEEF123"),
            plan.chapters.map { it.watchUrl.substringAfter("v=") }
        )
    }
}
