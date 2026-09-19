/**
 * spec-51 (#694/#697, T9) — the web twin of Android's `CuratorIdentity` and
 * `CollectionIdentity`: the anonymous, cross-platform identity of a curator
 * and of one vote/complaint.
 *
 * These are a WIRE CONTRACT, not a private detail. Android writes
 * `collection_authors/{sha256(uid)}`, `curator_collection_votes/{sha256(uid +
 * collectionId)}` and `curator_collection_reports/{sha256(uid + collectionId)}`;
 * if the browser derived a different digest it would write into different
 * documents and the phone's votes would appear to vanish. The vectors are
 * therefore PINNED against `CollectionIdentityTest.kt:11-20` and
 * `CuratorIdentityTest.kt:15-25` — do not "improve" the algorithm here.
 *
 * `js-sha256` emits lowercase hex, byte-for-byte the same as Kotlin's
 * `MessageDigest("SHA-256").digest(...).joinToString { "%02x" }` over UTF-8.
 */
import { sha256 } from 'js-sha256'

/**
 * Android's `CuratorIdentity`: a listener's PUBLIC identity. The raw uid must
 * never travel, so every public document id is derived from `sha256(uid)`.
 */
export const CuratorIdentity = {
  /**
   * Android's `CuratorIdentity.authorId`: the lowercase hex SHA-256 of the uid,
   * or `""` for a blank uid — a blank identity is never publishable, and the
   * empty string is the honest "nothing to publish", not a hash of nothing.
   */
  authorId(uid: string | null | undefined): string {
    if (uid === null || uid === undefined || uid.trim() === '') return ''
    return sha256(uid)
  },

  /** Only a real identity can own published documents. */
  isPublishable(uid: string | null | undefined): boolean {
    return CuratorIdentity.authorId(uid) !== ''
  },
} as const

/**
 * Android's `CollectionIdentity`: the ANONYMOUS identity of one vote or one
 * complaint. `sha256(uid + collectionId)` keeps the same person unrelated on
 * two collections and unlinkable to their reviews, while a re-vote still lands
 * on the same document.
 */
export const CollectionIdentity = {
  /**
   * Android's `CollectionIdentity.voterKey`: the lowercase hex SHA-256 of the
   * RAW `uid + collectionId`, or `""` when either part is blank — a blank key
   * is never a votable identity. The parts are concatenated UNTRIMMED, exactly
   * as Kotlin does; only the blank check trims.
   */
  voterKey(uid: string | null | undefined, collectionId: string): string {
    if (uid === null || uid === undefined || uid.trim() === '' || collectionId.trim() === '') return ''
    return sha256(uid + collectionId)
  },
} as const
