package com.example.data.collection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-16 T1 (#107) — the shipped `assets/collections/` fixtures load through
 * the real asset pipeline. This is a data test: every JSON file present must
 * be parseable and id-consistent, so a bad edit fails here, not on devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartCollectionAssetsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `shipped collections load in index order with ids and entries`() {
        val collections = SmartCollectionAssets.load(context)

        assertEquals(listOf("nobel", "shevchenko", "booker"), collections.map { it.id })
        assertTrue(collections.all { it.displayName.isNotBlank() })
        assertTrue(collections.all { it.entries.isNotEmpty() })
        assertTrue(collections.all { it.entries.all { e -> e.author.isNotBlank() } })
    }

    @Test
    fun `every entry carries a title for the no-ambiguity rule`() {
        // The author-only fallback must stay unambiguous; the shipped data
        // should never rely on it.
        val collections = SmartCollectionAssets.load(context)
        assertTrue(collections.all { spec -> spec.entries.all { e -> !e.title.isNullOrBlank() } })
    }

    @Test
    fun `collection ids are unique`() {
        val collections = SmartCollectionAssets.load(context)
        assertEquals(collections.map { it.id }.distinct().size, collections.size)
    }
}
