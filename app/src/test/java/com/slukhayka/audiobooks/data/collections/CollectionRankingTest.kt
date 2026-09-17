package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec-51 (#692/#693) — one ordering shared by the block, the rail and the profile. */
class CollectionRankingTest {

    private fun collection(id: String, sum: Int, count: Int, publishedAt: Long) = PublishedCollection(
        authorId = "a".repeat(64),
        collectionId = id,
        pseudonym = "Слухач",
        title = "Тема",
        description = "",
        bookIds = listOf("b"),
        reasons = listOf(""),
        ratingSum = sum,
        ratingCount = count,
        publishedAt = publishedAt
    )

    @Test
    fun `order is real average, then votes, then newest`() {
        val best = collection("best", sum = 10, count = 2, publishedAt = 1)      // 5.0
        val good = collection("good", sum = 9, count = 2, publishedAt = 2)       // 4.5
        val popular = collection("popular", sum = 20, count = 5, publishedAt = 3) // 4.0
        val unrated = collection("unrated", sum = 0, count = 0, publishedAt = 99)

        val top = CollectionRanking.top(listOf(unrated, popular, good, best))

        assertEquals(listOf("best", "good", "popular", "unrated"), top.map { it.collectionId })
    }

    @Test
    fun `equal averages are ordered by the number of real votes`() {
        val few = collection("few", sum = 5, count = 1, publishedAt = 1)   // 5.0
        val many = collection("many", sum = 15, count = 3, publishedAt = 1) // 5.0

        assertEquals(listOf("many", "few"), CollectionRanking.top(listOf(few, many)).map { it.collectionId })
    }

    @Test
    fun `no votes is never above a real average`() {
        val unratedButNew = collection("unrated", sum = 0, count = 0, publishedAt = 1_000)
        val oneStar = collection("one", sum = 1, count = 1, publishedAt = 1)

        assertEquals(listOf("one", "unrated"), CollectionRanking.top(listOf(unratedButNew, oneStar)).map { it.collectionId })
    }

    @Test
    fun `the shelf is capped at ten`() {
        val many = (1..15).map { collection("c$it", sum = 5, count = 1, publishedAt = it.toLong()) }

        assertEquals(10, CollectionRanking.top(many).size)
        assertEquals(0, CollectionRanking.top(many, limit = 0).size)
    }
}
