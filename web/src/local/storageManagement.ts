/**
 * #591 W5.2 — the storage direction's honest measurements and scoped
 * clears, as pure functions with injectable seams (the ADR-0034
 * two-runtime rule). Nothing here guesses: usage comes from the browser's
 * own estimate, the audio-cache size is summed from the real cache, the
 * snapshot count is read from the real stores, and every clear names its
 * exact scope in the confirmation the UI shows.
 */
import { AUDIO_CACHE_NAME } from '../offline/policy'
import { collectLegacyListeningRows } from './listeningState'

/** Android-style human byte size («1,2 МБ»); degrades to «0 Б». */
export function formatBytes(bytes: number): string {
  const value = Number.isFinite(bytes) && bytes > 0 ? bytes : 0
  if (value < 1024) return `${value} Б`
  const units = ['КБ', 'МБ', 'ГБ']
  let amount = value
  let unit = 'Б'
  for (const next of units) {
    if (amount < 1024) break
    amount /= 1024
    unit = next
  }
  const rounded = amount >= 10 ? Math.round(amount) : Math.round(amount * 10) / 10
  return `${rounded} ${unit}`
}

export interface OriginUsage {
  used: number
  quota: number
}

export type EstimateFn = () => Promise<{ usage?: number; quota?: number }>

const defaultEstimate: EstimateFn = async () => {
  try {
    if (typeof navigator === 'undefined' || navigator.storage?.estimate === undefined) {
      return { usage: undefined, quota: undefined }
    }
    const estimate = await navigator.storage.estimate()
    return { usage: estimate.usage, quota: estimate.quota }
  } catch {
    return { usage: undefined, quota: undefined }
  }
}

/** The whole origin's usage — honest total, null when the API is missing. */
export async function estimateOriginUsage(estimate: EstimateFn = defaultEstimate): Promise<OriginUsage | null> {
  let result: { usage?: number; quota?: number }
  try {
    result = await estimate()
  } catch {
    return null
  }
  if (typeof result.usage !== 'number' || typeof result.quota !== 'number') return null
  return { used: result.usage, quota: result.quota }
}

/** The thin Cache surface the size/clear functions need (injectable). */
export interface ManagementCache {
  matchAll(): Promise<readonly Response[]>
  keys(): Promise<readonly (Request | string)[]>
  delete(request: RequestInfo | string): Promise<boolean>
}

export interface ManagementCacheStorage {
  open(name: string): Promise<ManagementCache>
  delete(name: string): Promise<boolean>
}

function defaultCacheStorage(): ManagementCacheStorage | null {
  return typeof caches !== 'undefined' ? (caches as unknown as ManagementCacheStorage) : null
}

/**
 * The audio cache's real byte size: bodies are summed (bounded cache, ≤12
 * chapters); a body read that fails falls back to its Content-Length
 * header, and an unreadable entry counts as zero — the displayed number is
 * a floor, never an inflated guess.
 */
export async function audioCacheSize(cacheStorage: ManagementCacheStorage | null = defaultCacheStorage()): Promise<number> {
  if (cacheStorage === null) return 0
  try {
    const cache = await cacheStorage.open(AUDIO_CACHE_NAME)
    const responses = await cache.matchAll()
    let total = 0
    for (const response of responses) {
      try {
        const body = await response.clone().arrayBuffer()
        total += body.byteLength
      } catch {
        const header = Number(response.headers.get('Content-Length'))
        total += Number.isFinite(header) && header > 0 ? header : 0
      }
    }
    return total
  } catch {
    return 0
  }
}

/** Deletes the whole audio cache; false when the Cache API is missing. */
export async function clearAudioCache(cacheStorage: ManagementCacheStorage | null = defaultCacheStorage()): Promise<boolean> {
  if (cacheStorage === null) return false
  try {
    return await cacheStorage.delete(AUDIO_CACHE_NAME)
  } catch {
    return false
  }
}

/**
 * The count of persisted listening snapshots — the honest «позиції: N»
 * row. IDB is the source of truth after migration; the legacy localStorage
 * rows are counted too so the number is never smaller than reality.
 */
export async function listeningSnapshotCount(deps: {
  idbCount: () => Promise<number>
  storage: { getItem(key: string): string | null; removeItem(key: string): void; length?: number; key?(index: number): string | null }
}): Promise<number> {
  let count = 0
  try {
    count += await deps.idbCount()
  } catch {
    // degrade-never: count what can be counted
  }
  try {
    count += collectLegacyListeningRows(deps.storage).length
  } catch {
    // degrade-never
  }
  return count
}