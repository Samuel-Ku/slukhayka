package com.slukhayka.audiobooks.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver hosting the Glance home screen widget (spec-21 Track B,
 * restored as spec-22 T4).
 */
class AudiobookGlanceWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = AudiobookGlanceWidget()

    companion object {
        fun update(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AudiobookGlanceWidget().updateAll(context)
                } catch (_: Exception) {}
            }
        }
    }
}
