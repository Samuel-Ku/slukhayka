# Phase 4 — Phone Test Result

> Executed: 2026-08-06 against the background-playback build (MediaSessionService
> + app-scoped player, `startService` FGS fix, MediaController fix). Devices:
> **OnePlus 8 Pro** (IN2023), Android 14 (SDK 34), wireless ADB; **Xiaomi Mi 11**
> (M2012K11AG), Android 13 (SDK 33), wireless ADB (install blocked by MIUI
> "Install via USB" toggle — not an app defect).

## Environment notes (blocked streaming)

1. **Tailscale VPN** on the phone was routing the app's traffic (uid 10517 is
   inside the VPN's per-app range) through MagicDNS with `PrivateDnsBroken`,
   which produced `UnknownHostException` for the app's requests (4read.org AND
   archive.org), while shell `ping` worked. Excluding the app from Tailscale
   fixed the app's DNS/fetch path. This is a device-environment issue, not an
   app bug.
2. **archive.org is unreachable from the home network** (workstation curl to
   `ia800201.us.archive.org` times out too) — the seeded LibriVox streams
   cannot be validated from this location.
3. **reasd.org (4read CDN) streams work** — `HTTP 200`, Range `206` supported;
   verified playing on the phone (see below).

## Verified PASS

| # | State | Evidence |
|---|-------|----------|
| 01 | App installs + launches (Explore renders books) | `oneplus_01_launch.png` |
| 02 | `PlaybackService` starts and its `MediaSession` registers with the system | `dumpsys media_session` shows `com.aistudio.audiobook.read/androidx.media3.session.id./NN` in the stack, `active=true` |
| 03 | **Real stream plays** (`https://s1.reasd.org/7810/...mp3`, PLAYING, position advancing) | debug overlay `STATE: PLAYING`, position 00:05 → advancing |
| 04 | **Background playback works**: HOME pressed, app in launcher, audio keeps playing | position 33 636 → 39 644 ms after 8 s → 188 773 ms after 130 s |
| 05 | **Service runs in FOREGROUND with media notification** (the fix) | `dumpsys activity services`: `isForeground=true foregroundId=1001 types=00000002`, `startForegroundCount=2`, notification `category=transport groupKey=media3_group_key actions=2` |
| 06 | **Service survives > 2 min in background** (previously killed at ~90 s by `Stopping service due to app idle`) | process alive at 130 s, no app-idle kill in logcat |
| 07 | **No crash** on slow/blocked stream (previously `ForegroundServiceDidNotStartInTimeException` killed the app after ~30 s of buffering) | logcat has no FATAL after the `startService` fix |
| 08 | Honest error/loading states (no silent fake audio) | debug overlay: `STATE: BUFFERING` / `Playback error` paths intact |
| 09 | 34/34 JVM unit tests green, incl. updated single-player `AudioPlayerManagerTest` | `testDebugUnitTest` |

## Critical bug found & fixed this cycle: Media3 1.3.1 requires a MediaController

`MediaNotificationManager.shouldRunInForeground()` (decompiled from
`media3-session-1.3.1.aar`) returns `false` when **no MediaController is
connected** to the session — even with `startForegroundService`. With the app
driving the Player directly (no controller), the service never called
`startForeground()`: no notification, and the system killed the service after
~90 s with `Stopping service due to app idle` while playback was PLAYING.

**Fix**: `AudioPlayerManager` now connects a background `MediaController` to the
`PlaybackService` session via `SessionToken` on every play path
(`ensureMediaControllerConnected()`). The UI still drives the shared Player
directly; the controller's presence alone unlocks the foreground service +
notification, and exposes the session to Android Auto / media buttons.
Released in `release()`.

Verified on device: `startForegroundCount` 0 → 2, `isForeground=true`, media
notification present, service survives 130+ s in background.

## Found on device (bugs/issues)

1. **[REAL BUG, fixed]** `AudiobookRepository.getChaptersList` treated the
   intentionally-seeded archive.org chapters as placeholders and re-inserted
   the live 4read page's chapters on **every** play / detail-open: one
   6-chapter seed book accumulated **54 chapter rows**, chapter order got
   scrambled. Fixed: only fetch the live page when the book has zero chapters.
2. **[FIXED]** MediaController requirement (above) — the actual reason
   background playback was not surviving.
3. **Network environment** (see above) — Tailscale DNS + archive.org blocked
   from this LAN. Not app defects.

## Not yet verified

- Notification transport buttons (play/pause/next) taps — notification posted
  with `actions=2`, button taps untested.
- Swipe-app-away keeps playback (documented trade-off: process kill ends
  playback; the critical backgrounded-while-alive scenario is fixed).
- Xiaomi Mi 11 install — blocked by MIUI "Install via USB" (requires Mi
  account); `adb pair` + connect verified working.

## Runbook commands used

```bash
adb pair <ip>:<pairing_port> <code>          # Android 11+ wireless debugging
adb connect <ip>:<connect_port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.aistudio.audiobook.read -c android.intent.category.LAUNCHER 1
adb shell uiautomator dump /sdcard/ui.xml    # + ui_helpers.py (text -> bounds)
adb shell dumpsys media_session              # session state
adb shell dumpsys activity services          # service foreground + notification
adb shell dumpsys notification --noredact    # notification records
adb exec-out screencap -p > screenshots/NN.png
```

Screenshots: `docs/phone-test/screenshots/{oneplus_01_launch,...,oneplus_08_fg_notification}.png`
