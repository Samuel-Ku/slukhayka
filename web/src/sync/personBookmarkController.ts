import type { DomainStore, PersonBookmarkEntity, PersonRole } from '../local/domain'
import type { PersonBookmarkSyncStore } from './personBookmarkStore'
import {
  pendingDeleteKey,
  remoteWins,
  shouldPush,
  toDomainBookmark,
  wireKindOf,
  type RemotePersonBookmark,
} from './personBookmarkSync'

/**
 * #582 W0.4 — the web orchestrator of person-bookmark sync, sequenced
 * exactly like Android's `PersonBookmarksSyncController` (#404) and gated
 * exactly like the web's `WorkRelationshipController` (same gates, same
 * degrade-never):
 *
 *  - `toggle` — the UI's one action: add → local write + upload;
 *    remove → the pending-delete marker is persisted FIRST (an offline
 *    removal must not be resurrected by a later pull), then the remote
 *    doc is deleted and the marker dropped on success.
 *  - `sync` — pull every cloud row, apply the strictly-newer ones through
 *    the domain's LWW projection, push local rows whose clock is ahead,
 *    and flush queued removals. Pull never infers deletion.
 *  - `mergeAtLinking` — after a Recovery-Code restore, pre-link local
 *    rows upload (the account has no doc for them) and the account's
 *    rows apply through the same LWW rule.
 *
 * A null store (no Firebase config) or an unbound (`local-…`) profile
 * makes every entry point a no-op — and refusing sync NEVER deletes local
 * bookmarks: `remove` touches only the cloud row.
 */
export class PersonBookmarkSyncController {
  private uidOverride: string | null = null

  constructor(
    private readonly getUid: () => string | null,
    private readonly domain: DomainStore,
    private readonly store: PersonBookmarkSyncStore | null,
    private readonly isEnabled: () => boolean,
    private readonly pendingDeletes: PendingPersonBookmarkDeletes,
  ) {}

  /** After a Recovery-Code restore the binding is newer than the injected getter's view. */
  setUid(uid: string | null): void {
    this.uidOverride = uid
  }

  private boundUid(): string | null {
    const uid = this.uidOverride ?? this.getUid()
    if (uid === null || uid.startsWith('local-')) return null
    return uid
  }

  private gate(): string | null {
    if (!this.isEnabled()) return null
    if (this.store === null) return null
    return this.boundUid()
  }

  /** The UI's ONE toggle: bookmark on, bookmark off (Android's toggle). */
  async toggle(role: PersonRole, displayName: string): Promise<boolean> {
    const { personIdentityOf } = await import('../local/personIdentity')
    const identity = personIdentityOf(role, displayName)
    const existing = await this.domain.personBookmarkOf(identity.id)
    if (existing !== null) {
      await this.remove(identity.id)
      return false
    }
    await this.domain.addPersonBookmark({ role, personId: identity.id, displayName: identity.displayName })
    const bookmark = await this.domain.personBookmarkOf(identity.id)
    if (bookmark !== null) await this.push(bookmark)
    return true
  }

  /** Local-first removal: the marker persists BEFORE the network call. */
  async remove(personId: string): Promise<void> {
    const uid = this.gate()
    const bookmark = await this.domain.personBookmarkOf(personId)
    await this.domain.removePersonBookmark(personId)
    if (uid === null || this.store === null || bookmark === null) return
    const kind = wireKindOf(bookmark.role)
    this.pendingDeletes.add(kind, personId)
    await this.flushPendingDelete(uid, kind, personId)
  }

  private async push(bookmark: PersonBookmarkEntity): Promise<void> {
    const uid = this.gate()
    if (uid === null || this.store === null) return
    if (!this.isEnabled()) return
    await this.store.push(uid, bookmark).catch(() => null)
  }

  /** The full sync pass — Android's sequence, gated. */
  async sync(): Promise<void> {
    const uid = this.gate()
    if (uid === null || this.store === null) return
    await this.runPass(uid)
  }

  /**
   * The linking moment (Recovery-Code restore): the SAME Android pass over
   * the freshly-bound account — pre-link local rows upload when the account
   * has no doc for them (or an older one), and every account row applies
   * through the LWW rule, including rows the browser never touched.
   */
  async mergeAtLinking(): Promise<{ uploaded: number; applied: number }> {
    const uid = this.boundUid()
    if (uid === null || this.store === null) return { uploaded: 0, applied: 0 }
    return this.runPass(uid)
  }

  /** Android's PersonBookmarksSyncController.sync() — one honest pass. */
  private async runPass(uid: string): Promise<{ uploaded: number; applied: number }> {
    const store = this.store
    if (store === null) return { uploaded: 0, applied: 0 }
    // 1. Flush queued removals first: a deleted doc must not re-pull.
    for (const [kind, personId] of this.pendingDeletes.keys()) {
      await this.flushPendingDelete(uid, kind, personId)
    }
    let applied = 0
    let uploaded = 0
    if (!this.isEnabled()) return { uploaded, applied }
    // 2. Pull, filter pending deletes, apply strictly-newer remote rows.
    const remoteRows = await store.pullAll(uid).catch(() => [] as RemotePersonBookmark[])
    if (!this.isEnabled()) return { uploaded, applied }
    const deletedKeys = new Set(this.pendingDeletes.keys().map(([kind, personId]) => pendingDeleteKey(kind, personId)))
    const remoteByKey = new Map<string, RemotePersonBookmark>()
    for (const row of remoteRows) {
      if (deletedKeys.has(pendingDeleteKey(row.kind, row.personId))) continue
      remoteByKey.set(pendingDeleteKey(row.kind, row.personId), row)
      const local = await this.domain.personBookmarkOf(row.personId)
      if (remoteWins(local, row)) {
        await this.domain.applyRemotePersonBookmark(toDomainBookmark(row))
        applied++
      }
    }
    // 3. Local rows whose clock is ahead push (the phone's newer row won).
    for (const bookmark of await this.domain.personBookmarks()) {
      if (!this.isEnabled()) break
      const remote = remoteByKey.get(pendingDeleteKey(wireKindOf(bookmark.role), bookmark.personId)) ?? null
      if (shouldPush(bookmark, remote)) {
        await this.push(bookmark)
        uploaded++
      }
    }
    return { uploaded, applied }
  }

  private async flushPendingDelete(uid: string, kind: string, personId: string): Promise<void> {
    if (this.store === null) return
    const ok = await this.store.remove(uid, kind, personId).catch(() => false)
    if (ok) this.pendingDeletes.remove(kind, personId)
  }
}

/** Android's PendingPersonBookmarkDeletes — persist before the network call. */
export interface PendingPersonBookmarkDeletes {
  keys(): Array<[string, string]>
  add(kind: string, personId: string): void
  remove(kind: string, personId: string): void
}

/** localStorage-backed marker list, same shape as Android's SharedPreferences impl. */
export class LocalPendingPersonBookmarkDeletes implements PendingPersonBookmarkDeletes {
  private static readonly KEY = 'slukhayka.pending_person_bookmark_deletes'
  private cache: Array<[string, string]> | null = null

  constructor(private readonly storage: { getItem(key: string): string | null; setItem(key: string, value: string): void }) {}

  private load(): Array<[string, string]> {
    if (this.cache !== null) return this.cache
    try {
      const raw = this.storage.getItem(LocalPendingPersonBookmarkDeletes.KEY)
      const parsed = raw === null ? [] : (JSON.parse(raw) as unknown)
      this.cache = Array.isArray(parsed)
        ? parsed.filter((entry): entry is [string, string] =>
            Array.isArray(entry) && entry.length === 2 && typeof entry[0] === 'string' && typeof entry[1] === 'string')
        : []
    } catch {
      this.cache = []
    }
    return this.cache
  }

  private persist(): void {
    try {
      this.storage.setItem(LocalPendingPersonBookmarkDeletes.KEY, JSON.stringify(this.cache ?? []))
    } catch {
      // degrade-never: the in-memory list still applies this session
    }
  }

  keys(): Array<[string, string]> {
    return [...this.load()]
  }

  add(kind: string, personId: string): void {
    const list = this.load()
    if (!list.some(([k, id]) => k === kind && id === personId)) list.push([kind, personId])
    this.persist()
  }

  remove(kind: string, personId: string): void {
    this.cache = this.load().filter(([k, id]) => !(k === kind && id === personId))
    this.persist()
  }
}