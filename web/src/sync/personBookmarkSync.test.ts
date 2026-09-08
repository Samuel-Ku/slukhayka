/**
 * #582 W0.4 — the pure `person_bookmarks` contract: Android's doc id,
 * the strict codec (unknown fields and out-of-bounds values are misses),
 * and the LWW rules (remote wins strictly newer; pull never deletes;
 * local pushes when its clock is ahead).
 */
import { describe, expect, it } from 'vitest'
import {
  pendingDeleteKey,
  personBookmarkDocumentId,
  remotePersonBookmarkFromDocument,
  remoteWins,
  roleOfWireKind,
  shouldPush,
  toDomainBookmark,
  wireKindOf,
  type RemotePersonBookmark,
} from './personBookmarkSync'
import type { PersonBookmarkEntity } from '../local/domain'

const remote: RemotePersonBookmark = {
  kind: 'AUTHOR',
  personId: 'author-abc123def4567890',
  displayName: 'Шевченко',
  notifyEnabled: true,
  updatedAtServerMs: 500,
}

function local(overrides: Partial<PersonBookmarkEntity> = {}): PersonBookmarkEntity {
  return {
    role: 'author',
    personId: 'author-abc123def4567890',
    displayName: 'Шевченко',
    createdAt: 100,
    updatedAt: 100,
    notifyEnabled: true,
    ...overrides,
  }
}

describe('wire vocabulary — Android verbatim', () => {
  it('kinds map 1:1 to Android storage values', () => {
    expect(wireKindOf('author')).toBe('AUTHOR')
    expect(wireKindOf('narrator')).toBe('NARRATOR')
    expect(roleOfWireKind('AUTHOR')).toBe('author')
    expect(roleOfWireKind('NARRATOR')).toBe('narrator')
    expect(roleOfWireKind('ROLE')).toBeNull()
  })

  it('document id is `${uid}_${kind}_${personId}` (Android)', () => {
    expect(personBookmarkDocumentId('u1', 'AUTHOR', 'author-x')).toBe('u1_AUTHOR_author-x')
  })

  it('pending-delete keys are `${kind}:${personId}` (Android)', () => {
    expect(pendingDeleteKey('NARRATOR', 'narrator-y')).toBe('NARRATOR:narrator-y')
  })
})

describe('codec — strict misses, never a corrupt row', () => {
  it('decodes a valid document', () => {
    const row = remotePersonBookmarkFromDocument({
      uid: 'u1',
      kind: 'AUTHOR',
      personId: 'author-x',
      displayName: 'Камю',
      notifyEnabled: true,
      updatedAt: 42,
    })
    expect(row).toEqual({ kind: 'AUTHOR', personId: 'author-x', displayName: 'Камю', notifyEnabled: true, updatedAtServerMs: 42 })
  })

  it('rejects unknown fields (Android\'s allowed-set strictness)', () => {
    expect(remotePersonBookmarkFromDocument({
      uid: 'u1', kind: 'AUTHOR', personId: 'author-x', displayName: 'Камю', notifyEnabled: true, updatedAt: 42, extra: 1,
    })).toBeNull()
  })

  it('rejects unknown kinds, wrong types, blanks and oversized values', () => {
    expect(remotePersonBookmarkFromDocument({
      uid: 'u1', kind: 'FAN', personId: 'x', displayName: 'Камю', notifyEnabled: true, updatedAt: 42,
    })).toBeNull()
    expect(remotePersonBookmarkFromDocument({
      uid: 'u1', kind: 'AUTHOR', personId: '', displayName: 'Камю', notifyEnabled: true, updatedAt: 42,
    })).toBeNull()
    expect(remotePersonBookmarkFromDocument({
      uid: 'u1', kind: 'AUTHOR', personId: 'x', displayName: '', notifyEnabled: true, updatedAt: 42,
    })).toBeNull()
    expect(remotePersonBookmarkFromDocument({
      uid: 'u1', kind: 'AUTHOR', personId: 'x'.repeat(301), displayName: 'Камю', notifyEnabled: true, updatedAt: 42,
    })).toBeNull()
    expect(remotePersonBookmarkFromDocument({
      uid: 'u1', kind: 'AUTHOR', personId: 'x', displayName: 'Камю', notifyEnabled: true, updatedAt: 0,
    })).toBeNull()
    expect(remotePersonBookmarkFromDocument({
      uid: 'u1', kind: 'AUTHOR', personId: 'x', displayName: 'Камю', notifyEnabled: 'yes', updatedAt: 42,
    })).toBeNull()
  })

  it('maps a remote row onto the domain entity with the server clock', () => {
    expect(toDomainBookmark(remote)).toEqual({
      role: 'author',
      personId: 'author-abc123def4567890',
      displayName: 'Шевченко',
      createdAt: 500,
      updatedAt: 500,
      notifyEnabled: true,
    })
  })
})

describe('LWW policy — Android\'s sync rules', () => {
  it('remote wins only when strictly newer; a tie keeps local', () => {
    expect(remoteWins(null, remote)).toBe(true)
    expect(remoteWins(local({ updatedAt: 100 }), remote)).toBe(true)
    expect(remoteWins(local({ updatedAt: 500 }), remote)).toBe(false) // tie → local
    expect(remoteWins(local({ updatedAt: 600 }), remote)).toBe(false)
  })

  it('push happens when the local clock is ahead or the cloud has no row', () => {
    expect(shouldPush(local({ updatedAt: 600 }), remote)).toBe(true)
    expect(shouldPush(local({ updatedAt: 500 }), remote)).toBe(false) // tie → no push
    expect(shouldPush(local({ updatedAt: 100 }), null)).toBe(true)
  })
})