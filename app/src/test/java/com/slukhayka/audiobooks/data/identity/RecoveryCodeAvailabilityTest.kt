package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryCodeAvailabilityTest {

    @Test
    fun `a build without Firebase reports the configuration problem`() = runBlocking {
        val store = FakeLocalCredentialStore()
        val identity = LocalOnlyIdentity(store, Random(1))
        identity.ensure()

        assertEquals(
            RecoveryCodeAvailability.FirebaseNotConfigured,
            identity.recoveryCodeAvailability()
        )
    }

    @Test
    fun `legacy server credentials stay hidden until Firebase can verify them`() = runBlocking {
        val store = FakeLocalCredentialStore().apply {
            save(
                StoredCredentials(
                    uid = "server-uid",
                    email = "listener@slukhayka.local",
                    password = "password",
                    nickname = "Слухач"
                )
            )
        }
        val identity = LocalOnlyIdentity(store, Random(3))

        assertNull(identity.recoveryCode())
        assertEquals(
            RecoveryCodeAvailability.FirebaseNotConfigured,
            identity.recoveryCodeAvailability()
        )
        assertEquals("listener@slukhayka.local", store.load()?.email)
        assertEquals("password", store.load()?.password)
    }

    @Test
    fun `a build without Firebase does not pretend to restore a server profile`() = runBlocking {
        val store = FakeLocalCredentialStore()
        val identity = LocalOnlyIdentity(store, Random(2))
        val localProfile = identity.ensure()
        val code = RecoveryCodec.encode("listener@slukhayka.local", "password")

        assertNull(identity.restoreFromCode(code))
        assertEquals(localProfile, identity.current())
        assertNull(store.load()?.email)
        assertNull(store.load()?.password)
    }
}
