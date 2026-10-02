package com.slukhayka.audiobooks.data.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.ingest.TelegramMembershipPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #831 AC4 — «Source Audio Refusal поважається: клас можна відмовити як
 * будь-яке джерело».
 *
 * The AC is a NEGATIVE claim: the shared library must not become a privileged
 * source that the listener cannot switch off. The honest proof is therefore
 * not "we added a door" but "the class is an ORDINARY source": the registered
 * community group is the plain `telegram` entry of [SourceRegistry], with no
 * exception carved for it, so every existing refusal path already reaches it.
 *
 * The tests below pin exactly that — the class is in the registry, it is NOT a
 * scam source (so `ALWAYS_REFUSED` does not silently cover it, which would make
 * the refusal path untested), the refusal really applies to its id, and the
 * listener can take it back. If a future change promotes the community group to
 * a privileged class, these go red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class CommunitySourceRefusalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The registered group's source id, resolved from the SAME constant the
     * membership policy publishes — not a literal, so the test cannot drift
     * away from the class it is about.
     */
    private val communitySourceId: String
        get() = SourceRegistry.entries
            .first { it.homeUrl == TelegramMembershipPolicy.GROUP_URL }
            .id

    @Test
    fun `the community group IS an ordinary registered source`() {
        val source = SourceRegistry.entries
            .firstOrNull { it.homeUrl == TelegramMembershipPolicy.GROUP_URL }

        assertTrue(
            "зареєстрована група мусить бути звичайним записом реєстру — інакше " +
                "відмова не має до чого прив'язатися",
            source != null
        )
        assertEquals("https://t.me/slukhayka", TelegramMembershipPolicy.GROUP_URL)
    }

    @Test
    fun `the class is NOT a scam source`() {
        // Important, and easy to get wrong: a scam source is ALWAYS_REFUSED
        // regardless of the listener, so if the community group were scam the
        // refusal path would pass without exercising anything the listener
        // controls — a vacuous green.
        assertFalse(
            "група спільноти не є scam-джерелом",
            SourceRegistry.isScam(communitySourceId)
        )
        assertFalse(
            "ALWAYS_REFUSED не покриває групу спільноти",
            communitySourceId in SourceAudioRefusal.ALWAYS_REFUSED
        )
    }

    @Test
    fun `the listener can refuse the class and take it back`() {
        val refusal = SourceAudioRefusal(context)
        val id = communitySourceId

        // Clean slate: another test in this JVM may have written the pref.
        refusal.allow(id)
        assertFalse("до відмови клас доступний", refusal.isRefused(id))

        refusal.refuse(id)
        assertTrue("клас можна відмовити, як будь-яке джерело", refusal.isRefused(id))
        assertTrue("стан дійшов до потоку", id in refusal.refusedSources.value)

        refusal.allow(id)
        assertFalse("відмову можна зняти", refusal.isRefused(id))
    }

    @Test
    fun `a refusal of the class is not special-cased away`() {
        // The refusal set is normalized on write; `local` is the only
        // pseudo-source excluded, because the listener's own files are never
        // a source. The community group must NOT be in that exclusion.
        val refusal = SourceAudioRefusal(context)
        refusal.setRefused(setOf(communitySourceId))
        assertTrue(
            "нормалізація не має викидати групу спільноти з відмов",
            communitySourceId in refusal.refusedSources.value
        )
        assertNotEquals(
            "група спільноти — не псевдо-джерело `local`",
            "local",
            communitySourceId
        )
        refusal.allow(communitySourceId)
    }
}
