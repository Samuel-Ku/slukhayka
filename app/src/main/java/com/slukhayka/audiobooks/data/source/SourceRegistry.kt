package com.slukhayka.audiobooks.data.source

/**
 * ADR-0038 — the ONE static fact table of every Source, the Android side of
 * the `sources.json` carrier at the repo root.
 *
 * The web worker imports the same file natively; the JVM conformance test
 * ([SourceRegistryConformanceTest]) reads the file and pins every fact by
 * full equality — a fact added, removed or changed in one place fails the
 * build, never drifts silently. Consumers migrate onto this module one by
 * one (sourceIdForUrl/sourceDisplayName, SourceAccessPolicy, DownloadPolicy,
 * SourceBrowserPolicy, BrowserRecoveryProfiles, App.kt's direct-adapter
 * filter, the web sourceMetadata/registry/workFeed).
 *
 * Deliberately OUT of the registry (ADR-0038 §4): platform capabilities —
 * [SourceAdapter.sessionBound] stays adapter/platform knowledge (sluhay:
 * Android needs the live session, the worker fetches it server-side); the
 * tier rule (LOCAL < DIRECT < UNKNOWN < BROWSER) stays code in
 * [SourceAccessPolicy]; adapter parsing and the persisted plain-string ids
 * of the `sources` table are untouched.
 */
data class RefererRule(
    /** The Referer value the transport sends. */
    val value: String,
    /**
     * Hosts that may receive the Referer; EMPTY = the source's streams may
     * live on any host, the Referer always applies (SEC-004: never leak a
     * Referer onto a host that does not need one).
     */
    val scopeHosts: Set<String> = emptySet()
)

/** The ADR-0036 browser-recovery facts of one Browser Source (ADR-0038: same carrier). */
data class BrowserProfileFacts(
    val pageHosts: Set<String> = emptySet(),
    val audioHosts: Set<String> = emptySet(),
    val homeUrl: String? = null,
    val entryNotice: String? = null,
    val manifestProbe: Boolean = false,
    /** JS regex source of the source's manifest-URL obfuscation token; null = in the clear. */
    val manifestPlaceholder: String? = null,
    val manifestPlaceholderReplacement: String? = null,
    /** Search-door URL template with a `{q}` placeholder; null = no door. */
    val searchDoor: String? = null,
    /** ADR-0027: release browser door is a per-source recorded decision, never a config edit. */
    val releaseBrowserDoor: Boolean = false
)

/** One declarative row of the Source Registry (ADR-0038). */
data class SourceFacts(
    /** The stable, persisted (Room `sources.type`) domain id. */
    val id: String,
    val displayName: String,
    val homeUrl: String = "",
    /** BCP-47 catalogue language; "" = unknown (never guessed). */
    val contentLanguage: String = "",
    val accessMode: SourceAccessMode = SourceAccessMode.UNKNOWN,
    /**
     * The ONE order list: within-tier order for Android, the global feed
     * priority for the web worker. Lower wins.
     */
    val order: Int = Int.MAX_VALUE,
    val streamOnly: Boolean = false,
    /**
     * A scam source: the audio it serves for a clean client is not the book
     * (4read's is a 52-second artefact). Never imported, offered, played,
     * downloaded or mirrored — the hard refusal ([SourceAudioRefusal.ALWAYS_REFUSED])
     * and every write gate read this one fact.
     */
    val scam: Boolean = false,
    val referer: RefererRule? = null,
    /** Search-endpoint URL template with a `{q}` placeholder (worker/global search). */
    val searchUrl: String? = null,
    val searchHeaders: Map<String, String> = emptyMap(),
    /** Catalogue feed's starting URL where the source declares a dedicated one. */
    val catalogUrl: String? = null,
    /** All hosts the platform transports may fetch for this source (web SSRF allowlist, cookie/audio boundaries). */
    val transportHosts: Set<String> = emptySet(),
    val browserProfile: BrowserProfileFacts? = null
)

/**
 * The registry's Android reader: a typed object mirroring `sources.json`
 * fact for fact. The conformance test pins the mirror; consumers read the
 * accessors, never raw ids.
 */
object SourceRegistry {

    val entries: List<SourceFacts> = listOf(
        SourceFacts(
            id = "4read",
            displayName = "4read",
            homeUrl = "https://4read.org/",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.BROWSER,
            order = 0,
            streamOnly = true,
            scam = true,
            referer = RefererRule("https://4read.org/", setOf("4read.org", "reasd.org")),
            searchUrl = "https://4read.org/index.php?do=search&subaction=search&story={q}",
            transportHosts = setOf("4read.org", "reasd.org"),
            browserProfile = BrowserProfileFacts(
                pageHosts = setOf("4read.org", "reasd.org"),
                audioHosts = setOf("4read.org", "reasd.org"),
                homeUrl = "https://4read.org/",
                entryNotice = "Метод 4read змінився: для прослуховування потрібен браузер.",
                manifestProbe = true,
                // JS regex source `\{v1\}` — the Playerjs obfuscation the capture JS decodes.
                manifestPlaceholder = "\\{v1\\}",
                manifestPlaceholderReplacement = "https://4read.org/m3u/",
                searchDoor = "https://4read.org/index.php?do=search&subaction=search&story={q}",
                // #741: the release browser door is retired with the scam
                // decision — no 4read surface opens in a release build.
                releaseBrowserDoor = false
            )
        ),
        SourceFacts(
            id = "soundbooks",
            displayName = "Sound-Books",
            homeUrl = "https://sound-books.net",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 1,
            referer = RefererRule("https://sound-books.net/", setOf("arch.sound-books.net")),
            transportHosts = setOf("sound-books.net", "arch.sound-books.net")
        ),
        SourceFacts(
            id = "sluhayua",
            // «Sluhay UA» vs sluhay.com's «Sluhay» — the disambiguating name
            // (Android's old badge said «Sluhay» for both; the consumer
            // migration unifies onto this one, ADR-0038).
            displayName = "Sluhay UA",
            homeUrl = "https://sluhay.com.ua",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 2,
            searchUrl = "https://sluhay.com.ua/find/allcards?search={q}&page=1",
            searchHeaders = mapOf("x-requested-with" to "XMLHttpRequest"),
            transportHosts = setOf("sluhay.com.ua")
        ),
        SourceFacts(
            id = "sluhay",
            displayName = "Sluhay",
            homeUrl = "https://sluhay.com",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.BROWSER,
            order = 3,
            referer = RefererRule("https://sluhay.com/"),
            transportHosts = setOf("sluhay.com", "redirectto.cc"),
            browserProfile = BrowserProfileFacts(
                pageHosts = setOf("sluhay.com"),
                audioHosts = setOf("sluhay.com", "redirectto.cc"),
                manifestProbe = true
            )
        ),
        SourceFacts(
            id = "audiobookmp3",
            displayName = "audiobook-mp3",
            homeUrl = "https://audiobook-mp3.com",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 4,
            // #527 — the site referer is scoped: the book pages and covers on
            // audiobook-mp3.com, plus the media CDN (`*.redirectto.cc`) that
            // 403s without it. Never a third party (SEC-004).
            referer = RefererRule(
                "https://audiobook-mp3.com/uk",
                setOf("audiobook-mp3.com", "redirectto.cc")
            ),
            transportHosts = setOf("audiobook-mp3.com", "redirectto.cc")
        ),
        SourceFacts(
            id = "lihtar",
            displayName = "Lihtar",
            homeUrl = "https://lihtar.in.ua",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 5,
            streamOnly = true,
            transportHosts = setOf("lihtar.in.ua")
        ),
        SourceFacts(
            id = "librivox",
            displayName = "LibriVox",
            homeUrl = "https://librivox.org",
            contentLanguage = "",
            accessMode = SourceAccessMode.DIRECT,
            order = 6,
            catalogUrl = "https://librivox.org/api/feed/audiobooks/?format=json",
            transportHosts = setOf("librivox.org", "archive.org")
        ),
        SourceFacts(
            id = "audiobookcoua",
            displayName = "Audiobook.co.ua",
            homeUrl = "https://audiobook.co.ua",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 7,
            catalogUrl = "https://audiobook.co.ua/novinki-ozvuchivaniya/",
            transportHosts = setOf("audiobook.co.ua")
        ),
        SourceFacts(
            id = "chytaylo",
            displayName = "Читайло",
            homeUrl = "https://chytaylo.com.ua",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 8,
            transportHosts = setOf("chytaylo.com.ua")
        ),
        SourceFacts(
            id = "ukrainianaudiobooks",
            displayName = "Ukrainian Audiobooks",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.BROWSER,
            order = 9,
            streamOnly = true,
            transportHosts = setOf("ukrainianaudiobooks.com"),
            browserProfile = BrowserProfileFacts(
                pageHosts = setOf("ukrainianaudiobooks.com"),
                audioHosts = setOf("ukrainianaudiobooks.com"),
                manifestProbe = true,
                releaseBrowserDoor = false
            )
        ),
        SourceFacts(
            id = "telegram",
            displayName = "Telegram",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.UNKNOWN,
            order = 10,
            transportHosts = emptySet()
        ),
        SourceFacts(
            id = "sluhayknigi",
            displayName = "SluhayKnigi",
            homeUrl = "https://sluhayknigi.com",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.BROWSER,
            order = 11,
            referer = RefererRule("https://sluhayknigi.com/"),
            transportHosts = setOf("sluhayknigi.com", "redirectto.cc"),
            browserProfile = BrowserProfileFacts(
                pageHosts = setOf("sluhayknigi.com"),
                audioHosts = setOf("sluhayknigi.com", "redirectto.cc"),
                manifestProbe = true
            )
        ),
        SourceFacts(
            id = "knigionline",
            displayName = "Knigi-Online",
            homeUrl = "https://knigi-online.com.ua",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 12,
            catalogUrl = "https://knigi-online.com.ua/audioknyhy/",
            searchUrl = "https://knigi-online.com.ua/?s={q}",
            transportHosts = setOf("knigi-online.com.ua")
        ),
        SourceFacts(
            id = "chitaka",
            displayName = "Читака",
            homeUrl = "https://chitaka.com.ua",
            contentLanguage = "uk",
            accessMode = SourceAccessMode.DIRECT,
            order = 13,
            catalogUrl = "https://chitaka.com.ua/audioknyhy/",
            transportHosts = setOf("chitaka.com.ua")
        ),
        SourceFacts(
            id = "local",
            displayName = "Локальна",
            accessMode = SourceAccessMode.DIRECT,
            order = -1,
            transportHosts = emptySet()
        )
    )

    private val byId: Map<String, SourceFacts> = entries.associateBy { it.id }

    /** The full declarative row of one source; null = unknown id (never invented). */
    fun facts(sourceId: String): SourceFacts? = byId[sourceId]

    /** Every registered id, in registry order. */
    fun ids(): Set<String> = byId.keys

    /** The registry's display name; unknown ids fall back to the raw id. */
    fun displayName(sourceId: String): String = byId[sourceId]?.displayName ?: sourceId

    /** The declared Source Access Mode; unknown ids are UNKNOWN. */
    fun modeFor(sourceId: String): SourceAccessMode = byId[sourceId]?.accessMode ?: SourceAccessMode.UNKNOWN

    /** Stream-only verdict from the registry; unknown ids are not stream-only. */
    fun streamOnlyFor(sourceId: String): Boolean = byId[sourceId]?.streamOnly == true

    /** The scam sources — audio that is never the book. */
    fun scamIds(): Set<String> = entries.filter { it.scam }.map { it.id }.toSet()

    /** Whether [sourceId] is a scam source; unknown ids are not. */
    fun isScam(sourceId: String): Boolean = byId[sourceId]?.scam == true

    /** All ids in the ONE registry order (lower [SourceFacts.order] first). */
    fun orderedSourceIds(): List<String> = entries.sortedBy { it.order }.map { it.id }

    /** The source's catalogue content language; "" = unknown. */
    fun contentLanguage(sourceId: String): String = byId[sourceId]?.contentLanguage.orEmpty()

    /** The browser-recovery facts of a Browser Source; null = not a browser source. */
    fun browserProfile(sourceId: String): BrowserProfileFacts? = byId[sourceId]?.browserProfile

    /** The search-door URL template (`{q}` placeholder); null = no declared door. */
    fun searchDoorTemplate(sourceId: String): String? = byId[sourceId]?.browserProfile?.searchDoor

    /**
     * The scoped Referer rule of one source, applied to one concrete stream
     * URL: an empty [RefererRule.scopeHosts] applies always, a scoped rule
     * only to its hosts (exact or subdomain). Unknown sources and out-of-
     * scope hosts yield no header (SEC-004).
     */
    fun refererHeaderFor(sourceId: String, streamUrl: String): Map<String, String> {
        val rule = byId[sourceId]?.referer ?: return emptyMap()
        if (rule.scopeHosts.isEmpty()) return mapOf("Referer" to rule.value)
        val host = hostOf(streamUrl) ?: return emptyMap()
        return if (rule.scopeHosts.any { host == it || host.endsWith(".$it") }) {
            mapOf("Referer" to rule.value)
        } else {
            emptyMap()
        }
    }

    /**
     * ADR-0039 / spec #681 T3 (#684) — true when [host] belongs to a
     * registered Source's transport allowlist (exact or subdomain). The gate
     * guards only Source hosts; enrichment and update hosts stay raw.
     */
    fun isSourceHost(host: String): Boolean {
        val normalized = host.lowercase()
        return entries.any { facts ->
            facts.transportHosts.any { allowed ->
                normalized == allowed || normalized.endsWith(".$allowed")
            }
        }
    }

    private fun hostOf(streamUrl: String): String? = try {
        java.net.URI(streamUrl).host?.lowercase()
    } catch (_: Exception) {
        null
    }
}