---
status: accepted
---

# One Smart Rewind rule serves in-session and across-restart resume

Smart Rewind (wayfinder #25) existed as two resume paths that each re-derived
the tiers and the boundary behavior: the in-session resume in the player
applied the rewind and clamped the target at zero, while the across-restart
resume in the UI layer recomputed the same tiers but skipped the rewind
entirely when the saved position was smaller than the rewind. Two derivations
of one rule is how boundaries drift.

## Decision

There is exactly one pure function, `SmartRewind.rewoundPositionMs(positionMs,
pauseDurationMs)`, sitting beside the existing tier logic. Both resume paths
call it and neither re-derives tiers or boundary behavior:

- **In-session resume** (player): target = `rewoundPositionMs(currentPos, now -
  pausedAt)`; an unchanged target (short pause / nothing to rewind) skips the
  seek.
- **Across-restart resume** (UI): start position = `rewoundPositionMs(savedPos,
  now - pausedAt)`.

Boundary semantics unify to clamp-at-zero: a position smaller than the rewind
rewinds to zero — the former restart path skipped the rewind there (the delta
is bounded by the 25 s max tier, so the clamp only ever costs at most the
rewind window). A future-dated pause (clock skew) produces a negative
duration, which the tier logic already treats as no pause.

The jump-threshold constant (`SEEK_JUMP_THRESHOLD_MS = 5 min`) moves to the
playback-event policy in the data layer as its canonical home; the player's
seek history reads it downward, removing the data-to-player import.

## Consequences

- One rule, two call sites — a tier or clamp change edits one function.
- Restart resume with a position smaller than the rewind now rewinds to zero
  instead of silently skipping (behavior change, pinned by table-driven tests:
  short pause → no rewind, position < rewind → 0, future-dated pause → no
  rewind).
- The pause marker stays dual (in-memory + persisted) with lockstep clearing —
  one pause never rewinds twice.
- Data-layer files no longer import the player package.
