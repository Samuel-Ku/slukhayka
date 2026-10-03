package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity

/** Stable Chapter identity carried across a read and a later queue installation. */
data class AnchoredProgress(val progress: PlaybackProgressEntity, val chapterId: String?)
data class AnchoredPlaybackEvent(val event: PlaybackEventEntity, val chapterId: String?)
