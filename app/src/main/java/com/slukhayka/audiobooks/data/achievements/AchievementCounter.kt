package com.slukhayka.audiobooks.data.achievements

/**
 * #1173 (T9) — stable keys of the durable counters ([AchievementStore.incrementCounter]).
 *
 * Strings, not enum ordinals, for the same reason the playback events use
 * them: a stored key must survive a reordering of the code that reads it.
 */
object AchievementCounter {
    /**
     * The moment the «до кінця розділу» mode was ARMED (#700).
     *
     * Deliberately not `TIMER_STOP`: at a chapter boundary the timer re-arms
     * for the next chapter, so the stop event may never be written at all and
     * the listener would lose the count they earned. Arming is the fact.
     */
    const val END_OF_CHAPTER_ARM = "end_of_chapter_arm"
}
