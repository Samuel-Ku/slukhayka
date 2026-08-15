---
status: accepted
---

# One place applies Metadata Assertions

Metadata Assertions from sources were applied in four places, each
re-deriving the same edge rules: the explicit import door blanked nothing
(brand placeholders could be stored as-is), the catalog upsert handled the
legacy 14400 s sentinel and never-clobber itself, the on-demand chapter fetch
and the detail refresh each had their own chapter id/title conventions and
their own blank/cover/series handling. Four derivations of one rule is how
edge cases drift.

## Decision

A pure module, `MetadataAssertions`, owns every claim-application rule. It
has no DAO and no I/O — everything is a function of the claim, so the JVM
suite pins each boundary without Robolectric:

- **Claim normalization** — a claimed duration is unknown when blank, zero,
  or the legacy 14400 s sentinel; a claimed author/narrator is absent when
  blank or a brand placeholder («4read.org», «Аудиокнига 4read.org», «4read
  Voice Narrator») — the brand-scrub becomes a write-time step, applied in one
  place.
- **Book delta** (field precedence against the existing row) — never clobber
  a known duration with an unknown claim; series applies only when its URL
  changed (the URL is the membership signal); cover applies only when
  non-blank (and is never brand-scrubbed — cover URLs may legitimately live
  on a source's own domain).
- **Chapter materialization** — one id format (the dash format `<bookId>_ch_<n>`)
  and one title fallback for all new books; existing rows stay unmigrated.
  Duration convention: the claim's real duration survives normalization,
  anything unknown becomes 0 (unknown until played).

The four persistent application sites — explicit import, catalog upsert,
on-demand chapter fetch, detail refresh — are thin callers of these rules;
per-door insert defaults (id schemes, placeholder author/narrator, description
templates) stay at the doors.

## Consequences

- The legacy 14400 s sentinel is treated as unknown everywhere, including
  catalog upsert — it never renders as a real duration.
- Chapter rows created by any of the four sites are now identical in shape,
  so a concurrent fetch-then-insert dedupes via `@Insert(REPLACE)` instead of
  duplicating the whole list.
- Brand placeholder authors/narrators are scrubbed to absent at write time.
  The display-layer filter (`displayAuthor`) is retained as a display
  convenience for rows written before this module existed; **retirement
  condition**: drop `displayAuthor` (and any bulk SQL cleanup of placeholder
  values) once a stored-data audit shows zero rows whose author or narrator
  contains the brand placeholder — i.e. after this module has been the sole
  write path and the refresh/chapter-fetch sites have back-filled existing
  rows.
- The module is the future landing spot for Metadata Override precedence
  (CONTEXT.md: Override takes precedence over Assertion).
