package com.slukhayka.audiobooks.data.achievements

/**
 * #704 (T6) — what the «Досягнення» screen shows, decided as data.
 *
 * The spec asks for «здобуті + видимі майбутні (приховані без спойлерів)», and
 * T5 adds the same rule from its side: «приховані нагороди не показуються в
 * списку майбутніх до здобуття». That is a RULE about the catalogue, not a
 * layout, so it lives here as a pure function the screen can render however it
 * likes — and it can be tested without a single pixel.
 */
data class AchievementBoard(
    /** Everything the listener has earned, newest first. */
    val earned: List<AchievementDefinition>,
    /** Not yet earned and safe to show: the visible ladder ahead. */
    val upcoming: List<AchievementDefinition>
) {
    val isEmpty: Boolean get() = earned.isEmpty() && upcoming.isEmpty()

    companion object {
        /**
         * Splits the catalogue by what has been earned.
         *
         * A HIDDEN award is withheld from [upcoming] until it is earned, which is
         * the whole point of it: the screen must not spoil the surprise. Once
         * earned it appears in [earned] like any other, because by then the
         * listener has seen the notice.
         *
         * [earnedIds] is a set because the store can only ever award an id once;
         * a list here would invite the caller to think order mattered, and the
         * caller's order is not the catalogue's order.
         */
        fun of(
            catalog: List<AchievementDefinition> = AchievementCatalog.definitions,
            earnedIds: Set<String>
        ): AchievementBoard {
            val (earned, unearned) = catalog.partition { it.id in earnedIds }
            return AchievementBoard(
                earned = earned,
                upcoming = unearned.filterNot { it.hidden }
            )
        }
    }
}
