/**
 * #586 W2.2 — the local Recommendation Preference, the web port of
 * Android's `RecommendationPreferenceEntity` verbatim: an explicit,
 * local-only listener verdict that changes discovery ranking without
 * changing a Work, Library Entry, Listening State or Listener Review
 * (CONTEXT.md). One of `HIDE_WORK`, `REDUCE_SIMILAR` or `HIDE_AUTHOR`,
 * keyed by normalized Work/author identity (the mergeKey on web), and
 * reversible from Налаштування → Рекомендації.
 *
 * A preference, not an identity fact — deliberately NEVER synced and
 * stored in IndexedDB (R-W8) like every other listener-owned row. No
 * server profile: nothing leaves the browser.
 */
import { openListenerDatabase, type IdbDatabase } from './idb'
import { LISTENER_DB_VERSION, LISTENER_STORES, RECOMMENDATION_PREFS_STORE } from './schema'

/** Android's dictionary verbatim — never renamed. */
export const RECOMMENDATION_KINDS = ['HIDE_WORK', 'REDUCE_SIMILAR', 'HIDE_AUTHOR'] as const
export type RecommendationKind = (typeof RECOMMENDATION_KINDS)[number]

/** One preference row; the PK is `${kind}:${targetKey}` (Android's kind+targetKey). */
export interface RecommendationPreferenceRow {
  id: string
  kind: RecommendationKind
  /** The normalized identity the verdict applies to (the mergeKey on web). */
  targetKey: string
  /** The Work the feedback came from (== targetKey for HIDE_WORK/REDUCE_SIMILAR). */
  sourceWorkId: string
  createdAt: number
}

function openPrefsDatabase(): Promise<IdbDatabase | null> {
  return openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
}

/** The one seam for Recommendation Preferences; degrade-never everywhere. */
export class RecommendationPrefsStore {
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

  async all(): Promise<RecommendationPreferenceRow[]> {
    const db = await this.ready()
    if (db === null) return []
    const rows = await db.getAll<RecommendationPreferenceRow>(RECOMMENDATION_PREFS_STORE)
    return rows
      .filter((row) => RECOMMENDATION_KINDS.includes(row.kind) && typeof row.targetKey === 'string' && row.targetKey !== '')
      .sort((a, b) => b.createdAt - a.createdAt)
  }

  /** Writes (or overwrites) the preference at kind+targetKey — Android's upsert. */
  async add(kind: RecommendationKind, targetKey: string, sourceWorkId: string): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    const id = `${kind}:${targetKey}`
    await db.put<RecommendationPreferenceRow>(RECOMMENDATION_PREFS_STORE, {
      id,
      kind,
      targetKey,
      sourceWorkId,
      createdAt: Date.now(),
    })
  }

  /** Removes one preference; a missing row is a no-op (Android's delete). */
  async remove(kind: RecommendationKind, targetKey: string): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    await db.delete(RECOMMENDATION_PREFS_STORE, `${kind}:${targetKey}`)
  }

  /** «Не цікаво» — the HIDE_WORK targets the Listen composer filters out. */
  async hideWorkTargets(): Promise<string[]> {
    const rows = await this.all()
    return rows.filter((row) => row.kind === 'HIDE_WORK').map((row) => row.targetKey)
  }
}