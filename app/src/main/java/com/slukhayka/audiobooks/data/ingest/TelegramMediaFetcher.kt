package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.source.SourceRegistry

/**
 * ADR-0050 / spec-53 (#829) — the account-bound Telegram door.
 *
 * The listener's own Telegram account is the ONLY way to reach the audio of a
 * community-library post: the public `t.me/s/…` preview carries metadata but no
 * sound. This interface is the ONE new injection the submission lane gains; its
 * implementation (MTProto/TDLib on device) is the risky part and lands behind a
 * spike.
 *
 * Hard rules the seam encodes, so no implementation can quietly break them:
 * - a session is DEVICE-LOCAL: never synchronised, never transferred, and the
 *   listener can sign out at any time;
 * - login happens ONLY on an explicit listener action — no implicit login as a
 *   side effect of pasting a link or opening a card;
 * - no session, or membership refused, is an HONEST refusal carrying the group
 *   deep link to join — never a silent empty result;
 * - the APK holds no token or secret: whatever the implementation persists is a
 *   local session, not a credential shipped in the build.
 */
sealed interface TelegramSessionState {
    /** No local session: nothing was ever connected, or the listener signed out. */
    data object Absent : TelegramSessionState

    /** A local session exists and the listener is a member of the group. */
    data object Connected : TelegramSessionState

    /** A session exists, but the account is not in the registered group. */
    data object NotMember : TelegramSessionState
}

/** One audio part of a community-library post, in the post's own order. */
data class TelegramTrack(
    val index: Int,
    val title: String,
    val sizeBytes: Long? = null,
    val mimeType: String? = null
)

/** The honest outcome of one account-bound fetch. */
sealed interface TelegramFetchResult {
    data class Ok(val tracks: List<TelegramTrack>) : TelegramFetchResult

    /**
     * Nothing was fetched. [joinDeepLink] always carries the registered group's
     * URL, so the surface can offer "join the group" instead of a dead end.
     */
    data class Refused(val reason: String, val joinDeepLink: String) : TelegramFetchResult
}

/**
 * The fetcher seam. Implementations talk MTProto under the listener's account;
 * tests drive a fake. Nothing here exposes a token, and nothing logs in
 * implicitly.
 */
interface TelegramMediaFetcher {

    /** The current local session state; never triggers a login. */
    suspend fun session(): TelegramSessionState

    /**
     * Connects the listener's account — ONLY ever called from an explicit
     * action. @return the state after the attempt.
     */
    suspend fun connect(): TelegramSessionState

    /** Signs the listener out and drops the local session. */
    suspend fun disconnect()

    /** Fetches the post's metadata and tracks; refuses honestly when unable. */
    suspend fun fetch(link: String): TelegramFetchResult
}

/**
 * The pure rules of the Telegram door: where to send a listener who may not be
 * a member, and what an honest refusal says.
 */
object TelegramSessionPolicy {

    const val REASON_NO_SESSION = "telegram-no-session"
    const val REASON_NOT_MEMBER = "telegram-not-member"
    const val REASON_UNSUPPORTED_LINK = "telegram-unsupported-link"

    /** The registered community group's join URL, from the Source Registry. */
    fun joinDeepLink(): String =
        SourceRegistry.facts(SOURCE_ID)?.telegramProfile?.groupUrl
            ?: SourceRegistry.facts(SOURCE_ID)?.homeUrl.orEmpty()

    /**
     * The refusal for a non-connected state, or null when the state can fetch.
     * A refusal ALWAYS carries the join deep link.
     */
    fun refusalFor(state: TelegramSessionState): TelegramFetchResult.Refused? = when (state) {
        TelegramSessionState.Connected -> null
        TelegramSessionState.Absent ->
            TelegramFetchResult.Refused(REASON_NO_SESSION, joinDeepLink())
        TelegramSessionState.NotMember ->
            TelegramFetchResult.Refused(REASON_NOT_MEMBER, joinDeepLink())
    }

    const val SOURCE_ID = "telegram"
}
