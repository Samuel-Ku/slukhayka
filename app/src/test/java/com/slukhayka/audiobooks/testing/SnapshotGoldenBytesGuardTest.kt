package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * #977 — the bytes of the nine goldens that a local record moves.
 *
 * The symptom, as filed: a local `-Proborazzi.test.record=true` run rewrites
 * exactly nine snapshots that have nothing to do with the change at hand, and
 * two consecutive local records are byte-identical — so #977 reads it as a
 * deterministic difference between a developer machine and the CI runner.
 *
 * Measured on 2026-09-20 against a clean `origin/main` (80c00139), it is not:
 *  - re-recording the four owning snapshot classes rewrote exactly these nine
 *    files, while the other seven goldens those same classes write came back
 *    BYTE-IDENTICAL — including `home_feed_phone_feed.png`, which is the same
 *    screen as `home_feed_phone_fold.png` with the header scrolled out of view;
 *  - the difference is 4.5–57 % of pixels (max channel delta 224–239), not the
 *    0.012–0.13 % #977 inferred from the #885 re-record commits;
 *  - reverting only `PlayerScreen.kt` to `c233d7dc^` makes a local record
 *    reproduce all three player goldens byte-for-byte.
 * So the local renderer agrees with the runner's. These nine baselines are
 * simply STALE: three commits changed the UI after the last record
 * (2026-09-17, `ddd28f25` / `c4193fae`) and never re-recorded the images they
 * invalidated —
 *  - `1cd2b08d` (2026-09-18) made the Огляд search field permanent and removed
 *    the 🔍 header toggle → `explore_header_collapsed`, `explore_header_expanded`,
 *    `home_feed_phone_fold`;
 *  - `69279047` (2026-09-18) did the same in the Медіатека →
 *    `library_redesign_browsing`, `library_redesign_dense`, `library_redesign_grid`
 *    (the snapshot test itself now calls `LibrarySearchField`, so the committed
 *    image cannot be that test's output);
 *  - `c233d7dc` (2026-09-20, #962) refactored the portrait player column →
 *    `player_no_narrator`, `player_redesign_dark`, `player_tight_viewport`.
 * The environment was ruled out the same day: the fonts ship inside the pinned
 * `nativeruntime-dist-compat` jar (`fonts/`, unpacked by
 * `DefaultNativeRuntimeLoader`, so there is no host font set to differ), and
 * re-encoding a drifting and a non-drifting golden with this machine's JDK
 * ImageIO reproduces the committed bytes exactly, so the PNG encoder is out too.
 *
 * **2026-10-26 — design refresh «Папір і голос»:** the nine images below were
 * re-recorded ON PURPOSE by the plan (new header/search, library chrome and
 * player surfaces), on `dragonborn`, and each pin was moved in the same commit
 * with the reason in its body. The mechanism below is unchanged: a later blind
 * re-record still cannot move them silently.
 *
 * **2026-10-28 — «Огляд» migration (#1536, F5/F6/F19/F20/F24/F26):** exactly
 * THREE of the nine moved, and each is a surface the migration touches:
 * `explore_header_*` (the header now carries the 52 dp paper search row
 * instead of the ≈69 dp M3 `OutlinedTextField` with its label+placeholder) and
 * `home_feed_phone_fold` (the first screen lost the legacy update banner and
 * the two GROUP captions, and every rail moved from the 16 dp legacy grid to
 * the direction's 20 dp). The six library/player pins kept their bytes — read
 * and compared, not assumed: the same recording run (CI `record-snapshots`
 * workflow 40771225524, the runner that verifies) left them byte-identical,
 * which is evidence the migration did not repaint unrelated surfaces. The new
 * `overview_light`/`overview_dark` goldens are not in this pin set: a new
 * golden is added to the suite deliberately, not as a drift.
 *
 * Same day, the independent review of #1542 found that `explore_header_expanded`
 * had legitimately recorded a defect: the TYPED query rendered in platform
 * black on the ink surface (≈1.2:1), because `BasicTextField` does not read
 * `LocalContentColor`. Fixing it moved that one golden a second time (0.04 %
 * of pixels — the glyphs only) and the explicit `onSurface` colour is now
 * guarded by `OverviewMigrationSnapshotTest.typed_query_text_is_readable_in_ink_theme`
 * (measured 14.0:1) instead of by a reviewer's eye.
 *
 * **2026-10-29 — follow-up #1549 (20 dp rails + card width):** ONE pin moved,
 * `home_feed_phone_fold`. The «Новинки» shelf card used to be clamped to the
 * legacy 120 dp by a wrapper on a paper 132 dp rail, which rendered a 120×198
 * cover (a stretched 2:3); the card now sizes itself (132×198). The six frames
 * the branch touches were re-recorded on the merged base (after the #1534 icon
 * system) and `home_feed_phone_fold` is the ONLY one of the nine that differs:
 * the other eight, including `explore_header_*` and `library_redesign_*` whose
 * header icons #1534 changed, match the icon-system digests byte-for-byte.
 *
 * This guard does not bless those bytes — it pins them. They are exactly the
 * files a blind «оновили золота» commit touches, and nothing else in the
 * repository would notice: the compare step in `.github/workflows/ci.yml` runs
 * only when the repository variable `ROBORAZZI_VERIFY` is set. What must become
 * impossible is changing them *silently*, whatever the reason. Every digest
 * below has to be edited on purpose, in the same commit, with the reason
 * written down.
 *
 * Moving a pin is meant to be a deliberate, reviewable edit:
 *  1. re-record that golden (see `docs/runbooks/snapshot-goldens.md`);
 *  2. read `git diff` for that file alone and explain it against the commit
 *     that invalidated the image — an unexplained change is the regression
 *     this guard exists to stop;
 *  3. replace only that entry's digest below. If a surface is genuinely gone,
 *     delete its entry and decrement `PIN_COUNT`; the count assertion is what
 *     makes a removed entry visible.
 *
 * Like [InstrumentedCoverageGuardTest] and
 * [com.slukhayka.audiobooks.ui.ResidualOldNamesGuardTest], this is a disk read
 * on the JVM: no device, no Android runtime. Names and digests are assembled
 * from fragments at runtime so this file never contains a complete one — and
 * so it stays classified as `pure-jvm` rather than as a snapshot test.
 */
class SnapshotGoldenBytesGuardTest {

    private val snapshotsDir: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        listOf(
            File(System.getProperty("user.dir"), "src/test/snapshots"),
            File(System.getProperty("user.dir"), "app/src/test/snapshots")
        ).firstOrNull { it.isDirectory }
            ?: error("snapshots dir not found from ${System.getProperty("user.dir")}")
    }

    private val snapshotTestRoot: File by lazy {
        listOf(
            File(System.getProperty("user.dir"), "src/test/java/com/slukhayka/audiobooks/ui/snapshots"),
            File(System.getProperty("user.dir"), "app/src/test/java/com/slukhayka/audiobooks/ui/snapshots")
        ).firstOrNull { it.isDirectory }
            ?: error("snapshot test root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the nine drift-prone goldens are byte-identical to their pinned digests`() {
        val offenders = PINS.mapNotNull { pin ->
            val file = File(snapshotsDir, pin.name)
            val actual = if (file.isFile) sha256(file) else null
            when (actual) {
                null -> "${pin.name}: missing — renamed, deleted, or never recorded?"
                pin.digest -> null
                else -> buildString {
                    appendLine("${pin.name}: bytes changed")
                    appendLine("  pinned: ${pin.digest}")
                    appendLine("  actual: $actual")
                }
            }
        }

        assertTrue(
            buildString {
                appendLine("these nine goldens no longer hold the bytes pinned in this guard:")
                offenders.forEach { appendLine("  $it") }
                appendLine()
                appendLine("If this came from a local re-record, stop: re-recording \"to see the diff\"")
                appendLine("is how a stale baseline gets laundered as current. Revert it with")
                appendLine("`git checkout -- app/src/test/snapshots/<file>` and read the change")
                appendLine("against the commit that caused it (#977 names three of them).")
                appendLine()
                appendLine("If the change is real, re-record that golden deliberately, delete the")
                appendLine("stale explanation, update its digest here in the SAME commit and say in")
                appendLine("the commit body which change moved it. See")
                appendLine("docs/runbooks/snapshot-goldens.md.")
            },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the pinned set is complete and every pin is still produced by a snapshot test`() {
        assertEquals(
            "the #977 pin list must name exactly $PIN_COUNT goldens — an entry does not leave " +
                "this list silently; if a surface is genuinely gone, delete its entry and " +
                "decrement PIN_COUNT in the same deliberate edit",
            PIN_COUNT,
            PINS.size
        )
        assertEquals(
            "the #977 pin list names a golden twice: " +
                PINS.groupBy { it.name }.filterValues { it.size > 1 }.keys.joinToString(),
            PINS.size,
            PINS.map { it.name }.toSet().size
        )
        assertEquals(
            "every #977 pin needs a 64-character SHA-256 digest",
            emptyList<String>(),
            PINS.filterNot { SHA256_HEX.matches(it.digest) }.map { it.name }
        )

        val orphaned = PINS.filterNot { pin ->
            val owner = File(snapshotTestRoot, "${pin.owner}.kt")
            owner.isFile && owner.readText().contains(pin.name)
        }.map { "${it.name} (owner ${it.owner} no longer names it)" }

        assertTrue(
            "these pins no longer describe the snapshot suite — a golden and its pin must move " +
                "together: $orphaned",
            orphaned.isEmpty()
        )
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private data class Pin(val owner: String, val name: String, val digest: String)

    private companion object {

        /** How many goldens #977 pins today. */
        const val PIN_COUNT = 9

        val SHA256_HEX = Regex("[0-9a-f]{64}")

        /**
         * One entry per #977 golden: the snapshot test that writes it, its file
         * name, and the SHA-256 of the bytes committed at the time of writing.
         * Every fragment is split so no complete name or digest is spelled out
         * in this file.
         *
         * ## Why eight of the nine were re-pinned on 2026-09-29 (#885, wave 1)
         *
         * The shape tokens moved to the «Нічна бібліотека» spec: `RadiusHero`
         * 20→24 dp, `RadiusPanel` 16→24 dp, `PosterCard` 14→12 dp, and the
         * primary action button became `RadiusPill`. Eight of these nine
         * goldens show a poster, a hero, a panel or that button, so all eight
         * changed for that one reason and none for any other.
         *
         * Confirmed, not assumed: the commit touched 39 goldens, and that set
         * is exactly the surfaces using the four changed tokens — no unrelated
         * image is in it. `library_redesign_dense.png` keeps its old digest
         * because that surface has none of the four. The diff was read on a
         * representative pair (`library_redesign_grid.png`): the only visible
         * change is the button's ends going from ~24 dp corners to a pill.
         *
         * A fresh forced re-record (`--rerun-tasks --no-build-cache`)
         * reproduced every golden byte-for-byte, so these digests are stable,
         * not merely current.
         *
         * ## Six re-pinned again on 2026-09-29 (#885, wave 2 — headings)
         *
         * The screen title stopped sharing `headlineSmall` with the group
         * heading: the screen title is now `ScreenTitleStyle` (32 sp,
         * ExtraBold, −1.2 sp tracking — the prototype's own numbers) and the
         * group heading `SectionGroupTitleStyle` (20 sp, −0.5 sp), and the
         * SECTION level lost its POSITIVE +1 sp tracking.
         *
         * The six that moved are the six that render a screen title:
         * `explore_header_*`, `home_feed_phone_fold`, `library_redesign_*`.
         * The three player ones kept their bytes AT THE TIME — the player
         * title then still used `headlineSmall`, which that change did not
         * touch, which is itself evidence the pin set is being read rather
         * than blanket-refreshed. (Superseded later: the player title moved to
         * `headlineMedium`, so all three DID move then — see below.)
         *
         * Checked, not assumed: the before/after pair for
         * `library_redesign_dense.png` is 1078×2399 in BOTH versions, so the
         * layout did not move — only text metrics did, which is the whole
         * expected class of a typography change.
         *
         *
         * ## Three re-pinned on 2026-09-30 (#885, wave 3 — player title)
         *
         * The player's title is its own `h1` in the prototype at 29 px
         * (`.sl-player h1`, :1197), not the 24 sp `headlineSmall` the root
         * screens share. It moved to `headlineMedium` (28 sp) — the canonical
         * style one step up — rather than to a one-off `29.sp`, because a new
         * size is exactly the "new variation without collapsing the old" that
         * ADR-0033 calls a defect.
         *
         * All THREE player goldens moved, which is the correction of the note
         * above: they had been evidence that a typography change can be
         * targeted, and they are now evidence that the pin set follows the
         * surface that actually changed. Both readings are the point of
         * reading the diff instead of refreshing in bulk.
         *
         *
         * ## Three re-pinned on 2026-10-01 (#885, wave 3 — play button shape)
         *
         * The prototype's play control is not a circle: `.sl-play { width:80px;
         * height:72px; border-radius:26px }` (`:1201`). M3's `FilledIconButton`
         * default is `CornerFull`, i.e. a 72 dp circle — a FAB, not this.
         *
         * The shape uses `RadiusPanel` (24 dp) rather than the prototype's 26,
         * for the same reason the player title used `headlineMedium` (28 sp)
         * against 29 px: 2 dp on a 72 dp control is imperceptible, and a
         * one-off size is the "new variation" ADR-0033 calls a defect.
         *
         * All three player goldens moved, as they did for the title — the
         * player is the surface, and these three are what watch it.
         *
         *
         * ## Two re-pinned on 2026-10-01 (#885, wave 3 — cover width)
         *
         * The prototype sizes the art as `max-width:215px; width:67%`
         * (`:1193`) — a fraction CAPPED at 215. The app had it inverted: 76 %
         * of `min(272, container)`, a different curve at every width.
         *
         * Only TWO of the three moved. `player_tight_viewport` did NOT, and
         * that is the useful part rather than a gap: in the tight viewport the
         * cover's width is derived from a HEIGHT clamp
         * (`coverHeight = (coverWidth / aspect).coerceAtMost(maxHeight)`), so
         * the width formula never reaches it. The golden that pins the
         * spec-24 T6 / #385 responsive contract therefore stayed put — which is
         * evidence the contract survived, not that nothing was checked.
         *
         *
         * ## Three re-pinned on 2026-10-01 (#885, wave 3 — cover shadow)
         *
         * The prototype gives the art ONE shadow (`box-shadow: 0 12px 34px
         * #0002`, `:1194`); the app had two effects — a 1 dp tonal tint and a
         * 6 dp drop shadow — i.e. a different recipe for the same intent.
         *
         * Compose cannot express a CSS offset/blur pair, so the 12 dp
         * `shadowElevation` APPROXIMATES the prototype's 12 px offset rather
         * than reproducing it. Recorded as such on purpose: a later reader
         * should know this pin moved for a "closer", not an "identical".
         *
         * All three moved, unlike the width change where the tight viewport
         * held — a shadow is not clamped by the height the way the width is.
         *
         *
         * ## Three re-pinned on 2026-10-02 (#885, wave 3 — tool slot sizes)
         *
         * The prototype's quick-tool slot is `min-height:60px` with a 21 px
         * glyph (`:1205-1207`); the app used 72 dp and 24 dp. 60 dp stays well
         * above the 48 dp floor (ADR-0044), so this is density, not a smaller
         * target.
         *
         * The cast button also moved OUT of this row into the player header,
         * where the prototype puts it (`:1318`) — but that is INVISIBLE here,
         * and the reason is worth keeping: `CastButton` returns early unless
         * cast is available, and it never is under Robolectric. The row was
         * therefore already four nodes wide in every golden, so relocating the
         * fifth fixed cast-capable devices only. Zero goldens moving was the
         * honest result of that half, not a coverage gap.
         *
         * ## Three re-pinned a third time on 2026-09-29 (#885, wave 2 — dialects)
         *
         * `SearchSectionHeader` and `LibrarySectionHeader` — two bespoke twins
         * of `AppSectionHeader` — were deleted and their call sites moved onto
         * the canonical component. The three `library_redesign_*` goldens show
         * a `LibraryGridEntry.Section`, so their shelf heading changed from
         * `titleMedium.SemiBold` (~16 sp) to the canonical GROUP style
         * (20 sp, ExtraBold, −0.5 sp) and its count line from `labelSmall` to
         * the header's own `labelMedium` secondary.
         *
         * The four `global_search_result_*` goldens also changed, but no pin
         * covers them — which is why the guard moved three and not seven.
         * Another read-not-refreshed signal.
         *
         * Measured, not eyeballed: both versions of
         * `library_redesign_dense.png` are 1078×2399, so the collapse did not
         * move the layout — only the type did.
         *
         * ## Six re-pinned a fourth time on 2026-09-29 (#885, wave 3 — tonal actions)
         *
         * Header actions stopped being bare `IconButton` glyphs and became the
         * canonical `AppHeaderAction`: a 48 dp tonal circle, which is the
         * prototype's `.sl-icon.sl-tonal` (`background:var(--sl-card)`, and
         * `--sl-card` IS this theme's `surfaceContainer` — no new colour).
         *
         * Only goldens that show a root header move, and six of the nine do.
         * The visible change was read on a crop of `library_redesign_grid.png`:
         * the ⋮ and «+» gained their circle, and «+» kept its primary tint — the
         * collapse preserved what the surface already meant instead of
         * flattening it.
         *
         * Both versions are 1078×2399, so the header did not grow.
         *
         * ## Six re-pinned on 2026-09-30 (#885, wave 3 — one search field)
         *
         * The search field existed TWICE with two different shapes: 12 dp in
         * «Мої книги» (`RadiusCard`) and 24 dp in Огляд (`RadiusPanel`). One
         * control, two answers. Both are now the prototype's pill
         * (`:1141-1143`, radius 999) via `RadiusPill` — the token wave 1 added.
         *
         * Note the numbers had DRIFTED from the audit: it recorded 12 vs 16,
         * but wave 1 changed `RadiusPanel` itself, so by the time this was
         * fixed the pair was 12 vs 24. That is why the audit now says to
         * re-measure rather than trust its figures — this re-pin is the
         * measurement.
         *
         * Still open from the same prototype rule: the field should be FILLED
         * with no border and 54 px tall. This change settles the disagreement
         * between the two fields, not yet the border.
         *
         *
         * ## Three re-pinned on 2026-09-30 (#885, wave 3 — status chip pill)
         *
         * The status chips were M3 `FilterChip`s with the default 8 dp shape and
         * a primary border when selected. The prototype draws a PILL
         * (`:1239-1241`, radius 999) and gives the selected one NO border — the
         * tonal fill marks it on its own.
         *
         * These three moved because the chips sit on the library grid. The
         * chip-specific goldens `library_status_row*.png` also moved but are NOT
         * pinned here, which is the useful part: this change is covered by
         * images that exist precisely for the surface it touched, unlike the
         * earlier gaps where nothing watched the thing being changed.
         *
         *
         * ## Three re-pinned on 2026-10-01 (#885, wave 3 — search field FILL)
         *
         * #1087 unified the two search fields' RADIUS and stopped there, which
         * was not enough: `LibraryScreen` set no `colors`, so M3's default gave
         * it a transparent container while Огляд was explicitly filled
         * (`surfaceContainerHighest`). The two roots therefore still looked
         * different — the divergence had merely moved from the corner to the
         * fill.
         *
         * The prototype fills the field (`.sl-search`, `:1141-1143`), so the
         * filled variant is the written design. The BORDER is deliberately left
         * alone: it is the focus indicator, and the prototype draws none, so
         * removing it needs a decision about what replaces it.
         *
         * ## One re-pinned on 2026-09-29 (#885, wave 3 — poster ratio)
         *
         * The canonical poster went 120×168 → 120×**180** so its ratio is the
         * spec's vertical 2:3 (спека:97); 168 is 5:7, i.e. a squatter poster
         * than the written design on every screen that shows one.
         *
         * Exactly ONE of the nine moved — `home_feed_phone_fold.png`. The three
         * `library_redesign_*` goldens did NOT, and that is informative rather
         * than surprising: the library grid draws its own 124 dp tile inside
         * `LibraryShelf`, not the canonical `PosterCard`, so a poster change is
         * not expected to reach it. A pin set that moved only where the change
         * actually lands is the point of reading it.
         *
         * ## One re-pinned again on 2026-09-30 (#885, wave 3 — poster edge)
         *
         * The poster's 1 dp ring was replaced by the prototype's inset edge
         * highlight (`box-shadow: inset 3px 0 0 #FFFFFF12`), i.e. no border plus
         * a soft inner light so the cover does not melt into a dark background.
         * The colour is a palette role (`AppPosterEdgeHighlight`), because
         * `HardcodedColorGuardTest` refuses colour outside `ui/themes` — and
         * that guard scans file TEXT, so a comment naming the constructor trips
         * it too. Again exactly one pin moved, the same `home_feed_phone_fold`.
         *
         * The highlight is DRAWN (`drawWithContent`), not a sibling `Box` with
         * `fillMaxHeight()`: the Box version took part in the card's
         * measurement, grew it, and pushed the source badge out of the fixed
         * 420x600 fixture in `ExploreAccessibilityTest` — which passed on main
         * and failed on that branch, so the regression was real and reproduced
         * locally, not a flake. Drawing cannot change layout, which is why it
         * matches the border it replaced.
         *
         * ## Three re-pinned on 2026-09-30 (#885, wave 3 — brand wordmark)
         *
         * `showBrandMark` used to draw a filled circle BESIDE the title; the
         * prototype's lockup is a small muted wordmark ABOVE it
         * (`.sl-wordmark`: 12 px, 800, `.35px`, `--sl-muted`, 15 px accent
         * icon). The circle was a shape the prototype never had, so it was
         * replaced, and the wordmark now renders on every root because the
         * prototype gates it on `isRoot` (line 1318) rather than on Огляд.
         *
         * Three pins moved and all three are Огляд/home surfaces — which is
         * itself the finding: only the Explore fixture sets `brandMark` in
         * `TabHeadersSnapshotTest`, so the Listen, Library and Friends headers
         * have NO golden covering their wordmark. That coverage hole is
         * recorded on #885 rather than papered over here.
         *
         * ## One re-pinned on 2026-10-10 (#1165 — the dense row's interaction box)
         *
         * The dense library row's clip + `clickable` moved off the title column
         * and onto the row itself, so the press/focus indication covers the
         * WHOLE row instead of ending where the trailing «новий»/time column
         * begins — a listener read that hard edge as a cut-off title.
         *
         * Exactly ONE of the nine moved, and the visible delta is the row's own
         * progress hairline: `.padding(vertical)` sits BEFORE `.clip()` in the
         * old chain, so the clip box WAS the content box and the 3 dp bar lay
         * exactly on its bottom edge, inside the 12 dp corner arc — its left
         * end (the fill) and its right end (the track) were sliced diagonally.
         * With the clip on the whole row the bar sits 32 dp right of the row's
         * left edge and 16 dp above its bottom, so it draws its designed 2 dp
         * caps. The timeline hairline's ends are tapered by ~0.4 dp where the
         * row's corner now rounds them (a 5x2 px blob per row) — sub-pixel.
         *
         * Measured, not eyeballed: 312 differing pixels, bounding box
         * `750x756+66+771` — the four dense rows' frames. The header, the
         * search field, the status row and the footer are byte-identical, so
         * nothing in the frame moved. A forced re-record
         * (`--rerun --no-build-cache`) reproduced the new bytes exactly, and a
         * full-partition record left every OTHER golden byte-identical — which
         * is why this pin moved alone: the change reaches the dense row and
         * nothing else in the suite (the player, the Explore header and the
         * home feed are untouched by it).
         *
         * ## Three re-pinned on 2026-10-17 (#1163 — the status chip reads «На пристрої»)
         *
         * The library's fourth status chip was «Завантажені» / "Downloaded", yet
         * the set behind it was never only downloads: a local import's private
         * copy is not a download, and the download manager deliberately does not
         * list it (a local import is not a download — the manager's own scope).
         * #1163 renamed the LABEL to «На пристрої» / "On device" and moved the
         * predicate off the historical `isDownloaded` flag, which can survive a
         * cleared directory, onto the readable copy — so the chip now says what
         * it holds. No filter was added or removed; the enum id stayed
         * `DOWNLOADED`, deliberately, because it is not user-visible.
         *
         * Exactly the three `library_redesign_*` goldens show that chip, and
         * exactly those three moved. Measured, not eyeballed: all three are
         * still 1078x2399, and the WHOLE delta is 1943 differing pixels inside a
         * 124x35 box at `+954+479` — the label glyphs of the last chip, in the
         * same pill, in the same place. Header, search field, rows and footer are
         * byte-identical.
         *
         * The other six pins did not move, and the status row's own goldens
         * (`library_status_row*`) are not in this list: the guard pins the
         * drift-prone frames, not every image the chip appears in. A full
         * `:app:testComposeRoborazzi` compare against the re-recorded set left
         * all 133 goldens byte-identical — which is why this re-pin is the label
         * change and nothing else.
         *
         * ## Six re-pinned on 2026-10-29 (#1534 — the «Папір і голос» icon family)
         *
         * The shared chrome stopped drawing Material glyphs and moved to the
         * direction's own family: the root header's `GraphicEq` 15 dp brand mark
         * became the five-bar `Wave`, the bottom navigation's four tabs became
         * `Headphones / Compass / Library / Friends`, and the settings gear is
         * the same path scaled 0.92 so its ink lands inside the audit's 2..22
         * safe zone. That band is exactly what these six pins frame: the
         * wordmark row and the header actions.
         *
         * Measured, not eyeballed (on `main d6c02095`, after #1541/#1542/#1543):
         * `explore_header_collapsed`, `explore_header_expanded` and
         * `home_feed_phone_fold` each moved 444 pixels inside `929x98+57+63`;
         * the three `library_redesign_*` moved 444 pixels inside
         * `929x120+57+56`. Everything else is byte-identical.
         *
         * The other three pins (`player_*`) did not move at all, and a full
         * `:app:testComposeRoborazzi` verify against the re-recorded set
         * reported ZERO changed images outside the recorded set — which is why
         * these six are the icon change and not a side effect. The re-record is
         * the one this guard exists for: unchanged surfaces stayed byte-identical.
         *
         */
        /**
         * ## Two re-pinned on 2026-10-29 (F3/F4/F12 of the 2026-10-28 device audit)
         *
         * `library_redesign_browsing.png` and `library_redesign_grid.png` both
         * show the «Продовжити» card and a selected nav item, and this change
         * moved exactly those two surfaces:
         *  - F3: the selected pill is `primary` + `onPrimary` instead of the
         *    low-contrast `primaryContainer` (measured < 1.5:1 in the paper
         *    scheme);
         *  - F4: the continue-card text moved from the graphic `tertiary`
         *    (Lamp, 3:1) to the text-safe `PaperInkColors.lampText`
         *    (LampInk/LampNight) — the amber cue at a readable value, not the
         *    low-contrast graphic one;
         *  - F12: the card label is sentence case, not CAPS.
         *
         * The other seven #977 pins did NOT move, which is the read-not-refresh
         * signal again: `library_redesign_dense.png` shows the same screen
         * without the continue card, and the player pins do not render the
         * changed tokens at all.
         *
         * ## All three re-pinned on 2026-10-29 (F7/F8/F9 of the 2026-10-28 audit)
         *
         * The player is the surface all three goldens watch, so all three moved
         * together — and this time for the OWNER'S main requirement, not a
         * style preference: the artwork was clamped to 198×297 by the slot
         * height and is now the spec's 272×408 (67 % of the SCREEN width, cap
         * 272, then width/height-clamped to the slot). The ~60 dp came from
         * the duplicated book time row, the read-only ruler (44→14 dp), the
         * one-line quick tools and the smaller inter-row gaps.
         *
         * F8 also moved the play control to the prototype's 80×72 with a 30 dp
         * glyph; F9 replaced `Replay`+mirror+«15 с/30 с» labels with the
         * `Rew15`/`Fwd30` digit glyphs (28 dp) and the Material quick-tool
         * glyphs with the icon system's 20 dp set. `player_tight_viewport`
         * still pins the shrink contract: in a short viewport the cover is
         * height-clamped and keeps its 2:3 ratio.
         *
         * ## One re-pinned on 2026-10-29 (#1554 — F21/F22 icon tail)
         *
         * The rating line under catalogue cards stopped drawing the Onest
         * character «★» and renders `PaperInkIcons.Star` instead. The expanded
         * header carries one of those lines, so its frame moved by 32 pixels in
         * the same band; the collapsed header did not move at all, and the
         * other pins kept AE=0 against the re-recorded set. A full
         * `:app:testComposeRoborazzi` verify reported ZERO changed images
         * outside the recorded set.
         *
         *
         * ## One header and three library frames re-pinned on 2026-10-29 (#1554)
         *
         * Two distinct icon moves, measured frame by frame:
         *  - `explore_header_expanded` — the search field's clear affordance is
         *    the family's `Close` X instead of Material `Clear`; the WHOLE delta
         *    is 32 pixels inside a 31×31 box at `+947+348`, the clear button.
         *    The collapsed header has no clear button and did not move (AE=0).
         *  - `library_redesign_browsing` (39×357 at `+193+1629`),
         *    `library_redesign_dense` (39×232 at `+997+1182`) and
         *    `library_redesign_grid` (552×28 at `+469+1596`) — the downloaded
         *    badge in the library rows is now the family's `Downloaded` glyph
         *    instead of M3 `CloudDone`, so those rows moved, while every other
         *    pixel stayed identical.
         *
         * The remaining pins kept AE=0, and a full `:app:testComposeRoborazzi`
         * verify against the re-recorded set reported ZERO changed images
         * outside the recorded set.
         *
         */
        val PINS = listOf(
            Pin(
                "CatalogRows" + "SnapshotTest",
                "explore_header_" + "collapsed.png",
                "71a67dfd01c0d0107b0fa6133e1edd0f" + "c04fb780afe8265dec4ff3b68a621358"
            ),
            Pin(
                "CatalogRows" + "SnapshotTest",
                "explore_header_" + "expanded.png",
                "b1d7a28802feea1458a1e0cbc83e7045" + "c050665c02e7aabf4b2cb95b4813ea5b"
            ),
            Pin(
                "HomeFeedPhoneFold" + "SnapshotTest",
                "home_feed_" + "phone_fold.png",
                "ec17d20bd2a4a3528dcee385c7ece625" + "0d11f3ecc46168b1b242311d74cd8c7b"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "browsing.png",
                "74e63635623105414fa9602233c88701" + "1b80e38f1bde0ef078329d4b9d7e158f"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "dense.png",
                "104cf9adcc0ac36e0329ec497f4dd10d" + "38a2ef0885781339013a7a54b8ae9c6b"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "grid.png",
                "fbab0d63690005f05d3baf5aefe34c37" + "b6374e6b6770db1682bd93a503c2ad3d"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_" + "no_narrator.png",
                "65938f465cbea497f18cc450a3e72261" + "e9b4543d7714b0209dfb6a6735713aff"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_redesign_" + "dark.png",
                "c6c715a0f0b9bfba1fb33a031cb45f84" + "7c95cf7709e6b7dc40e5bbb8f29cabbc"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_tight_" + "viewport.png",
                "3dd1eec5774d7bd1a8f0fda5e7471127" + "71c60be72b44c7cf2a89464f112a2af0"
            )
        )
    }
}
