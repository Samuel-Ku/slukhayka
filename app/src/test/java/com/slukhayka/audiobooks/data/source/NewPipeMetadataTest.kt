package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.ingest.YouTubeSubmissionPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-53 T2 — the in-app metadata engine entry: NewPipe models are mapped
 * to the planner's JSON shape (title, optional total duration, ordered
 * entries with REAL durations), so the listener path needs no external
 * binary. Pure builder tests + an injected provider seam — no network.
 */
class NewPipeMetadataTest {

    @Test
    fun `single video metadata parses back through the planner`() {
        val json = NewPipeMetadata.metadataJsonOf(
            NewPipeMetadata.Metadata(title = "Острів Дума", durationSeconds = 4284L)
        )
        val parsed = YouTubeSubmissionPlanner.parseMetadata(json)!!
        assertEquals("Острів Дума", parsed.title)
        assertEquals(4284L, parsed.durationSeconds)
        assertEquals(0, parsed.entries.size)
    }

    @Test
    fun `playlist entries keep order and real durations`() {
        val json = NewPipeMetadata.metadataJsonOf(
            NewPipeMetadata.Metadata(
                title = "Гаррі Поттер 1",
                entries = listOf(
                    NewPipeMetadata.Entry("a1", "https://www.youtube.com/watch?v=a1", "Розділ 1", 4285L),
                    NewPipeMetadata.Entry("b2", "https://www.youtube.com/watch?v=b2", "Розділ 2", 4075L)
                )
            )
        )
        val parsed = YouTubeSubmissionPlanner.parseMetadata(json)!!
        assertEquals("Гаррі Поттер 1", parsed.title)
        assertNull("a playlist carries no honest total", parsed.durationSeconds)
        assertEquals(listOf("a1", "b2"), parsed.entries.map { it.id })
        assertEquals(listOf("Розділ 1", "Розділ 2"), parsed.entries.map { it.title })
        assertEquals(listOf(4285L, 4075L), parsed.entries.map { it.durationSeconds })
    }

    @Test
    fun `quotes and backslashes survive the json round trip`() {
        val tricky = "Кава \"Ара\" \\ тест\nдругий рядок"
        val json = NewPipeMetadata.metadataJsonOf(
            NewPipeMetadata.Metadata(
                title = tricky,
                entries = listOf(NewPipeMetadata.Entry("x", "https://www.youtube.com/watch?v=x", "Частина \"1\"", 60L))
            )
        )
        val parsed = YouTubeSubmissionPlanner.parseMetadata(json)!!
        assertEquals(tricky, parsed.title)
        assertEquals("Частина \"1\"", parsed.entries.single().title)
    }

    @Test
    fun `fetchMetadataJson uses the injected provider`() = runBlocking {
        val json = NewPipeMetadata.fetchMetadataJson("https://www.youtube.com/watch?v=abc") { url ->
            assertEquals("https://www.youtube.com/watch?v=abc", url)
            NewPipeMetadata.Metadata(title = "Книга", durationSeconds = 100L)
        }
        assertEquals("Книга", YouTubeSubmissionPlanner.parseMetadata(json!!)!!.title)
    }

    @Test
    fun `a failing or blank provider is an honest null - never fabricated`() = runBlocking {
        assertNull(NewPipeMetadata.fetchMetadataJson("https://youtu.be/abc") { null })
        assertNull(
            NewPipeMetadata.fetchMetadataJson("https://youtu.be/abc") { NewPipeMetadata.Metadata(title = "   ") }
        )
        assertNull(
            NewPipeMetadata.fetchMetadataJson("https://youtu.be/abc") { error("engine down") }
        )
    }
}
