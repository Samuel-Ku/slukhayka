package com.slukhayka.audiobooks.data.diagnostics

interface PlaybackActivityMarker {
    fun setPlaybackActive(active: Boolean)
}

data class PlaybackDiagnosticSnapshot(
    val state: DiagnosticPlaybackState,
    val audioOrigin: DiagnosticAudioOrigin,
    val castActive: Boolean
) {
    val active: Boolean
        get() = state == DiagnosticPlaybackState.PLAYING || state == DiagnosticPlaybackState.BUFFERING
}

/** App-scoped owner of truthful, bounded crash context. */
class CrashContextTracker(
    private val reporting: CrashReporting,
    private val playbackMarker: PlaybackActivityMarker
) {
    private var appVisibility = AppVisibility.UNKNOWN
    private var playbackService = DiagnosticPlaybackService.STOPPED
    private var playback = PlaybackDiagnosticSnapshot(
        state = DiagnosticPlaybackState.IDLE,
        audioOrigin = DiagnosticAudioOrigin.NONE,
        castActive = false
    )

    fun updateAppVisibility(visibility: AppVisibility) {
        appVisibility = visibility
        publish()
    }

    fun updatePlaybackService(service: DiagnosticPlaybackService) {
        playbackService = service
        publish()
    }

    fun updatePlayback(snapshot: PlaybackDiagnosticSnapshot) {
        playback = snapshot
        playbackMarker.setPlaybackActive(snapshot.active)
        publish()
    }

    fun updateCastActive(active: Boolean) {
        playback = playback.copy(castActive = active)
        publish()
    }

    private fun publish() {
        reporting.updateContext(
            CrashContext(
                appVisibility = appVisibility,
                playbackState = playback.state,
                playbackService = if (
                    playbackService == DiagnosticPlaybackService.STARTED && playback.active
                ) {
                    DiagnosticPlaybackService.FOREGROUND
                } else {
                    playbackService
                },
                audioOrigin = playback.audioOrigin,
                castActive = playback.castActive
            )
        )
    }
}
