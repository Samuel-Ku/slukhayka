package com.slukhayka.audiobooks.data.imports

import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure codec tests for the folder memory (ADR-0057 «Пам'ять походження»,
 * ADR-0061 §3): v3 round-trips the SAF metadata beside the hashes, v2 stays
 * readable as metadata-unknown, and the re-scan reuses a hash only when the
 * metadata matches AND the path carries exactly one hash.
 */
class LocalFolderMemoryCodecTest {

    private fun token(value: String): String = value.encodeUtf8().base64Url().trimEnd('=')

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "c".repeat(64)

    private fun lineage(files: Map<String, LocalFileRecord>) = LocalFolderLineage(
        bookId = "local-1",
        grouping = LocalFolderGrouping.SEPARATE_BOOKS,
        groups = setOf("g1"),
        files = files
    )

    @Test
    fun `v3 round-trips the SAF metadata beside the hashes`() {
        val original = lineage(
            mapOf(
                "Кобзар/01.mp3" to LocalFileRecord(setOf(hashA), 42L, 1_700_000_000_000L),
                "/root.mp3" to LocalFileRecord(setOf(hashB, hashC))
            )
        )
        assertEquals(original, LocalFolderMemoryCodec.decode(LocalFolderMemoryCodec.encode(original)))
    }

    @Test
    fun `a legacy v2 record decodes with unknown metadata`() {
        val v2 = listOf(
            "2",
            token("local-1"),
            LocalFolderGrouping.SEPARATE_BOOKS.name,
            token("g1"),
            token("Кобзар/01.mp3") + ":" + hashA
        ).joinToString("|")

        val decoded = requireNotNull(LocalFolderMemoryCodec.decode(v2))

        assertEquals("local-1", decoded.bookId)
        assertEquals(setOf(hashA), decoded.hashes)
        assertNull(decoded.files.getValue("Кобзар/01.mp3").sizeBytes)
        assertNull(decoded.files.getValue("Кобзар/01.mp3").lastModifiedMs)
    }

    @Test
    fun `an unreadable record is a miss, never a guess`() {
        assertNull(LocalFolderMemoryCodec.decode("garbage"))
        assertNull(LocalFolderMemoryCodec.decode("9|a|b|c|d"))
        assertNull(LocalFolderMemoryCodec.decode("2|" + token("local-1") + "|" +
            LocalFolderGrouping.SEPARATE_BOOKS.name + "||not-a-hash"))
    }

    @Test
    fun `metadata-equal and one hash is the only cache hit`() {
        val records = mapOf("Кобзар/01.mp3" to LocalFileRecord(setOf(hashA), 42L, 100L))
        assertEquals(hashA, rememberedHashFor(records, "01.mp3", "Кобзар", 42L, 100L))
    }

    @Test
    fun `changed or unknown metadata must read the file`() {
        val records = mapOf("Кобзар/01.mp3" to LocalFileRecord(setOf(hashA), 42L, 100L))
        assertNull(rememberedHashFor(records, "01.mp3", "Кобзар", 43L, 100L))
        assertNull(rememberedHashFor(records, "01.mp3", "Кобзар", 42L, 101L))
        assertNull(rememberedHashFor(records, "01.mp3", "Кобзар", null, null))
        assertNull(rememberedHashFor(records, "01.mp3", "Кобзар", 42L, null))
        assertNull(rememberedHashFor(emptyMap(), "01.mp3", "Кобзар", 42L, 100L))
    }

    @Test
    fun `several hashes for one path are an ambiguity not a hit`() {
        val records = mapOf("Кобзар/01.mp3" to LocalFileRecord(setOf(hashA, hashB), 42L, 100L))
        assertNull(rememberedHashFor(records, "01.mp3", "Кобзар", 42L, 100L))
    }

    @Test
    fun `merged records collapse disagreeing metadata to unknown`() {
        val one = lineage(mapOf("p" to LocalFileRecord(setOf(hashA), 10L, 5L)))
        val two = lineage(mapOf("p" to LocalFileRecord(setOf(hashB), 11L, 6L))).copy(bookId = "local-2")

        val merged = mergedRecords(listOf(one, two)).getValue("p")

        assertEquals(setOf(hashA, hashB), merged.hashes)
        assertNull(merged.sizeBytes)
        assertNull(merged.lastModifiedMs)
    }

    @Test
    fun `merged records keep the half both owners agree on`() {
        val one = lineage(mapOf("p" to LocalFileRecord(setOf(hashA), 10L, 5L)))
        val two = lineage(mapOf("p" to LocalFileRecord(setOf(hashB), 10L, 6L))).copy(bookId = "local-2")

        val merged = mergedRecords(listOf(one, two)).getValue("p")

        assertEquals(10L, merged.sizeBytes)
        assertNull(merged.lastModifiedMs)
    }

    @Test
    fun `a fresh observation never inherits stale metadata`() {
        val stale = LocalFileRecord(setOf(hashA), 10L, 5L)
        val observed = FolderRescan.RescanFile("01.mp3", "Кобзар", hashB)

        val record = observedRecord(stale, observed)

        assertEquals(setOf(hashA, hashB), record.hashes)
        assertNull("метадані мусять стати невідомими, а не успадкувати старі", record.sizeBytes)
        assertNull(record.lastModifiedMs)
    }

    @Test
    fun `merged records keep metadata both owners agree on`() {
        val one = lineage(mapOf("p" to LocalFileRecord(setOf(hashA), 10L, 5L)))
        val two = lineage(mapOf("p" to LocalFileRecord(setOf(hashB), 10L, 5L))).copy(bookId = "local-2")

        val merged = mergedRecords(listOf(one, two)).getValue("p")

        assertEquals(10L, merged.sizeBytes)
        assertEquals(5L, merged.lastModifiedMs)
        assertNull(rememberedHashFor(mapOf("p" to merged), "p", null, 10L, 5L))
    }
}
