import { describe, expect, it } from 'vitest'
import { generateCredentials, isValidCredentialPair, type Credentials } from './credentials'

/** Deterministic stand-in for Math.random so tests pin exact outputs. */
function seeded(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 4294967296
  }
}

describe('generated .local credentials', () => {
  it('are deterministic for the same random source', () => {
    const a = generateCredentials(seeded(42))
    const b = generateCredentials(seeded(42))
    expect(a).toEqual(b)
  })

  it('use the synthetic slukhayka.local domain and bounded lengths', () => {
    for (const seed of [1, 7, 99, 123456]) {
      const pair = generateCredentials(seeded(seed))
      expect(pair.email.endsWith('@slukhayka.local')).toBe(true)
      expect(pair.email.length).toBe(12 + '@slukhayka.local'.length)
      expect(pair.password.length).toBe(24)
      expect(isValidCredentialPair(pair)).toBe(true)
    }
  })

  it('reject malformed pairs', () => {
    const cases: Array<[Credentials | null, string]> = [
      [null, 'null'],
      [{ email: 'user@gmail.com', password: 'long-enough-password' }, 'wrong domain'],
      [{ email: 'abcd@slukhayka.local', password: 'short' }, 'short password'],
      [{ email: 'ab@slukhayka.local', password: 'long-enough-password-12' }, 'email too short'],
    ]
    for (const [pair, name] of cases) {
      expect(isValidCredentialPair(pair), name).toBe(false)
    }
  })
})
