package com.slukhayka.audiobooks.testing

import com.slukhayka.audiobooks.AppLocale
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1389 — Polish, batch 3: the language is RETURNED to the supported set, and
 * that return is only allowed with zero missing lines.
 *
 * The ticket's last two acceptance criteria are inseparable:
 *  * «Польська мова повернута в підтримувані локалі»;
 *  * «Є перевірка, яка падає, якщо польська повернута з неповним покриттям».
 *
 * So this guard derives the whole reachable string surface from the app's own
 * Kotlin sources (every `R.string` / `R.plurals` reference, not a hand-kept
 * list of keys) and asks `values-pl` three questions: does the value exist, is
 * it really Polish (Cyrillic means a Ukrainian copy, the #1385 defect), and do
 * its format placeholders match the Ukrainian source. If [AppLocale.SYSTEM_FOLLOWS]
 * contains `pl` while any of that is false, this test fails — the switch cannot
 * be turned on over a half-translated set, and nobody has to remember that rule.
 *
 * Language names are the one deliberate exception: «Українська» and «English»
 * are endonyms shown as-is on the interface-language screen, so they are
 * excluded from the "no Cyrillic" check by name rather than by weakening the rule.
 */
class PolishCoverageGuardTest {

    private val appRoot: File by lazy {
        listOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir"), "app"))
            .firstOrNull { File(it, "src/main/res").isDirectory }
            ?: error("app root not found from ${System.getProperty("user.dir")}")
    }

    private val res: File get() = File(appRoot, "src/main/res")

    /** Every string key the application source can reach. */
    private val referenced: Map<String, String> by lazy {
        val reference = Regex("""R\.(string|plurals)\.([a-z0-9_]+)""")
        val found = LinkedHashMap<String, String>()
        File(appRoot, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { source ->
                reference.findAll(source.readText(charset = Charsets.UTF_8)).forEach { match ->
                    found.putIfAbsent(match.groupValues[2], match.groupValues[1])
                }
            }
        assertTrue(
            "the source walk produced ${found.size} keys — a green run here would prove nothing",
            found.size >= 900
        )
        found
    }

    private fun valuesIn(directory: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val stringNode = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val pluralNode = Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".xml") }
            .sortedBy { it.name }
            .forEach { file ->
                val text = file.readText(charset = Charsets.UTF_8)
                stringNode.findAll(text).forEach { out[it.groupValues[1]] = it.groupValues[2] }
                pluralNode.findAll(text).forEach { out[it.groupValues[1]] = it.groupValues[2] }
            }
        return out
    }

    private val ukrainian: Map<String, String> by lazy { valuesIn(res.resolve("values")) }
    private val polish: Map<String, String> by lazy { valuesIn(res.resolve("values-pl")) }

    /** Endonyms deliberately rendered in their own script on the language screen. */
    private val endonyms = setOf("app_locale_ukrainian", "content_language_uk")

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")
    private val placeholder = Regex("""%(?:\d+\$)?[sd]|%%""")
    private val quantityItem = Regex("""<item\s+quantity="([a-z]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

    private fun quantitiesOf(raw: String): Map<String, List<String>> =
        quantityItem.findAll(raw).associate { match ->
            match.groupValues[1] to placeholder.findAll(match.groupValues[2]).map { it.value }.sorted().toList()
        }

    @Test
    fun `every line the app can reach has a polish value`() {
        val missing = referenced.keys.filterNot { polish.containsKey(it) }.sorted()
        assertTrue(
            "these lines are reachable but absent from `values-pl`, so a Polish device falls " +
                "back to Ukrainian:\n  " + missing.joinToString("\n  "),
            missing.isEmpty()
        )
    }

    @Test
    fun `no reachable line in the polish set is a ukrainian copy`() {
        val copies = referenced.keys.mapNotNull { key ->
            val value = polish[key] ?: return@mapNotNull null
            if (key in endonyms) null else if (cyrillic.containsMatchIn(value)) key else null
        }.sorted()
        assertTrue(
            "these `values-pl` lines still carry Cyrillic letters — copied from `values/` " +
                "instead of translated, the #1385 mixture:\n  " + copies.joinToString("\n  "),
            copies.isEmpty()
        )
    }

    @Test
    fun `placeholders in the polish set match the ukrainian source`() {
        val offenders = referenced.keys.mapNotNull { key ->
            val ukrainianText = ukrainian[key] ?: return@mapNotNull "$key: no Ukrainian source"
            val polishText = polish[key] ?: return@mapNotNull null
            val ukrainianQuantities = quantitiesOf(ukrainianText)
            val polishQuantities = quantitiesOf(polishText)
            if (ukrainianQuantities.isEmpty() && polishQuantities.isEmpty()) {
                val expected = placeholder.findAll(ukrainianText).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(polishText).map { it.value }.sorted().toList()
                return@mapNotNull if (expected == actual) null else "$key: expected $expected, found $actual"
            }
            val fallback = ukrainianQuantities["other"]
            val diff = polishQuantities.mapNotNull { (quantity, actual) ->
                val expected = ukrainianQuantities[quantity] ?: fallback
                    ?: return@mapNotNull "$key/$quantity: no Ukrainian quantity to compare"
                if (expected == actual) null else "$key/$quantity: expected $expected, found $actual"
            }
            if (diff.isEmpty()) null else diff.joinToString("; ")
        }.sorted()
        assertTrue(
            "placeholders differ from the Ukrainian source, so `String.format` could throw on a " +
                "Polish device:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty()
        )
    }

    @Test
    fun `a key lives in exactly one file of the pl qualifier`() {
        val owners = HashMap<String, MutableList<String>>()
        (res.resolve("values-pl").listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".xml") }
            .forEach { file ->
                val text = file.readText(charset = Charsets.UTF_8)
                Regex("""<(string|plurals)\s+name="([^"]+)"""").findAll(text).forEach { match ->
                    owners.getOrPut(match.groupValues[2]) { mutableListOf() }.add(file.name)
                }
            }
        val clashes = owners.filterValues { it.size > 1 }
        assertTrue(
            "these keys are defined twice in the `pl` qualifier — aapt rejects the build:\n  " +
                clashes.entries.joinToString("\n  ") { "${it.key}: ${it.value}" },
            clashes.isEmpty()
        )
    }

    @Test
    fun `polish is enabled only with complete coverage`() {
        val enabled = "pl" in AppLocale.SYSTEM_FOLLOWS || AppLocale.entries.any { it.tag == "pl" }
        val missing = referenced.keys.filterNot { polish.containsKey(it) }
        assertTrue(
            "Polish is switched on (SYSTEM_FOLLOWS / AppLocale.POLISH) while " +
                "${missing.size} reachable line(s) have no Polish value — that is the #1385 " +
                "mixture returning:\n  " + missing.sorted().joinToString("\n  "),
            !enabled || missing.isEmpty()
        )
        assertTrue(
            "the switch must actually be ON after #1389 — otherwise this guard tests an " +
                "unreachable state",
            enabled
        )
        // Polish needs all four plural categories; Android Lint enforces the
        // same rule, and a missing category falls back to `other` at runtime.
        val incomplete = polish.filterValues { it.contains("<item quantity=") }.mapNotNull { (name, raw) ->
            val categories = quantitiesOf(raw).keys
            if (categories.containsAll(listOf("one", "few", "many", "other"))) null
            else "$name: ${categories.sorted()}"
        }.sorted()
        assertTrue(
            "these Polish plurals miss a category Polish requires:\n  " +
                incomplete.joinToString("\n  "),
            incomplete.isEmpty()
        )
    }
}
