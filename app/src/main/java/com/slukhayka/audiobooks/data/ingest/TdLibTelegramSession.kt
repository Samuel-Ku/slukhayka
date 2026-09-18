package com.slukhayka.audiobooks.data.ingest

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * ADR-0050 / #829 — the narrow view of TDLib that [TdLibTelegramSession] needs.
 *
 * The seam exists so the session's rules — which status counts as membership,
 * how a linked post becomes a [TelegramMessage] — can be tested on the JVM: a
 * real `Client` cannot be built off-device because its JNI library is loaded on
 * the device only (see `TelegramLoginSpikeTest`). This interface deliberately
 * mentions no `TdApi` type, so a fake needs no binding at all; the production
 * adapter ([TdlibClientAdapter]) is the one place that speaks TDLib.
 *
 * Every method answers `null` when TDLib refuses (`TdApi.Error`), when the
 * object does not exist, or when no session is logged in — the caller then
 * refuses honestly instead of receiving a half-invented object.
 */
interface TdlibClient {

    /** The logged-in account's id (`GetMe`), or null when there is no session. */
    suspend fun currentUserId(): Long?

    /** The id of the public chat behind a username (`SearchPublicChat`). */
    suspend fun publicChatId(username: String): Long?

    /** [userId]'s membership in [chatId] (`GetChatMember`). */
    suspend fun memberStatus(chatId: Long, userId: Long): MemberStatus?

    /** The post behind a canonical `t.me/…` link (`GetMessageLinkInfo`). */
    suspend fun message(link: String): TelegramMessage?
}

/**
 * The membership statuses the group door distinguishes. `CREATOR` is TDLib's
 * name for the group owner; [UNKNOWN] keeps an unrecognised status from ever
 * being read as a "yes".
 */
enum class MemberStatus {
    MEMBER,
    ADMINISTRATOR,
    CREATOR,
    RESTRICTED,
    BANNED,
    LEFT,
    UNKNOWN
}

/**
 * ADR-0050 / #829 — [TelegramSession] on TDLib.
 *
 * Membership is asked of the account itself (me → the group chat → my record in
 * it), so it is the LISTENER's membership that decides, never the client's.
 *
 * A post is read through its canonical link; an inaccessible one (deleted,
 * private, not a member) yields null and never a stub.
 */
class TdLibTelegramSession(
    private val client: TdlibClient,
    private val groupUsername: String = DEFAULT_GROUP_USERNAME
) : TelegramSession {

    override suspend fun isMember(): Boolean {
        val userId = client.currentUserId() ?: return false
        val chatId = client.publicChatId(groupUsername) ?: return false
        val status = client.memberStatus(chatId, userId) ?: return false
        return status.isMembership
    }

    override suspend fun message(link: String): TelegramMessage? = client.message(link)
}

/**
 * The group's public username. Derived from the join deep link in
 * [TelegramMembershipPolicy] so the chat we check and the chat we tell the
 * listener to join can never drift apart.
 */
private val DEFAULT_GROUP_USERNAME = TelegramMembershipPolicy.GROUP_URL.substringAfterLast('/')

/**
 * `banned` and `left` are refusals; an unrecognised status is one too, so a
 * state TDLib adds later cannot silently unlock the group. `restricted` counts
 * as membership: the listener is in the group, only some permissions are
 * withheld.
 */
private val MemberStatus.isMembership: Boolean
    get() = when (this) {
        MemberStatus.MEMBER,
        MemberStatus.ADMINISTRATOR,
        MemberStatus.CREATOR,
        MemberStatus.RESTRICTED -> true

        MemberStatus.BANNED,
        MemberStatus.LEFT,
        MemberStatus.UNKNOWN -> false
    }

// ---- TDLib mapping. Pure functions over TdApi DTOs: no native call, so the
// ---- rules below are exercised directly by JVM tests.

internal fun TdApi.ChatMemberStatus?.toMemberStatus(): MemberStatus = when (this) {
    is TdApi.ChatMemberStatusMember -> MemberStatus.MEMBER
    is TdApi.ChatMemberStatusAdministrator -> MemberStatus.ADMINISTRATOR
    is TdApi.ChatMemberStatusCreator -> MemberStatus.CREATOR
    is TdApi.ChatMemberStatusRestricted -> MemberStatus.RESTRICTED
    is TdApi.ChatMemberStatusBanned -> MemberStatus.BANNED
    is TdApi.ChatMemberStatusLeft -> MemberStatus.LEFT
    else -> MemberStatus.UNKNOWN
}

/** The post's own words: the body of a text post, or the caption of a media one. */
internal fun TdApi.MessageContent?.toPostText(): String = when (this) {
    is TdApi.MessageText -> text?.text.orEmpty()
    is TdApi.MessageAudio -> caption?.text.orEmpty()
    is TdApi.MessageDocument -> caption?.text.orEmpty()
    else -> ""
}

/**
 * The audio of one content, or null when the content carries none. A document
 * counts as audio too: Telegram delivers some audiobooks that way.
 */
internal fun TdApi.MessageContent?.toTelegramAudio(): TelegramAudio? = when (this) {
    is TdApi.MessageAudio -> TelegramAudio(
        fileName = audio?.fileName,
        title = audio?.title,
        performer = audio?.performer,
        durationSeconds = audio?.duration ?: 0
    )

    is TdApi.MessageDocument -> TelegramAudio(fileName = document?.fileName)

    else -> null
}

/** The linked post, or null when the link resolves to no readable message. */
internal fun TdApi.MessageLinkInfo?.toTelegramMessage(): TelegramMessage? {
    val message = this?.message ?: return null
    val content = message.content

    return TelegramMessage(
        id = message.id.toString(),
        text = content.toPostText(),
        audio = listOfNotNull(content.toTelegramAudio())
    )
}

/**
 * The production [TdlibClient]: a thin adapter over the pinned TDLib `Client`.
 *
 * It holds NO secret — the api id/hash, the phone number and the login code
 * reach TDLib through the login flow, exactly as in `TelegramLoginSpikeTest`;
 * this class only turns the callback API into suspending calls.
 *
 * TDLib's `send` must never be called from two threads at once, so every call
 * goes through one [Mutex]. The lock is held until the result arrives, which
 * also keeps the calls in the order the caller made them.
 */
class TdlibClientAdapter(
    private val client: Client
) : TdlibClient {

    private val gate = Mutex()

    override suspend fun currentUserId(): Long? =
        request<TdApi.User>(TdApi.GetMe())?.id

    override suspend fun publicChatId(username: String): Long? =
        request<TdApi.Chat>(TdApi.SearchPublicChat(username))?.id

    override suspend fun memberStatus(chatId: Long, userId: Long): MemberStatus? =
        request<TdApi.ChatMember>(TdApi.GetChatMember(chatId, TdApi.MessageSenderUser(userId)))
            ?.status
            ?.toMemberStatus()

    override suspend fun message(link: String): TelegramMessage? =
        request<TdApi.MessageLinkInfo>(TdApi.GetMessageLinkInfo(link))
            .toTelegramMessage()

    private suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>): T? =
        gate.withLock {
            suspendCancellableCoroutine<T?> { continuation ->
                client.send(function) { result ->
                    if (result is TdApi.Error) {
                        continuation.resumeWith(Result.success(null))
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resumeWith(Result.success(result as T))
                    }
                }
            }
        }
}
