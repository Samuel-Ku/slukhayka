package com.slukhayka.audiobooks.data.identity

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A bare Application: the real App's startup ensure() must not race this test. */
private class BareApp : Application()

/**
 * Spec-40 #275 (t1) — the SharedPreferences credential store round-trips
 * and clears. The file it writes (`listener_identity`) IS the Android Auto
 * Backup contract (spec-40 #276 pins it in res/xml) — Robolectric only if
 * already configured, which it is.
 *
 * Security: the password is sealed at rest with [DeviceBindingCipher] under
 * the device id, so the backup carries ciphertext only. The tests below pin
 * the sealed form, the one-time legacy-plaintext migration, and the
 * another-device/tampered honest misses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = BareApp::class)
class SharedPreferencesLocalCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var raw: SharedPreferences
    private lateinit var store: LocalCredentialStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        raw = context.getSharedPreferences("listener_identity", Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        store = SharedPreferencesLocalCredentialStore(context) { DEVICE_ID }
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
    fun `password is sealed at rest, never plaintext`() {
        store.save(
            StoredCredentials(
                uid = "abc123",
                email = "x7k2p9@slukhayka.local",
                password = "hunter2-but-longer",
                nickname = "Слухач-0042"
            )
        )

        // The backup-relevant raw file holds ciphertext, not the secret.
        assertNull(raw.getString("password", null))
        val sealed = raw.getString("password_sealed", null)
        assert(sealed != null && sealed.isNotBlank())
        assert(!sealed!!.contains("hunter2-but-longer"))
        // ...while the store still hands the live password to the caller.
        assertEquals("hunter2-but-longer", store.load()?.password)
    }

    @Test
    fun `legacy plaintext migrates to sealed on load`() {
        // A pre-seal install (or its restored backup): password in the open.
        raw.edit()
            .putString("uid", "abc123")
            .putString("email", "x7k2p9@slukhayka.local")
            .putString("password", "legacy-open-secret")
            .putString("nickname", "Слухач-0042")
            .commit()

        val loaded = store.load()

        assertEquals("legacy-open-secret", loaded?.password)
        // The read migrated forward: the next backup carries ciphertext only.
        assertNull(raw.getString("password", null))
        assert(raw.getString("password_sealed", null) != null)
        assertEquals("legacy-open-secret", store.load()?.password)
    }

    @Test
    fun `sealed blob from another device loads with null password`() {
        store.save(
            StoredCredentials(
                uid = "abc123",
                email = "x7k2p9@slukhayka.local",
                password = "hunter2-but-longer",
                nickname = "Слухач-0042"
            )
        )

        // A cloud backup restored on a DIFFERENT phone: same file, other key.
        val foreign = SharedPreferencesLocalCredentialStore(context) { "another-device-id" }
        val loaded = foreign.load()

        assertEquals("abc123", loaded?.uid)
        assertEquals("x7k2p9@slukhayka.local", loaded?.email)
        assertEquals("Слухач-0042", loaded?.nickname)
        assertNull(loaded?.password)
    }

    @Test
    fun `tampered sealed blob loads with null password, never a crash`() {
        store.save(
            StoredCredentials(
                uid = "abc123",
                email = "x7k2p9@slukhayka.local",
                password = "hunter2-but-longer",
                nickname = "Слухач-0042"
            )
        )
        raw.edit().putString("password_sealed", "!!!not-base64!!!").commit()

        val loaded = store.load()

        assertEquals("abc123", loaded?.uid)
        assertNull(loaded?.password)
    }

    @Test
    fun `no device id never writes a plaintext password`() {
        val legacy = SharedPreferencesLocalCredentialStore(context) { null }
        val record = StoredCredentials(
            uid = "abc123",
            email = "x7k2p9@slukhayka.local",
            password = "hunter2-but-longer",
            nickname = "Слухач-0042"
        )

        legacy.save(record)

        assertEquals(record.copy(password = null), legacy.load())
        assertNull(raw.getString("password", null))
        assertNull(raw.getString("password_sealed", null))
    }

    @Test
    fun `missing device id preserves previously sealed password without plaintext`() {
        val record = StoredCredentials("abc123", "a@slukhayka.local", "original", "Нік")
        store.save(record)
        SharedPreferencesLocalCredentialStore(context) { null }.save(record.copy(password = "replacement"))
        assertNull(raw.getString("password", null))
        assertEquals("original", store.load()?.password)
    }

    @Test
    fun `legacy plaintext is removed even without device id`() {
        raw.edit().putString("uid", "abc123").putString("password", "legacy").commit()
        val unavailable = SharedPreferencesLocalCredentialStore(context) { null }
        assertEquals("legacy", unavailable.load()?.password)
        assertNull(raw.getString("password", null))
        assertNull(unavailable.load()?.password)
    }

    @Test
    fun `sealed value removes abandoned plaintext even when key is unavailable`() {
        val record = StoredCredentials("abc123", "a@slukhayka.local", "sealed-original", "Нік")
        store.save(record)
        raw.edit().putString("password", "abandoned-open-secret").commit()
        assertEquals("sealed-original", store.load()?.password)
        assertNull(raw.getString("password", null))

        raw.edit().putString("password", "another-open-secret").commit()
        assertNull(SharedPreferencesLocalCredentialStore(context) { null }.load()?.password)
        assertNull(raw.getString("password", null))
    }

    @Test
    fun `clear wipes everything`() {
        store.save(
            StoredCredentials(uid = "abc123", email = null, password = null, nickname = "Нік")
        )

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `clear wipes the sealed password too`() {
        store.save(
            StoredCredentials(
                uid = "abc123",
                email = "x7k2p9@slukhayka.local",
                password = "hunter2-but-longer",
                nickname = "Нік"
            )
        )

        store.clear()

        assertNull(store.load())
        assertNull(raw.getString("password_sealed", null))
    }

    private companion object {
        const val DEVICE_ID = "test-device-id"
    }
}
