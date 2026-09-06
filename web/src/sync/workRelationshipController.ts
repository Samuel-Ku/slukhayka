import type { DomainStore, WorkRelationshipRow } from '../local/domain'
import { mergeRelationshipRows, type RemoteWorkRelationship } from './workRelationships'
import type { WorkRelationshipSyncStore } from './workRelationshipStore'

/**
 * #581 W0.3 — the web orchestrator of Work-relationship sync, sequenced
 * exactly like `ProgressSyncController` (same gates, same degrade-never):
 *
 *  - `pushAfterChange` — the honest moments (save / hide from the UI) upload
 *    the relationship row when the listener is bound and sync is on; the
 *    gate re-checks after every await, so a switch flip stops the mirror
 *    mid-flight.
 *  - `pullAndApply` — every cloud row applies through the domain store's
 *    shared LWW projection (ADR-0034), so a stale remote favorite can never
 *    resurrect a locally newer tombstone.
 *  - `mergeAtLinking` — after entering a Recovery Code, the pre-link local
 *    rows (server stamp 0 — no server ever vouched for them) union-merge
 *    with the account's rows: favorites upload, the merged projection is
 *    applied, and a clock tie keeps the tombstone.
 *
 * A null store (no Firebase config) or an unbound (`local-…`) profile makes
 * every entry point a no-op — the unlinked browser writes nothing.
 */
export class WorkRelationshipController {
  private uidOverride: string | null = null

  constructor(
    private readonly getUid: () => string | null,
    private readonly domain: DomainStore,
    private readonly store: WorkRelationshipSyncStore | null,
    private readonly isEnabled: () => boolean,
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

  /**
   * Uploads one relationship change (the honest moments: save / hide from
   * the UI). A fresh local action is the listener's newest intent: it
   * uploads unconditionally and the server's stamp makes it the row every
   * other device's LWW compares against. Display data degrades to the
   * mergeKey provenance when no Work row stands behind the action (a hide
   * from Огляд) — never a fabricated author.
   */
  async pushAfterChange(mergeKey: string): Promise<void> {
    const uid = this.gate()
    if (uid === null) return
    const current = await this.domain.relationshipOf(mergeKey)
    if (current === null || current.state === 'none') return
    if (!this.isEnabled()) return
    const row: RemoteWorkRelationship = {
      mergeKey,
      state: current.state,
      title: current.title !== '' ? current.title : mergeKey,
      author: current.author !== '' ? current.author : mergeKey,
      updatedAtServerMs: 0, // stamped by the server at the transport
    }
    await this.store!.push(uid, row).catch(() => null)
  }

  /** Applies every cloud row through the domain's shared LWW projection. */
  async pullAndApply(): Promise<RemoteWorkRelationship[]> {
    const uid = this.gate()
    if (uid === null) return []
    if (!this.isEnabled()) return []
    const remote = await this.store!.pullAll(uid).catch(() => [] as RemoteWorkRelationship[])
    if (!this.isEnabled()) return []
    for (const row of remote) {
      await this.domain.applyRelationship(WorkRelationshipController.toDomainRow(row))
    }
    return remote
  }

  /** Maps a cloud row onto the domain projection (the shape applyRelationship expects). */
  private static toDomainRow(row: RemoteWorkRelationship): WorkRelationshipRow {
    return {
      mergeKey: row.mergeKey,
      state: row.state,
      updatedAtServerMs: row.updatedAtServerMs,
      updatedAtLocalMs: row.updatedAtServerMs,
      title: row.title,
      author: row.author,
    }
  }

  /**
   * The linking moment: uploads pre-link local rows (their server stamp is
   * 0 by construction) and applies the union back through the shared LWW
   * rule. Returns the upload/applied counts for tests and diagnostics.
   */
  async mergeAtLinking(): Promise<{ uploaded: number; applied: RemoteWorkRelationship[] }> {
    const uid = this.boundUid()
    if (uid === null || this.store === null) return { uploaded: 0, applied: [] }
    const localRows = await this.domain.allRelationships()
    let uploaded = 0
    for (const row of localRows) {
      if (row.state === 'none') continue
      if (!this.isEnabled()) break
      // Pre-link rows carry no server clock (stamp 0): they are OLDER than
      // everything the account already vouches for. Upload only when the
      // account has no row for this Work — a server-vouched row (a phone's
      // deliberate hide included) always stands. The union below applies it.
      const existing = await this.store.pull(uid, row.mergeKey).catch(() => null)
      if (existing !== null) continue
      const remote: RemoteWorkRelationship = {
        mergeKey: row.mergeKey,
        state: row.state,
        title: row.title !== '' ? row.title : row.mergeKey,
        author: row.author !== '' ? row.author : row.mergeKey,
        updatedAtServerMs: 0,
      }
      const stamp = await this.store.push(uid, remote).catch(() => null)
      if (stamp !== null) uploaded += 1
    }
    const remote = await this.store.pullAll(uid).catch(() => [] as RemoteWorkRelationship[])
    const localProjection: RemoteWorkRelationship[] = localRows
      .filter((r): r is WorkRelationshipRow & { state: 'entry' | 'tombstone' } => r.state !== 'none')
      .map((r) => ({
        mergeKey: r.mergeKey,
        state: r.state,
        title: r.title,
        author: r.author,
        updatedAtServerMs: r.updatedAtServerMs,
      }))
    const union = mergeRelationshipRows(localProjection, remote)
    for (const row of union) {
      await this.domain.applyRelationship(WorkRelationshipController.toDomainRow(row))
    }
    return { uploaded, applied: union }
  }
}
