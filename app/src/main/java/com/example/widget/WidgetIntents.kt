package com.example.widget

import android.content.Context
import android.content.Intent
import com.example.MainActivity

/** Spec-17 (#110): intent plumbing for "tap the book → open the player". */
object WidgetIntents {

    /**
     * Extra set on the [MainActivity] intent by the widget's book tap.
     * [com.example.MainActivity] reads it (also in `onNewIntent`) and shows
     * the existing full-player overlay — no new navigation surface.
     */
    const val EXTRA_OPEN_PLAYER = "com.slukhayka.app.widget.EXTRA_OPEN_PLAYER"

    fun openPlayerIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
}