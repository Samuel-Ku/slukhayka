# Global search: IA, ranking & states — wayfinder prototype (#58)

Status: prototype for «Global search information architecture & ranking» (#58),
wayfinder map «Smart Library, Sync & Listening Intelligence» (#45). Grounded
in the current search (`searchAllSources` — per-adapter search + feed-filter,
merged by `MergeKey` into Work cards), the #51 benchmark (winner: two-stage
hybrid — tolerant-normalized prefilter + Levenshtein ranking; layout/translit
lanes for latin-looking queries; raw exact as last resort), #54 identity
tiers, and #56 corrections.

## 1. Result groups — one box, grouped results

The search screen is one text field; results render as **sections**, not a
flat list:

```
«Тарас Шевченко»
────────────────────────────
У БІБЛІОТЕЦІ (2)          ← instant, on-device
  [Кобзар]            Локальна
  [Кобзар]            4read · Sound-Books
РОЗДІЛИ (1)               ← chapter-title matches, library books only
  [Кобзар — Глава 3 «Сон»] → plays from that chapter
ОНЛАЙН (3)                ← remote, fills after fetch
  [Кобзар]           Sluhay
  [Кобзар]           4read
  [Кобзар (Том 2)]   4read
```

- **«У бібліотеці»** — the personal library, Work cards deduped by `MergeKey`
  (existing rule), with source badges; instant from an in-memory index.
- **«Розділи»** — chapter-title matches inside library Works. A matched
  chapter chip on the card; tapping deep-links into the book at that chapter
  (the existing `selectChapter` path). Remote sources have no chapter index —
  this group is library-only.
- **«Онлайн»** — remote matches from the adapters (`searchAllSources` today),
  one card per Work with per-source badges; never merged with the library
  group silently — a book present both locally and online appears in both
  groups, honestly labelled. Cross-group collapse would hide the «in my
  library» fact the user is checking for.

## 2. Ranking — the #51 two-stage hybrid, library-first

**Stage 1 prefilter (recall net, ~1 ms):** tolerant-normalized contains (the
#51 approach C — lowercase, `і/и/ї→i`, `е/є→e`, `ґ→г`, apostrophe/hyphen
collapse) over title/author/narrator/series + chapter titles. When the query
is mostly latin (>70 % latin letters), also union the layout-fixed lane
(QWERTY `en→uk`, D) and the transliteration-variant lane (E) candidates.

**Stage 2 rank:** Levenshtein over tolerant-normalized tokens (F) **on the
candidates only** — cost stays flat as the library grows.

**Fallback:** if stage 1 yields nothing and the query is Cyrillic, run the
raw exact pass (A); never show an empty state for a 1–2 char query that a
server call could still answer. Deterministic tiebreak by title
(case-insensitive), then by source count (more sources = more evidence, #54).

The same matcher runs on the library first (instant), then on the remote
results once fetched — both sides use one pure ranking function
(`SearchRanker`), unit-tested against the #51 benchmark corpus as a
regression suite.

## 3. Loading states — library never waits on the network

- Library group renders from the in-memory index immediately (no spinner).
- Each remote group shows its own thin loading row, then fills; a failed
  remote group shows «не вдалося завантажити · повторити» and **never**
  blocks or delays the library results.
- Debounce + the existing 2-character threshold stay; a blank query renders
  the search landing (recent library, quick genres) instead of a dead box.

## 4. The key states (the prototype's states matrix)

| State | Behaviour |
|---|---|
| **Empty query** | Search landing: recent listens + quick genre chips — never a blank page |
| **Partial (1–2 chars)** | Never empty: the #51 fallback runs the exact pass, then the remote call; result count shows "все ще друкується" |
| **No results** | «Нічого не знайдено» + two CTAs: «Шукати в інтернеті» (the remote adapters beyond the merged groups) and «Імпортувати з пристрою» (the #29 flow) |
| **Typo** | Levenshtein recovers on the library; on remote, a «можливо, ви мали на увазі» suggestion from the same ranker before the empty state |
| **Offline** | Library group fully works (in-memory); remote groups show «офлайн — повторіть пізніше»; downloaded books remain searchable and playable |

## 5. Correction affordances

Search itself stays minimal: a subtle «схоже» tag on T1/T2 fuzzy remote
matches (the #54 tier, never a silent merge). Merge/split/never-match
corrections live where they belong — the import preview (#29) and the
book-detail merge affordance — and ride the #56 correction store. A
«never-match» decided there suppresses the pair in search results too, so
the correction memory is honoured everywhere.

## 6. Test seams

- **Pure JVM**: `SearchRanker` (prefilter lanes + Levenshtein + fallback) —
  prior art: the #51 `SearchApproaches.kt` benchmark, kept as a regression
  suite; the grouping/merge logic extends `mergeGlobalSearchResults`.
- **Repository seam**: `searchAllSources` gains the grouped shape; the
  existing adapter-seam tests pin the remote half.
- **UI**: the grouped search screen is Compose over the grouped state —
  snapshot-tested like the existing screens; the states matrix (empty,
  partial, typo, offline) is the UI-test surface.

## Out of scope (of this prototype)

- FTS4/FTS5 index work — the #51 doc concluded normalize-on-write + in-memory
  two-stage is the shape; SQLite FTS can come later if the corpus outgrows it.
- Search analytics, personalization ranking signals (that is #62), voice
  search, and anything network-heavy beyond the existing adapter seam.
