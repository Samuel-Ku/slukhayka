---
status: accepted
---

# Crash reports wait for an explicit choice

Слухайка needs production crash evidence, especially when Android kills or
breaks background playback. Automatic reporting would solve that by quietly
sending data before the listener had a chance to decide. That is not an
acceptable default for this app.

## Decision

Crashlytics automatic collection is disabled in the manifest. A new install
starts at `UNDECIDED`. After the first fatal crash or ANR, Crashlytics keeps
the report on-device; the next launch asks once. `ALLOWED` sends that held
report and enables future collection. `DENIED` deletes held reports and does
not ask again. The same durable choice is reversible under «Приватність»;
enabling it there after a denial deletes anything old before enabling future
reports.

The reporting boundary accepts only a typed `CrashContext`. It maps to five
bounded keys: app visibility, playback state, playback-service state, local
or remote audio origin, and whether Cast is active. Listener identity,
Firebase UID, Listening State, Work/Edition or media names, Source URLs and
arbitrary logs do not fit through that boundary. Firebase Analytics is not
installed, so breadcrumbs cannot widen the payload indirectly.

Debug and test builds keep reporting disabled regardless of the stored
choice. The consent policy is tested through one fake reporting sink; Firebase
itself stays behind a thin adapter.

Android 11+ also stores the same five bounded facts plus the current app
version code and Android API in its process-state summary (a versioned payload
under Android's 128-byte limit). On the next
launch, the app reads the newest historical exit through a typed adapter. A
signal, low-memory, excessive-resource or dependency-death reason becomes the
fixed nonfatal `UnexpectedPlaybackExit` only when that summary says playback
was `PLAYING` or `BUFFERING`. Fatal crashes and ANRs already have their native
Crashlytics path; self/normal exits, user stops, permission/package changes
and all other reasons are ignored.

The nonfatal payload is closed: reason enum, status, bounded process
importance, RSS/PSS, app version code, Android API and the five summary facts.
The adapter never reads the exit description, trace, process name or any
listener/media data. A local-only timestamp plus SHA-256 of those bounded
fields prevents replay on later launches. This cursor is transient and is
therefore deliberately absent from Android backup allowlists. `UNDECIDED`
holds the nonfatal locally and opens the existing post-failure prompt;
`DENIED` remains silent. Android 10 and debug/test builds do not inspect exit
history.

## Consequences

- Background failures gain enough bounded context to separate lifecycle,
  playback-service, audio-origin and Cast cases without identifying a book or
  listener.
- A restored backup carries the explicit choice instead of asking again.
- Without Firebase configuration the adapter is a no-op and the app keeps
  working locally.
- Unexpected background stops become queryable without broadening the privacy
  boundary or introducing a second consent flow.
