package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.source.SourceBookDetail

/**
 * Spec-32 T2 (#232) — the pure mapping from a resolved source book page
 * ([SourceBookDetail], the one object every import door resolves) to the
 * shared [BookProfile] document. No I/O, no DAO — table-testable; the write
 * path is the only place this runs (the profile is what the page yielded,
 * nothing more).
 */
object BookProfileMapping {

    fun fromDetail(detail: SourceBookDetail): BookProfile = BookProfile(
        title = detail.title,
        author = detail.author,
        description = detail.description,
        narrator = detail.narrator,
        seriesTitle = detail.series?.name,
        seriesIndex = detail.series?.position,
        genres = detail.genres,
        rating = detail.rating,
        coverImageUrl = detail.coverImageUrl,
        chapters = detail.chapters.map { chapter ->
            ProfileChapter(
                title = chapter.title,
                streamUrl = chapter.streamUrl,
                durationSeconds = chapter.durationSeconds
            )
        },
        totalDurationSeconds = detail.totalDurationSeconds
    )
}
