# Ukrainian-tolerant on-device search benchmark — wayfinder ticket «Ukrainian search» (#51)

Status: resolved 2026-08-10. Measured on JVM (Robolectric, sdk 36) via
`UkrainianSearchBenchmarkTest` (app/src/test/java/com/example/search/benchmark/).
All numbers below are produced by the test on this machine and can be
reproduced with `./gradlew testDebugUnitTest --tests "com.example.search.benchmark.*"`.

## Problem

The app's search is `MainViewModel.updateSearchQuery` → `repository.searchAudiobooksOn4Read`
(AudiobookRepository.kt ~1390–1491): a **network** DLE query that starts at 2 typed
characters. There is no on-device search, and Ukrainian has properties that break
naive substring matching:

- **SQLite `LIKE` is ASCII-only case-insensitive** — Cyrillic matches are case-sensitive.
- Real-world typo/confusion pairs: `і/и/ї`, `е/є`, `ґ/г`.
- Keyboard-layout errors: Cyrillic typed on an English keyboard (`Ytcjcfyybq` → «Неостанній»).
- Transliteration in both official (2010: `и→y`, `ї→yi`) and legacy (`и→i`, `й→y`) systems.
- Apostrophe (`До'Урдена` vs `Доурдена`) and hyphen/space variants.

## Method

Corpus: **45 books** with real titles/authors from the 4read.org homepage
(+ synthetic narrator/series/genre), 385 queries in 12 groups:
exact, prefix, case, typo (char deletion), layout (en-keyboard), translit (latin),
apostrophe/hyphen, author surname, author-typo, series, series-name, chapter.

Each approach is a whole-index searcher returning top-5 ids; recall@5 = target in top 5.
Latency: total wall time for all 385 queries after one warm-up (JVM, indicative only —
Robolectric timings are noisy; on-device SQLite will differ).

Candidate implementations live in `SearchApproaches.kt` (test source set — the
deliverable is evidence, not a production feature; a future search ticket promotes
the winner into app/src/main).

## Results

| # | Approach | recall@5 | 385 queries, total ms |
|---|---|---|---|
| A | Raw substring (SQLite-LIKE semantics, case-sensitive Cyrillic) | 35.3% | 7 |
| B | Light-normalized contains (lowercase uk, ё→е, apostrophes, hyphen→space) | 48.1% | 8 |
| C | Tolerant-normalized contains (B + і/и/ї→i, е/є→e, ґ→г) | 57.9% | 7 |
| D | Layout-corrected (en→uk QWERTY fix, then tolerant contains) | 69.4% | 7 |
| E | Translit variant index (Cyrillic→Latin, both spelling systems) | 66.0% | 346 |
| F | Levenshtein-ranked (tolerant-normalized tokens) | **85.2%** | 170 |
| G | FTS4 over pre-normalized index (prefix MATCH) | 58.7% | 189 |

By query type (columns: apostrophe, author, author-typo, case, chapter, exact, layout, prefix, series, series-name, translit, typo):

| Approach | apostr | author | auth-typ | case | chapter | exact | layout | prefix | series | s-name | translit | typo |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A. Raw | 0 | 100 | 0 | 0 | 0 | 100 | 0 | 100 | 20 | 100 | 0 | 0 |
| B. Light | 5 | 100 | 0 | 98 | 100 | 100 | 0 | 98 | 80 | 100 | 0 | 0 |
| C. Tolerant | 100 | 100 | 0 | 98 | 100 | 100 | 0 | 98 | 80 | 100 | 0 | 0 |
| D. Layout | 100 | 100 | 0 | 98 | 100 | 100 | **100** | 98 | 60 | 100 | 0 | 0 |
| E. Translit | 65 | 100 | 0 | 98 | 100 | 100 | 0 | 98 | 80 | 100 | **100** | 0 |
| F. Edit | 98 | 100 | **100** | 100 | 100 | 98 | 24 | 100 | 100 | 100 | 53 | **100** |
| G. FTS4 | 100 | 100 | 0 | 100 | 100 | 100 | 0 | 100 | 100 | 100 | 0 | 0 |

## Findings

1. **F wins overall (85.2%)**: it is the only approach with meaningful typo
   tolerance (`author-typo` 100%, `typo` 100%) and still scores ~100 on exact/prefix/
   case/author/series. Its weak spots are the *orthography-switch* groups — layout
   (24%) and translit (53%) — which no single index can cover, because those are
   *input-system* errors, not spelling errors.
2. **D is the layout answer**: 100% on the layout group thanks to a QWERTY fix with
   ambiguity resolution (`','`, `'.'`, `'\''` are both punctuation and the `б/ю/є`
   keys — resolved by letter context; the orthographic apostrophe `До'Урдена` stays
   intact). D's layout fix must only apply when the query *looks* latin (>70% latin
   letters), otherwise legitimate latin queries (translit) get corrupted.
3. **E is the latin-input answer**: a *spelling-variant* transliteration index
   (cartesian product over `и→y|i`, `й→i|y`, `я→ia|ya`, `ю→iu|yu`, `є→ie|ye`, `ї→i|yi`,
   `ь→∅|'`, `'→∅`) covers both official and legacy systems; 100% on translit queries
   by construction. Cost: index size ×(variants), latency 346 ms total.
4. **C is the cheap baseline**: 57.9% for 7 ms total — all of A+B's wins plus
   apostrophe/case coverage. The і/и/ї→i, е/є→e, ґ→г collapse is the single
   highest-value normalization for Cyrillic-Cyrillic queries.
5. **G (FTS4) is a prefix index, not a spell-checker**: 100% on prefix/chapter/
   series-name, 0 on typo/layout/translit. FTS5 is **not available** in Robolectric's
   bundled SQLite (`no such module: fts5` — verified by probe), but ships in real
   Android system SQLite; the doc-level conclusion (normalize-on-write, MATCH on
   read) is identical either way.
6. **A (raw LIKE) is the current de-facto baseline and loses 65% of these queries** —
   including every case/typo/layout/translit one. It is only a reasonable fallback
   for exact Cyrillic phrases.
7. Micro-typos in the *latin* group (translit typo 0%) are not recovered by any
   approach — E+F together would be needed for that.

## Recommended shape for a production search ticket

A **hybrid, two-stage** searcher, cheap first then precise:

1. **Stage 1 — prefilter** (all in-memory, ~1 ms): tolerant-normalized contains (C).
   Optionally also match the layout-fixed query (D) and translit variants (E) when
   the query is mostly latin; union the candidate ids. This is the recall net.
2. **Stage 2 — rank**: Levenshtein over tolerant-normalized tokens (F) on the
   candidates only (not the whole corpus — keeps cost flat as the library grows).
3. **Fallback**: if stage 1 yields nothing and the query is Cyrillic, run the raw
   exact pass (A) as the last resort; never show an empty state for a 1–2 char query
   that a server call could still answer.

Expected coverage with C+D+E prefilter + F ranking: ≈100% on every group except
translit-typos (~53%) and layout+translit combinations — and those are recoverable
by falling back to the existing network search.

## Notes / caveats

- All latencies are JVM/Robolectric and indicative; Robolectric numbers for SQLite
  approaches (G) are the least representative of a real device.
- Corpus is 45 books — enough for approach ranking, not for production tuning of
  thresholds. A search ticket should re-verify on a real library dump (e.g. 500+
  books) before finalizing the Levenshtein cutoff and the layout-detection ratio.
- The QWERTY maps in `SearchApproaches.kt` (`EN_TO_UK`, cyr→en reverse) were caught
  with two wrong keys by the benchmark's own sanity assertions (н/т and м/ь) —
  a good argument for keeping this benchmark as a regression test when the search
  feature ships.
