package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1718 — both Auto Backup configs must pin the same durable stores.
 *
 * `backup_rules.xml` covers API < 31, `data_extraction_rules.xml` covers API 31+
 * through its `cloud-backup` and `device-transfer` halves. The pref and database
 * names are private constants, so a rename would silently drop the listener
 * identity or listening progress from Auto Backup while everything else stayed
 * green. This source scan pins the include sets and that each include still has
 * a production constant behind it — the JVM counterpart of the contract
 * [com.slukhayka.audiobooks.data.identity.SharedPreferencesLocalCredentialStoreTest]
 * only claims.
 */
class BackupRulesConsistencyGuardTest {

    private val main: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        listOf(
            File(System.getProperty("user.dir"), "app/src/main"),
            File(System.getProperty("user.dir"), "src/main")
        ).firstOrNull { File(it, "res/xml").isDirectory }
            ?: error("app/src/main not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `both backup configs pin the same durable stores`() {
        val extraction = File(main, "res/xml/data_extraction_rules.xml").readText()
        val sections = mapOf(
            "backup_rules.xml" to includes(File(main, "res/xml/backup_rules.xml").readText()),
            "cloud-backup" to includes(section(extraction, "cloud-backup")),
            "device-transfer" to includes(section(extraction, "device-transfer"))
        )

        sections.forEach { (name, stores) ->
            assertEquals("$name drifted from the pinned durable stores", EXPECTED, stores)
            assertEquals("$name lost the identity/progress contract", CONTRACT, stores intersect CONTRACT)
        }
    }

    @Test
    fun `every include resolves to a production constant`() {
        val sources = File(main, "java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
        val unresolved = includes(File(main, "res/xml/backup_rules.xml").readText()).filterNot { (domain, path) ->
            val name = when (domain) {
                "sharedpref" -> path.removeSuffix(".xml")
                "database" -> path.removeSuffix("-wal").removeSuffix("-shm")
                else -> return@filterNot false
            }
            sources.any { "\"$name\"" in it }
        }

        assertTrue("includes with no matching production constant: $unresolved", unresolved.isEmpty())
    }

    private fun includes(xml: String): Set<Pair<String, String>> =
        INCLUDE.findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toSet()

    private fun section(xml: String, tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: error("data_extraction_rules.xml has no <$tag> section")

    private companion object {
        val INCLUDE = Regex("""<include\b[^>]*\bdomain="([^"]+)"[^>]*\bpath="([^"]+)"[^>]*>""")

        /** The durable set, frozen so a removal in both files is still a failure. */
        val EXPECTED = setOf(
            "sharedpref" to "listener_identity.xml",
            "database" to "read4_audiobook_database",
            "database" to "read4_audiobook_database-wal",
            "database" to "read4_audiobook_database-shm",
            "sharedpref" to "listen_prefs.xml",
            "sharedpref" to "network_privacy_settings.xml",
            "sharedpref" to "playback_settings.xml",
            "sharedpref" to "recommendation_settings.xml",
            "sharedpref" to "crash_reporting_consent.xml"
        )

        /** The named contract: silent listener identity + listening progress. */
        val CONTRACT = setOf(
            "sharedpref" to "listener_identity.xml",
            "database" to "read4_audiobook_database"
        )
    }
}
