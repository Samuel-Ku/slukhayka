package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.metadata.decodeTitleEntities

/** Display-only decoding: stored metadata and identity keys stay unchanged. */
fun displayBookTitle(value: String): String = decodeTitleEntities(value)
