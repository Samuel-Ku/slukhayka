package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.slukhayka.audiobooks.ui.components.SectionHeaderTags
import com.slukhayka.audiobooks.ui.screens.BookDetailPresentation
import com.slukhayka.audiobooks.ui.screens.BookDetailSourcePresentation
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailCanonicalSummary
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailSourceSection
import com.slukhayka.audiobooks.ui.screens.collections.CollectionWithBookRow
import com.slukhayka.audiobooks.ui.screens.collections.CollectionsWithBookBlock
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * spec-46 T10 (#571, E4 AC1) — «усі секції сторінки книги — канонічні
 * заголовки».
 *
 * Two halves, mirroring how [#562] guarded Огляд and how
 * [BookDetailHardcodedChromeGuardTest] guarded the book page's resources:
 *
 * 1. RENDERED — the two book-page sections that did NOT go through the
 *    canonical header before this ticket («Джерела» and «Добірки з цією
 *    книгою») now carry [SectionHeaderTags.SECTION] next to their TalkBack
 *    heading. The work title is pinned as the ONE deliberate exception: it is
 *    the page H1 (`book_detail_title`), not a section header.
 * 2. SOURCE SCAN — `BookDetailScreen` is one `LazyColumn` welded to a
 *    `MainViewModel`, so its four inline headers are out of a rendered test's
 *    reach. Every `heading()` in the book-page screen + `bookdetail` package
 *    must therefore be marked as the page title; anything else is a new
 *    ad-hoc header and fails this guard by name. `BookDetailDeleteModals.kt`
 *    is excluded because its headings are M3 dialog titles, not sections.
 *
 * [#562]: https://github.com/Samuel-Ku/slukhayka/issues/562
 */
@RunWith(RobolectricTestRunner::class)
// uk-rUA: the book-page chrome is resource-backed; without the qualifier the
// assertions would silently resolve values-en and never see the real header.
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class BookDetailSectionHeadersTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun source(id: String, isCurrent: Boolean = false) = BookDetailSourcePresentation(
        sourceId = id,
        name = id,
        url = "https://$id.example/book",
        streamOnly = false,
        rating = null,
        isCurrent = isCurrent,
        selectable = false,
        differingDescription = null,
        differingNarrator = null,
        differingGenres = emptyList()
    )

    private fun presentation(vararg sources: BookDetailSourcePresentation) = BookDetailPresentation(
        title = "Трохи ненависті",
        author = "Джо Аберкромбі",
        description = "Опис",
        narrator = "Pik CAH4E3",
        genre = "Фентезі",
        totalDurationSeconds = 3_600L,
        totalChapters = 4,
        seriesTitle = null,
        seriesUrl = null,
        seriesIndex = null,
        sources = sources.toList(),
        combinedAverage = null
    )

    @Test
    fun source_section_heading_is_the_canonical_section_header() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        BookDetailSourceSection(presentation(source("4read", isCurrent = true)))
                    }
                }
            }
        }

        // One source → the singular heading, rendered by AppSectionHeader.
        composeTestRule.onNodeWithText("Джерело")
            .assert(hasTestTag(SectionHeaderTags.SECTION))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun plural_source_heading_is_the_same_canonical_header() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        BookDetailSourceSection(
                            presentation(source("4read", isCurrent = true), source("sluhay"))
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Джерела")
            .assert(hasTestTag(SectionHeaderTags.SECTION))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun collections_with_this_book_heading_is_the_canonical_section_header() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CollectionsWithBookBlock(
                        rows = listOf(
                            CollectionWithBookRow(
                                documentId = "doc-1",
                                title = "Магія",
                                pseudonym = "Слухач",
                                average = 4.5,
                                ratingCount = 2
                            )
                        ),
                        onOpen = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Добірки з цією книгою")
            .assert(hasTestTag(SectionHeaderTags.SECTION))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun the_work_title_is_a_heading_but_not_a_section_header() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BookDetailCanonicalSummary(presentation(source("4read", isCurrent = true)))
                }
            }
        }

        val title = composeTestRule.onNodeWithTag("book_detail_title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .fetchSemanticsNode()
        val tag = title.config.getOrNull(SemanticsProperties.TestTag)
        assertTrue(
            "the page H1 must not claim a section level; it is the deliberate exception",
            tag !in setOf(SectionHeaderTags.GROUP, SectionHeaderTags.SECTION)
        )
    }

    @Test
    fun `no ad-hoc section headings are left on the book page`() {
        val offenders = scannedBookPageFiles().flatMap { file ->
            adHocHeadings(file).map { line ->
                "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:$line"
            }
        }

        assertTrue(
            "Every heading on the book page must come from AppSectionHeader " +
                "(spec-46 T10 / #562). A `semantics { heading() }` is only allowed " +
                "on the work title (marker `book_detail_title`). Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    /** Header sites outside the section vocabulary (M3 dialog titles) live here. */
    private val excludedFromScan = setOf("BookDetailDeleteModals.kt")

    private fun scannedBookPageFiles(): List<File> = buildList {
        add(sourceRoot.resolve("ui/screens/BookDetailScreen.kt"))
        val bookDetailPackage = sourceRoot.resolve("ui/screens/bookdetail")
        if (bookDetailPackage.isDirectory) {
            addAll(
                bookDetailPackage.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.name !in excludedFromScan }
            )
        }
    }.filter { it.exists() }

    /**
     * 1-based line numbers of `heading()` occurrences that are NOT the work
     * title: the marker `book_detail_title` must sit in the same declaration.
     * Comments are stripped first (the blocker comments legitimately name the
     * pattern they removed) and block comments are replaced newline-for-newline
     * so the reported line numbers stay real.
     */
    private fun adHocHeadings(file: File): List<Int> {
        val lines = stripComments(file.readText())
        return lines.indices
            .filter { HEADING_SEMANTICS.containsMatchIn(lines[it]) }
            .filter { index ->
                val window = lines.subList((index - 20).coerceAtLeast(0), index + 1)
                window.none { it.contains(PAGE_TITLE_MARKER) }
            }
            .map { it + 1 }
    }

    private fun stripComments(code: String): List<String> {
        val withoutBlocks = BLOCK_COMMENT.replace(code) { match ->
            "\n".repeat(match.value.count { it == '\n' })
        }
        return withoutBlocks.lines().map { LINE_COMMENT.replace(it, "") }
    }

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        val HEADING_SEMANTICS = Regex("""heading\(\)""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        const val PAGE_TITLE_MARKER = "book_detail_title"
    }
}
