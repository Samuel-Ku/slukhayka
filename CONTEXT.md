# 4read Audiobook Library

The domain describes a personal audiobook library that unifies works, audiobook renditions, available sources, and a listener's state across devices.

## Library identity

**Work**:
The abstract authored book, independent of narration, language, file format, provider, or device. It owns bibliographic identity and series membership but not listening progress.
_Avoid_: Book, audiobook, copy

**Edition**:
A specific audiobook rendition of one Work, distinguished by language, narrator, audio content, and logical chapter structure. Sources with materially different narration or chapter topology belong to different Editions.
_Avoid_: Version, copy, source

**Source**:
A provenance-bearing origin or copy through which one Edition may be played, such as a local folder, an M4B file, a 4read stream, or a downloaded copy. A Source can exist in the library even when it is not currently available on a device.
_Avoid_: Edition, book

**Source Binding**:
A device's locator, permission, and availability relationship to a Source. Bindings are device-specific even when the Source identity is shared.
_Avoid_: Source, download

**Chapter**:
An ordered logical subdivision of one Edition to which positions and bookmarks can be anchored, independent of how a Source divides its files.
_Avoid_: Track, file

**Series**:
A named bibliographic sequence or cycle containing ordered Works.
_Avoid_: Collection, shelf

**Series Membership**:
A Work's place in a Series, including its position when known. One Work may have more than one Series Membership.
_Avoid_: Series, volume file

## Metadata

**Metadata Assertion**:
A provenance-bearing claim about a Work, Edition, Chapter, or Series supplied by a provider, embedded tag, folder structure, or recognition process.
_Avoid_: Canonical metadata, truth

**Metadata Override**:
A listener-authored replacement for a metadata value. It takes precedence over Metadata Assertions and survives source refreshes and rescans.
_Avoid_: Metadata Assertion, corrected source data

## Listener relationship

**Library Entry**:
A listener's relationship to one Work, including library status and Metadata Overrides. Removing a Library Entry does not erase the underlying Work, Editions, or Sources.
_Avoid_: Book, collection item

**Listening State**:
A listener's progress, bookmarks, completion state, and playback preferences for one Edition. It is independent of the Source currently used to play that Edition.
_Avoid_: Library Entry, playback progress

**Tombstone**:
A durable record that a listener intentionally removed or rejected a relationship, preventing imports, catalog refreshes, or sync from silently recreating it.
_Avoid_: Missing Source, unavailable file

## Playback

**Smart Rewind**:
One pure rule (`SmartRewind.rewoundPositionMs`): the longer the pause, the further back playback resumes (tiers of 3/12/25 s), clamped at zero. It serves both the in-session resume (live engine position) and the across-restart resume (persisted Listening State), so the two paths can never drift (ADR-0003).
_Avoid_: per-path rewind logic

## Architecture

**Module reads**:
Screens read the five deep modules' flows and suspend functions directly — `module.flow.collectAsState(...)` for flows, `rememberCoroutineScope().launch { module.suspendFun(...) }` for actions. MainViewModel keeps only composition, navigation and orchestration; the pure download/import outcome messages and the resume start-position decision live in `ui.library` (`OutcomeMessages`, `computeResumeStart`) with JVM tests (ADR-0008).
_Avoid_: forwarding StateFlows, 1:1 ViewModel forwarders

**Works and Library Entries**:
`audiobooks` is ONE concept — the metadata of the user's copy. The Work identity (mergeKey, series) lives in `works` (the shared spec-23 table, #142), the Library Entry (isFavorite, createdAt, downloadProgress) in `library_entries` (one row per audiobooks row, `workId` linking to the Work), and the per-book speed in the Listening State row (`playback_progress.preferredSpeed`). DAO reads join all three and fill `@Ignore` projections on `AudiobookEntity` (via the `BookRow` projection), so the UI keeps reading one shaped row while the persisted columns are gone (ADR-0009).
_Avoid_: fused columns on audiobooks

**One Source seam, one HTTP transport**:
Captured-page import is a [SourceAdapter] capability — `parseCapturedPage(html, url)` with a "not mine" default; the WebView-pattern adapters (4read, sluhay) override it under one name and no import door downcasts to a concrete adapter. All HTTP goes through the shared [HttpFetcher]: it serves text (`getText`) and binary streams (`getStream`), and the offline download loop consumes the stream method — the offline user agent lives in the download policy beside the per-source header rules (ADR-0006).
_Avoid_: per-adapter captured-page methods, raw HttpURLConnection in modules

**Bibliographic Work, Edition-owned narrator**:
The Work is `title|author` — the mergeKey carries NO narrator. The narrator is an Edition (rendition) property: it lives on `editions` and on the audiobooks row (the user's copy of one rendition), never on `works`. The Edition id hashes `mergeKey|narrator|language`, so two narrations of one Work never share listening state (ADR-0001) even though they share the Work (ADR-0010).
_Avoid_: narrator in the mergeKey, narrator on the Works row

**Multi-Edition library**:
Import dedup is per RENDITION (Edition id), not per Work — the same narration merges into its card, a DIFFERENT narration of the same Work creates a NEW card of that Work (own audiobooks row, own Library Entry under the same `workId`, own Edition/chapters/sources/tracks/progress). The book page lists the other rendition cards in the «Інші начитки» block via the pure, JVM-tested `siblingNarrations` (ADR-0011). No schema change — the v16 split already allowed several entries per Work.
_Avoid_: one card per Work with unreachable second narrations

**Smart collections**:
The «Колекції» Огляд block is curated external lists (Нобелівські лауреати, Шевченківська премія, Букер) shipped as static JSON assets (`assets/collections/`), matched locally against the catalog union. The strict decoder (`CollectionJson`) and the matcher (`CollectionMatcher`) are pure JVM; the matcher reuses the MergeKey normalization plus diacritics (Cyrillic-preserving) and parenthetical-annotation trimming, requires author agreement, and hides non-matches (author-only fallback for title-less entries). `SourceCatalog.smartCollections` is recomputed on the SAME trigger as the union (`refreshUnifiedCatalog`); empty collections are dropped, nothing is persisted — no schema change (ADR-0012).
_Avoid_: network lists, Room persistence of match results
