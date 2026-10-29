package com.slukhayka.audiobooks.data.achievements

import kotlinx.coroutines.flow.StateFlow

/**
 * #1574 — the ONE snapshot seam the achievements module exposes.
 *
 * Before this interface the engine watched a cold combine tower forever: every
 * DAO invalidation (a progress write lands about every five seconds while a
 * book plays) recomputed the whole `AchievementProgress` and re-ran
 * `evaluate` + `award`, while `MainActivity` recomposed the board and the
 * listener title on each of those emissions.
 *
 * The snapshot is now OWNED here: [snapshot] is the latest value, and
 * [refresh] is the only thing that moves it. The engine calls [refresh] on the
 * discrete transitions that actually change the metrics (completion, a
 * progress-write checkpoint, app start, opening the achievements screen); a
 * DAO write that nobody pulled explicitly changes nothing.
 *
 * A `refresh()` computes the WHOLE snapshot together (the same tower, one
 * pass), so no field can be newer than another and there is no hot/cold split
 * or throttling layer — just explicit pulls.
 */
interface AchievementSnapshot {
    /** The latest published progress; starts empty and moves only on [refresh]. */
    val snapshot: StateFlow<AchievementProgress>

    /** Recomputes the whole progress and publishes it; returns the new value. */
    suspend fun refresh(): AchievementProgress
}
