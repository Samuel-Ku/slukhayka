package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec-53 T10 — a whole channel from a listener paste: the channel is read
 * page by page, the listener ticks videos and/or playlists, and every picked
 * item walks the ORDINARY submission path (local import → playback verdict →
 * publication), so each item keeps its own honest verdict. Nothing here
 * publishes by itself.
 *
 * Pure JVM: the channel read, the membership read, the pause and the clock
 * are injected seams — deterministic tests without sleeping or binaries.
 */
enum class ChannelItemKind { VIDEO, PLAYLIST }

/** One tickable row of the channel card: a video (one chapter) or a playlist. */
data class ChannelListItem(
    val id: String,
    val kind: ChannelItemKind,
    val title: String,
    /** The canonical URL this item is submitted with (watch / playlist). */
    val url: String,
    val durationSeconds: Long? = null
)

/** One honestly-paged slice of a channel tab. */
data class ChannelPage(
    val channelTitle: String,
    val channelUrl: String,
    val items: List<ChannelListItem>,
    /** False at the honest end — no "more" door is shown then. */
    val hasMore: Boolean
)

/** Which channel tab is open in the card. */
enum class ChannelTab { VIDEOS, PLAYLISTS }

/**
 * The channel read seam. Stateful by necessity — NewPipe pages carry opaque
 * cursors, not numbers — so one instance browses one open card: open a tab,
 * then [more] while [ChannelPage.hasMore] holds. Production:
 * [com.slukhayka.audiobooks.data.source.NewPipeChannelListFetcher].
 */
interface ChannelListFetcher {
    val openTab: ChannelTab?
    suspend fun openVideos(channelUrl: String): ChannelPage?
    suspend fun openPlaylists(channelUrl: String): ChannelPage?
    suspend fun more(): ChannelPage?
}

/**
 * Spec-53 T10 — the pure selection rule. Playlists always go in whole; a
 * ticked video that already rides inside a ticked playlist stays OUT unless
 * the listener explicitly asks for the skipped ones back. Membership is
 * matched on canonical watch URLs — the same strings the import door
 * materialises — so the dedup is real, not string luck.
 */
object ChannelSelectionPolicy {

    data class ResolvedSelection(
        /** Exactly what will be submitted, playlists first, then videos. */
        val toAdd: List<ChannelListItem>,
        /** Ticked videos held back by membership — shown as "пропущено N". */
        val skippedVideoIds: List<String>
    )

    fun resolve(
        selected: List<ChannelListItem>,
        /** Playlist item id → canonical watch URLs of its members. */
        playlistMembers: Map<String, Set<String>>,
        includeSkipped: Boolean
    ): ResolvedSelection {
        val playlists = selected.filter { it.kind == ChannelItemKind.PLAYLIST }
        val memberUrls = playlists
            .flatMap { playlistMembers[it.id].orEmpty() }
            .toSet()
        val videos = selected.filter { it.kind == ChannelItemKind.VIDEO }
        val (heldBack, free) = videos.partition { it.url in memberUrls }
        return ResolvedSelection(
            toAdd = playlists + if (includeSkipped) videos else free,
            skippedVideoIds = if (includeSkipped) emptyList() else heldBack.map { it.id }
        )
    }

    /** "Останні N" in one tap — the freshest rows of the listed tab. */
    fun lastN(items: List<ChannelListItem>, n: Int): List<ChannelListItem> =
        if (n <= 0) emptyList() else items.take(n)
}

/**
 * Spec-53 T10 — the paced walk over the resolved selection. Every item goes
 * through [submit] (the ordinary door: verdict and publication stay
 * per-item), every request waits the human rhythm first, and [progress]
 * tells the card where the walk is. Stopping is the caller's coroutine
 * cancellation — [ensureActive] checkpoints make it land between items, so
 * a stopped walk never leaves a half-submitted item.
 */
class ChannelImportSession(
    private val submit: suspend (url: String) -> ListenerSubmissionFlow.Start,
    private val pacing: PacingPolicy,
    private val pauseMillis: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    data class Progress(val done: Int, val total: Int, val currentTitle: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    suspend fun run(items: List<ChannelListItem>): List<ListenerSubmissionFlow.Start> {
        val results = mutableListOf<ListenerSubmissionFlow.Start>()
        try {
            items.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                if (index > 0) pauseMillis(pacing.nextPauseMillis())
                while (!pacing.allowsRequest(CHANNEL_DOMAIN, nowMillis())) {
                    pauseMillis(pacing.nextPauseMillis())
                }
                _progress.value = Progress(index, items.size, item.title)
                results += submit(item.url)
                _progress.value = Progress(index + 1, items.size, item.title)
            }
        } finally {
            _progress.value = null
        }
        return results
    }

    companion object {
        const val CHANNEL_DOMAIN = "youtube.com"
    }
}

/**
 * Spec-53 T10 — the whole state of one open channel card. Framework-free on
 * purpose: the ViewModel holds it, the composable renders it, and JVM tests
 * could drive it without Compose. The checked rows resolve through
 * [ChannelSelectionPolicy] at render/start time, so the state never stores a
 * second copy of the selection.
 */
data class ChannelCardState(
    val url: String = "",
    val title: String = "",
    val tab: ChannelTab = ChannelTab.VIDEOS,
    val items: List<ChannelListItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val checkedIds: Set<String> = emptySet(),
    val includeSkipped: Boolean = false,
    val playlistMembers: Map<String, Set<String>> = emptyMap(),
    val membersLoading: Set<String> = emptySet(),
    val progress: ChannelImportSession.Progress? = null,
    val running: Boolean = false,
    /** Null until a run finishes: how many of the resolved items imported. */
    val doneAdded: Int? = null,
    val doneTotal: Int = 0,
    val doneStopped: Boolean = false
)
