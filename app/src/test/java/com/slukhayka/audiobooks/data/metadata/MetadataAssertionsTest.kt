package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.source.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM seam (ADR-0004): the ONE place Metadata Assertions are applied to
 * library rows. Table-driven — every rule is a pure function of the claim,
 * no I/O, so the JVM suite pins each boundary without Robolectric.
 */
class MetadataAssertionsTest {

    // --- Sentinel normalization -------------------------------------------

    @Test
    fun `duration claims normalize - blank zero negative and legacy sentinel are unknown`() {
        val cases = listOf<Pair<Long?, Long?>>(
            null to null,
            0L to null,
            -5L to null,
            MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS to null,
            1L to 1L,
            3_600L to 3_600L,
            14_401L to 14_401L
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeDurationSeconds($claimed)", expected, MetadataAssertions.normalizeDurationSeconds(claimed))
        }
    }

    // --- SEO title scrub (spec-24 T1) -------------------------------------

    @Test
    fun `title scrub strips the curated SEO phrases from the end across every separator`() {
        val cases = listOf<Pair<String, String>>(
            // ` - `
            "Тіні забутих предків - аудіокнига слухати онлайн" to "Тіні забутих предків",
            // ` — `
            "Кобзар — слухати онлайн" to "Кобзар",
            // ` (`
            "Пасажир (аудіокнига онлайн)" to "Пасажир",
            // `, `
            "Нейромант, слухати онлайн безкоштовно" to "Нейромант",
            // `|`
            "1984 | аудіокнига українською" to "1984",
            // Nested phrases — the longer «аудіокнига слухати онлайн» wins
            // over «слухати онлайн», so the whole suffix is gone in one pass.
            "Книга - аудіокнига слухати онлайн" to "Книга",
            // Case-insensitive.
            "Кобзар - АУДІОКНИГА СЛУХАТИ ОНЛАЙН" to "Кобзар"
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeTitle($claimed)", expected, MetadataAssertions.normalizeTitle(claimed))
        }
    }

    @Test
    fun `title scrub strips every phrase from the end across every separator`() {
        // The full phrase x separator matrix (#162 AC-1): every curated phrase
        // behind every separator variant comes out clean.
        val separators = listOf(" - ", " — ", " (", ", ", "|")
        for (phrase in MetadataAssertions.SEO_TITLE_PHRASES) {
            for (sep in separators) {
                // The ` (` separator closes the phrase in parens.
                val claimed = if (sep == " (") "Кобзар (${phrase})" else "Кобзар$sep$phrase"
                assertEquals("normalizeTitle($claimed)", "Кобзар", MetadataAssertions.normalizeTitle(claimed))
            }
        }
    }

    @Test
    fun `title scrub keeps clean titles untouched`() {
        val cases = listOf(
            "Тіні забутих предків",
            "Кобзар",
            "Нейромант",
            "  Пасажир  ",
            "Книга про аудіокниги",
            // The phrase in the MIDDLE is not a suffix — untouched.
            "Слухати онлайн: гід по аудіокнигах",
            // A whole-title bare word is kept (the bare-word strip needs a
            // separator before it, and a whole-title word has none).
            "Аудіокнига",
            "АУДІОКНИГА",
            "Нейромант. Аудіокнига українською мовою"
        )
        cases.forEach { claimed ->
            assertEquals("normalizeTitle($claimed)", claimed.trim(), MetadataAssertions.normalizeTitle(claimed))
        }
    }

    @Test
    fun `title scrub cuts the plural site-brand suffix`() {
        // Spec-27 (#184) BUG-002: «АудіоКниги Українською» is the site brand
        // 4read appends to its raw page <title>. The plural form the site
        // actually uses is a curated phrase, so the raw title cleans fully.
        assertEquals(
            "Трохи ненависті",
            MetadataAssertions.normalizeTitle("Трохи ненависті - АудіоКниги Українською")
        )
        // A whole-title brand is kept, never blanked (the never-blank rule).
        assertEquals(
            "АудіоКниги Українською",
            MetadataAssertions.normalizeTitle("АудіоКниги Українською")
        )
    }

    @Test
    fun `title scrub never blanks a title and is idempotent`() {
        // The whole title is the phrase — keep the original, never blank.
        assertEquals("слухати онлайн", MetadataAssertions.normalizeTitle("слухати онлайн"))
        assertEquals("аудіокнига українською", MetadataAssertions.normalizeTitle("аудіокнига українською"))

        // Idempotent: a second pass matches nothing (the startup cleanup
        // relies on this — a second run reports zero changes).
        val dirty = "Тіні забутих предків — аудіокнига слухати онлайн"
        val once = MetadataAssertions.normalizeTitle(dirty)
        val twice = MetadataAssertions.normalizeTitle(once)
        assertEquals(once, twice)
        assertEquals("Тіні забутих предків", twice)
    }

    @Test
    fun `title scrub strips the real multi-word source suffixes`() {
        // Real suffixes observed in source fixtures (2026-08-17): sluhay.com /
        // sluhayknigi site brands and sluhay.com.ua's «…Слухай аудіокнигу
        // онлайн».
        val cases = listOf<Pair<String, String>>(
            "Кобзар - слухай аудіокнигу онлайн" to "Кобзар",
            "Трохи ненависті — слухай безкоштовні аудіокниги онлайн українською мовою" to "Трохи ненависті",
            "Метаморфоза Землі - аудіокниги українською мовою безкоштовно" to "Метаморфоза Землі",
            "Кобзар | безкоштовні аудіокниги онлайн українською мовою" to "Кобзар"
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeTitle($claimed)", expected, MetadataAssertions.normalizeTitle(claimed))
        }
    }

    @Test
    fun `title scrub strips a leading audiobook prefix behind a separator`() {
        val cases = listOf<Pair<String, String>>(
            "Аудіокнига: Метро 2033" to "Метро 2033",
            "Аудіокнига - Метро 2033" to "Метро 2033",
            "аудіокнига — Метро 2033" to "Метро 2033",
            "Аудіокнигу: Метро 2033" to "Метро 2033",
            "АУДІОКНИГА | Метро 2033" to "Метро 2033"
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeTitle($claimed)", expected, MetadataAssertions.normalizeTitle(claimed))
        }
        // The prefix is only a prefix: a mid-title «аудіокнига» is untouched.
        assertEquals("Книга про аудіокниги", MetadataAssertions.normalizeTitle("Книга про аудіокниги"))
    }

    @Test
    fun `title scrub strips a bare audiobook word as a separated suffix`() {
        val cases = listOf<Pair<String, String>>(
            "Метро 2033 - аудіокнига" to "Метро 2033",
            "Кобзар — аудіокнига" to "Кобзар",
            "Кобзар, аудіокниги" to "Кобзар",
            "Пасажир | аудіокнигу" to "Пасажир",
            "Нейромант (аудіокнига)" to "Нейромант"
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeTitle($claimed)", expected, MetadataAssertions.normalizeTitle(claimed))
        }
        // A bare word that is a natural last word (space before, no separator)
        // is kept: «…про аудіокниги» must not lose its subject.
        assertEquals("Книга про аудіокниги", MetadataAssertions.normalizeTitle("Книга про аудіокниги"))
    }

    @Test
    fun `title scrub strips emoji anywhere`() {
        assertEquals("Метро 2033", MetadataAssertions.normalizeTitle("💙💛 Метро 2033"))
        assertEquals("Метро 2033", MetadataAssertions.normalizeTitle("Метро 2033 💙💛"))
        assertEquals("Метро 2033", MetadataAssertions.normalizeTitle("💙Метро💛 2033"))
    }

    // --- Brand-scrub ------------------------------------------------------

    @Test
    fun `claimed text normalizes - blank and brand placeholders are absent`() {
        val cases = listOf<Pair<String?, String?>>(
            null to null,
            "" to null,
            "   " to null,
            "4read.org" to null,
            "Аудиокнига 4read.org" to null,
            "4READ Voice Narrator" to null,
            "Жан-Крістоф Гранже" to "Жан-Крістоф Гранже",
            "  Тарас Шевченко  " to "Тарас Шевченко"
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeClaimedText($claimed)", expected, MetadataAssertions.normalizeClaimedText(claimed))
        }
    }

    // --- Never-clobber duration -------------------------------------------

    @Test
    fun `duration delta never clobbers a known value with an unknown claim`() {
        val cases = listOf<Pair<Long?, Long>>(
            null to 3_600L,        // unknown claim keeps existing
            0L to 3_600L,
            MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS to 3_600L,
            7_200L to 7_200L,      // real claim wins
            3_600L to 3_600L,      // same value — no change
            7_200L to 7_200L       // real claim over sentinel-existing? existing already normalized
        )
        cases.forEach { (claimed, expected) ->
            assertEquals(
                "durationDelta(3600, $claimed)",
                expected,
                MetadataAssertions.durationDelta(3_600L, claimed)
            )
        }
        // Sentinel stored on the existing row is also treated as unknown.
        assertEquals(
            7_200L,
            MetadataAssertions.durationDelta(MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS, 7_200L)
        )
    }

    // --- Series-on-URL-change ---------------------------------------------

    @Test
    fun `series applies only when its URL changed`() {
        // No claim → nothing.
        assertNull(MetadataAssertions.seriesDelta("https://s/1", null, "Цикл", 1))
        // Same URL → nothing (title/index updates are not a membership signal).
        assertNull(MetadataAssertions.seriesDelta("https://s/1", "https://s/1", "Цикл", 1))
        // URL changed → delta carries the new values.
        val changed = MetadataAssertions.seriesDelta("https://s/1", "https://s/2", "Цикл", 2)
        assertEquals("https://s/2", changed?.url)
        assertEquals("Цикл", changed?.title)
        assertEquals(2, changed?.index)
        // First series (no existing URL) → applies.
        val first = MetadataAssertions.seriesDelta(null, "https://s/2", "Цикл", 1)
        assertEquals("https://s/2", first?.url)
        // URL changed, title unknown → url still updates, title stays null.
        val noTitle = MetadataAssertions.seriesDelta("https://s/1", "https://s/2", null, null)
        assertEquals("https://s/2", noTitle?.url)
        assertNull(noTitle?.title)
    }

    // --- Cover-only-non-blank ---------------------------------------------

    @Test
    fun `cover applies only when non-blank and never brand-scrubbed`() {
        assertNull(MetadataAssertions.coverDelta(null))
        assertNull(MetadataAssertions.coverDelta(""))
        assertNull(MetadataAssertions.coverDelta("   "))
        // Cover URLs may legitimately live on the source's own domain.
        assertEquals(
            "https://4read.org/uploads/cover.jpg",
            MetadataAssertions.coverDelta(" https://4read.org/uploads/cover.jpg ")
        )
    }

    // --- Chapter materialization ------------------------------------------

    @Test
    fun `chapter materialization uses the dash id format, one title fallback and duration conventions`() {
        // ADR-0007: the module materializes BOTH lists — the Edition's
        // logical chapters and the importing Source's physical tracks.
        val materialized = MetadataAssertions.materializeChaptersAndTracks(
            editionId = "ed-b1",
            sourceId = "4read",
            bookId = "b1",
            bookTitle = "Пасажир",
            chapters = listOf(
                SourceChapter("Розділ 1", "https://s/1.mp3", durationSeconds = 600L),
                SourceChapter("", "https://s/2.mp3", durationSeconds = MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS),
                SourceChapter("  ", "https://s/3.mp3", durationSeconds = 0L)
            )
        )
        val chapters = materialized.chapters
        val tracks = materialized.tracks

        // The dash id format — the single format for all new books.
        assertEquals(listOf("b1_ch_1", "b1_ch_2", "b1_ch_3"), chapters.map { it.id })
        // Real title kept; the one blank-title fallback carries the book title.
        assertEquals("Розділ 1", chapters[0].title)
        assertEquals("Глава 2 (Пасажир)", chapters[1].title)
        assertEquals("Глава 3 (Пасажир)", chapters[2].title)
        // Duration convention: real claim survives, sentinel/zero become 0.
        assertEquals(listOf(600L, 0L, 0L), chapters.map { it.durationSeconds })
        // Indexes intact; the physical stream URLs live on the TRACK rows.
        assertEquals(listOf(0, 1, 2), chapters.map { it.chapterIndex })
        assertEquals(listOf(0, 1, 2), tracks.map { it.trackIndex })
        assertEquals("https://s/3.mp3", tracks[2].url)
        assertEquals(setOf("4read"), tracks.map { it.sourceId }.toSet())
    }
}
