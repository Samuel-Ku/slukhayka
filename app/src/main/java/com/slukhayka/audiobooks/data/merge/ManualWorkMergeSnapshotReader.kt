package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.sha256Hex
import com.slukhayka.audiobooks.data.db.*
import okio.ByteString.Companion.decodeBase64

/** Called only inside AudiobookDao's concrete @Transaction read door. */
internal object ManualWorkMergeSnapshotReader {
    suspend fun read(dao: AudiobookDao, requested: List<String>): ManualWorkMergeSnapshot {
        require(requested.isNotEmpty() && requested.all { it.isNotBlank() }) { "Work identities are required" }
        val roots = requested.distinct().sortedWith(::compareMergeUtf8)
        val rootWorks = dao.manualMergeWorks(roots)
        val rootEntries = dao.manualMergeEntries(roots)
        // Root strings are candidate legacy book anchors too, not confirmed library associations.
        val bookIds = (rootEntries.map { it.id } + roots).toCollection(linkedSetOf())
        val editionIds = linkedSetOf<String>()
        val submissionSourceIds = linkedSetOf<String>()
        val anchors = (roots + bookIds).distinct()
        var editions = dao.manualMergeEditions(anchors, emptyList())
        editionIds.addAll(editions.map { it.id })
        var sources: List<SourceEntity>
        var chapters: List<ChapterEntity>
        var progress: List<PlaybackProgressEntity>
        var bookmarks: List<BookmarkEntity>
        var readthroughs: List<ReadthroughEntity>
        var editionFacets: List<EditionFacetEntity>
        var entries: List<LibraryEntryEntity>
        var submissions: List<SubmissionStateEntity>
        // Follow both legacy book and Edition edges, retaining contradictory raw owners too.
        // The finite persisted identity sets grow monotonically; no display LIMIT truncates them.
        do {
            val before = Triple(bookIds.size, editionIds.size, submissionSourceIds.size)
            editions = dao.manualMergeEditions((roots + bookIds).distinct(), editionIds.toList())
            editionIds.addAll(editions.map { it.id })
            editionFacets = dao.manualMergeEditionFacets(roots, editionIds.toList())
            editionIds.addAll(editionFacets.map { it.editionId })
            sources = (dao.manualMergeSources(bookIds.toList(), editionIds.toList()) +
                submissionSourceIds.mapNotNull { dao.getSourceById(it) }).distinctBy { it.id }
            submissions = dao.manualMergeSubmissions(bookIds.toList(), sources.map { it.id })
            submissionSourceIds.addAll(submissions.map { it.sourceId })
            chapters = dao.manualMergeChapters(bookIds.toList(), editionIds.toList())
            progress = dao.manualMergeProgress(bookIds.toList(), editionIds.toList())
            bookmarks = dao.manualMergeBookmarks(bookIds.toList(), editionIds.toList())
            readthroughs = dao.manualMergeReadthroughs(roots, bookIds.toList(), editionIds.toList())
            val inverseBookIds = sources.map { it.bookId } + chapters.map { it.bookId } +
                progress.map { it.bookId } + bookmarks.map { it.bookId } + readthroughs.map { it.libraryEntryId } +
                submissions.map { it.bookId }
            bookIds.addAll(inverseBookIds.filter { it.isNotBlank() })
            val entryCandidates = (bookIds + editions.map { it.workId }).toList()
            entries = (rootEntries + dao.manualMergeEntriesByIds(entryCandidates)).distinctBy { it.id }
            bookIds.addAll(entries.map { it.id })
            editionIds.addAll((sources.mapNotNull { it.editionId } + chapters.mapNotNull { it.editionId } +
                progress.map { it.editionId } + bookmarks.mapNotNull { it.editionId } +
                readthroughs.mapNotNull { it.editionId }).filter { it.isNotBlank() })
        } while (before != Triple(bookIds.size, editionIds.size, submissionSourceIds.size))
        val books = dao.manualMergeBooks(bookIds.toList())
        var works = (rootWorks + dao.manualMergeWorks(
            (editions.map { it.workId } + entries.map { it.workId } + readthroughs.map { it.workId } +
                editionFacets.map { it.workId }).distinct()
        ) + dao.manualMergeWorksForKeys(rootWorks.map { it.mergeKey }.filter { it.isNotBlank() }))
            .distinctBy { it.id }
        val sourceIds = sources.map { it.id }
        val tracks = dao.manualMergeTracks(sourceIds)
        val settings = dao.manualMergeSettings(bookIds.toList())
        val events = dao.manualMergeEvents(bookIds.toList())
        val failures = dao.manualMergeFailures(bookIds.toList())
        val workSources = dao.manualMergeWorkSources(roots)
        val members = dao.manualMergeSeriesMembers(roots)
        val facetSeries = dao.manualMergeFacetSeries(roots)
        val seriesIds = (members.map { it.seriesId } + facetSeries.map { it.seriesId }).distinct()
        val series = dao.manualMergeSeries(seriesIds)
        val universes = dao.manualMergeUniverses(series.mapNotNull { it.universeId }.distinct())
        val workFacets = dao.manualMergeWorkFacets(roots)
        val genreStates = dao.manualMergeGenreStates(roots)
        val assertions = dao.manualMergeGenreAssertions(roots)
        val workGenres = dao.manualMergeWorkGenres(roots)
        val genres = dao.manualMergeGenres((assertions.map { it.genreId } + workGenres.map { it.genreId }).distinct())
        val authorIds = workFacets.mapNotNull { it.canonicalAuthorId }.distinct()
        val authors = dao.manualMergeAuthors(authorIds)
        val aliases = dao.manualMergeAuthorAliases(authorIds)
        val fts = dao.manualMergeFtsRows(roots)
        val workKeys = (roots + rootWorks.map { it.mergeKey }.filter { it.isNotBlank() }).distinct()
        val popularity = dao.manualMergePopularity(workKeys)
        val vectors = dao.embeddingVectors(roots).map {
            ManualWorkMergeVectorRow(it.workId, it.textHash, ManualWorkMergeBytes(it.vector), it.updatedAt)
        }
        val issues = mutableListOf<ManualWorkMergeConflict>()
        val correctionKeys = (workKeys + bookIds + bookIds.map { "chapter-order:$it" } +
            books.mapNotNull { it.sourceTreeUri }.map { "local-folder:${sha256Hex(it.toByteArray(Charsets.UTF_8))}" }).distinct()
        val corrections = dao.manualMergeCorrections(correctionKeys).filter { row ->
            correctionInScope(row, correctionKeys.toSet(), bookIds, issues)
        }
        // A scoped forbidden-pair rule can name an existing third Work or a real Entry alias.
        // Materialize those identity witnesses without adding requested participants or treating
        // MERGE arrows / SPLIT display titles as a new alias registry.
        val pairEndpointIds = corrections.filter { it.kind == CorrectionKind.NEVER_MATCH }
            .flatMap { listOf(it.mergeKey, it.value) }.filter { it.isNotBlank() }.distinct()
        val endpointEntries = dao.manualMergeEntriesByIds(pairEndpointIds)
        // Retain each real Entry endpoint's legacy Book as identity evidence only.
        val endpointBooks = dao.manualMergeBooks(endpointEntries.map { it.id })
        entries = (entries + endpointEntries).distinctBy { it.id }
        works = (works + dao.manualMergeWorks(pairEndpointIds + endpointEntries.map { it.workId }) +
            dao.manualMergeWorksForKeys(pairEndpointIds)).distinctBy { it.id }
        val tombstones = dao.manualMergeTombstones((roots + bookIds).distinct())
        val preferences = dao.manualMergePreferences(roots, workKeys)
        works = (works + dao.manualMergeWorks(preferences.map { it.sourceWorkId }.distinct())).distinctBy { it.id }
        preferences.filter { it.kind !in setOf(RecommendationPreferenceEntity.HIDE_WORK,
            RecommendationPreferenceEntity.REDUCE_SIMILAR, RecommendationPreferenceEntity.HIDE_AUTHOR) }
            .forEach { issues += ManualWorkMergeConflict.OpaqueDocument("recommendation_preferences", it.kind + ":" + it.targetKey) }
        val items = dao.manualMergeCollectionItems(bookIds.toList())
        val collections = dao.manualMergeCollections(items.map { it.collectionId }.distinct())
        val knownProviders = workSources.map { it.sourceId }.toSet()
        val knownUrls = (workSources.map { it.sourceUrl } + sources.map { it.url } + books.map { it.sourceUrl })
            .filter { it.isNotBlank() }.toSet()
        val identityIds = (workKeys + bookIds + editionIds + sourceIds).toSet()
        val feedSnapshots = dao.manualMergeFeedSnapshots().filter { row ->
            feedInScope(row, identityIds, knownUrls, knownProviders, issues)
        }
        val library = ManualWorkMergeLibraryRows(
            works, entries, (books + endpointBooks).distinctBy { it.id }, editions, sources, tracks, chapters, progress, bookmarks,
            settings, readthroughs, events, failures, submissions,
        )
        val catalog = ManualWorkMergeCatalogRows(
            workSources, series, members, universes, workFacets, facetSeries, genres, genreStates,
            assertions, workGenres, editionFacets, authors, aliases, fts, popularity, vectors,
        )
        val personal = ManualWorkMergePersonalRows(corrections, tombstones, preferences, collections, items, feedSnapshots)
        val retained = ManualWorkMergeRetainedRows(
            dao.manualMergeStats(), dao.manualMergeSessions(), dao.manualMergeAchievements(),
            dao.manualMergeAchievementFacts(), dao.manualMergeAchievementCounters(), dao.manualMergePersonBookmarks(),
            dao.manualMergeHiddenReviewers(), dao.manualMergeFriendships(), dao.manualMergeSocialBlocks(),
        )
        validate(roots, library, catalog, personal, issues)
        return ManualWorkMergeSnapshot(roots, library, catalog, personal, retained, issues.distinct())
    }

    private fun correctionInScope(
        row: CorrectionEntity, keys: Set<String>, bookIds: Set<String>, issues: MutableList<ManualWorkMergeConflict>,
    ): Boolean {
        val direct = row.mergeKey in keys || row.value in keys
        if (row.kind == CorrectionKind.NEVER_MATCH) return direct
        if (row.kind == CorrectionKind.MERGE) {
            val anchors = row.value.split("->")
            if (direct && (anchors.size != 2 || anchors.any { it.isBlank() }))
                issues += ManualWorkMergeConflict.OpaqueDocument("corrections", correctionKey(row))
            if (direct || (anchors.size == 2 && anchors.any { it in bookIds || it in keys })) return true
        }
        if (direct && !row.mergeKey.startsWith("local-folder:")) {
            if (row.kind !in setOf(CorrectionKind.MERGE, CorrectionKind.SPLIT, CorrectionKind.NEVER_MATCH,
                    CorrectionKind.FIELD, CorrectionKind.WRONG_UNIVERSE))
                issues += ManualWorkMergeConflict.OpaqueDocument("corrections", correctionKey(row))
            return true
        }
        if (row.mergeKey.startsWith("local-folder:")) {
            // V2 lineage begins with a base64url book identity. Other fields remain opaque.
            val fields = row.value.split('|')
            val owner = if (fields.size == 5 && fields[0] == "2") decodeOwnerToken(fields[1]) else null
            if (owner == null) {
                // The unclassifiable lineage may hide a participant reference; retain its evidence.
                issues += ManualWorkMergeConflict.OpaqueDocument("corrections", correctionKey(row))
                return true
            }
            if (owner in bookIds || direct) {
                if (fields[2] !in setOf("ONE_BOOK", "SEPARATE_BOOKS"))
                    issues += ManualWorkMergeConflict.OpaqueDocument("corrections", correctionKey(row))
                return true
            }
        }
        return false
    }

    private fun decodeOwnerToken(raw: String): String? {
        if (!raw.matches(Regex("[A-Za-z0-9_-]*={0,2}"))) return null
        return try { raw.decodeBase64()?.utf8()?.takeIf { it.isNotBlank() } }
        catch (_: IllegalArgumentException) { null }
    }

    private fun feedInScope(
        row: FeedSnapshotEntity, ids: Set<String>, urls: Set<String>, providers: Set<String>,
        issues: MutableList<ManualWorkMergeConflict>,
    ): Boolean {
        val parsed = MiniJson.parseLenient(row.cardsJson)
        if (parsed == null) {
            if (row.sourceId !in providers) return false
            issues += ManualWorkMergeConflict.OpaqueDocument("feed_snapshots", feedKey(row))
            return true
        }
        // Read structured card identities and the writer's existing title/author key,
        // without requiring all display fields or normalizing the stored document.
        fun inspectCard(value: Any?): FeedReferences {
            val card = value as? Map<*, *> ?: return FeedReferences(false, false)
            val identityFields = setOf("id", "workId", "bookId", "editionId", "sourceId")
            val urlFields = setOf("url", "sourceUrl")
            val known = card.keys.any { it in identityFields || it in urlFields || it == "title" }
            val matched = card.any { (key, child) ->
                (key in identityFields && child is String && child in ids) ||
                    (key in urlFields && child is String && child in urls)
            } || ((card["title"] as? String)?.let { title ->
                (card["author"] as? String)?.let { MergeKey.keyFor(title, it) in ids }
            } == true)
            val invalid = card.any { (key, child) -> (key in identityFields || key in urlFields) && child !is String }
            return FeedReferences(known && !invalid, matched)
        }
        fun inspectDocument(value: Any?): FeedReferences = when (value) {
            is List<*> -> {
                val cards = value.map(::inspectCard)
                FeedReferences(cards.all { it.known }, cards.any { it.matched })
            }
            is Map<*, *> -> {
                val books = value["books"] ?: value["cards"]
                val sections = value["sections"] as? List<*>
                when {
                    books is List<*> -> inspectDocument(books)
                    sections != null -> {
                        val frames = sections.map(::inspectDocument)
                        FeedReferences(frames.all { it.known }, frames.any { it.matched })
                    }
                    else -> FeedReferences(false, false)
                }
            }
            else -> FeedReferences(false, false)
        }
        val references = inspectDocument(parsed)
        if (!references.known && (references.matched || row.sourceId in providers)) {
            issues += ManualWorkMergeConflict.OpaqueDocument("feed_snapshots", feedKey(row))
            return true
        }
        return references.matched
    }

    private data class FeedReferences(val known: Boolean, val matched: Boolean)

    private fun validate(
        roots: List<String>, l: ManualWorkMergeLibraryRows, c: ManualWorkMergeCatalogRows,
        p: ManualWorkMergePersonalRows, issues: MutableList<ManualWorkMergeConflict>,
    ) {
        val works = l.works.associateBy { it.id }
        val entries = l.entries.associateBy { it.id }
        val editions = l.editions.associateBy { it.id }
        fun missing(table: String, key: String, reference: String, id: String) {
            issues += ManualWorkMergeConflict.UnresolvedReference(table, key, reference, id)
        }
        roots.filter { it !in works }.forEach { missing("works", it, "Work", it) }
        l.entries.forEach { if (it.workId !in works) missing("library_entries", it.id, "Work", it.workId) }
        l.books.forEach { if (it.id !in entries) missing("audiobooks", it.id, "LibraryEntry", it.id) }
        val editionOwners = l.editions.associate { edition ->
            val owners = buildSet {
                if (edition.workId in works) add(edition.workId)
                entries[edition.workId]?.workId?.let(::add)
            }
            if (owners.isEmpty()) missing("editions", edition.id, "WorkOrLibraryEntry", edition.workId)
            ambiguous("editions", edition.id, owners, issues)
            if (edition.workId in works && edition.workId in entries)
                issues += ManualWorkMergeConflict.AmbiguousNamespace(edition.id, edition.workId)
            val associations = buildSet {
                if (edition.workId in entries) add(edition.workId)
                l.sources.filter { it.editionId == edition.id }.forEach { if (it.bookId in entries) add(it.bookId) }
                l.chapters.filter { it.editionId == edition.id }.forEach { if (it.bookId in entries) add(it.bookId) }
                l.progress.filter { it.editionId == edition.id }.forEach { if (it.bookId in entries) add(it.bookId) }
                l.bookmarks.filter { it.editionId == edition.id }.forEach { if (it.bookId in entries) add(it.bookId) }
                l.readthroughs.filter { it.editionId == edition.id }.forEach { if (it.libraryEntryId in entries) add(it.libraryEntryId) }
            }.sortedWith(::compareMergeUtf8)
            for (i in associations.indices) for (j in i + 1 until associations.size)
                issues += ManualWorkMergeConflict.MultipleLibraryEntries(edition.id, associations[i], associations[j])
            edition.id to owners
        }
        fun checkAnchors(table: String, key: String, bookId: String, editionId: String?, workId: String? = null) {
            val entry = entries[bookId]
            if (entry == null) missing(table, key, "LibraryEntry", bookId)
            if (editionId != null && editionId !in editions) missing(table, key, "Edition", editionId)
            if (workId != null && workId !in works) missing(table, key, "Work", workId)
            val owners = buildSet {
                entry?.workId?.let(::add)
                editionOwners[editionId]?.let(::addAll)
                workId?.let(::add)
            }
            ambiguous(table, key, owners, issues)
        }
        l.sources.forEach { checkAnchors("sources", it.id, it.bookId, it.editionId) }
        l.chapters.forEach { checkAnchors("chapters", it.id, it.bookId, it.editionId) }
        l.progress.forEach { checkAnchors("playback_progress", it.editionId, it.bookId, it.editionId) }
        l.bookmarks.forEach { checkAnchors("bookmarks", it.id.toString(), it.bookId, it.editionId) }
        l.readthroughs.forEach { checkAnchors("readthroughs", it.id, it.libraryEntryId, it.editionId, it.workId) }
        l.settings.forEach { if (it.bookId !in entries) missing("edition_settings", it.bookId + ":" + it.sourceKey, "LibraryEntry", it.bookId) }
        l.events.forEach { if (it.bookId !in entries) missing("playback_events", it.id.toString(), "LibraryEntry", it.bookId) }
        l.failures.forEach { if (it.bookId !in entries) missing("playback_failures", it.id.toString(), "LibraryEntry", it.bookId) }
        val sourceIds = l.sources.map { it.id }.toSet()
        val sourceOwners = l.sources.associate { source ->
            source.id to buildSet {
                entries[source.bookId]?.workId?.let(::add)
                editionOwners[source.editionId]?.let(::addAll)
            }
        }
        l.submissions.forEach { submission ->
            if (submission.bookId !in entries)
                missing("submission_states", submission.sourceId, "LibraryEntry", submission.bookId)
            if (submission.sourceId !in sourceIds)
                missing("submission_states", submission.sourceId, "Source", submission.sourceId)
            val owners = buildSet {
                entries[submission.bookId]?.workId?.let(::add)
                sourceOwners[submission.sourceId]?.let(::addAll)
            }
            ambiguous("submission_states", submission.sourceId, owners, issues)
        }
        l.tracks.forEach { if (it.sourceId !in sourceIds) missing("source_tracks", it.id, "Source", it.sourceId) }
        val seriesIds = c.series.map { it.id }.toSet()
        val universeIds = c.universes.map { it.id }.toSet()
        c.series.forEach { row -> row.universeId?.let { if (it !in universeIds) missing("series", row.id, "Universe", it) } }
        c.seriesMembers.forEach { if (it.seriesId !in seriesIds) missing("series_members", it.workId + ":" + it.seriesId, "Series", it.seriesId) }
        c.facetSeries.forEach { if (it.seriesId !in seriesIds) missing("work_facet_series", it.workId + ":" + it.seriesId, "Series", it.seriesId) }
        val authorIds = c.authors.map { it.id }.toSet()
        c.workFacets.forEach { row -> row.canonicalAuthorId?.let { if (it !in authorIds) missing("work_facets", row.workId, "Author", it) } }
        val genreIds = c.genres.map { it.id }.toSet()
        c.genreAssertions.forEach { if (it.genreId !in genreIds) missing("genre_assertions", it.id, "Genre", it.genreId) }
        val genreDocuments = c.genreStates.map { it.workId to it.sourceId }.toSet()
        c.workGenres.forEach {
            if (it.genreId !in genreIds) missing("work_genres", it.workId + ":" + it.genreId + ":" + it.sourceId, "Genre", it.genreId)
            if ((it.workId to it.sourceId) !in genreDocuments)
                missing("work_genres", it.workId + ":" + it.genreId + ":" + it.sourceId, "GenreDocument", it.sourceId)
        }
        c.genreAssertions.forEach {
            if ((it.workId to it.sourceId) !in genreDocuments)
                missing("genre_assertions", it.id, "GenreDocument", it.sourceId)
        }
        c.editionFacets.forEach { facet ->
            if (facet.editionId !in editions) missing("edition_facets", facet.editionId, "Edition", facet.editionId)
            if (facet.workId !in works) missing("edition_facets", facet.editionId, "Work", facet.workId)
            ambiguous("edition_facets", facet.editionId, editionOwners[facet.editionId].orEmpty() + facet.workId, issues)
        }
        val collectionIds = p.collections.map { it.id }.toSet()
        p.collectionItems.forEach {
            if (it.collectionId !in collectionIds) missing("listener_collection_items", it.collectionId + ":" + it.bookId, "Collection", it.collectionId)
            if (it.bookId !in entries) missing("listener_collection_items", it.collectionId + ":" + it.bookId, "LibraryEntry", it.bookId)
        }
        p.preferences.forEach { if (it.sourceWorkId !in works) missing("recommendation_preferences", it.kind + ":" + it.targetKey, "Work", it.sourceWorkId) }
        p.tombstones.forEach { issues += ManualWorkMergeConflict.Tombstone(it) }
        fun pairEndpoint(token: String): Set<String> {
            val direct = l.works.filter { it.id == token || (it.mergeKey.isNotBlank() && it.mergeKey == token) }
                .map { it.id }
            val entryOwners = l.entries.filter { it.id == token }.map { it.workId }.filter { it in works }
            return (direct + entryOwners).toSet()
        }
        p.corrections.forEach { row ->
            if (row.kind == CorrectionKind.SPLIT) {
                // ImportPlanner's legacy value is a display title, not a verified opposite identity.
                issues += ManualWorkMergeConflict.OpaqueDocument("corrections", correctionKey(row))
            } else if (row.kind == CorrectionKind.NEVER_MATCH) {
                val first = pairEndpoint(row.mergeKey)
                val second = pairEndpoint(row.value)
                if (first.size > 1) ambiguous("corrections", correctionKey(row) + ":mergeKey", first, issues)
                if (second.size > 1) ambiguous("corrections", correctionKey(row) + ":value", second, issues)
                if (first.isEmpty() || second.isEmpty()) {
                    issues += ManualWorkMergeConflict.OpaqueDocument("corrections", correctionKey(row))
                } else if (first.size == 1 && second.size == 1 && roots.size == 2 &&
                    first.single() != second.single() && first + second == roots.toSet()
                ) {
                    issues += ManualWorkMergeConflict.NeverMatch(row)
                }
                // A known third-party pair remains raw evidence but does not forbid these two roots.
            }
        }
        val selectedFacets = c.workFacets.sortedWith { a, b -> compareMergeUtf8(a.workId, b.workId) }
        for (i in selectedFacets.indices) for (j in i + 1 until selectedFacets.size) {
            val a = selectedFacets[i]; val b = selectedFacets[j]
            if (a.canonicalAuthorId != null && b.canonicalAuthorId != null && a.canonicalAuthorId != b.canonicalAuthorId)
                issues += ManualWorkMergeConflict.CanonicalAuthor(a, b)
        }
        c.seriesMembers.groupBy { it.seriesId }.values.forEach { group ->
            val rows = group.sortedWith { a, b -> compareMergeUtf8(a.workId, b.workId) }
            for (i in rows.indices) for (j in i + 1 until rows.size) {
                val a = rows[i]; val b = rows[j]
                if (a.workId != b.workId && a.position != b.position) issues += ManualWorkMergeConflict.SeriesPosition(a, b)
            }
        }
        l.works.filter { it.mergeKey.isNotBlank() }.groupBy { it.mergeKey }.values.forEach { group ->
            val rows = group.sortedWith { a, b -> compareMergeUtf8(a.id, b.id) }
            for (i in rows.indices) for (j in i + 1 until rows.size) {
                if (rows[i].id !in roots || rows[j].id !in roots)
                    issues += ManualWorkMergeConflict.DuplicateWorkKey(rows[i], rows[j])
            }
        }
    }

    private fun ambiguous(table: String, key: String, owners: Set<String>, issues: MutableList<ManualWorkMergeConflict>) {
        val sorted = owners.sortedWith(::compareMergeUtf8)
        for (i in sorted.indices) for (j in i + 1 until sorted.size)
            issues += ManualWorkMergeConflict.AmbiguousAnchor(table, key, sorted[i], sorted[j])
    }
    private fun correctionKey(row: CorrectionEntity): String = "${row.mergeKey}:${row.kind}:${row.value}"
    private fun feedKey(row: FeedSnapshotEntity): String = "${row.sourceId}:${row.feedKey}:${row.pageCursor}"
}
