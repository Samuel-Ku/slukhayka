package com.slukhayka.audiobooks.data.source

/**
 * ADR-0036 (spec-48 T1) — the declared capabilities of ONE browser source,
 * living beside its adapter in the source package. The shared recovery engine
 * (`BrowserGating`, `SourceBrowserPolicy`, `MainViewModel.openBrowserRecovery`,
 * `WebSourceBrowserScreen`) reads profiles and no screen or policy branches on
 * a `sourceId` string again.
 *
 * ADR-0038 — the facts are the [SourceRegistry] (`sources.json`): the
 * registry's `browserProfile` block feeds these profiles fact for fact, and
 * the conformance test pins the carrier. Pure JVM data: gating tests stay
 * variant-free.
 *
 * Field glossary: `pageHosts`/`audioHosts` are the source's own and observed
 * audio hosts; `searchDoor` is the pre-filled search URL builder (null = the
 * source has no door); `homeUrl` is the last-resort door target (4read only);
 * `entryNotice` is the one-shot method-change announcement; `manifestProbe`
 * enables the in-page manifest hunt; `manifestPlaceholder` /
 * `manifestPlaceholderReplacement` express the source's manifest-URL
 * obfuscation (4read's `{v1}` → `/m3u/`); `releaseBrowserDoor` is ADR-0027's
 * per-source recorded release decision (true only for 4read).
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
 * Spec-42 #440 — the 4read search URL for a free-text [query], URL-encoded
 * the same way [FourReadAdapter.search] does. ADR-0038: the template is the
 * registry's 4read `searchDoor` fact. Pure JVM so the door's target can be
 * pinned without a WebView.
 */
fun fourReadSearchUrl(query: String): String {
    val template = SourceRegistry.searchDoorTemplate("4read")
        ?: "https://4read.org/index.php?do=search&subaction=search&story={q}"
    return template.replace("{q}", java.net.URLEncoder.encode(query.trim(), "UTF-8"))
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
     * Declaration order is the registry order (ADR-0038) — stable for
     * browser routing and per-source settings.
     */
    private val profiles: Map<String, BrowserRecoveryProfile> =
        SourceRegistry.entries
            .sortedBy { it.order }
            .mapNotNull { facts ->
                facts.browserProfile?.let { declared ->
                    facts.id to BrowserRecoveryProfile(
                        pageHosts = declared.pageHosts,
                        audioHosts = declared.audioHosts,
                        searchDoor = declared.searchDoor?.let { template ->
                            { query -> template.replace("{q}", java.net.URLEncoder.encode(query.trim(), "UTF-8")) }
                        },
                        homeUrl = declared.homeUrl,
                        entryNotice = declared.entryNotice,
                        manifestProbe = declared.manifestProbe,
                        manifestPlaceholder = declared.manifestPlaceholder,
                        manifestPlaceholderReplacement = declared.manifestPlaceholderReplacement,
                        releaseBrowserDoor = declared.releaseBrowserDoor
                    )
                }
            }
            .toMap()

    /** Stable source order for routing and per-source settings. */
    val orderedSourceIds: List<String> = profiles.keys.toList()

    fun forSource(sourceId: String): BrowserRecoveryProfile =
        profiles[sourceId] ?: EMPTY

    /** Whether [sourceId] declares a browser-recovery profile at all. */
    fun hasSource(sourceId: String): Boolean = profiles.containsKey(sourceId)
}
