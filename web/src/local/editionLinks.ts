/**
 * #584 W1.2 — the local mergeKey → Edition join, written only at the
 * moments the app itself knows both sides:
 *
 *  - a play from Огляд/книги knows (mergeKey, editionId, narrator,
 *    language, chapter durations) — the honest base for a progress
 *    hairline and the Нові/Слухаю/Завершені classification;
 *  - «зберегти» on an Огляд card knows the same minus per-chapter detail.
 *
 * Never guessed: a Work the browser never touched has no link, and the
 * Медіатека shows it as never-started with no hairline — the honest
 * absence (ADR-0014), never a fabricated number.
 */
import { openListenerDatabase, type IdbDatabase } from './idb'
import { EDITION_LINKS_STORE, LISTENER_DB_VERSION, LISTENER_STORES } from './schema'

export interface EditionLink {
  editionId: string
  mergeKey: string
  narrator: string
  /** BCP-47 primary tag, '' = unknown (never guessed). */
  language: string
  /** The Edition's total duration when the source declares it; null = unknown. */
  durationSeconds: number | null
  /** Per-chapter durations when every chapter declared one; null = unknown. */
  chapterDurations: number[] | null
  updatedAt: number
}

function openLinksDatabase(): Promise<IdbDatabase | null> {
  return openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
}

/** The one seam for edition links; degrade-never everywhere. */
export class EditionLinkStore {
  private db: IdbDatabase | null = null
  private opening: Promise<IdbDatabase | null> | null = null

  constructor(
    private readonly open: () => Promise<IdbDatabase | null> = openLinksDatabase,
    private readonly now: () => number = () => Date.now(),
  ) {}

  private async ready(): Promise<IdbDatabase | null> {
    if (this.db !== null) return this.db
    if (this.opening === null) this.opening = this.open()
    const db = await this.opening
    if (db !== null) this.db = db
    return this.db
  }

  /** Upserts one link (the newest write wins — same facts, fresher detail). */
  async link(input: Omit<EditionLink, 'updatedAt'>): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    await db.put<EditionLink>(EDITION_LINKS_STORE, { ...input, updatedAt: this.now() })
  }

  async linksFor(mergeKey: string): Promise<EditionLink[]> {
    const db = await this.ready()
    if (db === null) return []
    const all = await db.getAll<EditionLink>(EDITION_LINKS_STORE)
    return all.filter((row) => row.mergeKey === mergeKey)
  }

  async all(): Promise<EditionLink[]> {
    const db = await this.ready()
    if (db === null) return []
    return db.getAll<EditionLink>(EDITION_LINKS_STORE)
  }

  /** Removes the links of one Work (the deletion's local cleanup). */
  async removeForMerge(mergeKey: string): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    const all = await db.getAll<EditionLink>(EDITION_LINKS_STORE)
    for (const row of all.filter((link) => link.mergeKey === mergeKey)) {
      await db.delete(EDITION_LINKS_STORE, row.editionId)
    }
  }
}
