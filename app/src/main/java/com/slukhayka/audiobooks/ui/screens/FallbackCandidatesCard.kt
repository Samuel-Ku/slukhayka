package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.editions.FallbackCandidate
import com.slukhayka.audiobooks.data.editions.FallbackCandidateKind
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * #530 / AC9 — the ORDERED fallback offer, shown before anything switches.
 * Every candidate is labelled with its own Source and what it is; a candidate
 * that [FallbackCandidate.requiresConfirmation] carries the explicit «Перемкнути»
 * affordance instead of a plain «Слухати», so a different narration is always a
 * decision the listener makes. The list keeps the policy's order.
 *
 * The host renders this only for a non-empty offer; an empty offer renders
 * nothing (the honest "there is nothing safe to offer").
 */
@Composable
fun FallbackCandidatesCard(
    candidates: List<FallbackCandidate>,
    onSelect: (FallbackCandidate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (candidates.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(AppDimens.RadiusCard),
        modifier = modifier
            .fillMaxWidth()
            .testTag("fallback_candidates_card")
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.fallback_candidates_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            candidates.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sourceDisplayName(candidate.sourceId),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(kindLabelRes(candidate.kind)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { onSelect(candidate) },
                        modifier = Modifier
                            .testTag("fallback_candidate_${candidate.sourceId}")
                            .padding(start = 8.dp)
                    ) {
                        Text(
                            text = if (candidate.requiresConfirmation) {
                                stringResource(R.string.fallback_candidate_switch)
                            } else {
                                stringResource(R.string.fallback_candidate_play)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun kindLabelRes(kind: FallbackCandidateKind): Int = when (kind) {
    FallbackCandidateKind.LOCAL -> R.string.fallback_kind_local
    FallbackCandidateKind.CURRENT_DIRECT -> R.string.fallback_kind_current
    FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION -> R.string.fallback_kind_same_edition
    FallbackCandidateKind.BROWSER -> R.string.fallback_kind_browser
    FallbackCandidateKind.CONFIRMED_OTHER_EDITION -> R.string.fallback_kind_other_edition
}
