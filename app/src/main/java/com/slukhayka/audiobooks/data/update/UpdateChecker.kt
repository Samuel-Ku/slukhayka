package com.slukhayka.audiobooks.data.update

import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.source.HttpFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An update the listener can act on: the newer release's bare version and
 * the DIRECT apk asset link (the name is guaranteed by the release
 * workflow's rename step — tag v<version> ↔ slukhayka-v<version>.apk).
 */
data class AvailableAppRelease(
    val versionName: String,
    val apkUrl: String
)

/**
 * Spec-36 T1 (#244) — the app-release check behind ONE door. The screen
 * reads [available] directly (ADR-0008 module reads); the composition root
 * triggers [checkNow] lazily after startup, never on the cold-start path.
 *
 * Contract (all best-effort, degrade-never-throw — the house convention):
 *  - at most one check per [CHECK_INTERVAL_MILLIS], anchored on the last
 *    SUCCESSFUL check only (a failed launch retries on the next one);
 *  - a NEWER tag than the installed version emits an [AvailableAppRelease];
 *    equal/older/malformed tags emit nothing;
 *  - empty body, garbage JSON, missing/non-string tag_name — silence, and
 *    the window stays unanchored.
 *
 * The transport is the shared [HttpFetcher] (ADR-0006); the response is
 * decoded by the shared pure-JVM MiniJson (the ADR-0013 collections
 * precedent) — no second JSON parser enters the codebase.
 */
class UpdateChecker(
    private val fetcher: HttpFetcher,
    private val store: UpdateCheckStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val installedVersionName: String,
    private val releasesUrl: String = RELEASES_LATEST_URL,
    private val downloadUrlFor: (versionName: String) -> String = ::defaultDownloadUrlFor
) {

    /** The update to surface on Огляд, or null when everything is current. */
    private val _available = MutableStateFlow<AvailableAppRelease?>(null)
    val available: StateFlow<AvailableAppRelease?> = _available.asStateFlow()

    /** One throttled, silent, best-effort check. Safe to call often. */
    suspend fun checkNow() {
        // A zero timestamp means «never checked» — a fresh install always
        // runs its first check regardless of what the clock says.
        val lastCheckAt = store.lastCheckAtMillis
        if (lastCheckAt != 0L && clock() - lastCheckAt < CHECK_INTERVAL_MILLIS) return
        val body = try {
            fetcher.getText(releasesUrl)
        } catch (e: Exception) {
            ""
        }
        if (body.isBlank()) return
        val root = MiniJson.parse(body) as? Map<*, *> ?: return
        val latest = ReleaseVersion.parseTag(root[TAG_NAME_FIELD] as? String) ?: return

        // Only a well-formed response anchors the daily window.
        store.lastCheckAtMillis = clock()

        if (!ReleaseVersion.isNewer(latest, installedVersionName)) {
            _available.value = null
            return
        }
        _available.value = AvailableAppRelease(latest, downloadUrlFor(latest))
    }

    companion object {
        /** The single source of truth for «what is the latest release». */
        const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/Samuel-Ku/slukhayka/releases/latest"

        /** How often the check may run (spec-36 #244: once per day). */
        const val CHECK_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000

        /**
         * The direct asset link of the latest release. The `latest/download/
         * <asset>` shortcut always resolves to the newest published release,
         * and the asset name is pinned by the workflow's rename+checksum.
         */
        internal fun defaultDownloadUrlFor(versionName: String): String =
            "https://github.com/Samuel-Ku/slukhayka/releases/latest/download/" +
                "slukhayka-v$versionName.apk"

        private const val TAG_NAME_FIELD = "tag_name"
    }
}
