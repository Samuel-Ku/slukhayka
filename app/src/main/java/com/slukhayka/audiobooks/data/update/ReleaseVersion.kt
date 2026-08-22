package com.slukhayka.audiobooks.data.update

/**
 * Pure JVM version algebra for the app-release check (spec-36 #243).
 *
 * The release workflow's convention is tag = v<versionName> (v1.2 ↔
 * versionName "1.2"), and the checksum step fails loudly when they drift,
 * so the tag alone is enough truth. Parsing is total: anything that does
 * not look like a dotted number means «no update» — never an exception,
 * never a fabricated comparison.
 */
object ReleaseVersion {

    /**
     * Strips the leading `v` from a release tag and returns the bare
     * version string ("v1.2" → "1.2", "1.3" → "1.3"), or null when the
     * remainder is empty or does not start with a digit.
     */
    fun parseTag(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val body = tag.removePrefix("v").removePrefix("V")
        if (body.isEmpty() || !body[0].isDigit()) return null
        return body
    }

    /**
     * Segment-wise numeric comparison ("1.10" > "1.9"; missing segments
     * count as zero). A candidate carrying a non-numeric segment is never
     * newer — malformed input degrades to «no update» by contract.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val candidateSegments = candidate.split('.').map { it.toLongOrNull() ?: return false }
        val installedSegments = installed.split('.').map { it.toLongOrNull() ?: 0L }
        for (index in 0 until maxOf(candidateSegments.size, installedSegments.size)) {
            val candidateValue = candidateSegments.getOrElse(index) { 0L }
            val installedValue = installedSegments.getOrElse(index) { 0L }
            when {
                candidateValue > installedValue -> return true
                candidateValue < installedValue -> return false
            }
        }
        return false
    }
}
