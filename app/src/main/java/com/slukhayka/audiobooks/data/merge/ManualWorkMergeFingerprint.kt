package com.slukhayka.audiobooks.data.merge

import java.security.MessageDigest

/** Fixed v1 Room54 framing of the typed dependency closure, excluding retained globals. */
internal object ManualWorkMergeFingerprint {
    fun of(snapshot: ManualWorkMergeSnapshot): String {
        val out = Frames()
        out.ascii("ManualWorkMergeSnapshot\u0000v1\u0000Room54\u0000")
        out.ascii("P${snapshot.rootWorkIds.size};")
        snapshot.rootWorkIds.sortedWith(::compareMergeUtf8).forEach(out::string)
        out.ascii("G"); out.string("achievement_counters"); out.ascii("excluded;")
        out.ascii("G"); out.string("achievement_facts"); out.ascii("excluded;")
        out.ascii("G"); out.string("achievements"); out.ascii("excluded;")
        out.ascii("T"); out.string("audiobooks")
        val books = snapshot.library.books.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${books.size};")
        books.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.title)
            out.string(row.author)
            out.string(row.narrator)
            out.string(row.description)
            out.int(row.coverDrawableRes)
            row.coverImageUrl?.let(out::string) ?: out.nil()
            out.string(row.genre)
            out.string(row.sourceUrl)
            out.boolean(row.isDownloaded)
            out.long(row.totalDurationSeconds)
            out.int(row.totalChapters)
            out.real(row.rating)
            row.sourceTreeUri?.let(out::string) ?: out.nil()
        }
        out.ascii("T"); out.string("author_aliases")
        val authorAliases = snapshot.catalog.authorAliases.sortedWith { a, b ->
            var order = compareMergeUtf8(a.authorId, b.authorId)
            if (order == 0) order = compareMergeUtf8(a.normalizedAlias, b.normalizedAlias)
            if (order == 0) order = compareMergeUtf8(a.sourceId, b.sourceId)
            order
        }
        out.ascii("C${authorAliases.size};")
        authorAliases.forEach { row ->
            out.ascii("R")
            out.string(row.authorId)
            out.string(row.normalizedAlias)
            out.string(row.rawAlias)
            out.string(row.sourceId)
            out.long(row.observedAt)
        }
        out.ascii("T"); out.string("author_facets")
        val authors = snapshot.catalog.authors.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${authors.size};")
        authors.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.displayName)
            out.string(row.normalizedName)
            out.long(row.updatedAt)
        }
        out.ascii("T"); out.string("bookmarks")
        val bookmarks = snapshot.library.bookmarks.sortedWith { a, b ->
            var order = a.id.compareTo(b.id)
            order
        }
        out.ascii("C${bookmarks.size};")
        bookmarks.forEach { row ->
            out.ascii("R")
            out.long(row.id)
            out.string(row.bookId)
            row.editionId?.let(out::string) ?: out.nil()
            out.int(row.chapterIndex)
            out.string(row.chapterTitle)
            out.long(row.timestampSeconds)
            out.string(row.note)
            out.long(row.createdAt)
        }
        out.ascii("T"); out.string("chapters")
        val chapters = snapshot.library.chapters.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${chapters.size};")
        chapters.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.bookId)
            out.int(row.chapterIndex)
            out.string(row.title)
            out.long(row.durationSeconds)
            row.editionId?.let(out::string) ?: out.nil()
        }
        out.ascii("T"); out.string("corrections")
        val corrections = snapshot.personal.corrections.sortedWith { a, b ->
            var order = compareMergeUtf8(a.mergeKey, b.mergeKey)
            if (order == 0) order = compareMergeUtf8(a.kind, b.kind)
            if (order == 0) order = compareMergeUtf8(a.value, b.value)
            order
        }
        out.ascii("C${corrections.size};")
        corrections.forEach { row ->
            out.ascii("R")
            out.string(row.mergeKey)
            out.string(row.kind)
            out.string(row.value)
            out.string(row.origin)
            out.long(row.updatedAt)
            out.string(row.updatedBy)
        }
        out.ascii("T"); out.string("edition_facets")
        val editionFacets = snapshot.catalog.editionFacets.sortedWith { a, b ->
            var order = compareMergeUtf8(a.editionId, b.editionId)
            order
        }
        out.ascii("C${editionFacets.size};")
        editionFacets.forEach { row ->
            out.ascii("R")
            out.string(row.editionId)
            out.string(row.workId)
            row.narratorId?.let(out::string) ?: out.nil()
            row.language?.let(out::string) ?: out.nil()
            row.durationSeconds?.let(out::long) ?: out.nil()
            row.durationBucketId?.let(out::string) ?: out.nil()
            row.chapterCount?.let(out::int) ?: out.nil()
            row.isAbridged?.let(out::boolean) ?: out.nil()
            row.availabilityAvailable?.let(out::boolean) ?: out.nil()
            row.availabilityObservedAtMillis?.let(out::long) ?: out.nil()
            row.availabilityTtlSeconds?.let(out::long) ?: out.nil()
            out.long(row.updatedAt)
        }
        out.ascii("T"); out.string("edition_settings")
        val settings = snapshot.library.settings.sortedWith { a, b ->
            var order = compareMergeUtf8(a.bookId, b.bookId)
            if (order == 0) order = compareMergeUtf8(a.sourceKey, b.sourceKey)
            order
        }
        out.ascii("C${settings.size};")
        settings.forEach { row ->
            out.ascii("R")
            out.string(row.bookId)
            out.string(row.sourceKey)
            row.rewindSeconds?.let(out::int) ?: out.nil()
            row.sleepTimerDefaultSeconds?.let(out::int) ?: out.nil()
            out.boolean(row.volumeBoostEnabled)
            out.boolean(row.silenceSkipEnabled)
            out.long(row.updatedAt)
        }
        out.ascii("T"); out.string("editions")
        val editions = snapshot.library.editions.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${editions.size};")
        editions.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.workId)
            out.string(row.language)
            out.string(row.narrator)
            out.int(row.totalChapters)
            out.long(row.totalDurationSeconds)
            out.long(row.addedAt)
        }
        out.ascii("T"); out.string("embedding_vectors")
        val vectors = snapshot.catalog.vectors.sortedWith { a, b ->
            var order = compareMergeUtf8(a.workId, b.workId)
            order
        }
        out.ascii("C${vectors.size};")
        vectors.forEach { row ->
            out.ascii("R")
            out.string(row.workId)
            out.string(row.textHash)
            out.blob(row.vector.copyBytes())
            out.long(row.updatedAt)
        }
        out.ascii("T"); out.string("feed_snapshots")
        val feedSnapshots = snapshot.personal.feedSnapshots.sortedWith { a, b ->
            var order = compareMergeUtf8(a.sourceId, b.sourceId)
            if (order == 0) order = compareMergeUtf8(a.feedKey, b.feedKey)
            if (order == 0) order = compareMergeUtf8(a.pageCursor, b.pageCursor)
            order
        }
        out.ascii("C${feedSnapshots.size};")
        feedSnapshots.forEach { row ->
            out.ascii("R")
            out.string(row.sourceId)
            out.string(row.feedKey)
            out.string(row.pageCursor)
            out.long(row.fetchedAt)
            out.string(row.cardsJson)
        }
        out.ascii("G"); out.string("friendship_states"); out.ascii("excluded;")
        out.ascii("T"); out.string("genre_assertion_states")
        val genreStates = snapshot.catalog.genreStates.sortedWith { a, b ->
            var order = compareMergeUtf8(a.workId, b.workId)
            if (order == 0) order = compareMergeUtf8(a.sourceId, b.sourceId)
            order
        }
        out.ascii("C${genreStates.size};")
        genreStates.forEach { row ->
            out.ascii("R")
            out.string(row.workId)
            out.string(row.sourceId)
            out.long(row.documentUpdatedAt)
            out.string(row.provenance)
        }
        out.ascii("T"); out.string("genre_assertions")
        val genreAssertions = snapshot.catalog.genreAssertions.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${genreAssertions.size};")
        genreAssertions.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.assertionId)
            out.string(row.workId)
            out.string(row.genreId)
            out.string(row.rawText)
            out.string(row.sourceId)
            out.long(row.observedAt)
        }
        out.ascii("T"); out.string("genre_facets")
        val genres = snapshot.catalog.genres.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${genres.size};")
        genres.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.displayName)
            out.string(row.normalizedName)
        }
        out.ascii("G"); out.string("hidden_reviewers"); out.ascii("excluded;")
        out.ascii("T"); out.string("library_entries")
        val entries = snapshot.library.entries.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${entries.size};")
        entries.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.workId)
            out.string(row.origin)
            out.boolean(row.isFavorite)
            out.long(row.createdAt)
            out.real(row.downloadProgress)
            out.string(row.downloadState)
        }
        out.ascii("T"); out.string("listener_collection_items")
        val collectionItems = snapshot.personal.collectionItems.sortedWith { a, b ->
            var order = compareMergeUtf8(a.collectionId, b.collectionId)
            if (order == 0) order = compareMergeUtf8(a.bookId, b.bookId)
            order
        }
        out.ascii("C${collectionItems.size};")
        collectionItems.forEach { row ->
            out.ascii("R")
            out.string(row.collectionId)
            out.string(row.bookId)
            out.string(row.reason)
            out.long(row.addedAt)
        }
        out.ascii("T"); out.string("listener_collections")
        val collections = snapshot.personal.collections.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${collections.size};")
        collections.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.title)
            out.string(row.description)
            out.long(row.createdAt)
            row.sourceDocumentId?.let(out::string) ?: out.nil()
            row.sourceTitle?.let(out::string) ?: out.nil()
            row.sourcePseudonym?.let(out::string) ?: out.nil()
            row.snapshotAt?.let(out::long) ?: out.nil()
        }
        out.ascii("G"); out.string("listening_stats"); out.ascii("excluded;")
        out.ascii("G"); out.string("person_bookmarks"); out.ascii("excluded;")
        out.ascii("T"); out.string("playback_events")
        val events = snapshot.library.events.sortedWith { a, b ->
            var order = a.id.compareTo(b.id)
            order
        }
        out.ascii("C${events.size};")
        events.forEach { row ->
            out.ascii("R")
            out.long(row.id)
            out.string(row.bookId)
            out.string(row.sourceKey)
            out.string(row.kind)
            out.int(row.chapterIndex)
            out.long(row.positionSeconds)
            row.fromPositionSeconds?.let(out::long) ?: out.nil()
            out.long(row.timestamp)
            out.string(row.deviceId)
        }
        out.ascii("T"); out.string("playback_failures")
        val failures = snapshot.library.failures.sortedWith { a, b ->
            var order = a.id.compareTo(b.id)
            order
        }
        out.ascii("C${failures.size};")
        failures.forEach { row ->
            out.ascii("R")
            out.long(row.id)
            out.long(row.timestamp)
            out.string(row.bookId)
            out.int(row.chapterIndex)
            out.string(row.errorCodeName)
            out.string(row.streamUrl)
            out.string(row.audioEngineMode)
            row.category?.let(out::string) ?: out.nil()
        }
        out.ascii("T"); out.string("playback_progress")
        val progress = snapshot.library.progress.sortedWith { a, b ->
            var order = compareMergeUtf8(a.editionId, b.editionId)
            order
        }
        out.ascii("C${progress.size};")
        progress.forEach { row ->
            out.ascii("R")
            out.string(row.editionId)
            out.string(row.bookId)
            out.int(row.currentChapterIndex)
            out.long(row.currentPositionSeconds)
            out.long(row.lastListenedAt)
            out.boolean(row.isCompleted)
            row.lastPausedAtEpochMs?.let(out::long) ?: out.nil()
            row.preferredSpeed?.let(out::real) ?: out.nil()
        }
        out.ascii("G"); out.string("playback_sessions"); out.ascii("excluded;")
        out.ascii("T"); out.string("popularity_assertions")
        val popularity = snapshot.catalog.popularity.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${popularity.size};")
        popularity.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.kind)
            out.string(row.mergeKey)
            out.string(row.rawValue)
            out.string(row.sourceId)
            out.long(row.observedAt)
        }
        out.ascii("T"); out.string("readthroughs")
        val readthroughs = snapshot.library.readthroughs.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${readthroughs.size};")
        readthroughs.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.libraryEntryId)
            out.string(row.workId)
            out.string(row.format)
            out.string(row.state)
            out.long(row.startedAt)
            row.finishedAt?.let(out::long) ?: out.nil()
            row.editionId?.let(out::string) ?: out.nil()
            out.string(row.unit)
            out.int(row.unitValue)
            out.string(row.journalJson)
        }
        out.ascii("T"); out.string("recommendation_preferences")
        val preferences = snapshot.personal.preferences.sortedWith { a, b ->
            var order = compareMergeUtf8(a.kind, b.kind)
            if (order == 0) order = compareMergeUtf8(a.targetKey, b.targetKey)
            order
        }
        out.ascii("C${preferences.size};")
        preferences.forEach { row ->
            out.ascii("R")
            out.string(row.kind)
            out.string(row.targetKey)
            out.string(row.sourceWorkId)
            out.long(row.createdAt)
        }
        out.ascii("T"); out.string("series")
        val series = snapshot.catalog.series.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${series.size};")
        series.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.title)
            row.url?.let(out::string) ?: out.nil()
            row.universeId?.let(out::string) ?: out.nil()
            row.positionInUniverse?.let(out::int) ?: out.nil()
            row.publicationYear?.let(out::int) ?: out.nil()
        }
        out.ascii("T"); out.string("series_members")
        val seriesMembers = snapshot.catalog.seriesMembers.sortedWith { a, b ->
            var order = compareMergeUtf8(a.workId, b.workId)
            if (order == 0) order = compareMergeUtf8(a.seriesId, b.seriesId)
            order
        }
        out.ascii("C${seriesMembers.size};")
        seriesMembers.forEach { row ->
            out.ascii("R")
            out.string(row.workId)
            out.string(row.seriesId)
            out.int(row.position)
            row.resolvedAt?.let(out::long) ?: out.nil()
        }
        out.ascii("G"); out.string("social_blocks"); out.ascii("excluded;")
        out.ascii("T"); out.string("source_tracks")
        val tracks = snapshot.library.tracks.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${tracks.size};")
        tracks.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.sourceId)
            out.int(row.trackIndex)
            out.string(row.url)
            row.localFilePath?.let(out::string) ?: out.nil()
            row.contentHash?.let(out::string) ?: out.nil()
            out.boolean(row.isDownloaded)
        }
        out.ascii("T"); out.string("sources")
        val sources = snapshot.library.sources.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${sources.size};")
        sources.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.bookId)
            row.editionId?.let(out::string) ?: out.nil()
            out.string(row.type)
            out.string(row.url)
            out.boolean(row.streamOnly)
            out.long(row.addedAt)
            row.lastScanFingerprint?.let(out::string) ?: out.nil()
        }
        out.ascii("T"); out.string("submission_states")
        val submissions = snapshot.library.submissions.sortedWith { a, b ->
            var order = compareMergeUtf8(a.sourceId, b.sourceId)
            order
        }
        out.ascii("C${submissions.size};")
        submissions.forEach { row ->
            out.ascii("R")
            out.string(row.sourceId)
            out.string(row.url)
            out.string(row.bookId)
            out.string(row.metadataJson)
            out.string(row.channelId)
            out.string(row.state)
            row.reason?.let(out::string) ?: out.nil()
            out.long(row.createdAt)
            out.long(row.updatedAt)
        }
        out.ascii("T"); out.string("tombstones")
        val tombstones = snapshot.personal.tombstones.sortedWith { a, b ->
            var order = compareMergeUtf8(a.bookId, b.bookId)
            order
        }
        out.ascii("C${tombstones.size};")
        tombstones.forEach { row ->
            out.ascii("R")
            out.string(row.bookId)
            out.long(row.deletedAt)
        }
        out.ascii("T"); out.string("universes")
        val universes = snapshot.catalog.universes.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${universes.size};")
        universes.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.name)
        }
        out.ascii("T"); out.string("work_facet_series")
        val facetSeries = snapshot.catalog.facetSeries.sortedWith { a, b ->
            var order = compareMergeUtf8(a.workId, b.workId)
            if (order == 0) order = compareMergeUtf8(a.seriesId, b.seriesId)
            order
        }
        out.ascii("C${facetSeries.size};")
        facetSeries.forEach { row ->
            out.ascii("R")
            out.string(row.workId)
            out.string(row.seriesId)
        }
        out.ascii("T"); out.string("work_facets")
        val workFacets = snapshot.catalog.workFacets.sortedWith { a, b ->
            var order = compareMergeUtf8(a.workId, b.workId)
            order
        }
        out.ascii("C${workFacets.size};")
        workFacets.forEach { row ->
            out.ascii("R")
            out.string(row.workId)
            row.canonicalAuthorId?.let(out::string) ?: out.nil()
            out.long(row.updatedAt)
        }
        out.ascii("T"); out.string("work_genres")
        val workGenres = snapshot.catalog.workGenres.sortedWith { a, b ->
            var order = compareMergeUtf8(a.workId, b.workId)
            if (order == 0) order = compareMergeUtf8(a.genreId, b.genreId)
            if (order == 0) order = compareMergeUtf8(a.sourceId, b.sourceId)
            order
        }
        out.ascii("C${workGenres.size};")
        workGenres.forEach { row ->
            out.ascii("R")
            out.string(row.workId)
            out.string(row.genreId)
            out.string(row.sourceId)
        }
        out.ascii("T"); out.string("work_sources")
        val workSources = snapshot.catalog.workSources.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${workSources.size};")
        workSources.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.workId)
            out.string(row.sourceId)
            out.string(row.sourceUrl)
            out.boolean(row.streamOnly)
            row.coverImageUrl?.let(out::string) ?: out.nil()
            row.durationSeconds?.let(out::long) ?: out.nil()
            out.long(row.addedAt)
        }
        out.ascii("T"); out.string("works")
        val works = snapshot.library.works.sortedWith { a, b ->
            var order = compareMergeUtf8(a.id, b.id)
            order
        }
        out.ascii("C${works.size};")
        works.forEach { row ->
            out.ascii("R")
            out.string(row.id)
            out.string(row.mergeKey)
            out.string(row.title)
            out.string(row.author)
            row.seriesTitle?.let(out::string) ?: out.nil()
            row.seriesUrl?.let(out::string) ?: out.nil()
            row.seriesIndex?.let(out::int) ?: out.nil()
            row.coverImageUrl?.let(out::string) ?: out.nil()
            out.long(row.addedAt)
        }
        out.ascii("T"); out.string("works_fts")
        val ftsRows = snapshot.catalog.ftsRows.sortedWith { a, b ->
            var order = compareMergeUtf8(a.workId, b.workId)
            if (order == 0) order = compareMergeUtf8(a.title, b.title)
            if (order == 0) order = compareMergeUtf8(a.author, b.author)
            if (order == 0) order = compareMergeUtf8(a.series, b.series)
            if (order == 0) order = compareMergeUtf8(a.narrator, b.narrator)
            order
        }
        out.ascii("C${ftsRows.size};")
        ftsRows.forEach { row ->
            out.ascii("R")
            out.string(row.workId)
            out.string(row.title)
            out.string(row.author)
            out.string(row.series)
            out.string(row.narrator)
        }
        return "manual-work-merge-v1:" + out.finish()
    }

    private class Frames {
        private val hash = MessageDigest.getInstance("SHA-256")
        fun ascii(value: String) { hash.update(value.toByteArray(Charsets.US_ASCII)) }
        fun nil() = ascii("N;")
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            ascii("S${bytes.size}:"); hash.update(bytes); ascii(";")
        }
        fun int(value: Int) = ascii("I$value;")
        fun long(value: Long) = ascii("L$value;")
        fun boolean(value: Boolean) = ascii(if (value) "B1;" else "B0;")
        fun real(value: Float) = ascii("D" + java.lang.Long.toHexString(value.toDouble().toRawBits()).padStart(16, '0') + ";")
        fun blob(bytes: ByteArray) = ascii("X${bytes.size}:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ";")
        fun finish(): String = hex(hash.digest())
        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            val digits = "0123456789abcdef"
            bytes.forEach { byte -> val n = byte.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) }
        }
    }
}

/** SQL NOCASE and UTF16 ordering are not the canonical v1 byte ordering. */
internal fun compareMergeUtf8(first: String, second: String): Int {
    val a = first.toByteArray(Charsets.UTF_8)
    val b = second.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(a.size, b.size)) {
        val order = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255)
        if (order != 0) return order
    }
    return a.size.compareTo(b.size)
}
