---
status: accepted
---

# The Web Client builds on the v1.4 canonical vocabulary from day one

The web-parity initiative («Web Client 1:1 з Android») starts while the
Android codebase is still migrating to the v1.4 canonical component
system (ADR-0033: one PosterCard, one CycleCard, one BookRow, one
MetadataChip, two-level headers, two canonical states, full EN
resources). ADR-0033 explicitly deferred the web: «the Web Client
inherits the same contracts in a later spec». This is that spec's
baseline decision, fixed during the 2026-09-06 grilling session
(evidence: `docs/audits/2026-09-06-web-parity-grill.md`).

## Decision

- **Build-on, not migrate-twice.** The web never grows its own second
  vocabulary and never migrates one: React equivalents of the canonical
  components (PosterCard, CycleCard, BookRow, MetadataChip, two-level
  section headers, two canonical empty/loading states) are the ONLY
  components new web screens may use, from the first ticket. The
  current ad-hoc web cards (`CatalogCardRow` and friends) are absorbed
  as their canonical equivalents are introduced — deleted, not kept
  beside, matching ADR-0033's «dead canonical components» rule.
- **Surface parity is the definition of «1:1».** Every Android screen
  and block exists on web with the same IA and the same component
  vocabulary. Mechanically impossible things (the Tor/proxy privacy
  route — ADR-0024, 4read's WebView session recovery — ADR-0026/0027)
  are recorded as honest platform deltas in the delivery spec's ledger,
  never hidden.
- **Progress Sync extends to Library Entries + Tombstones** after
  deliberate linking (the clause ADR-0023 reserved for a later spec);
  Metadata Overrides follow later. A Work hidden on Android must not
  resurrect in web's Огляд: tombstones anchor at the Work mergeKey,
  like canonical covers and reviews.
- **Link merge rule: one Work-relationship row.** The sync layer
  represents the listener's relationship to a Work as ONE row with
  state `entry | tombstone | none` (`{uid}_{mergeKey}`), resolved
  last-write-wins by server time with ties going to the tombstone —
  a deliberate hide can never lose to a stale favorite on a clock
  tie. The local rows of an unlinked browser union-merge into the
  account at linking. Library Entry and Tombstone remain the domain
  concepts; this is the sync representation only.
- **Person Bookmarks ride the same extension.** Both roles (authors
  and narrators) port to web with the same deterministic ids, stored
  in IndexedDB, useful without network; they enter Progress Sync in
  the same delivery as Library Entries — otherwise «Виконавці» and
  the author rails diverge across devices.
- **Recommendations mirror the Android split**: the same server
  Recommendation Profile is read only under Recommendation
  Participation (ADR-0030/0031); without it, local adaptation runs on
  the browser's own Listening State. No new privacy surface.
- **Offline = streaming-cache first** (service worker keeps recently
  played audio available offline); an explicit download manager is a
  separate later ticket.
- **iOS Safari (installed PWA) is the verification reference**;
  desktop Chromium is second. Every ticket closes with a device-verified
  visible delta (the spirit of ADR-0017).

## Rejected alternatives

- **Migrate existing web components to canonical ones later** —
  rejected: the web UI is ~1.6k lines; building on canonical parts once
  is cheaper than migrating twice, and «невідмінний» becomes literal at
  the visual layer from the first ticket.
- **Literal parity including the privacy route and WebView recovery** —
  rejected as physically impossible; honesty about deltas is the voice
  of the project (docs/voice.md), not a compromise of it.
- **Web-only component set tuned for browser idiom** — rejected: the
  whole point of the initiative is that a listener switching devices
  cannot tell which surface they are on.

## Consequences

- The web's `ui/` layer grows a `components/` canonical set whose
  contracts mirror the Android ones; divergent web variants are defects
  exactly like Android's were.
- The Progress Sync extension needs a Firestore collection and rules
  addition inside the existing App Check envelope; unlinked browsers
  stay write-nothing readers.
- Delivery order, per-surface tickets and the platform-delta ledger
  live in the web-parity delivery spec (descendant of this session).
