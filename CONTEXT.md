# 4read Audiobook Library

The domain describes a personal audiobook library that unifies works, audiobook renditions, available sources, and a listener's state across devices.

## Library identity

**Work**:
The abstract authored book, independent of narration, file format, provider, or device. It owns bibliographic identity and series membership.
_Avoid_: Book, audiobook, copy

**Edition**:
A specific audiobook rendition of a Work, distinguished by properties such as language, narrator, duration, and chapter structure.
_Avoid_: Version, copy, source

**Source**:
One way a device can access an Edition, such as a local folder, an M4B file, a 4read stream, or a downloaded copy.
_Avoid_: Edition, book

## Listener relationship

**Library Entry**:
A listener's relationship to a Work, including its library status and manually curated bibliographic metadata.
_Avoid_: Book, collection item

**Listening State**:
A listener's progress, bookmarks, and playback preferences for one Edition.
_Avoid_: Library Entry, playback progress
