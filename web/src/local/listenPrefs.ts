/**
 * #585 W2.1 — local-only Listen preferences, the web port of Android's
 * `ListenPrefs` (wayfinder #62): the user's block order, hidden blocks and
 * dismissed works. A preference, not an identity fact — deliberately NEVER
 * synced, fully reversible from «Керувати полицями», and stored in
 * IndexedDB (R-W8) like every other listener-owned row.
 */
import { openListenerDatabase, type IdbDatabase } from './idb'
import { LISTENER_DB_VERSION, LISTENER_STORES, LISTEN_PREFS_STORE } from './schema'

/** The stable id of every Listen block — persisted, never renamed. */
export type ListenBlockId =
  | 'hero'
  | 'almost-done'
  | 'return'
  | 'next-in-series'
  | 'travel'
  | 'short'
  | 'favorite-authors'
  | 'recently-added'

export const LISTEN_BLOCK_IDS: readonly ListenBlockId[] = [
  'hero',
  'almost-done',
  'return',
  'next-in-series',
  'travel',
  'short',
  'favorite-authors',
  'recently-added',
]

export interface ListenPrefsRow {
  id: 'listen'
  /** The user's block order; empty = the default priority. */
  order: ListenBlockId[]
  /** Blocks the user hid; hidden blocks stay computed but unrendered. */
  hidden: ListenBlockId[]
  /** Works the user marked «Не цікаво» — filtered from every block (W2.2). */
  dismissed: string[]
}

const ROW_ID = 'listen'

const DEFAULT_ROW: ListenPrefsRow = { id: ROW_ID, order: [], hidden: [], dismissed: [] }

function openPrefsDatabase(): Promise<IdbDatabase | null> {
  return openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
}

/** The one seam for Listen preferences; degrade-never everywhere. */
export class ListenPrefsStore {
  private db: IdbDatabase | null = null
  private opening: Promise<IdbDatabase | null> | null = null

  constructor(
    private readonly open: () => Promise<IdbDatabase | null> = openPrefsDatabase,
  ) {}

  private async ready(): Promise<IdbDatabase | null> {
    if (this.db !== null) return this.db
    if (this.opening === null) this.opening = this.open()
    const db = await this.opening
    if (db !== null) this.db = db
    return this.db
  }

  async load(): Promise<ListenPrefsRow> {
    const db = await this.ready()
    if (db === null) return DEFAULT_ROW
    const row = await db.get<ListenPrefsRow>(LISTEN_PREFS_STORE, ROW_ID)
    if (row === null) return DEFAULT_ROW
    return {
      id: ROW_ID,
      order: Array.isArray(row.order) ? row.order : [],
      hidden: Array.isArray(row.hidden) ? row.hidden : [],
      dismissed: Array.isArray(row.dismissed) ? row.dismissed : [],
    }
  }

  async save(row: Omit<ListenPrefsRow, 'id'>): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    await db.put<ListenPrefsRow>(LISTEN_PREFS_STORE, { id: ROW_ID, ...row })
  }
}
