package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.duration.DurationBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditionDurationPolicyTest {

    @Test
    fun `bucket boundaries and labels come from one honest duration policy`() {
        val cases = listOf(
            1L to (FacetDurationBucket.UNDER_FIVE_HOURS to "До 5 год"),
            17_999L to (FacetDurationBucket.UNDER_FIVE_HOURS to "До 5 год"),
            18_000L to (FacetDurationBucket.FIVE_TO_TEN_HOURS to "5–10 год"),
            35_999L to (FacetDurationBucket.FIVE_TO_TEN_HOURS to "5–10 год"),
            36_000L to (FacetDurationBucket.TEN_TO_TWENTY_HOURS to "10–20 год"),
            71_999L to (FacetDurationBucket.TEN_TO_TWENTY_HOURS to "10–20 год"),
            72_000L to (FacetDurationBucket.TWENTY_HOURS_OR_MORE to "Понад 20 год"),
            DurationSanity.MAX_PLAUSIBLE_SECONDS to
                (FacetDurationBucket.TWENTY_HOURS_OR_MORE to "Понад 20 год")
        )

        cases.forEach { (seconds, expected) ->
            val bucket = EditionDurationPolicy.bucketFor(seconds)
            assertEquals(expected.first, bucket)
            assertEquals(expected.second, EditionDurationPolicy.labelFor(bucket!!))
        }

        listOf(
            -1L,
            0L,
            DurationBuckets.FABRICATED_LEGACY_SECONDS,
            DurationSanity.MAX_PLAUSIBLE_SECONDS + 1L
        ).forEach { assertNull(EditionDurationPolicy.bucketFor(it)) }
    }

    @Test
    fun `one known Edition stays exact equivalent Editions become one rounded value and different Editions form a range`() {
        assertEquals(
            EditionDurationSummary.Single(19_861L),
            EditionDurationPolicy.summarize(listOf(19_861L))
        )
        assertEquals(
            EditionDurationSummary.Single(19_860L),
            EditionDurationPolicy.summarize(listOf(19_830L, 19_890L))
        )
        assertEquals(
            EditionDurationSummary.Range(18_000L, 28_800L),
            EditionDurationPolicy.summarize(listOf(28_800L, 18_000L))
        )
        assertNull(
            EditionDurationPolicy.summarize(
                listOf(0L, DurationBuckets.FABRICATED_LEGACY_SECONDS)
            )
        )
    }
}
