# Phase 4 — Phone Test Plan

> Status: APK built (29 MB) and `./gradlew test` green. No device or
> AVD was attached to the workstation at execution time, so the on-device
> walkthrough below is a manual runbook for the reviewer.

## What we already verified

- `./gradlew assembleDebug` — green, APK at
  `app/build/outputs/apk/debug/app-debug.apk` (29 MB).
- `./gradlew test` — green. (Robolectric + screenshot tests in
  `app/src/test/`.)
- All Phase 2.5 CRITICAL/HIGH fixes applied — see
  `docs/audits/2026-07-30-static-and-agents.md` and the `bd85f7a` and
  `df56e71` commits.

## What needs a phone to verify

1. App launch + permission prompts.
2. Library + Explore tabs render and seed data loads.
3. Audio playback (ExoPlayer → archive.org HTTPS).
4. WebView inside the 4read.org tab — including the SEC-001..008
   lockdown path.
5. Bookmark add, sleep timer, mini-player controls.
6. Offline download path (per-chapter success).
7. PlayerDebugOverlay hidden in release builds, visible only in debug.

## Runbook

### 1. Plug a phone in (USB debugging on) or start an emulator

```bash
# workstation
adb devices -l
# expected: one device listed, "device" state, "unauthorized" -> tap Allow
```

### 2. Install the debug APK

```bash
./gradlew installDebug
# or, if device is connected but Gradle doesn't see it:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Start the app, capture launch

```bash
adb shell monkey -p com.example.myapplication -c android.intent.category.LAUNCHER 1
sleep 4
adb exec-out screencap -p > docs/phone-test/01_launch.png
```

### 4. Walk through the golden flow and screenshot at each step

| # | Step | Expected | Output file |
|---|------|----------|-------------|
| 01 | Launch | Library shows 9 seeded books | `01_launch.png` |
| 02 | Tap a book → BookDetail | Tabs "Chapters / Bookmarks" render | `02_book_detail.png` |
| 03 | Tap a chapter | ExoPlayer prepares archive.org URL | `03_player_loading.png` |
| 04 | Wait ~5 s | Audio plays, "AudioEngineMode: ExoPlayer" | `04_player_playing.png` |
| 05 | Tap "Bookmark" chip + save | Toast or dialog dismissed, bookmark appears on the Bookmarks tab | `05_bookmark_saved.png` |
| 06 | Tap "Sleep Timer" → 15 min | Sheet closes, chip shows "15m" in cyber green | `06_sleep_timer.png` |
| 07 | Swipe down to mini-bar | Mini bar visible above tabs (debug build) | `07_mini_bar.png` |
| 08 | Open the 4read.org tab | WebView loads 4read.org | `08_fourread_webview.png` |
| 09 | Trigger 4read import | Or paste a URL and tap "Завантажити" | `09_fourread_import.png` |
| 10 | Open Library → Downloaded | Empty state if no downloads | `10_library_downloads.png` |
| 11 | Trigger an offline download | Per-chapter progress, isDownloaded flag flips when complete | `11_offline_download.png` |

### 5. Verify the SEC-006 / CR-002 fixes by hand

#### a) Time-machine fallback is gone (CR-002)

- Disable network mid-play (airplane mode → attempt a chapter).
- Expected: `audioEngineMode = "Playback error"` and the Ukrainian
  message "Цю главу зараз не вдалося відтворити..." replaces the
  silent archive.org substitution.

#### b) User-CA cannot hijack 4read.org (SEC chain CR-004..007)

- Install a user CA (Magisk + MagiskTrustUserCerts, or a work-profile CA).
- Navigate inside the WebView.
- Expected: requests on `http://` URLs are blocked (cleartext off);
  requests on `https://` with the user-CA signed cert are refused with
  "недовірений сертифікат (можливий MITM)"; legitimate `https://` 4read.org
  loads fine.

#### c) Debug overlay is release-hidden (CR-003)

- `./gradlew assembleRelease` and install that APK.
- Expected: no bug icon in the top bar of the player.

### 6. Capture audio + network logs

```bash
adb logcat -d -t 200 \
  -s AudiobookRepo:* AudioPlayer:* FourReadWeb:* \
  | tee docs/phone-test/logcat.log
```

Look for:
- `AudioPlayerManager` start/stop logs.
- `AudiobookRepo` "No chapters ... refusing to fabricate placeholder audio"
  when 4read fetch returns empty.
- `FourReadWeb` "SSL error" / "Blocked non-http navigation" when probed.

## Result template

Once executed, drop the populated runbook into this folder:

```text
docs/phone-test/
├── 01_launch.png
├── ...
├── 11_offline_download.png
└── RESULT.md   (free-form report: pass/fail per step, screenshots cited)
```

If a step fails, attach the failure log and the screenshot to `RESULT.md`
before opening the next issue.

## Out-of-scope / deferred

- Accessibility audit (WCAG 2.2) — `MEDIUM-003` in the audit.
- Test coverage > 80% — `COVERAGE-001` in the audit (current ~5%).
- Repository split (CR-001 god-object) — deferred per the audit's
  Phase 3 deferred list.
- Replace archive.org time-machine URLs in ChapterEntity seed
  data — the audit misclassified these (they are intentional
  public-domain LibriVox picks, not the bug), no action.
