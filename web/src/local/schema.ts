/**
 * #580 W0.2 — the ONE schema of the ONE listener database (R-W8).
 *
 * Every listener-data module opens the same database with this same,
 * complete store list, so a second connection never opens a version whose
 * upgrade already ran without the other module's stores. This module is a
 * leaf: it imports nothing from the stores it describes, which keeps
 * `listeningState` and `domain` acyclic.
 */

export const LISTENING_STATE_STORE = 'listening_state'
export const WORKS_STORE = 'works'
export const WORK_RELATIONSHIPS_STORE = 'work_relationships'
export const PERSON_BOOKMARKS_STORE = 'person_bookmarks'

export interface StoreSpec {
  name: string
  /** KeyPath options for `createObjectStore`; omit for out-of-line keys. */
  keyPath?: string | string[]
  /** Index specs created on every fresh store. */
  indexes?: Array<{ name: string; keyPath: string | string[] | Iterable<string> }>
}

export const LISTENER_STORES: StoreSpec[] = [
  {
    name: LISTENING_STATE_STORE,
    keyPath: 'editionId',
    indexes: [{ name: 'updatedAt', keyPath: 'updatedAt' }],
  },
  { name: WORKS_STORE, keyPath: 'mergeKey' },
  { name: WORK_RELATIONSHIPS_STORE, keyPath: 'mergeKey' },
  { name: PERSON_BOOKMARKS_STORE, keyPath: 'personId' },
]
