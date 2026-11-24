import { afterEach, describe, expect, it, vi } from 'vitest'
import { MAX_TEXT_BYTES, readBoundedText } from '../boundedText'
import worker from '../index'

const request = () => new Request('https://transport.example/api/bibliography/isbn?isbn=9786177023202')
const catalogRequest = () => new Request('https://transport.example/api/catalog?source=sound-books')
const signal = () => new AbortController().signal
const lengthMetadata: Record<string, string>[] = [
  {}, { 'content-length': '1' }, { 'content-encoding': 'gzip', 'content-length': '10' },
]
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('upstream text resource boundaries', () => {
  it('cancels a declared oversized response before reading it', async () => {
    const cancel = vi.fn()
    const body = new ReadableStream({ cancel })
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { headers: { 'content-length': String(MAX_TEXT_BYTES + 1) } })))
    expect((await worker.fetch(catalogRequest(), {})).status).toBe(502)
    expect(cancel).toHaveBeenCalledOnce()
  })

  it.each(lengthMetadata)
  ('rejects actual oversized bytes regardless of metadata %j', async (headers) => {
    const cancel = vi.fn()
    let chunk = 0
    const body = new ReadableStream<Uint8Array>({
      pull(controller) { controller.enqueue(new Uint8Array(chunk++ === 0 ? MAX_TEXT_BYTES : 1)) },
      cancel,
    })
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { headers })))
    expect((await worker.fetch(request(), { GOOGLE_BOOKS_KEY: 'private-test-key' })).status).toBe(502)
    expect(cancel).toHaveBeenCalledOnce()
  })

  it('accepts the exact byte boundary and decodes split UTF-8 characters', async () => {
    const text = 'ї'.repeat(MAX_TEXT_BYTES / 2)
    const encoded = new TextEncoder().encode(text)
    const body = new ReadableStream<Uint8Array>({ start(controller) {
      controller.enqueue(encoded.subarray(0, 1))
      controller.enqueue(encoded.subarray(1))
      controller.close()
    } })
    expect(await readBoundedText(new Response(body), signal())).toBe(text)
  })

  it('ends a stalled body and cancels its stream at the deadline', async () => {
    vi.useFakeTimers()
    const cancel = vi.fn()
    const body = new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new Uint8Array([65])) }, cancel })
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body)))
    const pending = worker.fetch(catalogRequest(), {})
    await vi.advanceTimersByTimeAsync(18_001)
    expect((await pending).status).toBe(502)
    expect(cancel).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ends stalled headers without relying on the peer to acknowledge cancellation', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn((_target: string, _options: RequestInit) => new Promise<Response>(() => {}))
    vi.stubGlobal('fetch', fetchMock)
    const pending = worker.fetch(request(), { GOOGLE_BOOKS_KEY: 'private-test-key' })
    await vi.advanceTimersByTimeAsync(18_001)
    const response = await pending
    expect(response.status).toBe(502)
    expect(await response.text()).not.toContain('private-test-key')
    expect(fetchMock.mock.calls[0][1].signal?.aborted).toBe(true)
  })

  it('shares the deadline across redirects and releases redirect bodies', async () => {
    vi.useFakeTimers()
    const cancel = vi.fn()
    const redirected = new Response(new ReadableStream({ cancel }), { status: 302, headers: { location: '/catalog' } })
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => setTimeout(() => resolve(redirected), 9_000)))
      .mockImplementationOnce(() => new Promise<Response>(() => {}))
    vi.stubGlobal('fetch', fetchMock)
    const pending = worker.fetch(catalogRequest(), {})
    await vi.advanceTimersByTimeAsync(18_001)
    expect((await pending).status).toBe(502)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(cancel).toHaveBeenCalledOnce()
    expect(fetchMock.mock.calls[1][1].signal.aborted).toBe(true)
  })
})
