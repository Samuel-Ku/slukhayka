package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            // #533 (measured on-device): this pass is launched from a
            // LaunchedEffect, i.e. on the MAIN dispatcher, so the robots.txt
            // GET threw NetworkOnMainThreadException and the permission gate
            // silently never worked. One background pass = one IO hop.
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    fetcher.getText(robotsUrl, emptyMap(), SourceRequestClass.BACKGROUND, TTL_MS)
                }.getOrDefault("")
            }
            if (body.isBlank()) continue
            val verdict = RobotsDownloadRules.verdictFor(body, cataloguePath)
            if (verdict == DownloadPermissionVerdict.UNKNOWN) continue
            store.record(DownloadPermissionRecord(verdict, clock()), sourceId)
            // #527 — positive proof that the live gate actually recorded a
            // verdict on-device (successful fetches are otherwise silent).
            android.util.Log.w("DownloadPermission", "recorded $verdict for $sourceId")
            recorded++
        }
        return recorded
    }

    companion object {
        /** The robots.txt cache TTL rides the same week as the verdict store. */
        const val TTL_MS: Long = SourceDownloadPermissionStore.TTL_MS
    }
}
