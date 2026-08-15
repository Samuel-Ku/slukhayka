# Sync backend feasibility — wayfinder ticket «Sync backend feasibility» (#49)

Status: resolved 2026-08-10. Evidence: `app/build.gradle.kts` (HEAD 3c3c731),
Firebase documentation (Firestore offline persistence, App Check), prior art
`backup-sync-approach.md` (#35).

## Scope split vs #35

#35 already settled *backup* («нічого не губиться») as **Auto Backup + SAF
export, no backend**. This ticket answers a different question: is a *sync
backend* (multi-device listening state + library reconciliation) feasible for
this app at all, and what would it cost — i.e. the transport for the spec-10
(cross-device) parts of the domain model.

## What already exists in the build (verified, `app/build.gradle.kts`)

- `implementation(platform(libs.firebase.bom))` — Firebase BOM **34.15.0** is a live dependency (`:91`).
- `implementation(libs.firebase.ai)` — Firebase AI (generative) is shipped (`:117`).
- `implementation(libs.firebase.appcheck.recaptcha)` — **App Check is already on** (`:128`).
- Firestore and Auth are commented out **on purpose**, with a comment that uncommenting is the intended path back:
  `// implementation(libs.firebase.firestore)` (`:119`), `// implementation(libs.firebase.auth)` (`:124`), plus the Google Sign-In cluster (`:121-127`).
- `googleServices { missingGoogleServicesStrategy = WARN }` (`:85`) — the build currently compiles **without** `google-services.json`; enabling Auth/Firestore hard-requires it (and its per-app SHA-256 whitelisting for Android).
- No DataStore/WorkManager/accounts code exists yet; Room is the single store.

## Feasibility findings

1. **Firestore is the right backend shape and is nearly free to reach.** BOM + App Check + commented-out Firestore/Auth mean the dependency path is already paved; Firestore's **offline persistence is native** (local cache + queued writes + auto-sync; documents work with no connectivity — exactly the offline-first behaviour the app already has as a hard requirement), and it fits the domain model's shape: per-device bindings are separate documents, listening state is per-Edition documents, tombstones are first-class documents that survive re-sync (the missing piece that `deletedCatalogBookIds` and the spec-10 gap need — cf. `room-migration-risk-inventory.md` finding 3).
2. **The real cost is auth + provisioning, not the SDK.** Firestore requires authenticated clients:
   - Anonymous auth is the cheapest path (no sign-in UI, survives reinstall only via re-anon + merge, fine for «sync across MY devices»).
   - Google Sign-In needs the full commented-out cluster (`firebase-auth` + `androidx.credentials` + `googleid`) plus `google-services.json` and per-device SHA-256 registration.
   - App Check (already shipped) must be re-verified against the Firebase project when the app is registered — currently it ships in WARN-less default mode; enabling Firestore flips it from passive to blocking.
3. **Conflict model must be designed, not defaulted.** Firestore gives last-write-wins per document. The domain has two conflict surfaces: (a) per-Edition listening state (position, completed, bookmarks) — last-write-wins is actually *correct* here when keyed by `(editionId, deviceId, lastListenedAt)` and merged by timestamp; (b) library reconciliation (tombstones vs re-import) — requires tombstone-first merging, and the tombstone table must be in the *sync* schema, not only Room (it must survive reinstall and ride along with catalog refreshes).
4. **What must NOT sync:** audio files themselves (user's own files / 4read streams), device-local bindings beyond the device that owns them (per CONTEXT.md, bindings are device-specific by definition), and the library's file paths. The Room schema is currently book-centric (no Works/Edition separation) — see `room-migration-risk-inventory.md`: the schema work for sync is the risky part, and the guardrails there (exportSchema, no destructive fallback, migration tests) are prerequisites for any of this.
5. **Existing alternative considered and rejected for this app:** roll-your-own backend or WebDAV would recreate auth, storage, offline queues and conflict tooling that Firestore already provides; Drive API (from #35) is a backup transport, not a sync substrate (no per-document merging).

## Costed path (if/when a sync ticket opens)

| Step | Cost | Blockers |
|---|---|---|
| 1. Uncomment `firebase.firestore` + add `firebase.auth` (anonymous) | S | none code-wise |
| 2. Add `google-services.json` + enable App Check/Firestore in console | S | project owner action; SHA-256s for debug/release |
| 3. Migration guardrails first (Room v8, exportSchema, no-op throwing migration) | M | must precede any schema change |
| 4. Sync schema: `tombstones`, `listening_state(editionId, deviceId, ts)`, per-device `bindings`; Room side mirrors them | M | conflict merge tests required |
| 5. Offline queue + retry (Firestore native) + merge tests | M | none |

## Verdict

**FEASIBLE — GO with conditions.** The SDK path is 90% pre-wired; the hard
parts are auth provisioning (project owner action) and the Room migration
guardrails, not the backend itself. No decision is needed today — this ticket's
answer is «the backend exists, costs S-M of code, and its offline/conflict
behaviour fits the domain model», with #35 remaining the no-backend answer for
backup.
