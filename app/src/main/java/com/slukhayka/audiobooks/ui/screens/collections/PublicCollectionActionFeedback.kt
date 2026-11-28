package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import kotlinx.coroutines.flow.Flow

/**
 * #1822 — the Огляд feed answers what a vote or a report actually did.
 *
 * (The ticket calls that surface «Слухати»; the code's name for it is
 * [com.slukhayka.audiobooks.ui.screens.HomeScreen] — Огляд.)
 *
 * Both outcomes are published by the ViewModel on replay-less event flows
 * ([com.slukhayka.audiobooks.ui.MainViewModel.publicCollectionVoteResults],
 * [com.slukhayka.audiobooks.ui.MainViewModel.collectionReportResults]). A
 * screen that does not collect them loses every event, and an offline or
 * no-profile tap then changes nothing at all — the silent state ADR-0014 §21
 * forbids. This composable is that subscription, kept next to the page it
 * answers so both the page and the snackbar above it survive one owner.
 *
 * It mirrors the working model on the book page (spec-51 #696): a stored
 * report closes the page, then offers «Скасувати» for the LOCAL hide only —
 * the complaint itself stands. A refusal is words, never silence.
 */
@Composable
fun PublicCollectionActionFeedback(
    /** true = stored, false = the honest refusal (offline / no identity). */
    voteResults: Flow<Boolean>,
    /** documentId -> stored; false is the same honest refusal. */
    reportResults: Flow<Pair<String, Boolean>>,
    snackbarHostState: SnackbarHostState,
    /** Closes the page a stored report was raised from. */
    onReported: () -> Unit,
    /** Reverses the local hide of that document; the complaint stands. */
    onUndoLocalHide: (String) -> Unit
) {
    val voteErrorMessage = stringResource(R.string.collection_vote_error)
    val reportedMessage = stringResource(R.string.collection_reported)
    val reportUndoLabel = stringResource(R.string.collection_report_undo)
    val reportErrorMessage = stringResource(R.string.collection_report_error)

    // Spec-51 (#694) — a stored vote is told by the stars themselves, so only
    // the refusal needs a line of text; announcing both would contradict the
    // state the listener already sees.
    LaunchedEffect(voteResults, snackbarHostState, voteErrorMessage) {
        voteResults.collect { stored ->
            if (!stored) snackbarHostState.showSnackbar(voteErrorMessage)
        }
    }

    // Spec-51 (#696) — the reporter's collection leaves their surfaces at
    // once; the page closes and only that local hide is reversible.
    LaunchedEffect(reportResults, snackbarHostState, reportedMessage) {
        reportResults.collect { (documentId, stored) ->
            if (!stored) {
                snackbarHostState.showSnackbar(reportErrorMessage)
                return@collect
            }
            onReported()
            val action = snackbarHostState.showSnackbar(
                message = reportedMessage,
                actionLabel = reportUndoLabel
            )
            if (action == SnackbarResult.ActionPerformed) onUndoLocalHide(documentId)
        }
    }
}
