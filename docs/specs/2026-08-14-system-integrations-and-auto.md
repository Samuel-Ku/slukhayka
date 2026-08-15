# [Spec-21] System Integrations & Android Auto: MediaLibraryService & Home Widget

> **Status:** Draft — Stage 4 Roadmap Spec (System Integrations, Auto & Quick Access).
> **Scope:** MediaLibraryService transition for Android Auto, Jetpack Glance Home Screen Widget, Smart Sleep Timer enhancements (Shake-to-extend & End-of-chapter).

## Problem Statement

Currently, *Слухайка* provides playback and catalog browsing within the main Android UI through standard Compose activities and an in-process / background MediaSessionService. However:
1. **Automotive / Android Auto experience:** `MediaSessionService` alone does not support Android Auto hierarchical browsing (catalog, recent books, history) which requires `MediaLibraryService` and `MediaLibrarySession.Callback` implementation (`onGetLibraryRoot`, `onGetChildren`, `onGetItem`).
2. **Quick Access / Desktop Widget:** Listeners currently must open the full application to pause/play, jump 15 seconds, or see their current reading progress. An Android Glance-based Home Widget is absent.
3. **Bedtime / Sleep Timer Polish:** Listeners using the sleep timer often fall asleep mid-chapter or miss the ending of the timer. There is no "Stop at the end of current chapter" option, nor is there a low-friction "Shake device to extend timer" sensor gesture when the audio starts fading.

## Solution

1. **MediaLibraryService Upgrade:**
   - Migrate audio playback background service from `MediaSessionService` to `MediaLibraryService`.
   - Implement `MediaLibrarySession.Callback` supporting:
     - Root nodes (`ROOT_ID_RECENTS`, `ROOT_ID_CATALOG`).
     - Sub-nodes for "Продовжити слухати" (recent listening books) and "Обране" (favorites).
     - Full vehicle head-unit control and display of artwork, metadata, and progress.

2. **Jetpack Glance Home Screen Widget (`AudiobookGlanceWidget`):**
   - Provide a Material 3 interactive home screen widget (`4x2` and `2x2` responsive sizes).
   - Display: Book Cover thumbnail, Book Title, Chapter Title, Progress Bar, Play/Pause toggle, and Rewind 15s / Fast-Forward 15s action buttons.
   - Automatic live update upon state changes in `AudioPlayerManager` / `PlaybackProgress`.

3. **Smart Sleep Timer Suite:**
   - Add "До кінця розділу" (End of chapter) timer mode.
   - Implement audio volume ducking / fading in the final 30 seconds of timer expiration.
   - Accelerometer sensor listener for "Shake to extend (+15m)" during the fade-out window with subtle haptic feedback.

---

## User Stories

1. As a driver using Android Auto, I want to see my currently listening book and recent library on my car's dashboard, so I can safely resume playback with one tap.
2. As a driver, I want to browse my favorite books via my car's screen without picking up my phone.
3. As a listener, I want an Android Home Screen Widget, so I can instantly check my progress and play/pause without opening the app.
4. As a listener falling asleep, I want the option to stop playback exactly when the current chapter ends, so I don't lose my place in the story.
5. As a listener, when my sleep timer starts fading out, I want to gently shake my phone to add 15 more minutes without unlocking the screen.

---

## Implementation Decisions

### Track A: MediaLibraryService & Android Auto Support
- **Service Inheritance:** Convert `AudioPlayerService` from `MediaSessionService` to `MediaLibraryService`.
- **MediaLibrarySession:** Replace `MediaSession.Builder` with `MediaLibrarySession.Builder(this, player, callback)`.
- **Tree Structure:**
  - `ROOT_ID_ROOT` -> `[ROOT_ID_RECENTS, ROOT_ID_FAVORITES]`
  - `ROOT_ID_RECENTS` -> List of books with active progress (`MediaItem` with playable flag).
  - `ROOT_ID_FAVORITES` -> List of favorited books.
- **Auto Manifest Declaration:**
  - Declare `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc"/>`.

### Track B: Glance Home Widget
- **Framework:** Use `androidx.glance:glance-appwidget` and `androidx.glance:glance-material3`.
- **State Flow:** `GlanceAppWidgetReceiver` observes repository/player state and calls `AudiobookGlanceWidget().updateAll(context)`.
- **Actions:** Play/Pause (`PlayerAction.TogglePlay`), Skip 15s Back (`PlayerAction.Rewind15`), Skip 15s Forward (`PlayerAction.FastForward15`), and Tap on Card (`LaunchMainActivity`).

### Track C: Smart Sleep Timer & Accelerometer Shake
- **Sleep Timer Modes:** `Mode.Time(minutes)` vs `Mode.EndOfChapter`.
- **Fade Out:** When timer has ≤ 30s remaining, volume scales linearly `1.0f -> 0.0f`.
- **Shake Detection:** Use Android `SensorManager.SENSOR_DELAY_UI` for accelerometer readings (threshold ~13-15 m/s² delta). When shaken during fade out: resets volume to 1.0f and extends timer by 15 minutes + performs light haptic tick.

---

## Testing Decisions

- **MediaLibrarySession Tests:** Robolectric JVM tests verifying `onGetLibraryRoot` returns appropriate flags and `onGetChildren` yields playable MediaItems for recents and favorites.
- **Glance Widget Tests:** JVM unit test for widget state mapping (`PlayerState` -> `GlanceWidgetState`).
- **Sleep Timer & Sensor Tests:** Unit tests for chapter-end boundary calculation, volume interpolation over time, and shake sensor math threshold evaluation.
- **Compilation & Verification:** Standard `compile_applet` and `:app:testDebugUnitTest`.

---

## Out of Scope
- Full independent Wear OS standalone app (separate future spec).
- Custom car touchscreen keyboard search (uses voice assistant intent instead).
