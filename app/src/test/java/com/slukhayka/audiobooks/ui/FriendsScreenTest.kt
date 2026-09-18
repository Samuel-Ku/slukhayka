package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.social.Audience
import com.slukhayka.audiobooks.data.social.BlockState
import com.slukhayka.audiobooks.ui.screens.FriendsFeedRow
import com.slukhayka.audiobooks.ui.screens.FriendsFeedState
import com.slukhayka.audiobooks.ui.screens.FriendsScreen
import com.slukhayka.audiobooks.ui.screens.formatFriendsPostTime
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #898 — the «Друзі» root screen. Two things must hold and are asserted here:
 *
 * - the two empty states stay **distinguishable** and invent nothing (§6.4);
 * - the audience/block/friendship rule is applied at render time (§1/§2/§3), so
 *   a post the listener may not see never reaches the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class FriendsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val me = "Слухач-0042"
    private val friend = "Оксана"

    private fun render(
        feed: FriendsFeedState,
        onOpenBook: (String) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Scaffold { padding ->
                    Box(Modifier.padding(padding)) {
                        FriendsScreen(
                            feed = feed,
                            viewerPseudonym = me,
                            onOpenBook = onOpenBook
                        )
                    }
                }
            }
        }
    }

    // ---- empty states ----------------------------------------------------

    @Test
    fun withNoFriendsItSaysSoInsteadOfInventingAcquaintances() {
        render(FriendsFeedState())

        composeTestRule.onNodeWithTag("friends_empty_no_friends").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithText("Поки немає друзів").assertExists()
        // The "friends, but no posts" state must NOT also be shown: the two
        // facts are different and the screen never merges them.
        composeTestRule.onNodeWithTag("friends_empty_no_posts").assertDoesNotExist()
    }

    @Test
    fun withFriendsButNoPostsItSaysExactlyThat() {
        render(FriendsFeedState(friendsNow = setOf(friend)))

        composeTestRule.onNodeWithTag("friends_empty_no_posts").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithText("Друзі є, але дописів немає").assertExists()
        composeTestRule.onNodeWithTag("friends_empty_no_friends").assertDoesNotExist()
    }

    @Test
    fun theEmptyStateExplainsHowTheFirstPostAppears() {
        render(FriendsFeedState(friendsNow = setOf(friend)))

        // The body copy is the honest "how" — it names the audience that makes
        // a post appear here rather than promising a feature that is not built.
        composeTestRule.onNodeWithText(
            "Щойно хтось із друзів поділиться враженням про книгу з аудиторією «Друзі», " +
                "допис з’явиться тут. Дописи з аудиторією «Лише я» не показуються нікому."
        ).assertExists()
    }

    @Test
    fun theEmptyStateAnnouncesItselfPolitely() {
        render(FriendsFeedState())

        composeTestRule.onNodeWithText("Поки немає друзів")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
    }

    // ---- visibility ------------------------------------------------------

    @Test
    fun aFriendsPostFromALiveFriendIsRendered() {
        render(
            FriendsFeedState(
                friendsNow = setOf(friend),
                posts = listOf(
                    FriendsFeedRow(
                        id = "p1",
                        authorPseudonym = friend,
                        audience = Audience.FRIENDS,
                        text = "Дочитала «Місто» — варте кожного розділу.",
                        sourceId = "work-1",
                        postedAtMs = 1_700_000_000_000L
                    )
                ),
                bookTitles = mapOf("work-1" to "Місто")
            )
        )

        composeTestRule.onNodeWithTag("friends_post_p1").assertExists().assertIsDisplayed()
        // The card merges its descendants so TalkBack reads one statement, so
        // the inner rows are addressed in the unmerged tree.
        composeTestRule.onNodeWithTag("friends_post_author_p1", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Дочитала «Місто» — варте кожного розділу.").assertExists()
        composeTestRule.onNodeWithTag("friends_post_book_p1", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Місто").assertExists()
    }

    @Test
    fun aPostFromSomeoneWhoIsNotAFriendIsNotRendered() {
        render(
            FriendsFeedState(
                friendsNow = setOf(friend),
                posts = listOf(
                    FriendsFeedRow(
                        id = "stranger",
                        authorPseudonym = "Незнайомець",
                        audience = Audience.FRIENDS,
                        text = "мене не має бути видно"
                    )
                )
            )
        )

        composeTestRule.onNodeWithTag("friends_post_stranger").assertDoesNotExist()
        composeTestRule.onNodeWithText("мене не має бути видно").assertDoesNotExist()
        // And the screen is honest about it: friends exist, so this is the
        // "no posts" state, not a fabricated feed.
        composeTestRule.onNodeWithTag("friends_empty_no_posts").assertExists()
    }

    @Test
    fun aBlockedFriendsPostIsNotRendered() {
        render(
            FriendsFeedState(
                friendsNow = setOf(friend),
                posts = listOf(
                    FriendsFeedRow(
                        id = "blocked",
                        authorPseudonym = friend,
                        audience = Audience.FRIENDS,
                        text = "приховано блоком"
                    )
                ),
                blocks = BlockState(blocked = setOf(friend))
            )
        )

        composeTestRule.onNodeWithTag("friends_post_blocked").assertDoesNotExist()
        composeTestRule.onNodeWithText("приховано блоком").assertDoesNotExist()
    }

    @Test
    fun aPrivatePostOfSomeoneElseIsNeverRenderedEvenForAFriend() {
        render(
            FriendsFeedState(
                friendsNow = setOf(friend),
                posts = listOf(
                    FriendsFeedRow(
                        id = "private",
                        authorPseudonym = friend,
                        audience = Audience.PRIVATE,
                        text = "це лише для мене"
                    )
                )
            )
        )

        composeTestRule.onNodeWithTag("friends_post_private").assertDoesNotExist()
        composeTestRule.onNodeWithText("це лише для мене").assertDoesNotExist()
    }

    @Test
    fun theAuthorsOwnPrivatePostStaysVisibleToTheAuthor() {
        render(
            FriendsFeedState(
                posts = listOf(
                    FriendsFeedRow(
                        id = "mine",
                        authorPseudonym = me,
                        audience = Audience.PRIVATE,
                        text = "мій власний запис"
                    )
                )
            )
        )

        composeTestRule.onNodeWithTag("friends_post_mine").assertExists().assertIsDisplayed()
        // The author has no friends yet, but their own post is real content:
        // the feed must not claim to be empty while showing something.
        composeTestRule.onNodeWithTag("friends_empty_no_friends").assertDoesNotExist()
    }

    @Test
    fun aPublicPostIsVisibleEvenWithoutFriendship() {
        render(
            FriendsFeedState(
                posts = listOf(
                    FriendsFeedRow(
                        id = "public",
                        authorPseudonym = "Будь-хто",
                        audience = Audience.PUBLIC,
                        text = "публічний відгук"
                    )
                )
            )
        )

        composeTestRule.onNodeWithTag("friends_post_public").assertExists().assertIsDisplayed()
        // A public post is visible content, so no empty state is shown even
        // though the listener has no friends.
        composeTestRule.onNodeWithTag("friends_empty_no_friends").assertDoesNotExist()
    }

    // ---- book route ------------------------------------------------------

    @Test
    fun tappingAPostWithAKnownBookOpensThatBook() {
        var opened: String? = null
        render(
            feed = FriendsFeedState(
                friendsNow = setOf(friend),
                posts = listOf(
                    FriendsFeedRow(
                        id = "p2",
                        authorPseudonym = friend,
                        audience = Audience.FRIENDS,
                        text = "раджу",
                        sourceId = "work-7"
                    )
                ),
                bookTitles = mapOf("work-7" to "Тіні забутих предків")
            ),
            onOpenBook = { opened = it }
        )

        composeTestRule.onNodeWithTag("friends_post_p2").performClick()

        assertEquals("work-7", opened)
    }

    @Test
    fun aPostWithAnUnresolvedBookRendersNoBookLineAndIsInert() {
        render(
            FriendsFeedState(
                friendsNow = setOf(friend),
                posts = listOf(
                    FriendsFeedRow(
                        id = "p3",
                        authorPseudonym = friend,
                        audience = Audience.FRIENDS,
                        text = "без книги",
                        sourceId = "work-unknown"
                    )
                )
            )
        )

        composeTestRule.onNodeWithTag("friends_post_p3").assertExists()
        composeTestRule.onNodeWithTag("friends_post_book_p3", useUnmergedTree = true)
            .assertDoesNotExist()
        // No fake title, and nothing to tap.
        composeTestRule.onNodeWithTag("friends_post_p3")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    // ---- a11y ------------------------------------------------------------

    @Test
    fun theRootIsAHeadingAndANamedPane() {
        render(FriendsFeedState())

        composeTestRule.onNodeWithTag("friends_heading")
            .assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithText("Друзі").assertExists()
        composeTestRule.onNodeWithTag("friends_screen")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Друзі"))
    }

    @Test
    fun theRootStaysUsableAtTwoHundredPercentFontScale() {
        composeTestRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density,
                    fontScale = 2f
                )
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        FriendsScreen(
                            feed = FriendsFeedState(friendsNow = setOf(friend)),
                            viewerPseudonym = me,
                            onOpenBook = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("friends_heading").assertExists().assertIsDisplayed()
        composeTestRule.onNodeWithTag("friends_empty_no_posts").assertExists()
    }

    // ---- time ------------------------------------------------------------

    @Test
    fun anUnknownTimeRendersNothingRatherThanAFabricatedOne() {
        assertTrue(formatFriendsPostTime(0L).isEmpty())
        assertTrue(formatFriendsPostTime(-1L).isEmpty())
        assertTrue(formatFriendsPostTime(1_700_000_000_000L).isNotBlank())
    }
}
