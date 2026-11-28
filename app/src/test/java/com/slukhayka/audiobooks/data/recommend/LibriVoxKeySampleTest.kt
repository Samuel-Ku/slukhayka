package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.merge.MergeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Python `merge_key` port now drives a 20k LibriVox export, not just the
 * 23-book seed. This sample spans the API's first page (punctuation, initials,
 * diacritics) and checks every key against the app's real implementation.
 */
class LibriVoxKeySampleTest {
    @Test fun `python port boundary fixture agrees with actual Android Work identity`() {
        val text = javaClass.getResource("/recommend/merge-key-boundaries.json")!!.readText()
        val document = MiniJson.parse(text) as Map<*, *>
        val rows = document["entries"] as List<*>
        for (value in rows) {
            val row = value as Map<*, *>
            assertEquals(row.toString(), row["workKey"], MergeKey.keyFor(row["title"] as String, row["author"] as String))
        }
    }

    @Test fun `python key port matches the app on LibriVox titles`() {
        val text = javaClass.getResource("/recommend/librivox-key-sample.json")?.readText()
            ?: error("missing LibriVox sample")
        val document = MiniJson.parse(text) as? Map<*, *> ?: error("sample is not an object")
        val entries = document["entries"] as? List<*> ?: error("sample has no entries")
        assertTrue("the sample must cover a real page", entries.size >= 90)
        for (entry in entries) {
            val row = entry as? Map<*, *> ?: error("entry is not an object")
            val title = row["title"] as String
            val author = row["author"] as String
            val expected = row["workKey"] as String
            assertEquals("$title — $author", expected, MergeKey.keyFor(title, author))
        }
    }
}
