package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The minimum identity needed to keep a Source change invisible while making
 * a real Edition change explicit. A blank key means the catalogue has not
 * asserted enough identity to warn truthfully.
 *
 * [#520] — [sourceName] is provenance for the confirmation prompt: the
 * listener sees which source the found narration comes from before switching.
 */
data class NarrationSwitchIdentity(
    val workKey: String,
    val editionKey: String,
    val narrator: String,
    val title: String,
    val sourceName: String = ""
)

data class NarrationSwitchPrompt(
    val currentNarrator: String,
    val targetNarrator: String,
    val title: String,
    val targetEditionKey: String,
    /** [#520] — the source of the found narration, "" when unknown. */
    val targetSourceName: String = ""
)

fun narrationSwitchIdentity(book: AudiobookEntity): NarrationSwitchIdentity {
    val workKey = book.mergeKey.ifBlank { book.workId.orEmpty() }
    val editionKey = if (workKey.isBlank() || book.narrator.isBlank()) "" else {
        EditionId.forBook(workKey, book.id, book.narrator, book.language)
    }
    return NarrationSwitchIdentity(
        workKey,
        editionKey,
        book.narrator,
        book.title,
        sourceDisplayName(sourceIdForUrl(book.sourceUrl))
    )
}

fun narrationSwitchIdentity(result: GlobalSearchResult): NarrationSwitchIdentity {
    val workKey = result.mergeKey
    val assertedEditions = result.sources
        .map { it.editionId }
        .filter { it.isNotBlank() }
        .distinct()
    val editionKey = when {
        assertedEditions.size == 1 -> assertedEditions.single()
        assertedEditions.size > 1 -> ""
        workKey.isBlank() || result.narrator.isBlank() -> ""
        else -> EditionId.forBook(workKey, result.key, result.narrator, result.language)
    }
    return NarrationSwitchIdentity(
        workKey,
        editionKey,
        result.narrator,
        result.title,
        // One Work may be carried by several sources; the prompt states them
        // all, deduplicated, so the listener knows where the narration is.
        result.sources.map { it.sourceName }.filter { it.isNotBlank() }.distinct().joinToString(" · ")
    )
}

fun narrationSwitchIdentity(book: CatalogBook): NarrationSwitchIdentity {
    val workKey = book.mergeKey.ifBlank { book.workId.orEmpty() }
    val editionKey = if (workKey.isBlank() || book.narrator.isBlank()) "" else {
        EditionId.forBook(workKey, book.id, book.narrator)
    }
    return NarrationSwitchIdentity(
        workKey,
        editionKey,
        book.narrator,
        book.title,
        sourceDisplayName(sourceIdForUrl(book.url))
    )
}

/**
 * Different Sources of one Edition are recovery, not a narration switch.
 * Conversely, two asserted Editions of one Work never share listening state
 * and therefore require an explicit listener choice.
 */
fun requiresNarrationSwitchConfirmation(
    current: NarrationSwitchIdentity?,
    target: NarrationSwitchIdentity,
    approvedEditionKey: String? = null
): Boolean {
    current ?: return false
    if (current.workKey.isBlank() || target.workKey.isBlank()) return false
    if (current.workKey != target.workKey) return false
    if (current.editionKey.isBlank() || target.editionKey.isBlank()) return false
    if (current.editionKey == target.editionKey) return false
    return target.editionKey != approvedEditionKey
}

/**
 * #520 — the confirmation state machine behind the player's narration switch.
 *
 * A tap that would move to a different Edition of the same Work is DEFERRED:
 * [request] publishes a [NarrationSwitchPrompt] and keeps the action pending,
 * so nothing plays and the current Edition, its source and its Listening State
 * stay exactly as they were until the listener decides. [confirm] runs the
 * pending action once and remembers the approval for that target;
 * [dismiss] drops it and changes nothing.
 *
 * Pure JVM and observable, so the deferral/refusal contract is unit-testable
 * without a player or an Application.
 */
class NarrationSwitchGate {

    private val _prompt = MutableStateFlow<NarrationSwitchPrompt?>(null)
    val prompt: StateFlow<NarrationSwitchPrompt?> = _prompt.asStateFlow()

    private var pendingAction: (() -> Unit)? = null
    private var approvedEditionKey: String? = null

    /**
     * Runs [action] immediately when no confirmation is required; otherwise
     * defers it and shows the prompt. Returns true when it ran immediately.
     */
    fun request(
        current: NarrationSwitchIdentity?,
        target: NarrationSwitchIdentity,
        action: () -> Unit
    ): Boolean {
        if (!requiresNarrationSwitchConfirmation(current, target, approvedEditionKey)) {
            action()
            return true
        }
        pendingAction = action
        _prompt.value = NarrationSwitchPrompt(
            currentNarrator = current?.narrator.orEmpty(),
            targetNarrator = target.narrator,
            title = target.title,
            targetEditionKey = target.editionKey,
            targetSourceName = target.sourceName
        )
        return false
    }

    /** Runs the deferred action exactly once; a no-op when nothing is pending. */
    fun confirm() {
        val prompt = _prompt.value ?: return
        val action = pendingAction ?: return
        approvedEditionKey = prompt.targetEditionKey
        pendingAction = null
        _prompt.value = null
        action()
    }

    /** Keeps the current Edition untouched: the deferred action is dropped. */
    fun dismiss() {
        pendingAction = null
        _prompt.value = null
    }
}
