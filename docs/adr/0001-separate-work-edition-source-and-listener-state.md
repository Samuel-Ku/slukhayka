---
status: accepted
---

# Separate Work, Edition, Source, and listener state

The library uses separate identities for the abstract Work, its audiobook Edition, each playable Source, the listener's Work-level Library Entry, and Edition-level Listening State. This costs more relationships than the former all-in-one audiobook record, but prevents duplicate sources from becoming duplicate books, prevents incompatible narrations from sharing timestamps, and lets portable identity and user state sync while device-specific file access remains local.

## Consequences

Stable app-owned identities do not depend on provider URLs or file paths. External identifiers and fingerprints are aliases or evidence; merges preserve old identities as redirects, while removals use tombstones so refresh and sync cannot resurrect them. Logical Chapters belong to Editions, and Source Bindings carry device-specific locators, permissions, and availability.
