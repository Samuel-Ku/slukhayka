package com.slukhayka.audiobooks.ui.components

import com.slukhayka.audiobooks.data.facets.GenreIdentity

/** A compact view of source-owned memberships, never a principal-genre claim. */
fun compactGenreLabel(sourceText: String): String {
    val genres = GenreIdentity.fromSourceText(sourceText)
    val visible = genres.take(2).joinToString(" · ") { it.label }
    return if (genres.size > 2) "$visible +${genres.size - 2}" else visible
}
