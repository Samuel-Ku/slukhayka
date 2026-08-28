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

## Consequences

- Background failures gain enough bounded context to separate lifecycle,
  playback-service, audio-origin and Cast cases without identifying a book or
  listener.
- A restored backup carries the explicit choice instead of asking again.
- Without Firebase configuration the adapter is a no-op and the app keeps
  working locally.
