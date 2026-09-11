package com.slukhayka.audiobooks.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Spec-49 follow-up — the file-backed Work index survives restarts: a
 * round-trip keeps every entry and the refresh stamp; a corrupt file is a
 * miss, never a crash.
 */
class WorkIndexStoreTest {

    @Test
    fun `a saved index round-trips entries and timestamp`() {
        val file = File.createTempFile("work-index", ".tsv").also { it.delete() }
        try {
            val store = WorkIndexStore(file)
            val index = PersistedWorkIndex(
                entries = listOf(
                    CatalogIndexEntry("chytaylo", "https://chytaylo.com.ua/books/siddhartha", "siddhartha"),
                    CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/audioknyha-kobzar/", "", "кобзар|тарас шевченко")
                ),
                refreshedAtMs = 1_234_567L
            )

            store.save(index)

            val loaded = store.load()
            assertEquals(1_234_567L, loaded?.refreshedAtMs)
            assertEquals(index.entries, loaded?.entries)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a corrupt file is a miss`() {
        val file = File.createTempFile("work-index", ".tsv")
        try {
            file.writeText("garbage without a header\n")
            assertNull(WorkIndexStore(file).load())
        } finally {
            file.delete()
        }
    }
}
