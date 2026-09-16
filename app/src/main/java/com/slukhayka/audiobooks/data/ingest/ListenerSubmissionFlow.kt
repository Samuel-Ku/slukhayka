package com.slukhayka.audiobooks.data.ingest

import kotlinx.coroutines.CancellationException

/**
 * ADR-0035 / #604/#605/#606 — the ONE listener-submission flow behind the
 * «Надіслати посилання» door: a pasted YouTube or Telegram link becomes a
 * local Source, a real playback verdict makes it publishable, and the
 * shared base gets the Source + metadata deltas — never audio.
 *
 * The flow is pure JVM: every effect arrives as a seam (metadata fetch,
 * import, TG identity parse, publish, remaining-budget read, submitter id),
 * and the production composition plugs [SubmissionVerification],
 * [SubmissionPolicy] and [SubmissionPublisher] into them. The verdict rule
 * is the ADR's whole quality bar: ONLY the player's real `playing` event —
 * [onPlaybackStarted] — may publish; a bare link insert never does.
 *
 * Honest degradation by contract: a missing shared base (no Firebase keys),
 * a missing profile or a failing publisher never breaks the local import —
 * the book still lands in the library and plays; only the shared publication
 * is reported as unavailable.
 */
class ListenerSubmissionFlow(
    /** `yt-dlp -J --flat-playlist` metadata of the submitted link, or null. */
    private val fetchMetadata: suspend (url: String) -> String?,
    /**
     * The ordinary import door: [ImportOutcome.IMPORTED] / ALREADY_ADDED carry ids.
     * Spec-53 T9 — [edits] carries the preview's corrections into the import,
     * so the added book starts corrected instead of needing a fix afterwards.
     * Spec-53 T11 — [selectedWatchUrls] narrows a playlist to the picked
     * positions; null means "everything the engine observed".
     */
    private val importYouTube: suspend (
        url: String,
        metadataJson: String,
        channelId: String,
        edits: PreviewEdits?,
        selectedWatchUrls: Set<String>?
    ) -> ImportOutcome,
    /** The parsed Telegram preview identity (chapters are honestly empty — RED verdict). */
    private val fetchTgIdentity: suspend (url: String) -> TgIdentity?,
    /**
     * Spec-53 T5 — materialises the TG post as a sourceless library card
     * («Шукаємо джерело»). Null keeps the pre-T5 metadata-only path.
     */
    private val importWatchingTelegram: (suspend (url: String, identity: TgIdentity) -> WatchingImport)? = null,
    /**
     * Spec-53 T5 — arms the Source Watch for that card's Work, so a direct
     * source found later arrives through the existing spec-49 machinery.
     */
    private val watchSource: (suspend (mergeKey: String, workId: String) -> Unit)? = null,
    private val publisher: SubmissionPublisher?,
    private val verification: SubmissionVerification?,
    /** The honest remaining daily budget for the device today. */
    private val remainingToday: suspend () -> Int,
    /** The anonymous device profile id (also the daily-budget key), or null. */
    private val submitterId: suspend () -> String?,
    /** Spec-53 T3 — the restart-safe state carrier (multi-slot). */
    private val store: SubmissionStateStore = InMemorySubmissionStateStore(),
    /**
     * Spec-53 T8 — the honest connectivity read. Offline is NOT a failure:
     * the paste is deferred instead of burning the daily budget or claiming
     * the link was unreadable.
     */
    private val isOnline: suspend () -> Boolean = { true },
) {

    /** The shape of the paste. */
    enum class Kind { YOUTUBE, TELEGRAM, UNSUPPORTED }

    /** The import door's outcome with the ids the verdict seam needs. */
    data class ImportOutcome(
        val result: ImportResult,
        val bookId: String? = null,
        val sourceId: String? = null
    )

    enum class ImportResult { IMPORTED, ALREADY_ADDED, METADATA_FAILED, NO_PLAYABLE_TRACKS }

    /** The parsed TG post identity (never invented — absent stays absent). */
    data class TgIdentity(
        val title: String,
        val author: String? = null,
        val narrator: String? = null,
        val coverUrl: String? = null,
        val description: String? = null
    )

    /**
     * Spec-53 T5 — the library card created for a TG post: the stored book and
     * the Work key the watch is armed with. Both null when the card could not
     * be materialised (the publication still stands — metadata-only).
     */
    data class WatchingImport(
        val bookId: String? = null,
        val mergeKey: String? = null,
        val workId: String? = null
    )

    /**
     * Spec-53 T9 — the listener's preview corrections. Null fields mean
     * "keep the engine's value"; the import applies them before
     * materialisation, so identity (mergeKey, Work) is built corrected.
     */
    data class PreviewEdits(
        val title: String? = null,
        val author: String? = null,
        val narrator: String? = null
    )

    /** Spec-53 T9 — what the pre-add preview may show. */
    enum class PreviewKind { YOUTUBE_VIDEO, YOUTUBE_PLAYLIST, TELEGRAM_POST }

    /**
     * Spec-53 T11 — one pickable position of a playlist: the engine's own
     * title and duration, and the canonical watch URL that doubles as the
     * selection identity (the same string the import door materialises, so a
     * tick can never point at a different video than the one imported).
     */
    data class PreviewEntry(
        val watchUrl: String,
        val title: String,
        val durationSeconds: Long? = null
    )

    /**
     * Spec-53 T9/T11 — the honest pre-add preview: everything the engine
     * really observed (title, author, cover, durations, the ordered playlist
     * positions), nothing invented. A null cover/duration means "the engine
     * saw none"; [entries] is empty for a single video or a TG post.
     */
    data class SubmissionPreview(
        val url: String,
        val kind: PreviewKind,
        val title: String,
        val author: String?,
        val narrator: String?,
        val coverUrl: String?,
        val durationSeconds: Long?,
        val chapterCount: Int,
        val entries: List<PreviewEntry> = emptyList()
    )

    /** Why a submission was refused — mapped to honest copy by the UI. */
    enum class Reason {
        DAILY_LIMIT_REACHED,
        METADATA_FAILED,
        NO_PLAYABLE_TRACKS,
        IMPORT_FAILED,
        ALREADY_PUBLISHED,
        NOT_VERIFIED,
        SHARED_BASE_UNAVAILABLE,
        /** #836 — the curator rejected this link: it can never return. */
        REJECTED
    }

    /** The result of starting one submission. */
    sealed interface Start {
        /** The local copy is imported and should be played; publication awaits the verdict. */
        data class Imported(
            val bookId: String,
            val sourceId: String,
            val publishable: Boolean,
            val remainingToday: Int,
            /**
             * Spec-53 T6 — this link's copy was ALREADY in my library. The
             * sheet says so and offers «Відкрити книгу» instead of pretending
             * a fresh import happened.
             */
            val alreadyInLibrary: Boolean = false
        ) : Start

        /**
         * A TG post was published metadata-only (no audio exists in the
         * preview) AND got its own library card. Spec-53 T5: [bookId] is that
         * card, [mergeKey] the Work it waits for — the Source Watch entry
         * alarms the listener when a direct source appears.
         */
        data class MetadataPublished(
            val bookId: String? = null,
            val mergeKey: String? = null
        ) : Start

        data class Refused(val reason: Reason, val remainingToday: Int) : Start

        /**
         * Spec-53 T8 — the link was pasted without a network. It is stored in
         * the visible queue and will be processed on the next open or when the
         * network returns; nothing was attempted silently.
         */
        data class Deferred(val url: String) : Start

        /**
         * Spec-53 T10 — the pasted link is a whole channel, not one book. No
         * blind import happens: the caller opens the selection card for this
         * [url] instead. Deferred rows holding a channel link are skipped by
         * [processDeferred] for the same reason — the card needs a human.
         */
        data class ChannelLink(val url: String) : Start

        data object Unsupported : Start
    }

    /** The result of a real playback event against the pending submission. */
    sealed interface Verdict {
        data object Published : Verdict

        /**
         * Moderation T4 (#837) — the candidate is QUEUED and waits for the
         * curator: the honest verdict, never "already in the shared base".
         */
        data object PendingModeration : Verdict
        data class Refused(val reason: Reason) : Verdict

        /**
         * Spec-53 T12 — the verdict was real, the day's budget was gone. The
         * publication is not refused: it waits for tomorrow, and the stored
         * row keeps the proof so no second playback is needed.
         */
        data object DeferredPublication : Verdict

        data object NoPending : Verdict
    }


    /** YouTube hosts (watch, short, playlist, music) versus Telegram preview links. */
    fun classify(rawUrl: String): Kind {
        val url = rawUrl.trim()
        return when {
            YOUTUBE_URL.containsMatchIn(url) -> Kind.YOUTUBE
            TELEGRAM_URL.containsMatchIn(url) -> Kind.TELEGRAM
            else -> Kind.UNSUPPORTED
        }
    }

    /** The honest remaining budget right now (part of the sheet's copy). */
    suspend fun remainingToday(): Int =
        runCatching { remainingToday.invoke() }.getOrDefault(0)

    /**
     * Whether the pasted link is a whole YouTube channel (`/channel/`,
     * `/@handle`, `/c/`, `/user/`) rather than one video or playlist. Pure:
     * the same canonical form the import door uses, so the check and the
     * identity can never drift apart.
     */
    fun isChannelLink(rawUrl: String): Boolean {
        val url = SubmissionUrlCanonicalizer.canonical(rawUrl) ?: return false
        return CHANNEL_URL.matches(url)
    }

    /**
     * Starts one submission: a YouTube link imports locally and waits for the
     * playback verdict; a TG link publishes metadata-only immediately (the
     * public preview exposes no audio — the RED prototype verdict); a channel
     * link opens the selection card instead of importing blindly.
     */
    suspend fun submit(
        rawUrl: String,
        edits: PreviewEdits? = null,
        selectedWatchUrls: Set<String>? = null
    ): Start {
        // Spec-53 T6 — one canonical form per link, so dedup and identity are
        // real: every live YouTube shape and every TG query string lands here.
        val url = SubmissionUrlCanonicalizer.canonical(rawUrl) ?: return Start.Unsupported
        // Spec-53 T8 — a paste without a network waits, visibly.
        if (!runCatching { isOnline() }.getOrDefault(true)) return defer(url)
        // Spec-53 T10 — a channel is picked, never auto-imported. Before the
        // budget check: opening the card must not spend a submission.
        if (CHANNEL_URL.matches(url)) return Start.ChannelLink(url)
        val remaining = remainingToday()
        if (remaining <= 0) return Start.Refused(Reason.DAILY_LIMIT_REACHED, 0)
        // Spec-53 T11 — an explicit empty selection is an honest "nothing to
        // add", never a silent full-playlist import.
        if (selectedWatchUrls != null && selectedWatchUrls.isEmpty()) {
            return Start.Refused(Reason.NO_PLAYABLE_TRACKS, remaining)
        }
        return when (classify(url)) {
            Kind.UNSUPPORTED -> Start.Unsupported
            Kind.YOUTUBE -> submitYouTube(url, remaining, edits, selectedWatchUrls)
            Kind.TELEGRAM -> submitTelegram(url, remaining)
        }
    }

    /**
     * Spec-53 T10 — the canonical watch URLs of one playlist's entries, for
     * the channel card's membership dedup. Read-only: no import, no budget,
     * no store writes. Null when the engine saw nothing usable — the card
     * then treats the playlist as having no known members, never as empty.
     */
    suspend fun playlistMemberUrls(playlistUrl: String): Set<String>? {
        val url = SubmissionUrlCanonicalizer.canonical(playlistUrl) ?: return null
        if (!runCatching { isOnline() }.getOrDefault(true)) return null
        val metadataJson = try {
            fetchMetadata(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val metadata = YouTubeSubmissionPlanner.parseMetadata(metadataJson) ?: return null
        val plan = YouTubeSubmissionPlanner.plan(url, metadata, "")
        if (plan.chapters.isEmpty()) return null
        return plan.chapters.map { it.watchUrl }.toSet()
    }

    /**
     * Spec-53 T9 — the pre-add preview: engine data only, zero side effects
     * (no import, no budget, no store writes). Null when the link is
     * unsupported, offline, or the engine saw nothing usable.
     */
    suspend fun previewSubmission(rawUrl: String): SubmissionPreview? {
        val url = SubmissionUrlCanonicalizer.canonical(rawUrl) ?: return null
        if (!runCatching { isOnline() }.getOrDefault(true)) return null
        return when (classify(url)) {
            Kind.YOUTUBE -> previewYouTube(url)
            Kind.TELEGRAM -> previewTelegram(url)
            Kind.UNSUPPORTED -> null
        }
    }

    private suspend fun previewYouTube(url: String): SubmissionPreview? {
        val metadataJson = try {
            fetchMetadata(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val metadata = YouTubeSubmissionPlanner.parseMetadata(metadataJson) ?: return null
        if (metadata.title.isBlank()) return null
        val plan = YouTubeSubmissionPlanner.plan(url, metadata, "")
        if (plan.chapters.isEmpty()) return null
        val playlist = metadata.entries.isNotEmpty()
        val totalSeconds = if (playlist) {
            plan.chapters.sumOf { it.durationSeconds }.takeIf { it > 0 }
        } else {
            metadata.durationSeconds
        }
        return SubmissionPreview(
            url = url,
            kind = if (playlist) PreviewKind.YOUTUBE_PLAYLIST else PreviewKind.YOUTUBE_VIDEO,
            title = plan.title,
            author = plan.author,
            narrator = plan.narrator,
            coverUrl = metadata.coverUrl,
            durationSeconds = totalSeconds,
            chapterCount = plan.chapters.size,
            // Spec-53 T11 — the ordered positions a big playlist is picked
            // from; empty for a single video (nothing to choose there).
            entries = if (playlist) {
                plan.chapters.map { chapter ->
                    PreviewEntry(
                        watchUrl = chapter.watchUrl,
                        title = chapter.title,
                        durationSeconds = chapter.durationSeconds.takeIf { it > 0 }
                    )
                }
            } else {
                emptyList()
            }
        )
    }

    private suspend fun previewTelegram(url: String): SubmissionPreview? {
        val identity = try {
            fetchTgIdentity(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (identity.title.isBlank()) return null
        return SubmissionPreview(
            url = url,
            kind = PreviewKind.TELEGRAM_POST,
            title = identity.title,
            author = identity.author,
            narrator = identity.narrator,
            coverUrl = identity.coverUrl,
            durationSeconds = null,
            chapterCount = 0
        )
    }

    /** Spec-53 T8 — stores a deferred link once, keyed by its canonical URL. */
    private suspend fun defer(url: String): Start {
        val now = System.currentTimeMillis()
        runCatching {
            store.save(
                SubmissionState(
                    sourceId = DeferredSubmissionQueue.keyFor(url),
                    url = url,
                    bookId = "",
                    metadataJson = "",
                    channelId = "",
                    state = SubmissionState.State.DEFERRED,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        return Start.Deferred(url)
    }

    /** Spec-53 T8 — the visible queue, oldest first. */
    suspend fun deferredSubmissions(): List<SubmissionState> =
        runCatching { store.deferred() }.getOrDefault(emptyList())

    /** Spec-53 T8 — drops one queued link: the listener's explicit "не треба". */
    suspend fun removeDeferred(sourceId: String) {
        runCatching { store.remove(sourceId) }
    }

    /**
     * Spec-53 T8 — processes the queue ONCE and only with a network. Each
     * link leaves the queue BEFORE its single attempt, so a processed link can
     * never run twice; an attempt that lands offline again is honestly
     * re-queued by [defer] instead of retried in a loop.
     */
    suspend fun processDeferred(): List<Start> {
        if (!runCatching { isOnline() }.getOrDefault(true)) return emptyList()
        val results = mutableListOf<Start>()
        for (row in deferredSubmissions()) {
            // Spec-53 T10 — a queued channel link stays queued: the selection
            // card needs a human, so a headless pass must not consume it.
            if (CHANNEL_URL.matches(row.url)) continue
            store.remove(row.sourceId)
            val outcome = submit(row.url)
            if (outcome is Start.Deferred) continue
            results += outcome
        }
        return results
    }

    private suspend fun submitYouTube(
        url: String,
        remaining: Int,
        edits: PreviewEdits?,
        selectedWatchUrls: Set<String>?
    ): Start {
        val metadataJson = try {
            fetchMetadata(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return Start.Refused(Reason.METADATA_FAILED, remaining)

        val outcome = try {
            importYouTube(url, metadataJson, "", edits, selectedWatchUrls)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return Start.Refused(Reason.IMPORT_FAILED, remaining)

        return when (outcome.result) {
            ImportResult.METADATA_FAILED -> Start.Refused(Reason.METADATA_FAILED, remaining)
            ImportResult.NO_PLAYABLE_TRACKS -> Start.Refused(Reason.NO_PLAYABLE_TRACKS, remaining)
            ImportResult.IMPORTED, ImportResult.ALREADY_ADDED -> {
                val bookId = outcome.bookId
                val sourceId = outcome.sourceId
                if (bookId.isNullOrBlank() || sourceId.isNullOrBlank()) {
                    return Start.Refused(Reason.IMPORT_FAILED, remaining)
                }
                val submitter = runCatching { submitterId.invoke() }.getOrNull()
                val publishable = publisher != null && verification != null && !submitter.isNullOrBlank()
                if (publishable) {
                    // Only a verified, publishable submission awaits a
                    // verdict; the store keeps it across restarts and
                    // multiple links await side by side.
                    val now = System.currentTimeMillis()
                    store.save(
                        SubmissionState(
                            sourceId = sourceId,
                            url = url,
                            bookId = bookId,
                            metadataJson = metadataJson,
                            channelId = "",
                            state = SubmissionState.State.AWAITING_PLAY,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                Start.Imported(
                    bookId = bookId,
                    sourceId = sourceId,
                    publishable = publishable,
                    remainingToday = remaining,
                    alreadyInLibrary = outcome.result == ImportResult.ALREADY_ADDED
                )
            }
        }
    }

    private suspend fun submitTelegram(url: String, remaining: Int): Start {
        val identity = try {
            fetchTgIdentity(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return Start.Refused(Reason.METADATA_FAILED, remaining)
        if (identity.title.isBlank()) return Start.Refused(Reason.METADATA_FAILED, remaining)

        val pub = publisher ?: return Start.Refused(Reason.SHARED_BASE_UNAVAILABLE, remaining)
        val submitter = runCatching { submitterId.invoke() }.getOrNull()
            ?: return Start.Refused(Reason.SHARED_BASE_UNAVAILABLE, remaining)

        return when (
            pub.publishTgMetadata(
                url = url,
                title = identity.title,
                author = identity.author,
                narrator = identity.narrator,
                coverUrl = identity.coverUrl,
                description = identity.description,
                submitterId = submitter
            )
        ) {
            SubmissionPublisher.Result.PUBLISHED -> {
                // Spec-53 T5 — the shared base already has the metadata; now
                // the post gets a card of its OWN, so the listener's path does
                // not end in empty space. The card waits for a direct source.
                val watching = createWatchingCard(url, identity)
                Start.MetadataPublished(
                    bookId = watching?.bookId,
                    mergeKey = watching?.mergeKey
                )
            }
            SubmissionPublisher.Result.ALREADY_PUBLISHED -> Start.Refused(Reason.ALREADY_PUBLISHED, remaining)
            // #836 — an honest verdict, not silence: the listener learns the
            // link was rejected (and the budget is untouched — nothing was queued).
            SubmissionPublisher.Result.REJECTED -> Start.Refused(Reason.REJECTED, remaining)
            SubmissionPublisher.Result.DAILY_LIMIT_REACHED -> Start.Refused(Reason.DAILY_LIMIT_REACHED, 0)
            SubmissionPublisher.Result.METADATA_FAILED -> Start.Refused(Reason.METADATA_FAILED, remaining)
            SubmissionPublisher.Result.NOT_VERIFIED -> Start.Refused(Reason.SHARED_BASE_UNAVAILABLE, remaining)
        }
    }

    /**
     * Spec-53 T5 — the local card and the watch that follows it. Both are
     * local, silent effects: a failure here never un-publishes the metadata
     * and never refuses the submission — the honest degradation is a card
     * without a watch, and the caller still reports «Поділилися».
     */
    private suspend fun createWatchingCard(url: String, identity: TgIdentity): WatchingImport? {
        val imported = runCatching { importWatchingTelegram?.invoke(url, identity) }
            .getOrNull() ?: return null
        val mergeKey = imported.mergeKey?.takeIf { it.isNotBlank() }
        val workId = imported.workId?.takeIf { it.isNotBlank() } ?: mergeKey
        if (mergeKey != null && workId != null) {
            runCatching { watchSource?.invoke(mergeKey, workId) }
        }
        val bookId = imported.bookId?.takeIf { it.isNotBlank() }
        if (bookId != null) {
            val now = System.currentTimeMillis()
            runCatching {
                store.save(
                    SubmissionState(
                        sourceId = "tg-$bookId",
                        url = url,
                        bookId = bookId,
                        metadataJson = "",
                        channelId = "",
                        state = SubmissionState.State.WATCHING,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
        return imported
    }

    /**
     * The verdict seam: called ONLY from the player's factual `playing`
     * event with the physical source id the engine reports. The matching
     * pending submission records the verdict and publishes; everything else
     * is a no-op. The pending slot clears after the one attempt — a repeat
     * `playing` event can never publish twice.
     */
    suspend fun onPlaybackStarted(sourceId: String): Verdict {
        if (sourceId.isBlank()) return Verdict.NoPending
        val active = store.bySourceId(sourceId) ?: return Verdict.NoPending
        if (active.state != SubmissionState.State.AWAITING_PLAY) return Verdict.NoPending
        verification?.record(sourceId, actualPlaybackStarted = true)
        val submitter = runCatching { submitterId.invoke() }.getOrNull()
            ?: return settleRefused(sourceId, Reason.SHARED_BASE_UNAVAILABLE)
        val pub = publisher ?: return settleRefused(sourceId, Reason.SHARED_BASE_UNAVAILABLE)
        return try {
            when (
                pub.publish(
                    url = active.url,
                    metadataJson = active.metadataJson,
                    channelId = active.channelId,
                    sourceId = active.sourceId,
                    submitterId = submitter
                )
            ) {
                SubmissionPublisher.Result.PUBLISHED -> {
                    // #837 — the verified submission is a QUEUED candidate, not
                    // a published card: the honest state is "on moderation"
                    // until the curator decides.
                    store.updateState(
                        sourceId,
                        SubmissionState.State.PENDING_MODERATION,
                        null,
                        System.currentTimeMillis()
                    )
                    Verdict.PendingModeration
                }
                SubmissionPublisher.Result.ALREADY_PUBLISHED ->
                    settleRefused(sourceId, Reason.ALREADY_PUBLISHED)
                SubmissionPublisher.Result.REJECTED ->
                    settleRefused(sourceId, Reason.REJECTED)
                SubmissionPublisher.Result.DAILY_LIMIT_REACHED -> {
                    // Spec-53 T12 — the playback really happened, so the
                    // promise is not thrown away: the row moves to
                    // DEFERRED_PUBLICATION and tomorrow's pass publishes from
                    // this very row, with no second playback.
                    store.updateState(
                        sourceId,
                        SubmissionState.State.DEFERRED_PUBLICATION,
                        Reason.DAILY_LIMIT_REACHED.name,
                        System.currentTimeMillis()
                    )
                    Verdict.DeferredPublication
                }
                SubmissionPublisher.Result.METADATA_FAILED ->
                    settleRefused(sourceId, Reason.METADATA_FAILED)
                SubmissionPublisher.Result.NOT_VERIFIED ->
                    settleRefused(sourceId, Reason.NOT_VERIFIED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            settleRefused(sourceId, Reason.SHARED_BASE_UNAVAILABLE)
        }
    }

    private suspend fun settleRefused(sourceId: String, reason: Reason): Verdict {
        store.updateState(sourceId, SubmissionState.State.REFUSED, reason.name, System.currentTimeMillis())
        return Verdict.Refused(reason)
    }

    /** Spec-53 T3 — the book ids still awaiting their playback verdict. */
    suspend fun awaitingBookIds(): Set<String> =
        store.awaiting().map { it.bookId }.toSet()

    /** Spec-53 T5 — the book ids whose card waits for a direct source. */
    suspend fun watchingBookIds(): Set<String> =
        runCatching { store.watching().map { it.bookId }.toSet() }.getOrDefault(emptySet())

    /** Spec-53 T12 — the book ids whose publication waits for tomorrow. */
    suspend fun deferredPublicationBookIds(): Set<String> =
        runCatching { store.deferredPublications().map { it.bookId }.toSet() }.getOrDefault(emptySet())

    /**
     * Spec-53 T12 — the next-day pass. Every stored submission whose REAL
     * verdict already landed is published from ITS OWN row: no second
     * playback, no re-import, no new fetch. The daily budget is re-read by
     * the publisher, so a still-exhausted day simply leaves the row waiting;
     * the limit is respected, never bypassed.
     */
    suspend fun publishDeferredPublications(): List<Verdict> {
        val results = mutableListOf<Verdict>()
        for (row in runCatching { store.deferredPublications() }.getOrDefault(emptyList())) {
            val pub = publisher ?: break
            val submitter = runCatching { submitterId.invoke() }.getOrNull() ?: break
            val verdict = try {
                when (
                    pub.publishDeferred(
                        url = row.url,
                        metadataJson = row.metadataJson,
                        channelId = row.channelId,
                        sourceId = row.sourceId,
                        submitterId = submitter,
                        // The row was written the moment the verdict landed,
                        // so its stamp IS the honest verdict time — and it
                        // survives the restart that wipes the in-memory
                        // verification record.
                        verifiedAt = row.updatedAt
                    )
                ) {
                    SubmissionPublisher.Result.PUBLISHED,
                    SubmissionPublisher.Result.ALREADY_PUBLISHED -> {
                        // #837 — queued (or already queued by another device):
                        // the promise is settled, and the honest state is
                        // "on moderation" until the curator decides.
                        store.updateState(
                            row.sourceId,
                            SubmissionState.State.PENDING_MODERATION,
                            null,
                            System.currentTimeMillis()
                        )
                        Verdict.PendingModeration
                    }
                    SubmissionPublisher.Result.DAILY_LIMIT_REACHED -> Verdict.DeferredPublication
                    else -> Verdict.Refused(Reason.METADATA_FAILED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Verdict.Refused(Reason.SHARED_BASE_UNAVAILABLE)
            }
            results += verdict
            // The day is still exhausted: asking the remaining rows would only
            // repeat the same refusal.
            if (verdict is Verdict.DeferredPublication) break
        }
        return results
    }

    /**
     * Spec-53 T7 — the published submission of one book, when it exists. This
     * is the gate for «Оновити в спільній базі»: null means the action must
     * not exist at all. A [SubmissionState.State.PUBLISHED] row could only be
     * written after a real playback verdict, so the quality bar holds even
     * after a restart wipes the in-memory verification record.
     */
    suspend fun publishedSubmission(bookId: String): SubmissionState? =
        runCatching {
            store.byBookId(bookId)?.takeIf { it.state == SubmissionState.State.PUBLISHED }
        }.getOrNull()

    /**
     * Spec-53 T7 — pushes the corrected display claims of my published
     * submission back to the shared base. Identity is the stored row's URL
     * (→ document id), never recomputed from the edit, so a fixed title can
     * never fork a second document. A failed update leaves the row PUBLISHED:
     * the publication still stands, only the correction did not land.
     */
    suspend fun updatePublishedMetadata(
        bookId: String,
        title: String,
        author: String?,
        narrator: String?
    ): Verdict {
        val row = publishedSubmission(bookId) ?: return Verdict.NoPending
        val pub = publisher ?: return Verdict.Refused(Reason.SHARED_BASE_UNAVAILABLE)
        val submitter = runCatching { submitterId.invoke() }.getOrNull()
            ?: return Verdict.Refused(Reason.SHARED_BASE_UNAVAILABLE)
        return try {
            when (
                pub.updatePublishedMetadata(
                    url = row.url,
                    metadataJson = row.metadataJson,
                    channelId = row.channelId,
                    sourceId = row.sourceId,
                    submitterId = submitter,
                    title = title,
                    author = author,
                    narrator = narrator,
                    verifiedAt = row.updatedAt
                )
            ) {
                SubmissionPublisher.Result.PUBLISHED -> Verdict.Published
                SubmissionPublisher.Result.METADATA_FAILED -> Verdict.Refused(Reason.METADATA_FAILED)
                // #834 — the queue belongs to the curator once the document
                // exists; the refusal says so instead of blaming the network.
                SubmissionPublisher.Result.ALREADY_PUBLISHED -> Verdict.Refused(Reason.ALREADY_PUBLISHED)
                SubmissionPublisher.Result.REJECTED -> Verdict.Refused(Reason.REJECTED)
                else -> Verdict.Refused(Reason.SHARED_BASE_UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Verdict.Refused(Reason.SHARED_BASE_UNAVAILABLE)
        }
    }

}

// Spec-53 T4 — the supported submission hosts, shared by the flow's
// classification and the share/clipboard intake.
private val YOUTUBE_URL = Regex("""https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/""")
private val TELEGRAM_URL = Regex("""https?://t\.me/""")

/**
 * Spec-53 T10 — the canonical channel shapes the canonicalizer emits
 * (`/channel/`, `/@handle`, `/c/`, `/user/`). Full-match on purpose: a watch
 * or playlist URL must never read as a channel.
 */
private val CHANNEL_URL = Regex("""https://www\.youtube\.com/(?:channel/|c/|user/|@).+""")

/**
 * Spec-53 T4 — the first supported link inside a shared text (a system
 * ACTION_SEND often carries "Title\nhttps://…"), or null when there is
 * none. Pure: the share/clipboard intake and its tests share it, so no
 * unsupported paste ever reaches the submission flow.
 */
fun sharedSubmissionUrlOf(text: String?): String? {
    val candidate = text?.let { RAW_URL.find(it)?.value } ?: return null
    return candidate.takeIf { YOUTUBE_URL.containsMatchIn(it) || TELEGRAM_URL.containsMatchIn(it) }
}

private val RAW_URL = Regex("""https?://\S+""")

/**
 * The canonical page the TG preview is read from: an exact post link becomes
 * the `?embed=1` single-post view (the RED-prototype verdict: the newest-10
 * channel page renders other posts), a `t.me/s/…` preview stays as-is.
 * Null for anything that is not a Telegram link.
 */
fun tgPreviewFetchUrl(rawUrl: String): String? {
    val url = rawUrl.trim()
    val embed = Regex("""https?://t\.me/([A-Za-z0-9_]{3,32})/(\d+)(?:\?.*)?""").matchEntire(url)
    if (embed != null) {
        return "https://t.me/${embed.groupValues[1]}/${embed.groupValues[2]}?embed=1"
    }
    if (Regex("""https?://t\.me/s/[A-Za-z0-9_]{3,32}(?:/\d+)?(?:\?.*)?""").matches(url)) return url
    return null
}
