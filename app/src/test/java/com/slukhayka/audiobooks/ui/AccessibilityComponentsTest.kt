package com.slukhayka.audiobooks.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.CachePolicy
import coil.request.Options
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("DEPRECATION")
class AccessibilityComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "accessibility-cover",
        title = "Тестова книга",
        author = "Тестовий автор",
        narrator = "Тестовий виконавець",
        description = "",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "Фантастика",
        sourceUrl = "https://example.invalid/test"
    )

    @Test
    fun sectionHeaderIsExposedAsAHeading() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                AppSectionHeader(title = "Нещодавно слухали")
            }
        }

        composeTestRule.onNodeWithText("НЕЩОДАВНО СЛУХАЛИ")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun decorativeFallbackCoverIsSilent() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookCoverImage(
                    book = book,
                    semantics = BookCoverSemantics.Decorative
                )
            }
        }

        composeTestRule.onNodeWithText(book.title)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun meaningfulFallbackCoverHasExactlyTheProvidedDescription() {
        val description = "Обкладинка: Тестова книга"
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookCoverImage(
                    book = book,
                    semantics = BookCoverSemantics.Meaningful(description)
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(description)
            .assertContentDescriptionEquals(description)
        composeTestRule.onNodeWithText(book.title)
            .assertDoesNotExist()
    }

    @Test
    fun decorativeLoadedCoverIsSilentLikeTheFallback() {
        val loaded = AtomicBoolean(false)
        composeTestRule.setContent {
            val context = LocalContext.current
            val imageLoader = remember(context) {
                loadedCoverImageLoader(context)
            }
            val loadedBook = remember { book.copy(coverImageUrl = LOADED_COVER_URL) }
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                AudiobookTheme(darkTheme = true) {
                    BookCoverImage(
                        book = loadedBook,
                        semantics = BookCoverSemantics.Decorative,
                        modifier = Modifier.size(100.dp),
                        onImageLoaded = { loaded.set(true) }
                    )
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = IMAGE_TIMEOUT_MS) { loaded.get() }
        composeTestRule.onNodeWithText(book.title)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun meaningfulLoadedCoverHasExactlyTheProvidedDescriptionLikeTheFallback() {
        val description = "Обкладинка: Тестова книга"
        val loaded = AtomicBoolean(false)
        composeTestRule.setContent {
            val context = LocalContext.current
            val imageLoader = remember(context) {
                loadedCoverImageLoader(context)
            }
            val loadedBook = remember { book.copy(coverImageUrl = LOADED_COVER_URL) }
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                AudiobookTheme(darkTheme = true) {
                    BookCoverImage(
                        book = loadedBook,
                        semantics = BookCoverSemantics.Meaningful(description),
                        modifier = Modifier.size(100.dp),
                        onImageLoaded = { loaded.set(true) }
                    )
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = IMAGE_TIMEOUT_MS) { loaded.get() }
        composeTestRule.onNodeWithContentDescription(description)
            .assertContentDescriptionEquals(description)
        composeTestRule.onNodeWithText(book.title)
            .assertDoesNotExist()
    }

    @Test
    fun visibleModalHidesTheComposedBackgroundFromAccessibility() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .testTag("modal_background")
                        .accessibilityModalBackground(modalVisible = true)
                )
            }
        }

        composeTestRule.onNodeWithTag("modal_background", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
    }

    @Test
    fun modalPaneAnnouncesItsUkrainianTitle() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .testTag("modal_pane")
                        .accessibilityPane("Програвач")
                )
            }
        }

        composeTestRule.onNodeWithTag("modal_pane", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Програвач"
                )
            )
    }

    @Test
    fun modalFocusReturnRunsOnlyAfterAVisibleToClosedTransition() {
        var setModalVisible: ((Boolean) -> Unit)? = null
        var restoreCount = 0
        composeTestRule.setContent {
            var modalVisible by remember { mutableStateOf(false) }
            val initialFocusRequester = remember { FocusRequester() }
            val returnFocusRequester = remember { FocusRequester() }
            setModalVisible = { modalVisible = it }

            RestoreFocusAfterModal(
                modalVisible = modalVisible,
                returnFocusRequester = returnFocusRequester,
                onFocusRestored = { restoreCount += 1 }
            )
            LaunchedEffect(initialFocusRequester) {
                initialFocusRequester.requestFocus()
            }
            Column {
                Box(
                    Modifier
                        .size(48.dp)
                        .focusRequester(initialFocusRequester)
                        .focusable()
                        .testTag("initial_focus_target")
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .focusRequester(returnFocusRequester)
                        .focusable()
                        .testTag("modal_return_focus_target")
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("initial_focus_target").assertIsFocused()
        composeTestRule.onNodeWithTag("modal_return_focus_target").assertIsNotFocused()
        assertEquals(0, restoreCount)

        composeTestRule.runOnIdle { setModalVisible?.invoke(true) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("modal_return_focus_target").assertIsNotFocused()
        assertEquals(0, restoreCount)

        composeTestRule.runOnIdle { setModalVisible?.invoke(false) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("modal_return_focus_target").assertIsFocused()
        assertEquals(1, restoreCount)
    }

    @Test
    fun modalFocusReturnUsesStableFallbackWhenDestructiveOriginIsDetached() {
        var closeAndDetach: (() -> Unit)? = null
        var restoreCount = 0
        composeTestRule.setContent {
            var modalVisible by remember { mutableStateOf(true) }
            var showOrigin by remember { mutableStateOf(true) }
            val origin = remember { FocusRequester() }
            val fallback = remember { FocusRequester() }
            closeAndDetach = {
                showOrigin = false
                modalVisible = false
            }

            RestoreFocusAfterModal(
                modalVisible = modalVisible,
                returnFocusRequester = origin,
                fallbackFocusRequester = fallback,
                onFocusRestored = { restoreCount += 1 }
            )
            Column {
                if (showOrigin) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .focusRequester(origin)
                            .focusable()
                            .testTag("destructive_origin")
                    )
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .focusRequester(fallback)
                        .focusable()
                        .testTag("stable_focus_fallback")
                )
            }
        }

        composeTestRule.runOnIdle { closeAndDetach?.invoke() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("stable_focus_fallback").assertIsFocused()
        assertEquals(1, restoreCount)
    }

    @Test
    fun modalFocusReturnDoesNotConsumeOwnerWhenEveryTargetIsDetached() {
        var closeAndDetach: (() -> Unit)? = null
        var restoreCount = 0
        composeTestRule.setContent {
            var modalVisible by remember { mutableStateOf(true) }
            var showOrigin by remember { mutableStateOf(true) }
            val origin = remember { FocusRequester() }
            closeAndDetach = {
                showOrigin = false
                modalVisible = false
            }

            RestoreFocusAfterModal(
                modalVisible = modalVisible,
                returnFocusRequester = origin,
                onFocusRestored = { restoreCount += 1 }
            )
            if (showOrigin) {
                Box(
                    Modifier
                        .size(48.dp)
                        .focusRequester(origin)
                        .focusable()
                        .testTag("removed_focus_origin")
                )
            }
        }

        composeTestRule.runOnIdle { closeAndDetach?.invoke() }
        composeTestRule.waitForIdle()

        assertEquals(0, restoreCount)
    }

    private fun loadedCoverImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .allowHardware(false)
            .dispatcher(Dispatchers.Unconfined)
            .interceptorDispatcher(Dispatchers.Unconfined)
            .fetcherDispatcher(Dispatchers.Unconfined)
            .decoderDispatcher(Dispatchers.Unconfined)
            .transformationDispatcher(Dispatchers.Unconfined)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .components {
                add(LoadedCoverFetcherFactory)
            }
            .build()

    private object LoadedCoverFetcherFactory : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? = if (data.toString() == LOADED_COVER_URL) LoadedCoverFetcher else null
    }

    private object LoadedCoverFetcher : Fetcher {
        override suspend fun fetch(): FetchResult = DrawableResult(
            drawable = ColorDrawable(Color.MAGENTA),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }

    private companion object {
        const val LOADED_COVER_URL = "test://loaded-cover"
        const val IMAGE_TIMEOUT_MS = 5_000L
    }
}
