package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F15/F34 class — the «Огляд» header's refresh was the ONE root action below
 * the 48 dp floor: a bare 44 dp circle next to the 48 dp gear and the
 * 48 dp «+»/⋮ of other roots. It stays quiet (no tonal disc), but the target
 * must be the canonical TouchTarget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-w411dp-h891dp", sdk = [35])
class HomeRefreshTargetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the explore refresh keeps the canonical 48 dp target and still clicks`() {
        var refreshes = 0
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeHeader(
                        searchQuery = "",
                        onRefresh = { refreshes += 1 },
                        onSearchQueryChange = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("home_refresh")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, refreshes)
    }
}
