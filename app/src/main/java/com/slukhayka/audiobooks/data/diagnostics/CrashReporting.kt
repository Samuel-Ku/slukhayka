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

    /** Durable next-launch handoff for a bounded non-fatal process exit. */
    fun markFailurePromptPending() = Unit
    fun consumeFailurePromptPending(): Boolean = false
}

interface CrashReportSink {
    fun setCollectionEnabled(enabled: Boolean)
    fun checkForUnsentReports(onResult: (Boolean) -> Unit)
    fun sendUnsentReports()
    fun deleteUnsentReports()
    fun setCustomKeys(keys: Map<String, String>) = Unit
    fun record(exception: Throwable) = Unit
    fun setContext(context: CrashContext) = setCustomKeys(context.customKeys)
    fun recordUnexpectedPlaybackExit(event: UnexpectedPlaybackExitEvent) = Unit
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
    STARTED("started"), FOREGROUND("foreground")
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
    private val enabledForBuild: Boolean,
    private val processStateSummarySink: ProcessStateSummarySink = ProcessStateSummarySink { }
) {
    private val contextLock = Any()
    private val publishLock = Any()
    private var currentContext = CrashContext()
    // #1730 — contextLock guards every write above, so the snapshot start()
    // takes can never be a torn read; publishLock orders the sink calls made
    // from start() (IO) and from the setters (main). A version, not the lock
    // order, decides which context the sinks end with: a stale snapshot must
    // never overwrite a newer update.
    private var contextVersion = 0L
    private var publishedContextVersion = -1L
    // #1723 — the constructor runs wherever the lazy App.crashReporting is
    // first touched, and that can be the main thread (Activity.onStart). It
    // must not read prefs: the initial UNDECIDED state is the honest "not
    // loaded yet" answer, and start() — which the composition root calls on
    // IO — publishes the persisted choice.
    private val _state = MutableStateFlow(
        CrashReportingState(consent = CrashConsent.UNDECIDED)
    )
    val state: StateFlow<CrashReportingState> = _state.asStateFlow()

    /**
     * #1723 — false until [start] has read (or received) the persisted consent.
     * Activity.onStart can update the visibility before the IO batch answers;
     * nothing may reach the sink until the consent is known, or a listener who
     * denied reporting would briefly have diagnostic context published.
     */
    @Volatile
    private var started = false

    /**
     * @param consent the value a caller already loaded off the main thread
     *   (#1723); the default keeps the old self-contained contract for tests
     *   and for any future caller that has no startup batch.
     */
    fun start(consent: CrashConsent = consentStore.load()) {
        _state.value = CrashReportingState(consent)
        sink.setCollectionEnabled(enabledForBuild && consent == CrashConsent.ALLOWED)
        // #1730 — start() runs on IO while the setters run on main. Take the
        // accumulated context AND mark the module started under one lock: a
        // setter that lost the race is either inside this snapshot or sees
        // started == true and publishes its own newer context. The sinks are
        // called after the lock is released.
        val (contextSnapshot, version) = synchronized(contextLock) {
            started = true
            currentContext to contextVersion
        }
        if (enabledForBuild) {
            // Publish the context that accumulated before start() ran — now
            // that the consent is known. DENIED still publishes nothing to
            // the crash sink.
            publishStartContext(contextSnapshot, version)
        }

        if (!enabledForBuild) return

        when (consent) {
            CrashConsent.ALLOWED -> Unit
            CrashConsent.DENIED -> sink.deleteUnsentReports()
            CrashConsent.UNDECIDED -> if (consentStore.consumeFailurePromptPending()) {
                _state.value = CrashReportingState(
                    consent = CrashConsent.UNDECIDED,
                    shouldShowPrompt = true
                )
            } else sink.checkForUnsentReports { hasReports ->
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

    fun updateContext(context: CrashContext) {
        val version = synchronized(contextLock) {
            currentContext = context
            ++contextVersion
        }
        publishContext(context, version)
    }

    fun record(exception: Throwable) {
        if (enabledForBuild && started && _state.value.consent != CrashConsent.DENIED) {
            sink.record(exception)
        }
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

    fun reportUnexpectedPlaybackExit(event: UnexpectedPlaybackExitEvent) {
        if (!enabledForBuild || !started) return
        when (_state.value.consent) {
            CrashConsent.DENIED -> Unit
            CrashConsent.ALLOWED -> sink.recordUnexpectedPlaybackExit(event)
            CrashConsent.UNDECIDED -> {
                sink.recordUnexpectedPlaybackExit(event)
                // This event is discovered after startup. Persist the exact
                // same voluntary prompt for the next launch rather than
                // interrupting the listener in the current session.
                consentStore.markFailurePromptPending()
            }
        }
    }

    private fun updateCurrentContext(transform: CrashContext.() -> CrashContext) {
        val (updated, version) = synchronized(contextLock) {
            val next = currentContext.transform()
            currentContext = next
            ++contextVersion
            next to contextVersion
        }
        publishContext(updated, version)
    }

    /**
     * #1730 — publish one context snapshot unless a newer one already reached
     * the sinks. The version check and both sink calls share [publishLock], so
     * two threads cannot interleave an older setContext after a newer one; the
     * lock is NOT [contextLock], so a slow sink never blocks a context write.
     */
    private fun publishContext(context: CrashContext, version: Long) {
        if (!enabledForBuild || !started) return
        synchronized(publishLock) {
            if (version <= publishedContextVersion) return
            publishedContextVersion = version
            processStateSummarySink.set(context)
            if (_state.value.consent != CrashConsent.DENIED) sink.setContext(context)
        }
    }

    /**
     * #1730 — the start-time publish. It keeps the same process-summary
     * contract (written even when reporting is denied) and the same crash-sink
     * guard, but a context update published from inside a sink callback must
     * win over this older snapshot.
     */
    private fun publishStartContext(context: CrashContext, version: Long) {
        synchronized(publishLock) {
            if (version < publishedContextVersion) return
            publishedContextVersion = version
            processStateSummarySink.set(context)
            // A reentrant/concurrent setter may have published a newer context
            // while this sink call was running; do not put the old one back.
            if (version != publishedContextVersion) return
            if (_state.value.consent != CrashConsent.DENIED) sink.setContext(context)
        }
    }

    private fun disableAndDeleteReports() {
        sink.setCollectionEnabled(false)
        sink.deleteUnsentReports()
    }
}
