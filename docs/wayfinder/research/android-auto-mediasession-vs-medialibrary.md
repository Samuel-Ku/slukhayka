# Android Auto: MediaLibraryService — wayfinder ticket «Android Auto: MediaLibraryService» (#32)

Status: resolved 2026-08-07. Research verdict for migrating the player service to MediaLibraryService (stage 4: system integrations).

## The decision

**Migrate the app's Media3 `MediaSessionService` to `MediaLibraryService`** when stage 4 starts. The current MediaSessionService is the right base (play/pause/seek for lock screen, system notification, headphones) but exposes **no browsing tree** — Android Auto can control playback but cannot let the driver *browse* books and chapters.

## What MediaLibraryService adds over MediaSessionService

- `MediaLibraryService` **extends** `MediaSessionService`; `MediaLibrarySession` extends `MediaSession`. The only new surface is a hierarchical browse API (`MediaLibrarySession.Callback`).
- Clients (Android Auto's own UI) can then browse `Books → Chapters` before starting playback — the essential car-safe flow for an audiobook app.
- Keep a **single ExoPlayer instance** inside the service; wrap it in the `MediaLibrarySession`; the service hosts both the player lifecycle and the browse tree.

## Minimal API surface

| API | Role |
|---|---|
| `onGetLibraryRoot()` | Root node of the library tree (e.g. «Медіатека» → books) |
| `onGetChildren()` | Paginated children of a parent id (chapters given a book id) |
| `MediaBrowser` (client side) | Connects to the service, browses asynchronously |
| `MediaController` | Sends playback commands once an item is chosen |

Room-backed DAOs already expose books + chapters, so the browse tree maps 1:1 onto existing data — no schema work.

## Android 15/16/17 background-audio rules

- Playback must trace to a **clear user action** (a tap in the app or in the car UI). Programmatic background starts (alarms, broadcasts) hit `ForegroundServiceStartNotAllowedException` or system background limits.
- A media session started from a user tap is exempt under the media foreground-service rules. The current app already starts playback only from explicit UI actions, so this is satisfied — the migration must not add any auto-start path (e.g. no "play on app open").

## Migration steps (stage 4 ticket)

1. Extend `MediaLibraryService` instead of `MediaSessionService`; keep the single player.
2. Add `MediaLibrarySession` + callback implementing root/children from the Room DAOs (books → chapters, plus series grouping).
3. Wire the existing app UI to keep using the session as today (MediaController over the library session works the same).
4. Test: Android Auto emulator (Google Maps "Automotive OS" emulator image or desktop head-unit) + real car/AAWireless if available.

## Verdict

**GO** — the change is small (one service base class + a browse callback), reuses existing DAOs, and is a hard requirement for Android Auto browsing. It is stage-4 work; nothing before it blocks.

Sources: developer.android.com Media3 service guide (MediaLibraryService), androidx.media3 session reference.
