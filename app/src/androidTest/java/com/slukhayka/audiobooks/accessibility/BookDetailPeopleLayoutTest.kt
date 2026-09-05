package com.slukhayka.audiobooks.accessibility

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.BookDetailCanonicalSummary
import com.slukhayka.audiobooks.ui.screens.BookDetailSourceSection
import com.slukhayka.audiobooks.ui.screens.PersonBookmarkControl
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device layout fixture; does not edit the listener's library or saved bookmarks. */
class BookDetailPeopleLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun longNamesKeepTheirBookmarkAndDistinctSourceAddresses() {
        val book = AudiobookEntity(
            id = "546-layout", title = "Сторінка книги", author = "Дуже довге ім’я автора для перевірки переносу",
            narrator = "Дуже довге ім’я озвучувача для перевірки переносу", description = "",
            genre = "Фентезі", coverDrawableRes = 0, sourceUrl = "https://4read.org/one", isDownloaded = false
        )
        val source = SourceCatalog.WorkSourceRow("4read", "4read", book.sourceUrl, false)
        val presentation = bookDetailPresentation(book, emptyList(), listOf(source, source, source.copy(url = "https://4read.org/two")))
        var authorMarked by mutableStateOf(false)
        var narratorMarked by mutableStateOf(false)
        rule.activity.runOnUiThread {
            rule.activity.setContent {
                AudiobookTheme(darkTheme = true) {
                    Surface {
                        Column(Modifier.width(320.dp)) {
                            BookDetailCanonicalSummary(
                                presentation, universeName = "Перший закон",
                                authorBookmark = PersonBookmarkControl(isBookmarked = authorMarked, onToggle = { authorMarked = !authorMarked }),
                                narratorBookmark = PersonBookmarkControl(isBookmarked = narratorMarked, onToggle = { narratorMarked = !narratorMarked })
                            )
                            BookDetailSourceSection(presentation)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        for ((role, name) in listOf("author" to book.author, "narrator" to book.narrator)) {
            val text = rule.onNodeWithTag("book_detail_${role}_link").fetchSemanticsNode().boundsInRoot
            val star = rule.onNodeWithTag("book_detail_${role}_bookmark").fetchSemanticsNode().boundsInRoot
            assertEquals(text.right, star.left, 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag("book_detail_${role}_link").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("long name should wrap", layouts.single().lineCount > 1)
            rule.onNodeWithTag("book_detail_${role}_bookmark").performClick()
                .assertContentDescriptionEquals(rule.activity.getString(com.slukhayka.audiobooks.R.string.person_bookmark_remove, name))
        }
        rule.onAllNodesWithText("4read").assertCountEquals(2)
        rule.onAllNodesWithText(rule.activity.getString(com.slukhayka.audiobooks.R.string.book_detail_browser_needed)).assertCountEquals(0)
        rule.onAllNodesWithText(rule.activity.getString(com.slukhayka.audiobooks.R.string.bookdetail_wrong_universe)).assertCountEquals(0)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "546-people-layout.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
