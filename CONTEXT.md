# Слухайка — Audiobook Library

The domain describes a personal audiobook library that unifies works, audiobook renditions, available sources, and a listener's state across devices.

## Library identity

**Work**:
The abstract authored book, independent of narration, language, file format, provider, or device. It owns bibliographic identity and series membership but not listening progress.
_Avoid_: Book, audiobook, copy

**Edition**:
A specific audiobook rendition of one Work, distinguished by language, narrator, audio content, and logical chapter structure; it owns the logical Chapter list and the listening totals (chapter count, duration). Sources with materially different narration or chapter topology belong to different Editions, and every Listening State row anchors to exactly one Edition.
_Avoid_: Version, copy, source

**Source**:
A provenance-bearing origin or copy through which one Edition may be played, such as a local folder, an M4B file, a 4read stream, or a downloaded copy. A Source can exist in the library even when it is not currently available on a device. A Source owns the physical tracks of its Edition — stream URLs, local file copies, content hashes, download state — aligned with logical Chapters one-to-one by index.
_Avoid_: Edition, book

**Source Access Mode**:
The static capability of a Source: `DIRECT` for a native HTTP path, `UNKNOWN`
for a legacy/unclassified path, or `BROWSER` when a live in-app browser
session is required (4read and the Cloudflare-backed sources). Recommendations
and automatic playback try local files first, then direct, unknown and browser
in that order. A browser Source is never opened as an implicit side effect of
a card tap; it is an explicit recovery/import action. This is a capability
order, not a health score, so a transient HTTP failure does not permanently
demote a Source.
_Avoid_: health-ranked Source order, silent browser launch

**Source Binding**:
A device's locator, permission, and availability relationship to a Source. Bindings are device-specific even when the Source identity is shared. No Binding rows exist yet — with a single device, locator and permission stay on the Source row; the Binding table arrives with device sync, not before. NOTE (spec-40): the Firestore collection `device_bindings` is NOT this domain concept — it is the reinstall-recovery anchor mapping a device id to the listener's own uid.
_Avoid_: Source, download

**Source Catalog**:
The union of browseable Works a Source exposes — sections, genres, series listings, people — fetched as Metadata Assertions on demand rather than stored wholesale.
_Avoid_: Store, browse cache

**Chapter**:
An ordered logical subdivision of one Edition to which positions and bookmarks can be anchored, independent of how a Source divides its files. A Chapter row carries order, title, and duration only — stream URLs, file paths, and content hashes belong to Source tracks.
_Avoid_: Track, file

**Series**:
A named bibliographic sequence or cycle containing ordered Works.
_Avoid_: Collection, shelf

**Series Membership**:
A Work's place in a Series, including its position when known. One Work may have more than one Series Membership.
_Avoid_: Series, volume file

## Metadata

**Metadata Assertion**:
A provenance-bearing claim about a Work, Edition, Chapter, or Series supplied by a provider, embedded tag, folder structure, or recognition process.
_Avoid_: Canonical metadata, truth

**Metadata Override**:
A listener-authored replacement for a metadata value. It takes precedence over Metadata Assertions and survives source refreshes and rescans.
_Avoid_: Metadata Assertion, corrected source data

**Canonical cover**:
The one cover URL of a Work in the shared metadata base (`book_covers`, keyed by the Work mergeKey — one cover per Work, shared across narrations). Covers are URL-only (never rehosted) and curated/seeded first: the curated seed pours the bundled asset idempotently, untrusted device write-backs are deferred until AppCheck + reporting exist. Reads are client-first — a locally known cover wins, the shared base fills the gap and mirrors into the local database via the existing cover write path (`updateCoverImageUrl`), the source's own claim is the last resort; corrupt documents are misses (spec-30 T3, ADR-0020).
_Avoid_: per-source cover claims, rehosting covers, fabricated URLs

## App

**Release**:
A published build of the app itself, tagged `v<versionName>` on GitHub Releases — the single source of truth for the in-app update banner. Not a book concept: never use «version» for an Edition or narration (that word is reserved here).
_Avoid_: version (for an Edition), update feed

## Listener relationship

**Library Entry**:
A listener's relationship to one Work, including library status and Metadata Overrides. Removing a Library Entry does not erase the underlying Work, Editions, or Sources.
_Avoid_: Book, collection item

**Listening State**:
A listener's progress, bookmarks, completion state, and playback preferences for one Edition. It is independent of the Source currently used to play that Edition — its row is keyed by Edition alone, so a Source switch never forks progress.
_Avoid_: Library Entry, playback progress

**Smart Rewind**:
The Listening State rule that a resume position steps back by how long the pause lasted. One rule serves both in-session resume and resume across restarts.
_Avoid_: sleep timer, resume offset

**Tombstone**:
A durable record that a listener intentionally removed or rejected a relationship, preventing imports, catalog refreshes, or sync from silently recreating it. Its identity anchors at the Work: one tombstone blocks every Edition and Source of that Work.
_Avoid_: Missing Source, unavailable file

**Progress Sync**:
The rule that one listener's Listening State mirrors across that listener's own linked devices, resolved last-write-wins by server time. It exists only between devices the listener deliberately linked and never carries Library Entries, Metadata Overrides, or anything belonging to another listener.
_Avoid_: backup, history upload, library sync

**Web Client**:
The browser surface of Слухайка — installable to the iOS home screen — where a listener browses the Source Catalog, plays Editions, and keeps a Listening State exactly like any other device. There is one listener relationship model across platforms; the Web Client introduces no second kind of profile.
_Avoid_: mobile site, native iOS app, companion viewer

**Listener Review**:
The one shared review («Відгук») a listener may leave per Work — a required 1–5 star rating plus optional bounded text and an optional narration tag (`editionTag`). Anchored at the Work mergeKey like Canonical covers; document identity `workId_uid` in the shared base's `book_reviews` collection makes double-voting impossible by construction. The headline score above the cards is the honest flat average over every source WITH a rating and every review (ADR-0022); a source's own ★ stays a separate row. A source page's visitors' comments are NOT reviews — they render as a plainly-labelled simple subblock, never mixed into community cards.
_Avoid_: source visitor comment, fabricated zeros

**Narration Rating**:
The listener's stars-only verdict (1–5) on one Edition of a Work — «як мені ось ця озвучка» — beside, never instead of, the Work-level Listener Review. One rating per (Work × Edition × listener); document identity `workId_uid_editionId` in the shared base's `edition_ratings` collection makes double voting impossible by construction, with the review's security model mirrored rule-for-rule (public read, owner-only writes under App Check, shape gate). Narration averages render by the narrator's name and on «Інші начитки» cards; they NEVER feed the book's headline average or recommendations (ADR-0023).
_Avoid_: mixing into the headline average, edition-scoped Listener Review (the old wording), stars + text

**Recommendation Preference**:
An explicit, local-only listener verdict that changes discovery ranking without changing a Work, Library Entry, Listening State or Listener Review. It is one of `HIDE_WORK`, `REDUCE_SIMILAR` or `HIDE_AUTHOR`, keyed by normalized Work/author identity and reversible from recommendation settings. It stores no embedding or listening history.
_Avoid_: deletion, tombstone, implicit dislike, server-side listening profile

**Recovery Code**:
The encoded credential pair of the silent anonymous profile («Код відновлення профілю»), shown in ⚙️ Профіль only behind BiometricPrompt and accepted on a fresh install — or in the Web Client — to restore or link the same uid. Surviving reinstall also rides Android Auto Backup of the generated credentials and the Firestore `device_bindings/{ANDROID_ID} → uid` silent restore — the binding exists ONLY for recovery of one's own profile, written solely for the caller's own uid.
_Avoid_: login screen, hardware identifiers (IMEI), password reset

## Playback

**Smart Rewind**:
One pure rule (`SmartRewind.rewoundPositionMs`): the longer the pause, the further back playback resumes (tiers of 3/12/25 s), clamped at zero. It serves both the in-session resume (live engine position) and the across-restart resume (persisted Listening State), so the two paths can never drift (ADR-0003).
_Avoid_: per-path rewind logic

**Self-healing stream URLs**:
A 404/403 stream failure re-resolves the book's source page through the `LibraryImport.refreshStreamUrl` door and retries ONCE with the fresh URL — the pure `StreamHealPolicy` decides (404/403 + heal budget of one per user-initiated chapter prepare). The index pairing heals only while the page still serves the other chapters at their own indices (a reordered page never heals — it would play the wrong chapter). An exhausted budget surfaces the honest «Книга недоступна» state — never a fabricated retry (ADR-0019).
_Avoid_: heal loops, retrying the same dead URL, index-based healing on reordered pages, synthesized substitute audio

## Architecture

**Module reads**:
Screens read the five deep modules' flows and suspend functions directly — `module.flow.collectAsState(...)` for flows, `rememberCoroutineScope().launch { module.suspendFun(...) }` for actions. MainViewModel keeps only composition, navigation and orchestration; the pure download/import outcome messages and the resume start-position decision live in `ui.library` (`OutcomeMessages`, `computeResumeStart`) with JVM tests (ADR-0008).
_Avoid_: forwarding StateFlows, 1:1 ViewModel forwarders

**Works and Library Entries**:
`audiobooks` is ONE concept — the metadata of the user's copy. The Work identity (mergeKey, series) lives in `works` (the shared spec-23 table, #142), the Library Entry (isFavorite, createdAt, downloadProgress) in `library_entries` (one row per audiobooks row, `workId` linking to the Work), and the per-book speed in the Listening State row (`playback_progress.preferredSpeed`). DAO reads join all three and fill `@Ignore` projections on `AudiobookEntity` (via the `BookRow` projection), so the UI keeps reading one shaped row while the persisted columns are gone (ADR-0009).
_Avoid_: fused columns on audiobooks

**Local facet projection**:
The indexed Room read model used by «Огляд» filters. Work facets carry canonical author/series references and indexed genre memberships; every genre membership is Source-owned, and a strictly newer Source document atomically replaces that Source's whole set (including an empty set) while equal/older replay is a no-op. Edition facets separately carry narrator, language, duration bucket, chapter count, full/abridged status and an expiring availability assertion. Edition availability is atomic (available, observed-at epoch milliseconds and bounded TTL), is stale at exact expiry, and never moves onto Work. Raw genre assertions keep their exact Source text, assertion identity and observation provenance beside the derived relation. `LocalFacetWriter` is the one bounded transactional write door for both local raw normalization and canonical shared assertions, and `WorkFacetFilter` is the frozen Paging query contract: values inside one dimension are OR, dimensions are AND, and an empty dimension is unfiltered. It is a projection of Metadata Assertions, never a replacement Work, Edition or Source.
_Avoid_: free-form `LIKE` filters, genre text as identity, duration on Work, direct facet-table writes from sync

**One Source seam, one HTTP transport**:
Captured-page import is a [SourceAdapter] capability — `parseCapturedPage(html, url)` with a "not mine" default; the WebView-pattern adapters (4read, sluhay) override it under one name and no import door downcasts to a concrete adapter. All HTTP goes through the shared [HttpFetcher] on the ONE shared OkHttp client (pool, identity, route, DoH): it serves text (`getText`) and binary streams (`getStream`), and the offline download loop consumes the stream method — every request carries the device's browser identity (the real system WebView User-Agent, static fallback on JVM; superseding ADR-0006's dedicated download agent) and rides the listener's network privacy route (spec-38), never silently falling back to direct. Domain names resolve through encrypted DoH with a transparent system-resolver fallback (spec-38 T4) — one decision independent of the chosen route, on by default. Offline downloads ride the human-rhythm pacing from the privacy door (`PacingPolicy`: random pause + per-domain burst budget; the loop owns no thresholds) so bulk fetching never looks like scraping (spec-38 T5). The relay prototype (spec-38 T6) is just another resolved route: requests are rewritten `<base>?url=<target>` at the transport's single request-shaping seam, never a default; a route WebView cannot carry refuses the browser instead of going direct. The source-browser WebView sessions ride the SAME route through the official webkit proxy controller and keep the same session hygiene (third-party cookies rejected, geolocation/sensors denied, cookies isolated per source by purge-on-entry with a source-scoped first-party snapshot restored on re-entry). Per-source header rules (Referer) stay beside the transport in the source package.
_Avoid_: per-adapter captured-page methods, raw HttpURLConnection in modules, app-named User-Agents, system-DNS lookups for transport hosts, special-cased relay branches outside the door, WebView sessions off-route

**4read browser recovery**:
When a 4read stream or offline chapter fails, the listener explicitly opens the
4read in-app browser and imports the current page. Parsed chapter identity and
order must match the stored Edition before Source tracks are updated; valid
downloaded files and Listening State are retained. The failed download queue is
paused and resumes on the next explicit download attempt. First-party browser
cookies are kept only in a source-scoped local session; they are never written
to Room, shared profile metadata, logs or requests to another host. A short
first-entry notice explains that 4read now needs the browser and is not shown
again after the listener has opened that session.
_Avoid_: browser auto-launch, track replacement by request-arrival order,
cross-source cookies, resetting progress during recovery

**Web Transport**:
The web-side transport door for the Web Client: it resolves Source pages into structured catalog and book data server-side, and relays an audio stream only when the Source refuses direct playback. Direct-to-source audio is the default path; relaying everything is not this concept.
_Avoid_: full-traffic proxy, client-side CORS workarounds

**Bibliographic Work, Edition-owned narrator**:
The Work is `title|author` — the mergeKey carries NO narrator. The narrator is an Edition (rendition) property: it lives on `editions` and on the audiobooks row (the user's copy of one rendition), never on `works`. The Edition id hashes `mergeKey|narrator|language`, so two narrations of one Work never share listening state (ADR-0001) even though they share the Work (ADR-0010).
_Avoid_: narrator in the mergeKey, narrator on the Works row

**Multi-Edition library**:
Import dedup is per RENDITION (Edition id), not per Work — the same narration merges into its card, a DIFFERENT narration of the same Work creates a NEW card of that Work (own audiobooks row, own Library Entry under the same `workId`, own Edition/chapters/sources/tracks/progress). The book page lists the other rendition cards in the «Інші начитки» block via the pure, JVM-tested `siblingNarrations` (ADR-0011). No schema change — the v16 split already allowed several entries per Work.
_Avoid_: one card per Work with unreachable second narrations

**Smart collections**:
The «Колекції» Огляд block is curated external lists (Нобелівські лауреати, Шевченківська премія, Букер) shipped as static JSON assets (`assets/collections/`), matched locally against the catalog union. The strict decoder (`CollectionJson`) and the matcher (`CollectionMatcher`) are pure JVM; the matcher reuses the MergeKey normalization plus diacritics (Cyrillic-preserving) and parenthetical-annotation trimming, requires author agreement, and hides non-matches (author-only fallback for title-less entries). `SourceCatalog.smartCollections` is recomputed on the SAME trigger as the union (`refreshUnifiedCatalog`); empty collections are dropped, nothing is persisted — no schema change (ADR-0012).
_Avoid_: network lists, Room persistence of match results

**Live collections**:
The same matcher also consumes LIVE lists over the `LiveCollectionSource` seam (keyless OpenLibrary trending → «Популярне зараз»; sluhay.com.ua most-viewed → «Популярне у sluhay.com.ua»; sound-books.net top-100 → «ТОП-100 sound-books», spec-37), fetched through the shared HttpFetcher on the union refresh, TTL-cached per source like the feeds, best-effort (failure → no collection, never a broken refresh). Static + live feed one `matchAll`; the JSON parser behind the assets is the shared pure-JVM `MiniJson` (ADR-0013).
_Avoid_: raw connections in live sources, live lists persisted to Room

## Discovery surfaces (spec-28)

**Private recommendation adaptation**:
The bundled E5 encoder remains frozen and local; `RecommendationPersonalization` adapts the transparent positive/negative profile and five ranking coefficients over Work-level vectors. Weak 30%/70% progress fades only after day 30 and reaches zero at day 180, while deliberate durable signals do not decay. Explicit preferences live in their own Room table. Shared weekly learning has a pure validation/aggregation contract but no production upload path; `PRODUCTION_UPLOAD_ENABLED` remains false until the privacy, legal and security gates in #290 pass.
_Avoid_: fine-tuning E5 on-device, treating pauses/non-completion as dislike, uploading Works or history, calling weekly IDs anonymous

**Cross-source «Новинки» rail**:
One Огляд rail merging the new-arrival books of every Source — 4read's «Новинки» section plus the other sources' new feeds — into a single Work-deduplicated list with a per-Source badge on each card (`SourceCatalog.newArrivals`). It is published on both union triggers (`refreshSourceFeeds` + `fetchCatalogSections`) so it always reflects the fresher input, and the 4read «Новинки» catalogue section row is skipped on Огляд so 4read's new arrivals appear exactly once (spec-28 #192).
_Avoid_: per-source «Нове» rows, duplicate 4read sections

**«Серії» index screen**:
A pushed screen listing every Series aggregated from the Source Catalog sections (the «Цикли» row), deduplicated by URL via the pure `CatalogSeriesIndex`; tapping one opens the existing series page with its books and universe context. No new series data source — it only indexes what the catalogue parser already produces (spec-28 #189).
_Avoid_: inline-only series row, new series data source

**«Колекції» index screen**:
A pushed screen listing every matched smart collection (Нобелівські лауреати, Шевченківська премія, Букер, live lists); tapping a book resolves-and-plays it exactly like the inline collection cards — the move changes location, not behaviour (spec-28 #190).
_Avoid_: duplicated collection behaviour, new collection data source

## UX principles (spec-27)

The standing UI rules distilled from the four UX reviews (spec-27 #184). New screens must satisfy them; a change that violates one needs a recorded decision, not silence.

**One book, one card**:
Every Work renders as exactly one card on a screen — duplicate rows, raw SEO-suffixed titles, and the same book styled two ways at once are defects (write-path normalization + the one-time DuplicateWorkMerger; Listen cross-shelf dedup).
_Avoid_: «Трохи ненависті» + «… - АудіоКниги Українською» as two rows

**Destructive actions never look neutral**:
Deleting files is a red, explicitly-labelled action behind a confirmation quoting the exact scope (count + size). A refresh-looking button must never wipe data (ClearCacheConfirmDialog, ⋮ → «Завантаження та пам'ять»).
_Avoid_: «Очистити» next to neutral storage info without a dialog

**Numbers match the user's experience**:
A displayed count, percent or duration must be the truth the user can verify — the library counter is live (Room flow), the hero percent is cumulative, unknown durations render as absent, never «00:00» (ADR-0014).
_Avoid_: stale counters, in-chapter percent passed off as book progress, fabricated 4:00:00

**One tool, one place**:
Each capability has one obvious home: import is «+ Додати» (a sheet), genre filtering lives only behind the feed's «Фільтри» control (spec-42 #302 superseded the separate homepage «Жанри» navigation row), destructive delete lives in ⋮. Duplicate affordances (three genre rows, two import buttons) are the defect this rule kills.
_Avoid_: the same action reachable from competing buttons/chips

**A landing answers one question**:
Each tab answers one question — Слухати «що я слухаю?», Огляд «що знайти?», Медіатека «де мої книги?» — with its real content above the fold and the endless feed always last.
_Avoid_: discovery mixed into the listening tab, an infinite feed burying curated shelves

**Control-to-content ratio**:
Chrome rows (headers, chips, filters) stay a small fraction of the screen; content starts above the fold. When chrome grows, collapse it (search as an expanding icon, rare filters in a sheet).
_Avoid_: six stacked chrome rows before the first book

**Only truthful data**:
While a value is unknown, render the loading state («довантажуємо серію…»), not a provisional number; a failed resolution contributes nothing (best-effort, silent). Numbers only when they are real (ADR-0014).
_Avoid_: «1 книг у циклі» as a placeholder while resolution runs

**Rare actions live in secondary menus**:
Frequent actions are first-class buttons; rare or destructive ones hide in ⋮ / long-press / confirmation dialogs — reachable, never in the way.
_Avoid_: delete/share icons at the same rank as play
