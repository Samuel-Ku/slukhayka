import { describe, expect, it } from 'vitest'
import { ProgressSyncCodec, documentId, shouldPull, shouldPush, type RemoteListeningState } from '../policy'

function remote(updatedAt: number, editionId = 'ed-1'): RemoteListeningState {
  return {
    editionId,
    chapterIndex: 2,
    positionSeconds: 120,
    isCompleted: false,
    preferredSpeed: 1.25,
    updatedAtServerMs: updatedAt,
  }
}

describe('shouldPull — only strictly newer server states apply', () => {
  const cases: Array<[string, RemoteListeningState | null, number | null, boolean]> = [
    ['no remote document', null, 1000, false],
    ['corrupt stamp never applies', remote(0), null, false],
    ['first sight of the cloud', remote(5000), null, true],
    ['newer than what we saw', remote(9000), 5000, true],
    ['equal means we already are the server state', remote(5000), 5000, false],
    ['older than what we saw', remote(4000), 5000, false],
  ]
  for (const [name, r, synced, expected] of cases) {
    it(name, () => {
      expect(shouldPull(r, synced)).toBe(expected)
    })
  }
})

describe('shouldPush — honest moments bypass the pacing window', () => {
  const cases: Array<[string, number, number | null, boolean, boolean]> = [
    ['immediate push ignores everything', 1000, null, true, true],
    ['first tick pushes at once', 1000, null, false, true],
    ['tick inside the window waits', 30_000, 0, false, false],
    ["exactly at the window's end goes", 60_000, 0, false, true],
    ['tick past the window goes', 60_001, 0, false, true],
  ]
  for (const [name, nowMs, lastAttempt, immediate, expected] of cases) {
    it(name, () => {
      expect(shouldPush(nowMs, lastAttempt, immediate)).toBe(expected)
    })
  }
})

it('document id keeps the reviews shape', () => {
  expect(documentId('uid-1', 'ed-9')).toBe('uid-1_ed-9')
})

describe('codec', () => {
  it('round-trips a payload without fabricating updatedAt', () => {
    const original = remote(42)
    const doc = ProgressSyncCodec.toDocument('listener', original)
    expect(doc).not.toHaveProperty(ProgressSyncCodec.FIELD_UPDATED_AT)
    expect(doc[ProgressSyncCodec.FIELD_UID]).toBe('listener')
    const decoded = ProgressSyncCodec.fromDocument({ ...doc, [ProgressSyncCodec.FIELD_UPDATED_AT]: 7777 })
    expect(decoded).toEqual({ ...original, updatedAtServerMs: 7777 })
  })

  it('drops corrupt documents instead of crashing', () => {
    const base: Record<string, unknown> = {
      [ProgressSyncCodec.FIELD_EDITION_ID]: 'ed-1',
      [ProgressSyncCodec.FIELD_CHAPTER_INDEX]: 1,
      [ProgressSyncCodec.FIELD_POSITION_SECONDS]: 10,
      [ProgressSyncCodec.FIELD_IS_COMPLETED]: false,
      [ProgressSyncCodec.FIELD_UPDATED_AT]: 5,
    }
    const cases: Array<[Record<string, unknown>, string]> = [
      [Object.fromEntries(Object.entries(base).filter(([k]) => k !== ProgressSyncCodec.FIELD_EDITION_ID)), 'missing edition'],
      [{ ...base, [ProgressSyncCodec.FIELD_EDITION_ID]: '' }, 'blank edition'],
      [{ ...base, [ProgressSyncCodec.FIELD_EDITION_ID]: 'x'.repeat(301) }, 'overlong edition'],
      [{ ...base, [ProgressSyncCodec.FIELD_CHAPTER_INDEX]: -1 }, 'negative chapter'],
      [{ ...base, [ProgressSyncCodec.FIELD_POSITION_SECONDS]: -5 }, 'negative position'],
      [{ ...base, [ProgressSyncCodec.FIELD_POSITION_SECONDS]: 10_000_000_000 }, 'absurd position'],
      [{ ...base, [ProgressSyncCodec.FIELD_PREFERRED_SPEED]: 9.0 }, 'speed out of range'],
      [{ ...base, [ProgressSyncCodec.FIELD_PREFERRED_SPEED]: 'fast' }, 'speed of a wrong type'],
      [Object.fromEntries(Object.entries(base).filter(([k]) => k !== ProgressSyncCodec.FIELD_UPDATED_AT)), 'no server stamp'],
      [{ ...base, [ProgressSyncCodec.FIELD_UPDATED_AT]: 0 }, 'zero stamp'],
    ]
    for (const [doc, name] of cases) {
      expect(ProgressSyncCodec.fromDocument(doc), name).toBeNull()
    }
    expect(ProgressSyncCodec.fromDocument(null)).toBeNull()
  })
})
