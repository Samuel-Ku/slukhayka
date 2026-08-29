package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.reviews.ListenerReview
import com.slukhayka.audiobooks.data.reviews.ListenerReviewLimits
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Spec-40 #277/#278/#280 — the book page's «Відгуки» vocabulary: the star
 * row, the Google-style review card (stars ABOVE the text, then nickname,
 * then date), the honest pending state of an offline-written review, and
 * the write/edit form. Pure `@Composable`s over fixture data so the
 * snapshot seam can pin every state (SourceProfileBlock precedent).
 */

/**
 * Rating seam shared by read-only cards and the editor. Read-only ratings
 * are one spoken value; editing exposes exactly five RadioButton choices.
 */
@Composable
fun ReviewStarsRow(
    rating: Int,
    modifier: Modifier = Modifier,
    starSize: Int = 16,
    interactive: Boolean = false,
    onRatingChange: (Int) -> Unit = {}
) {
    val ratingSummary = stringResource(R.string.book_detail_review_rating_summary, rating)
    val rowModifier = if (interactive) {
        modifier.selectableGroup()
    } else {
        modifier.clearAndSetSemantics {
            contentDescription = ratingSummary
        }
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(ListenerReviewLimits.MAX_RATING) { index ->
            val position = index + 1
            if (interactive) {
                val choiceLabel = stringResource(R.string.book_detail_review_rating_choice, position)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("rating_star_$position")
                        .selectable(
                            selected = position == rating,
                            role = Role.RadioButton,
                            onClick = { onRatingChange(position) }
                        )
                        .semantics { contentDescription = choiceLabel },
                    contentAlignment = Alignment.Center
                ) {
                    StarIcon(filled = position <= rating, size = starSize)
                }
            } else {
                StarIcon(filled = position <= rating, size = starSize)
            }
        }
    }
}

@Composable
private fun StarIcon(filled: Boolean, size: Int) {
    Icon(
        imageVector = if (filled) Icons.Default.Star else Icons.Default.StarBorder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(size.dp)
    )
}

/** «12 серп. 2026 р.» style label for a review's createdAt stamp. */
fun reviewDateLabel(createdAtMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale("uk")).format(Date(createdAtMillis))

/**
 * One listener review, Google-style: stars above everything, optional text,
 * then the nickname and the date; the edition tag renders as a muted
 * «Начитка: …» chip ONLY when the reviewer named one (#278). An own review
 * offers edit/delete; a pending one carries the honest «надішлемо при
 * мережі» line instead of pretending it is already published (#280).
 */
@Composable
fun ReviewCard(
    review: ListenerReview,
    workTitle: String,
    isOwn: Boolean,
    isPending: Boolean,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onHideAuthor: () -> Unit = {},
    deleteFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val editLabel = stringResource(
        R.string.book_detail_review_edit,
        workTitle,
        review.rating
    )
    val deleteLabel = stringResource(
        R.string.book_detail_review_delete,
        workTitle,
        review.rating
    )
    val authorActionsLabel = stringResource(
        R.string.book_detail_review_author_actions,
        review.authorName
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("review_card_${review.uid}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Stars ABOVE the text — the Google pattern the ticket pins.
            ReviewStarsRow(rating = review.rating)

            Spacer(modifier = Modifier.height(8.dp))

            if (!review.body.isNullOrBlank()) {
                Text(
                    text = review.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // #278 — the narration this review listened to, as a metadata
            // chip. An empty tag renders nothing (ADR-0014: no filler).
            if (!review.editionTag.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = "Начитка: ${review.editionTag}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isPending) {
                // #280 — honest pending state: the card exists locally and is
                // queued; it is NOT published yet.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "надішлемо при мережі",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = review.authorName,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = reviewDateLabel(review.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Rare actions stay secondary (spec-27): small text buttons;
                // delete sits behind its own confirmation dialog upstream.
                if (isOwn) {
                    TextButton(
                        onClick = onEdit,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = editLabel }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(2.dp))
                        Text(
                            "Змінити",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clearAndSetSemantics { }
                        )
                    }
                    TextButton(
                        onClick = onDelete,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .then(
                                deleteFocusRequester?.let { Modifier.focusRequester(it) }
                                    ?: Modifier
                            )
                            .semantics { contentDescription = deleteLabel }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    // Spec-40 #281 — the ONLY moderation v1 offers: hide this
                    // author's reviews locally, reversibly, without a server.
                    var muteMenuOpen by remember { mutableStateOf(false) }
                    androidx.compose.material3.IconButton(
                        onClick = { muteMenuOpen = true },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = authorActionsLabel }
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = muteMenuOpen,
                        onDismissRequest = { muteMenuOpen = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.book_detail_review_hide_author,
                                        review.authorName
                                    )
                                )
                            },
                            onClick = {
                                muteMenuOpen = false
                                onHideAuthor()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** «Не вказувати» — the dropdown's explicit no-tag choice (#278). */
const val EDITION_TAG_NONE = "Не вказувати"

/**
 * ADR-0023 (#348) — the narration-rating row beside the narrator's name:
 * the crowd average ONLY when votes exist (#383 / ADR-0014 — zero votes
 * never draw even the bare «Начитка:» line, same honest-absence rule as the
 * combined-average guard), below it THIS listener's interactive stars under
 * the explicit invitation «Оцінити начитку» so an unrated narration asks for
 * a rating instead of faking a 0-star value. Renders nothing when there is
 * nothing to show and nobody to ask.
 */
@Composable
fun NarrationRatingRow(
    average: Double?,
    voteCount: Int,
    ownRating: Int?,
    canRate: Boolean,
    onRate: (Int) -> Unit,
    onDeleteOwn: (() -> Unit)? = null,
    deleteFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    if (average == null && !canRate) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("narration_rating_row"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        // #383 — the whole average line (label + stars + count) exists only
        // behind real votes; without them there is nothing truthful to show.
        if (average != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Начитка:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(6.dp))
                ReviewStarsRow(rating = kotlin.math.round(average).toInt(), starSize = 14)
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = String.format(java.util.Locale.US, "%.1f", average) + " · $voteCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("narration_rating_average")
                )
            }
        }
        if (canRate) {
            // The rater block always names itself («Оцінити начитку») — with
            // no own rating yet these five outline stars are an invitation to
            // rate, never a displayed value.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Оцінити начитку",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(6.dp))
                ReviewStarsRow(
                    rating = ownRating ?: 0,
                    starSize = 14,
                    interactive = true,
                    onRatingChange = onRate,
                    modifier = Modifier.testTag("narration_rating_own_stars")
                )
                if (ownRating != null && onDeleteOwn != null) {
                    IconButton(
                        onClick = onDeleteOwn,
                        modifier = Modifier
                            .then(
                                deleteFocusRequester?.let { Modifier.focusRequester(it) }
                                    ?: Modifier
                            )
                            .size(48.dp)
                            .testTag("narration_rating_delete")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.book_detail_narration_rating_delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * #277/#278 — the write/edit form as a bottom sheet (ADR-0018: transient
 * input; dialogs stay for irreversible confirmations). Stars are REQUIRED
 * (Save stays disabled until at least one is set), the text body is
 * optional with a live counter against the 2000-char limit, and the edition
 * tag is a dropdown of THIS Work's editions from the local DB plus «Не
 * вказувати», prefilled with the currently open edition's narrator.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListenerReviewFormSheet(
    bookTitle: String,
    editing: ListenerReview?,
    editionOptions: List<String>,
    defaultEditionTag: String,
    onSave: (rating: Int, body: String?, editionTag: String?) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null
) {
    var selectedRating by remember { mutableIntStateOf(editing?.rating ?: 0) }
    var bodyText by remember { mutableStateOf(editing?.body.orEmpty()) }
    // Prefill from the currently open edition's narrator (its own card);
    // an edit keeps the review's stored tag instead.
    var selectedTag by remember {
        mutableStateOf(editing?.editionTag ?: defaultEditionTag.takeIf { it.isNotBlank() })
    }
    var tagMenuOpen by remember { mutableStateOf(false) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = Modifier.accessibilityPane(
            stringResource(R.string.book_detail_review_form_pane, bookTitle)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = stringResource(
                    if (editing == null) R.string.book_detail_review_new_title
                    else R.string.book_detail_review_edit_title
                ),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "«$bookTitle»",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.book_detail_review_rating_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            ReviewStarsRow(
                rating = selectedRating,
                starSize = 26,
                interactive = true,
                onRatingChange = { selectedRating = it },
                modifier = Modifier.testTag("review_form_stars")
            )

            Spacer(modifier = Modifier.height(4.dp))

            // #278 — the narration tag dropdown: this Work's editions (by
            // narrator) + the explicit no-tag choice.
            ExposedDropdownMenuBox(
                expanded = tagMenuOpen,
                onExpandedChange = { tagMenuOpen = it }
            ) {
                OutlinedTextField(
                    value = selectedTag ?: EDITION_TAG_NONE,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.book_detail_review_edition_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tagMenuOpen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                        .testTag("review_form_edition_tag")
                )
                ExposedDropdownMenu(
                    expanded = tagMenuOpen,
                    onDismissRequest = { tagMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(EDITION_TAG_NONE) },
                        onClick = {
                            selectedTag = null
                            tagMenuOpen = false
                        }
                    )
                    editionOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                selectedTag = option
                                tagMenuOpen = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = bodyText,
                onValueChange = { bodyText = it.take(ListenerReviewLimits.MAX_BODY_LEN) },
                label = { Text(stringResource(R.string.book_detail_review_body_label)) },
                minLines = 3,
                supportingText = {
                    // The live counter near the limit — always visible, honest.
                    Text("${bodyText.length} / ${ListenerReviewLimits.MAX_BODY_LEN}")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_form_body")
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            ReviewFormActions(
                canSave = selectedRating in
                    ListenerReviewLimits.MIN_RATING..ListenerReviewLimits.MAX_RATING,
                isSaving = isSaving,
                isEditing = editing != null,
                onSave = { onSave(selectedRating, bodyText.trim(), selectedTag) },
                onDismiss = onDismiss
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReviewFormActions(
    canSave: Boolean,
    isSaving: Boolean,
    isEditing: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val stackActions = LocalDensity.current.fontScale >= 1.5f
    val saveLabel = stringResource(
        when {
            isSaving -> R.string.book_detail_review_saving
            isEditing -> R.string.book_detail_review_save
            else -> R.string.book_detail_review_publish
        }
    )
    val saveButton: @Composable () -> Unit = {
        Button(
            onClick = onSave,
            enabled = canSave && !isSaving,
            modifier = Modifier
                .then(if (stackActions) Modifier.fillMaxWidth() else Modifier)
                .sizeIn(minHeight = 48.dp)
                .testTag("review_form_save")
        ) {
            Text(saveLabel)
        }
    }
    val cancelButton: @Composable () -> Unit = {
        TextButton(
            onClick = onDismiss,
            enabled = !isSaving,
            modifier = Modifier
                .then(if (stackActions) Modifier.fillMaxWidth() else Modifier)
                .sizeIn(minHeight = 48.dp)
        ) {
            Text(stringResource(R.string.book_detail_cancel), color = MaterialTheme.colorScheme.onSurface)
        }
    }

    if (stackActions) {
        Column(modifier = Modifier.fillMaxWidth()) {
            cancelButton()
            saveButton()
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            cancelButton()
            saveButton()
        }
    }
}

/** Exact, destructive confirmation for the listener's own review. */
@Composable
fun ReviewDeleteConfirmation(
    workTitle: String,
    review: ListenerReview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val titleFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.accessibilityPane(
            stringResource(R.string.book_detail_review_delete_pane)
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.book_detail_review_delete_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .focusRequester(titleFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("review_delete_title")
            )
            // Stable Dialog initial-focus seam: unlike a Material Button's
            // internal focus target, this explicit title target is attached
            // directly to the requester.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                titleFocusRequester.requestFocus()
            }
        },
        text = {
            Text(
                text = stringResource(
                    if (review.body.isNullOrBlank()) {
                        R.string.book_detail_review_delete_consequence_without_text
                    } else {
                        R.string.book_detail_review_delete_consequence_with_text
                    },
                    workTitle,
                    review.rating
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("review_delete_confirm")
            ) {
                Text(
                    stringResource(R.string.book_detail_review_delete_confirm),
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = 48.dp)
            ) {
                Text(
                    stringResource(R.string.book_detail_cancel),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

/**
 * Owns the modal's focus round trip while the screen owns the selected
 * review. Keeping this seam composed at `review == null` makes restoration
 * deterministic after either confirm or cancel.
 */
@Composable
fun ReviewDeleteConfirmationOwner(
    workTitle: String,
    review: ListenerReview?,
    returnFocusRequester: FocusRequester,
    fallbackFocusRequester: FocusRequester? = null,
    onConfirm: (ListenerReview) -> Unit,
    onDismiss: () -> Unit
) {
    RestoreFocusAfterModal(
        modalVisible = review != null,
        returnFocusRequester = returnFocusRequester,
        fallbackFocusRequester = fallbackFocusRequester
    )
    review?.let { doomed ->
        ReviewDeleteConfirmation(
            workTitle = workTitle,
            review = doomed,
            onConfirm = { onConfirm(doomed) },
            onDismiss = onDismiss
        )
    }
}

/**
 * Spec-40 #282 — the source site's own visitors' comments as a plainly
 * labelled simple list UNDER our review cards, never mixed into them: parsed
 * texts carry no author/date/rating and do not survive card form. A source
 * without comments renders nothing (absent, never filler).
 */
@Composable
fun VisitorCommentsSubblock(
    profile: com.slukhayka.audiobooks.data.entries.LibraryEntries.SourceProfile,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = "Коментарі відвідувачів ${profile.sourceName}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        profile.visitorComments.forEach { comment ->
            Text(
                text = "• $comment",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
