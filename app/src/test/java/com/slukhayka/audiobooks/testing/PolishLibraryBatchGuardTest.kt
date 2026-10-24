package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1387 — batch 1 of the Polish return: library, shelves, saved.
 *
 * The ticket's acceptance criterion is not «a `values-pl` file exists»: an
 * untranslated line falls back to Ukrainian, so a file with one missing key
 * looks complete until a Polish listener opens that screen. This guard
 * therefore DERIVES the batch's area from the sources that own the three
 * surfaces — every `R.string` / `R.plurals` key the library, collections and
 * saved screens can reach — and then asks the `pl` resource set, key by key,
 * whether that exact line is really Polish.
 *
 * The area is source-derived on purpose: a new string added to one of these
 * screens turns this test red until that line is translated, instead of
 * quietly falling back. The list below is the batch's boundary, so a later
 * batch (#1388/#1389) adds its own sources rather than widening this one.
 *
 * Three failures this catches, all of which a "file exists" check misses:
 *  * a key missing from `values-pl` — the listener sees Ukrainian;
 *  * a value copied from `values/` — Cyrillic letters in the Polish set mean
 *    the line was not translated, which is what #1385 was filed about;
 *  * a placeholder lost or renamed (`%1$d` → `%1$s`, `%%` → `%`) — the app
 *    would throw at `String.format` time on a Polish device only.
 *
 * A library that renders a wrong-looking string but not a missing one is out
 * of scope here on purpose: this is a completeness guard, not a linguistic
 * review.
 */
class PolishLibraryBatchGuardTest {

    /** `app/` — Gradle runs unit tests with cwd = app/, other runners with cwd = repo root. */
    private val appRoot: File by lazy {
        listOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir"), "app"))
            .firstOrNull { File(it, "src/main/res").isDirectory }
            ?: error("app root not found from ${System.getProperty("user.dir")}")
    }

    private val res: File get() = File(appRoot, "src/main/res")

    /** The batch boundary: the sources that own the library / shelves / saved surfaces. */
    private val areaSources: List<File> by lazy {
        val files = listOf(
            "src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryScreen.kt",
            "src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryFilterSheet.kt",
            "src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryImportSheet.kt",
            "src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryBookGrid.kt",
            "src/main/java/com/slukhayka/audiobooks/ui/screens/CollectionsIndexScreen.kt",
            "src/main/java/com/slukhayka/audiobooks/ui/screens/PeopleScreen.kt",
            "src/main/java/com/slukhayka/audiobooks/ui/library/LibraryModel.kt",
        ).map { File(appRoot, it) }
        val collections = File(
            appRoot,
            "src/main/java/com/slukhayka/audiobooks/ui/screens/collections"
        ).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val missing = files.filterNot { it.isFile }
        assertTrue(
            "the batch area lists sources that no longer exist (the area moved, not the test): " +
                missing.joinToString { it.path },
            missing.isEmpty()
        )
        files + collections
    }

    private val reference = Regex("""R\.(string|plurals|array)\.([a-z0-9_]+)""")

    /** `key` → every value of that key in one resource qualifier, as raw XML text. */
    private fun valuesIn(directory: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val stringNode = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val pluralNode = Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".xml") }
            .sortedBy { it.name }
            .forEach { file ->
                val text = file.readText(encoding = Charsets.UTF_8)
                stringNode.findAll(text).forEach { out[it.groupValues[1]] = it.groupValues[2] }
                pluralNode.findAll(text).forEach { out[it.groupValues[1]] = it.groupValues[2] }
            }
        return out
    }

    private val ukrainian: Map<String, String> by lazy { valuesIn(res.resolve("values")) }
    private val polish: Map<String, String> by lazy { valuesIn(res.resolve("values-pl")) }

    /** Every `R.string` / `R.plurals` the batch area names. */
    private fun area(): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        areaSources.forEach { source ->
            val text = source.readText(encoding = Charsets.UTF_8)
            reference.findAll(text).forEach { match ->
                val kind = match.groupValues[1]
                val key = match.groupValues[2]
                if (kind == "array") {
                    throw AssertionError(
                        "${source.name} reaches the string array `$key`; add array support to " +
                            "this guard (or keep the array out of the batch area) instead of " +
                            "letting it fall back to Ukrainian silently"
                    )
                }
                found[key] = kind
            }
        }
        assertTrue(
            "the batch area produced ${found.size} keys — the source walk found nothing, so a " +
                "green run here would prove nothing",
            found.size >= 200
        )
        return found
    }

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")
    private val placeholder = Regex("""%(?:\d+\$)?[sd]|%%""")

    @Test
    fun `every line in the batch area has a polish value`() {
        val missing = area().keys.filterNot { polish.containsKey(it) }.sorted()
        assertTrue(
            "these library/shelves/saved lines are absent from `values-pl` and would fall back " +
                "to Ukrainian:\n  " + missing.joinToString("\n  "),
            missing.isEmpty()
        )
    }

    @Test
    fun `no polish line in the batch area is a copy of the ukrainian one`() {
        val copies = area().keys.mapNotNull { key ->
            val value = polish[key] ?: return@mapNotNull null
            if (cyrillic.containsMatchIn(value)) key else null
        }.sorted()
        assertTrue(
            "these `values-pl` lines still carry Cyrillic letters — they were copied from " +
                "`values/` instead of translated, which is the #1385 mixture:\n  " +
                copies.joinToString("\n  "),
            copies.isEmpty()
        )
    }

    @Test
    fun `placeholders in the batch area match the ukrainian source`() {
        val offenders = area().keys.mapNotNull { key ->
            val ukrainianText = ukrainian[key] ?: return@mapNotNull "$key: no Ukrainian source"
            val polishText = polish[key] ?: return@mapNotNull null
            val expected = placeholder.findAll(ukrainianText).map { it.value }.sorted().toList()
            val actual = placeholder.findAll(polishText).map { it.value }.sorted().toList()
            if (expected == actual) null else "$key: expected $expected, found $actual"
        }.sorted()
        assertTrue(
            "placeholders differ from the Ukrainian source, so `String.format` could throw on a " +
                "Polish device:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty()
        )
    }

    @Test
    fun `a key lives in exactly one file of the pl qualifier`() {
        // Two definitions in the same qualifier are an aapt error, and a build
        // that never reaches this point tells the reviewer nothing about which
        // file is authoritative. The override file and the translation file
        // must never both claim a key.
        val owners = HashMap<String, MutableList<String>>()
        (res.resolve("values-pl").listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".xml") }
            .forEach { file ->
                val text = file.readText(encoding = Charsets.UTF_8)
                Regex("""<(string|plurals)\s+name="([^"]+)"""").findAll(text).forEach { match ->
                    owners.getOrPut(match.groupValues[2]) { mutableListOf() }.add(file.name)
                }
            }
        val clashes = owners.filterValues { it.size > 1 }
        assertTrue(
            "these keys are defined twice in the `pl` qualifier — aapt rejects the build, and " +
                "nothing says which definition the app would speak:\n  " +
                clashes.entries.joinToString("\n  ") { "${it.key}: ${it.value}" },
            clashes.isEmpty()
        )
    }
}
