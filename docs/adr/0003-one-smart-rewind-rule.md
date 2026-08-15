---
status: accepted
---

# One Smart Rewind rule

Smart Rewind is one rule, applied through one pure function beside the existing tier logic in the player's rewind module: given a position and a pause marker, return the rewound position. Both call sites — in-session resume (works from the live engine position) and resume across restart (works from the persisted Listening State row) — call it; neither re-derives tiers or boundary behavior. Boundary semantics are unified to clamp-at-zero: a position smaller than the rewind rewinds to zero. The former restart path skipped the rewind in that case; the delta is bounded by the 25 s maximum tier.

## Consequences

The pause marker stays deliberately dual — in-memory in the player for latency-free in-session reads, persisted on the Listening State row for restarts — with lockstep clearing so one pause never rewinds twice. The jump threshold constant ("what counts as a seek jump") moves to the playback-event policy in the data layer as its canonical home; the player's seek history reads it downward, fixing the former data-to-player import. A PlaybackStateStore-style interface is not introduced until a second adapter for Listening State exists (per ADR-0002).
