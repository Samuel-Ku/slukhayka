package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.collections.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-0038 — the Android side of the parity gate: [SourceRegistry] must
 * mirror `sources.json` (repo root) fact for fact. The file is decoded with
 * the pure-JVM [MiniJson] (no Android, no network) and compared by FULL
 * equality — an added, removed or changed fact on either side fails the
 * build. The file is located by walking up from the test working directory
 * (Gradle runs :app tests with cwd = `app/`).
 *
 * The live 4read search door is additionally pinned to the registry's
 * template, so the door function and the carrier cannot drift.
 */
class SourceRegistryConformanceTest {

    private val decoded: List<SourceFacts> by lazy {
        val root = MiniJson.parse(locateSourcesJson().readText()) as? Map<*, *>
            ?: error("sources.json must be a JSON object")
        val rawEntries = root["sources"] as? List<*> ?: error("sources.json must carry a \"sources\" array")
        rawEntries.map { entry ->
            entry as? Map<*, *> ?: error("each source entry must be an object")
            @Suppress("UNCHECKED_CAST")
            decodeFacts(entry as Map<String, Any?>)
        }
    }

    private fun locateSourcesJson(): File {
        val start = File(System.getProperty("user.dir"))
        return generateSequence(start) { it.parentFile }
            .take(4)
            .map { File(it, "sources.json") }
            .firstOrNull { it.isFile }
            ?: error("sources.json not found above ${start.absolutePath}")
    }

    @Test
    fun `SourceRegistry mirrors sources json exactly`() {
        assertEquals(decoded.sortedBy { it.id }, SourceRegistry.entries.sortedBy { it.id })
    }

    @Test
    fun `json ids are unique and match the registry id set`() {
        val ids = decoded.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.toSet(), SourceRegistry.ids())
    }

    @Test
    fun `registry order follows the json order`() {
        assertEquals(decoded.sortedBy { it.order }.map { it.id }, SourceRegistry.orderedSourceIds())
    }

    @Test
    fun `4read is the one scam source - the carrier and the registry agree`() {
        assertEquals(setOf("4read"), decoded.filter { it.scam }.map { it.id }.toSet())
        assertEquals(setOf("4read"), SourceRegistry.scamIds())
        assertTrue(SourceRegistry.isScam("4read"))
        assertFalse(SourceRegistry.isScam("soundbooks"))
    }

    @Test
    fun `live fourread search door pins the registry template`() {
        val template = requireNotNull(SourceRegistry.searchDoorTemplate("4read"))
        val query = "Кобзар  Т. Шевченко"
        assertEquals(
            fourReadSearchUrl(query),
            template.replace("{q}", java.net.URLEncoder.encode(query.trim(), "UTF-8"))
        )
    }

    @Test
    fun `referer rules apply within their host scope only`() {
        // Scoped to the source's own audio hosts (exact or subdomain).
        assertEquals(
            mapOf("Referer" to "https://4read.org/"),
            SourceRegistry.refererHeaderFor("4read", "https://s1.reasd.org/audio.mp3")
        )
        // Never onto a foreign host (SEC-004).
        assertEquals(
            emptyMap<String, String>(),
            SourceRegistry.refererHeaderFor("4read", "https://archive.org/details/x")
        )
        assertEquals(
            mapOf("Referer" to "https://sound-books.net/"),
            SourceRegistry.refererHeaderFor("soundbooks", "https://arch.sound-books.net/x.mp3")
        )
        assertEquals(
            emptyMap<String, String>(),
            SourceRegistry.refererHeaderFor("soundbooks", "https://sound-books.net/page")
        )
        // Unscoped rules apply to any stream host.
        assertEquals(
            mapOf("Referer" to "https://sluhay.com/"),
            SourceRegistry.refererHeaderFor("sluhay", "https://cdn.redirectto.cc/x.mp3")
        )
        // Sources without a rule never carry a Referer.
        assertEquals(
            emptyMap<String, String>(),
            SourceRegistry.refererHeaderFor("lihtar", "https://lihtar.in.ua/x.mp3")
        )
    }

    // --- strict decoder (test-local; the registry mirror is the production reader) ---

    private fun decodeFacts(raw: Map<String, Any?>): SourceFacts = SourceFacts(
        id = raw.requireString("id"),
        displayName = raw.requireString("displayName"),
        homeUrl = raw.string("homeUrl"),
        contentLanguage = raw.string("contentLanguage"),
        accessMode = SourceAccessMode.valueOf(raw.requireString("accessMode")),
        order = raw.int("order"),
        streamOnly = raw.bool("streamOnly"),
        scam = raw.bool("scam"),
        referer = raw.map("referer")?.let {
            RefererRule(value = it.requireString("value"), scopeHosts = it.stringSet("scopeHosts"))
        },
        // Nullable fields decode absent → null (never ""), exactly like the registry.
        searchUrl = raw.string("searchUrl").takeIf(String::isNotBlank),
        searchHeaders = raw.map("searchHeaders")
            ?.mapValues { (_, value) -> value as? String ?: "" }
            ?: emptyMap(),
        catalogUrl = raw.string("catalogUrl").takeIf(String::isNotBlank),
        transportHosts = raw.stringSet("transportHosts"),
        browserProfile = raw.map("browserProfile")?.let {
            BrowserProfileFacts(
                pageHosts = it.stringSet("pageHosts"),
                audioHosts = it.stringSet("audioHosts"),
                homeUrl = it.string("homeUrl").takeIf(String::isNotBlank),
                entryNotice = it.string("entryNotice").takeIf(String::isNotBlank),
                manifestProbe = it.bool("manifestProbe"),
                manifestPlaceholder = it.string("manifestPlaceholder").takeIf(String::isNotBlank),
                manifestPlaceholderReplacement = it.string("manifestPlaceholderReplacement").takeIf(String::isNotBlank),
                searchDoor = it.string("searchDoor").takeIf(String::isNotBlank),
                releaseBrowserDoor = it.bool("releaseBrowserDoor")
            )
        }
    )

    private fun Map<String, Any?>.requireString(key: String): String =
        this[key] as? String ?: error("sources.json: \"$key\" must be a string")

    private fun Map<String, Any?>.string(key: String): String = (this[key] as? String).orEmpty()

    private fun Map<String, Any?>.int(key: String): Int =
        (this[key] as? Number)?.toInt() ?: error("sources.json: \"$key\" must be a number")

    /**
     * Absent optional booleans decode to the data-class default (false) — a
     * source that does not declare the fact is not an error. A WRONG type
     * still fails: the registry mirror declares every fact with its real
     * value, so the full-equality assertion catches any mismatch.
     */
    private fun Map<String, Any?>.bool(key: String): Boolean =
        this[key] as? Boolean ?: false

    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? =
        this[key] as? Map<String, Any?>

    private fun Map<String, Any?>.stringSet(key: String): Set<String> =
        ((this[key] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { it as? String }
            .toSet()
}