package com.slukhayka.audiobooks.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.slukhayka.audiobooks.ui.components.CtaRadiusGuard
import com.slukhayka.audiobooks.ui.theme.PaperInkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1771 (unslop audit 2026-11-01, U6/U7) — one heading ladder, no synthetic
 * weight.
 *
 * The paper direction records exactly four heading steps in
 * [PaperInkType.Heading]: page (34), section (21), sub (16), group (14). Before
 * the ticket the two lower steps existed only as call-site arithmetic —
 * `titleMedium.copy(fontWeight = Bold)` and `titleSmall.copy(fontWeight =
 * Bold)` — so the same hierarchy was written dozens of times and any screen
 * could invent a step. In paper those M3 slots are already SemiBold/Bold, which
 * made every such `Bold` a synthetic weight rather than a call for a heavier
 * cut; on the serif it is worse, because [PaperInkType.Book] ships ONE face
 * (`literata_semibold.ttf`) and `FontWeight.Bold` asks the renderer to fake a
 * second one.
 *
 * This guard reads the four steps and the M3 slot map from the LIVE token
 * objects, not from a copy of their numbers, so changing a weight in
 * `PaperInk.kt` changes what the scan enforces. It flags:
 *
 * 1. `PaperInkType.<token>.copy(fontWeight = W)` anywhere under `ui/` (paper
 *    tokens are paper-only, so there is no legacy dialect to attribute the
 *    site to) when the token's own weight is already SemiBold or heavier and
 *    `W` is that weight or heavier — a stack, not a reduction; and any weight
 *    override on a `PaperInkType.Book*` token, the single-face serif;
 * 2. `MaterialTheme.typography.<slot>.copy(fontWeight = W)` outside the token
 *    layer and outside the legacy dialect, judged against
 *    [PaperInkType.typography]'s own slot weights (the same rule);
 * 3. an inline `TextStyle(…)` in the same scope that names both
 *    `PaperInkType.Book` and an extra-bold weight.
 *
 * It also asserts the ladder itself (four steps, descending, all Onest) and
 * that the migrated heading surfaces actually reach the ladder.
 *
 * **Named limits** (read, not assumed):
 * - This is a SOURCE scan. It cannot see a `TextStyle` assembled field by
 *   field from a token (that copy is out of reach), it cannot prove what the
 *   renderer does with a weight it cannot find a face for, and it cannot prove
 *   the four steps look right — the re-recorded Roborazzi goldens and the
 *   review of the diff hold the pixels. Only a literal
 *   `fontWeight = FontWeight.X` argument is read; a weight passed through a
 *   variable (`if (current) Bold else Medium`) is invisible here.
 * - It reads call sites, not composition. A `Text` that receives a style from
 *   a parameter or a `when` branch is somebody else's variable here.
 * - The legacy v1.4 dialect is exempt from the M3-slot rule by file
 *   ([legacyDialectFiles]): there `titleMedium` is Medium and `titleSmall` is
 *   Medium, so `Bold` really does ask for a heavier cut, and moving those
 *   files is the header migration's ticket (audit U10), not this one. The
 *   exemption is file-wide: a paper branch inside a shared component is not
 *   attributed per branch, so the token-level rule is what protects those
 *   files. A NEW paper screen is not on the list and is scanned in full —
 *   the fail-closed direction.
 */
class HeadingLadderGuardTest {

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks/ui"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks/ui")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("ui source root not found from ${System.getProperty("user.dir")}")
    }

    /**
     * The un-migrated v1.4 dialect (audit U10): the nine `IndexScreenScaffold`
     * destinations, the legacy tab header, the shared components that still
     * carry a legacy branch, and the fullscreen source browser. Their M3 slots
     * are the legacy scale; their paper branches are protected by the
     * token-level rule instead.
     */
    private val legacyDialectFiles = setOf(
        "components/IndexScreenScaffold.kt",
        "components/AppTabHeader.kt",
        "components/DesignSystem.kt",
        "components/BookRow.kt",
        "components/SectionHeaders.kt",
        "components/NavigationChip.kt",
        "screens/AuthorDiscovery.kt",
        "screens/BookListScreen.kt",
        "screens/CollectionsIndexScreen.kt",
        "screens/LibraryRatingScreen.kt",
        "screens/PeopleScreen.kt",
        "screens/PersonBooksScreen.kt",
        "screens/SeriesIndexScreen.kt",
        "screens/SeriesScreen.kt",
        "screens/WebSourceBrowserScreen.kt"
    )

    /**
     * The debug overlay is a developer tool, not a surface of the direction:
     * it names its own monospace family and its own sizes, so the paper slots'
     * weights do not describe what it renders. Its weights are out of the
     * M3-slot rule; nothing else is exempt for this reason.
     */
    private val debugOnlyFiles = setOf("components/PlayerDebugOverlay.kt")

    /** One call site that overrides the weight its own token already carries. */
    data class Offence(val file: String, val expression: String, val reason: String)

    // ── the scan ────────────────────────────────────────────────────────────

    private val tokenCopy = Regex(
        """\bPaperInkType\.([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?)\s*\.\s*copy\s*\("""
    )
    private val slotCopy = Regex(
        """\bMaterialTheme\.typography\.([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*copy\s*\("""
    )

    /** `FontWeight.Bold`, including a fully-qualified `a.b.FontWeight.Bold`. */
    private val weightArgument =
        Regex("""\bfontWeight\s*=\s*(?:[A-Za-z_][A-Za-z0-9_.]*\.)?FontWeight\.([A-Za-z]+)""")
    private val textStyleCall = Regex("""\bTextStyle\s*\(""")
    private val bookFamily =
        Regex("""\bfontFamily\s*=\s*(?:[A-Za-z0-9_.]*\.)?PaperInkType\.Book\b""")

    private fun read(file: File): String = file.readText()

    private fun weightOf(name: String): FontWeight? = when (name) {
        "Thin" -> FontWeight.Thin
        "ExtraLight" -> FontWeight.ExtraLight
        "Light" -> FontWeight.Light
        "Normal" -> FontWeight.Normal
        "Medium" -> FontWeight.Medium
        "SemiBold" -> FontWeight.SemiBold
        "Bold" -> FontWeight.Bold
        "ExtraBold" -> FontWeight.ExtraBold
        "Black" -> FontWeight.Black
        else -> null
    }

    /** The paper token for a `PaperInkType.<path>` name, or null when unknown. */
    private fun tokenStyle(path: String): TextStyle? = when (path) {
        "ScreenTitle" -> PaperInkType.ScreenTitle
        "SectionTitle" -> PaperInkType.SectionTitle
        "BookTitleHero" -> PaperInkType.BookTitleHero
        "BookTitlePage" -> PaperInkType.BookTitlePage
        "BookTitlePlayer" -> PaperInkType.BookTitlePlayer
        "MiniTitle" -> PaperInkType.MiniTitle
        "RowTitle" -> PaperInkType.RowTitle
        "Body" -> PaperInkType.Body
        "BodyStrong" -> PaperInkType.BodyStrong
        "Meta" -> PaperInkType.Meta
        "Label" -> PaperInkType.Label
        "Micro" -> PaperInkType.Micro
        "Tab" -> PaperInkType.Tab
        "TrackingLabel" -> PaperInkType.TrackingLabel
        "Timer" -> PaperInkType.Timer
        "TimerXL" -> PaperInkType.TimerXL
        "StatXL" -> PaperInkType.StatXL
        "EmptyTitle" -> PaperInkType.EmptyTitle
        "Heading.Page" -> PaperInkType.Heading.Page
        "Heading.Section" -> PaperInkType.Heading.Section
        "Heading.Sub" -> PaperInkType.Heading.Sub
        "Heading.Group" -> PaperInkType.Heading.Group
        else -> null
    }

    /** The paper slot for a `MaterialTheme.typography.<slot>` name, or null. */
    private fun slotStyle(slot: String): TextStyle? {
        val typography = PaperInkType.typography
        return when (slot) {
            "displaySmall" -> typography.displaySmall
            "headlineLarge" -> typography.headlineLarge
            "headlineMedium" -> typography.headlineMedium
            "headlineSmall" -> typography.headlineSmall
            "titleLarge" -> typography.titleLarge
            "titleMedium" -> typography.titleMedium
            "titleSmall" -> typography.titleSmall
            "bodyLarge" -> typography.bodyLarge
            "bodyMedium" -> typography.bodyMedium
            "bodySmall" -> typography.bodySmall
            "labelLarge" -> typography.labelLarge
            "labelMedium" -> typography.labelMedium
            "labelSmall" -> typography.labelSmall
            else -> null
        }
    }

    /** The argument text of the call whose `(` sits at [open], or null when unbalanced. */
    private fun callArguments(masked: String, open: Int): String? {
        val close = CtaRadiusGuard.balancedEnd(masked, open) ?: return null
        return masked.substring(open + 1, close)
    }

    /** The offending weight overrides in one source text, attributed to [relative]. */
    fun offencesIn(relative: String, source: String): List<Offence> {
        val masked = CtaRadiusGuard.maskCommentsAndStrings(source)
        val inTokenLayer = relative.startsWith("theme/")
        val legacy = relative in legacyDialectFiles || relative in debugOnlyFiles
        val offences = mutableListOf<Offence>()

        fun judge(expression: String, path: String, open: Int, style: TextStyle?, book: Boolean) {
            if (style == null) {
                offences += Offence(
                    relative,
                    expression,
                    "`$path` is not a token this scanner knows — add it to the token " +
                        "map before it is used with a weight override"
                )
                return
            }
            val arguments = callArguments(masked, open) ?: return
            val requested = weightArgument.find(arguments)?.groupValues?.get(1)
                ?.let(::weightOf) ?: return
            val base = style.fontWeight ?: FontWeight.Normal
            if (book) {
                offences += Offence(
                    relative,
                    expression,
                    "Literata ships ONE face (`literata_semibold.ttf`), so a " +
                        "fontWeight override on its `$base` is either redundant or " +
                        "synthetic"
                )
                return
            }
            if (base.weight >= FontWeight.SemiBold.weight && requested.weight >= base.weight) {
                offences += Offence(
                    relative,
                    expression,
                    "`$path` already carries $base; `FontWeight.$requested` stacks a " +
                        "synthetic weight on top of it"
                )
            }
        }

        for (match in tokenCopy.findAll(masked)) {
            val path = match.groupValues[1]
            val style = tokenStyle(path)
            judge(match.value, path, match.range.last, style, style?.fontFamily == PaperInkType.Book)
        }

        if (!legacy && !inTokenLayer) {
            for (match in slotCopy.findAll(masked)) {
                val slot = match.groupValues[1]
                val style = slotStyle(slot)
                judge(
                    match.value,
                    "MaterialTheme.typography.$slot",
                    match.range.last,
                    style,
                    style?.fontFamily == PaperInkType.Book
                )
            }

            // A hand-built TextStyle that puts the single-face serif together
            // with an extra-bold weight is the same defect as (1), one level
            // lower: the family is named directly instead of borrowed from a
            // Book token.
            for (match in textStyleCall.findAll(masked)) {
                val arguments = callArguments(masked, match.range.last) ?: continue
                if (!bookFamily.containsMatchIn(arguments)) continue
                val requested = weightArgument.find(arguments)?.groupValues?.get(1) ?: continue
                val weight = weightOf(requested) ?: continue
                if (weight.weight > FontWeight.SemiBold.weight) {
                    offences += Offence(
                        relative,
                        "TextStyle(…) with PaperInkType.Book",
                        "the single-face serif cannot render `FontWeight.$requested` — " +
                            "the renderer would synthesize it"
                    )
                }
            }
        }
        return offences
    }

    private fun offencesIn(file: File): List<Offence> =
        offencesIn(file.relativeTo(sourceRoot).invariantSeparatorsPath, read(file))

    /** Every Kotlin file under `ui/`. */
    private fun scannedFiles(): List<File> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()

    // ── scanner self-test: a broken scanner must not pass as a clean tree ───

    @Test
    fun `the scanner catches a weight stacked on a SemiBold token`() {
        val offences = offencesIn(
            "screens/Example.kt",
            """
            Text(
                text = "x",
                style = PaperInkType.Heading.Sub.copy(fontWeight = FontWeight.Bold)
            )
            """.trimIndent()
        )
        assertEquals(
            "a Bold stacked on the ladder's SemiBold step is the synthetic weight " +
                "the ticket removes",
            1,
            offences.size
        )
    }

    @Test
    fun `the scanner catches a weight on the single-face serif`() {
        val offences = offencesIn(
            "screens/Example.kt",
            """
            Text(text = "x", style = PaperInkType.BookTitleHero.copy(fontWeight = FontWeight.Bold))
            Text(
                text = "y",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            )
            """.trimIndent()
        )
        assertEquals(
            "both a Book token and the paper headlineMedium slot are Literata; a " +
                "weight override on either is synthetic (and the second one spells " +
                "the weight as a fully-qualified name)",
            2,
            offences.size
        )
    }

    @Test
    fun `the scanner leaves a real weight change and a legacy site alone`() {
        val paper = offencesIn(
            "screens/Example.kt",
            """
            Text(text = "x", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            """.trimIndent()
        )
        val legacy = offencesIn(
            "screens/BookListScreen.kt",
            """
            Text(text = "x", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            """.trimIndent()
        )
        assertEquals(
            "Normal -> SemiBold on body text is a real distinction, not a stack",
            0,
            paper.size
        )
        assertEquals(
            "the legacy dialect's titleSmall is Medium, so its Bold is a real weight",
            0,
            legacy.size
        )
    }

    // ── the assertions ──────────────────────────────────────────────────────

    @Test
    fun `the four heading steps are recorded once, in descending order`() {
        val steps = listOf(
            "Page" to PaperInkType.Heading.Page,
            "Section" to PaperInkType.Heading.Section,
            "Sub" to PaperInkType.Heading.Sub,
            "Group" to PaperInkType.Heading.Group,
        )
        assertEquals(
            "the ladder holds exactly four steps (ADR-0033: no fifth size)",
            4,
            steps.size
        )
        steps.forEach { (name, style) ->
            assertEquals(
                "heading step `$name` must be Onest — the ladder is interface, " +
                    "not book titles",
                PaperInkType.Ui,
                style.fontFamily
            )
        }
        assertEquals(
            "the page step is the ExtraBold screen title",
            FontWeight.ExtraBold,
            PaperInkType.Heading.Page.fontWeight
        )
        assertEquals(
            "the section step is the Bold section title",
            FontWeight.Bold,
            PaperInkType.Heading.Section.fontWeight
        )
        assertEquals(
            "the four steps are 34/21/16/14, the numbers the audit measured",
            listOf(34f, 21f, 16f, 14f),
            steps.map { it.second.fontSize.value }
        )
        assertTrue(
            "the ladder must descend: page > section > sub > group",
            steps.map { it.second.fontSize.value }.zipWithNext().all { (a, b) -> a > b }
        )
        assertTrue(
            "sub and group sit at or above the SemiBold floor — a heading lighter " +
                "than its body is not a heading",
            steps.drop(2).all {
                (it.second.fontWeight ?: FontWeight.Normal).weight >=
                    FontWeight.SemiBold.weight
            }
        )
    }

    @Test
    fun `no heading overrides a weight its token already owns`() {
        val files = scannedFiles()
        assertTrue(
            "the scan found no UI sources under ${sourceRoot.path} — a guard that " +
                "sees nothing must not pass",
            files.size > 50
        )
        val offences = files.flatMap(::offencesIn)
        assertTrue(
            "a heading's weight comes from its token. These call sites stack a " +
                "`fontWeight` on a token that already carries it (or put one on " +
                "the single-face Literata): " +
                offences.joinToString { "${it.file}: ${it.expression} — ${it.reason}" },
            offences.isEmpty()
        )
    }

    @Test
    fun `paper headings route through the ladder, not through an ad hoc slot`() {
        val surfaces = listOf(
            "components/SectionHeaders.kt",
            "components/BookmarkDialog.kt",
            "screens/LibraryFilterSheet.kt",
            "screens/LibraryImportSheet.kt",
            "screens/ListenScreen.kt",
            "screens/SubmissionSheet.kt",
            "screens/HomeScreen.kt",
            "screens/LibraryScreen.kt",
            "screens/BookDetailReviews.kt",
            "screens/ProfileScreen.kt",
            "screens/StorageDestinationScreen.kt",
            "screens/ChannelImportCard.kt",
            "screens/bookdetail/BookDetailDeleteModals.kt",
        )
        val missing = surfaces.filterNot { path ->
            read(File(sourceRoot, path)).contains("PaperInkType.Heading.")
        }
        assertTrue(
            "each migrated heading surface must reach its heading through the one " +
                "ladder, so changing a step is a change in PaperInk.kt only. " +
                "No ladder reference in: ${missing.joinToString()}",
            missing.isEmpty()
        )
    }
}
