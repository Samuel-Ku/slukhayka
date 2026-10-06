package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #995 — the hero must not draw the Work's identity twice.
 *
 * ## Why this reads the source instead of the semantics tree
 *
 * The no-art cover fallback draws the title and the author, and the book-detail
 * hero draws the summary ON TOP of that same box. At `fontScale = 2f` the two
 * copies collide outright.
 *
 * The fallback's `Box` calls `clearAndSetSemantics { }`, so its text is NOT in
 * the semantics tree.
 * `BookDetailAccessibilityTest.identityHeaderReadsWorkTitleOnceAndMarksItAsHeading`
 * has therefore asserted «the title appears exactly once» for as long as the
 * defect has existed — and passed the whole time. **Semantics is not pixels.**
 *
 * A Robolectric fixture cannot see the collision either: it needs the real
 * device's text metrics at `2f`, which is why #995 requires device evidence.
 * What a fast test CAN pin is the decision itself — that the hero asks the cover
 * for its ground alone. Delete that argument and this fails; the pixels
 * themselves are checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookDetailHeroCoverFallbackTest {

    private val sourceRoot: File =
        listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        ).first { it.isDirectory }

    private fun heroBody(): String =
        File(sourceRoot, "ui/screens/bookdetail/BookDetailSections.kt").readText()
            .substringAfter("fun BookDetailIdentityHeader(")
            .substringBefore("/** The production Work/Edition summary")

    @Test
    fun `the hero asks the cover for its ground alone`() {
        val call = heroBody().substringAfter("BookCoverImage(")

        assertTrue(
            "the hero's cover must not repeat the identity the summary already states",
            call.contains("typographicFallback = false")
        )
    }

    @Test
    fun `the seam is real, and the fallback still draws identity by default`() {
        // The default matters as much as the hero's override: on a shelf tile the
        // typographic cover IS the identity, and turning it off everywhere would
        // trade this defect for a screenful of blank rectangles.
        val cover = File(sourceRoot, "ui/components/BookCoverImage.kt").readText()

        assertTrue(
            "the fallback must stay on by default",
            cover.contains("typographicFallback: Boolean = true")
        )
        assertTrue(
            "the ground must be drawn even when the identity is not",
            cover.contains("if (!typographicFallback) return@Box")
        )
    }
}
