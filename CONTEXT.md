# Слухайка — Audiobook Library

The domain describes a personal audiobook library that unifies works, audiobook renditions, available sources, and a listener's state across devices.

## Library identity

**Work**:
The abstract authored book, independent of narration, language, file format, provider, or device. It owns bibliographic identity and series membership but not listening progress.
_Avoid_: Book, audiobook, copy

**Edition**:
A specific audiobook rendition of one Work, distinguished by language, narrator, audio content, and logical chapter structure; it owns the logical Chapter list and the listening totals (chapter count, duration). Sources with materially different narration or chapter topology belong to different Editions, and every Listening State row anchors to exactly one Edition.
_Avoid_: Version, copy, source

**Source**:
A provenance-bearing origin or copy through which one Edition may be played, such as a local folder, an M4B file, a 4read stream, or a downloaded copy. A Source can exist in the library even when it is not currently available on a device. A Source owns the physical tracks of its Edition — stream URLs, local file copies, content hashes, download state — aligned with logical Chapters one-to-one by index.
_Avoid_: Edition, book

**Source Binding**:
A device's locator, permission, and availability relationship to a Source. Bindings are device-specific even when the Source identity is shared. No Binding rows exist yet — with a single device, locator and permission stay on the Source row; the Binding table arrives with device sync, not before.
_Avoid_: Source, download

**Source Catalog**:
The union of browseable Works a Source exposes — sections, genres, series listings, people — fetched as Metadata Assertions on demand rather than stored wholesale.
_Avoid_: Store, browse cache

**Chapter**:
An ordered logical subdivision of one Edition to which positions and bookmarks can be anchored, independent of how a Source divides its files. A Chapter row carries order, title, and duration only — stream URLs, file paths, and content hashes belong to Source tracks.
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
A listener's progress, bookmarks, completion state, and playback preferences for one Edition. It is independent of the Source currently used to play that Edition — its row is keyed by Edition alone, so a Source switch never forks progress.
_Avoid_: Library Entry, playback progress

**Smart Rewind**:
The Listening State rule that a resume position steps back by how long the pause lasted. One rule serves both in-session resume and resume across restarts.
_Avoid_: sleep timer, resume offset

**Tombstone**:
A durable record that a listener intentionally removed or rejected a relationship, preventing imports, catalog refreshes, or sync from silently recreating it. Its identity anchors at the Work: one tombstone blocks every Edition and Source of that Work.
_Avoid_: Missing Source, unavailable file
