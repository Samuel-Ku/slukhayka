package com.slukhayka.audiobooks.player

import android.content.Context
import android.os.CountDownTimer
/**
 * Spec-22 T5 — «Таймер сну» as ONE deep module.
 *
 * Before this seam the countdown, its fade curve, the shake-to-extend
 * listening, the end-of-chapter re-arm and the three finish effects
 * (auto-bookmark, pause, TIMER_STOP event) all lived inside
 * [AudioPlayerManager] as loose fields and one `CountDownTimer` object. The
 * manager now only starts, stops and extends the timer; every timer-caused
 * effect lives behind [SleepTimerModule].
 *
 * The module owns the countdown state and emits it synchronously through
 * [SleepTimerEffects.onState], so the manager mirrors it into `PlayerState`
 * exactly when the tick happens (no coroutine hop, no test race).
 *
 * Injected adapters keep the seam honest:
 *
 *  - [SleepTicker] — production is the 1-second [CountDownSleepTicker];
 *    contract tests drive ticks manually;
 *  - [ShakeSource] — production is [ShakeDetectorSource] (accelerometer);
 *    tests fake it;
 *  - [SleepTimerEffects] — the manager's ports for volume, pause, auto-
 *    bookmark, TIMER_STOP event, notices and the end-of-chapter arm callback.
 */
internal fun sleepTimerFadeVolume(remainingSec: Int): Float =
    if (remainingSec in 1..30) remainingSec / 30f else 1.0f

/** The timer facts mirrored into [PlayerState]. */
internal data class SleepTimerSnapshot(
    val minutes: Int = 0,
    val remainingSeconds: Int = 0,
    val isEndOfChapter: Boolean = false
) {
    val isActive: Boolean get() = minutes != 0
}

/** Everything a finishing timer needs to name the moment it stopped. */
internal data class SleepTimerStopContext(
    val bookId: String?,
    val chapterIndex: Int,
    val chapterTitle: String,
    val positionSeconds: Long,
    val chapterId: String?
)

/** The 1-second countdown seam (production: [CountDownSleepTicker]). */
internal interface SleepTicker {
    fun start(totalMs: Long, onTick: (remainingSec: Int) -> Unit, onFinish: () -> Unit)
    fun cancel()
}

/** The shake-to-extend seam (production: [ShakeDetectorSource]). */
internal interface ShakeSource {
    fun start(onShake: () -> Unit)
    fun stop()
}

/** Effects the timer causes in the player; the manager implements them. */
internal interface SleepTimerEffects {
    fun onState(snapshot: SleepTimerSnapshot)
    fun setVolume(volume: Float)
    fun pause()
    fun writeAutoBookmark(context: SleepTimerStopContext)
    fun recordTimerStop(context: SleepTimerStopContext)
    fun emitNotice(notice: SleepTimerNotice)
    fun onEndOfChapterArmed()
    fun stopContext(): SleepTimerStopContext
}

/** Production ticker: the real 1-second `CountDownTimer`. */
internal class CountDownSleepTicker : SleepTicker {
    private var timer: CountDownTimer? = null

    override fun start(
        totalMs: Long,
        onTick: (remainingSec: Int) -> Unit,
        onFinish: () -> Unit
    ) {
        cancel()
        timer = object : CountDownTimer(totalMs, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                onTick((millisUntilFinished / 1_000L).toInt())
            }

            override fun onFinish() {
                timer = null
                onFinish()
            }
        }.start()
    }

    override fun cancel() {
        timer?.cancel()
        timer = null
    }
}

/** Production shake source: the accelerometer listener behind the seam. */
internal class ShakeDetectorSource(context: Context) : ShakeSource {
    private var onShake: (() -> Unit)? = null
    private val detector = ShakeDetector(context) { onShake?.invoke() }

    override fun start(onShake: () -> Unit) {
        this.onShake = onShake
        detector.startListening()
    }

    override fun stop() {
        detector.stopListening()
    }
}

/**
 * The countdown and every timer-caused effect.
 *
 * Modes: `0` cancels, `-1` stops exactly at the current chapter's boundary
 * (re-armed on manual chapter switches), positive minutes fade the volume
 * 1.0→0.0 over the last 30 s and stop the player at zero. During the fade a
 * shake restores volume and extends by +15 min. Behaviour is unchanged from
 * the pre-#1573 manager implementation — only the owner moved behind a seam.
 */
internal class SleepTimerModule(
    private val ticker: SleepTicker,
    private val shakeSource: ShakeSource,
    private val effects: SleepTimerEffects
) {
    var snapshot: SleepTimerSnapshot = SleepTimerSnapshot()
        private set

    private var fadeWarningEmitted = false

    /** Arms a plain minutes timer. */
    fun start(minutes: Int) {
        require(minutes > 0) { "a timed sleep timer needs positive minutes" }
        apply(SleepTimerSnapshot(minutes = minutes, remainingSeconds = minutes * 60))
        startTicker(minutes * 60_000L)
    }

    /**
     * Arms «до кінця розділу».
     *
     * @param remainingMs the honest remainder of the CURRENT chapter (the
     *   manager clamps it to at least one second before calling)
     */
    fun startEndOfChapter(remainingMs: Long) {
        apply(
            SleepTimerSnapshot(
                minutes = -1,
                remainingSeconds = (remainingMs / 1_000L).toInt(),
                isEndOfChapter = true
            )
        )
        startTicker(remainingMs.coerceAtLeast(1_000L))
        // #1173 (T9): ARMING is the fact. A re-arm on the chapter boundary is
        // not a second request for the mode and must not reach this callback.
        effects.onEndOfChapterArmed()
    }

    /** Stops the countdown without resetting the visible state (load hop). */
    fun cancel() {
        ticker.cancel()
        shakeSource.stop()
        fadeWarningEmitted = false
    }

    /** Cancels and forgets the timer — used when a new book is loaded. */
    fun clear() {
        cancel()
        apply(SleepTimerSnapshot())
        effects.setVolume(1.0f)
    }

    /**
     * Canonical +15-minute command for both the visible action and the shake
     * shortcut. The exact remainder is preserved; an end-of-chapter timer
     * becomes an ordinary timed remainder.
     *
     * @return the new exact remainder in seconds, or `0` when none is active.
     */
    fun extendBy15Minutes(): Int {
        val currentRemaining = snapshot.remainingSeconds
        if (currentRemaining <= 0) return 0
        ticker.cancel()
        shakeSource.stop()
        effects.setVolume(1.0f)
        val extended = currentRemaining + 15 * 60
        apply(
            SleepTimerSnapshot(
                minutes = (extended + 59) / 60,
                remainingSeconds = extended
            )
        )
        startTicker(extended * 1_000L)
        effects.emitNotice(SleepTimerNotice.Extended(extended))
        return extended
    }

    /**
     * Spec-22 T5: «до кінця розділу» follows manual chapter switches so it
     * still stops exactly at the new chapter's boundary. No-op for timed
     * timers.
     */
    fun onChapterSwitch(
        chapterDurationSeconds: Long,
        startPositionMs: Long
    ) {
        if (!snapshot.isEndOfChapter) return
        ticker.cancel()
        shakeSource.stop()
        val remainingMs = (chapterDurationSeconds * 1_000L - startPositionMs)
            .coerceAtLeast(1_000L)
        apply(snapshot.copy(remainingSeconds = (remainingMs / 1_000L).toInt()))
        startTicker(remainingMs)
    }

    /** Stops the timer for teardown without touching the visible state. */
    fun release() = cancel()

    private fun startTicker(totalMs: Long) {
        fadeWarningEmitted = false
        ticker.start(totalMs, onTick = ::handleTick, onFinish = ::handleFinish)
    }

    private fun handleTick(remainingSec: Int) {
        apply(snapshot.copy(remainingSeconds = remainingSec))
        val volume = sleepTimerFadeVolume(remainingSec)
        if (volume < 1.0f) {
            if (!fadeWarningEmitted) {
                fadeWarningEmitted = true
                effects.emitNotice(SleepTimerNotice.FadeWarning)
            }
            effects.setVolume(volume)
            shakeSource.start { extendBy15Minutes() }
        } else {
            shakeSource.stop()
        }
    }

    private fun handleFinish() {
        shakeSource.stop()
        val context = effects.stopContext()
        effects.writeAutoBookmark(context)
        effects.pause()
        effects.recordTimerStop(context)
        effects.setVolume(1.0f)
        apply(SleepTimerSnapshot())
    }

    private fun apply(next: SleepTimerSnapshot) {
        snapshot = next
        effects.onState(next)
    }
}
