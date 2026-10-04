package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.collections.MiniJson
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class RecommendationEvalIdentityAliasesTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "CONTEXT.md").isFile }
    private val manifest = File(root, "docs/recommend/real-scale-identity-aliases.json")
    private fun records() = RecommendationFeedSnapshot.records(RecommendationFeedSnapshot.load(
        File(root, "docs/recommend/snapshots/librivox-2026-10-04")))

    @Test
    fun `real Alice translations and abridged editions share one held-out Work while distinct books remain distinct`() {
        val records = records()
        val aliases = RecommendationEvalIdentityAliases.load(manifest, records)
        val catalog = RecommendationEvalCatalog.fromRecords(records, aliases)
        val english = "https://archive.org/details/alice_adventures_v_1208_librivox"
        val work = catalog.sourceUrls.entries.single { english in it.value }
        assertEquals(16, work.value.size)
        for (id in listOf("alice_au_pays_des_merveilles_1811_librivox", "alices_abenteuer_0911",
            "alicia_1708_librivox", "avventuredalice_1907_librivox", "laaventuro_de_alicio0912_librivox",
            "alicesadventure_abridged_pc_librivox")) {
            assertEquals(work.key, catalog.sourceUrls.entries.single { "https://archive.org/details/$id" in it.value }.key)
        }
        for (id in listOf("alice_underground_pc_librivox", "nursery_alice_1705_librivox", "lookingglass_ap_librivox",
            "songs_aliceinwonderland_throughthelookingglass_2012_librivox")) {
            assertFalse("Distinct Work $id must not be silently aliased", "https://archive.org/details/$id" in work.value)
        }
        assertEquals("Alice's Adventures in Wonderland", catalog.works.single { it.id == work.key }.title)
    }
    @Test(expected = IllegalArgumentException::class)
    fun `a changed frozen Alice recording description cannot validate as the audited source record`() {
        val records = records().map { raw ->
            if (raw["identifier"] == "laaventuro_de_alicio0912_librivox") raw + ("description" to "Changed after identity audit") else raw
        }
        RecommendationEvalIdentityAliases.load(manifest, records)
    }
    @Test(expected = IllegalArgumentException::class)
    fun `a canonical author cannot falsely claim another writer's audited Alice recording`() {
        val invalid = File.createTempFile("identity-wrong-author", ".json")
        try {
            invalid.writeText(manifest.readText().replace("\"author\": \"Lewis Carroll\"", "\"author\": \"Charles Dickens\""))
            RecommendationEvalIdentityAliases.load(invalid, records())
        } finally { invalid.delete() }
    }
    @Test(expected = IllegalArgumentException::class)
    fun `one audited source record cannot be claimed twice in the identity registry`() {
        val invalid = File.createTempFile("identity-repeated-record", ".json")
        try {
            val json = (MiniJson.parse(manifest.readText()) as Map<*, *>).toMutableMap()
            val works = json["works"] as List<*>
            json["works"] = works + listOf(works.first())
            invalid.writeText(Moshi.Builder().build().adapter(Map::class.java).toJson(json))
            RecommendationEvalIdentityAliases.load(invalid, records())
        } finally { invalid.delete() }
    }
    @Test
    fun `all 24 preregistered target Works retain their 112 real source URLs in five resolved cohorts`() {
        val records = records()
        val identities = RecommendationEvalIdentityAliases.load(manifest, records)
        val catalog = RecommendationEvalCatalog.fromRecords(records, identities)
        val cohorts = RecommendationEvalCohorts.load(File(root, "docs/recommend/real-scale-cohorts.json"), catalog)
        assertEquals(5, cohorts.size)
        assertEquals(24, cohorts.flatMap { it.workIds }.distinct().size)
        assertEquals(112, identities.works.sumOf { it.recordIds.size })
        for (target in identities.works) {
            val expected = target.recordIds.map { "https://archive.org/details/$it" }.toSet()
            val projected = catalog.sourceUrls.entries.single { "https://archive.org/details/${target.representativeIdentifier}" in it.value }
            assertEquals(expected, projected.value.toSet())
            assertEquals(true, cohorts.any { projected.key in it.workIds })
        }
        assertEquals(true, catalog.works.size >= 10_000 && catalog.distinctTitles >= 10_000)
    }
}
