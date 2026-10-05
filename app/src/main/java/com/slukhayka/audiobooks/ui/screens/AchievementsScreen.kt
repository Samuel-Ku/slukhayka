package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.achievements.AchievementBoard
import com.slukhayka.audiobooks.data.achievements.AchievementDefinition
import com.slukhayka.audiobooks.data.achievements.ListenerTitle
import com.slukhayka.audiobooks.ui.achievements.achievementNotice
import com.slukhayka.audiobooks.ui.components.EmptyState

/**
 * #704 (T6) — the «Досягнення» screen.
 *
 * What to show is decided by [AchievementBoard], not here: the screen renders
 * the two lists it is handed, so the rule «приховані не розкриваються до
 * здобуття» has exactly one owner and is tested without pixels.
 *
 * Everything is composed from what the app already has — the same destination
 * scaffold as every other settings screen, the canonical [EmptyState], and the
 * shared title/notice naming — because the spec says «a11y-контракти канонічні;
 * нових швів не вводимо».
 */
@Composable
fun AchievementsScreen(
    board: AchievementBoard,
    title: ListenerTitle,
    showcase: List<String>,
    onBackClick: () -> Unit
) {
    SettingsDestinationScaffold(
        destination = SettingsDestination.Achievements,
        onBackClick = onBackClick
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("achievements_screen")
        ) {
            if (board.isEmpty) {
                EmptyState(
                    icon = Icons.Default.EmojiEvents,
                    title = stringResource(R.string.achievements_empty_title),
                    body = stringResource(R.string.achievements_empty_body),
                    modifier = Modifier.testTag("achievements_empty_state")
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.achievements_tier, title.label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("achievements_tier")
            )

            if (showcase.isNotEmpty()) {
                SectionHeading(stringResource(R.string.achievements_showcase), "achievements_showcase_heading")
                AchievementList(showcase, "achievements_showcase_item")
            }

            if (board.earned.isNotEmpty()) {
                SectionHeading(stringResource(R.string.achievements_earned), "achievements_earned_heading")
                AchievementList(board.earned.map { it.id }, "achievements_earned_item")
            }

            if (board.upcoming.isNotEmpty()) {
                SectionHeading(stringResource(R.string.achievements_upcoming), "achievements_upcoming_heading")
                AchievementList(board.upcoming.map { it.id }, "achievements_upcoming_item")
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, tag: String) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        // A heading is what a screen reader needs to move between sections.
        modifier = Modifier.semantics { heading() }.testTag(tag)
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * The award ids are resolved through the SAME naming the notice uses, so a
 * screen and a toast can never disagree about what an award is called.
 */
@Composable
private fun AchievementList(ids: List<String>, tagPrefix: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (id in ids) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_$id"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = achievementNotice(context, id), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }
}

/** Kept so the screen and its previews agree on one definition shape. */
internal fun AchievementDefinition.label(): String = id
