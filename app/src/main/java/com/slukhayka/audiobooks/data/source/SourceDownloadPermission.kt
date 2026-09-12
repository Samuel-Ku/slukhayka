package com.slukhayka.audiobooks.data.source

import java.io.File

/** #527 — the live download-permission verdict of one source. */
enum class DownloadPermissionVerdict {
    /** A fresh live check confirmed the source's rules allow the download. */
    ALLOWED,

    /** The source's rules disallow it. */
    DENIED,

    /** No fresh live confirmation exists — the honest fail-closed state. */
    UNKNOWN
}

/** #527 — one recorded verdict plus when it was observed. */
data class DownloadPermissionRecord(
    val verdict: DownloadPermissionVerdict,
    val observedAt: Long
)

/**
 * #527 — the pure `robots.txt` policy: a source's own rules decide whether the
 * app may download its media. Only the `User-agent: *` group is consulted (the
 * app presents no other agent); `Allow`/`Disallow` compete by longest match,
 * the standard rule. An EMPTY document is [DownloadPermissionVerdict.UNKNOWN] —
 * nothing was confirmed, so the gate stays closed (never a fabricated ALLOW).
 */
object RobotsDownloadRules {

    fun verdictFor(robotsText: String, path: String): DownloadPermissionVerdict {
        if (robotsText.isBlank()) return DownloadPermissionVerdict.UNKNOWN
        val normalizedPath = path.trim().ifEmpty { "/" }
        var inStarGroup = false
        var sawStarGroup = false
        var disallowMatch = -1
        var allowMatch = -1
        var disallowed = false
        var allowed = false

        for (rawLine in robotsText.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "user-agent" -> {
                    inStarGroup = value == "*"
                    if (inStarGroup) sawStarGroup = true
                }
                "disallow", "allow" -> {
                    if (!inStarGroup || value.isEmpty()) continue
                    if (!value.startsWith("/")) continue
                    val matches = normalizedPath.startsWith(value)
                    if (!matches) continue
                    if (key == "disallow" && value.length > disallowMatch) {
                        disallowMatch = value.length
                        disallowed = true
                    } else if (key == "allow" && value.length > allowMatch) {
                        allowMatch = value.length
                        allowed = true
                    }
                }
            }
        }
        // A group that mentions neither directive leaves the path unconstrained.
        if (!sawStarGroup) return DownloadPermissionVerdict.UNKNOWN
        if (disallowed && allowMatch < disallowMatch) return DownloadPermissionVerdict.DENIED
        return DownloadPermissionVerdict.ALLOWED
    }
}

/**
 * #527 — the persisted live verdicts of the download gate. A verdict is FRESH
 * only inside [TTL_MS]; an absent, stale or unreadable record is [UNKNOWN], so
 * the gate fails closed rather than trusting yesterday's permission. A plain
 * TSV, best-effort: a broken file is an empty map, never a crash.
 */
class SourceDownloadPermissionStore(private val file: File) {

    fun record(verdict: DownloadPermissionRecord, sourceId: String) {
        if (sourceId.isBlank()) return
        val current = load().toMutableMap()
        current[sourceId] = verdict
        save(current)
    }

    /** The fresh verdict of one source, or [DownloadPermissionVerdict.UNKNOWN]. */
    fun freshVerdict(sourceId: String, now: Long): DownloadPermissionVerdict {
        val record = load()[sourceId] ?: return DownloadPermissionVerdict.UNKNOWN
        if (now - record.observedAt >= TTL_MS || now < record.observedAt) {
            return DownloadPermissionVerdict.UNKNOWN
        }
        return record.verdict
    }

    /** Raw records (tests/diagnostics); never throws. */
    fun load(): Map<String, DownloadPermissionRecord> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3 || parts[0].isBlank()) return@mapNotNull null
                val verdict = DownloadPermissionVerdict.entries
                    .firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
                val observedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
                parts[0] to DownloadPermissionRecord(verdict, observedAt)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun save(records: Map<String, DownloadPermissionRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                records.entries.joinToString("\n") { (sourceId, record) ->
                    listOf(sourceId, record.verdict.name, record.observedAt.toString()).joinToString("\t")
                }
            )
        }
    }

    companion object {
        /** A week: long enough to be cheap, short enough to be current. */
        const val TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * #527 — the ONE download gate: the static [SourceRegistry.streamOnly] refusal
 * always wins, and a source that declares [SourceFacts.liveDownloadPermission]
 * needs a FRESH [DownloadPermissionVerdict.ALLOWED] from the live rules check.
 * Everything else (no record, stale record, DENIED, unreadable store) keeps
 * downloads OFF — the gate never fabricates permission.
 */
fun downloadPermittedFor(
    sourceId: String,
    permissions: SourceDownloadPermissionStore?,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (SourceRegistry.streamOnlyFor(sourceId)) return false
    if (SourceRegistry.isScam(sourceId)) return false
    if (!SourceRegistry.requiresLiveDownloadPermission(sourceId)) return true
    return permissions?.freshVerdict(sourceId, now) == DownloadPermissionVerdict.ALLOWED
}
