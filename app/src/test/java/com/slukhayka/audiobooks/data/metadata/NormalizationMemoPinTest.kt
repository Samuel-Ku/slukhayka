package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.merge.MergeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #1589 — the normalization cache is the fix, so the cache itself gets the pin.
 *
 * The achievements pass re-normalizes the same strings on every Room
 * invalidation (own-library titles/authors × curated universes/collections).
 * The #1570 stack showed a fresh `Pattern.compile` per title; #1572 moved the
 * pass off the main thread but did not make it cheaper. The fix memoizes the
 * PURE normalization rules, so a repeated input costs a map lookup instead of
 * the regex pipeline and its allocations.
 *
 * The witness is reference identity: the memoized functions are pure, so a
 * second call for the same input must return the SAME result instance — a
 * recomputation would allocate a new String. This is deliberately black-box
 * (no test-only counters): a mutation that bypasses the memo makes every
 * `assertSame` here fail, which is the mutation-kill evidence for this pin.
 * The inputs below all need real work (trim, prefix/subtitle cuts, punctuation
 * strip, diacritic fold), so a pass-through result cannot accidentally be the
 * same object.
 */
class NormalizationMemoPinTest {

    @Test
    fun `metadata title normalization is memoized`() {
        val raw = "  Аудіокнига: Кобзар  "
        val first = MetadataAssertions.normalizeTitle(raw)
        val second = MetadataAssertions.normalizeTitle(raw)
        assertEquals(first, second)
        assertSame("repeated MetadataAssertions.normalizeTitle must be a cache hit", first, second)
    }

    @Test
    fun `metadata title normalization with an author is memoized separately`() {
        // The author only participates in the Soundbooks wrapper path; the memo
        // key must still include it so a different author can never share a
        // result that was computed for another one.
        val raw = "Аудіокнига Кобзар слухати онлайн українською"
        val first = MetadataAssertions.normalizeTitle(raw, "Тарас Шевченко")
        val second = MetadataAssertions.normalizeTitle(raw, "Тарас Шевченко")
        assertEquals(first, second)
        assertSame("repeated two-argument normalizeTitle must be a cache hit", first, second)
    }

    @Test
    fun `merge key title and person normalization are memoized`() {
        val rawTitle = "  Кобзар: повна збірка! "
        assertSame(
            "repeated MergeKey.normalizeTitle must be a cache hit",
            MergeKey.normalizeTitle(rawTitle),
            MergeKey.normalizeTitle(rawTitle)
        )
        val rawPerson = "Тарас, Шевченко"
        assertSame(
            "repeated MergeKey.normalizePerson must be a cache hit",
            MergeKey.normalizePerson(rawPerson),
            MergeKey.normalizePerson(rawPerson)
        )
    }

    @Test
    fun `collection title and author normalization are memoized`() {
        val rawTitle = "Кобзар (повне видання)"
        assertSame(
            "repeated CollectionMatcher.normalizeTitle must be a cache hit",
            CollectionMatcher.normalizeTitle(rawTitle),
            CollectionMatcher.normalizeTitle(rawTitle)
        )
        val rawAuthor = "García Márquez"
        assertSame(
            "repeated CollectionMatcher.normalizeAuthor must be a cache hit",
            CollectionMatcher.normalizeAuthor(rawAuthor),
            CollectionMatcher.normalizeAuthor(rawAuthor)
        )
    }
}
