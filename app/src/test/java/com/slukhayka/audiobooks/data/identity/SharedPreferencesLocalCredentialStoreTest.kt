package com.slukhayka.audiobooks.data.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-40 #275 (t1) — the SharedPreferences credential store round-trips
 * and clears. The file it writes (`listener_identity`) IS the Android Auto
 * Backup contract (spec-40 #276 pins it in res/xml) — Robolectric only if
 * already configured, which it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPreferencesLocalCredentialStoreTest {

    private lateinit var store: LocalCredentialStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listener_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = SharedPreferencesLocalCredentialStore(context)
    }

    @Test
    fun `an empty store loads null`() {
        assertNull(store.load())
    }

    @Test
    fun `credentials round-trip`() {
        val record = StoredCredentials(
            uid = "abc123",
            email = "x7k2p9@slukhayka.local",
            password = "hunter2-but-longer",
            nickname = "Слухач-0042"
        )

        store.save(record)

        assertEquals(record, store.load())
    }

    @Test
    fun `clear wipes everything`() {
        store.save(
            StoredCredentials(uid = "abc123", email = null, password = null, nickname = "Нік")
        )

        store.clear()

        assertNull(store.load())
    }
}
