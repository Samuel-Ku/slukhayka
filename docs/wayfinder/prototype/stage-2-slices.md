# Stage 2 release slices & acceptance gates — wayfinder prototype (#63)

Status: prototype for «Stage 2 release slices & acceptance gates» (#63),
wayfinder map «Smart Library, Sync & Listening Intelligence» (#45). This is
the capstone: it converts the map's 20 resolved decisions (indexed in the
map's "Decisions so far") into independently shippable tracer slices.

**Standing rules for every slice:**
- **Additive only.** All schema changes are additive (new table / nullable
  column / back-fill) per the migration decision (#55); no destructive
  fallback, ever.
- **Local-only first.** Every slice is fully usable without an account and
  offline; sync (#56) is the one slice that adds optional account behavior
  and nothing else depends on it.
- **Gates are per-slice, on the Kover-gated CI** (the standing pipeline:
  assembleDebug + testDebugUnitTest + Kover gate) plus the shared device
  smoke (install previous APK → upgrade → verify).

## S1 — Identity & schema foundation (v10)

The additive bump that carries the unified-library metadata: `workId` /
`editionId` back-filled columns, `tombstones`, `corrections`, `series` +
`series_members`, `edition_settings`, `playback_failures.category`.

- **Migration gate**: migration tests on a real v9 DB for every change; a
  representative user-library fixture migrates losslessly (row counts, FKs,
  content hashes); schema export committed. **(#55)**
- **Offline/playback gate**: nothing reads the new columns yet; existing
  tests stay green unchanged. **(#46, #54)**

## S2 — Smart import: preview & re-scan (#29, #42)

scan → plan → confirm → apply; plan-based import, corrections, re-scan diff
(new / missing / moved / duplicate), tree-URI + fingerprint tracking,
relocation by hash-set match.

- **Test gate**: pure-JVM plan/diff tests; preview-then-apply equals the
  direct import when unedited; re-scan re-attach keeps progress/bookmarks.
- **Accessibility gate**: the preview and correction controls reach 48 dp
  targets; TalkBack labels on every interactive row.
- **Offline gate**: full flow works with no network (SAF walk + hashing +
  Room only).

## S3 — Series & continue (#57, #60 Q4)

series tables populated from posters, Work-level membership, generalized
`nextInSeries`, «Що далі?» on COMPLETED, missing-volume chips.

- **Test gate**: Work-level membership tests; next-not-completed rule; derived
  member states agree with the library cards.
- **Device gate**: the continuation block renders on the phone smoke.

## S4 — Grouped search (#58)

Grouped sections (library / chapters / online), two-stage hybrid ranker,
states matrix.

- **Test gate**: the #51 benchmark corpus stays a regression suite
  (recall@5); grouped-UI snapshots; the states matrix UI-tested.
- **Performance gate**: library group renders without network; ranker stays
  candidate-bounded.

## S5 — Offline lifecycle (#59)

Per-source download state machine, FIFO queue with Room-persisted state,
Range resume, backoff, free-space gate, size disclosure, auto-cleanup toggle.

- **Test gate**: state-machine transitions; interrupted-download resume; no
  data loss on process death; stream-only refusal stays.
- **Privacy gate**: never touches user files outside the three-level
  deletion contract; cache clear stays explicit.

## S6 — Listening intelligence & personalized Listen (#60, #62)

Rewind presets, edition settings, the «Автоматизації» screen, contextual
continue messages, `ListenComposer` blocks with reorder/hide/not-interested.

- **Test gate**: derived-state tests; toggle reversibility; composer
  snapshot; no hidden scoring (each block explains itself).
- **Accessibility gate**: the automation toggles and block controls pass the
  48 dp / TalkBack checks.

## S7 — Diagnostics & support report (#61)

`category` on the failure ledger, bounded FIFO retention, redaction at
export, the read-only diagnostic screen, «Слухайка звіт v1» exporter +
share sheet.

- **Test gate**: redaction (URLs → host/path) test; report-contract version
  test; no-upload invariant.
- **Privacy gate**: nothing leaves the device without the share sheet.

## S8 — Local-first sync (#49, #56)

Project-owner provisioning (`google-services.json`, App Check re-verify,
SHA-256s), anonymous-first Auth, Firestore mirror, per-document LWW merge,
pairing code, tombstone sync, the two review sheets.

- **Migration gate**: the v10 schema is the sync baseline; tombstones are
  durable from S1.
- **Test gate**: pure merge/LWW/tiebreak tests; device-pair flow; sign-out
  idempotence (re-sign-in re-merges); no-audio-upload invariant.
- **Privacy gate**: metadata-only by construction; deletion is explicit,
  two-step, never touches local audio.

## Dependency order & inherited-vs-rebuilt

Order: **S1 → (S2, S3, S4, S5) → S6 → S7 → S8** — S1 unblocks everything;
S2–S5 are independent after S1; S6 consumes S3/S5; S7 is independent; S8 is
last (optional, provisioned, and nothing else depends on it — the map's
constraint that the app stays fully useful without an account holds even if
S8 is skipped).

**Adapt, don't rebuild** (the ticket's last clause): the migration/test
convention, `MergeKey` (extend to tiers + corrections), the
`importAudioEntries` core (split into plan/apply), `PlaybackEventPolicy`
(reuse for failure retention), `LibraryModel.filterAndSort` (add
work/source grouping), `SmartRewind`/`SeekHistory`/per-book speed (expose as
presets), `searchAllSources` (group + rank through one ranker),
`downloadAudiobookOffline` (wrap the copy core in the state machine). The
repository-seam + pure-functions architecture was built for exactly this —
no subsystem needs a rebuild.

## Out of scope (of this prototype)

- Anything beyond the resolved decisions (editorial mood collections, AI
  recommendations, social features, transcripts) — ruled out by the map.
- S8's provisioning is a project-owner action and can be deferred without
  blocking any other slice.
