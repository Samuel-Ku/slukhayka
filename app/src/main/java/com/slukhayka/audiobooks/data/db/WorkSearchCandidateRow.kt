package com.slukhayka.audiobooks.data.db

/** Cursor over the existing FTS projection; this is a query result, not another stored index. */
data class WorkSearchCandidateRow(
    val rowId: Long,
    val workId: String,
    val title: String,
    val author: String,
    val series: String,
    val narrator: String
) {
    fun document() = WorkSearchFtsEntity(workId, title, author, series, narrator)
}

/** Lightweight cursor over owned narrator claims, without materializing the catalog. */
data class OwnedNarratorSearchRow(val rowId: Long, val workId: String, val narrator: String)
