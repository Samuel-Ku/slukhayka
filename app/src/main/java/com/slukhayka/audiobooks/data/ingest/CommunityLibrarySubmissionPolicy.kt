package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.merge.MergeKey

/**
 * ADR-0050 / #830 — one OBSERVED community-library post, as the account-bound
 * fetcher reported it: the link, the claimed title/author and whatever parts
 * were really observed.
 */
data class CommunityPostRef(
    val link: String,
    val title: String,
    val author: String? = null,
    /** The observed part marker of this post (1-based), or null. */
    val partIndex: Int? = null,
    val tracks: List<TelegramTrack> = emptyList()
)

/** One book, however many posts it arrived in. */
data class CommunityBookGroup(
    val workKey: String,
    val title: String,
    val author: String?,
    /** The posts of this book, in observed part order. */
    val posts: List<CommunityPostRef>
) {
    val trackCount: Int get() = posts.sumOf { post -> post.tracks.size }
}

/** A chapter boundary the post ITSELF observed — never a guess. */
data class ObservedChapterBoundary(val trackIndex: Int, val chapterNumber: Int)

/**
 * ADR-0050 / #830 — the pure composition rules of a community-library
 * submission:
 *
 * - the sheet takes SEVERAL pastes at once, and one link is typically ONE
 *   book;
 * - parts of one book, spread across posts, collapse into ONE Edition group
 *   under the observed `Channel Post Pattern` identity (title|author);
 * - chapter boundaries are ONLY the ones the tracks themselves carry — a track
 *   without an observed marker is never promoted to a boundary.
 */
object CommunityLibrarySubmissionPolicy {

    /** «Розділ 3», «Глава 2», «Частина 1», «Chapter 4» — the observed shapes. */
    private val CHAPTER_MARKER = Regex(
        """(?iu)^\s*(?:розділ|глава|частина|chapter|part)\s*[№#]?\s*(\d+)\b"""
    )

    /**
     * Groups the submitted posts into books. A blank title cannot become a book
     * and contributes no group. Groups keep first-seen order; posts inside a
     * group follow the observed part marker, then the submission order.
     */
    fun group(posts: List<CommunityPostRef>): List<CommunityBookGroup> {
        val valid = posts.filter { it.title.isNotBlank() }
        if (valid.isEmpty()) return emptyList()
        val order = mutableListOf<String>()
        val byKey = linkedMapOf<String, MutableList<IndexedValue<CommunityPostRef>>>()
        valid.forEachIndexed { index, post ->
            val key = workKeyFor(post.title, post.author)
            if (key !in byKey) {
                byKey[key] = mutableListOf()
                order += key
            }
            byKey.getValue(key) += IndexedValue(index, post)
        }
        return order.map { key ->
            val members = byKey.getValue(key)
            val sorted = members.sortedWith(
                compareBy<IndexedValue<CommunityPostRef>> { it.value.partIndex ?: Int.MAX_VALUE }
                    .thenBy { it.index }
            )
            val first = sorted.first().value
            CommunityBookGroup(
                workKey = key,
                title = first.title,
                author = first.author,
                posts = sorted.map { it.value }
            )
        }
    }

    /**
     * The Work identity of one post — the SAME `title|author` key the rest of
     * the app merges on. A single claimed field (no author) is not a Work key:
     * such a post stays its own group under the link.
     */
    fun workKeyFor(title: String, author: String?): String {
        val mergeKey = MergeKey.keyFor(title, author.orEmpty())
        return mergeKey.ifBlank { title.trim().lowercase() }
    }

    /**
     * The chapter boundaries the tracks OBSERVED, in track order. A title
     * without a marker contributes none, and the marker's own number is kept
     * (the post may label «Розділ 7» first — that is the post's own truth, not
     * our renumbering).
     */
    fun observedBoundaries(tracks: List<TelegramTrack>): List<ObservedChapterBoundary> =
        tracks.mapIndexedNotNull { index, track ->
            val match = CHAPTER_MARKER.find(track.title) ?: return@mapIndexedNotNull null
            val number = match.groupValues.getOrNull(1)?.toIntOrNull()
                ?: return@mapIndexedNotNull null
            ObservedChapterBoundary(trackIndex = index, chapterNumber = number)
        }
}
