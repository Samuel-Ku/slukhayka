---
name: smart-library-sync-listening-intelligence
label: wayfinder:map
created: 2026-08-07
status: active
tracker: github-issues
map_issue: https://github.com/Samuel-Ku/slukhayka/issues/45
---

# Wayfinder Mirror — Smart Library, Sync & Listening Intelligence

> **The canonical artifact lives on the GitHub issue tracker.**
>
> Map: [Wayfinder map: Smart Library, Sync & Listening Intelligence](https://github.com/Samuel-Ku/slukhayka/issues/45)

## Destination

An implementation-ready product and domain specification for Stage 2: Smart Library, Sync & Listening Intelligence. The map covers the unified library foundation, automatic recognition, optional local-first multi-device sync, listening assistance, series, search, offline lifecycle, reliability diagnostics, and rule-based personalization; implementation starts only after the route is complete.

## Standing constraints

- All nine proposed areas are in scope, ordered as foundation → listening system → experience and operations.
- Resolved decisions from the mature-library map remain authoritative unless the unified model or cloud sync makes them incompatible.
- Canonical vocabulary lives in [`CONTEXT.md`](../../CONTEXT.md): Work, Edition, Source, Library Entry, and Listening State.
- Deterministic evidence may link a Source automatically; fuzzy metadata alone never silently merges Works or Editions. Corrections are remembered and synced.
- Sync covers state, metadata corrections, identities, and position history, but never audiobook files.
- The app remains fully usable offline and without an account; later sign-in merges rather than replaces local state.

## Decisions so far

- [Unified library invariants & state ownership](https://github.com/Samuel-Ku/slukhayka/issues/46) — stable Work/Edition/Source identities are separate from device-local Source Bindings; Library Entry is Work-level, Listening State and logical Chapters are Edition-level, and provenance, overrides, redirects, and tombstones preserve user intent.

## Current frontier

- [Room migration risk inventory](https://github.com/Samuel-Ku/slukhayka/issues/47) — current-schema and compatibility evidence.
- [Local media recognition evidence audit](https://github.com/Samuel-Ku/slukhayka/issues/48) — reliable MP3/M4B/SAF evidence.
- [Sync backend & optional-auth feasibility](https://github.com/Samuel-Ku/slukhayka/issues/49) — backend and anonymous-to-account options.
- [4read offline rights & source-stability audit](https://github.com/Samuel-Ku/slukhayka/issues/50) — permission and reconnectability boundary.
- [Ukrainian-tolerant search benchmark](https://github.com/Samuel-Ku/slukhayka/issues/51) — normalization, accuracy, latency, and index evidence.
- [Playback reliability observability audit](https://github.com/Samuel-Ku/slukhayka/issues/52) — current failure-observation gaps.
- [Sleep timer & bookmark upgrades](https://github.com/Samuel-Ku/slukhayka/issues/27) — reused inherited listening decision.
- [Listening event model & position-history semantics](https://github.com/Samuel-Ku/slukhayka/issues/53) — event ownership, causality, conflict recovery, and compaction.

## Blocked route

- [Identity matching & correction memory](https://github.com/Samuel-Ku/slukhayka/issues/54)
- [Import preview & corrections flow](https://github.com/Samuel-Ku/slukhayka/issues/29)
- [Safe unified-library migration & rollout](https://github.com/Samuel-Ku/slukhayka/issues/55)
- [Re-scan, duplicates & missing files](https://github.com/Samuel-Ku/slukhayka/issues/42)
- [Local-first sync protocol & conflict UX](https://github.com/Samuel-Ku/slukhayka/issues/56)
- [Series, cycles & alternate-edition behavior](https://github.com/Samuel-Ku/slukhayka/issues/57)
- [Global search information architecture & ranking](https://github.com/Samuel-Ku/slukhayka/issues/58)
- [Offline lifecycle & download manager policy](https://github.com/Samuel-Ku/slukhayka/issues/59)
- [Listening Intelligence completion](https://github.com/Samuel-Ku/slukhayka/issues/60)
- [Diagnostics, privacy & support report contract](https://github.com/Samuel-Ku/slukhayka/issues/61)
- [Rule-based personalized Listen screen](https://github.com/Samuel-Ku/slukhayka/issues/62)
- [Stage 2 release slices & acceptance gates](https://github.com/Samuel-Ku/slukhayka/issues/63)

## Fog and exclusions

The canonical map holds the current fog: backend-specific security, account recovery, retention, and operational monitoring. Audiobook-file sync, mandatory registration, non-Android clients, social/gamification, AI recommendations, transcripts, recaps, implementation, a custom equalizer, and deeper WebView dependence are outside this effort.
