package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.source.GateOutcome
import com.slukhayka.audiobooks.data.source.SourceBucketState
import com.slukhayka.audiobooks.data.source.SourceGateBudgetStore
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.delay

/** Host-only, bounded and resumable acquisition of the production LibriVox archive feed. */
object RecommendationFeedSnapshot {
    const val PAGE_SIZE = 500
    const val MAX_PAGES = 44

    data class Snapshot(val directory: File, val totalRecords: Int, val pages: List<File>)

    suspend fun acquire(
        directory: File,
        gate: SourceRequestGate = SourceRequestGate(budgetStore = FileBudget(File(directory, "request-budget.properties"))),
        fetch: suspend (String) -> String? = ::get
    ): Snapshot {
        directory.mkdirs()
        return FileChannel.open(File(directory, ".snapshot.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("Another snapshot acquisition owns this directory")
            lock.use {
                if (File(directory, "manifest.properties").exists()) load(directory)
                else acquireLocked(directory, gate, fetch)
            }
        }
    }

    private suspend fun acquireLocked(directory: File, gate: SourceRequestGate, fetch: suspend (String) -> String?): Snapshot {
        val cutoffFile = File(directory, "cutoff.txt")
        val cutoff = if (cutoffFile.exists()) cutoffFile.readText().trim() else java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().also {
            cutoffFile.writeText(it + "\n")
        }
        java.time.LocalDate.parse(cutoff)
        var expectedTotal: Int? = null
        val pages = mutableListOf<File>()
        var afterId: String? = null
        for (index in 0 until MAX_PAGES) {
            val page = File(directory, "page-%03d.json.gz".format(index))
            val checksum = File(directory, page.name + ".sha256")
            val url = url(cutoff, index, afterId)
            val text = if (page.exists()) {
                val provenance = File(directory, page.name + ".url")
                require(provenance.isFile && provenance.readText().trim() == url) { "Altered frozen page provenance: $page" }
                require(checksum.exists() && sha256(page) == checksum.readText().trim()) { "Corrupt frozen page: $page" }
                read(page)
            } else {
                var value: String? = null
                while (value == null) {
                    when (val outcome = gate.run(url, SourceRequestClass.BACKGROUND, cacheTtlMillis = 86_400_000L, fetch = { fetch(url) })) {
                        is GateOutcome.Fetched -> value = outcome.value
                        is GateOutcome.Fresh -> value = outcome.value
                        is GateOutcome.Deferred -> delay(outcome.retryAfterMs)
                        GateOutcome.Unavailable -> error("Feed request failed; resume uses saved pages: $url")
                    }
                }
                val parsed = response(value)
                validatePage(parsed, index, expectedTotal, index >= 20)
                validateIdentifiers(parsed, afterId)
                val pending = File(directory, page.name + ".partial")
                GZIPOutputStream(pending.outputStream()).bufferedWriter().use { it.write(value) }
                checksum.writeText(sha256(pending) + "\n")
                File(directory, page.name + ".url").writeText(url + "\n")
                File(directory, page.name + ".fetched-at").writeText(Instant.now().toString() + "\n")
                require(pending.renameTo(page)) { "Could not save page atomically" }
                value
            }
            val parsed = response(text)
            val total = validatePage(parsed, index, expectedTotal, index >= 20)
            expectedTotal = total
            afterId = validateIdentifiers(parsed, afterId)
            pages += page
            println("snapshot page ${index + 1}: ${docs(parsed).size} records; frozen total=$total")
            if ((index + 1) * PAGE_SIZE >= total) {
                val manifest = Properties().apply {
                    setProperty("schemaVersion", "1")
                    setProperty("source", "librivox/archive.org")
                    setProperty("queryCutoff", cutoff)
                    setProperty("totalRecords", total.toString())
                    setProperty("pageSize", PAGE_SIZE.toString())
                    setProperty("pageCount", pages.size.toString())
                    setProperty("gate", "ADR-0039 BACKGROUND; capacity=6; refill=10000ms; max one request per host")
                    pages.forEachIndexed { i, file ->
                        setProperty("page.$i.file", file.name)
                        setProperty("page.$i.sha256", sha256(file))
                        setProperty("page.$i.url", File(directory, file.name + ".url").readText().trim())
                    }
                }
                val manifestFile = File(directory, "manifest.properties")
                val pendingManifest = File(directory, "manifest.properties.partial")
                pendingManifest.outputStream().use { manifest.store(it, "Frozen real feed; no audio or listener data") }
                require(pendingManifest.renameTo(manifestFile)) { "Could not freeze manifest atomically" }
                return Snapshot(directory, total, pages)
            }
        }
        error("Bounded acquisition exhausted $MAX_PAGES pages; never silently truncate a catalog")
    }

    fun load(directory: File): Snapshot {
        val props = Properties().apply { File(directory, "manifest.properties").inputStream().use { load(it) } }
        require(props.getProperty("schemaVersion") == "1" && props.getProperty("source") == "librivox/archive.org")
        require(props.getProperty("pageSize").toInt() == PAGE_SIZE)
        val total = props.getProperty("totalRecords").toInt()
        val count = props.getProperty("pageCount").toInt()
        require(count in 1..MAX_PAGES && count == (total + PAGE_SIZE - 1) / PAGE_SIZE)
        val cutoff = props.getProperty("queryCutoff")
        var afterId: String? = null
        val pages = (0 until count).map { index ->
            val name = props.getProperty("page.$index.file")
            require(name == "page-%03d.json.gz".format(index))
            val file = File(directory, name)
            require(props.getProperty("page.$index.sha256") == sha256(file)) { "Snapshot checksum mismatch: $name" }
            require(props.getProperty("page.$index.url") == url(cutoff, index, afterId)) { "Snapshot provenance mismatch: $name" }
            val parsed = response(read(file))
            validatePage(parsed, index, total, index >= 20)
            afterId = validateIdentifiers(parsed, afterId)
            file
        }
        return Snapshot(directory, total, pages)
    }

    fun records(snapshot: Snapshot): List<Map<*, *>> = snapshot.pages.flatMap { docs(response(read(it))) }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun read(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
    private fun response(text: String): Map<*, *> = (MiniJson.parseLenient(text) as? Map<*, *>)?.get("response") as? Map<*, *>
        ?: error("Malformed catalog feed response")
    private fun docs(response: Map<*, *>): List<Map<*, *>> = (response["docs"] as? List<*>)?.map {
        it as? Map<*, *> ?: error("Invalid feed card")
    } ?: error("Feed has no docs array")
    private fun validatePage(response: Map<*, *>, index: Int, expected: Int?, keyset: Boolean = false): Int {
        val remaining = (response["numFound"] as? Number)?.toInt() ?: error("Feed count absent")
        val total = remaining + if (keyset) index * PAGE_SIZE else 0
        require(total > 0 && (expected == null || expected == total)) { "Catalog changed during acquisition; no mixed snapshot" }
        require((response["start"] as? Number)?.toInt() == (if (keyset) 0 else index * PAGE_SIZE)) { "Wrong page offset" }
        require(docs(response).size == minOf(PAGE_SIZE, total - index * PAGE_SIZE)) { "Incomplete feed page" }
        return total
    }
    private fun validateIdentifiers(response: Map<*, *>, afterId: String?): String {
        val ids = docs(response).map { it["identifier"] as? String ?: error("Missing source identifier") }
        val folded = ids.map { it.lowercase() }
        require(ids.distinct().size == ids.size && folded == folded.sorted()) { "Source identifiers are not ordered" }
        require(afterId == null || folded.first() > afterId.lowercase()) { "Repeated cursor record" }
        return ids.last()
    }
    private fun url(cutoff: String, index: Int, afterId: String? = null): String {
        // Advanced search permits only the first 10k sorted results. Beyond
        // that window, a strict identifier range resumes from the last saved
        // record; no page is fetched twice and the remaining count is checked.
        require(index < 20 || afterId?.matches(Regex("[A-Za-z0-9_.-]+")) == true) { "Unsafe or absent source cursor" }
        val range = if (index < 20) "" else " AND identifier:[$afterId TO *] AND NOT identifier:$afterId"
        val query = URLEncoder.encode("collection:librivoxaudio AND addeddate:[* TO $cutoff]$range", "UTF-8")
        return "https://archive.org/advancedsearch.php?q=$query&rows=$PAGE_SIZE&page=${if (index < 20) index + 1 else 1}&output=json" +
            "&sort%5B%5D=identifier%20asc&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator" +
            "&fl%5B%5D=language&fl%5B%5D=description&fl%5B%5D=subject"
    }
    private suspend fun get(url: String): String? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "Slukhayka-recommendation-eval/1 (bounded public catalog snapshot)")
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private class FileBudget(private val file: File) : SourceGateBudgetStore {
        private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
        override fun load(host: String): SourceBucketState? = props.getProperty(host)?.split(',')?.let {
            SourceBucketState(it[0].toInt(), it[1].toLong())
        }
        override fun save(host: String, state: SourceBucketState) {
            props.setProperty(host, "${state.tokens},${state.lastRefillAtMs}")
            file.parentFile.mkdirs()
            val pending = File(file.parentFile, file.name + ".partial")
            pending.outputStream().use { props.store(it, "ADR-0039 source request budget") }
            require(pending.renameTo(file)) { "Could not persist source request budget" }
        }
    }
}
