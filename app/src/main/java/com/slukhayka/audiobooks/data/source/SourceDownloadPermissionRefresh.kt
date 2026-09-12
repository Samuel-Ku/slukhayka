package com.slukhayka.audiobooks.data.source

/**
 * #527 — the live rules check that feeds the download gate. For every source
 * that declares [SourceFacts.liveDownloadPermission] it reads the source's
 * `robots.txt` (ONE background request, never a per-book request), parses the
 * `User-agent: *` rules for the source's catalogue path, and records the
 * verdict with its observation time. Best-effort by contract: a blank or
 * failing fetch records NOTHING, so the gate keeps failing closed.
 */
class SourceDownloadPermissionRefresh(
    private val fetcher: HttpFetcher,
    private val store: SourceDownloadPermissionStore,
    /** The path whose permission the verdict describes (the catalogue). */
    private val cataloguePath: String = "/",
    private val clock: () -> Long = System::currentTimeMillis,
    private val sourceIds: Collection<String> = SourceRegistry.ids()
) {

    /** @return how many fresh verdicts were recorded this pass. */
    suspend fun refreshOnce(): Int {
        var recorded = 0
        for (sourceId in sourceIds) {
            if (!SourceRegistry.requiresLiveDownloadPermission(sourceId)) continue
            val facts = SourceRegistry.facts(sourceId) ?: continue
            val home = facts.homeUrl.takeIf { it.isNotBlank() } ?: continue
            val robotsUrl = home.trimEnd('/') + "/robots.txt"
            val body = runCatching {
                fetcher.getText(robotsUrl, emptyMap(), SourceRequestClass.BACKGROUND, TTL_MS)
            }.getOrDefault("")
            if (body.isBlank()) continue
            val verdict = RobotsDownloadRules.verdictFor(body, cataloguePath)
            if (verdict == DownloadPermissionVerdict.UNKNOWN) continue
            store.record(DownloadPermissionRecord(verdict, clock()), sourceId)
            recorded++
        }
        return recorded
    }

    companion object {
        /** The robots.txt cache TTL rides the same week as the verdict store. */
        const val TTL_MS: Long = SourceDownloadPermissionStore.TTL_MS
    }
}
