/**
 * W6.2 (#593) — the service worker: precaches the app shell and serves the
 * offline streaming cache of recently listened audio. Network-first for
 * everything (ADR-0024 untouched — direct-first and the relay fallback
 * behave exactly as before); the cache answers ONLY when the network
 * itself failed, and a resumed <audio> Range request gets a synthesized
 * 206 from the primed full body.
 *
 * This file is built by Vite (vite-plugin-pwa, injectManifest strategy) —
 * the pure policy it wires lives in offline/policy.ts and is tested in
 * vitest; this layer is deliberately thin.
 */
import { createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching'
import { AUDIO_CACHE_NAME, buildRangeResponse, parseRange, shouldCacheResponse } from './offline/policy'

/** Minimal structural typing: tsc compiles this with the DOM lib. */
type SwFetchEvent = {
  request: Request
  respondWith(response: Promise<Response> | Response): void
}
type SwSelf = {
  addEventListener(type: 'fetch', listener: (event: SwFetchEvent) => void): void
  skipWaiting(): void
  clients: { claim(): Promise<void> }
}
const sw = self as unknown as SwSelf
const manifest = (self as unknown as { __WB_MANIFEST: Array<{ url: string; revision: string | null }> }).__WB_MANIFEST

precacheAndRoute(manifest)
sw.skipWaiting()
void sw.clients.claim()

/** The precached app shell — the app itself opens offline. */
const shellHandler = createHandlerBoundToURL('/index.html') as unknown as (event: SwFetchEvent) => Promise<Response>

sw.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return

  if (request.mode === 'navigate') {
    event.respondWith(shellHandler(event))
    return
  }

  event.respondWith(
    (async () => {
      try {
        const response = await fetch(request)
        // Cache full relay audio responses the SW happens to see (the page
        // primer on the playing event is the main path; this covers any
        // straggler). Partial Range asks are never cached (the policy).
        if (shouldCacheResponse(request.url, response.status, request.headers.get('Range'))) {
          const cache = await caches.open(AUDIO_CACHE_NAME)
          void cache.put(request, response.clone())
        }
        return response
      } catch {
        // The network failed — serve the offline cache if it honestly has
        // this resource; otherwise let the request fail (the player's own
        // fallback machinery decides what «honestly unavailable» means).
        const cached = await caches.match(request)
        if (cached === undefined) throw new TypeError('offline: not cached')
        const rangeHeader = request.headers.get('Range')
        if (rangeHeader !== null) {
          const body = await cached.arrayBuffer()
          const range = parseRange(rangeHeader, body.byteLength)
          if (range !== null) {
            return buildRangeResponse(body, body.byteLength, range, cached.headers.get('Content-Type') ?? 'audio/mpeg')
          }
        }
        return cached
      }
    })(),
  )
})