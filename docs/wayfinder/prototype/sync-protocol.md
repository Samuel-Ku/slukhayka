# Local-first sync protocol — wayfinder prototype (#56)

Status: prototype for ticket «Local-first sync protocol & conflict UX» (#56),
wayfinder map «Smart Library, Sync & Listening Intelligence» (#45).
Grounded in: `sync-backend-feasibility.md` (#49 — Firestore + anonymous Auth
feasible, pre-wired), «Listening event model» (#53 — event log with
`deviceId`, LWW by `lastListenedAt`, deterministic tiebreak, no vector
clocks), the map's standing constraints, and the Room schema at v9.

Everything here is a **decision for review**, not implemented code. The two
snippets (lifecycle machine and Firestore model) are the decision-rich parts
of the prototype; the rest is the protocol narrative.

## 0. What sync is (and is not)

- **Metadata only.** Listening state, library entries, bookmarks, event-log
  tail, daily stats, metadata corrections, tombstones. Never audio files,
  never local file paths, never `sourceTreeUri` (device-local).
- **Local-first.** Room stays the single source of truth; Firestore is a
  mirror. The app is fully useful offline and without an account.
- **Opt-in.** No account at first run; sync starts only when the user enables
  it. Signing in later *merges* local state — it never replaces it.

## 1. Lifecycle state machine (prototype snippet)

States and transitions of the sync identity; one account spans many devices,
and a device is identified by its own `deviceId` (see §2).

```
                    ┌──────────────────────────────┐
                    │  LOCAL_ONLY (default)        │
                    │  Room only; sync off         │
                    └──────────────┬───────────────┘
                     enable sync   │  (opt-in, asks nothing)
                                   ▼
                    ┌──────────────────────────────┐
              ┌────►│  SIGNED_IN                   │
              │     │  anonymous uid; device added │
              │     │  to the account registry     │
              │     └──────────────┬───────────────┘
              │       Google sign-in│ (linkWithCredential)
              │                    ▼
              │     ┌──────────────────────────────┐
              │     │  ACCOUNT_LINKED              │
              │     │  same uid after upgrade;     │
              │     │  collection unchanged        │
              │     └──────────────┬───────────────┘
              │                    │ sign out (not deletion)
              │                    ▼
              │     ┌──────────────────────────────┐
              └─────┤  SIGNED_OUT                  │
                    │  Room intact; sync paused;   │
                    │  cloud kept; re-sign-in =    │
                    │  idempotent LWW merge        │
                    └──────────────────────────────┘
```

- **Anonymous first.** The cheapest auth path (#49 finding 2), no sign-in UI.
  Firebase's anonymous uid survives an upgrade to Google sign-in via
  `linkWithCredential` — the account collection is keyed by the *uid*, so the
  upgrade changes nothing structurally.
- **Reinstall recovery is a pairing code, not "re-anon + merge".** A fresh
  install cannot re-derive the old anonymous uid; merging two anonymous
  accounts is unsafe. Instead: a signed-in device shows a 6-digit code; the
  fresh install enters it, gets added to the account's `devices` registry, and
  pulls the merge. The registry also powers a «your devices» screen and a
  remote sign-out.
- **Sign-out is not deletion.** Cloud stays; local stays; re-sign-in is an
  idempotent per-document merge (no double-application, LWW makes it
  convergent). Deleting data is a separate, explicit, two-step action (§7).

## 2. Device identity

- One per-install UUID `deviceId`, generated at first sync opt-in, stored in
  Room, attached to every sync write. The `playback_events.deviceId` column
  (v9) is exactly this — today it is always `""` until sync stamps it.
- `deviceId ≠ account`. It identifies *this install*; the uid identifies the
  *account*. One account, N devices; one device, one account at a time.
- `deviceId` rides in every event row, every document's `updatedBy`, and the
  device-registry document.

## 3. Firestore document model (prototype snippet)

All under `users/{uid}/`. Documents are small and keyed; every document
carries `updatedAt` (server timestamp) and `updatedBy` (deviceId) for the LWW
merge (§4).

```
users/{uid}/
├── devices/{deviceId}            { name, platform, appVersion, lastSeenAt }
├── library/{mergeKey}            Work-level entry (the merged card)
│                                 { title, author, narrator, seriesTitle,
│                                   seriesIndex, isFavorite, deleted? }
│   library/{mergeKey}/sources/{sourceKey}
│                                 per-source edition row { type, url,
│                                   streamOnly, addedAt, deleted? }
├── listening/{mergeKey}:{sourceKey}
│                                 state row { chapterIndex, positionSeconds,
│                                   lastListenedAt, isCompleted,
│                                   lastPausedAtEpochMs }
├── events/{mergeKey}:{sourceKey} compacted capped tail (≤ 50) of the
│                                 playback_events log { events[], 
│                                   lastEventTimestamp, updatedBy }
├── bookmarks/{bookmarkId}        { chapterIndex, chapterTitle,
│                                   timestampSeconds, note, createdAt,
│                                   deleted? }
├── stats/{dateIso}               daily listenedSeconds aggregate
└── corrections/{mergeKey}        per-field metadata corrections
                                  { field, value, origin, updatedAt,
                                    updatedBy, userMade }
```

Decisions embedded in the model:

- **Library entries are Work-level** (`mergeKey`, the existing normalized
  title|author|narrator dedup key), sources are child docs — matches the
  domain glossary (Work / Edition / Source) and spec-10 T2.
- **The event log syncs as a compacted tail, not per-event documents.**
  Firestore is a bad append-only log; the capped 50-FIFO tail (the #53
  policy) ships as one document per (mergeKey, sourceKey), LWW by last event
  timestamp. It is advisory history (undo-across-devices within cap,
  Listening Intelligence #60); each device's UI derives from its own Room
  state row, never from the log.
- **Tombstones are first-class documents** (`deleted: true` on library/source/
  bookmark docs). They survive reinstall and ride along with catalogue
  refreshes — closing the gap that today's in-memory `deletedCatalogBookIds`
  (a `ConcurrentHashMap`, lost on restart) leaves open (#49 finding 3).
- **`playback_failures` stays local-only** — support/observability, not
  user state.

## 4. Deterministic conflict rules

One rule set, applied per document; no vector clocks (#53).

1. **Last-write-wins by `updatedAt`.** Greater `updatedAt` wins.
2. **Deterministic tiebreak** when timestamps collide: greater `updatedBy`
   (deviceId) wins; then greater document key — the (sourceKey, bookId)
   convention from #53.
3. **State rows (listening)** additionally merge by `lastListenedAt`: the row
   with the newer `lastListenedAt` wins wholesale (chapter+position+completed
   are one row, never recombined — prevents half-applied mixes).
4. **Tombstone-first for library reconciliation.** A tombstone beats a live
   entry within the same sync epoch (a deleted book must not resurrect);
   a *re-import* is a new write that clears the tombstone with a fresh
   `updatedAt`. No "re-add" needs to survive an old tombstone.
5. **User-made corrections outrank derived ones** regardless of timestamp —
   user intent beats machine parsing (see §6 Case B).
6. **Everything else** (favorites, completion flags, bookmarks, series
   metadata, stats) is plain per-document LWW — convergent, no UI.

## 5. Offline edits & retries

- Writes land in Room first, then push; Firestore offline persistence holds
  the outbox across restarts (native). Retry: exponential backoff with jitter
  (30 s → 5 min, capped), flush on connectivity regained.
- Reads are optimistic from Room; the remote reconciles in the background and
  writes back only what changed (per-document).
- Conflicts surfacing on flush resolve by §4; documents are small and keyed,
  so no cross-document torn states.

## 6. Rare cases that ask the user (the two review surfaces)

Deterministic rules resolve *almost* everything. Exactly two cases destroy
user intent if resolved blindly, so they get a lightweight review sheet —
everything else stays silent.

**Case A — divergent positions.** LWW by `lastListenedAt` is correct unless
the merge would *roll back the position the user is currently listening to by
more than 5 minutes* (the seek threshold): device A at 02:18:43 (newer
write), device B offline at 05:38:10. Rolling A back to B would visibly
"move the user backwards" mid-session.

- Rule: if the local device's own `lastListenedAt` is newer, keep local —
  never silently. The sheet appears only when the remote row would win and
  its position is ≥ 5 min *behind* the local current position.
- Sheet: «На цьому пристрої ви на 02:18:43, інший пристрій на 05:38:10.
  Що залишити?» → [Залишити мій прогрес] / [Взяти прогрес іншого пристрою].
- Default if ignored: **keep local** — never move the user backwards without
  consent. The choice is applied as a normal LWW write (the picked side wins),
  so both devices converge without re-asking.

**Case B — metadata corrections.** Device A (manual edit) says author
«Тарас Шевченко»; device B (parsed from a source page) says «Т. Шевченко».
Per §4.5 a *user-made* correction always outranks a *derived* one — no UI.
The sheet appears only when **two user-made corrections of the same field
conflict**: show both values + their origins, let the user pick; the choice
is stored as a new correction (that memory is exactly the #54 correction
memory) and syncs like any other.

Everything else — completion, favorites, bookmarks, series, tombstones,
stats — resolves with no UI and no prompt.

## 7. Sign-out & data deletion

- **Sign out:** sync pauses; Room intact; cloud kept. Re-sign-in re-merges
  (idempotent, §4). Remote sign-out of a lost device: delete its
  `devices/{deviceId}` doc.
- **«Видалити дані з хмари»** (destructive, two-step confirm): wipe the
  uid's collections; account stays; local unaffected.
- **«Видалити акаунт»** (destructive, two-step confirm): Firebase
  `deleteUser` + collection wipe.
- Sync deletion touches the cloud mirror and library tombstones only — it
  never deletes local audio files (the three-level deletion contract from the
  mature-library map stands).

## 8. Source reconnection

- Sources are per-device bindings by definition (map constraint); sync stores
  only public `type` + `url` (+ `streamOnly`, `addedAt`), never local paths.
- After reinstall, streamed sources re-link by type+url automatically;
  local-file sources re-link via the existing SAF re-scan flow (or
  re-picking the folder) — the same #42 behaviour, unchanged.

## 9. Sequencing for a bounded first release (from #49's costed path)

1. Migration guardrails — already done through v9 (exportSchema committed,
   migration tests).
2. Room v10: durable `tombstones` table + `deviceId` stamping on event rows
   (the column exists; wire it), corrections table (feeds #54).
3. Auth (anonymous) + Firestore + `google-services.json` + App Check
   re-verification — **project-owner action** (SHA-256s for debug/release).
4. Sync core as pure repository-seam functions: per-document LWW merge,
   tombstone-first reconciliation, backoff policy — unit-tested the way
   `PlaybackEventPolicy` / `GlobalSearch` are (pure functions + in-memory
   Room), no Firebase in JVM tests.
5. The two review sheets (Case A / Case B) as a minimal bottom-sheet UI.

## Out of scope (of this prototype)

- Audio upload/download, account-gated content, social features.
- Cross-device transfer of local files; the pairing code is identity-only.
- Vector clocks / CRDTs — explicitly rejected by #53 for this domain.
- Implementing Stage 2 — this prototype settles the protocol so the map can
  close; implementation is a later effort.
