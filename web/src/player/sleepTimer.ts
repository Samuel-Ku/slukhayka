/**
 * W5.1 — port of Android's sleep timer policy (spec-22 T5, the pure rules
 * behind `AudioPlayerManager.setSleepTimer` / `extendSleepTimerBy15Minutes`
 * / `rearmEndOfChapterTimerIfActive`). One state machine, no DOM, no
 * timers — the transport (AudioEngine's 1s ticker) only feeds it seconds.
 *
 * Options are Android's exact vocabulary: `0` = off, `-1` = «до кінця
 * розділу», then 5/15/30/45/60/90 minutes. Both timed modes fade the
 * volume 1.0→0.0 linearly over the last 30 s (Android's fade window), and
 * an active timer can be extended by exactly +15 minutes preserving the
 * exact current remainder (the shake-shortcut rule, exposed as the visible
 * action too). An end-of-chapter timer re-arms at the NEXT chapter's
 * remaining duration so it still stops exactly at the chapter boundary.
 */

/** Android's option set, verbatim: 0 = off, -1 = end of chapter, minutes. */
export const SLEEP_TIMER_OPTIONS: readonly number[] = [0, -1, 5, 15, 30, 45, 60, 90]

/** The fade window: volume ramps 1.0→0.0 over the last 30 seconds. */
export const SLEEP_TIMER_FADE_SECONDS = 30

/** The canonical extension step, in seconds (Android: +15 minutes). */
export const SLEEP_TIMER_EXTEND_SECONDS = 15 * 60

export interface SleepTimerState {
  /** Android's selector: 0 = off, -1 = end of chapter, else minutes. */
  minutes: number
  remainingSeconds: number
  isEndOfChapter: boolean
}

/** The inactive state — what `setSleepTimer(0)` resets to. */
export const SLEEP_TIMER_OFF: SleepTimerState = { minutes: 0, remainingSeconds: 0, isEndOfChapter: false }

export function isSleepTimerActive(state: SleepTimerState): boolean {
  return state.remainingSeconds > 0
}

/** Linear fade 1.0→0.0 over the last 30 s; outside the window it is 1.0. */
export function sleepTimerFadeVolume(remainingSeconds: number): number {
  if (remainingSeconds <= 0) return 0
  if (remainingSeconds >= SLEEP_TIMER_FADE_SECONDS) return 1
  return remainingSeconds / SLEEP_TIMER_FADE_SECONDS
}

/**
 * Sets the timer. `minutes == 0` turns it off; `minutes == -1` arms the
 * end-of-chapter mode with the caller-provided remaining seconds of THIS
 * chapter; a positive value is an ordinary countdown.
 */
export function setSleepTimer(
  minutes: number,
  chapterRemainingSeconds: number | null,
): SleepTimerState {
  if (minutes === 0) return { ...SLEEP_TIMER_OFF }
  if (minutes === -1) {
    const remaining = Math.max(1, Math.round(chapterRemainingSeconds ?? 0))
    return { minutes: -1, remainingSeconds: remaining, isEndOfChapter: true }
  }
  return {
    minutes,
    remainingSeconds: Math.max(1, minutes * 60),
    isEndOfChapter: false,
  }
}

/**
 * One tick of the 1s transport clock. Decrements the exact remainder; at
 * zero the timer has FIRED (the caller pauses playback) and the state
 * collapses to off. A paused book never ticks (the ticker only runs while
 * playing, matching Android's countdown on the playing media clock).
 */
export function tickSleepTimer(state: SleepTimerState, secondsElapsed = 1): SleepTimerState {
  if (!isSleepTimerActive(state)) return state
  const remaining = Math.max(0, state.remainingSeconds - Math.max(0, Math.round(secondsElapsed)))
  if (remaining <= 0) return { ...SLEEP_TIMER_OFF }
  return { ...state, remainingSeconds: remaining }
}

/**
 * Android's canonical +15-minute command: the exact current remainder is
 * preserved and an end-of-chapter timer becomes an ordinary timed remainder.
 */
export function extendSleepTimer(state: SleepTimerState): SleepTimerState {
  const currentRemaining = state.remainingSeconds
  if (currentRemaining <= 0) return state
  const extended = currentRemaining + SLEEP_TIMER_EXTEND_SECONDS
  return {
    minutes: Math.ceil(extended / 60),
    remainingSeconds: extended,
    isEndOfChapter: false,
  }
}

/**
 * Re-arms an end-of-chapter timer for the newly prepared chapter (manual
 * skip or auto-advance) so it still stops exactly at the chapter boundary.
 * A non-end-of-chapter timer is untouched.
 */
export function rearmEndOfChapterTimer(
  state: SleepTimerState,
  nextChapterRemainingSeconds: number,
): SleepTimerState {
  if (!state.isEndOfChapter || !isSleepTimerActive(state)) return state
  return { ...state, remainingSeconds: Math.max(1, Math.round(nextChapterRemainingSeconds)) }
}

/** Human «m:ss» label for a remaining countdown (Android's %d:%02d). */
export function sleepTimerCountdown(remainingSeconds: number): string {
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = remainingSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}