package com.slukhayka.audiobooks.data.reviews

import android.content.Context

class AndroidFeedbackPreferences(context: Context) : FeedbackPreferences {
    private val prefs = context.getSharedPreferences("book_feedback", Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(values: Map<String, String?>) {
        prefs.edit().also { edit -> values.forEach { (key, value) -> edit.putString(key, value) } }.apply()
    }
    override fun pending(): Set<String> = prefs.getStringSet("pending", emptySet()).orEmpty().toSet()
    override fun pending(ids: Set<String>) { prefs.edit().putStringSet("pending", ids.toSet()).apply() }
}
