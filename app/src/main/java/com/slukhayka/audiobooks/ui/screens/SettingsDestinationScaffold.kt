package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkTabHeader
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent

internal enum class SettingsDestination(
    val titleRes: Int,
    // #885 (wave 3) — the prototype's settings row carries a LEADING ICON
    // (`.sl-list-row > svg`, `:1148-1153`, 21 px, muted). The app's rows had
    // none, so a settings list of seven rows was a wall of text with no
    // landmarks.
    val icon: ImageVector,
    // #885 (wave 3) — the prototype's row has a 12 px description under the
    // title (`.sl-list-row small`, :1153). It answers "what is behind this
    // row" without making the listener open it to find out.
    val descriptionRes: Int,
    val paneTag: String,
    val headingTag: String
) {
    Profile(
        descriptionRes = R.string.settings_desc_profile,
        icon = Icons.Default.Person,
        titleRes = R.string.profile_title,
        paneTag = "profile_screen_pane",
        headingTag = "profile_screen_heading"
    ),
    NetworkPrivacy(
        descriptionRes = R.string.settings_desc_network,
        icon = Icons.Default.Shield,
        titleRes = R.string.privacy_title,
        paneTag = "network_privacy_screen_pane",
        headingTag = "network_privacy_screen_heading"
    ),
    Recommendations(
        descriptionRes = R.string.settings_desc_recommendations,
        icon = Icons.Default.Star,
        titleRes = R.string.recommendations_title,
        paneTag = "recommendations_screen_pane",
        headingTag = "recommendations_screen_heading"
    ),
    // Spec-45 (#405) T6 (#494): the «Мови контенту» destination.
    ContentLanguages(
        descriptionRes = R.string.settings_desc_content_languages,
        icon = Icons.Default.Language,
        titleRes = R.string.content_languages_title,
        paneTag = "content_languages_screen_pane",
        headingTag = "content_languages_screen_heading"
    ),
    // Spec-45 (#405) R7 (#514): the «Мова інтерфейсу» destination.
    AppLocale(
        descriptionRes = R.string.settings_desc_app_locale,
        icon = Icons.Default.Translate,
        titleRes = R.string.app_locale_title,
        paneTag = "app_locale_screen_pane",
        headingTag = "app_locale_screen_heading"
    ),
    // ADR-0037 (spec-49 T1): the «Аудіо джерел» destination — the personal
    // Source Audio Refusal switch.
    SourceAudioRefusal(
        descriptionRes = R.string.settings_desc_source_refusal,
        icon = Icons.Default.VolumeOff,
        titleRes = R.string.source_audio_refusal_title,
        paneTag = "source_audio_refusal_screen_pane",
        headingTag = "source_audio_refusal_screen_heading"
    ),
    Storage(
        descriptionRes = R.string.settings_desc_data,
        icon = Icons.Default.SdCard,
        titleRes = R.string.storage_title,
        paneTag = "storage_destination_screen_pane",
        headingTag = "storage_destination_screen_heading"
    ),
    Achievements(
        descriptionRes = R.string.settings_desc_achievements,
        icon = Icons.Default.EmojiEvents,
        titleRes = R.string.achievements_title,
        paneTag = "achievements_screen_pane",
        headingTag = "achievements_screen_heading"
    )
}

/**
 * Shared chrome and entry-focus contract for pushed settings destinations.
 *
 * F10 of the 2026-10-28 device audit: the settings ROOT is «Папір і голос»
 * (brand mark, 34 sp title, paper ground) while every pushed destination was a
 * legacy M3 `TopAppBar` — 21 sp system font over the same paper list, a
 * different vocabulary one tap away. The seven destinations now live in the
 * SAME theme as Settings, and their header is the ONE pushed-screen header:
 * [PaperInkTabHeader] in `compact` mode (56 dp, SectionTitle, visible back) —
 * the pattern the direction already names for sub-screens.
 *
 * The BODIES of these screens are still legacy components: this change is the
 * chrome+theme seam, so their lists now read the paper palette (whose contrast
 * pairs are pinned by `PaperInkTokensTest`) while their own type/layout
 * migration stays a separate step. That limit is named in the PR, not hidden.
 */
@Composable
internal fun SettingsDestinationScaffold(
    destination: SettingsDestination,
    onBackClick: () -> Unit,
    modalVisible: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    val title = stringResource(destination.titleRes)
    // Focus contract: entering a pushed screen focuses its title, exactly as
    // the legacy TopAppBar did, so TalkBack announces the new pane.
    val headingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(title) {
        withFrameNanos { }
        headingFocusRequester.requestFocus()
    }

    PaperInkThemedContent {
        Scaffold(
            modifier = Modifier
                .testTag(destination.paneTag)
                .accessibilityPane(title)
                .accessibilityModalBackground(modalVisible),
            topBar = {
                PaperInkTabHeader(
                    title = title,
                    // A pushed screen: one compact line, no wordmark (the
                    // brand belongs to roots), the visible way back.
                    compact = true,
                    showBrandMark = false,
                    onBackClick = onBackClick,
                    headingTestTag = destination.headingTag,
                    returnFocusRequester = headingFocusRequester,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            content = content
        )
    }
}
