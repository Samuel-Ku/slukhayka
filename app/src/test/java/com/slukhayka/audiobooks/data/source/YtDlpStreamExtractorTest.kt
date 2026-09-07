package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the yt-dlp resolve-only backend (ADR-0035 / #603).
 * The fixture is a REAL capture (2026-09-07, `yt-dlp -J --no-download
 * --no-playlist https://www.youtube.com/watch?v=ozaZXk5Qcwc`), URLs elided
 * to placeholders — only the JSON shape is under test. Precedent:
 * `YouTubeStreamResolverTest` — no network, no Android, no database.
 */
class YtDlpStreamExtractorTest {

    /** The captured shape: storyboard mhtml stubs, an HLS audio shell, three
     *  audio-only formats (139/140 m4a, 251 opus) and a video+audio file. */
    private val capturedJson = """
        {
          "id": "ozaZXk5Qcwc",
          "title": "Звички невдах | Стівен Адамс | Аудіокнига українською повністю",
          "duration": 5000,
          "formats": [
            {"format_id": "sb3", "ext": "mhtml", "protocol": "mhtml", "vcodec": "none", "acodec": "none", "abr": 0, "url": "https://i.ytimg.com/sb/ozaZXk5Qcwc/storyboard3_L0/default.jpg"},
            {"format_id": "233", "ext": "mp4", "protocol": "m3u8_native", "vcodec": "none", "acodec": null, "abr": null, "url": "https://manifest.googlevideo.com/api/manifest/hls_playlist/..."},
            {"format_id": "139", "ext": "m4a", "protocol": "https", "vcodec": "none", "acodec": "mp4a.40.5", "abr": 48.783, "url": "https://rr.googlevideo.com/videoplayback?id=139"},
            {"format_id": "140", "ext": "m4a", "protocol": "https", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129.472, "url": "https://rr.googlevideo.com/videoplayback?id=140"},
            {"format_id": "251", "ext": "webm", "protocol": "https", "vcodec": "none", "acodec": "opus", "abr": 139.001, "url": "https://rr.googlevideo.com/videoplayback?id=251"},
            {"format_id": "22", "ext": "mp4", "protocol": "https", "vcodec": "avc1.64001F", "acodec": "mp4a.40.2", "abr": null, "url": "https://rr.googlevideo.com/videoplayback?id=22"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parse keeps only audio-only direct formats`() {
        val parsed = YtDlpStreamExtractor.parseFormats(capturedJson)

        // Storyboards (acodec none), the HLS shell (acodec null) and the
        // video+audio file (vcodec != none) are never audio candidates.
        assertEquals(
            listOf("https://rr.googlevideo.com/videoplayback?id=139", "https://rr.googlevideo.com/videoplayback?id=140", "https://rr.googlevideo.com/videoplayback?id=251"),
            parsed.map { it.url }
        )
        assertTrue(parsed.all { it.isDirectUrl })
    }

    @Test
    fun `selection prefers the highest bitrate progressive m4a`() {
        val best = YouTubeStreamResolver.pickBestAudio(YtDlpStreamExtractor.parseFormats(capturedJson))

        // 140 (m4a, 129k) wins over 251 (opus, 139k): M4A is preferred the
        // same way NewPipe's selection does — a consistent choice across the
        // two engines behind the same seam.
        assertEquals("https://rr.googlevideo.com/videoplayback?id=140", best?.url)
    }

    @Test
    fun `null fields are stripped before parsing`() {
        // yt-dlp emits `"acodec": null` on HLS/storyboard formats; MiniJson
        // cannot represent null, so the sanitizer must drop the pairs, with
        // and without surrounding commas.
        assertEquals("""{"a":1}""", YtDlpStreamExtractor.stripNullFields("""{"a":1,"b":null}"""))
        assertEquals("""{"b":2}""", YtDlpStreamExtractor.stripNullFields("""{"a":null,"b":2}"""))
        assertEquals("""{"a":1,"b":2}""", YtDlpStreamExtractor.stripNullFields("""{"a":1,"x":null,"b":2}"""))
        assertEquals("""{}""", YtDlpStreamExtractor.stripNullFields("""{"x":null}"""))
        // The captured yt-dlp shape stays parseable: nulls inside formats.
        assertTrue(YtDlpStreamExtractor.parseFormats(capturedJson).isNotEmpty())
    }

    @Test
    fun `broken or empty json parses to no candidates`() {
        assertTrue(YtDlpStreamExtractor.parseFormats("not json").isEmpty())
        assertTrue(YtDlpStreamExtractor.parseFormats("{}").isEmpty())
        assertTrue(YtDlpStreamExtractor.parseFormats("""{"formats":[]}""").isEmpty())
    }

    @Test
    fun `extract parses the launcher output and selects the best`() = runBlocking {
        val streams = YtDlpStreamExtractor.extract(
            "https://www.youtube.com/watch?v=ozaZXk5Qcwc",
            launcher = { capturedJson }
        )

        assertEquals(3, streams.size)
        assertEquals(
            "https://rr.googlevideo.com/videoplayback?id=140",
            YouTubeStreamResolver.pickBestAudio(streams)?.url
        )
    }

    @Test
    fun `a failed launcher yields no candidates - the honest failure`() = runBlocking {
        assertTrue(YtDlpStreamExtractor.extract("https://www.youtube.com/watch?v=ozaZXk5Qcwc", launcher = { null }).isEmpty())
        assertTrue(
            YtDlpStreamExtractor.extract(
                "https://www.youtube.com/watch?v=ozaZXk5Qcwc",
                launcher = { throw java.io.IOException("yt-dlp broke") }
            ).isEmpty()
        )
    }

    @Test
    fun `the backend plugs into the resolver seam`() = runBlocking {
        val resolver = YouTubeStreamResolver { url ->
            YtDlpStreamExtractor.extract(url, launcher = { capturedJson })
        }

        // Non-YouTube URLs pass through untouched; a YouTube URL resolves
        // through the yt-dlp backend to the same pick as NewPipe.
        assertEquals("https://4read.org/audio.mp3", resolver.resolve("https://4read.org/audio.mp3"))
        assertEquals(
            "https://rr.googlevideo.com/videoplayback?id=140",
            resolver.resolve("https://www.youtube.com/watch?v=ozaZXk5Qcwc")
        )
    }
}