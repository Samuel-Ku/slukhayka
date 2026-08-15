---
status: accepted
---

# Editions own Chapters; Sources own tracks

The library gains the Edition as a real row, keyed by Work plus narrator and language, owning the logical Chapter list (order, title, duration) and the listening totals. A Source is a copy of one Edition and owns the physical tracks — stream URLs, local file copies, content hashes, download state — aligned with logical Chapters one-to-one by index until a per-source chapter topology is ever needed. Listening State, bookmarks, and playback preferences anchor to the Edition alone, so switching Source inside one Edition no longer forks progress; the sourceKey derived from the book row's current URL is deleted. The first migration creates exactly one Edition per existing book (identity is its mergeKey — or the book id when the mergeKey is blank — plus an unknown language), keeps chapter ids unchanged, and keeps the audiobook row as the fused Work/Library Entry until a follow-up split. Source Bindings stay deferred: with a single device, locator and permission live on the Source row; the Binding table lands with device sync. Tombstone identity anchors at the Work and blocks every Edition and Source of that Work.

## Consequences

Chapter rows lose streamUrl/localFilePath/isDownloaded/contentHash to a source_tracks row; local imports become Sources of type local whose tracks point at the copied files; downloads mutate tracks, never chapters. playback_events keep their sourceKey column as history and stop recording SOURCE_SWITCH, since an Edition's key cannot change mid-listen. A second Source of the same Edition stores its own tracks without touching the Chapter list. Different narrations remain separate Works until Work-merge policy revisits the mergeKey — this step deliberately does not change Work identity. The Metadata Assertions module materializes both lists: logical chapters for an Edition from its first Source, tracks for every Source. The `editions` name is reserved for renditions: per-source browse rows in any catalog layer are Sources, not Editions.
