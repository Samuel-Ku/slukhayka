package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementEvaluatorTest {
    @Test fun `the first explicit book earns one award only once`() {
        val snapshot = AchievementProgress(explicitBooks = 1)
        assertEquals(listOf("first_book"), AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id })
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(snapshot, setOf("first_book")).map { it.id })
    }
    @Test fun `each real first step earns only its own award`() {
        val cases = listOf(
            AchievementProgress(playbackStarts = 1) to "first_playback",
            AchievementProgress(acceptedReviews = 1) to "first_review",
            AchievementProgress(notInterestedChoices = 1) to "first_not_interested",
            AchievementProgress(searchImports = 1) to "first_search_import",
            AchievementProgress(offlinePlaybackStarts = 1) to "first_offline_playback",
            AchievementProgress(downloadedBooks = 1) to "first_download",
            AchievementProgress(completedBooks = 1) to "first_completion"
        )
        for ((snapshot, id) in cases) {
            // #700 — a first step earns its own award. For `completedBooks = 1`
            // the catalogue ALSO opens the first rung of the book ladder
            // (`books_1`), and that is intended, not duplication: the spec asks
            // for both «перша завершена книга» (story 8) and a ladder that
            // starts at 1 (story 18). So this asserts the first-step award is
            // PRESENT rather than that it is the only one.
            val earned = AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }
            assertTrue("$id мусить бути серед виданих: $earned", id in earned)
            assertTrue(
                "повторна видача не має нічого додавати",
                AchievementEvaluator.evaluate(snapshot, earned.toSet()).map { it.id }.isEmpty()
            )
        }
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id })
    }

    @Test fun `verified hour levels unlock at exactly the five specified thresholds`() {
        val cases = listOf(
            3_600_000L to listOf("hours_1"),
            36_000_000L to listOf("hours_1", "hours_10"),
            360_000_000L to listOf("hours_1", "hours_10", "hours_100"),
            3_600_000_000L to listOf("hours_1", "hours_10", "hours_100", "hours_1000"),
            18_000_000_000L to listOf("hours_1", "hours_10", "hours_100", "hours_1000", "hours_5000")
        )
        for ((millis, expected) in cases) {
            val at = AchievementProgress(verifiedListeningMillis = millis)
            assertEquals(expected, AchievementEvaluator.evaluate(at, emptySet()).map { it.id })
            assertEquals(expected.dropLast(1), AchievementEvaluator.evaluate(at.copy(verifiedListeningMillis = millis - 1), emptySet()).map { it.id })
            assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(at, expected.toSet()).map { it.id })
        }
    }

    @Test fun `unknown or negative evidence does not invent any award`() {
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id })
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(explicitBooks = -1, verifiedListeningMillis = -1), emptySet()).map { it.id })
    }

    @Test fun `hidden definitions are only returned after factual earning`() {
        val hidden = listOf(AchievementDefinition("secret", "hidden", 1, AchievementMetric.COMPLETED_BOOKS, 1, hidden = true))
        assertEquals(emptyList<String>(), AchievementEvaluator.evaluate(AchievementProgress(), emptySet(), hidden).map { it.id })
        assertEquals(listOf("secret"), AchievementEvaluator.evaluate(AchievementProgress(completedBooks = 1), emptySet(), hidden).map { it.id })
    }

    /**
     * #700 (T2) — the BOOK path. Every level unlocks at EXACTLY its threshold
     * and not one book earlier, which is the whole point of a level ladder: an
     * off-by-one here would hand a listener "10 books" at nine.
     *
     * Filled with real numbers rather than a count of loops, so a threshold
     * typed wrong in the catalogue fails here instead of shipping.
     */
    @Test fun `every book level unlocks at exactly its threshold`() {
        val expected = listOf(1L to "books_1", 5L to "books_5", 10L to "books_10", 25L to "books_25",
            50L to "books_50", 100L to "books_100", 250L to "books_250", 500L to "books_500")

        for ((threshold, id) in expected) {
            val justBefore = AchievementEvaluator.evaluate(
                AchievementProgress(completedBooks = threshold - 1), emptySet()
            ).map { it.id }
            assertFalse(
                "$id не має відкриватись на ${threshold - 1} книгах",
                id in justBefore
            )
            val at = AchievementEvaluator.evaluate(
                AchievementProgress(completedBooks = threshold), emptySet()
            ).map { it.id }
            assertTrue("$id мусить відкритись на $threshold книгах", id in at)
        }
    }

    /** A repeated evaluation never re-awards what is already earned. */
    @Test fun `an already earned book level is not awarded twice`() {
        val snapshot = AchievementProgress(completedBooks = 10)
        val once = AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }
        val twice = AchievementEvaluator.evaluate(snapshot, once.toSet()).map { it.id }

        assertTrue("books_10 мусить бути в першій видачі", "books_10" in once)
        assertTrue("повторна видача не має нічого додавати", twice.isEmpty())
    }

    /**
     * #700 (T2) — the SHAPE bands, each at its exact threshold.
     *
     * «Коротка форма» is 10 books under three hours; «Епопея» is a single 30+
     * hour book; «Довгожитель» is five of them. The thresholds are the spec's,
     * and they are pinned here the same way the book ladder is — one value
     * wrong in the catalogue must fail this test, not ship.
     */
    @Test fun `every books-shape award unlocks at exactly its threshold`() {
        assertFalse(
            "«Коротка форма» не має відкриватись на 9 книгах",
            "short_form_10" in AchievementEvaluator.evaluate(
                AchievementProgress(shortCompletedBooks = 9), emptySet()
            ).map { it.id }
        )
        assertTrue(
            "«Коротка форма» мусить відкритись на 10 книгах",
            "short_form_10" in AchievementEvaluator.evaluate(
                AchievementProgress(shortCompletedBooks = 10), emptySet()
            ).map { it.id }
        )

        assertFalse(
            "«Епопея» не має відкриватись без жодної епічної книги",
            "epic_1" in AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id }
        )
        val oneEpic = AchievementEvaluator.evaluate(
            AchievementProgress(epicCompletedBooks = 1), emptySet()
        ).map { it.id }
        assertTrue("«Епопея» мусить відкритись на першій", "epic_1" in oneEpic)
        assertFalse("«Довгожитель» не має відкриватись разом із нею", "long_liver_5" in oneEpic)

        val fiveEpic = AchievementEvaluator.evaluate(
            AchievementProgress(epicCompletedBooks = 5), emptySet()
        ).map { it.id }
        assertTrue("«Довгожитель» мусить відкритись на п'ятій", "long_liver_5" in fiveEpic)
    }

    /**
     * The honesty guard that keeps the bands from lying: a book whose duration
     * is unknown contributes to NEITHER side. The count arrives from the DAO
     * already filtered, so this pins the evaluator's side — a zero snapshot
     * earns neither band.
     */
    @Test fun `an unknown duration earns no shape award`() {
        val earned = AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id }

        assertFalse("порожній снапшот не має давати «Коротку форму»", "short_form_10" in earned)
        assertFalse("порожній снапшот не має давати «Епопею»", "epic_1" in earned)
    }

    /**
     * #700 (T2) — «Глибокий запас» opens at ten downloaded books, not nine.
     *
     * The metric behind it counts books whose EVERY chapter has a downloaded
     * track with a real file on disk (see `DownloadedBookProof`), so a row
     * alone cannot claim it. Here the boundary is what matters: the award must
     * not appear one book early.
     */
    @Test fun `deep reserve opens at exactly ten downloaded books`() {
        assertFalse(
            "«Глибокий запас» не має відкриватись на 9 книгах",
            "deep_reserve_10" in AchievementEvaluator.evaluate(
                AchievementProgress(downloadedBooks = 9), emptySet()
            ).map { it.id }
        )
        assertTrue(
            "«Глибокий запас» мусить відкритись на 10 книгах",
            "deep_reserve_10" in AchievementEvaluator.evaluate(
                AchievementProgress(downloadedBooks = 10), emptySet()
            ).map { it.id }
        )
    }

    /**
     * The three offline awards that need HOURS are absent on purpose: nothing
     * records offline listening time yet, and approximating it from the count
     * of offline starts would be a different fact dressed as this one
     * (ADR-0014). This test keeps that a decision rather than an oversight —
     * if someone adds them, they must add the data too.
     */
    @Test fun `offline awards that need hours are absent, not approximated`() {
        val all = AchievementCatalog.definitions.map { it.id }

        assertTrue("жодної нагороди за офлайн-години бути не має",
            all.none { it in setOf("autonomous_10h", "airplane_1", "downloaded_gourmet_100h") })
        assertTrue("«Глибокий запас» натомість мусить бути", "deep_reserve_10" in all)
    }

    /**
     * #701 (T3) — «Глибокий пошук» rewards the MECHANISM, not just any import.
     *
     * The fact behind it is written only when the import really came from
     * global search (`MainViewModel` records it under
     * `target.fromGlobalSearch`). So the award must stay closed on a snapshot
     * where no such import happened — otherwise it would celebrate every book
     * that merely followed a search.
     */
    @Test fun `deep search opens only on a real global-search import`() {
        assertFalse(
            "«Глибокий пошук» не має відкриватись без жодного імпорту з пошуку",
            "deep_search" in AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id }
        )
        assertTrue(
            "«Глибокий пошук» мусить відкритись після імпорту з глобального пошуку",
            "deep_search" in AchievementEvaluator.evaluate(
                AchievementProgress(searchImports = 1), emptySet()
            ).map { it.id }
        )
    }

    /**
     * The three mechanisms that need data nothing records yet stay ABSENT on
     * purpose: «Резолвер» (cross-resolve), «Відновлювач» (source recovery) and
     * «Той самий голос» (same narration from two sources). Keeping this a test
     * means the gap is a decision, not an oversight — adding them must add
     * their data too.
     */
    @Test fun `mechanism awards that need unrecorded events are absent`() {
        val all = AchievementCatalog.definitions.map { it.id }

        assertTrue(
            "жодної нагороди за кросс-резолв, відновлення чи збіг начитки бути не має",
            all.none { it in setOf("resolver", "recoverer", "same_voice") }
        )
        assertTrue("«Глибокий пошук» натомість мусить бути", "deep_search" in all)
    }

    /**
     * #702 (T4) — the genre ladder: breadth at 8 and 12 genres, depth at 25
     * books in one. Each must open at EXACTLY its threshold.
     */
    @Test fun `genre breadth and depth open at exactly their thresholds`() {
        fun counts(n: Int) = (1..n).associate { "genre-$it" to 1L }

        assertFalse(
            "«Жанровий поліглот» не має відкриватись на 7 жанрах",
            "genre_polyglot_8" in AchievementEvaluator.evaluate(
                AchievementProgress(genreCounts = counts(7)), emptySet()
            ).map { it.id }
        )
        val eight = AchievementEvaluator.evaluate(
            AchievementProgress(genreCounts = counts(8)), emptySet()
        ).map { it.id }
        assertTrue("«Жанровий поліглот» мусить відкритись на 8 жанрах", "genre_polyglot_8" in eight)
        assertFalse("«Всеїдний» не має відкриватись разом із ним", "omnivore_12" in eight)

        val twelve = AchievementEvaluator.evaluate(
            AchievementProgress(genreCounts = counts(12)), emptySet()
        ).map { it.id }
        assertTrue("«Всеїдний» мусить відкритись на 12 жанрах", "omnivore_12" in twelve)

        // Depth is a separate axis: many genres with one book each must NOT
        // open «Однолюб жанру», and one genre with 25 must.
        assertFalse(
            "глибина не має відкриватись від широти",
            "mono_genre_25" in twelve
        )
        assertTrue(
            "«Однолюб жанру» мусить відкритись на 25 книгах одного жанру",
            "mono_genre_25" in AchievementEvaluator.evaluate(
                AchievementProgress(genreCounts = mapOf("detective" to 25L)), emptySet()
            ).map { it.id }
        )
    }

    /**
     * #702 (T4) — «немає заяви — немає поступу». An empty genre map earns no
     * genre award at all, which is what keeps a Work with no claimed genre from
     * counting toward anything (ADR-0014).
     */
    @Test fun `no claimed genre earns no genre award`() {
        val earned = AchievementEvaluator.evaluate(AchievementProgress(), emptySet()).map { it.id }

        assertTrue(
            "порожні жанри не мають давати жодної жанрової нагороди",
            earned.none { it in setOf("genre_polyglot_8", "omnivore_12", "mono_genre_25") }
        )
    }
}
