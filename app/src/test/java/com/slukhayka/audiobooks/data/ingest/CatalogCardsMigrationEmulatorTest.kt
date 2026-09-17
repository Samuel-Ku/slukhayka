package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SubmissionCandidateCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #840 — the ONLY check the runbook cannot make without a database: the run
 * itself (counts before/after, and that a second run changes nothing).
 *
 * Everything else is already unit-verified in
 * [CatalogCardModerationMigrationTest] (the mapping, the form, the key,
 * canonicalisation, the skip rule). This test therefore does not re-check the
 * rules — it proves that applying them to a real Firestore leaves
 * `catalog_cards` untouched and creates exactly one queue document per
 * migratable card, twice.
 *
 * Gated: it needs the emulator, so run it with
 *
 *   cd scripts/rules-matrix && npm run emulator        # 127.0.0.1:8080
 *   FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 ./gradlew testDebugUnitTest \
 *     --tests "*CatalogCardsMigrationEmulatorTest"
 */
class CatalogCardsMigrationEmulatorTest {

    private val host: String = System.getenv("FIRESTORE_EMULATOR_HOST") ?: ""
    private val project = "slukhayka"
    private val base = "http://$host/v1/projects/$project/databases/(default)/documents"
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun request(method: String, url: String, body: String? = null): HttpResponse<String> {
        // The migration runs with Admin SDK privileges (the runbook says so, and
        // the rules make catalog_cards read-only for clients). Against the
        // emulator that is the documented owner token.
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer owner")
        when (method) {
            "GET" -> builder.GET()
            "PATCH" -> builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            "DELETE" -> builder.DELETE()
            else -> error("unsupported $method")
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun write(collection: String, id: String, fields: Map<String, String>) {
        val json = fields.entries.joinToString(",") { (k, v) -> "\"$k\":{\"stringValue\":\"$v\"}" }
        val response = request("PATCH", "$base/$collection/$id", "{\"fields\":{$json}}")
        assertTrue("write $collection/$id → ${response.statusCode()}", response.statusCode() in 200..299)
    }

    /** The emulator keeps state between runs; start from a known empty set. */
    private fun clear(collection: String) {
        val response = request("GET", "$base/$collection")
        if (response.statusCode() == 404) return
        Regex("/$collection/([A-Za-z0-9_-]+)\"").findAll(response.body()).forEach { match ->
            request("DELETE", "$base/$collection/${match.groupValues[1]}")
        }
    }

    private fun count(collection: String): Int {
        val response = request("GET", "$base/$collection")
        if (response.statusCode() == 404) return 0
        assertEquals("list $collection", 200, response.statusCode())
        return Regex("\"name\":\\s*\"projects/[^\"]+/$collection/").findAll(response.body()).count()
    }

    /** The cards a curator's database would hold: two migratable, one not. */
    private val cards = listOf(
        mapOf("title" to "Кобзар", "sourceUrl" to "https://youtu.be/abc123", "author" to "Шевченко"),
        mapOf("title" to "Гайдамаки", "sourceUrl" to "https://www.youtube.com/watch?v=abc123"),
        mapOf("title" to "", "sourceUrl" to "https://youtu.be/empty")
    )

    @Test
    fun `the migration leaves the cards alone and queues exactly the migratable ones`() {
        assumeTrue("needs the Firestore emulator", host.isNotBlank())

        clear("catalog_cards")
        clear("pending_submissions")

        cards.forEachIndexed { index, card ->
            write("catalog_cards", "card-$index", card.filterValues { it is String }.mapValues { it.value as String })
        }
        val cardsBefore = count("catalog_cards")
        val queueBefore = count("pending_submissions")

        val first = migrate()
        assertEquals("catalog_cards must not change", cardsBefore, count("catalog_cards"))
        assertEquals("two cards are migratable", 2, first)
        // The two spellings of ONE link collapse into ONE document (that is the
        // canonicalisation the unit tests pin) — so the queue grows by one.
        assertEquals("one document for the two spellings", queueBefore + 1, count("pending_submissions"))

        // A second run must change nothing: same ids, same count.
        val second = migrate()
        assertEquals("second run migrates the same two cards", 2, second)
        assertEquals("and adds no duplicates", queueBefore + 1, count("pending_submissions"))
    }

    /** Applies the app's OWN mapping — no rule is reimplemented here. */
    private fun migrate(): Int = cards.count { card ->
        val candidate = CatalogCardModerationMigration.candidateFromCard(card) ?: return@count false
        val id = SubmissionCandidateCodec.documentId(candidate.canonicalUrl)
        val fields = SubmissionCandidateCodec.encode(candidate)
            .mapValues { (_, value) -> value?.toString().orEmpty() }
        write("pending_submissions", id, fields)
        true
    }
}
