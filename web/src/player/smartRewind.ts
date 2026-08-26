/**
 * spec-43/T5 (pre-work engine slice) — THE Smart Rewind rule for the Web
 * Client, ported semantically from Android `player/SmartRewind.kt` under
 * ADR-0003: exactly one pure rule serves both the in-session resume (live
 * engine position) and the across-restart resume (persisted Listening
 * State), so the two paths can never drift.
 *
 * Tiers copied verbatim from SmartRewind.kt:
 *   NO_REWIND_BELOW_MS = 2_000      — pauses shorter than this rewind nothing
 *   SHORT_PAUSE_MS     = 10 minutes — pauses below this rewind the short tier (3 s)
 *   DAY_MS             = 24 hours    — pauses below this rewind the medium tier (12 s)
 *   beyond a day                      → the long tier (25 s)
 *
 * Boundary semantics also copied: clamp-at-zero (a position smaller than
 * the rewind lands on 0 — the delta is bounded by the 25 s max tier), and a
 * future-dated pause (clock skew → negative duration) counts as no pause.
 *
 * Units: the web engine and the persisted Listening State carry positions
 * in SECONDS, while pause durations come from epoch-ms arithmetic — hence
 * `rewoundPositionMs(positionSeconds, pausedForMs)` returns the rewound
 * position in SECONDS. Same rule, same tiers, same boundaries as the JVM
 * original; only the position unit follows the web platform's.
 */

/** Pauses shorter than this rewind nothing (sub-second double-taps included). */
export const NO_REWIND_BELOW_MS = 2_000

/** Pauses below this are "short" (3 s rewind). */
export const SHORT_PAUSE_MS = 10 * 60 * 1000

/** Pauses below a day are "hours" (12 s rewind). */
export const DAY_MS = 24 * 60 * 60 * 1000

export const REWIND_SHORT_SECONDS = 3
export const REWIND_MEDIUM_SECONDS = 12
export const REWIND_LONG_SECONDS = 25

/** Seconds to rewind for a pause of `pauseDurationMs`. Pure, deterministic. */
export function computeRewindSeconds(pauseDurationMs: number): number {
  if (pauseDurationMs < NO_REWIND_BELOW_MS) return 0
  if (pauseDurationMs < SHORT_PAUSE_MS) return REWIND_SHORT_SECONDS
  if (pauseDurationMs < DAY_MS) return REWIND_MEDIUM_SECONDS
  return REWIND_LONG_SECONDS
}

/**
 * ADR-0003 — the one rewind rule. Given the position playback resumes from
 * (seconds) and how long the pause lasted (ms), return the rewound position
 * (seconds): same tiers, same clamp-at-zero boundary, future-dated pauses
 * leave the position untouched. Pure and deterministic.
 */
export function rewoundPositionMs(positionSeconds: number, pausedForMs: number): number {
  const rewindSeconds = computeRewindSeconds(pausedForMs)
  return Math.max(0, positionSeconds - rewindSeconds)
}
