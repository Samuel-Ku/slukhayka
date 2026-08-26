/**
 * spec-43/T5 (pre-work engine slice) — the web-side playback-attempt policy,
 * mirroring the SPIRIT of ADR-0019 (self-healing stream URLs): a dead direct
 * stream URL gets exactly ONE relay retry per user-initiated prepare, the
 * budget can never loop, and an exhausted budget surfaces the honest
 * terminal give-up instead of a fabricated retry.
 *
 * Pure state machine — it never touches `<audio>` or the network. The thin
 * AudioElement binding (T5) feeds events in and follows decisions out; the
 * relay resolver comes from the Web Transport door. Like Android's
 * `StreamHealPolicy`, any user-initiated prepare (`started`) resets the
 * chain to a fresh direct attempt; `playing` resets nothing — terminal
 * states exit via the caller, not by silent state juggling.
 */

export type AttemptKind = 'direct' | 'relay'

export interface Attempt {
  kind: AttemptKind
  url: string
}

export type AttemptEvent = { type: 'started' } | { type: 'error' } | { type: 'playing' }

export type AttemptDecision = { next: Attempt | null } | { giveUp: true }

/** Resolves the relay route for a direct Source URL; '' means no route exists. */
export type RelayUrlOf = (url: string) => string

/**
 * Decide what to attempt next given the current attempt and one event.
 *
 * - `started`  — a user-initiated prepare: fresh DIRECT attempt (budget reset).
 * - `playing`  — resets nothing; the current attempt passes through unchanged
 *                (or stays null when nothing was attempted).
 * - `error`    — null current → give up (nothing running to heal); a relay
 *                attempt that failed → give up (budget spent); a direct
 *                failure → ONE switch to the relay of `directUrl`, unless no
 *                relay route exists ('' → honest give-up).
 */
export function decideNext(
  current: Attempt | null,
  event: AttemptEvent,
  directUrl: string,
  relayUrlOf: RelayUrlOf
): AttemptDecision {
  if (event.type === 'started') {
    return { next: { kind: 'direct', url: directUrl } }
  }
  if (event.type === 'playing') {
    return { next: current }
  }
  if (current === null || current.kind === 'relay') {
    return { giveUp: true }
  }
  const relayUrl = relayUrlOf(directUrl)
  if (relayUrl === '') {
    return { giveUp: true }
  }
  return { next: { kind: 'relay', url: relayUrl } }
}
