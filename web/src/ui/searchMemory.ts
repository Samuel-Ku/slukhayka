/**
 * W3.4 — the search round-trip memory: the query and the scroll place
 * survive the Огляд tab's unmount while a book is open (or while another
 * tab is shown). Android keeps the HomeScreen alive on the backstack with
 * its ViewModel state; the web remounts Catalog, so the tab remembers the
 * two things the AC names — «добірку і місце» (the query and the place).
 *
 * Module-singleton by design (no storage round-trip for a session-local
 * fact); `resetSearchMemory` exists for tests, which share the module.
 */
export interface SearchMemory {
  query: string
  scrollY: number
}

export const searchMemory: SearchMemory = { query: '', scrollY: 0 }

export function resetSearchMemory(): void {
  searchMemory.query = ''
  searchMemory.scrollY = 0
}