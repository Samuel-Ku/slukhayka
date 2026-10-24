package com.slukhayka.audiobooks.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1413 — «кнопка play заліпає наче зараз щось слухаю» (книга з YouTube,
 * після паузи або перемикання).
 *
 * `play()` ставить `isPlaying = true` оптимістично, до старту рушія. Коли
 * потік не заграв — типово протухлий підписаний YouTube-URL після паузи, —
 * ніщо цей стан не виправляло, і кнопка читалась як «грає» над тишею.
 *
 * Ці тести пінять ПРАВИЛО, а не реалізацію: рушій має голос, але лише коли
 * він справді осів і не перебуває в переході. Смикнути кнопку на кожному
 * seek було б гіршим дефектом за той, що лікуємо.
 */
class TransportStateReconciliationTest {

    @Test
    fun `a settled engine that is not playing corrects a stuck transport`() {
        assertTrue(
            TransportStateReconciliation.shouldClearPlayback(
                engineIsPlaying = false,
                transportIsPlaying = true,
                transportIsBuffering = false,
                enginePlaybackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun `an engine that died after an error is also a settled engine`() {
        // STATE_IDLE is where Media3 lands after a failed prepare. The stuck
        // report is exactly this: the stream died and the button kept saying
        // "playing".
        assertTrue(
            TransportStateReconciliation.shouldClearPlayback(
                engineIsPlaying = false,
                transportIsPlaying = true,
                transportIsBuffering = false,
                enginePlaybackState = Player.STATE_IDLE,
            ),
        )
    }

    @Test
    fun `a buffering transport is a transition, not a stop`() {
        assertFalse(
            TransportStateReconciliation.shouldClearPlayback(
                engineIsPlaying = false,
                transportIsPlaying = true,
                transportIsBuffering = true,
                enginePlaybackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun `a buffering engine is a transition, not a stop`() {
        assertFalse(
            TransportStateReconciliation.shouldClearPlayback(
                engineIsPlaying = false,
                transportIsPlaying = true,
                transportIsBuffering = false,
                enginePlaybackState = Player.STATE_BUFFERING,
            ),
        )
    }

    @Test
    fun `a playing engine never clears the transport`() {
        assertFalse(
            TransportStateReconciliation.shouldClearPlayback(
                engineIsPlaying = true,
                transportIsPlaying = true,
                transportIsBuffering = false,
                enginePlaybackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun `an already paused transport has nothing to correct`() {
        assertFalse(
            TransportStateReconciliation.shouldClearPlayback(
                engineIsPlaying = false,
                transportIsPlaying = false,
                transportIsBuffering = false,
                enginePlaybackState = Player.STATE_READY,
            ),
        )
    }
}
