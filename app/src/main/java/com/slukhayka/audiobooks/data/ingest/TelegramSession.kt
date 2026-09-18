package com.slukhayka.audiobooks.data.ingest

/**
 * #829 — the narrow view of the device's Telegram session that the fetcher needs.
 *
 * Deliberately free of any TDLib type: the implementation lives in
 * [TdLibTelegramSession], everything above this seam is pure Kotlin and testable
 * with a fake. The session belongs to THIS device only (proven by the spike:
 * `files/tdlib/td.binlog` in the app's private storage) and the listener can
 * leave it at any time.
 */
interface TelegramSession {

    /** Whether the logged-in account is in the community group. */
    suspend fun isMember(): Boolean

    /**
     * One message by its canonical link (`t.me/<group>/<id>`), or null when the
     * message cannot be read at all — never a half-invented message.
     */
    suspend fun message(link: String): TelegramMessage?
}

/** A message as the fetcher sees it: text plus the audio it carries. */
data class TelegramMessage(
    val id: String,
    val text: String,
    val audio: List<TelegramAudio> = emptyList()
)

/** One audio file of a message. */
data class TelegramAudio(
    val fileName: String? = null,
    val title: String? = null,
    val performer: String? = null,
    val durationSeconds: Int = 0
)

/**
 * #829 — the message becomes the SAME [ListenerSubmissionFlow.TgIdentity] the
 * preview-based path already produces, so nothing downstream changes.
 *
 * Honesty rules (ADR-0014): the title is what the post actually says; an author
 * or a narrator is taken only when the post names one in a labelled line
 * («Автор: …», «Читає: …») — otherwise they stay null instead of being guessed
 * from prose. A post with no usable title yields null: the caller refuses.
 */
object TelegramMessageMapper {

    private const val MAX_TITLE = 200
    private val AUTHOR = Regex("""(?imu)^\s*автор\s*[:\-–]\s*(.+)$""")
    private val NARRATOR = Regex("""(?imu)^\s*(?:читає|начитує|виконує)\s*[:\-–]\s*(.+)$""")

    fun identityFrom(message: TelegramMessage): ListenerSubmissionFlow.TgIdentity? {
        val title = message.audio.firstNotNullOfOrNull { it.title?.trim()?.takeIf(String::isNotBlank) }
            ?: message.text.lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?.take(MAX_TITLE)
            ?: return null

        return ListenerSubmissionFlow.TgIdentity(
            title = title,
            author = AUTHOR.find(message.text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank),
            narrator = NARRATOR.find(message.text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank),
            coverUrl = null,
            description = message.text.trim().takeIf(String::isNotBlank)
        )
    }
}
