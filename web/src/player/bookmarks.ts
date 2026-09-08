/**
 * W5.1 — the player's bookmarks, ported from Android's `BookmarkEntity`
 * (bookId/chapterIndex/chapterTitle/timestampSeconds/note/createdAt): a
 * bookmark anchors at the Work (workId = mergeKey) and the exact rendition
 * (editionId, ADR-0007), like Android's bookId + nullable editionId.
 *
 * Listener-owned playback data → IndexedDB (R-W8) via the same typed door
 * as the rest; the store degrades to null/[] on every failure, never an
 * exception. Bookmarks are NEVER synced — they are local reading artifacts,
 * exactly like Android's.
 */
import type { IdbDatabase } from '../local/idb'
import { openListenerDatabase } from '../local/idb'
import { LISTENER_DB_VERSION, LISTENER_STORES, PLAYER_BOOKMARKS_STORE } from '../local/schema'

export interface PlayerBookmark {
  id: string
  /** The Work this bookmark anchors to (Android's bookId = mergeKey). */
  workId: string
  /** The exact rendition (ADR-0007); empty when unknown. */
  editionId: string
  chapterIndex: number
  chapterTitle: string
  timestampSeconds: number
  note: string
  createdAt: number
}

export type PlayerBookmarkInput = Omit<PlayerBookmark, 'id' | 'createdAt'>

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/** The one validator: corrupt records are skipped, never crash the list. */
export function parsePlayerBookmarkValue(value: unknown): PlayerBookmark | null {
  if (typeof value !== 'object' || value === null) return null
  const candidate = value as Partial<PlayerBookmark>
  if (
    typeof candidate.id !== 'string' ||
    candidate.id === '' ||
    typeof candidate.workId !== 'string' ||
    candidate.workId === '' ||
    typeof candidate.editionId !== 'string' ||
    !isFiniteNumber(candidate.chapterIndex) ||
    candidate.chapterIndex < 0 ||
    !Number.isInteger(candidate.chapterIndex) ||
    !isFiniteNumber(candidate.timestampSeconds) ||
    candidate.timestampSeconds < 0 ||
    typeof candidate.chapterTitle !== 'string' ||
    typeof candidate.note !== 'string' ||
    !isFiniteNumber(candidate.createdAt)
  ) {
    return null
  }
  return {
    id: candidate.id,
    workId: candidate.workId,
    editionId: candidate.editionId,
    chapterIndex: candidate.chapterIndex,
    chapterTitle: candidate.chapterTitle,
    timestampSeconds: candidate.timestampSeconds,
    note: candidate.note,
    createdAt: candidate.createdAt,
  }
}

/** Newest first — Android's bookmarks list order. */
export function sortBookmarksNewestFirst(bookmarks: PlayerBookmark[]): PlayerBookmark[] {
  return [...bookmarks].sort((a, b) => b.createdAt - a.createdAt)
}

function bookmarkId(now: number): string {
  // Bookmarks are events, not identities: Android auto-generates ids, so
  // the web uses a time-ordered random id — no collisions, no leaks.
  const random =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID().slice(0, 8)
      : Math.random().toString(36).slice(2, 10)
  return `${now.toString(36)}-${random}`
}

function openBookmarksDatabase(): Promise<IdbDatabase | null> {
  return openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
}

/** The one store seam for player bookmarks; degrade-never everywhere. */
export class PlayerBookmarksStore {
  private db: IdbDatabase | null = null
  private opening: Promise<IdbDatabase | null> | null = null

  constructor(
    private readonly open: () => Promise<IdbDatabase | null> = openBookmarksDatabase,
    private readonly now: () => number = () => Date.now(),
  ) {}

  private async ready(): Promise<IdbDatabase | null> {
    if (this.db !== null) return this.db
    if (this.opening === null) this.opening = this.open()
    const db = await this.opening
    if (db !== null) this.db = db
    return this.db
  }

  /** Adds a bookmark; returns null when storage is unavailable (degrade-never). */
  async add(input: PlayerBookmarkInput): Promise<PlayerBookmark | null> {
    const db = await this.ready()
    if (db === null) return null
    const bookmark: PlayerBookmark = { ...input, id: bookmarkId(this.now()), createdAt: this.now() }
    await db.put(PLAYER_BOOKMARKS_STORE, bookmark)
    return bookmark
  }

  async remove(id: string): Promise<void> {
    const db = await this.ready()
    if (db === null) return
    await db.delete(PLAYER_BOOKMARKS_STORE, id)
  }

  /** All bookmarks of a Work, newest first (Android's book-scoped list). */
  async forWork(workId: string): Promise<PlayerBookmark[]> {
    const db = await this.ready()
    if (db === null) return []
    const all = await db.getAll<PlayerBookmark>(PLAYER_BOOKMARKS_STORE)
    return sortBookmarksNewestFirst(all.filter((raw) => parsePlayerBookmarkValue(raw)?.workId === workId))
  }

  async forEdition(editionId: string): Promise<PlayerBookmark[]> {
    const db = await this.ready()
    if (db === null) return []
    const all = await db.getAll<PlayerBookmark>(PLAYER_BOOKMARKS_STORE)
    return sortBookmarksNewestFirst(all.filter((raw) => parsePlayerBookmarkValue(raw)?.editionId === editionId))
  }
}