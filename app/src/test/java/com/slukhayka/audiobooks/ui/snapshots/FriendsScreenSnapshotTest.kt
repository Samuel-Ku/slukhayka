package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.social.Audience
import com.slukhayka.audiobooks.ui.screens.FriendsFeedRow
import com.slukhayka.audiobooks.ui.screens.FriendsFeedState
import com.slukhayka.audiobooks.ui.screens.FriendsScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.After
import org.junit.Before
import java.util.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #885 (wave 3) — the friends screen, which had NO golden at all.
 *
 * Found while about to restyle the post block (audit item 1 of «Друзі»: the
 * prototype draws a FLAT post with a bottom rule, `.sl-social-post` `:1259`,
 * where the app draws an elevated `Card`). The change would have moved **zero**
 * goldens — not because nothing was touched, but because only the *header* had
 * one (`tab_header_friends.png`) and `FriendsScreenTest` is a behaviour test
 * with no `captureRoboImage` at all.
 *
 * That is the sixth coverage gap this session, and the reason this file exists
 * BEFORE the restyle rather than after it: a visible change to a screen nobody
 * photographs is a change nobody reviews.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class FriendsScreenSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * The post shows a timestamp, and `formatFriendsPostTime` renders it with
     * `SimpleDateFormat`, which reads the **JVM default time zone**.
     *
     * That made the first version of this golden environment-dependent: the
     * same fixed instant rendered «14 лист., 23:13» locally (UTC+1) and
     * «14 лист., 22:13» in CI (UTC), so the snapshot gate failed on a fresh
     * record with a diff of 24 bytes. A golden that depends on where it was
     * recorded is not a golden.
     *
     * Pinning UTC makes the rendered time a property of the FIXTURE rather
     * than of the machine. Restored afterwards so this file cannot leak a
     * default into other tests in the same JVM.
     */
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private val friend = "Оксана"

    private fun populatedFeed() = FriendsFeedState(
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

    @Test
    fun a_friends_post_pins_the_block() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ScreenSurface {
                    FriendsScreen(
                        feed = populatedFeed(),
                        viewerPseudonym = "Слухач-0042",
                        onOpenBook = {}
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/friends_feed_post.png"
        )
    }

    @Test
    fun the_no_friends_state_pins_the_empty_feed() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ScreenSurface {
                    FriendsScreen(
                        feed = FriendsFeedState(),
                        viewerPseudonym = "Слухач-0042",
                        onOpenBook = {}
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/friends_feed_empty.png"
        )
    }

    /** A painted background — an unpainted surface invents defects (#1080). */
    @Composable
    private fun ScreenSurface(content: @Composable () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content
        )
    }
}
