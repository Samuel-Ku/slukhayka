/**
 * spec-43/T5 (pre-work engine slice) — the safe Media Session wrapper for
 * the Web Client: lock-screen / OS-level play-pause and track controls over
 * the engine's callbacks. A thin, degrade-never glue layer — on any browser
 * without `navigator.mediaSession` (or where an action is unsupported) it
 * is a no-op, never a crash. The T5 UI wiring calls it whenever now-playing
 * metadata changes.
 */

export interface NowPlayingMetadata {
  /** The Work's title. */
  title: string
  /** The Edition's narrator-facing author line. */
  author: string
  /** The current Chapter's title. */
  chapterTitle?: string
}

export interface MediaSessionHandlers {
  onPlay: () => void
  onPause: () => void
  onPreviousTrack: () => void
  onNextTrack: () => void
}

type MediaAction = 'play' | 'pause' | 'previoustrack' | 'nexttrack'

function hasMediaSessionSupport(): boolean {
  const nav = (globalThis as { navigator?: Navigator }).navigator
  return nav !== undefined && 'mediaSession' in nav
}

export function updateNowPlaying(metadata: NowPlayingMetadata, handlers: MediaSessionHandlers): void {
  if (!hasMediaSessionSupport()) return
  try {
    const session = (globalThis as { navigator?: Navigator }).navigator!.mediaSession
    if (typeof globalThis.MediaMetadata === 'function') {
      session.metadata = new globalThis.MediaMetadata({
        title: metadata.title,
        artist: metadata.author,
        album: metadata.chapterTitle,
      })
    }
    const bindings: Array<[MediaAction, () => void]> = [
      ['play', handlers.onPlay],
      ['pause', handlers.onPause],
      ['previoustrack', handlers.onPreviousTrack],
      ['nexttrack', handlers.onNextTrack],
    ]
    for (const [action, callback] of bindings) {
      try {
        session.setActionHandler(action, callback)
      } catch {
        // this action unsupported on this browser — the rest still wire
      }
    }
  } catch {
    // degrade-never: media session is a garnish, never a requirement
  }
}
