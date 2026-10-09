package com.slukhayka.audiobooks.data.listening

import java.util.Calendar
import java.util.TimeZone

/**
 * #1173 (T9) — the night window of the «Нічна зміна» measurement: 00:00–04:00
 * LOCAL time.
 *
 * A tick counts by the local time it was WRITTEN at, and counts whole: its
 * milliseconds are not split across the window and not spread over the session
 * (owner's decision, 2026-10-07). Splitting time proportionally would be a
 * number nobody observed (ADR-0014).
 */
object NightWindow {
    const val FIRST_HOUR = 0
    /** Exclusive: a tick at 04:00 is morning, not night. */
    const val LAST_HOUR_EXCLUSIVE = 4

    /** Whether [epochMs] falls into the local night window of [zone]. */
    fun contains(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return calendar.get(Calendar.HOUR_OF_DAY) in FIRST_HOUR until LAST_HOUR_EXCLUSIVE
    }
}
