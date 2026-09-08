/**
 * #592 W6.1 — the server Recommendation Profile seam (ADR-0030/0031):
 * participation ON reads the listener's profile; the shelf renders from
 * it when present. With no server implementation anywhere yet — Android's
 * own consent wording: «передавання технічно вимкнене» — the seam
 * degrades to null and the shelf honestly falls back to the local
 * adaptation (ADR-0031: absent graph never blocks Огляд). The web's own
 * write path (the «внески») joins when the server layer exists; today
 * the switch's consent is all there is to revoke.
 */

/** A server profile's personal picks — the same shape the local shelf renders. */
export interface ProfilePick {
  mergeKey: string
  title: string
  author: string
  /** The collective reason («Подобається слухачам…») — only real graph edges. */
  reason?: { kind: 'collective'; title: string }
}

/** The one read seam; null = no profile (never an exception). */
export interface RecommendationProfileSource {
  load(): Promise<ProfilePick[] | null>
}

/** Honest default: no server layer exists yet (ADR-0030 implementation pending). */
export class NoServerProfileSource implements RecommendationProfileSource {
  async load(): Promise<ProfilePick[] | null> {
    return null
  }
}