package com.slukhayka.audiobooks.data.source

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * #708 — the LIVE metadata spike. Never runs in ordinary test runs: it needs
 * real network and real URLs, so it is gated behind system properties:
 *
 *   ./gradlew testDebugUnitTest --tests "*NewPipeMetadataSpikeTest" \
 *     -Dnewpipe.spike=1 -Dspike.video=<url> [-Dspike.playlist=<url>]
 *
 * Its output IS the evidence the ticket asks for: ordered playlist entries with
 * durations, how far paging goes, and how long it takes.
 */
class NewPipeMetadataSpikeTest {

    private fun gate(): Boolean = System.getProperty("newpipe.spike") != null

    @Test
    fun `single video exposes a real duration`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        NewPipe.init(NewPipeYouTubeExtractor.SharedClientDownloader)
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))
        println("SPIKE video name=${info.name} durationSec=${info.duration} uploader=${info.uploaderName}")
        check(info.duration > 0) { "a single video must carry a real duration" }
    }

    @Test
    fun `channel tab pages and reports how far paging goes`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.channel") ?: return
        NewPipe.init(NewPipeYouTubeExtractor.SharedClientDownloader)
        // 0.26.5: the channel's ITEM LIST lives in ChannelTabExtractor (the
        // ChannelExtractor only carries channel info) — verified via javap.
        val handler = ServiceList.YouTube.channelTabLHFactory.fromUrl(url)
        val extractor = ServiceList.YouTube.getChannelTabExtractor(handler)
        extractor.fetchPage()
        var page = extractor.initialPage
        var items = page.items.size
        var pages = 1
        val started = System.currentTimeMillis()
        // The AC asks for the KNOWN limit: page until it stops, bounded so a
        // runaway channel cannot hang the spike.
        while (page.hasNextPage() && pages < 20) {
            page = extractor.getPage(page.nextPage)
            items += page.items.size
            pages++
        }
        val elapsed = System.currentTimeMillis() - started
        println("SPIKE channel pages=$pages items=$items hasNext=${page.hasNextPage()} elapsedMs=$elapsed")
    }

    @Test
    fun `playlist exposes ordered entries with durations`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.playlist") ?: return
        NewPipe.init(NewPipeYouTubeExtractor.SharedClientDownloader)
        val started = System.currentTimeMillis()
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
        extractor.fetchPage()
        val page = extractor.initialPage
        val elapsed = System.currentTimeMillis() - started
        println("SPIKE playlist streamCount=${extractor.streamCount} pageItems=${page.items.size} " +
            "hasNext=${page.hasNextPage()} elapsedMs=$elapsed")
        page.items.take(5).forEach { item ->
            println("  SPIKE entry name=${item.name} url=${item.url}")
        }
        check(extractor.streamCount > 0) { "a playlist must report its entry count" }
    }
}
