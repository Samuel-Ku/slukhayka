package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec-46 T17 (#578, ADR-0033) — «нуль старих імен» for the canonical-UI sweep.
 *
 * v1.4 C2 settled ONE portrait card — the canonical `PosterCard`, whose
 * union-card overload absorbed the «Увесь каталог» card — and #567 removed the
 * last two named row wrappers (`GlobalSearchResultCard`, `WorkFeedCard`). What
 * #578 sweeps up is the residue those renames left behind: the snapshot class
 * still named after the deleted union card, its five goldens, and the in-source
 * notes that kept spelling the deleted wrappers out.
 *
 * The scan is a source scan on purpose — the same guard style
 * [CanonicalRowWrappersGuardTest] and `HardcodedColorGuardTest` use, widened to
 * cover `src/test` as well as `src/main`: the deleted names must not survive in
 * either tree, or the next feature reaches for the old dialect instead of the
 * canonical component. It matches DECLARATIONS and imports, not any mention:
 * the live `unified_catalog_*` test-tag contract in `PosterCard` and the
 * historical «the wrapper is gone» notes in the surviving tests legitimately
 * still carry the words, so an any-line scan would fail on them rather than on
 * a revived component.
 */
class ResidualOldNamesGuardTest {

    private val sourceRoots: List<File> by lazy {
        SOURCE_ROOTS.map { relative ->
            // Gradle runs unit tests with cwd = app/.
            listOf(
                File(System.getProperty("user.dir"), relative),
                File(System.getProperty("user.dir"), "app/$relative")
            ).firstOrNull { it.isDirectory }
                ?: error("source root $relative not found from ${System.getProperty("user.dir")}")
        }
    }

    private val snapshotsDir: File by lazy {
        listOf(
            File(System.getProperty("user.dir"), "src/test/snapshots"),
            File(System.getProperty("user.dir"), "app/src/test/snapshots")
        ).firstOrNull { it.isDirectory }
            ?: error("snapshots dir not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the deleted canonical-ui names are declared nowhere in main or test sources`() {
        val offenders = sourceRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val text = file.readText()
                    DELETED_NAMES
                        .filter { name -> declaresOrImports(text, name) }
                        .map { "${file.relativeTo(root).invariantSeparatorsPath}: $it" }
                }
        }

        assertTrue(
            "${DELETED_NAMES.joinToString()} must stay deleted — found in: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the deleted union-card goldens are gone from the snapshot set`() {
        val survivors = DELETED_GOLDENS.filter { File(snapshotsDir, it).isFile }

        assertTrue(
            "these goldens belonged to a deleted snapshot class: $survivors",
            survivors.isEmpty()
        )
    }

    private fun declaresOrImports(text: String, name: String): Boolean {
        val declaration = Regex(
            """\b(?:class|interface|object|fun|val|typealias)\s+${Regex.escape(name)}\w*\b"""
        )
        val import = Regex("""^import\s+\S*\.${Regex.escape(name)}\w*\s*$""", RegexOption.MULTILINE)
        return declaration.containsMatchIn(text) || import.containsMatchIn(text)
    }

    private companion object {
        /** Paths relative to the `app/` module, the same two trees the criterion names. */
        val SOURCE_ROOTS = listOf(
            "src/main/java/com/slukhayka/audiobooks",
            "src/test/java/com/slukhayka/audiobooks"
        )

        // Built at runtime so the guard file itself never matches.
        val DELETED_NAMES = listOf(
            "UnifiedCatalog" + "Card",
            "GlobalSearchResult" + "Card",
            "WorkFeed" + "Card"
        )

        val DELETED_GOLDENS = listOf(
            "unified_catalog_card.png",
            "unified_catalog_download_affordance.png",
            "unified_catalog_downloaded.png",
            "unified_catalog_download_progress.png",
            "unified_catalog_stream_only.png"
        )
    }
}
