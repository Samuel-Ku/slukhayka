import { afterEach, describe, expect, it, vi } from 'vitest'
import worker from '../index'
import { mayFetch, sourceEntry } from '../registry'

const entry = sourceEntry('sound-books')!
const audioRequest = (url: string) => new Request(`https://transport.example/api/audio?u=${encodeURIComponent(url)}`)
afterEach(() => vi.unstubAllGlobals())

describe('worker security boundaries', () => {
  it.each(['http://sound-books.net/a', 'ftp://sound-books.net/a', 'https://user:pass@sound-books.net/a',
    'https://sound-books.net:8080/a', 'https://sound-books.net.evil.example/a', 'https://127.0.0.1/a'])
  ('rejects unsafe destination %s before fetching', async (url) => {
    expect(mayFetch(entry, url)).toBe(false)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    expect((await worker.fetch(audioRequest(url), {})).status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each(['__proto__', 'constructor', 'toString'])('does not dispatch inherited registry key %s', (id) => {
    expect(sourceEntry(id)).toBeNull()
  })

  it.each(['https://127.0.0.1/private', 'https://archive.org/other-source', 'http://sound-books.net/a'])
  ('never follows a redirect to %s', async (location) => {
    const fetchMock = vi.fn(async (_url: string, _options?: RequestInit) => new Response(null, { status: 302, headers: { location } }))
    vi.stubGlobal('fetch', fetchMock)
    expect((await worker.fetch(audioRequest('https://sound-books.net/a'), {})).status).toBe(403)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ redirect: 'manual' })
  })

  it('bounds redirect loops', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 302, headers: { location: '/loop' } }))
    vi.stubGlobal('fetch', fetchMock)
    expect((await worker.fetch(audioRequest('https://sound-books.net/a'), {})).status).toBe(502)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('preserves encoded URLs, byte ranges and same-source redirects', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: 'https://arch.sound-books.net/a%2Fb.mp3?sig=x%25y' } }))
      .mockResolvedValueOnce(new Response('abc', { status: 206, headers: { 'content-type': 'audio/mpeg', 'content-range': 'bytes 0-2/9' } }))
    vi.stubGlobal('fetch', fetchMock)
    const req = audioRequest('https://sound-books.net/a%2Fb.mp3?sig=x%25y')
    req.headers.set('range', 'bytes=0-2')
    const response = await worker.fetch(req, {})
    expect(response.status).toBe(206)
    expect(await response.text()).toBe('abc')
    expect(response.headers.get('content-range')).toBe('bytes 0-2/9')
    expect(response.headers.get('content-type')).toBe('audio/mpeg')
    expect(fetchMock.mock.calls[0][0]).toBe('https://sound-books.net/a%2Fb.mp3?sig=x%25y')
    expect(fetchMock.mock.calls[1][1].headers.range).toBe('bytes=0-2')
  })

  it.each(['text/html', 'image/svg+xml', 'application/javascript', ''])
  ('isolates active or unknown upstream MIME %s', async (mime) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>untrusted</html>', { headers: { 'content-type': mime } })))
    const response = await worker.fetch(audioRequest('https://sound-books.net/page'), {})
    expect(response.headers.get('content-type')).toBe('application/octet-stream')
    expect(response.headers.get('x-content-type-options')).toBe('nosniff')
    expect(response.headers.get('content-security-policy')).toContain('sandbox')
    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('rejects unsupported methods before network access', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    expect((await worker.fetch(new Request('https://transport.example/api/search-all?q=test', { method: 'POST' }), {})).status).toBe(405)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
