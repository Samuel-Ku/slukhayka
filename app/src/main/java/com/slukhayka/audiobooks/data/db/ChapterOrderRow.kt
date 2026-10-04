package com.slukhayka.audiobooks.data.db

import androidx.room.Embedded

/** A single-query view: chapters and their order memory cannot come from different commits. */
data class ChapterOrderRow(@Embedded val chapter: ChapterEntity, val orderMemory: String?)
