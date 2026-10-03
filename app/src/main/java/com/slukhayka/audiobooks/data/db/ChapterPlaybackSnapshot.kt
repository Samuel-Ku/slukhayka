package com.slukhayka.audiobooks.data.db

/** One Room read transaction: logical chapters and physical tracks share the same order. */
data class ChapterPlaybackSnapshot(
    val chapters: List<ChapterEntity>,
    val editionChapters: List<ChapterEntity>,
    val edition: EditionEntity?,
    val sources: List<SourceEntity>,
    val tracks: List<SourceTrackEntity>,
    val originalChapterIds: List<String>
) {
    /** Shared profiles describe provider order, never this listener's display order. */
    fun providerPairs(sourceId: String): List<Pair<ChapterEntity, SourceTrackEntity?>> {
        val byId = chapters.associateBy { it.id }
        val byIndex = tracks.filter { it.sourceId == sourceId }.associateBy { it.trackIndex }
        return originalChapterIds.mapNotNull { id -> byId[id]?.let { it to byIndex[it.chapterIndex] } }
    }
}
