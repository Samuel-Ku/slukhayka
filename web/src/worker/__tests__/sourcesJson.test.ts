import { describe, expect, it } from 'vitest'
import sources from '../../../../sources.json'
import { REGISTRY, sourceEntry } from '../registry'
import { isScamSourceKey, SOURCE_METADATA, SOURCE_ORDER } from '../sourceMetadata'

/**
 * ADR-0038 — the web side of the parity gate: the worker's registry and
 * metadata tables agree with `sources.json` (repo root), which the worker
 * imports natively. A fact changed on either side fails this test.
 */
describe('sources.json — the one carrier of source facts (ADR-0038)', () => {
  /** ADR-0038 §5: worker-local keys vs the canonical (persisted) domain ids. */
  const WEB_KEY_TO_ID: Record<string, string> = {
    fourread: '4read',
    'sound-books': 'soundbooks',
    'audiobook-mp3': 'audiobookmp3',
  }

  const idOf = (key: string): string => WEB_KEY_TO_ID[key] ?? key

  const jsonById = new Map(sources.sources.map((s) => [s.id, s]))

  it('every web source id exists in the registry', () => {
    for (const key of SOURCE_ORDER) {
      expect(jsonById.has(idOf(key)), key).toBe(true)
    }
  })

  it('SOURCE_ORDER matches the registry order', () => {
    const webIds = SOURCE_ORDER.map(idOf)
    const jsonWebOrder = sources.sources
      .filter((s) => webIds.includes(s.id))
      .sort((a, b) => a.order - b.order)
      .map((s) => s.id)
    expect(webIds).toEqual(jsonWebOrder)
  })

  it('SOURCE_METADATA mirrors the registry facts', () => {
    for (const key of SOURCE_ORDER) {
      const json = jsonById.get(idOf(key))!
      const meta = SOURCE_METADATA[key]
      expect(meta.label, key).toBe(json.displayName)
      // Trailing-slash normalization is not a fact difference.
      expect(meta.homeUrl.replace(/\/$/, ''), key).toBe((json.homeUrl ?? '').replace(/\/$/, ''))
      expect(meta.contentLanguage, key).toBe(json.contentLanguage)
    }
  })

  it('browserSessionRequired agrees with accessMode — except the documented sluhay divergence', () => {
    for (const key of SOURCE_ORDER) {
      const json = jsonById.get(idOf(key))!
      // ADR-0038 §4: session-bound-ness is platform knowledge — sluhay needs
      // the Android session but the worker fetches it server-side.
      if (key === 'sluhay') continue
      expect(SOURCE_METADATA[key].browserSessionRequired, key).toBe(json.accessMode === 'BROWSER')
    }
  })

  it('REGISTRY allowedHosts stay within the registry transportHosts', () => {
    for (const key of SOURCE_ORDER) {
      // ukrainianaudiobooks is a web-listed source with a worker entry still
      // pending (spec-47 T6 follow-up): skip it, never assert on undefined.
      const entry = (REGISTRY as Partial<typeof REGISTRY>)[key]
      if (!entry) continue
      const json = jsonById.get(idOf(key))!
      for (const host of entry.allowedHosts) {
        expect(json.transportHosts, `${key}: ${host}`).toContain(host)
      }
    }
  })

  it('search URLs and catalogue URLs ride the registry', () => {
    for (const key of SOURCE_ORDER) {
      const entry = (REGISTRY as Partial<typeof REGISTRY>)[key]
      if (!entry) continue
      const json = jsonById.get(idOf(key))!
      if (entry.searchUrl && json.searchUrl) {
        const q = 'Кобзар'
        expect(entry.searchUrl(q), key).toBe(json.searchUrl.replace('{q}', encodeURIComponent(q)))
      }
      if (entry.catalogUrl && json.catalogUrl) {
        // librivox appends limit/offset to the declared base URL.
        expect(entry.catalogUrl.startsWith(json.catalogUrl), key).toBe(true)
      }
    }
  })

  it('the scam source is never served by the worker', () => {
    // 4read's clean-client audio is a 52-second artefact: the carrier marks
    // it scam, the order drops it and the lookup refuses it — no catalog,
    // search, feed or book fetch can reach it.
    expect(isScamSourceKey('fourread')).toBe(true)
    expect(isScamSourceKey('4read')).toBe(true)
    expect(isScamSourceKey('sound-books')).toBe(false)
    expect(SOURCE_ORDER).not.toContain('fourread')
    expect(sourceEntry('fourread')).toBeNull()
    expect(sourceEntry('4read')).toBeNull()
  })
})
