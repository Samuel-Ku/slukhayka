package com.slukhayka.audiobooks.ui.snapshots

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogSection
import com.slukhayka.audiobooks.data.catalog.CatalogSectionId
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.personbookmarks.PersonNewArrivals
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.components.SectionHeaderTags
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #562 AC — «на Огляді не лишилось ad-hoc заголовків». The populated Огляд
 * body renders every group, shelf and rail through the canonical
 * [com.slukhayka.audiobooks.ui.components.AppSectionHeader], so EVERY node
 * that carries TalkBack's heading semantics must also carry one of the
 * canonical level tags ([SectionHeaderTags]). A screen that reintroduces a
 * bare `Text` + `semantics { heading() }` fails this test by name instead of
 * silently forking the header vocabulary again (ADR-0033).
 *
 * The people rail is the surface that used to be the last ad-hoc header: it
 * asserted its own `titleMedium` bold `Text` plus a free-standing count
 * `TextButton`. Here its counter must be the header's plain subtitle (no
 * `OnClick` of its own) and the mark-seen affordance must ride the action
 * slot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// uk-rUA: the feed chrome is resource-backed; without the qualifier the
// assertions would resolve values-en.
@Config(qualifiers = "uk-rUA-w411dp-h4000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class HomeFeedSectionHeadersTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val result = GlobalSearchResult(
        title = "Вкради мене... Зараз!",
        author = "Сергій Оріанець",
        mergeKey = "вкради-мене-зараз|сергій-оріанець",
        coverImageUrl = null,
        sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/7611.html"))
    )

    private val books = listOf(
        CatalogBook(
            id = "b1", title = "Старий і море", author = "Ернест Гемінґвей",
            url = "https://4read.org/b1", coverImageUrl = null
        )
    )

    private val sections = listOf(
        CatalogSection(title = "Популярне", books = books, id = CatalogSectionId.POPULAR)
    )

    private val collections = listOf(
        CollectionMatcher.MatchedCollection(
            id = "c1", name = "Нобелівські лауреати", sourceNote = "", books = listOf(result)
        )
    )

    /** Two new arrivals from bookmarked people — the migrated rail. */
    private val peopleNewArrivals = PersonNewArrivals.CatalogProjection(
        results = listOf(result, result.copy(title = "Темна матерія", mergeKey = "темна-матерія")),
        bookmarkKeys = emptySet()
    )

    @Test
    fun every_home_heading_is_the_canonical_section_header() {
        renderHomeFeed()

        val headings = composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true
        ).fetchSemanticsNodes()
        assertTrue("the populated feed renders section headings", headings.isNotEmpty())

        val canonicalTags = setOf(SectionHeaderTags.GROUP, SectionHeaderTags.SECTION)
        val adHoc = headings.mapNotNull { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            if (tag in canonicalTags) null else tag ?: "<no tag>"
        }
        assertTrue(
            "ad-hoc headings left on Огляд: $adHoc",
            adHoc.isEmpty()
        )

        // Both top-level groups of Огляд are the GROUP level; the rails are
        // the SECTION level. The tag is the machine-checkable distinction.
        composeTestRule.onNodeWithText("Для вас")
            .assert(hasTestTag(SectionHeaderTags.GROUP))
        composeTestRule.onNodeWithText("Відкрити нове")
            .assert(hasTestTag(SectionHeaderTags.GROUP))
        composeTestRule.onNodeWithText("Нове від ваших авторів/виконавців")
            .assert(hasTestTag(SectionHeaderTags.SECTION))
        composeTestRule.onNodeWithText("Популярне")
            .assert(hasTestTag(SectionHeaderTags.SECTION))
    }

    @Test
    fun people_rail_counter_is_a_subtitle_and_mark_seen_is_its_action() {
        var markedSeen = 0
        renderHomeFeed(onMarkPeopleSeen = { markedSeen++ })

        val context = ApplicationProvider.getApplicationContext<Context>()
        val counter = context.resources.getQuantityString(
            R.plurals.home_people_new_count,
            peopleNewArrivals.count,
            peopleNewArrivals.count
        )

        // R10: the counter is one plain subtitle line under the title — it
        // replaces the old free-standing badge button.
        composeTestRule.onNodeWithText(counter)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        // The mark-seen affordance lives in the header's action slot.
        composeTestRule.onNodeWithTag("people_new_arrivals_badge")
            .performClick()
            .assertExists()
        assertEquals(1, markedSeen)
    }

    private fun renderHomeFeed(onMarkPeopleSeen: () -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val feedFlow = remember { MutableStateFlow(PagingData.from(emptyList<WorkFeedRow>())) }
                    val feedItems = feedFlow.collectAsLazyPagingItems()
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("home_feed")) {
                        homeFeedContent(
                            isCatalogLoading = false,
                            hasLibraryBooks = true,
                            sections = sections,
                            genreFacetOptions = emptyList(),
                            collections = collections,
                            newArrivals = emptyList(),
                            peopleNewArrivals = peopleNewArrivals,
                            onMarkPeopleNewArrivalsSeen = onMarkPeopleSeen,
                            recommendedBooks = emptyList(),
                            personalCycles = emptyList(),
                            shortBooks = emptyList(),
                            longBooks = emptyList(),
                            workFeedItems = feedItems,
                            feedGenreFilters = emptySet(),
                            feedSortByTitle = false,
                            onRefreshCatalog = {},
                            onGoToLibrary = {},
                            onOpenTop100 = {},
                            onOpenPeople = {},
                            onOpenSeriesIndex = {},
                            onOpenCollectionsIndex = {},
                            onOpenSeries = { _, _ -> },
                            onOpenRecommendedBook = {},
                            onOpenWorkFeedRow = {},
                            onBookClick = {},
                            onSetFeedGenreFilters = {},
                            onSetFeedSortByTitle = {}
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }
}
