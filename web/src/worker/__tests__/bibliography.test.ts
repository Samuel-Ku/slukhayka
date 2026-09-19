import { afterEach, describe, expect, it, vi } from 'vitest'
import quota429 from '../fixtures/googlebooks-quota-429-2026-09-19.json?raw'
import worker from '../index'

/**
 * #858 (T5) — the keyless Google Books door. The API key exists ONLY as the
 * Worker env-secret `GOOGLE_BOOKS_KEY`; these tests drive the handler with a
 * fake upstream (`globalThis.fetch` stubbed), so no live Google call is made
 * and none could be verified here — the deployment secret is the owner's
 * ticket. The 429 fixture is a VERBATIM live capture (2026-09-19) of the
 * keyless answer this door exists to prevent.
 */

function request(query: string): Request {
  return new Request(`https://transport.example/api/bibliography/isbn${query}`)
}

describe('#858 — /api/bibliography/isbn, the keyless Google Books door', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('appends the env-secret key and returns the upstream body verbatim', async () => {
    const upstreamBody = JSON.stringify({
      kind: 'books#volumes',
      totalItems: 1,
      items: [{ volumeInfo: { title: 'Кобзар', language: 'uk' } }],
    })
    const calls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (target: string) => {
        calls.push(target)
        return new Response(upstreamBody, { status: 200, headers: { 'content-type': 'application/json' } })
      }),
    )

    const response = await worker.fetch(request('?isbn=978-617-702-3202'), { GOOGLE_BOOKS_KEY: 'test-secret' })

    expect(response.status).toBe(200)
    expect(await response.text()).toBe(upstreamBody)
    expect(calls).toHaveLength(1)
    expect(calls[0]).toContain('https://www.googleapis.com/books/v1/volumes?q=isbn:9786177023202')
    expect(calls[0]).toContain('key=test-secret')
  })

  it('refuses honestly and reaches Google not at all when the secret is absent', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const response = await worker.fetch(request('?isbn=9786177023202'), {})

    expect(response.status).toBe(503)
    expect(fetchMock).not.toHaveBeenCalled()
    expect(await response.text()).not.toContain('test-secret')
  })

  it('never echoes the secret back to the client', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{"kind":"books#volumes","totalItems":0}', { status: 200 })),
    )

    const response = await worker.fetch(request('?isbn=9786177023202'), { GOOGLE_BOOKS_KEY: 'top-secret' })

    expect(await response.text()).not.toContain('top-secret')
  })

  it('passes an upstream quota refusal through transparently', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(quota429, { status: 429 })))

    const response = await worker.fetch(request('?isbn=9786177023202'), { GOOGLE_BOOKS_KEY: 'k' })

    expect(response.status).toBe(429)
    expect(await response.text()).toContain('RESOURCE_EXHAUSTED')
  })

  it('refuses a value that is not an ISBN without touching the network', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const response = await worker.fetch(request('?isbn=not-an-isbn'), { GOOGLE_BOOKS_KEY: 'k' })

    expect(response.status).toBe(400)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
