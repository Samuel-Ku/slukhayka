package com.slukhayka.audiobooks.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CrashConsent { UNDECIDED, ALLOWED, DENIED }

interface CrashConsentStore {
    fun load(): CrashConsent
    fun save(consent: CrashConsent)
}

interface CrashReportSink {
    fun setCollectionEnabled(enabled: Boolean)
    fun checkForUnsentReports(onResult: (Boolean) -> Unit)
    fun sendUnsentReports()
    fun deleteUnsentReports()
    fun setCustomKeys(keys: Map<String, String>)
    fun record(exception: Throwable)
}

data class CrashReportingState(
    val consent: CrashConsent,
    val shouldShowPrompt: Boolean = false
)

enum class AppVisibility(val wireValue: String) {
    UNKNOWN("unknown"), FOREGROUND("foreground"), BACKGROUND("background")
}

enum class DiagnosticPlaybackState(val wireValue: String) {
    IDLE("idle"), BUFFERING("buffering"), PLAYING("playing"), PAUSED("paused")
}

enum class DiagnosticPlaybackService(val wireValue: String) {
    STOPPED("stopped"), STARTED("started"), FOREGROUND("foreground")
}

enum class DiagnosticAudioOrigin(val wireValue: String) {
    NONE("none"), LOCAL("local"), REMOTE("remote")
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

/** One boundary owns consent, held reports and the exact diagnostic allowlist. */
class CrashReporting(
    private val consentStore: CrashConsentStore,
    private val sink: CrashReportSink,
    private val enabledForBuild: Boolean
) {
    private val _state = MutableStateFlow(CrashReportingState(consentStore.load()))
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
                    _state.value = CrashReportingState(consent, shouldShowPrompt = true)
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
        sink.setCollectionEnabled(false)
        sink.deleteUnsentReports()
        _state.value = CrashReportingState(CrashConsent.DENIED)
    }

    fun setAllowedFromSettings(allowed: Boolean) {
        val consent = if (allowed) CrashConsent.ALLOWED else CrashConsent.DENIED
        consentStore.save(consent)
        sink.deleteUnsentReports()
        sink.setCollectionEnabled(allowed && enabledForBuild)
        _state.value = CrashReportingState(consent)
    }

    fun updateContext(context: CrashContext) {
        if (enabledForBuild && _state.value.consent != CrashConsent.DENIED) {
            sink.setCustomKeys(context.customKeys)
        }
    }

    fun record(exception: Throwable) {
        if (enabledForBuild && _state.value.consent != CrashConsent.DENIED) {
            sink.record(exception)
        }
    }
}
