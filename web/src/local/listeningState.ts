/**
 * #580 W0.2 — Listening State lives in IndexedDB (R-W8).
 *
 * localStorage survives neither iOS Safari's script-writable-storage cap
 * (script-writable storage of sites unused for 7 days of Safari use is
 * deleted) nor being a 5 MB cookie jar; IndexedDB under the WebKit storage
 * policy is quota-managed, evictable-but-LRU, and — crucially — can request
 * persistent mode, which a Home Screen Web App is granted heuristically.
 *
 * Migration is deliberate and loss-free (the acceptance criterion): on the
 * first open, every valid legacy `slukhayka.listening.*` localStorage row
 * is copied into IDB, written through a SINGLE async wrapper, confirmed
 * read-back in the same tick, and only then removed — a crash between the
 * two steps re-runs the migration harmlessly. The legacy key is never
 * deleted before its IDB twin is verified.
 *
 * Eviction honesty: a `lastSeen` heartbeat stamps every boot; on boot we
 * compare it with wall-clock time — a long absence with an EMPTY store and
 * an EXISTING heartbeat means the browser evicted us (R-W8's honest
 * message, never «порожня медіатека»). The heartbeat itself rides
 * localStorage's degrade-never semantics: its loss is indistinguishable
 * from «never ran», which is the correct default.
 *
 * The engine keeps its synchronous `StorageLike` shape (the spec's «access
 * through the existing StorageLike-like interfaces»); this module owns the
 * async IDB↔memory bridge that feeds it.
 */
import { openListenerDatabase, type IdbDatabase } from './idb'
import { LISTENER_STORES, LISTENING_STATE_STORE } from './schema'
import { parseSnapshotValue, type LocalListeningStateSnapshot } from '../player/localState'

const STORE = LISTENING_STATE_STORE
const HEARTBEAT_KEY = 'slukhayka.idb.last_seen'
const MIGRATED_KEY = 'slukhayka.listening.migrated_to_idb'

/** Suspiciously quiet for this long with an empty store = likely eviction. */
export const EVICTION_SUSPECT_MS = 7 * 24 * 60 * 60 * 1_000

export interface BootOutcome {
  /** Hydrated rows for the synchronous engine store. */
  snapshots: LocalListeningStateSnapshot[]
  /**
   * `true` when the evidence says the browser wiped previously stored
   * listener data (long absence + existing heartbeat + empty store).
   */
  evicted: boolean
}

export interface ListenerDatabase {
  loadSnapshot(editionId: string): Promise<LocalListeningStateSnapshot | null>
  saveSnapshot(snapshot: LocalListeningStateSnapshot): Promise<void>
  clearSnapshot(editionId: string): Promise<void>
  /** Every valid snapshot, for hydration. */
  allSnapshots(): Promise<LocalListeningStateSnapshot[]>
}

export function listeningStateRecordKey(editionId: string): string {
  return editionId
}

export class IdbListeningStateStore implements ListenerDatabase {
  private db: IdbDatabase | null = null
  private opening: Promise<IdbDatabase | null> | null = null

  constructor(
    private readonly open: () => Promise<IdbDatabase | null> = () =>
      openListenerDatabase(1, LISTENER_STORES),
    private readonly now: () => number = () => Date.now(),
  ) {}

  private async ready(): Promise<IdbDatabase | null> {
    if (this.db !== null) return this.db
    if (this.opening === null) this.opening = this.open()
    const db = await this.opening
    if (db !== null) this.db = db
    return this.db
  }

  async loadSnapshot(editionId: string): Promise<LocalListeningStateSnapshot | null> {
    const db = await this.ready()
    if (db === null) return null
    const record = await db.get<{ editionId: string; updatedAt: number; snapshot: LocalListeningStateSnapshot }>(STORE, editionId)
    return record === null ? null : parseSnapshotValue(record.snapshot)
  }

  async saveSnapshot(snapshot: LocalListeningStateSnapshot): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    await db.put(STORE, { editionId: snapshot.editionId, updatedAt: this.now(), snapshot })
  }

  async clearSnapshot(editionId: string): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    await db.delete(STORE, editionId)
  }

  /** Every valid snapshot, for the engine's hydration. */
  async allSnapshots(): Promise<LocalListeningStateSnapshot[]> {
    const db = await this.ready()
    if (db === null) return []
    const records = await db.getAll<{ editionId: string; updatedAt: number; snapshot: LocalListeningStateSnapshot }>(STORE)
    return records
      .map((record) => parseSnapshotValue(record.snapshot))
      .filter((snapshot): snapshot is LocalListeningStateSnapshot => snapshot !== null)
  }
}

export interface LegacySource {
  /** One legacy row's storage key (so it can be removed after confirmation). */
  key: string
  value: string
}

/** Collects valid legacy rows from a Storage-like with index access (real Storage, test doubles). */
export function collectLegacyListeningRows(storage: {
  getItem(key: string): string | null
  removeItem(key: string): void
  length?: number
  key?(index: number): string | null
}, prefix = 'slukhayka.listening.'): LegacySource[] {
  const rows: LegacySource[] = []
  const length = typeof storage.length === 'number' ? storage.length : 0
  if (typeof storage.key !== 'function') return rows
  for (let i = 0; i < length; i += 1) {
    const key = storage.key(i)
    if (key !== null && key.startsWith(prefix)) {
      const value = storage.getItem(key)
      if (value !== null) rows.push({ key, value })
    }
  }
  return rows
}

export class ListeningStateMigrator {
  constructor(
    private readonly store: ListenerDatabase,
    private readonly storage: {
      getItem(key: string): string | null
      setItem(key: string, value: string): void
      removeItem(key: string): void
    },
  ) {}

  /**
   * Copies every legacy row into IDB, confirms it read-back, and only then
   * removes the row; a crash between the steps re-runs harmlessly. Returns
   * the migrated snapshot count.
   */
  async migrate(): Promise<number> {
    if (this.storage.getItem(MIGRATED_KEY) === '1') return 0
    let migrated = 0
    let leftover = false
    for (const row of collectLegacyListeningRows(this.storage)) {
      let parsed: unknown
      try {
        parsed = JSON.parse(row.value)
      } catch {
        this.storage.removeItem(row.key)
        continue
      }
      const snapshot = parseSnapshotValue(parsed)
      if (snapshot === null) {
        this.storage.removeItem(row.key)
        continue
      }
      await this.store.saveSnapshot(snapshot)
      const confirmed = await this.store.loadSnapshot(snapshot.editionId)
      if (confirmed !== null && confirmed.positionSeconds === snapshot.positionSeconds) {
        this.storage.removeItem(row.key)
        migrated += 1
      } else {
        // Unconfirmed: keep the legacy row and retry on the next boot.
        leftover = true
      }
    }
    // The done-marker is set only when nothing was left unconfirmed —
    // otherwise the next boot must retry, not skip.
    if (!leftover) this.storage.setItem(MIGRATED_KEY, '1')
    return migrated
  }
}

/**
 * WebKit's heuristic grant of persistent storage for a Home Screen Web App
 * is not a promise — we ask. Refusal (or a missing API) changes nothing:
 * eviction detection below remains the honest fallback.
 */
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    if (typeof navigator === 'undefined' || navigator.storage?.persist === undefined) return false
    return await navigator.storage.persist()
  } catch {
    return false
  }
}

/** The engine's synchronous facade is hydrated from this boot sequence. */
export async function bootListenerStorage(deps: {
  store: ListenerDatabase
  storage: {
    getItem(key: string): string | null
    setItem(key: string, value: string): void
    removeItem(key: string): void
  }
  now?: () => number
}): Promise<BootOutcome> {
  const now = deps.now ?? (() => Date.now())
  await new ListeningStateMigrator(deps.store, deps.storage).migrate()

  const lastSeenRaw = deps.storage.getItem(HEARTBEAT_KEY)
  const lastSeen = lastSeenRaw === null ? null : Number(lastSeenRaw)
  const snapshots = await deps.store.allSnapshots()

  const evicted =
    lastSeen !== null &&
    Number.isFinite(lastSeen) &&
    now() - lastSeen > EVICTION_SUSPECT_MS &&
    snapshots.length === 0

  deps.storage.setItem(HEARTBEAT_KEY, String(now()))
  return { snapshots, evicted }
}
