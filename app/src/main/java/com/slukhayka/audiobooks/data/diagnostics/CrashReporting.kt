package com.slukhayka.audiobooks.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CrashConsent {
    UNDECIDED,
    ALLOWED,
    DENIED
}

interface CrashConsentStore {
    fun load(): CrashConsent
    fun save(consent: CrashConsent)
}

interface CrashReportSink {
    fun setCollectionEnabled(enabled: Boolean)
    fun checkForUnsentReports(onResult: (Boolean) -> Unit)
    fun sendUnsentReports()
    fun deleteUnsentReports()
    fun setContext(context: CrashContext)
}

data class CrashReportingState(
    val consent: CrashConsent,
    val shouldShowPrompt: Boolean = false
)

enum class AppVisibility(val wireValue: String) {
    UNKNOWN("unknown"),
    FOREGROUND("foreground"),
    BACKGROUND("background")
}

enum class DiagnosticPlaybackState(val wireValue: String) {
    IDLE("idle"),
    BUFFERING("buffering"),
    PLAYING("playing"),
    PAUSED("paused")
}

enum class DiagnosticPlaybackService(val wireValue: String) {
    STOPPED("stopped"),
    STARTED("started")
}

enum class DiagnosticAudioOrigin(val wireValue: String) {
    NONE("none"),
    LOCAL("local"),
    REMOTE("remote")
}

data class CrashContext(
    val appVisibility: AppVisibility = AppVisibility.UNKNOWN,
    val playbackState: DiagnosticPlaybackState = DiagnosticPlaybackState.IDLE,
    val playbackService: DiagnosticPlaybackService = DiagnosticPlaybackService.STOPPED,
    val audioOrigin: DiagnosticAudioOrigin = DiagnosticAudioOrigin.NONE,
    val castActive: Boolean = false
) {
    val customKeys: Map<String, String>
        get() = linkedMapOf(
            "app_visibility" to appVisibility.wireValue,
            "playback_state" to playbackState.wireValue,
            "playback_service" to playbackService.wireValue,
            "audio_origin" to audioOrigin.wireValue,
            "cast_active" to castActive.toString()
        )
}

/**
 * The single app boundary for crash-report consent and bounded diagnostic
 * context. Callers can submit typed state only; arbitrary Crashlytics keys,
 * logs and identifiers never cross this interface.
 */
class CrashReporting(
    private val consentStore: CrashConsentStore,
    private val sink: CrashReportSink,
    private val enabledForBuild: Boolean
) {
    private val contextLock = Any()
    private var currentContext = CrashContext()
    private val _state = MutableStateFlow(
        CrashReportingState(consent = consentStore.load())
    )
    val state: StateFlow<CrashReportingState> = _state.asStateFlow()

    fun start() {
        val consent = consentStore.load()
        _state.value = CrashReportingState(consent)
        sink.setCollectionEnabled(enabledForBuild && consent == CrashConsent.ALLOWED)

        if (!enabledForBuild) return

        when (consent) {
            CrashConsent.ALLOWED -> Unit
            CrashConsent.DENIED -> sink.deleteUnsentReports()
            CrashConsent.UNDECIDED -> sink.checkForUnsentReports { hasReports ->
                if (hasReports && _state.value.consent == CrashConsent.UNDECIDED) {
                    _state.value = CrashReportingState(
                        consent = CrashConsent.UNDECIDED,
                        shouldShowPrompt = true
                    )
                }
            }
        }
    }

    fun allowTriggeringReport() {
        if (!enabledForBuild || _state.value.consent != CrashConsent.UNDECIDED) return
        consentStore.save(CrashConsent.ALLOWED)
        sink.sendUnsentReports()
        sink.setCollectionEnabled(true)
        _state.value = CrashReportingState(CrashConsent.ALLOWED)
    }

    fun denyTriggeringReport() {
        if (_state.value.consent != CrashConsent.UNDECIDED) return
        consentStore.save(CrashConsent.DENIED)
        disableAndDeleteReports()
        _state.value = CrashReportingState(CrashConsent.DENIED)
    }

    fun setAllowedFromSettings(allowed: Boolean) {
        val consent = if (allowed) CrashConsent.ALLOWED else CrashConsent.DENIED
        consentStore.save(consent)
        if (allowed && enabledForBuild) {
            // Settings approval after a denial is future-only: remove reports
            // retained while collection was disabled before turning it on.
            sink.deleteUnsentReports()
            sink.setCollectionEnabled(true)
        } else {
            disableAndDeleteReports()
        }
        _state.value = CrashReportingState(consent)
    }

    fun setAppVisibility(visibility: AppVisibility) = updateCurrentContext {
        copy(appVisibility = visibility)
    }

    fun setPlayback(state: DiagnosticPlaybackState, origin: DiagnosticAudioOrigin) =
        updateCurrentContext { copy(playbackState = state, audioOrigin = origin) }

    fun setPlaybackService(service: DiagnosticPlaybackService) = updateCurrentContext {
        copy(playbackService = service)
    }

    fun setCastActive(active: Boolean) = updateCurrentContext {
        copy(castActive = active)
    }

    private fun updateCurrentContext(transform: CrashContext.() -> CrashContext) {
        val updated = synchronized(contextLock) {
            currentContext.transform().also { currentContext = it }
        }
        publishContext(updated)
    }

    private fun publishContext(context: CrashContext) {
        if (enabledForBuild) sink.setContext(context)
    }

    private fun disableAndDeleteReports() {
        sink.setCollectionEnabled(false)
        sink.deleteUnsentReports()
    }
}
