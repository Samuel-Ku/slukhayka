package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0050 / #830 — dedup, Edition reuse (no progress fork) and the daily batch budget. */
class CommunityEditionMappingPolicyTest {

    private fun post(title: String, narrator: String? = null) = CommunityPostRef(
        link = "https://t.me/slukhayka/573/1",
        title = title,
        author = "Автор",
        narrator = narrator
    )

    private fun group(link: String) = CommunityBookGroup(
        workKey = link,
        title = link,
        author = "Автор",
        posts = listOf(CommunityPostRef(link, link))
    )

    @Test
    fun `a link already in the shared base is a friendly state, not an error`() {
        assertEquals(
            CommunityAddition.ALREADY_IN_SHARED_BASE,
            CommunityEditionMappingPolicy.decide(post("Кобзар"), sharedBaseContainsLink = true, knownEditions = emptyList())
        )
    }

    @Test
    fun `the same Work with the same narration and public audio is deduped`() {
        val known = listOf(KnownEdition(workKey = workKey("Кобзар"), narrator = "Іван", hasPublicSource = true))

        assertEquals(
            CommunityAddition.ALREADY_PUBLIC,
            CommunityEditionMappingPolicy.decide(
                post("Кобзар", narrator = "Іван"),
                sharedBaseContainsLink = false,
                knownEditions = known
            )
        )
    }

    @Test
    fun `a different named narration is honestly a new Edition`() {
        val known = listOf(KnownEdition(workKey = workKey("Кобзар"), narrator = "Іван", hasPublicSource = true))

        assertEquals(
            "Петро is a different narration of the same Work",
            CommunityAddition.NEW_EDITION,
            CommunityEditionMappingPolicy.decide(
                post("Кобзар", narrator = "Петро"),
                sharedBaseContainsLink = false,
                knownEditions = known
            )
        )
    }

    @Test
    fun `an unknown narration on either side never reuses and never forks progress`() {
        val named = listOf(KnownEdition(workKey = workKey("Кобзар"), narrator = "Іван", hasPublicSource = true))
        assertNull(
            "one named, one blank is not evidence of sameness",
            CommunityEditionMappingPolicy.reuseEdition(workKey("Кобзар"), narrator = null, knownEditions = named)
        )

        val blank = listOf(KnownEdition(workKey = workKey("Кобзар"), narrator = "", hasPublicSource = true))
        assertNull(
            "a blank stored narrator does not adopt a named claim",
            CommunityEditionMappingPolicy.reuseEdition(workKey("Кобзар"), narrator = "Іван", knownEditions = blank)
        )

        assertTrue(
            "both unknown agree, so the Edition is reused instead of forked",
            CommunityEditionMappingPolicy.reuseEdition(workKey("Кобзар"), narrator = "", knownEditions = blank) != null
        )
    }

    @Test
    fun `narration sameness is case-insensitive`() {
        val known = listOf(KnownEdition(workKey = workKey("Кобзар"), narrator = "іван франко", hasPublicSource = false))

        assertEquals(
            "the STORED Edition is reused, so its own narrator spelling is kept",
            "іван франко",
            CommunityEditionMappingPolicy.reuseEdition(workKey("Кобзар"), "ІВАН ФРАНКО", known)?.narrator
        )
    }

    @Test
    fun `the day's budget accepts what fits and defers the rest honestly`() {
        val groups = listOf(group("a"), group("b"), group("c"))

        val planned = CommunityEditionMappingPolicy.planBatch(groups, remainingToday = 2)

        assertEquals(listOf("a", "b"), planned.accepted.map { it.workKey })
        assertEquals(listOf("c"), planned.deferred.map { it.workKey })
    }

    @Test
    fun `an exhausted budget defers everything instead of refusing the batch`() {
        val groups = listOf(group("a"), group("b"))

        val planned = CommunityEditionMappingPolicy.planBatch(groups, remainingToday = 0)

        assertTrue(planned.accepted.isEmpty())
        assertEquals(listOf("a", "b"), planned.deferred.map { it.workKey })
    }

    private fun workKey(title: String) = CommunityLibrarySubmissionPolicy.workKeyFor(title, "Автор")
}
