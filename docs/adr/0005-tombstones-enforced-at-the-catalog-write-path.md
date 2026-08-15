---
status: accepted
---

# Tombstones are enforced where catalog rows are written

A Tombstone prevents resurrection, so the enforcement belongs to the write path it guards: the catalog upsert. The persistence layer gains an insert-unless-tombstoned statement; the catalog upsert returns nothing for a tombstoned Work, and catalog fetches assemble their published lists from what actually landed. Explicit imports remain the deliberate resurrection door: they clear the Tombstone and insert. No read-side filtering exists or is needed — tombstoned Works have no row to read.

## Consequences

Catalog code no longer consults a tombstone set; a future fetch or upsert door is safe by construction because the rule is not in its interface. The DAO seam keeps two adapters (Room and the in-memory fake), so the fake mirrors the guard for JVM tests. Section publication filters on what survived the upsert, preserving today's behavior of not publishing emptied sections.
