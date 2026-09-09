/**
 * #580 W0.2 — the web port of the Kotlin domain (CONTEXT.md): Work,
 * Library Entry, Tombstone, Person Bookmark — plus the ONE Work-relationship
 * projection the sync extension (W0.3) will mirror: a Work's relationship is
 * one row with state `entry | tombstone | none` (ADR-0034), LWW by server
 * time with ties going to the tombstone.
 *
 * Ground rules honoured here:
 * - The Work is bibliographic: identity is the mergeKey (title|author),
 *   narrator/language stay Edition-owned — no second Work concept.
 * - Removing a Library Entry does not erase the Work or its Editions.
 * - A Tombstone anchors at the Work mergeKey: one row blocks every Edition
 *   and Source of that Work from silently re-importing.
 * - Person Bookmarks key by (role, deterministic id) and stay useful
 *   offline, without network.
 * - These stores are the DOMAIN seam; the sync layer (W0.3) reads and
 *   writes the same rows through the relationship projection, and nothing
 *   here knows Firestore exists.
 */
import type { IdbDatabase } from './idb'
import { openListenerDatabase } from './idb'
import { LISTENER_DB_VERSION, LISTENER_STORES, WORKS_STORE, WORK_RELATIONSHIPS_STORE, PERSON_BOOKMARKS_STORE } from './schema'
import { mergeKeyFor } from '../sync/edition'

export type WorkRelationshipState = 'entry' | 'tombstone' | 'none'

export interface WorkEntity {
  mergeKey: string
  title: string
  author: string
}

export interface LibraryEntryEntity {
  mergeKey: string
  /** Display surface of the Work at entry time (denormalized, honest cache). */
  title: string
  author: string
  createdAt: number
}

export interface TombstoneEntity {
  mergeKey: string
  createdAt: number
}

export type PersonRole = 'author' | 'narrator'

export interface PersonBookmarkEntity {
  /** Deterministic id: Android's boundedId (role prefix + sha256, #582 W0.4). */
  personId: string
  role: PersonRole
  displayName: string
  /** Set once on first toggle; never changes after (Android's createdAt). */
  createdAt: number
  /** Refreshed on every toggle or sync — the LWW clock (Android's updatedAt). */
  updatedAt: number
  /** Android's per-person notification flag; the wire carries it, web has no notifications UI yet. */
  notifyEnabled: boolean
}

export interface WorkRelationshipRow {
  mergeKey: string
  state: WorkRelationshipState
  /** Server-stamped time of the last state change (sync layer fills it). */
  updatedAtServerMs: number
  /** Local write time, used by the merge to keep the freshest display data. */
  updatedAtLocalMs: number
  title: string
  author: string
}

const WORKS = WORKS_STORE
const RELATIONSHIPS = WORK_RELATIONSHIPS_STORE
const BOOKMARKS = PERSON_BOOKMARKS_STORE

/** The pure LWW rule with tombstone ties (ADR-0034) — one decision, tested once. */
export function resolveRelationship(local: WorkRelationshipRow, incoming: WorkRelationshipRow): WorkRelationshipRow {
  if (incoming.updatedAtServerMs > local.updatedAtServerMs) return incoming
  if (incoming.updatedAtServerMs < local.updatedAtServerMs) return local
  // Tie: a deliberate hide never loses to a stale favorite.
  if (local.state === 'tombstone' || incoming.state === 'tombstone') {
    return local.state === 'tombstone' ? local : incoming
  }
  return incoming.updatedAtLocalMs >= local.updatedAtLocalMs ? incoming : local
}

function openDomainDatabase(): Promise<IdbDatabase | null> {
  return openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
}

/** The one store seam for domain data; degrade-never everywhere. */
export class DomainStore {
  private db: IdbDatabase | null = null
  private opening: Promise<IdbDatabase | null> | null = null

  constructor(
    private readonly open: () => Promise<IdbDatabase | null> = openDomainDatabase,
    private readonly now: () => number = () => Date.now(),
  ) {}

  private async ready(): Promise<IdbDatabase | null> {
    if (this.db !== null) return this.db
    if (this.opening === null) this.opening = this.open()
    const db = await this.opening
    if (db !== null) this.db = db
    return this.db
  }

  async ensureWork(work: { title: string; author: string }): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    const mergeKey = mergeKeyFor(work.title, work.author)
    const existing = await db.get<WorkEntity>(WORKS, mergeKey)
    if (existing !== null) return
    await db.put<WorkEntity>(WORKS, { mergeKey, title: work.title, author: work.author })
  }

  /** Creates an entry (or resurrects a hidden Work through an explicit action). */
  async addLibraryEntry(work: { title: string; author: string }, serverMs?: number): Promise<WorkRelationshipRow> {
    const db = await this.ready()
    if (db === null) throw new Error('domain store unavailable')
    await this.ensureWork(work)
    const mergeKey = mergeKeyFor(work.title, work.author)
    const current = await this.relationshipOf(mergeKey)
    const next: WorkRelationshipRow = {
      mergeKey,
      state: 'entry',
      updatedAtServerMs: serverMs ?? 0,
      updatedAtLocalMs: this.now(),
      title: work.title,
      author: work.author,
    }
    const row = current === null ? next : resolveRelationship(current, next)
    await db.put(RELATIONSHIPS, row)
    return row
  }

  /** Hides the Work everywhere: tombstone at the mergeKey. */
  async tombstoneWork(mergeKey: string, serverMs?: number): Promise<WorkRelationshipRow> {
    const db = await this.ready()
    if (db === null) throw new Error('domain store unavailable')
    const current = await this.relationshipOf(mergeKey)
    const next: WorkRelationshipRow = {
      mergeKey,
      state: 'tombstone',
      updatedAtServerMs: serverMs ?? 0,
      updatedAtLocalMs: this.now(),
      title: current?.title ?? '',
      author: current?.author ?? '',
    }
    const row = current === null ? next : resolveRelationship(current, next)
    await db.put(RELATIONSHIPS, row)
    return row
  }

  async relationshipOf(mergeKey: string): Promise<WorkRelationshipRow | null> {
    const db = await this.ready()
    if (db === null) return null
    return db.get<WorkRelationshipRow>(RELATIONSHIPS, mergeKey)
  }

  /** Every relationship row (any state) — the linking merge's local read side (#581). */
  async allRelationships(): Promise<WorkRelationshipRow[]> {
    const db = await this.ready()
    if (db === null) return []
    return db.getAll<WorkRelationshipRow>(RELATIONSHIPS)
  }

  /** Every active Library Entry (state = entry), for the future Медіатека. */
  async libraryEntries(): Promise<LibraryEntryEntity[]> {
    const db = await this.ready()
    if (db === null) return []
    const rows = await db.getAll<WorkRelationshipRow>(RELATIONSHIPS)
    return rows
      .filter((row) => row.state === 'entry')
      .map((row) => ({ mergeKey: row.mergeKey, title: row.title, author: row.author, createdAt: row.updatedAtLocalMs }))
  }

  /** Every tombstoned mergeKey — the sync layer's read model and Огляд's filter. */
  async tombstones(): Promise<TombstoneEntity[]> {
    const db = await this.ready()
    if (db === null) return []
    const rows = await db.getAll<WorkRelationshipRow>(RELATIONSHIPS)
    return rows
      .filter((row) => row.state === 'tombstone')
      .map((row) => ({ mergeKey: row.mergeKey, createdAt: row.updatedAtLocalMs }))
  }

  /** Applies a remote sync row through the shared LWW rule. */
  async applyRelationship(remote: WorkRelationshipRow): Promise<WorkRelationshipRow> {
    const db = await this.ready()
    if (db === null) throw new Error('domain store unavailable')
    const current = await this.relationshipOf(remote.mergeKey)
    const row = current === null ? remote : resolveRelationship(current, remote)
    await db.put(RELATIONSHIPS, row)
    return row
  }

  async addPersonBookmark(person: { role: PersonRole; personId: string; displayName: string }): Promise<PersonBookmarkEntity> {
    const db = await this.ready()
    if (db === null) throw new Error('domain store unavailable')
    const existing = await this.personBookmarkOf(person.personId)
    const now = this.now()
    const entity: PersonBookmarkEntity = {
      ...person,
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
      notifyEnabled: existing?.notifyEnabled ?? true,
    }
    await db.put(BOOKMARKS, entity)
    return entity
  }

  /** One bookmark by its deterministic id; null when absent. */
  async personBookmarkOf(personId: string): Promise<PersonBookmarkEntity | null> {
    const db = await this.ready()
    if (db === null) return null
    const row = await db.get<PersonBookmarkEntity>(BOOKMARKS, personId)
    return row ?? null
  }

  /** Applies a remote row through the LWW rule (Android's upsertRemote). */
  async applyRemotePersonBookmark(remote: PersonBookmarkEntity): Promise<PersonBookmarkEntity> {
    const db = await this.ready()
    if (db === null) throw new Error('domain store unavailable')
    const existing = await this.personBookmarkOf(remote.personId)
    // Android: the remote wins only when STRICTLY newer; a tie keeps local.
    if (existing !== null && existing.updatedAt >= remote.updatedAt) return existing
    await db.put(BOOKMARKS, { ...remote, createdAt: existing?.createdAt ?? remote.createdAt })
    return remote
  }

  async removePersonBookmark(personId: string): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    await db.delete(BOOKMARKS, personId)
  }

  async personBookmarks(role?: PersonRole): Promise<PersonBookmarkEntity[]> {
    const db = await this.ready()
    if (db === null) return []
    const all = await db.getAll<PersonBookmarkEntity>(BOOKMARKS)
    return role === undefined ? all : all.filter((bookmark) => bookmark.role === role)
  }
}
