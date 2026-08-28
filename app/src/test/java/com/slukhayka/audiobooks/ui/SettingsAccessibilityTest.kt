package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.screens.RecommendationResetControl
import com.slukhayka.audiobooks.ui.screens.SettingsRadioOption
import com.slukhayka.audiobooks.ui.screens.SettingsSwitchRow
import com.slukhayka.audiobooks.ui.screens.ProfileScreen
import com.slukhayka.audiobooks.ui.screens.StorageDestinationPane
import com.slukhayka.audiobooks.ui.screens.SettingsDestinationScaffold
import com.slukhayka.audiobooks.ui.screens.SettingsDestination
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.data.identity.FakeListenerIdentity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun profileDestinationFocusesItsHeadingAndReflowsAtTwoHundredPercentText() {
        var backClicks = 0
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        ProfileScreen(
                            identity = FakeListenerIdentity(Random(44)),
                            onBackClick = { backClicks += 1 }
                        )
                    }
                }
            }
        }

        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Профіль"),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag("profile_screen_heading", useUnmergedTree = true)
            .assertTextEquals("Профіль")
            .assertIsDisplayed()
            .assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Назад")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithTag("profile_nickname_field")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_nickname_save")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertEquals(1, backClicks)
    }

    @Test
    fun networkPrivacyDestinationChromeReflowsAndFocusesItsHeadingAtTwoHundredPercentText() {
        assertSettingsDestinationChrome(
            destination = SettingsDestination.NetworkPrivacy,
            title = "Приватність",
            headingTag = "network_privacy_screen_heading"
        )
    }

    @Test
    fun recommendationDestinationChromeReflowsAndFocusesItsHeadingAtTwoHundredPercentText() {
        assertSettingsDestinationChrome(
            destination = SettingsDestination.Recommendations,
            title = "Персональні рекомендації",
            headingTag = "recommendations_screen_heading"
        )
    }

    @Test
    fun storageDestinationChromeReflowsAndFocusesItsHeadingAtTwoHundredPercentText() {
        assertSettingsDestinationChrome(
            destination = SettingsDestination.Storage,
            title = "Завантаження та пам'ять",
            headingTag = "storage_destination_screen_heading"
        )
    }

    @Test
    fun switchRowIsOneContextualToggleAndForwardsTheChange() {
        var requested = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                SettingsSwitchRow(
                    title = "Шифрування DNS",
                    description = "Захищає назви сайтів від провайдера.",
                    checked = false,
                    onCheckedChange = { requested = it },
                    modifier = Modifier,
                    testTag = "settings_switch"
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_switch", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.Off
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Вимкнено"
                )
            )
            .performClick()

        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag("settings_switch")
            .assertTextContains("Шифрування DNS")
            .assertTextContains("Захищає назви сайтів від провайдера.")
        assertEquals(true, requested)
    }

    @Test
    fun radioRowsAreOneSelectableNodeEachAndExposeSelection() {
        var selected = "direct"
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Column(Modifier.selectableGroup()) {
                    SettingsRadioOption(
                        title = "Пряме з'єднання",
                        description = "Без проксі.",
                        selected = selected == "direct",
                        onSelect = { selected = "direct" },
                        testTag = "route_direct"
                    )
                    SettingsRadioOption(
                        title = "Власний проксі",
                        description = "Джерела бачать адресу проксі.",
                        selected = selected == "proxy",
                        onSelect = { selected = "proxy" },
                        testTag = "route_proxy"
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("route_direct", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
            useUnmergedTree = true
        ).assertCountEquals(2)
        composeTestRule.onNodeWithTag("route_direct")
            .assertTextContains("Пряме з'єднання")
            .assertTextContains("Без проксі.")

        composeTestRule.onNodeWithTag("route_proxy", useUnmergedTree = true).performClick()
        assertEquals("proxy", selected)
    }

    @Test
    fun destructiveStorageActionExplainsItsConsequenceAndOpensNamedDialog() {
        val showDelete = mutableStateOf(true)
        var deleteConfirmed = false
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        StorageDestinationPane(
                            storageText = "1,2 ГБ · 5 аудіокниг офлайн",
                            hasLocalBooks = false,
                            showDelete = showDelete.value,
                            bookCount = 5,
                            bytes = 1_200_000_000,
                            onRescan = {},
                            onDeleteConfirmed = {
                                deleteConfirmed = true
                                showDelete.value = false
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Небезпечна зона")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithText("Видалити завантажені файли")
            .assertHeightIsAtLeast(48.dp)
            .performScrollTo()
            .performClick()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Видалити завантажені файли?"
            ),
            useUnmergedTree = true
        ).assertExists()
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag("clear_cache_dialog_heading", useUnmergedTree = true)
            .assertIsFocused()
        composeTestRule.onNodeWithTag("storage_destination_screen", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
        composeTestRule.onNodeWithText("Видалити 5 завантажених книг", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Скасувати").performClick()
        composeTestRule.onNodeWithTag("clear_cache_button")
            .assertIsFocused()
            .performClick()
        composeTestRule.onNodeWithTag("clear_cache_confirm")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(true, deleteConfirmed)
        composeTestRule.onNodeWithTag("storage_device_heading")
            .assertIsFocused()
    }

    @Test
    fun closingRecommendationResetDialogReturnsFocusToItsTrigger() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                RecommendationResetControl(onReset = {})
            }
        }

        composeTestRule.onNodeWithTag("recommendation_reset_button")
            .performClick()
        composeTestRule.onNodeWithTag("recommendation_reset_dialog", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Скинути персоналізацію?"
                )
            )
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag(
            "recommendation_reset_dialog_heading",
            useUnmergedTree = true
        )
            .assertIsFocused()
        composeTestRule.onNodeWithText("Скасувати").performClick()
        composeTestRule.onNodeWithTag("recommendation_reset_button")
            .assertIsFocused()
    }

    @Test
    fun criticalSettingsActionsRemainReachableAtTwoHundredPercentText() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Column(
                        Modifier
                            .width(320.dp)
                            .height(480.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SettingsSwitchRow(
                            title = "Навчатися лише на цьому пристрої",
                            description = "Обрані книги, прогрес і відмови не залишають пристрій.",
                            checked = true,
                            onCheckedChange = {},
                            testTag = "large_text_switch"
                        )
                        SettingsRadioOption(
                            title = "Максимальна приватність",
                            description = "Через Tor локально. Потрібен запущений Orbot.",
                            selected = true,
                            onSelect = {},
                            testTag = "large_text_radio"
                        )
                        RecommendationResetControl(onReset = {})
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("large_text_switch")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("recommendation_reset_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("large_text_radio")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("recommendation_reset_button")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(
            "recommendation_reset_dialog_heading",
            useUnmergedTree = true
        ).assertIsFocused()
        composeTestRule.onNodeWithTag("recommendation_reset_confirm")
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Скасувати").performClick()
        composeTestRule.onNodeWithTag("recommendation_reset_button")
            .assertIsFocused()
    }

    @Test
    fun profileRestoreDialogWorksAndReturnsFocusAtTwoHundredPercentText() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        ProfileScreen(
                            identity = FakeListenerIdentity(Random(44)),
                            onBackClick = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("profile_restore_field")
            .performScrollTo()
            .performTextInput("код відновлення")
        composeTestRule.onNodeWithTag("profile_restore_button")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithTag("profile_restore_dialog", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Замінити поточний профіль?"
                )
            )
        composeTestRule.onNodeWithTag(
            "profile_restore_dialog_heading",
            useUnmergedTree = true
        ).assertIsFocused()
        composeTestRule.onNodeWithTag("profile_screen_pane", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
        composeTestRule.onNodeWithTag("profile_restore_confirm")
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Скасувати").performClick()
        composeTestRule.onNodeWithTag("profile_restore_button")
            .assertIsFocused()
    }

    private fun assertSettingsDestinationChrome(
        destination: SettingsDestination,
        title: String,
        headingTag: String
    ) {
        var backClicks = 0
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        SettingsDestinationScaffold(
                            destination = destination,
                            onBackClick = { backClicks += 1 }
                        ) { padding ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            ) {
                                Text("Налаштування")
                                Button(
                                    onClick = {},
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("settings_destination_action")
                                ) {
                                    Text("Застосувати")
                                }
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag(headingTag, useUnmergedTree = true)
            .assertTextEquals(title)
            .assertIsDisplayed()
            .assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Назад")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithTag("settings_destination_action")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertEquals(1, backClicks)
    }
}
