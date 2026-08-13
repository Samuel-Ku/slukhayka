# Smart import: preview & corrections — wayfinder prototype (#29)

Status: prototype for «Import preview & corrections flow» (#29), wayfinder map
«Smart Library, Sync & Listening Intelligence» (#45). Grounded in the current
import code (`importAudioEntries` — write-immediately copy+insert, folder
grouping, natural sort, contentHash dedupe; `LocalFolderScanner`; `sourceTreeUri`
for re-scan), the tiered identity policy from #54 (T0 auto / T1–T2 review,
corrections memory), and the sync correction model from #56.

**The one architectural change this prototype makes:** the import splits into
**scan → plan → confirm → apply**. Today nothing is previewable — files are
copied and rows written in a single pass. The plan is a pure, unit-testable
staging structure; nothing touches disk or Room until the user confirms.

## 1. The four steps (from the ticket)

**Step 1 — Source.** Three entries: «Вибрати папку» (existing
`OpenDocumentTree`), «Вибрати файли» (extend the existing single-file SAF
pick to multi-select), «Повторно просканувати папку» (re-scan of a
previously added folder via its stored `sourceTreeUri` — the #42 hook).
One sheet, no new screens.

**Step 2 — Scanning.** The SAF walk (`LocalFolderScanner`) is fast; the step
is a linear walk with a live counter — files found, audio files kept, folders
seen — and a Cancel that aborts the walk and discards the partial plan. No
progress bar gymnastics for a few thousand files; a determinate counter.

**Step 3 — Preview (the plan).** Nothing written yet. One screen listing the
planned books, each expandable: grouped files in natural order (1, 2, 3, 10 —
the existing rule carries through by construction), recognised title/author
(from folder name / file metadata when available, else "—"), series, cover,
duration. Merge *suggestions* from the #54 pipeline appear as review rows, not
silent merges.

**Step 4 — Corrections.** The four interactions, all in the plan (no
full-screen detours): **merge** two planned books into one, **split** a
planned book's chapters into two books, **reorder** tracks (natural sort is
the default; manual reorder overrides it for that book), **edit**
title/author/narrator/series + series volume number. Cover picking is a
v2 slice (§5).

## 2. The plan shape (prototype snippet)

The plan is a pure data structure — built from scanned entries, rendered by
the preview, consumed by apply. Each decision the user makes edits the plan;
the plan is what gets applied.

```
ImportPlan(
    source: SourceRef,            // FOLDER(uri) | FILES(uris) | RESCAN(treeUri)
    books: List<PlannedBook>,
    corrections: List<Correction> // from #54/#56: MERGE|SPLIT|NEVER_MATCH|FIELD
)

PlannedBook(
    title: String,                // recognised or edited; "" = needs attention
    author: String,               // "" = unknown, editable
    narrator: String = "",
    seriesTitle: String? = null,
    seriesIndex: Int? = null,
    cover: CoverRef? = null,      // null in v1 (see §5)
    chapters: List<PlannedChapter>,
    suggestion: MergeSuggestion?  // T1/T2 candidate from #54, if any
)

PlannedChapter(
    file: LocalAudioEntry,        // lazy stream — no bytes touched in preview
    title: String,                // natural-sorted by file name
    contentHash: String?,         // stream-hash computed in preview (no copy)
    durationSeconds: Long = 0     // 0 = unknown until probe/playback
)

MergeSuggestion(                  // from #54: candidate, never silent
    existingBookId: String,       // the library Work it would join
    tier: T0 | T1 | T2,           // T0 = same normalized MergeKey
    reason: String                // "диктор: X vs Y" — the differing field
)
```

Key properties:

- **No writes in preview.** Hashing for the duplicate check reads the stream
  (`MessageDigest`, same SHA-256 as today) but never copies; `apply` is the
  only writer, reusing the existing `copyUnlessDuplicate` + `insertLocalBook`
  internals. A user who backs out of the preview leaves zero trace.
- **Natural sort is the constructor, not a post-hoc fix.** `PlannedBook`
  chapters are built with the existing `compareNatural`; manual reorder only
  marks a per-book override. The "1, 2, 3, 10" rule cannot regress.
- **Grouping is the same rule as today** (root files → single-chapter books,
  subfolders → multi-chapter books by full relative path), so an import
  confirmed without edits behaves exactly like the current importer.
- **The plan is re-plannable.** Corrections mutate the plan in memory; the
  original scan result is kept so an "undo my edits" reset is one step.

## 3. Where the #54 tiers surface

- A planned book whose normalized key matches an existing Work exactly (T0)
  is *offered* as «З'єднати з книгою в бібліотеці» — never auto-merged
  silently (the map's hard constraint holds even for T0 at import time; the
  user's explicit choice becomes the remembered correction).
- T1/T2 candidates render as a "схоже" row with the differing field — accept
  (merge), reject (never-match), or ignore for now.
- Every merge/split/never-match the user makes in the preview is written as a
  correction (§4) so it is remembered and synced (#56) — re-importing the
  same folder never re-asks the same question.

## 4. Corrections → memory

| Preview action | Correction kind (#54/#56) | Effect |
|---|---|---|
| Confirm a T0/T1/T2 merge | `MERGE` | source attaches to the existing Work |
| Split a planned book | `SPLIT` | the fork gets a new identity + never-match pair |
| Reject a suggestion | `NEVER_MATCH` | candidate generation suppressed for the pair |
| Edit title/author/narrator/series | `FIELD` (userMade) | outranks derived on sync (#56 §6 Case B) |
| Set series volume number | `FIELD` (userMade) | feeds #57 series identity |

The corrections list rides inside `ImportPlan` and is written to the
`corrections` store at apply time — one write path, no orphan decisions.

## 5. Land now vs later

**In the v1 slice (the preview is real):** source sheet (folder/multi-file/
re-scan), scanning counter + cancel, the full preview screen (grouping,
natural order, names, merge suggestions), and the four core corrections —
merge, split, reorder, edit title/author/narrator/series + volume number.
This makes every import reviewable and non-destructive, and it unblocks #42
(re-scan can route through the same plan).

**v2 (later slices):** cover picking (photo picker + the cover-affinity work),
metadata recognition from file tags (Android `MediaMetadataRetriever` for
title/artist/duration — Android-side, needs a seam for JVM tests), and
duration probing in the preview (today's local books carry 0 until played —
the plan shows "—" honestly instead of guessing).

## 6. Test seams

- **Pure JVM**: plan building (scan result → `ImportPlan`), natural-sort
  invariant, corrections→memory mapping, re-plan/reset. Prior art:
  `MergeKey` / `PlaybackEventPolicy` tests.
- **Repository seam**: `apply(plan)` reuses the existing copy+insert core —
  the current `importAudioEntries` tests pin its behaviour, and an
  `apply(plan)`-on-confirm test proves preview-then-apply equals today's
  direct import when no edits were made.
- **UI**: the preview screen is Compose over the plan state — snapshot-tested
  like the existing screens, no network.

## Out of scope (of this prototype)

- Cover picking and tag-metadata recognition (v2, above).
- Voice notes, audio files in sync (metadata-only, #56), anything network.
- Automatic silent merging — the #54 constraint stays absolute.
