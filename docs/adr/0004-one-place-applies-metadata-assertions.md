---
status: accepted
---

# One place applies Metadata Assertions

Source claims about a Work (author, narrator, genre, rating, series, duration, cover, chapters) are applied to library rows in one pure module. It owns claim normalization (blank, zero, and the legacy 14400 s sentinel are "absent", not values), the field-precedence delta against the existing row (never clobber a known duration with an unknown; series only when its URL changed), and chapter materialization (one chapter-id format, one title fallback, duration unknown until first play). Callers — the import doors and the catalog module — apply the returned delta and materialized chapters through the DAO; per-door insert defaults (import fallbacks vs blank catalog seed) stay at the doors, because they are deliberate variation, not duplicated knowledge. Feed-item enrichment is out of scope: it enriches an in-flight item, not a library row.

## Consequences

Chapter identity for new books unifies on the dash format; existing rows are not migrated (imports materialize chapters only for new Works; the refresh path inserts only when the row has none, so REPLACE-by-id collisions cannot arise). The brand-scrub rules become a normalization step inside the module; the startup SQL cleanup stays until the minimum supported version predates write-time scrub no longer holds. The module is the designated landing spot for Metadata Override precedence over Metadata Assertions — signature anticipates it, implementation does not.
