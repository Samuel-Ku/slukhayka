package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.source.headersFor

/** A clean request never carries browser cookies or other session material. */
enum class CleanProfileProbeVerdict { PLAYABLE, BLOCKED, UNAVAILABLE }

fun interface CleanProfileProber {
    suspend fun probe(url: String, headers: Map<String, String>): CleanProfileProbeVerdict
}

data class VerifiedSourceProfile(
    val sourceId: String,
    val editionId: String,
    /** The recovery Player has opened the candidate, not merely parsed its URL. */
    val playerOpened: Boolean,
    val source: SourceAccessCandidate,
    val profile: BookProfile
)

enum class ProfilePublication { PUBLISHED, LOCAL_ONLY }

object VerifiedSourceProfileFreshness {
    const val FRESHNESS_MILLIS = 24L * 60 * 60 * 1_000

    fun isFresh(resolvedAtMillis: Long, nowMillis: Long): Boolean =
        resolvedAtMillis >= 0L && nowMillis >= resolvedAtMillis &&
            nowMillis - resolvedAtMillis < FRESHNESS_MILLIS
}

sealed interface VerifiedProfileReadOutcome {
    data class Ready(val entry: SharedProfileEntry) : VerifiedProfileReadOutcome
    data object BrowserRequired : VerifiedProfileReadOutcome
    data object Missing : VerifiedProfileReadOutcome
}

/**
 * The browser-recovery entry point reads only the profiles that the publisher
 * marked verified, then probes them without browser cookies. A blocked or
 * stale URL is an explicit browser-required outcome, never an automatic loop.
 */
class VerifiedSourceProfileReader(
    private val store: SharedBookMetaStore?,
    private val cleanProber: CleanProfileProber,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun read(sourceId: String, editionId: String): VerifiedProfileReadOutcome {
        val entry = runCatching { store?.getProfileEntry(sourceId, editionId) }.getOrNull()
            ?: return VerifiedProfileReadOutcome.Missing
        if (entry.provenanceSource != ProfileProvenance.SOURCE_VERIFIED ||
            !VerifiedSourceProfileFreshness.isFresh(entry.resolvedAt, nowMillis()) ||
            entry.profile.chapters.isEmpty()
        ) return VerifiedProfileReadOutcome.BrowserRequired
        val url = entry.profile.chapters.first().streamUrl
        return if (runCatching { cleanProber.probe(url, headersFor(sourceId, url)) }.getOrNull() == CleanProfileProbeVerdict.PLAYABLE) {
            VerifiedProfileReadOutcome.Ready(entry)
        } else {
            VerifiedProfileReadOutcome.BrowserRequired
        }
    }
}

/**
 * #431 — publishes a Source×Edition shortcut only after two independent facts:
 * the local Player opened it, and a clean cookie-free request can open it too.
 * The optional store is best effort; a missing or failing Firebase write leaves
 * the successful local recovery untouched.
 */
class VerifiedSourceProfilePublisher(
    private val store: SharedBookMetaStore?,
    private val cleanProber: CleanProfileProber,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun publish(candidate: VerifiedSourceProfile): ProfilePublication {
        if (!candidate.playerOpened || candidate.editionId.isBlank() || candidate.profile.chapters.isEmpty()) {
            return ProfilePublication.LOCAL_ONLY
        }
        val probeUrl = candidate.profile.chapters.first().streamUrl
        val approvedHeaders = headersFor(candidate.sourceId, probeUrl)
        if (runCatching { cleanProber.probe(probeUrl, approvedHeaders) }.getOrNull() != CleanProfileProbeVerdict.PLAYABLE) {
            return ProfilePublication.LOCAL_ONLY
        }
        val target = store ?: return ProfilePublication.LOCAL_ONLY
        return runCatching {
            target.putProfile(
                sourceId = candidate.sourceId,
                editionId = candidate.editionId,
                profile = candidate.profile,
                provenance = ProfileProvenance(ProfileProvenance.SOURCE_VERIFIED, nowMillis())
            )
            ProfilePublication.PUBLISHED
        }.getOrDefault(ProfilePublication.LOCAL_ONLY)
    }
}
