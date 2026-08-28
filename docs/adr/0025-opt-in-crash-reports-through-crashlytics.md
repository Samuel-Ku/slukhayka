---
status: accepted
---

# Crash reports are opt-in and travel through Crashlytics

Слухайка uses Firebase Crashlytics for release fatal crashes and ANRs, but
automatic collection stays disabled until the listener explicitly consents.
After a real held failure, the next launch asks once whether to send that
report and future reports. Refusal is remembered; the choice remains
reversible in profile settings.

One app-owned `CrashReporting` boundary owns consent, pending-report actions
and bounded context. Callers submit only typed enums and booleans. The exact
custom-key allowlist is `app_visibility`, `playback_state`,
`playback_service`, `audio_origin` and `cast_active`. Listener identity,
Listening State, Work or Edition names, Source URLs, media names, positions
and arbitrary logs never cross the boundary. Debug builds never collect.

Android 11+ additionally inspects `ApplicationExitInfo` after restart. Only
a new actionable resource/signal exit while playback had been active becomes
the controlled non-fatal `UnexpectedPlaybackExit`; lifecycle exits, package
updates and user-requested stops are ignored. A persisted timestamp prevents
duplicate reporting. Older Android versions emit no substitute.

## Consequences

- A report never leaves the phone without an explicit choice.
- Enabling after an earlier refusal affects only future failures; held data is
  deleted before collection starts.
- Crash evidence can improve a later maintenance release without turning
  listening behavior into analytics.
- The Firebase adapter is replaceable and no screen or playback module may
  write arbitrary Crashlytics payloads.
