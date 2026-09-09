/**
 * #582 W0.4 — the deterministic person identity, pinned against Android's
 * `FacetIdentity.boundedId` (`kind-${sha256(normalized).take(16)}`, UTF-8,
 * lowercase hex) and `AuthorIdentity.normalizedName` (NFKC → trim →
 * collapse spaces → apostrophes → ’ → lowercase). The phone and the
 * browser MUST compute the same id or the `person_bookmarks` sync rows
 * split across devices — these vectors are the cross-platform contract.
 */
import { describe, expect, it } from 'vitest'
import {
  boundedPersonId,
  personDisplayName,
  personIdentityOf,
  personNormalizedName,
} from './personIdentity'

describe('personIdentityOf — Android\'s canonical identity, verbatim', () => {
  it('normalizes the О\'ГЕНРІ variants to one id (AuthorIdentityTest vector)', () => {
    const straight = personIdentityOf('author', '  О\'ГЕНРІ  ')
    const curly = personIdentityOf('author', 'О’Генрі')
    const modifier = personIdentityOf('author', 'ОʼГенрі')

    expect(straight.normalizedName).toBe('о’генрі')
    expect(straight.displayName).toBe("О'ГЕНРІ")
    expect(curly.id).toBe(straight.id)
    expect(modifier.id).toBe(straight.id)
  })

  it('produces Android\'s boundedId format and the known sha256 vector', () => {
    expect(personIdentityOf('author', 'О\'ГЕНРІ').id).toBe('author-91aa129f67ea5e14')
    expect(personIdentityOf('narrator', 'Читець Оксана').id).toBe('narrator-64d29cf7c0314ae9')
    // The wire shape Android syncs: `author-<16 hex>` — the doc ids match
    // on both platforms only in exactly this form.
    expect(boundedPersonId('author', 'о’генрі')).toMatch(/^author-[0-9a-f]{16}$/)
    expect(boundedPersonId('narrator', 'читець оксана')).toMatch(/^narrator-[0-9a-f]{16}$/)
  })

  it('keeps roles apart: the same name under different roles has different ids', () => {
    expect(personIdentityOf('author', 'Читець Оксана').id).not.toBe(
      personIdentityOf('narrator', 'Читець Оксана').id,
    )
  })

  it('never merges Latin and Cyrillic lookalikes without explicit evidence', () => {
    expect(personIdentityOf('author', 'Андрій Кокотюха').id).not.toBe(
      personIdentityOf('author', 'Aндрій Кокотюха').id,
    )
  })

  it('bounded display names (Android\'s MAX_NAME_LENGTH = 120)', () => {
    expect(personDisplayName('x'.repeat(300)).length).toBe(120)
    expect(personNormalizedName('x'.repeat(300)).length).toBe(120)
  })
})