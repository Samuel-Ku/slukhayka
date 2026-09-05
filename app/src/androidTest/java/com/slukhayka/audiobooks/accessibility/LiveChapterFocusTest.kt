package com.slukhayka.audiobooks.accessibility

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly selected existing book; never seeds or clears the listener's database. */
@RunWith(AndroidJUnit4::class)
class LiveChapterFocusTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun closing_player_returns_focus_to_selected_chapter() {
        val id = InstrumentationRegistry.getArguments().getString("liveBookId")
        assumeTrue(!id.isNullOrBlank())
        val chapter = runBlocking { App.instance.audiobookDao.getChaptersListForBook(requireNotNull(id)).first() }
        val tag = "book_detail_chapter_${chapter.id}"
        lateinit var vm: MainViewModel
        rule.runOnUiThread {
            rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
            vm.selectBook(id)
        }
        try {
            rule.waitUntilExactlyOneExists(hasTestTag("book_detail_screen"), 20_000)
            rule.onNodeWithTag("book_detail_screen").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
            rule.waitUntilExactlyOneExists(hasTestTag("full_player_screen"), 20_000)
            rule.onNodeWithTag("close_player_button").performClick()
            rule.waitUntil(20_000) {
                rule.onAllNodesWithTag("full_player_screen").fetchSemanticsNodes().isEmpty()
            }
            rule.waitUntil(20_000) {
                rule.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.Focused) == true
            }
            rule.onNodeWithTag(tag).assertIsFocused()
            Log.i("LiveRecoveryTest", "chapterFocusReturn=passed")
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            Log.e("LiveChapterFocus", rule.onRoot(useUnmergedTree = true).printToString())
            throw failure
        } finally {
            rule.runOnUiThread { vm.playerManager.pause(); vm.setShowFullPlayer(false) }
        }
    }
}
