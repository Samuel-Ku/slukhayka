package com.slukhayka.audiobooks.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 #564 (ADR-0033) — the PosterCard's two under-cover text slots stay
 * separate.
 *
 * The owner's decision (variant B, 2026-09-19): the recommendation reason is
 * the canonical plain `MetadataChip`, but it must NOT ride the shared
 * `caption` slot — otherwise the Listen shelf's «Частина N» context line
 * silently becomes a chip. The two slots are a rendered-layout contract, and
 * the only honest guard is the source itself (the same source-scan style as
 * `LibraryCanonicalRowGuardTest` and `ResidualEmptyStatesSourceGuardTest`):
 * a snapshot pin proves today's pixels, this scan keeps the split from being
 * re-merged by the next card edit.
 */
class PosterCardReasonSlotGuardTest {

    private val posterCardSource: String by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(
                System.getProperty("user.dir"),
                "src/main/java/com/slukhayka/audiobooks/ui/components/PosterCard.kt"
            ),
            File(
                System.getProperty("user.dir"),
                "app/src/main/java/com/slukhayka/audiobooks/ui/components/PosterCard.kt"
            )
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("PosterCard.kt not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the reason slot renders the canonical plain chip`() {
        val body = slotBody("reason")
        assertTrue(
            "PosterCard's `reason` slot must render MetadataChip(text = …) — body was:\n$body",
            body.contains("MetadataChip(text =")
        )
    }

    @Test
    fun `the caption slot stays a bare Text and never becomes a chip`() {
        val body = slotBody("caption")
        assertTrue(
            "PosterCard's `caption` slot must render a bare Text — body was:\n$body",
            body.contains("Text(")
        )
        assertFalse(
            "PosterCard's `caption` slot must NOT render a MetadataChip — body was:\n$body",
            body.contains("MetadataChip")
        )
    }

    /** The `slot?.let { … }` render block from the main PosterCard. */
    private fun slotBody(slot: String): String =
        Regex("""$slot\?\.let[ \t]*\{(.*?)\n[ \t]*\}""", RegexOption.DOT_MATCHES_ALL)
            .find(posterCardSource)
            ?.groupValues
            ?.get(1)
            ?: error("no `$slot?.let { … }` block found in PosterCard.kt")
}
