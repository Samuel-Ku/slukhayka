package com.slukhayka.audiobooks

import android.content.Context
import android.content.Intent
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.imports.KnownBookIdentity

/**
 * ADR-0052 §6/§7 — the ONE builder for every notification tap.
 *
 * The producers live in different packages (downloads, person bookmarks,
 * source watch, playback), but the activity reads a single extras contract.
 * Building the intents here keeps producer and consumer on the same symbols:
 * [NotificationIntentContractTest] feeds each builder through
 * [routeNotificationIntent] — the activity's real reader — so renaming a key
 * on one side breaks the test instead of a notification tap.
 */
internal object NotificationIntents {

    private val tapFlags =
        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

    /** Download progress and Source Watch — the ordinary book-detail door. */
    fun bookDetail(context: Context, bookId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = tapFlags
            putExtra("bookId", bookId)
            putExtra("openBookDetail", true)
        }

    /** ADR-0052 §6 — the Media3 service notification opens the full player. */
    fun openPlayer(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        }

    /** ADR-0052 §6 — «Новинки від людей» carries the person it names. */
    fun peopleNew(context: Context, name: String?, role: PersonRole?): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = tapFlags
            putExtra(MainActivity.EXTRA_OPEN_PEOPLE_NEW, true)
            if (name != null && role != null) {
                putExtra(MainActivity.EXTRA_PEOPLE_NEW_NAME, name)
                putExtra(MainActivity.EXTRA_PEOPLE_NEW_ROLE, role.name)
            }
        }

    /**
     * ADR-0052 §7 — a watched Work with no Library Entry imports through the
     * ordinary door. [identity] only enables the shared-profile read-skip; an
     * absent one still imports by live fetch.
     */
    fun sourceWork(
        context: Context,
        sourceId: String,
        sourceUrl: String,
        identity: KnownBookIdentity?
    ): Intent = Intent(context, MainActivity::class.java).apply {
        flags = tapFlags
        putExtra(MainActivity.EXTRA_OPEN_SOURCE_WORK, true)
        putExtra(MainActivity.EXTRA_SOURCE_ID, sourceId)
        putExtra(MainActivity.EXTRA_SOURCE_URL, sourceUrl)
        if (identity != null) {
            putExtra(MainActivity.EXTRA_SOURCE_WORK_TITLE, identity.title)
            putExtra(MainActivity.EXTRA_SOURCE_WORK_AUTHOR, identity.author)
            putExtra(MainActivity.EXTRA_SOURCE_WORK_NARRATOR, identity.narrator)
        }
    }
}

/** What a notification tap asks the activity to do (ADR-0052 §6/§7). */
internal data class NotificationRoute(
    val openPeopleNew: Boolean = false,
    val peopleNewName: String? = null,
    val peopleNewRole: PersonRole? = null,
    val openPlayer: Boolean = false,
    val bookId: String? = null,
    val sourceImport: PendingSourceImport? = null
)

/**
 * The activity's extras contract as a pure function, so JVM tests exercise
 * the real reader instead of a copy of its key list.
 *
 * Degradations are deliberate and unchanged: an absent or malformed role
 * still opens the people feed (without inventing a person page), and a source
 * target missing its id or URL is dropped.
 */
internal fun routeNotificationIntent(intent: Intent): NotificationRoute {
    val openPeopleNew = intent.getBooleanExtra(MainActivity.EXTRA_OPEN_PEOPLE_NEW, false)
    val sourceId = intent.getStringExtra(MainActivity.EXTRA_SOURCE_ID).orEmpty()
    val sourceUrl = intent.getStringExtra(MainActivity.EXTRA_SOURCE_URL).orEmpty()
    val sourceTitle = intent.getStringExtra(MainActivity.EXTRA_SOURCE_WORK_TITLE).orEmpty()
    return NotificationRoute(
        openPeopleNew = openPeopleNew,
        peopleNewName = if (openPeopleNew) {
            intent.getStringExtra(MainActivity.EXTRA_PEOPLE_NEW_NAME)?.takeIf { it.isNotBlank() }
        } else null,
        peopleNewRole = if (openPeopleNew) {
            intent.getStringExtra(MainActivity.EXTRA_PEOPLE_NEW_ROLE)
                ?.let { raw -> runCatching { PersonRole.valueOf(raw) }.getOrNull() }
        } else null,
        openPlayer = intent.getBooleanExtra(MainActivity.EXTRA_OPEN_PLAYER, false),
        bookId = intent.getStringExtra("bookId")
            ?.takeIf { intent.getBooleanExtra("openBookDetail", false) },
        sourceImport = if (
            intent.getBooleanExtra(MainActivity.EXTRA_OPEN_SOURCE_WORK, false) &&
            sourceId.isNotBlank() && sourceUrl.isNotBlank()
        ) {
            PendingSourceImport(
                sourceId = sourceId,
                sourceUrl = sourceUrl,
                identity = sourceTitle.takeIf { it.isNotBlank() }?.let {
                    KnownBookIdentity(
                        title = it,
                        author = intent.getStringExtra(MainActivity.EXTRA_SOURCE_WORK_AUTHOR).orEmpty(),
                        narrator = intent.getStringExtra(MainActivity.EXTRA_SOURCE_WORK_NARRATOR).orEmpty()
                    )
                }
            )
        } else null
    )
}
