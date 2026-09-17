package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity

/**
 * spec-54 T07 (#874) — ONE row shape for a person's page, whichever source it
 * came from.
 *
 * The two existing screens disagreed on the ENTITY, not just on chrome: the
 * canonical author page showed Works, the person page showed books. The single
 * page shows **Works** for both roles (ADR-0046: the personal library is about
 * Works; ADR-0011: a card is a Work plus its narrations), so this row is the
 * meeting point:
 *
 * - a Work known to the app (`worksForAuthor` / `worksForNarrator`) becomes a
 *   row with its own id and the narrations that can play it;
 * - a card from a source page (which has a `mergeKey` but no WorkEntity) is
 *   grouped into the same kind of row by that key — never by guesswork: a card
 *   without a key stays its OWN row.
 *
 * The narrations themselves are untouched rows: their progress, bookmarks,
 * downloads and speed stay per narration.
 */
data class PersonWorkRow(
    val workId: String,
    val title: String,
    val author: String?,
    val narrator: String?,
    /** ADR-0047 §2 — this Work is in the listener's own library. */
    val ownedInLibrary: Boolean,
    /** What can play, newest first; each keeps its own facts. */
    val narrations: List<AudiobookEntity>,
    val coverUrl: String?
) {
    val hasSeveralNarrations: Boolean get() = narrations.size > 1
}

/** Rows for a person whose Works the app knows (the local projection). */
fun personWorkRows(
    works: List<WorkEntity>,
    ownedWorkIds: Set<String>,
    narrationsByWork: Map<String, List<AudiobookEntity>>
): List<PersonWorkRow> =
    works.sortedWith(
        compareBy<WorkEntity> { it.title.lowercase() }.thenBy { it.id }
    )
        .map { work ->
            val narrations = narrationsByWork[work.id].orEmpty()
            PersonWorkRow(
                workId = work.id,
                title = work.title,
                author = work.author.takeIf { it.isNotBlank() },
                narrator = narrations.firstOrNull()?.narrator?.takeIf { it.isNotBlank() },
                ownedInLibrary = work.id in ownedWorkIds,
                narrations = narrations,
                coverUrl = narrations.firstOrNull()?.coverImageUrl ?: work.coverImageUrl
            )
        }

/**
 * Rows for a person read from a SOURCE page: the cards carry a `mergeKey`, so
 * they group into Works exactly like the library does. A card with a blank key
 * is its own row — an unmergeable card is never folded into someone else's Work.
 */
fun personWorkRowsFromCards(
    cards: List<AudiobookEntity>,
    ownedWorkIds: Set<String>
): List<PersonWorkRow> =
    workCards(cards).map { card ->
        PersonWorkRow(
            workId = card.workKey,
            title = card.primary.title,
            author = card.primary.author.takeIf { it.isNotBlank() },
            narrator = card.primary.narrator.takeIf { it.isNotBlank() },
            ownedInLibrary = card.workKey.isNotBlank() && card.workKey in ownedWorkIds,
            narrations = card.narrations,
            coverUrl = card.primary.coverImageUrl
        )
    }
