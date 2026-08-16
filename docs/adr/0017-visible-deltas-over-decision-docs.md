---
status: accepted
---

# Visible deltas, not decision docs

The wayfinder map's standing preference was «Decisions, not deliverables» —
UI/UX tickets closed when a design question was answered, and implementation
was deferred to a later stage. By 2026-08-16 that pipeline had produced ~25
wayfinder tickets, four independent UX reviews, spec-27 (#184), and
ADR-0014..0016 — and a user who could not see the difference in the app they
held. The stage-1 redesigns (#38 player, #39 library, #40 book page) did ship,
but the decision-heavy sprint around them (universe, editions, collections,
feeds) was invisible, and the planned UX fixes stayed unexecuted. The
user-visible ratio of docs to change was what eroded trust, not any single
missing feature.

From now on a UI ticket closes only when its visible delta ships and is
verified on a device (per the `docs/phone-test/` convention). A grilling or
research ticket still closes with a decision — but the decision immediately
produces a delivery ticket with a concrete visible delta, and the two are
tracked separately. Sessions that touch the UI end by installing the build on
the user's device, so the difference is seen the same day, not discovered in a
release weeks later.

## Considered options

- **Keep decisions-first (status quo).** Rejected: decisions are not
  consumable by the user; the accumulation of docs with no app change is
  exactly the complaint that triggered this ADR (2026-08-16 grilling).
- **Ship-then-document.** Adopted: one phone-verified visible delta per
  session, docs follow the code instead of preceding it by days; deep design
  still happens first for genuinely hard-to-reverse choices (ADR-0014 keeps
  its role for those).

## Consequences

- Spec-27 (#184) becomes the immediate delivery backlog: P0 (destructive-delete
  confirmation, duplicate-Work merge) → P1 → P2, each item phone-verified on
  close.
- The wayfinder map's «Decisions, not deliverables» preference is superseded
  by this ADR.
