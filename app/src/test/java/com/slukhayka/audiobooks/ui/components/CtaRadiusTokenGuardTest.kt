package com.slukhayka.audiobooks.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1775 (unslop audit U1) — one role, one form: the empty-state CTA stays on the
 * shared primitives, and no surface hand-rolls a pill on an action button.
 *
 * The audit found the same invitation drawn four ways: a full pill hand-built
 * from `Row` + `CircleShape` on «Слухати», `RadiusCard` (12 dp) in «Медіатека»,
 * `RadiusCardLg` (14 dp) on «Огляд», and 24 dp on the book page and in the
 * player. The fix is structural, not a restyle: [EmptyStatePrimaryAction] and
 * [EmptyStateQuietAction] own the radius, the height and the paper colours, and
 * the surfaces only pass a label, a click and a tag. A guard is what keeps that
 * true after the next screen is written.
 *
 * What this proves, and only this: in the six paper surfaces below, every
 * `action`/`quietAction` slot renders exactly one shared primitive, names no
 * shape and no pill literal; every `Button`/`OutlinedButton`/`TextButton` in
 * those files names no pill literal except the feed toolbar's chip (which the
 * audit explicitly allows a pill); and the primitives themselves name the two
 * radii from [com.slukhayka.audiobooks.ui.theme.PaperInkDimens] rather than a
 * dp literal.
 *
 * **Named limits** (read, not assumed):
 * - This is a SOURCE scan, not a layout assertion. It cannot prove radii are
 *   equal to the pixel — no Compose semantics node carries a corner radius; that
 *   claim is held by the re-recorded goldens and by the review reading the diff.
 * - It sees the two slots of [EmptyStatePanel] and the button-shaped calls in
 *   the six files. A hand-rolled CTA built OUTSIDE a slot (a bare clickable
 *   `Row` on some future screen) is not distinguishable from an ordinary row by
 *   this scanner; the slot rule is where the migrated CTAs live.
 * - It reads exactly the six files #1766 migrated plus the component file. A
 *   seventh paper surface, the book page or the player is out of its scope on
 *   purpose: those CTAs were already on `PaperInkDimens.RadiusLarge` (and the
 *   player's play control is a disc, which the rule preserves).
 * - The chip exemption is keyed by the `feed_sort` tag the toolbar already
 *   publishes. Moving a pill onto another button fails; renaming that tag would
 *   make the chip fail too, which is the safe direction for a guard.
 */
class CtaRadiusTokenGuardTest {

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    /** The six surfaces #1766 migrated; the CTA rule is enforced in all of them. */
    private val surfaces = listOf(
        "ui/screens/ListenScreen.kt",
        "ui/screens/LibraryScreen.kt",
        "ui/screens/HomeScreen.kt",
        "ui/screens/FriendsScreen.kt",
        "ui/screens/AchievementsScreen.kt",
        "ui/screens/LibraryRatingScreen.kt"
    )

    private val designSystem = "ui/components/DesignSystem.kt"

    @Test
    fun `every empty-state action slot renders the shared primitive and names no shape`() {
        val offenders = surfaces.flatMap { path ->
            CtaRadiusGuard.slotViolations(path, read(path))
        }

        assertTrue(
            "an empty-state action slot may not hand-roll its own button: the CTA radius " +
                "and height live in EmptyStatePrimaryAction/EmptyStateQuietAction, so a " +
                "surface that names a shape is the fourth form again — " +
                offenders.joinToString { "${it.file} ${it.where}: ${it.reason}" },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the three surfaces with an action all reach it through the primitives`() {
        val bodies = surfaces.joinToString("\n") { read(it) }
        val primaries = CtaRadiusGuard.PRIMARY_CALL.findAll(
            CtaRadiusGuard.maskCommentsAndStrings(bodies)
        ).count()
        val quiets = CtaRadiusGuard.QUIET_CALL.findAll(
            CtaRadiusGuard.maskCommentsAndStrings(bodies)
        ).count()

        assertEquals(
            "«Слухати», «Медіатека» and «Огляд» each render ONE primary action through " +
                "EmptyStatePrimaryAction; a surface that drops its action (or reverts to a " +
                "hand-rolled button) must fail here, not only in a golden",
            3,
            primaries
        )
        assertEquals(
            "the same three surfaces demote their second path to EmptyStateQuietAction",
            3,
            quiets
        )
    }

    @Test
    fun `no surface names a pill on a button`() {
        val offenders = surfaces.flatMap { path ->
            CtaRadiusGuard.buttonViolations(path, read(path))
        }

        assertTrue(
            "a pill belongs to a chip, the navigation bar and play discs — not to an " +
                "action button (audit rule 1). Offenders: " +
                offenders.joinToString { "${it.file} ${it.where}: ${it.reason}" },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the empty-state CTA shape and height are named only in the primitives`() {
        val component = read(designSystem)
        val primary = CtaRadiusGuard.functionBody(component, "EmptyStatePrimaryAction")
            ?: error("EmptyStatePrimaryAction moved — this guard cannot read it")
        val quiet = CtaRadiusGuard.functionBody(component, "EmptyStateQuietAction")
            ?: error("EmptyStateQuietAction moved — this guard cannot read it")

        assertTrue(
            "the primary CTA must take its 24 dp radius from the token: $primary",
            Regex("""shape\s*=\s*RoundedCornerShape\(PaperInkDimens\.RadiusLarge\)""")
                .containsMatchIn(primary)
        )
        assertTrue(
            "the primary CTA must take its 52 dp height from the token: $primary",
            primary.contains("PaperInkDimens.PrimaryButtonHeight")
        )
        assertTrue(
            "the primary CTA must use the paper roles primary/onPrimary: $primary",
            primary.contains("MaterialTheme.colorScheme.primary") &&
                primary.contains("MaterialTheme.colorScheme.onPrimary")
        )
        assertTrue(
            "the primary CTA must not name a literal pill or a literal radius: $primary",
            !CtaRadiusGuard.PILL_LITERAL.containsMatchIn(primary) &&
                !Regex("""RoundedCornerShape\(\s*\d""").containsMatchIn(primary)
        )

        assertTrue(
            "the quiet action must take its 16 dp radius from the token: $quiet",
            Regex("""shape\s*=\s*RoundedCornerShape\(PaperInkDimens\.RadiusMedium\)""")
                .containsMatchIn(quiet)
        )
        assertTrue(
            "the quiet action must keep the 48 dp target floor: $quiet",
            quiet.contains("PaperInkDimens.TouchTarget")
        )
        assertTrue(
            "the quiet action must not name a literal pill or a literal radius: $quiet",
            !CtaRadiusGuard.PILL_LITERAL.containsMatchIn(quiet) &&
                !Regex("""RoundedCornerShape\(\s*\d""").containsMatchIn(quiet)
        )

        surfaces.forEach { path ->
            val source = CtaRadiusGuard.maskCommentsAndStrings(read(path))
            assertTrue(
                "$path defines its own copy of the empty-state CTA primitives — one form " +
                    "means one definition, in $designSystem",
                !source.contains("fun EmptyStatePrimaryAction") &&
                    !source.contains("fun EmptyStateQuietAction")
            )
            assertTrue(
                "$path names PaperInkDimens.PrimaryButtonHeight — the CTA height belongs " +
                    "to the primitive, not to the surface",
                !source.contains("PaperInkDimens.PrimaryButtonHeight")
            )
        }
    }

    @Test
    fun `the scanner finds a pill in a slot but not in prose and not on the chip`() {
        val fixture = """
            package fixture

            // CircleShape and percent = 50 in a comment are prose, not a shape.
            val prose = "CircleShape inside a string is prose"

            fun handRolled() {
                EmptyStatePanel(
                    title = "T",
                    body = "B",
                    action = {
                        Row(modifier = Modifier.clip(CircleShape)) { }
                    },
                    quietAction = {
                        TextButton(onClick = {}) { Text("quiet") }
                    }
                )
            }

            fun chip() {
                OutlinedButton(
                    onClick = {},
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.testTag("feed_sort")
                ) { Text("sort") }
            }

            fun cta() {
                Button(
                    onClick = {},
                    shape = RoundedCornerShape(percent = 50)
                ) { Text("go") }
            }
        """.trimIndent()

        val slots = CtaRadiusGuard.slots("fixture.kt", fixture)
        assertEquals("two slots in the fixture: $slots", 2, slots.size)

        val slotReasons = CtaRadiusGuard.slotViolations("fixture.kt", fixture).map { it.reason }
        assertTrue(
            "the hand-rolled pill must be named: $slotReasons",
            slotReasons.any { it.contains("pill literal") }
        )
        assertTrue(
            "the slot must not re-decide the shape: $slotReasons",
            slotReasons.any { it.contains("re-decides the shape") }
        )
        assertTrue(
            "a slot that renders a TextButton instead of the primitive must be named: $slotReasons",
            slotReasons.any { it.contains("hand-rolls") }
        )
        assertTrue(
            "a slot without the primitive must fail the one-primitive rule: $slotReasons",
            slotReasons.any { it.contains("exactly one EmptyStatePrimaryAction") }
        )

        val buttonViolations = CtaRadiusGuard.buttonViolations("fixture.kt", fixture)
        assertEquals(
            "exactly the non-chip Button is a pill offender — the chip and the prose are not: " +
                buttonViolations,
            1,
            buttonViolations.size
        )
        assertTrue(
            "the offender is the plain Button, not the feed_sort chip: ${buttonViolations.single()}",
            buttonViolations.single().where.startsWith("Button")
        )
    }

    private fun read(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        check(file.isFile) { "source file not found: $file" }
        return file.readText()
    }
}
