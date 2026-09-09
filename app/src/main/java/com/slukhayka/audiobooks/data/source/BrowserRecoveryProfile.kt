package com.slukhayka.audiobooks.data.source

/**
 * ADR-0036 (spec-48 T1) — the declared capabilities of ONE browser source,
 * living beside its adapter in the source package. The shared recovery engine
 * (`BrowserGating`, `SourceBrowserPolicy`, `MainViewModel.openBrowserRecovery`,
 * `WebSourceBrowserScreen`) reads profiles and no screen or policy branches on
 * a `sourceId` string again. Connecting a new browser source = a profile here
 * + fixtures, with zero UI edits.
 *
 * Pure JVM data: gating tests stay variant-free.
 *
 * Field glossary (extends the ADR's list with the two implementation fields
 * the manifest probe needs):
 * - [pageHosts] — allowlist of the source's own page hosts (moved from the
 *   old `SourceBrowserPolicy` `when`);
 * - [audioHosts] — observed audio stream hosts, judged separately from pages
 *   (4read's CDN pair; sluhay's `redirectto.cc`);
 * - [searchDoor] — the pre-filled search URL builder; null = the source has
 *   no search door and recovery falls back to the stored book URL only;
 * - [homeUrl] — the invented last-resort home a door-capable source may open
 *   when even its search door cannot be built (4read only — no other source
 *   ever gets a URL it was not seen to own);
 * - [entryNotice] — the one-shot method-change announcement on the browser
 *   surface; null = no announcement;
 * - [manifestProbe] — whether the capture JS hunts in-page player manifests
 *   (`file: "…m3u|txt|json"` + inline JSON playlists). True for every source
 *   today: the probe has always run for all browser sources, and behavior
 *   identity (the T1 criterion) forbids silently switching it off;
 * - [manifestPlaceholder] / [manifestPlaceholderReplacement] — the source's
 *   manifest-URL obfuscation, expressed as a JS regex source and its
 *   replacement (4read's `{v1}` → the `/m3u/` prefix). Null = the source
 *   serves manifest URLs in the clear;
 * - [releaseBrowserDoor] — whether the in-app browser door exists in RELEASE
 *   builds. True only for 4read: ADR-0027 stays per-source — flipping this
 *   flag for another source is a recorded decision, never a config edit.
 */
data class BrowserRecoveryProfile(
    val pageHosts: Set<String> = emptySet(),
    val audioHosts: Set<String> = emptySet(),
    val searchDoor: ((String) -> String)? = null,
    val homeUrl: String? = null,
    val entryNotice: String? = null,
    val manifestProbe: Boolean = false,
    val manifestPlaceholder: String? = null,
    val manifestPlaceholderReplacement: String? = null,
    val releaseBrowserDoor: Boolean = false
) {
    /**
     * JS regex source matching in-page manifest URLs: the source's
     * obfuscation token (if any) alternated with the plain manifest
     * extensions. The capture JS splices this into its `file:` matcher —
     * `\{v1\}|\.(?:m3u|txt|json)` for 4read, the plain extensions for
     * sources that serve manifest URLs in the clear.
     */
    val manifestSignal: String
        get() = manifestPlaceholder?.let { "$it|\\.(?:m3u|txt|json)" } ?: "\\.(?:m3u|txt|json)"
}

/**
 * Spec-42 #440 — the 4read search URL for a free-text [query], URL-encoded the
 * same way [FourReadAdapter.search] does. Moved here (spec-48 T1) from
 * `GlobalSearch.kt`: the search door is 4read's profile knowledge, and this is
 * its single home. Pure JVM so the door's target can be pinned without a
 * WebView. 4read resolves to the in-app browser in every build (ADR-0027), so
 * this is the release-accessible pre-filled search.
 */
fun fourReadSearchUrl(query: String): String {
    val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
    return "https://4read.org/index.php?do=search&subaction=search&story=$encoded"
}

/**
 * The one registry of browser-source capabilities (ADR-0036). The engine asks
 * [forSource]; an unknown or non-browser source id yields [EMPTY] — a profile
 * with no door, no notice, no hosts, exactly the treatment the old `else`
 * branches gave.
 */
object BrowserRecoveryProfiles {

    /** The profile of a source with no browser recovery at all. */
    val EMPTY: BrowserRecoveryProfile = BrowserRecoveryProfile()

    /**
     * Declaration order is the stable source order shared by browser routing
     * and per-source settings (the old `SourceBrowserPolicy.browserSourceIds`).
     */
    private val profiles: Map<String, BrowserRecoveryProfile> = mapOf(
        SourceIds.FOUR_READ to BrowserRecoveryProfile(
            pageHosts = setOf("4read.org", "reasd.org"),
            audioHosts = setOf("4read.org", "reasd.org"),
            searchDoor = ::fourReadSearchUrl,
            homeUrl = "https://4read.org/",
            entryNotice = "Метод 4read змінився: для прослуховування потрібен браузер.",
            manifestProbe = true,
            // Kotlin string "\\{v1\\}" is the JS regex source `\{v1\}` — the
            // Playerjs obfuscation the capture JS has always decoded.
            manifestPlaceholder = "\\{v1\\}",
            manifestPlaceholderReplacement = "https://4read.org/m3u/",
            releaseBrowserDoor = true
        ),
        "sluhay" to BrowserRecoveryProfile(
            pageHosts = setOf("sluhay.com"),
            audioHosts = setOf("sluhay.com", "redirectto.cc"),
            manifestProbe = true
        ),
        "sluhayknigi" to BrowserRecoveryProfile(
            pageHosts = setOf("sluhayknigi.com"),
            audioHosts = setOf("sluhayknigi.com", "redirectto.cc"),
            manifestProbe = true
        ),
        // Spec-48 T3 / spec-47 T4 — the first new consumer of the engine.
        // Hosts from the spec-47 T1 spike verdict (uainaudiobooks.com, single
        // host); Cloudflare-challenged server-side, so the recovery surface is
        // the live session. No search door yet: the site's search is only
        // usable inside the live session (T4's parser fixtures decide whether
        // one is worth declaring); recovery falls back to the stored book URL.
        // `releaseBrowserDoor = false` — ADR-0027 stays per-source (4read
        // remains the only release WebView exception); flipping this flag
        // later is a recorded decision, never a config edit.
        "ukrainianaudiobooks" to BrowserRecoveryProfile(
            pageHosts = setOf("ukrainianaudiobooks.com"),
            audioHosts = setOf("ukrainianaudiobooks.com"),
            manifestProbe = true
        )
    )

    /** Stable source order for routing and per-source settings. */
    val orderedSourceIds: List<String> = profiles.keys.toList()

    fun forSource(sourceId: String): BrowserRecoveryProfile =
        profiles[sourceId] ?: EMPTY

    /** Whether [sourceId] declares a browser-recovery profile at all. */
    fun hasSource(sourceId: String): Boolean = profiles.containsKey(sourceId)
}
