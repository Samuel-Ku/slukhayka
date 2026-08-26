import { describe, expect, it } from 'vitest'
import { readFirebaseConfig } from './config'

const full = {
  VITE_FIREBASE_API_KEY: 'key',
  VITE_FIREBASE_PROJECT_ID: 'proj',
  VITE_FIREBASE_APP_ID: 'app',
}

describe('web firebase config reader', () => {
  it('returns null when any required key is missing', () => {
    for (const drop of ['VITE_FIREBASE_API_KEY', 'VITE_FIREBASE_PROJECT_ID', 'VITE_FIREBASE_APP_ID']) {
      const env: Record<string, string | undefined> = { ...full }
      delete env[drop]
      expect(readFirebaseConfig(env)).toBeNull()
    }
    expect(readFirebaseConfig({})).toBeNull()
  })

  it('derives auth domain and bucket from the project id by default', () => {
    expect(readFirebaseConfig(full)).toEqual({
      apiKey: 'key',
      projectId: 'proj',
      appId: 'app',
      authDomain: 'proj.firebaseapp.com',
      storageBucket: 'proj.appspot.com',
    })
  })

  it('honours explicit overrides', () => {
    expect(
      readFirebaseConfig({ ...full, VITE_FIREBASE_AUTH_DOMAIN: 'sluhayka.example' })?.authDomain,
    ).toBe('sluhayka.example')
  })
})
