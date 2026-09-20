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
         */
        val PINS = listOf(
            Pin(
                "CatalogRows" + "SnapshotTest",
                "explore_header_" + "collapsed.png",
                "bd0300814faa1f06b7c2846687e67c06" + "fac2e143d800dda8dd162ffb162aafdd"
            ),
            Pin(
                "CatalogRows" + "SnapshotTest",
                "explore_header_" + "expanded.png",
                "da0b2d0b30a41c95ee378c08fea9b441" + "80bca54a8bf9c17c754fc99b40d3003c"
            ),
            Pin(
                "HomeFeedPhoneFold" + "SnapshotTest",
                "home_feed_" + "phone_fold.png",
                "15ea919abc8b7b130247fbed53ef1ffa" + "0559c67c8b979c10f816b278d6a95cd1"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "browsing.png",
                "5ce36ce18c1534b7cf4cdcbae12c9bea" + "7ef3413598b087a3df9f7279b7c9bef0"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "dense.png",
                "6644ba90731e429ab6793d36a475693e" + "085da3cd4856d00f706980fe9739448a"
            ),
            Pin(
                "LibraryRedesign" + "SnapshotTest",
                "library_redesign_" + "grid.png",
                "137bf568875d0b82f8374543b3cdd24e" + "a8a7f0834719e2076aeeb64f93329208"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_" + "no_narrator.png",
                "36161dd3bbb5d9e5c02b613489e84d8c" + "de2a6aada62a59bb1c261854c5afdf83"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_redesign_" + "dark.png",
                "acfa047b4c428fbc3c90020b9ed753b5" + "0501677f970f89e7dfadad4e5abb3bfa"
            ),
            Pin(
                "PlayerScreen" + "SnapshotTest",
                "player_tight_" + "viewport.png",
                "8b2a96c9f1bd064dbb6185baeec2cb04" + "afbbbbaaf625f4c965fe6945479b618b"
            )
        )
    }
}
