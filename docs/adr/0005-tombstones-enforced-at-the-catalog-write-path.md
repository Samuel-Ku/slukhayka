---
status: accepted
---

# Tombstones enforced at the catalog write path

Tombstone enforcement used to live as a tombstone-SET filter at each catalog
fetch site: the homepage sections, series pages, top-100 and related lists
each fetched the full tombstone id set and filtered their parsed books
against it. Six filters, one set, all re-consulting the same table — and the
catalog upsert itself had no guard, so any path that forgot the filter
resurrected a deleted Work.

## Decision

Tombstone enforcement moves into the persistence layer. The catalog upsert is
now guarded by an **insert-unless-tombstoned statement**: a single SQL INSERT
that lands only when no `tombstones` row exists for the book id, plus a
single-row `isBookTombstoned` check for an already-existing row. The upsert
returns **nothing** for a tombstoned Work.

Catalog fetches (homepage sections, series, top-100, related) assemble their
published lists from **what actually landed** — books whose upsert returned
nothing are dropped from the published row, and a section emptied by skips is
not published (matching today's behavior). No catalog fetch or upsert site
consults a tombstone set anymore; no read-side filtering is added.

Explicit imports remain the resurrection door: they clear the marker and
insert (unchanged).

## Consequences

- A deleted Work can never be resurrected by catalogue sync — the guard is at
  the write path, so forgetting a filter is impossible.
- The `getTombstoneBookIds` set query is no longer consumed by any fetch or
  upsert site; the in-memory DAO fake mirrors the guarded insert.
- Behavior is otherwise identical: sections emptied by skips are not
  published, explicit re-import resurrects.
