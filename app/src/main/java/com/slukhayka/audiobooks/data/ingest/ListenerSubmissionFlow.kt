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
    /** The ordinary import door: [ImportOutcome.IMPORTED] / ALREADY_ADDED carry ids. */
    private val importYouTube: suspend (url: String, metadataJson: String, channelId: String) -> ImportOutcome,
    /** The parsed Telegram preview identity (chapters are honestly empty — RED verdict). */
    private val fetchTgIdentity: suspend (url: String) -> TgIdentity?,
    private val publisher: SubmissionPublisher?,
    private val verification: SubmissionVerification?,
    /** The honest remaining daily budget for the device today. */
    private val remainingToday: suspend () -> Int,
    /** The anonymous device profile id (also the daily-budget key), or null. */
    private val submitterId: suspend () -> String?,
    /** Spec-53 T3 — the restart-safe state carrier (multi-slot). */
    private val store: SubmissionStateStore = InMemorySubmissionStateStore(),
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

    /** Why a submission was refused — mapped to honest copy by the UI. */
    enum class Reason {
        DAILY_LIMIT_REACHED,
        METADATA_FAILED,
        NO_PLAYABLE_TRACKS,
        IMPORT_FAILED,
        ALREADY_PUBLISHED,
        NOT_VERIFIED,
        SHARED_BASE_UNAVAILABLE
    }

    /** The result of starting one submission. */
    sealed interface Start {
        /** The local copy is imported and should be played; publication awaits the verdict. */
        data class Imported(
            val bookId: String,
            val sourceId: String,
            val publishable: Boolean,
            val remainingToday: Int
        ) : Start

        /** A TG post was published metadata-only (no audio exists in the preview). */
        data object MetadataPublished : Start

        data class Refused(val reason: Reason, val remainingToday: Int) : Start

        data object Unsupported : Start
    }

    /** The result of a real playback event against the pending submission. */
    sealed interface Verdict {
        data object Published : Verdict
        data class Refused(val reason: Reason) : Verdict
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
     * Starts one submission: a YouTube link imports locally and waits for the
     * playback verdict; a TG link publishes metadata-only immediately (the
     * public preview exposes no audio — the RED prototype verdict).
     */
    suspend fun submit(rawUrl: String): Start {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Start.Unsupported
        val remaining = remainingToday()
        if (remaining <= 0) return Start.Refused(Reason.DAILY_LIMIT_REACHED, 0)
        return when (classify(url)) {
            Kind.UNSUPPORTED -> Start.Unsupported
            Kind.YOUTUBE -> submitYouTube(url, remaining)
            Kind.TELEGRAM -> submitTelegram(url, remaining)
        }
    }

    private suspend fun submitYouTube(url: String, remaining: Int): Start {
        val metadataJson = try {
            fetchMetadata(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return Start.Refused(Reason.METADATA_FAILED, remaining)

        val outcome = try {
            importYouTube(url, metadataJson, "")
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
                    remainingToday = remaining
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
            SubmissionPublisher.Result.PUBLISHED -> Start.MetadataPublished
            SubmissionPublisher.Result.ALREADY_PUBLISHED -> Start.Refused(Reason.ALREADY_PUBLISHED, remaining)
            SubmissionPublisher.Result.DAILY_LIMIT_REACHED -> Start.Refused(Reason.DAILY_LIMIT_REACHED, 0)
            SubmissionPublisher.Result.METADATA_FAILED -> Start.Refused(Reason.METADATA_FAILED, remaining)
            SubmissionPublisher.Result.NOT_VERIFIED -> Start.Refused(Reason.SHARED_BASE_UNAVAILABLE, remaining)
        }
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
                    store.updateState(sourceId, SubmissionState.State.PUBLISHED, null, System.currentTimeMillis())
                    Verdict.Published
                }
                SubmissionPublisher.Result.ALREADY_PUBLISHED ->
                    settleRefused(sourceId, Reason.ALREADY_PUBLISHED)
                SubmissionPublisher.Result.DAILY_LIMIT_REACHED ->
                    settleRefused(sourceId, Reason.DAILY_LIMIT_REACHED)
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

    private companion object {
        val YOUTUBE_URL = Regex("""https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/""")
        val TELEGRAM_URL = Regex("""https?://t\.me/""")
    }
}

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
