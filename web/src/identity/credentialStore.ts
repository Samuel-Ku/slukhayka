import type { Credentials } from './credentials'

/**
 * spec-43/T2 — where the silent profile's credential pair lives between
 * visits. A plain contract so tests can inject an in-memory fake; the
 * browser implementation rides localStorage.
 */
export interface CredentialStore {
  load(): Credentials | null
  save(credentials: Credentials): void
}

export class BrowserCredentialStore implements CredentialStore {
  constructor(private readonly storage: Storage) {}

  load(): Credentials | null {
    try {
      const raw = this.storage.getItem('slukhayka.identity.credentials')
      if (raw === null) return null
      const parsed: unknown = JSON.parse(raw)
      if (
        typeof parsed !== 'object' ||
        parsed === null ||
        typeof (parsed as Credentials).email !== 'string' ||
        typeof (parsed as Credentials).password !== 'string'
      ) {
        return null
      }
      return parsed as Credentials
    } catch {
      return null
    }
  }

  save(credentials: Credentials): void {
    try {
      this.storage.setItem('slukhayka.identity.credentials', JSON.stringify(credentials))
    } catch {
      // degrade-never: private mode may refuse writes; the session keeps working
    }
  }
}
