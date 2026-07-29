package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioParsingTest {

    @Test
    fun testAudioExtractionFromPlayerJsFormat() {
        val sampleHtml = """
            <html>
                <head>
                    <meta property="og:image" content="https://4read.org/uploads/posts/2023-01/1672532000_cover.jpg">
                </head>
                <body>
                    <div id="player"></div>
                    <script>
                        var player = new Playerjs({id: "player", file: "https://4read.org/uploads/files/chapter1.mp3,https://4read.org/uploads/files/chapter2.mp3"});
                    </script>
                </body>
            </html>
        """.trimIndent()

        val extractedAudio = mutableListOf<String>()
        val fileJsRegex = Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        fileJsRegex.findAll(sampleHtml).forEach { m ->
            val rawFile = m.groupValues[1]
            rawFile.split(",", ";").forEach { piece ->
                val clean = piece.trim()
                if (clean.startsWith("http")) {
                    extractedAudio.add(clean)
                }
            }
        }

        assertEquals(2, extractedAudio.size)
        assertTrue(extractedAudio.contains("https://4read.org/uploads/files/chapter1.mp3"))
        assertTrue(extractedAudio.contains("https://4read.org/uploads/files/chapter2.mp3"))
    }

    @Test
    fun testCoverImageMetaExtraction() {
        val sampleHtml = """
            <meta property="og:image" content="https://4read.org/uploads/posts/cover.jpg">
        """.trimIndent()

        val ogMatch = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(sampleHtml)
        val coverUrl = ogMatch?.groupValues?.get(1)

        assertEquals("https://4read.org/uploads/posts/cover.jpg", coverUrl)
    }

    @Test
    fun testIframeSrcExtraction() {
        val sampleHtml = """
            <iframe src="https://4read.org/player/embed.php?id=123" width="100%" height="200"></iframe>
        """.trimIndent()

        val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val iframeSrc = iframeRegex.find(sampleHtml)?.groupValues?.get(1)

        assertEquals("https://4read.org/player/embed.php?id=123", iframeSrc)
    }
}
