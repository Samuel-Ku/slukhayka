package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.ListenerCollectionItem
import com.slukhayka.audiobooks.data.collections.PublicationPreview
import com.slukhayka.audiobooks.data.collections.PublishedCollection
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
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
 * #980 then closed the hole that made the earlier "clean" verdicts weaker than
 * they sounded: the walk that this class and its two predecessors ran never
 * read `SemanticsProperties.PaneTitle`, so [AddToCollectionSheet]'s
 * `accessibilityPane("Додати до добірки")` could not fail it. The walk now
 * lives once in [EnglishChromeWalk] and reads Text, ContentDescription,
 * StateDescription and PaneTitle from every root, so the three surfaces this
 * class used to step around — the delete chrome in [CollectionDetailContent],
 * the inline-create chrome in [AddToCollectionSheet] and the preview lines
 * rendered by [PublishCollectionSheet] — are walked whole instead of being
 * asserted at the resource seam. The reader-side save button (#695) is walked
 * too. The source-pin test stays: it catches a reintroduced literal at the
 * source even on a surface no rendered fixture happens to reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class CollectionsScreensEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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

    private fun ownCollectionWithBook(): ListenerCollection = ownCollection().copy(
        items = listOf(ListenerCollectionItem("book-a", "because stars", 1L))
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
                // the moderation chrome; the reader-side save button is walked
                // by its own test below.
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
    fun `the reader-side save button speaks English`() {
        composeTestRule.setContent {
            chrome {
                // Someone else's VISIBLE collection, so the walk reaches the
                // save button (#695) that the own-hidden fixture above skips.
                PublicCollectionContent(
                    collection = publishedCollection(hidden = false),
                    originalAvailableLocally = true,
                    onSaveForYou = {},
                    onReport = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule
            .onNodeWithText(context.getString(R.string.collection_save_for_you))
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

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText(context.getString(R.string.collection_detail_empty)).assertExists()
    }

    @Test
    fun `the collection delete confirmation speaks English`() {
        var deleted = false
        composeTestRule.setContent {
            chrome {
                CollectionDetailContent(
                    collection = ownCollectionWithBook(),
                    onRemoveBook = {},
                    onDelete = { deleted = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // The itemised surface carries the remove action ...
        assertChromeHasNoCyrillic()

        // ... and the destructive confirmation composes into its own root, so
        // this also proves the walk is not blind to dialogs.
        composeTestRule.onNodeWithTag("collection_delete").performClick()
        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithTag("collection_delete_cancel").performClick()
        assertTrue("cancelling must destroy nothing", !deleted)
    }

    @Test
    fun `the add-to-collection sheet chrome speaks English`() {
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

        assertChromeHasNoCyrillic()
        composeTestRule
            .onNodeWithText(context.getString(R.string.book_detail_add_to_collection))
            .assertExists()

        // The inline-create form is the same sheet's other half — open it so
        // the walk sees its labels and actions instead of only the closed one.
        composeTestRule.onNodeWithTag("new_collection_open").performClick()
        assertChromeHasNoCyrillic()
        composeTestRule
            .onNodeWithText(context.getString(R.string.collection_new_title_label))
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

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText(context.getString(R.string.publish_collection_title)).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.publish_collection_preview_lead))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.publish_collection_preview_line_title, "Magic"))
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
        assertTrue(
            "CollectionDetailContent must build the delete chrome from resources",
            detail.contains("R.string.collection_delete") &&
                detail.contains("R.string.collection_delete_title") &&
                detail.contains("R.string.collection_delete_body") &&
                detail.contains("R.string.collection_delete_confirm") &&
                detail.contains("R.string.collection_remove_book") &&
                detail.contains("R.string.collection_cancel")
        )
        listOf(
            "\"Прибрати\"",
            "\"Видалити добірку\"",
            "\"Видалити добірку?\"",
            "\"Видалити\"",
            "\"Скасувати\""
        ).forEach { literal ->
            assertTrue(
                "CollectionDetailContent must not keep the Ukrainian literal $literal",
                !detail.contains(literal)
            )
        }

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
            "PublishCollectionSheet must build the preview lines from resources",
            publish.contains("R.string.publish_collection_preview_line_title") &&
                publish.contains("R.string.publish_collection_preview_line_pseudonym") &&
                publish.contains("R.string.publish_collection_preview_line_book_count") &&
                publish.contains("R.string.publish_collection_preview_line_description_included") &&
                publish.contains("R.string.publish_collection_preview_line_description_absent")
        )
        listOf(
            "\"Опублікувати добірку?\"",
            "\"Піде назовні рівно це:\"",
            "\"Скасувати\"",
            "\"Опублікувати\""
        ).forEach { literal ->
            assertTrue(
                "PublishCollectionSheet must not keep the Ukrainian literal $literal",
                !publish.contains(literal)
            )
        }

        val addTo = read("ui/screens/collections/AddToCollectionSheet.kt")
        assertTrue(
            "AddToCollectionSheet must reuse R.string.book_detail_add_to_collection for its Text AND its pane",
            addTo.contains("R.string.book_detail_add_to_collection") &&
                addTo.contains("accessibilityPane(stringResource(R.string.book_detail_add_to_collection))")
        )
        assertTrue(
            "AddToCollectionSheet must build the inline-create chrome from resources",
            addTo.contains("R.string.collection_new_title_label") &&
                addTo.contains("R.string.collection_new_title_limit") &&
                addTo.contains("R.string.collection_new_description_label") &&
                addTo.contains("R.string.collection_cancel") &&
                addTo.contains("R.string.collection_create") &&
                addTo.contains("R.string.collection_new_open")
        )
        listOf(
            "\"Додати до добірки\"",
            "\"Назва добірки\"",
            "\"Опис (не обов",
            "\"Скасувати\"",
            "\"Створити\"",
            "\"Нова добірка"
        ).forEach { literal ->
            assertTrue(
                "AddToCollectionSheet must not keep the Ukrainian literal $literal",
                !addTo.contains(literal)
            )
        }

        val saveButton = read("ui/screens/collections/SaveCollectionForYouButton.kt")
        assertTrue(
            "SaveCollectionForYouButton must build its label from R.string.collection_save_for_you",
            saveButton.contains("R.string.collection_save_for_you")
        )
        assertTrue(
            "SaveCollectionForYouButton must not keep the Ukrainian label literal",
            !saveButton.contains("\"Зберегти собі\"")
        )

        val preview = read("data/collections/PublicationPreview.kt")
        listOf("\"Назва:", "\"Псевдонім:", "\"Книг у добірці:", "\"Опис:").forEach { literal ->
            assertTrue(
                "PublicationPreview must not keep the Ukrainian preview literal $literal",
                !preview.contains(literal)
            )
        }
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
        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "collections")
    }
}
