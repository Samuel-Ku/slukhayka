---
status: accepted
---

# Self-healing stream URLs (spec-32 US-5, #234)

A stream that dies mid-playback with HTTP 404/403 — the file moved, the CDN
blocked it, the cached link went stale — used to end the chapter with a
generic «Цю главу зараз не вдалося відтворити». The source page was already
in the library's import path, and the shared profile base (spec-32) already
refreshes on every page resolution (T2 #232). The missing piece was a player
that heals itself: re-resolve the page, swap in the fresh URL, retry once —
and say so honestly when the file is really gone.

## Decision

A 404/403 stream failure triggers **one** background heal per user-initiated
chapter prepare:

1. **Decision** — `StreamHealPolicy` (pure, JVM-tested): only 404/403 heal;
   the budget is `MAX_HEAL_ATTEMPTS = 1`. `responseCodeOf` walks the
   PlaybackException cause chain to the `InvalidResponseCodeException`, so the
   wrapped real-ExoPlayer failure still yields its status.
2. **Door** — `LibraryImport.refreshStreamUrl(bookId, chapterIndex,
   failedUrl)` re-fetches the source page, verifies the pairing, swaps the
   fresh URL into the PRIMARY source's physical track row (ADR-0007: track,
   never logical chapter), and writes the resolved page back to the shared
   profile (T2 #232 semantics, keyed by the STORED source id) best-effort.
3. **Retry** — the player re-prepares the same chapter from the last known
   position with the fresh URL. The heal re-prepare spends the budget
   (`resetHealBudget = false`), so a dead fresh URL cannot loop.
4. **Honest end state** — a 404/403 that spent the budget, a heal that
   yields nothing, or a page that yields the same URL reports
   `STREAM_HEAL_FAILED` with «Книга зараз недоступна: файл джерела переїхав
   або заблокований, і оновити його не вдалося» — no fabricated retry, no
   substitute audio (the CR-002 contract stands).

The heal gate mirrors `buildMediaItem`'s source decision: a track whose local
file is stale falls back to the network stream and MAY heal; pure local
playback never re-fetches a page.

## Why this shape

- **The index pairing is guarded.** The door heals by index only while the
  fresh page still serves every other chapter's URL at its own index. A
  REORDERED page keeps the old URLs — just misplaced — and healing by index
  would play the wrong chapter's audio under the failed one's title; a bulk
  move (every URL replaced in place) still heals.
- **The decision is a pure function** (spec-32 Testing Decisions): the
  404/403 → refetch → retry policy is JVM-tested in isolation; the wiring is
  Robolectric-tested at the player seam (fake healer lambda) and at the door
  (in-memory Room).
- **Profile refresh is conditional.** The door writes the refreshed profile
  only when the healed URL actually differs — re-stamping a page that still
  serves the dead link would roll freshness on a broken document (T2/T3
  write-back semantics).

## Considered options

- **Heal on any HTTP error.** Rejected: 5xx/timeouts are transient; retrying
  once with the same URL after a server error just delays the honest
  failure. Only 404/403 — evidence the URL itself is wrong — heal.
- **Unbounded heal loop.** Rejected: a permanently dead file would re-fetch
  the page on every error. The budget (one per user-initiated prepare, reset
  by any manual prepare/load) bounds the damage; the player never loops.
- **Heal by matching the failed URL anywhere in the fresh page.** Rejected:
  the pairing is chapter→track by index (ADR-0007); a URL match elsewhere in
  the page is exactly the reorder case the guard refuses to heal.

## Follow-ups

- **Device smoke test** (spec-32 Testing Decisions: «the end-to-end retry is
  verified by a device smoke test»). The current device smoke
  (`AudioPlaybackEspressoTest`) drives real CDN playback and cannot force a
  404. A heal smoke needs a local controllable HTTP server that 404s the
  stream once and serves a fresh page on the heal re-fetch — tracked as a
  separate device-test ticket.