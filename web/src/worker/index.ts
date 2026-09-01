/**
 * spec-43 — Web Transport (ADR-0024): the ONE server-side door between the
 * Web Client and the six sources. Routes:
 *
 *   GET /api/sources                     → [{id, displayName}]
 *   GET /api/catalog?source=&url=        → ParsedCatalog (url optional: source root)
 *   GET /api/book?source=&url=           → BookDetail with enriched chapters
 *   GET /api/search?source=&q=           → CatalogCard[] for one source
 *   GET /api/search-all?q=               → [{id, displayName, cards}] best-effort
 *
 * Every outbound fetch is host-allowlisted per source — INCLUDING every
 * redirect hop (redirect: 'manual', re-validated, bounded depth). Failures
 * degrade to `{ok:false}` with an honest reason — never fabricated data.
 */
import { mayFetch, REGISTRY, sourceEntry, type SourceEntry } from './registry'
import type { CatalogCard, SourceId } from './types'
import { mergeWorkFeed } from './workFeed'

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8' }
const MAX_REDIRECTS = 3

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS })
}

function ok(data: unknown): Response {
  return json({ ok: true, data })
}

function fail(reason: string, status = 400): Response {
  return json({ ok: false, reason }, status)
}

const DEFAULT_UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36'

/**
 * The guarded transport: every hop (initial URL and each redirect target)
 * must pass the source's allowlist, or the fetch refuses with null.
 */
async function guardedFetchText(
  entry: SourceEntry,
  url: string,
  referer: string,
  depth = 0,
  extraHeaders: Record<string, string> = {},
): Promise<string | null> {
  if (!mayFetch(entry, url)) return null
  if (depth > MAX_REDIRECTS) return null
  try {
    const response = await fetch(url, {
      headers: { referer, 'user-agent': DEFAULT_UA, ...extraHeaders },
      redirect: 'manual',
    })
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get('location')
      if (!location) return null
      return guardedFetchText(entry, new URL(location, url).toString(), referer, depth + 1, extraHeaders)
    }
    if (!response.ok) return null
    return await response.text()
  } catch {
    return null
  }
}

function transportFor(entry: SourceEntry) {
  return (target: string): Promise<string | null> => guardedFetchText(entry, target, entry.adapter.baseUrl)
}

interface WorkerEnv {
  // No bindings yet; declared for the Cloudflare module contract.
}

/** The minimal shape Cloudflare's runtime expects; declared locally to stay dep-free. */
interface ExportedHandler<E> {
  fetch(request: Request, env: E, ctx: { waitUntil(promise: Promise<unknown>): void }): Promise<Response> | Response
}

export default {
  async fetch(request: Request, _env: WorkerEnv): Promise<Response> {
    const url = new URL(request.url)
    const { pathname, searchParams } = url

    if (pathname === '/api/sources') {
      return ok(Object.entries(REGISTRY).map(([id, entry]) => ({ id, displayName: entry.adapter.displayName })))
    }

    // #458: one bounded Work page. We ask each available adapter for its
    // front page best-effort; a failed adapter simply contributes no cards.
    // The optional source filter reuses this exact Work contract.
    if (pathname === '/api/work-feed') {
      const requestedSource = searchParams.get('source')?.trim() ?? ''
      if (requestedSource && !sourceEntry(requestedSource)) return fail(`unknown source: ${requestedSource}`)
      const cursor = Number.parseInt(searchParams.get('cursor') ?? '0', 10)
      const entries = Object.entries(REGISTRY).filter(([id]) => !requestedSource || id === requestedSource)
      const settled = await Promise.allSettled(entries.map(async ([id, entry]) => {
        const pageUrl = entry.adapter.baseUrl
        const html = await guardedFetchText(entry, pageUrl, pageUrl)
        if (html === null) return { sourceId: id as SourceId, cards: [] as CatalogCard[] }
        const parsed = entry.adapter.parseCatalog(html, pageUrl)
        return { sourceId: id as SourceId, cards: parsed?.sections.flatMap((section) => section.cards) ?? [] }
      }))
      const sources = settled
        .filter((result): result is PromiseFulfilledResult<{ sourceId: SourceId; cards: CatalogCard[] }> => result.status === 'fulfilled')
        .map((result) => result.value)
      return ok(mergeWorkFeed(sources, Number.isFinite(cursor) ? cursor : 0))
    }

    // #458: search projects into the same Work → Edition → Source model as
    // the feed. A Source that cannot search simply contributes nothing; it
    // never changes the shape back to a per-source card list.
    if (pathname === '/api/work-search') {
      const query = searchParams.get('q')?.trim() ?? ''
      const requestedSource = searchParams.get('source')?.trim() ?? ''
      if (query.length < 2) return fail('query too short')
      if (requestedSource && !sourceEntry(requestedSource)) return fail(`unknown source: ${requestedSource}`)
      const entries = Object.entries(REGISTRY)
        .filter(([id, entry]) => (!requestedSource || id === requestedSource) && entry.searchUrl && entry.adapter.search)
      const settled = await Promise.allSettled(entries.map(async ([id, entry]) => {
        const searchEntry = entry as Required<Pick<SourceEntry, 'searchUrl' | 'searchHeaders'>> & SourceEntry
        const searchUrl = searchEntry.searchUrl(query)
        const payload = mayFetch(entry, searchUrl)
          ? await guardedFetchText(entry, searchUrl, entry.adapter.baseUrl, 0, searchEntry.searchHeaders ?? {})
          : null
        let cards: CatalogCard[] = []
        if (payload !== null) {
          try {
            cards = entry.adapter.search!(payload, searchUrl) ?? []
          } catch {
            cards = []
          }
        }
        return { sourceId: id as SourceId, cards }
      }))
      const sources = settled
        .filter((result): result is PromiseFulfilledResult<{ sourceId: SourceId; cards: CatalogCard[] }> => result.status === 'fulfilled')
        .map((result) => result.value)
      return ok(mergeWorkFeed(sources))
    }

    if (pathname === '/api/search' || pathname === '/api/search-all') {
      const query = searchParams.get('q')?.trim() ?? ''
      if (query.length < 2) return fail('query too short')
      if (pathname === '/api/search') {
        const sourceId = searchParams.get('source') ?? ''
        const entry = sourceEntry(sourceId)
        if (!entry?.searchUrl || !entry.adapter.search) return fail(`search not available for ${sourceId}`)
        const searchUrl = entry.searchUrl(query)
        if (!mayFetch(entry, searchUrl)) return fail(`search url outside ${sourceId} hosts`)
        const payload = await guardedFetchText(entry, searchUrl, entry.adapter.baseUrl, 0, entry.searchHeaders ?? {})
        if (payload === null) return fail(`search did not answer for ${sourceId}`, 502)
        try {
          const cards = entry.adapter.search(payload, searchUrl) ?? []
          return ok(cards)
        } catch {
          return fail(`search parsing failed for ${sourceId}`, 502)
        }
      }
      // search-all: best-effort across all searchable sources
      const settled = await Promise.allSettled(
        Object.entries(REGISTRY)
          .filter(([, entry]) => entry.searchUrl && entry.adapter.search)
          .map(async ([id, entry]) => {
            const searchEntry = entry as Required<Pick<SourceEntry, 'searchUrl' | 'searchHeaders'>> & SourceEntry
            const searchUrl = searchEntry.searchUrl(query)
            if (!mayFetch(entry, searchUrl)) return { id, displayName: entry.adapter.displayName, cards: [] as never[] }
            const payload = await guardedFetchText(entry, searchUrl, entry.adapter.baseUrl, 0, searchEntry.searchHeaders ?? {})
            if (payload === null) return { id, displayName: entry.adapter.displayName, cards: [] }
            try {
              const cards = entry.adapter.search!(payload, searchUrl) ?? []
              return { id, displayName: entry.adapter.displayName, cards }
            } catch {
              return { id, displayName: entry.adapter.displayName, cards: [] }
            }
          }),
      )
      const groups = (settled as PromiseSettledResult<{ id: string; displayName: string; cards: CatalogCard[] }>[])
        .filter((result) => result.status === 'fulfilled')
        .map((result) => (result as PromiseFulfilledResult<{ id: string; displayName: string; cards: CatalogCard[] }>).value)
        .filter((group) => group.cards.length > 0)
      return ok(groups)
    }

    if (pathname === '/api/audio') {
      const raw = searchParams.get('u') ?? ''
      let target: string
      try {
        target = decodeURIComponent(raw)
        new URL(target)
      } catch {
        return fail('bad audio url')
      }
      const allHosts = [...new Set(Object.values(REGISTRY).flatMap((entry) => entry.allowedHosts))]
      let host: string
      try {
        host = new URL(target).hostname
      } catch {
        return fail('bad audio url')
      }
      if (!allHosts.some((allowed) => host === allowed || host.endsWith(`.${allowed}`))) {
        return fail('audio host not allowlisted', 403)
      }
      const range = request.headers.get('range') ?? undefined
      try {
        const upstream = await fetch(target, {
          headers: {
            referer: 'https://4read.org/',
            'user-agent': DEFAULT_UA,
            ...(range ? { range } : {}),
          },
        })
        const headers = new Headers()
        for (const hop of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control']) {
          const value = upstream.headers.get(hop)
          if (value) headers.set(hop, value)
        }
        headers.set('access-control-allow-origin', '*')
        return new Response(upstream.body, { status: upstream.status, headers })
      } catch {
        return fail('audio relay failed', 502)
      }
    }

    if (pathname === '/api/catalog' || pathname === '/api/book') {
      const sourceId = searchParams.get('source') ?? ''
      const pageUrl = searchParams.get('url') ?? ''
      const entry = sourceEntry(sourceId)
      if (!entry) return fail(`unknown source: ${sourceId}`)
      const target = pageUrl || entry.adapter.baseUrl
      if (!mayFetch(entry, target)) return fail(`url is outside ${sourceId}'s own hosts`)

      const html = await guardedFetchText(entry, target, entry.adapter.baseUrl)
      if (html === null) return fail(`source did not answer for ${target}`, 502)

      try {
        if (pathname === '/api/catalog') {
          const parsed = entry.adapter.parseCatalog(html, target)
          if (!parsed) return fail(`unrecognized catalog markup at ${target}`, 502)
          return ok(parsed)
        }
        const detail = await entry.buildBook(html, target, transportFor(entry))
        if (!detail) return fail(`unrecognized book markup at ${target}`, 502)
        if (detail.chapters.length === 0) return ok({ ...detail, unavailableReason: 'no-chapters' })
        return ok(detail)
      } catch {
        return fail(`parsing failed for ${target}`, 502)
      }
    }

    return fail('not found', 404)
  },
} satisfies ExportedHandler<WorkerEnv>
