/**
 * W6.2 — the caching policy, pinned: only full relay responses are cached,
 * Range asks are answered offline with a synthesized 206, and the cache
 * stays bounded by the «recently listened» cap.
 */
import { describe, expect, it } from 'vitest'
import {
  AUDIO_CACHE_MAX_ENTRIES,
  buildRangeResponse,
  evictToMax,
  isRelayAudioUrl,
  parseRange,
  relayTargetOf,
  shouldCacheResponse,
} from './policy'

const RELAY = 'https://app.example/audio?u=https%3A%2F%2F4read.org%2Fuploads%2Faudio%2F1.mp3'
const DIRECT = 'https://4read.org/uploads/audio/1.mp3'

describe('isRelayAudioUrl', () => {
  it('recognizes the relay route in dev (/api/audio) and prod (/audio)', () => {
    expect(isRelayAudioUrl('https://app.example/api/audio?u=x')).toBe(true)
    expect(isRelayAudioUrl('https://app.example/audio?u=x')).toBe(true)
    expect(isRelayAudioUrl(DIRECT)).toBe(false)
    expect(isRelayAudioUrl('https://app.example/index.html')).toBe(false)
  })
})

describe('relayTargetOf', () => {
  it('extracts the direct stream URL behind the relay', () => {
    expect(relayTargetOf(RELAY)).toBe(DIRECT)
    expect(relayTargetOf(DIRECT)).toBeNull()
  })
})

describe('shouldCacheResponse', () => {
  it('caches only full 200 relay responses', () => {
    expect(shouldCacheResponse(RELAY, 200, null)).toBe(true)
    expect(shouldCacheResponse(RELAY, 206, null)).toBe(false)
    expect(shouldCacheResponse(RELAY, 200, 'bytes=0-')).toBe(false)
    expect(shouldCacheResponse(DIRECT, 200, null)).toBe(false)
  })
})

describe('parseRange', () => {
  it('parses open and closed byte ranges', () => {
    expect(parseRange('bytes=0-', 1000)).toEqual({ start: 0, end: 999 })
    expect(parseRange('bytes=100-199', 1000)).toEqual({ start: 100, end: 199 })
    expect(parseRange('bytes=100-', 1000)).toEqual({ start: 100, end: 999 })
  })

  it('parses suffix ranges as the last N bytes', () => {
    expect(parseRange('bytes=-500', 1000)).toEqual({ start: 500, end: 999 })
  })

  it('rejects malformed or unsatisfiable ranges honestly', () => {
    expect(parseRange('bytes=abc', 1000)).toBeNull()
    expect(parseRange('bytes=2000-', 1000)).toBeNull()
    expect(parseRange('bytes=100-50', 1000)).toBeNull()
    expect(parseRange('bytes=0-0', 0)).toBeNull()
  })
})

describe('buildRangeResponse', () => {
  it('synthesizes a correct 206 slice with Content-Range', () => {
    const body = new Uint8Array([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]).buffer
    const response = buildRangeResponse(body, 10, { start: 3, end: 6 }, 'audio/mpeg')
    expect(response.status).toBe(206)
    expect(response.headers.get('Content-Range')).toBe('bytes 3-6/10')
    expect(response.headers.get('Content-Length')).toBe('4')
    expect(response.headers.get('Content-Type')).toBe('audio/mpeg')
    void response.arrayBuffer().then((bytes) => {
      expect(new Uint8Array(bytes)).toEqual(new Uint8Array([3, 4, 5, 6]))
    })
  })
})

describe('evictToMax', () => {
  it('keeps the newest entries (keys arrive in insertion order)', () => {
    const keys = ['a', 'b', 'c', 'd', 'e']
    expect(evictToMax(keys, 3)).toEqual(['a', 'b'])
    expect(evictToMax(keys, AUDIO_CACHE_MAX_ENTRIES)).toEqual([])
    expect(evictToMax([], 3)).toEqual([])
  })
})