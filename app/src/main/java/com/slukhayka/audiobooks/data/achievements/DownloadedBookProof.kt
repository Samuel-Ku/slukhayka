package com.slukhayka.audiobooks.data.achievements

/** One actual source track with its owning Edition's logical chapter count. */
data class DownloadedTrackProof(val bookId: String, val sourceId: String, val sourceType: String,
    val chapterCount: Int, val trackIndex: Int?, val localFilePath: String?, val isDownloaded: Boolean? = true)

object DownloadedBookProof {
    fun count(rows: List<DownloadedTrackProof>, fileReady: (String) -> Boolean): Long =
        rows.groupBy { it.sourceId }.values.filter { tracks ->
            val source = tracks.first()
            source.sourceType != "local" && source.chapterCount > 0 &&
                (0 until source.chapterCount).all { index ->
                    tracks.any { track -> track.trackIndex == index && track.isDownloaded == true &&
                        track.localFilePath?.takeIf(String::isNotBlank)?.let(fileReady) == true }
                }
        }.map { it.first().bookId }.distinct().size.toLong()
}
