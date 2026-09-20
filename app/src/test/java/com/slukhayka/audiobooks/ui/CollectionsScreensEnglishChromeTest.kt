package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.PublicationPreview
import com.slukhayka.audiobooks.data.collections.PublishedCollection
import com.slukhayka.audiobooks.ui.screens.collections.AddToCollectionSheet
import com.slukhayka.audiobooks.ui.screens.collections.CollectionDetailContent
import com.slukhayka.audiobooks.ui.screens.collections.MyCollectionRow
import com.slukhayka.audiobooks.ui.screens.collections.MyCollectionsBlock
import com.slukhayka.audiobooks.ui.screens.collections.PublicCollectionContent
import com.slukhayka.audiobooks.ui.screens.collections.PublishCollectionSheet
import com.slukhayka.audiobooks.ui.screens.collections.PublishedCollectionRow
import com.slukhayka.audiobooks.ui.screens.collections.PublishedCollectionsBlock
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * spec-46 T14 (колекційний зріз, продовження #975 і #978) — the collection
 * screens' chrome must survive the English run.
 *
 * #975 moved the index chrome and #978 the series header, and both named the
 * same class of residue on the collection surfaces: hardcoded Ukrainian
 * literals that compile and render correctly under the default (uk) locale and
 * only leak once the app speaks English. This class is that named next slice.
 *
 * The proof walks the whole semantics tree in `en-rUS` and fails on any
 * Cyrillic (the [IndexScreensEnglishChromeTest] pattern); fixtures carry
 * Latin-only data, so a failure can only come from chrome the app itself
 * produced. Three surfaces cannot be walked honestly yet — the delete chrome in
 * [CollectionDetailContent], the inline-create chrome in [AddToCollectionSheet]
 * and `PublicationPreview.lines` are still Ukrainian literals in files outside
 * this slice, and the reader-side save button is too — so for those the test
 * asserts the English resource seam directly instead. The last test pins the
 * source itself so a future slice cannot quietly reintroduce the literals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class CollectionsScreensEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    private fun ownCollection(title: String = "Magic"): ListenerCollection = ListenerCollection(
        id = "c1",
        title = title,
        description = "about stars",
        createdAt = 1L
    )

    private fun publishedCollection(hidden: Boolean = false): PublishedCollection = PublishedCollection(
        authorId = "a".repeat(64),
        collectionId = "c1",
        pseudonym = "Listener",
        title = "Magic",
        description = "about stars",
        bookIds = listOf("book-a", "book-b", "book-c"),
        hidden = hidden,
        publishedAt = 1L
    )

    @Test
    fun `the my-collections block speaks English`() {
        composeTestRule.setContent {
            chrome {
                MyCollectionsBlock(
                    rows = listOf(
                        MyCollectionRow(id = "c1", title = "Magic", bookCount = 2),
                        MyCollectionRow(id = "c2", title = "Space", bookCount = 5)
                    ),
                    onOpen = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText(context.getString(R.string.my_collections_title)).assertExists()
        // «N книг» inflects the noun, so it is book_count, not a format string.
        composeTestRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.book_count, 2, 2))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.book_count, 5, 5))
            .assertExists()
    }

    @Test
    fun `the published-collections block speaks English`() {
        composeTestRule.setContent {
            chrome {
                PublishedCollectionsBlock(
                    rows = listOf(
                        PublishedCollectionRow("doc-1", "Magic", 2, "Listener"),
                        PublishedCollectionRow("doc-2", "Space", 5, "Listener")
                    ),
                    onOpen = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText(context.getString(R.string.published_collections_title)).assertExists()
        composeTestRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.book_count, 2, 2) + " · Listener"
            )
            .assertExists()
    }

    @Test
    fun `the public collection content speaks English`() {
        composeTestRule.setContent {
            chrome {
                // The author's own HIDDEN collection: it exercises the count and
                // the moderation chrome without reaching the reader-side save
                // button, whose Ukrainian literal is a different file (#695).
                PublicCollectionContent(
                    collection = publishedCollection(hidden = true),
                    originalAvailableLocally = true,
                    onSaveForYou = {},
                    isOwn = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.collection_book_count, 3, 3)
            )
            .assertExists()
    }

    @Test
    fun `the collection detail empty state comes from a resource`() {
        composeTestRule.setContent {
            chrome {
                CollectionDetailContent(
                    collection = ownCollection(),
                    onRemoveBook = {},
                    onDelete = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // The walk cannot be used whole: the delete chrome around it is a
        // Ukrainian literal in this same file but outside this slice.
        composeTestRule.onNodeWithText(context.getString(R.string.collection_detail_empty)).assertExists()
    }

    @Test
    fun `the add-to-collection sheet title comes from a resource`() {
        composeTestRule.setContent {
            chrome {
                AddToCollectionSheet(
                    collections = listOf(ownCollection()),
                    bookId = "book-a",
                    onDismiss = {},
                    onToggle = { _, _, _ -> },
                    onCreate = { _, _ -> },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.book_detail_add_to_collection))
            .assertExists()
    }

    @Test
    fun `the publish sheet chrome comes from resources`() {
        composeTestRule.setContent {
            chrome {
                PublishCollectionSheet(
                    preview = PublicationPreview(
                        title = "Magic",
                        pseudonym = "Listener",
                        bookCount = 2,
                        descriptionIncluded = false
                    ),
                    onConfirm = {},
                    onDismiss = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // `preview.lines` are still Ukrainian literals inside PublicationPreview
        // (another file), so only the sheet's own two lines are asserted here.
        composeTestRule.onNodeWithText(context.getString(R.string.publish_collection_title)).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.publish_collection_preview_lead))
            .assertExists()
    }

    @Test
    fun `the collection sources build their chrome from resources`() {
        val myCollections = read("ui/screens/collections/MyCollectionsBlock.kt")
        assertTrue(
            "MyCollectionsBlock must build its heading from R.string.my_collections_title: $myCollections",
            myCollections.contains("R.string.my_collections_title")
        )
        assertTrue(
            "MyCollectionsBlock must count books with R.plurals.book_count",
            myCollections.contains("R.plurals.book_count")
        )
        assertTrue(
            "MyCollectionsBlock must not keep the Ukrainian heading literal",
            !myCollections.contains("\"Мої добірки\"")
        )
        assertTrue(
            "MyCollectionsBlock must not keep the hardcoded «N книг» count",
            !myCollections.contains("} книг")
        )

        val published = read("ui/screens/collections/PublishedCollectionsBlock.kt")
        assertTrue(
            "PublishedCollectionsBlock must build its heading from R.string.published_collections_title",
            published.contains("R.string.published_collections_title")
        )
        assertTrue(
            "PublishedCollectionsBlock must count books with R.plurals.book_count",
            published.contains("R.plurals.book_count")
        )
        assertTrue(
            "PublishedCollectionsBlock must not keep the hardcoded «N книг» count",
            !published.contains("} книг")
        )

        val publicContent = read("ui/screens/collections/PublicCollectionContent.kt")
        assertTrue(
            "PublicCollectionContent must build its count from R.plurals.collection_book_count",
            publicContent.contains("R.plurals.collection_book_count")
        )
        assertTrue(
            "PublicCollectionContent must not keep the Ukrainian count literal",
            !publicContent.contains("\"Книг у добірці")
        )

        val detail = read("ui/screens/collections/CollectionDetailContent.kt")
        assertTrue(
            "CollectionDetailContent must build its empty state from R.string.collection_detail_empty",
            detail.contains("R.string.collection_detail_empty")
        )
        assertTrue(
            "CollectionDetailContent must not keep the Ukrainian empty-state literal",
            !detail.contains("\"У добірці ще немає книг\"")
        )

        val publish = read("ui/screens/collections/PublishCollectionSheet.kt")
        assertTrue(
            "PublishCollectionSheet must build its title from R.string.publish_collection_title",
            publish.contains("R.string.publish_collection_title")
        )
        assertTrue(
            "PublishCollectionSheet must build its lead from R.string.publish_collection_preview_lead",
            publish.contains("R.string.publish_collection_preview_lead")
        )
        assertTrue(
            "PublishCollectionSheet must not keep the Ukrainian title literal",
            !publish.contains("\"Опублікувати добірку?\"")
        )
        assertTrue(
            "PublishCollectionSheet must not keep the Ukrainian lead literal",
            !publish.contains("\"Піде назовні рівно це:\"")
        )

        val addTo = read("ui/screens/collections/AddToCollectionSheet.kt")
        assertTrue(
            "AddToCollectionSheet must reuse R.string.book_detail_add_to_collection",
            addTo.contains("R.string.book_detail_add_to_collection")
        )
        assertTrue(
            "AddToCollectionSheet must not hardcode the title Text",
            !addTo.contains("text = \"Додати до добірки\"")
        )
    }

    private fun read(path: String): String {
        val file = File(sourceRoot, path)
        check(file.isFile) { "source file not found: $file" }
        return file.readText()
    }

    @Composable
    private fun chrome(content: @Composable () -> Unit) {
        AudiobookTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }

    private fun assertChromeHasNoCyrillic() {
        val texts = collectAllTexts()
        val leaked = texts.filter { cyrillic.containsMatchIn(it) }
        assertTrue("Ukrainian chrome leaked into the EN collections run: $leaked", leaked.isEmpty())
    }

    private fun collectAllTexts(): List<String> =
        composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { collectTexts(it) }

    private fun collectTexts(node: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
        node.children.forEach { out += collectTexts(it) }
        return out
    }
}
