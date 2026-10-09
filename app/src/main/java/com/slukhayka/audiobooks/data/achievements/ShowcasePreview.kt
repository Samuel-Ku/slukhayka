package com.slukhayka.audiobooks.data.achievements

/**
 * #705 (T7) — what publishing the showcase will actually take off the device.
 *
 * The confirmation shows exactly these: the public name, how many awards
 * travel, and WHICH ones — by name, because «до 3 нагород» is not something a
 * listener can consent to meaningfully without seeing them. Nothing goes out
 * before this is confirmed; there is no silent path.
 *
 * Modelled on [com.slukhayka.audiobooks.data.collections.PublicationPreview],
 * which set this shape for collections in spec-51 (#691).
 */
data class ShowcasePreview(
    val pseudonym: String,
    val awards: List<ShowcaseAwardSnapshot>
) {
    val awardCount: Int get() = awards.size
}

object ShowcasePreviewFactory {

    /**
     * @return the preview, or null when this showcase can never be published —
     * no usable pseudonym, or nothing on the showcase. An honest refusal, not
     * an empty confirmation the listener could still accept and thereby publish
     * nothing while believing they had published something.
     */
    fun of(
        pinnedIds: List<String>,
        pseudonym: String,
        /**
         * The published limit, passed IN rather than imported: the constant
         * belongs to the published-collection codec, and importing it here
         * would make `achievements` depend on `collections` — which the
         * publishing side then depends on in turn, closing a package cycle.
         */
        maxPseudonymLength: Int,
        nameOf: (String) -> String?
    ): ShowcasePreview? {
        val cleanPseudonym = pseudonym.trim().take(maxPseudonymLength)
        if (cleanPseudonym.isEmpty()) return null
        val awards = ShowcasePublication.of(pinnedIds, nameOf)
        if (awards.isEmpty()) return null
        return ShowcasePreview(pseudonym = cleanPseudonym, awards = awards)
    }
}
