import { describe, expect, it } from 'vitest'
import { BrowserCredentialStore } from './credentialStore'
import { generateCredentials } from './credentials'

class FakeStorage implements Storage {
  private map = new Map<string, string>()
  readonly length = 0
  clear(): void {}
  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }
  key(_index: number): string | null {
    return null
  }
  removeItem(_key: string): void {}
  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }
}

describe('BrowserCredentialStore', () => {
  it('round-trips a stored pair', () => {
    const store = new BrowserCredentialStore(new FakeStorage())
    const pair = generateCredentials(() => 0.5)
    store.save(pair)
    expect(store.load()).toEqual(pair)
  })

  it('returns null before anything is stored', () => {
    const store = new BrowserCredentialStore(new FakeStorage())
    expect(store.load()).toBeNull()
  })

  it('treats corrupted JSON as absence, never a crash', () => {
    const storage = new FakeStorage()
    storage.setItem('slukhayka.identity.credentials', '{not json')
    expect(new BrowserCredentialStore(storage).load()).toBeNull()
  })

  it('treats a non-object payload as absence', () => {
    const storage = new FakeStorage()
    storage.setItem('slukhayka.identity.credentials', '"just a string"')
    expect(new BrowserCredentialStore(storage).load()).toBeNull()
  })
})
