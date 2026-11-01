package com.slukhayka.audiobooks.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * BroadcastReceiver hosting the Glance home screen widget (spec-21 Track B,
 * restored as spec-22 T4).
 *
 * There is deliberately no `update(context)` helper here anymore: #1749 moved
 * live refreshes to [publishWidgetSnapshot], which writes the snapshot the
 * content actually reads. An `updateAll` alone would only extend an active
 * session and leave the rendered frame unchanged.
 */
class AudiobookGlanceWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = AudiobookGlanceWidget()
}
