import { describe, expect, it } from 'vitest'
import { ensureProfile, currentProfile, type AuthGateway, type IdentityDeps } from './listenerIdentity'
import { generateCredentials } from './credentials'

function fakeGateway() {
  const calls: string[] = []
  const accounts = new Map<string, { email: string; password: string; uid: string }>()
  let signedInUid: string | null = null
  const gateway: AuthGateway & { forgetSession(): void } = {
    async signInWithPassword(email, password) {
      calls.push('signInWithPassword')
      const account = [...accounts.values()].find((a) => a.email === email && a.password === password)
      if (!account) throw new Error('user-not-found')
      signedInUid = account.uid
      return { uid: account.uid }
    },
    async createElevatedAccount(email, password) {
      calls.push('createElevatedAccount')
      const existing = [...accounts.values()].find((a) => a.email === email)
      if (existing) throw new Error('email-already-in-use')
      const uid = `uid-${accounts.size + 1}`
      accounts.set(uid, { email, password, uid })
      signedInUid = uid
      return { uid }
    },
    currentUser() {
      return signedInUid === null ? null : { uid: signedInUid }
    },
    /** Simulates the browser forgetting the session between visits. */
    forgetSession() {
      signedInUid = null
    },
  }
  return { gateway, calls }
}

function fakeDeps(overrides?: Partial<IdentityDeps>): { deps: IdentityDeps; store: Map<string, unknown> } {
  const store = new Map<string, unknown>()
  const deps: IdentityDeps = {
    gateway: null,
    readPair: () => (store.get('pair') as never) ?? null,
    persistPair: (pair) => void store.set('pair', pair),
    generatePair: () => generateCredentials(() => 0.25),
    randomToken: () => 'deadbeef1234',
    ...overrides,
  }
  return { deps, store }
}

describe('silent web identity bootstrap', () => {
  it('degrades to a local-… profile when no gateway is configured', async () => {
    const { deps } = fakeDeps({ gateway: null })
    const profile = await ensureProfile(deps)
    expect(profile.uid).toBe('local-deadbeef1234')
    expect(deps.readPair()).toBeNull() // nothing persisted without Firebase
  })

  it('first visit generates a pair and elevates the anonymous session', async () => {
    const { deps } = fakeDeps()
    const { gateway, calls } = fakeGateway()
    const profile = await ensureProfile({ ...deps, gateway })
    expect(calls).toEqual(['signInWithPassword', 'createElevatedAccount'])
    expect(profile.uid).toBe('uid-1')
    expect(profile.nickname).toMatch(/^Слухач-\d{4}$/)
    expect(deps.readPair()).not.toBeNull()
  })

  it('is idempotent: the second visit signs back into the same uid', async () => {
    const { gateway, calls } = fakeGateway()
    const { deps, store } = fakeDeps()
    const created = await ensureProfile({ ...deps, gateway })
    expect(created.uid).toBe('uid-1')
    const pairAtFirst = store.get('pair')

    // Same Firebase project and same stored pair; the browsing context
    // forgot the session (fresh page load).
    gateway.forgetSession()
    calls.length = 0
    const restored = await ensureProfile({ ...deps, gateway })
    expect(calls).toEqual(['signInWithPassword'])
    expect(restored.uid).toBe('uid-1')
    expect(store.get('pair')).toBe(pairAtFirst) // no re-generation
  })

  it('never throws outward when every auth path fails', async () => {
    const failing: AuthGateway = {
      signInWithPassword: () => Promise.reject(new Error('down')),
      createElevatedAccount: () => Promise.reject(new Error('down')),
      currentUser: () => null,
    }
    const { deps } = fakeDeps()
    const profile = await ensureProfile({ ...deps, gateway: failing })
    expect(profile.uid).toBe('local-deadbeef1234')
  })

  it('currentProfile reads without side effects', () => {
    const { gateway, calls } = fakeGateway()
    expect(currentProfile(gateway)).toBeNull()
    expect(calls).toEqual([])
  })
})
