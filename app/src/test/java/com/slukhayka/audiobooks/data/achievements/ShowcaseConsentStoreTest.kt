package com.slukhayka.audiobooks.data.achievements

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1723 — the store can be constructed on main without reading prefs: the
 * startup batch seeds the published value on IO. A seed must never overwrite
 * an explicit listener action that raced it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShowcaseConsentStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("showcase_consent_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `seed carries the startup value and explicit actions win the race`() {
        val store = ShowcaseConsentStore(context)
        assertFalse("construction must not read the file", store.isPublished())

        store.seed(true)
        assertTrue(store.isPublished())

        // A stale batch finishing after an explicit withdraw must not re-publish.
        store.withdraw()
        store.seed(true)
        assertFalse(store.isPublished())

        // A stale false must not hide a just-granted showcase either.
        store.grant()
        store.seed(false)
        assertTrue(store.isPublished())
    }

    @Test
    fun `grant persists so the next process can seed from it`() {
        ShowcaseConsentStore(context).grant()

        val reopened = ShowcaseConsentStore(context)
        assertFalse("the constructor stays read-free", reopened.isPublished())
        reopened.seed(
            ShowcaseConsentStore.readPublished(context)
        )
        assertTrue(reopened.isPublished())
    }
}
