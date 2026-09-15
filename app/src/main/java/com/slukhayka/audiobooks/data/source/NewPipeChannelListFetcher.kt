package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.ingest.ChannelItemKind
import com.slukhayka.audiobooks.data.ingest.ChannelListFetcher
import com.slukhayka.audiobooks.data.ingest.ChannelListItem
import com.slukhayka.audiobooks.data.ingest.ChannelPage
import com.slukhayka.audiobooks.data.ingest.ChannelTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Spec-53 T10 — the production channel read: NewPipe's channel tabs, one
 * honest page at a time. The `/videos` and `/playlists` tabs are separate
 * doors (YouTube has no mixed channel tab); paging follows NewPipe's opaque
 * cursors, so [ChannelPage.hasMore] is the engine's own "there is more",
 * never a guessed page count.
 *
 * One instance browses one open card (tab and cursor are instance state);
 * every method runs on IO and returns null on any engine failure — the card
 * reports the honest refusal instead of a half-read tab.
 */
class NewPipeChannelListFetcher : ChannelListFetcher {

    override var openTab: ChannelTab? = null
        private set

    private var tabHandler: ListLinkHandler? = null
    private var nextPage: Page? = null
    private var channelUrl: String = ""

    override suspend fun openVideos(channelUrl: String): ChannelPage? =
        openTab(channelUrl, ChannelTab.VIDEOS)

    override suspend fun openPlaylists(channelUrl: String): ChannelPage? =
        openTab(channelUrl, ChannelTab.PLAYLISTS)

    override suspend fun more(): ChannelPage? {
        val handler = tabHandler ?: return null
        val page = nextPage ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                NewPipeYouTubeExtractor.ensureInitialized()
                val itemsPage = ChannelTabInfo.getMoreItems(ServiceList.YouTube, handler, page)
                nextPage = itemsPage.nextPage
                ChannelPage(
                    channelTitle = "",
                    channelUrl = channelUrl,
                    items = itemsPage.items.mapNotNull { it.toListItem() },
                    hasMore = itemsPage.hasNextPage()
                )
            }.getOrNull()
        }
    }

    private suspend fun openTab(channelUrl: String, tab: ChannelTab): ChannelPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                NewPipeYouTubeExtractor.ensureInitialized()
                this@NewPipeChannelListFetcher.channelUrl = channelUrl
                val info = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl)
                val handler = info.tabs.firstOrNull { handler ->
                    (handler.url ?: "").trimEnd('/').endsWith("/" + tabSuffix(tab))
                } ?: return@runCatching null
                val tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, handler)
                tabHandler = handler
                openTab = tab
                nextPage = tabInfo.nextPage
                ChannelPage(
                    channelTitle = info.name.orEmpty(),
                    channelUrl = channelUrl,
                    items = tabInfo.relatedItems.mapNotNull { it.toListItem() },
                    hasMore = tabInfo.hasNextPage()
                )
            }.getOrNull()
        }

    private fun tabSuffix(tab: ChannelTab): String =
        if (tab == ChannelTab.VIDEOS) ChannelTabs.VIDEOS else ChannelTabs.PLAYLISTS

    private fun InfoItem.toListItem(): ChannelListItem? {
        val title = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when (this) {
            is StreamInfoItem -> {
                val videoId = watchIdOf(url) ?: return null
                ChannelListItem(
                    id = videoId,
                    kind = ChannelItemKind.VIDEO,
                    title = title,
                    url = YouTubeTracks.watchUrlOf(videoId),
                    durationSeconds = duration.takeIf { it > 0 }
                )
            }
            is PlaylistInfoItem -> {
                val playlistId = playlistIdOf(url) ?: return null
                ChannelListItem(
                    id = playlistId,
                    kind = ChannelItemKind.PLAYLIST,
                    title = title,
                    url = "https://www.youtube.com/playlist?list=$playlistId",
                    durationSeconds = null
                )
            }
            else -> null
        }
    }

    private fun watchIdOf(watchUrl: String): String? =
        watchUrl.substringAfter("v=", "").substringBefore('&').takeIf { it.isNotBlank() }
            ?: watchUrl.substringAfter("youtu.be/", "").substringBefore('?').takeIf { it.isNotBlank() }

    private fun playlistIdOf(playlistUrl: String?): String? =
        playlistUrl?.substringAfter("list=", "")?.substringBefore('&')?.takeIf { it.isNotBlank() }
}
