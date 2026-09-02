import type { SourceId } from '../worker/types'
import { readWarm, warmKey, writeWarm } from '../api/warmCache'

export const AVAILABILITY_POLICY = Object.freeze({
  positiveTtlMs: 6 * 60 * 60 * 1_000,
  negativeTtlMs: 15 * 60 * 1_000,
  verifiedProfileTtlMs: 24 * 60 * 60 * 1_000,
  sourceBudgetMs: 8_000,
  maxParallelSources: 2,
})

export type AvailabilityVerdict =
  | 'playing'
  | 'no-network'
  | 'temporary-failure'
  | 'audio-missing'
  | 'session-required'
  | 'timeout'
  | 'verified-profile'

export interface EditionSourceAttempt {
  editionId: string
  sourceId: SourceId
  url: string
}

export interface EditionRaceResult {
  candidate: EditionSourceAttempt | null
  verdict: Exclude<AvailabilityVerdict, 'verified-profile'>
}

export interface LocalAvailabilityAssertion {
  editionId: string
  sourceId: SourceId
  verdict: Exclude<AvailabilityVerdict, 'verified-profile'>
  observedAt: number
}

const PRIVATE_STREAM_PARAMETERS = new Set(['token', 'signature', 'sig', 'expires', 'key', 'auth', 'session', 'cookie'])

function relayUrlFor(streamUrl: string): string | null {
  try {
    const url = new URL(streamUrl)
    if ([...url.searchParams.keys()].some((key) => PRIVATE_STREAM_PARAMETERS.has(key.toLowerCase()))) return null
    return `/api/audio?u=${encodeURIComponent(streamUrl)}`
  } catch {
    return null
  }
}

export async function readAvailabilityAssertion(
  editionId: string,
  sourceId: SourceId,
): Promise<LocalAvailabilityAssertion | null> {
  const value = await readWarm<LocalAvailabilityAssertion>(warmKey('availability', sourceId, editionId))
  if (!value || value.editionId !== editionId || value.sourceId !== sourceId) return null
  return value
}

export function writeAvailabilityAssertion(assertion: LocalAvailabilityAssertion): Promise<void> {
  // Deliberately stores no URL, stream locator, cookie, token or identity.
  return writeWarm(warmKey('availability', assertion.sourceId, assertion.editionId), assertion)
}

export function availabilitySortRank(
  assertion: LocalAvailabilityAssertion | null,
  now: number,
): number {
  if (!assertion || !isAvailabilityFresh(assertion.verdict, assertion.observedAt, now)) return 1
  return assertion.verdict === 'playing' ? 0 : 2
}

export function isAvailabilityFresh(
  verdict: AvailabilityVerdict,
  observedAt: number,
  now: number,
): boolean {
  if (!Number.isFinite(observedAt) || now < observedAt) return false
  const ttl = verdict === 'playing'
    ? AVAILABILITY_POLICY.positiveTtlMs
    : verdict === 'verified-profile'
      ? AVAILABILITY_POLICY.verifiedProfileTtlMs
      : AVAILABILITY_POLICY.negativeTtlMs
  return now - observedAt < ttl
}

const TERMINAL_PRIORITY: Array<Exclude<AvailabilityVerdict, 'playing' | 'verified-profile'>> = [
  'session-required',
  'no-network',
  'audio-missing',
  'temporary-failure',
  'timeout',
]

/**
 * Runs one user action against no more than two Sources of the explicitly
 * selected Edition.  The attempt itself owns the meaning of `playing`; this
 * helper only enforces scope, time budget, cancellation and stable terminal
 * categorisation.
 */
export async function raceEditionSources(
  selectedEditionId: string,
  candidates: EditionSourceAttempt[],
  attempt: (
    candidate: EditionSourceAttempt,
    signal: AbortSignal,
  ) => Promise<Exclude<AvailabilityVerdict, 'verified-profile'>>,
  sourceBudgetMs = AVAILABILITY_POLICY.sourceBudgetMs,
  actionSignal?: AbortSignal,
): Promise<EditionRaceResult> {
  if (actionSignal?.aborted) return { candidate: null, verdict: 'timeout' }
  const eligible = candidates
    .filter((candidate) => candidate.editionId === selectedEditionId)
    .slice(0, AVAILABILITY_POLICY.maxParallelSources)
  if (eligible.length === 0) return { candidate: null, verdict: 'audio-missing' }

  const controllers = eligible.map(() => new AbortController())
  const abortAll = (): void => controllers.forEach((controller) => controller.abort())
  actionSignal?.addEventListener('abort', abortAll, { once: true })
  const pending = eligible.map((candidate, index) => {
    const controller = controllers[index]
    return new Promise<{ index: number; candidate: EditionSourceAttempt; verdict: Exclude<AvailabilityVerdict, 'verified-profile'> }>((resolve) => {
      let settled = false
      const finish = (verdict: Exclude<AvailabilityVerdict, 'verified-profile'>): void => {
        if (settled) return
        settled = true
        clearTimeout(timeout)
        resolve({ index, candidate, verdict })
      }
      const timeout = setTimeout(() => {
        controller.abort()
        finish('timeout')
      }, sourceBudgetMs)
      void attempt(candidate, controller.signal)
        .then(finish)
        .catch(() => finish(controller.signal.aborted ? 'timeout' : 'temporary-failure'))
    })
  })

  const remaining = new Set(pending.keys())
  const failures: Array<{ candidate: EditionSourceAttempt; verdict: Exclude<AvailabilityVerdict, 'playing' | 'verified-profile'> }> = []
  while (remaining.size > 0) {
    const result = await Promise.race([...remaining].map((index) => pending[index]))
    remaining.delete(result.index)
    if (result.verdict === 'playing') {
      controllers.forEach((controller, index) => {
        if (index !== result.index && remaining.has(index)) controller.abort()
      })
      actionSignal?.removeEventListener('abort', abortAll)
      return { candidate: result.candidate, verdict: 'playing' }
    }
    failures.push({ candidate: result.candidate, verdict: result.verdict })
  }

  const verdict = TERMINAL_PRIORITY.find((candidate) => failures.some((failure) => failure.verdict === candidate))
    ?? 'temporary-failure'
  actionSignal?.removeEventListener('abort', abortAll)
  return {
    candidate: failures.find((failure) => failure.verdict === verdict)?.candidate ?? null,
    verdict,
  }
}

/**
 * Media-only browser probe: a page fetch, status code, `loadedmetadata` or
 * assigning `src` is insufficient. Direct transport is attempted first;
 * only an actual `playing` event wins, then a same-origin Worker relay is
 * allowed as the bounded fallback. The temporary element is muted and is
 * always torn down on completion/cancellation.
 */
export async function probeStreamPlaying(
  streamUrl: string,
  signal: AbortSignal,
  relayUrlOf: (url: string) => string | null = relayUrlFor,
): Promise<boolean> {
  if (typeof Audio === 'undefined') return false
  const urls = [streamUrl, relayUrlOf(streamUrl)].filter((url): url is string => url !== null)
  for (const url of urls) {
    if (signal.aborted) return false
    const audio = new Audio()
    audio.muted = true
    audio.preload = 'auto'
    const played = await new Promise<boolean>((resolve) => {
      let settled = false
      const finish = (value: boolean): void => {
        if (settled) return
        settled = true
        audio.removeEventListener('playing', onPlaying)
        audio.removeEventListener('error', onError)
        signal.removeEventListener('abort', onAbort)
        audio.pause()
        audio.removeAttribute('src')
        audio.load()
        resolve(value)
      }
      const onPlaying = (): void => finish(true)
      const onError = (): void => finish(false)
      const onAbort = (): void => finish(false)
      audio.addEventListener('playing', onPlaying, { once: true })
      audio.addEventListener('error', onError, { once: true })
      signal.addEventListener('abort', onAbort, { once: true })
      audio.src = url
      void audio.play().catch(() => finish(false))
    })
    if (played) return true
  }
  return false
}

/** Byte-range/media validation for viewport preflight; HTML never passes. */
export async function preflightMediaRange(
  streamUrl: string,
  signal: AbortSignal,
  relayUrlOf: (url: string) => string | null = relayUrlFor,
  fetcher: typeof fetch = fetch,
): Promise<boolean> {
  for (const url of [streamUrl, relayUrlOf(streamUrl)].filter((url): url is string => url !== null)) {
    if (signal.aborted) return false
    try {
      const response = await fetcher(url, {
        signal,
        headers: { Range: 'bytes=0-2047' },
      })
      const contentType = response.headers.get('content-type')?.toLowerCase() ?? ''
      if (!(response.status === 200 || response.status === 206)) continue
      if (contentType.includes('text/html') || contentType.includes('application/xhtml')) continue
      const reader = response.body?.getReader()
      if (!reader) continue
      const first = await reader.read()
      await reader.cancel()
      if (!first.done && first.value.byteLength > 0) return true
    } catch {
      if (signal.aborted) return false
    }
  }
  return false
}
