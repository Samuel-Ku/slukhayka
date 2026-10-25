package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1389 — the switch that returns Polish is guarded by COVERAGE, not by a
 * comment.
 *
 * The ticket's rule is «return the language only when not a single line is
 * left untranslated». A missing key does not fail loudly at runtime: Android
 * silently falls back to `values/` (Ukrainian), so the listener sees two
 * languages on one screen — exactly the #1385 mixture. This guard therefore
 * asks the `pl` resource set, key by key, whether every key that `values/`
 * defines has a real Polish value.
 *
 * The area is ALL of `values/`, not only the screens batch 3 touched: the
 * earlier batch guards derived their area from the sources they owned, and a
 * line nobody owned would otherwise stay Ukrainian forever. Source-derived
 * batches remain useful while the language is off; the final switch needs the
 * whole set.
 *
 * Resolution follows what a `pl-PL` device actually gets: a value in
 * `values-pl-rPL` wins over `values-pl`, which wins over `values/`. The
 * `values-pl/strings.xml` file deliberately stays Ukrainian (Material3
 * TalkBack overrides, #371/#1385), so the keys it holds are required to have
 * their Polish twin in `values-pl-rPL`.
 *
 * Failures caught here, all invisible to a «file exists» check:
 *  * a key absent from both Polish qualifiers — Ukrainian fallback;
 *  * a value carrying Cyrillic letters — a copied source line, the #1385 defect
 *    (the brand name `app_name` is the one deliberate exception);
 *  * a placeholder lost or renamed (`%1$d` → `%1$s`, `%%` → `%`) — the app
 *    would throw at `String.format` time on a Polish device only;
 *  * a plural quantity missing — Android would use another quantity's text.
 */
class PolishCoverageGuardTest {

    /** `app/` — Gradle runs unit tests with cwd = app/, other runners with cwd = repo root. */
    private val appRoot: File by lazy {
        listOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir"), "app"))
            .firstOrNull { File(it, "src/main/res").isDirectory }
            ?: error("app root not found from ${System.getProperty("user.dir")}")
    }

    private val res: File get() = File(appRoot, "src/main/res")

    private data class Entry(
        val value: String,
        val pluralItems: Map<String, String> = emptyMap(),
    )

    private val stringNode =
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val pluralNode =
        Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
    private val pluralItem =
        Regex("""<item\s+quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

    /** `key` → every value of that key in one resource qualifier, as raw XML text. */
    private fun entriesIn(directory: File): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".xml") }
            .sortedBy { it.name }
            .forEach { file ->
                val text = file.readText(charset = Charsets.UTF_8)
                stringNode.findAll(text).forEach {
                    out[it.groupValues[1]] = Entry(it.groupValues[2])
                }
                pluralNode.findAll(text).forEach { match ->
                    val items = pluralItem.findAll(match.groupValues[2]).associate {
                        it.groupValues[1] to it.groupValues[2]
                    }
                    out[match.groupValues[1]] = Entry(match.groupValues[2], items)
                }
            }
        return out
    }

    private val ukrainian: Map<String, Entry> by lazy { entriesIn(res.resolve("values")) }

    /**
     * The resolved `pl` set: `values-pl-rPL` (more specific on a Polish device)
     * overlays `values-pl`.
     */
    private val polish: Map<String, Entry> by lazy {
        entriesIn(res.resolve("values-pl")) + entriesIn(res.resolve("values-pl-rPL"))
    }

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")
    private val placeholder = Regex("""%(?:\d+\$)?[sd]|%%""")

    @Test
    fun `every ukrainian key has a polish value`() {
        assertTrue(
            "the Ukrainian source produced ${ukrainian.size} keys — the resource walk found " +
                "nothing, so a green run here would prove nothing",
            ukrainian.size >= 1000
        )
        val missing = ukrainian.keys.filterNot { polish.containsKey(it) }.sorted()
        assertTrue(
            "these lines are absent from `values-pl`/`values-pl-rPL` and would silently fall " +
                "back to Ukrainian — the #1385 mixture the ticket forbids:\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty()
        )
    }

    @Test
    fun `no polish value is a copied ukrainian line`() {
        // `app_name` is the product's own name, not interface prose: it stays
        // «Слухайка» in every locale by design.
        val copies = ukrainian.keys.mapNotNull { key ->
            if (key == "app_name") return@mapNotNull null
            val value = polish[key]?.value ?: return@mapNotNull null
            if (cyrillic.containsMatchIn(value)) key else null
        }.sorted()
        assertTrue(
            "these `pl` lines still carry Cyrillic letters — they were copied from `values/` " +
                "instead of translated, which is the #1385 mixture:\n  " +
                copies.joinToString("\n  "),
            copies.isEmpty()
        )
    }

    @Test
    fun `placeholders match the ukrainian source`() {
        val offenders = ukrainian.keys.mapNotNull { key ->
            val source = ukrainian[key] ?: return@mapNotNull null
            val translated = polish[key] ?: return@mapNotNull null
            if (source.pluralItems.isEmpty()) {
                val expected = placeholder.findAll(source.value).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(translated.value).map { it.value }.sorted().toList()
                if (expected == actual) null else "$key: expected $expected, found $actual"
            } else {
                source.pluralItems.entries.mapNotNull { (quantity, sourceText) ->
                    val translatedText = translated.pluralItems[quantity]
                        ?: return@mapNotNull "$key/$quantity: quantity missing from `pl`"
                    val expected =
                        placeholder.findAll(sourceText).map { it.value }.sorted().toList()
                    val actual =
                        placeholder.findAll(translatedText).map { it.value }.sorted().toList()
                    if (expected == actual) null else
                        "$key/$quantity: expected $expected, found $actual"
                }.firstOrNull()
            }
        }.sorted()
        assertTrue(
            "placeholders differ from the Ukrainian source, so `String.format` could throw on a " +
                "Polish device:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty()
        )
    }

    @Test
    fun `every plural keeps the ukrainian quantities`() {
        val missing = ukrainian.keys.filter { ukrainian.getValue(it).pluralItems.isNotEmpty() }
            .flatMap { key ->
                val translated = polish[key]?.pluralItems ?: return@flatMap listOf("$key: no plural")
                ukrainian.getValue(key).pluralItems.keys
                    .filterNot { it in translated }
                    .map { "$key/$it: quantity missing from `pl`" }
            }
            .sorted()
        assertTrue(
            "these plural quantities would fall back to another form on a Polish device:\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty()
        )
    }
}
