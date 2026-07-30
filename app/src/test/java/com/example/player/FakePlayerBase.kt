package com.example.player

import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size

/**
 * Exhaustive, deliberately inert implementation of Media3's [Player] interface.
 *
 * Media3 1.3.1 declares 128 abstract members on [Player]; `AudioPlayerManager`
 * touches only 12 of them. Rather than let a fake silently return `0`/`false`
 * for the other 116 -- which would turn a genuine production regression into a
 * green test -- every unused member throws [UnsupportedOperationException].
 *
 * If a future change to `AudioPlayerManager` starts calling a new `Player`
 * method, the test suite fails loudly with the method name instead of quietly
 * exercising a fiction. Override the member in [FakePlayerEngine] at that point.
 *
 * The 12 members `AudioPlayerManager` actually calls (all overridden in
 * [FakePlayerEngine]):
 *
 * | Member                    | Call site                                  |
 * |---------------------------|--------------------------------------------|
 * | `setMediaItem`            | `prepareChapter` (local file or stream URL) |
 * | `addListener`             | `prepareChapter`                            |
 * | `removeListener`          | (symmetry; not used yet, kept usable)       |
 * | `prepare`                 | `prepareChapter`                            |
 * | `play`                    | `prepareChapter` listener, `play`           |
 * | `pause`                   | `pause`                                     |
 * | `seekTo(Long)`            | `prepareChapter` listener, `seekTo`         |
 * | `isPlaying`               | `pause`, `startProgressTracker`             |
 * | `getCurrentPosition`      | `startProgressTracker`                      |
 * | `getDuration`             | `prepareChapter` listener                   |
 * | `setPlaybackParameters`   | `applyPlaybackSpeed`                        |
 * | `setVolume` / `getVolume` | `setSleepTimer` fade-out                    |
 * | `release`                 | `prepareChapter`, `tryFallbackPlayback`     |
 *
 * See GitHub issue #4.
 */
// Media3's Player interface still carries pre-1.0 members (hasNext/previous/window
// accessors). We must implement them, but we never call them.
@Suppress("DEPRECATION")
abstract class FakePlayerBase : Player {

    protected fun unsupported(member: String): Nothing =
        throw UnsupportedOperationException(
            "FakePlayerEngine does not implement Player.$member(). " +
                "AudioPlayerManager was not expected to call it -- either the " +
                "production code changed, or the fake needs to grow an override."
        )

    override fun getApplicationLooper(): Looper = unsupported("getApplicationLooper")
    override fun addListener(p0: Player.Listener): Unit = unsupported("addListener")
    override fun removeListener(p0: Player.Listener): Unit = unsupported("removeListener")
    override fun setMediaItems(p0: List<MediaItem>): Unit = unsupported("setMediaItems")
    override fun setMediaItems(p0: List<MediaItem>, p1: Boolean): Unit = unsupported("setMediaItems")
    override fun setMediaItems(p0: List<MediaItem>, p1: Int, p2: Long): Unit = unsupported("setMediaItems")
    override fun setMediaItem(p0: MediaItem): Unit = unsupported("setMediaItem")
    override fun setMediaItem(p0: MediaItem, p1: Long): Unit = unsupported("setMediaItem")
    override fun setMediaItem(p0: MediaItem, p1: Boolean): Unit = unsupported("setMediaItem")
    override fun addMediaItem(p0: MediaItem): Unit = unsupported("addMediaItem")
    override fun addMediaItem(p0: Int, p1: MediaItem): Unit = unsupported("addMediaItem")
    override fun addMediaItems(p0: List<MediaItem>): Unit = unsupported("addMediaItems")
    override fun addMediaItems(p0: Int, p1: List<MediaItem>): Unit = unsupported("addMediaItems")
    override fun moveMediaItem(p0: Int, p1: Int): Unit = unsupported("moveMediaItem")
    override fun moveMediaItems(p0: Int, p1: Int, p2: Int): Unit = unsupported("moveMediaItems")
    override fun replaceMediaItem(p0: Int, p1: MediaItem): Unit = unsupported("replaceMediaItem")
    override fun replaceMediaItems(p0: Int, p1: Int, p2: List<MediaItem>): Unit = unsupported("replaceMediaItems")
    override fun removeMediaItem(p0: Int): Unit = unsupported("removeMediaItem")
    override fun removeMediaItems(p0: Int, p1: Int): Unit = unsupported("removeMediaItems")
    override fun clearMediaItems(): Unit = unsupported("clearMediaItems")
    override fun isCommandAvailable(p0: Int): Boolean = unsupported("isCommandAvailable")
    override fun canAdvertiseSession(): Boolean = unsupported("canAdvertiseSession")
    override fun getAvailableCommands(): Player.Commands = unsupported("getAvailableCommands")
    override fun prepare(): Unit = unsupported("prepare")
    override fun getPlaybackState(): Int = unsupported("getPlaybackState")
    override fun getPlaybackSuppressionReason(): Int = unsupported("getPlaybackSuppressionReason")
    override fun isPlaying(): Boolean = unsupported("isPlaying")
    override fun getPlayerError(): PlaybackException? = unsupported("getPlayerError")
    override fun play(): Unit = unsupported("play")
    override fun pause(): Unit = unsupported("pause")
    override fun setPlayWhenReady(p0: Boolean): Unit = unsupported("setPlayWhenReady")
    override fun getPlayWhenReady(): Boolean = unsupported("getPlayWhenReady")
    override fun setRepeatMode(p0: Int): Unit = unsupported("setRepeatMode")
    override fun getRepeatMode(): Int = unsupported("getRepeatMode")
    override fun setShuffleModeEnabled(p0: Boolean): Unit = unsupported("setShuffleModeEnabled")
    override fun getShuffleModeEnabled(): Boolean = unsupported("getShuffleModeEnabled")
    override fun isLoading(): Boolean = unsupported("isLoading")
    override fun seekToDefaultPosition(): Unit = unsupported("seekToDefaultPosition")
    override fun seekToDefaultPosition(p0: Int): Unit = unsupported("seekToDefaultPosition")
    override fun seekTo(p0: Long): Unit = unsupported("seekTo")
    override fun seekTo(p0: Int, p1: Long): Unit = unsupported("seekTo")
    override fun getSeekBackIncrement(): Long = unsupported("getSeekBackIncrement")
    override fun seekBack(): Unit = unsupported("seekBack")
    override fun getSeekForwardIncrement(): Long = unsupported("getSeekForwardIncrement")
    override fun seekForward(): Unit = unsupported("seekForward")
    override fun hasPrevious(): Boolean = unsupported("hasPrevious")
    override fun hasPreviousWindow(): Boolean = unsupported("hasPreviousWindow")
    override fun hasPreviousMediaItem(): Boolean = unsupported("hasPreviousMediaItem")
    override fun previous(): Unit = unsupported("previous")
    override fun seekToPreviousWindow(): Unit = unsupported("seekToPreviousWindow")
    override fun seekToPreviousMediaItem(): Unit = unsupported("seekToPreviousMediaItem")
    override fun getMaxSeekToPreviousPosition(): Long = unsupported("getMaxSeekToPreviousPosition")
    override fun seekToPrevious(): Unit = unsupported("seekToPrevious")
    override fun hasNext(): Boolean = unsupported("hasNext")
    override fun hasNextWindow(): Boolean = unsupported("hasNextWindow")
    override fun hasNextMediaItem(): Boolean = unsupported("hasNextMediaItem")
    override fun next(): Unit = unsupported("next")
    override fun seekToNextWindow(): Unit = unsupported("seekToNextWindow")
    override fun seekToNextMediaItem(): Unit = unsupported("seekToNextMediaItem")
    override fun seekToNext(): Unit = unsupported("seekToNext")
    override fun setPlaybackParameters(p0: PlaybackParameters): Unit = unsupported("setPlaybackParameters")
    override fun setPlaybackSpeed(p0: Float): Unit = unsupported("setPlaybackSpeed")
    override fun getPlaybackParameters(): PlaybackParameters = unsupported("getPlaybackParameters")
    override fun stop(): Unit = unsupported("stop")
    override fun release(): Unit = unsupported("release")
    override fun getCurrentTracks(): Tracks = unsupported("getCurrentTracks")
    override fun getTrackSelectionParameters(): TrackSelectionParameters = unsupported("getTrackSelectionParameters")
    override fun setTrackSelectionParameters(p0: TrackSelectionParameters): Unit = unsupported("setTrackSelectionParameters")
    override fun getMediaMetadata(): MediaMetadata = unsupported("getMediaMetadata")
    override fun getPlaylistMetadata(): MediaMetadata = unsupported("getPlaylistMetadata")
    override fun setPlaylistMetadata(p0: MediaMetadata): Unit = unsupported("setPlaylistMetadata")
    override fun getCurrentManifest(): Any? = unsupported("getCurrentManifest")
    override fun getCurrentTimeline(): Timeline = unsupported("getCurrentTimeline")
    override fun getCurrentPeriodIndex(): Int = unsupported("getCurrentPeriodIndex")
    override fun getCurrentWindowIndex(): Int = unsupported("getCurrentWindowIndex")
    override fun getCurrentMediaItemIndex(): Int = unsupported("getCurrentMediaItemIndex")
    override fun getNextWindowIndex(): Int = unsupported("getNextWindowIndex")
    override fun getNextMediaItemIndex(): Int = unsupported("getNextMediaItemIndex")
    override fun getPreviousWindowIndex(): Int = unsupported("getPreviousWindowIndex")
    override fun getPreviousMediaItemIndex(): Int = unsupported("getPreviousMediaItemIndex")
    override fun getCurrentMediaItem(): MediaItem? = unsupported("getCurrentMediaItem")
    override fun getMediaItemCount(): Int = unsupported("getMediaItemCount")
    override fun getMediaItemAt(p0: Int): MediaItem = unsupported("getMediaItemAt")
    override fun getDuration(): Long = unsupported("getDuration")
    override fun getCurrentPosition(): Long = unsupported("getCurrentPosition")
    override fun getBufferedPosition(): Long = unsupported("getBufferedPosition")
    override fun getBufferedPercentage(): Int = unsupported("getBufferedPercentage")
    override fun getTotalBufferedDuration(): Long = unsupported("getTotalBufferedDuration")
    override fun isCurrentWindowDynamic(): Boolean = unsupported("isCurrentWindowDynamic")
    override fun isCurrentMediaItemDynamic(): Boolean = unsupported("isCurrentMediaItemDynamic")
    override fun isCurrentWindowLive(): Boolean = unsupported("isCurrentWindowLive")
    override fun isCurrentMediaItemLive(): Boolean = unsupported("isCurrentMediaItemLive")
    override fun getCurrentLiveOffset(): Long = unsupported("getCurrentLiveOffset")
    override fun isCurrentWindowSeekable(): Boolean = unsupported("isCurrentWindowSeekable")
    override fun isCurrentMediaItemSeekable(): Boolean = unsupported("isCurrentMediaItemSeekable")
    override fun isPlayingAd(): Boolean = unsupported("isPlayingAd")
    override fun getCurrentAdGroupIndex(): Int = unsupported("getCurrentAdGroupIndex")
    override fun getCurrentAdIndexInAdGroup(): Int = unsupported("getCurrentAdIndexInAdGroup")
    override fun getContentDuration(): Long = unsupported("getContentDuration")
    override fun getContentPosition(): Long = unsupported("getContentPosition")
    override fun getContentBufferedPosition(): Long = unsupported("getContentBufferedPosition")
    override fun getAudioAttributes(): AudioAttributes = unsupported("getAudioAttributes")
    override fun setVolume(p0: Float): Unit = unsupported("setVolume")
    override fun getVolume(): Float = unsupported("getVolume")
    override fun clearVideoSurface(): Unit = unsupported("clearVideoSurface")
    override fun clearVideoSurface(p0: Surface?): Unit = unsupported("clearVideoSurface")
    override fun setVideoSurface(p0: Surface?): Unit = unsupported("setVideoSurface")
    override fun setVideoSurfaceHolder(p0: SurfaceHolder?): Unit = unsupported("setVideoSurfaceHolder")
    override fun clearVideoSurfaceHolder(p0: SurfaceHolder?): Unit = unsupported("clearVideoSurfaceHolder")
    override fun setVideoSurfaceView(p0: SurfaceView?): Unit = unsupported("setVideoSurfaceView")
    override fun clearVideoSurfaceView(p0: SurfaceView?): Unit = unsupported("clearVideoSurfaceView")
    override fun setVideoTextureView(p0: TextureView?): Unit = unsupported("setVideoTextureView")
    override fun clearVideoTextureView(p0: TextureView?): Unit = unsupported("clearVideoTextureView")
    override fun getVideoSize(): VideoSize = unsupported("getVideoSize")
    override fun getSurfaceSize(): Size = unsupported("getSurfaceSize")
    override fun getCurrentCues(): CueGroup = unsupported("getCurrentCues")
    override fun getDeviceInfo(): DeviceInfo = unsupported("getDeviceInfo")
    override fun getDeviceVolume(): Int = unsupported("getDeviceVolume")
    override fun isDeviceMuted(): Boolean = unsupported("isDeviceMuted")
    override fun setDeviceVolume(p0: Int): Unit = unsupported("setDeviceVolume")
    override fun setDeviceVolume(p0: Int, p1: Int): Unit = unsupported("setDeviceVolume")
    override fun increaseDeviceVolume(): Unit = unsupported("increaseDeviceVolume")
    override fun increaseDeviceVolume(p0: Int): Unit = unsupported("increaseDeviceVolume")
    override fun decreaseDeviceVolume(): Unit = unsupported("decreaseDeviceVolume")
    override fun decreaseDeviceVolume(p0: Int): Unit = unsupported("decreaseDeviceVolume")
    override fun setDeviceMuted(p0: Boolean): Unit = unsupported("setDeviceMuted")
    override fun setDeviceMuted(p0: Boolean, p1: Int): Unit = unsupported("setDeviceMuted")
    override fun setAudioAttributes(p0: AudioAttributes, p1: Boolean): Unit = unsupported("setAudioAttributes")
}
