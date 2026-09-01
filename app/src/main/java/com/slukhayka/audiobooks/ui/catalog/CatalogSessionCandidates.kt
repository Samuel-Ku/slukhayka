package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator

/** An expired `name=` remnant from CookieManager is not a reusable session. */
fun hasUsableSourceSession(cookieHeader: String): Boolean = cookieHeader
    .split(';')
    .any { fragment ->
        val value = fragment.substringAfter('=', missingDelimiterValue = "").trim()
        value.isNotEmpty()
    }

/** Pure policy for trying a durable Browser Source session before its browser door. */
fun catalogSessionCandidates(
    source: SourceEntity,
    mode: SourceAccessMode,
    hasFirstPartySession: Boolean
): List<SourceSelectionCoordinator.SourceCandidate> = when {
    source.type == "local" -> listOf(
        SourceSelectionCoordinator.SourceCandidate(
            source,
            SourceSelectionCoordinator.SourceCategory.LOCAL
        )
    )
    mode == SourceAccessMode.DIRECT -> listOf(
        SourceSelectionCoordinator.SourceCandidate(
            source,
            SourceSelectionCoordinator.SourceCategory.DIRECT
        )
    )
    mode == SourceAccessMode.BROWSER -> buildList {
        if (hasFirstPartySession) {
            add(
                SourceSelectionCoordinator.SourceCandidate(
                    source.copy(id = "${source.id}|session"),
                    SourceSelectionCoordinator.SourceCategory.UNKNOWN
                )
            )
        }
        add(
            SourceSelectionCoordinator.SourceCandidate(
                source.copy(id = "${source.id}|browser"),
                SourceSelectionCoordinator.SourceCategory.BROWSER
            )
        )
    }
    else -> listOf(
        SourceSelectionCoordinator.SourceCandidate(
            source,
            SourceSelectionCoordinator.SourceCategory.UNKNOWN
        )
    )
}
