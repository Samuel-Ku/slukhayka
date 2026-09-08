/**
 * #582 W0.4 — the deterministic person identity, ported from Android's
 * `PersonIdentity.from` (#399): the same `AuthorIdentity.normalizedName`
 * (NFKC → trim → collapse spaces → apostrophes → ’ → lowercase) and the
 * same `FacetIdentity.boundedId(role.idPrefix, normalized)` — so the
 * browser computes the SAME person id the phone does and the
 * `person_bookmarks` sync rows match across devices.
 */
import { sha256 } from 'js-sha256'
import type { PersonRole } from './domain'

/** Android's apostrophe set, all replaced with ’ (AuthorIdentity). */
const APOSTROPHES = /['ʼ`´‘’]/g
const SPACES = /\s+/g
const MAX_NAME_LENGTH = 120

/** Android's AuthorIdentity.displayName: NFKC → trim → collapse spaces → bound. */
export function personDisplayName(rawName: string): string {
  return rawName
    .normalize('NFKC')
    .trim()
    .replace(SPACES, ' ')
    .slice(0, MAX_NAME_LENGTH)
}

/** Android's AuthorIdentity.normalizedName: apostrophes → ’ → lowercase. */
export function personNormalizedName(rawName: string): string {
  return personDisplayName(rawName).replace(APOSTROPHES, '’').toLowerCase()
}

/** Android's FacetIdentity.boundedId: `${kind}-${sha256(normalized).take(16)}`. */
export function boundedPersonId(role: PersonRole, normalizedName: string): string {
  const prefix = role === 'author' ? 'author' : 'narrator'
  return `${prefix}-${sha256(normalizedName).slice(0, 16)}`
}

/** The full identity: role, deterministic id, display and normalized names. */
export interface PersonIdentity {
  role: PersonRole
  id: string
  displayName: string
  normalizedName: string
}

/** Android's PersonIdentity.from(role, rawName) — the one identity source. */
export function personIdentityOf(role: PersonRole, rawName: string): PersonIdentity {
  const displayName = personDisplayName(rawName)
  const normalizedName = personNormalizedName(displayName)
  return { role, id: boundedPersonId(role, normalizedName), displayName, normalizedName }
}