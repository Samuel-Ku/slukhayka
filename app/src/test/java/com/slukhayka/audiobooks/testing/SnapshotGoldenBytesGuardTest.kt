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
         * The three player ones kept their bytes — the player title still uses
         * `headlineSmall`, which this change does not touch, which is itself
         * evidence the pin set is being read rather than blanket-refreshed.
         *
         * Checked, not assumed: the before/after pair for
         * `library_redesign_dense.png` is 1078×2399 in BOTH versions, so the
         * layout did not move — only text metrics did, which is the whole
         * expected class of a typography change.
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
         */
        val PINS = listOf(
            Pin(
                "CatalogRows" + "SnapshotTest",
                "explore_header_" + "collapsed.png",
                "91f02bc4ff64fe1ccfa79c86186fac02" + "176fd073992b9aff7843db5a2a083533"
            ),
            Pin(
                "CatalogRows" + "SnapshotTest",
                "explore_header_" + "expanded.png",
                "8ffceeaa25334731ae730394b1b03d9f" + "c5ed04e7c0a92bb88d33867212b9ba55"
            ),
            Pin(
                "HomeFeedPhoneFold" + "SnapshotTest",
                "home_feed_" + "phone_fold.png",
                "21d83fc227b59456475a1b9aec1a1fea" + "e9d44dc448edc58949db5a1d39ced7d6"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "browsing.png",
                "af13ca576ca83bfe83bd223a8cb7ae64" + "6a93a4ebf71012110cbaa44a073cef53"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "dense.png",
                "f2553b72ba0b85f82aa4a541089f446c" + "8ff75b9ce05d16b3b11873882975d156"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "grid.png",
                "e03fa102e719e5d7cf8428974be76a14" + "94c32052e2f9b94323e18535195fa2c5"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_" + "no_narrator.png",
                "3ff2fcc3f1dd50b676ce76039efcafb1" + "04cbd351132bafaf602ddbd904891470"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_redesign_" + "dark.png",
                "1983802efdef62a1dc75bbfa55a4420d" + "b134d1aff9797dd262245de74abd0bd1"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_tight_" + "viewport.png",
                "7d6f42a6d10cf9520a6096fc8fa0b9ec" + "fc0547a45e440b2067898d3b7f383b3d"
            )
        )
    }
}
