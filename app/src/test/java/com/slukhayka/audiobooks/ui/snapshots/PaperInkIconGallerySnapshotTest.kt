package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.ui.icons.PaperInkIcon
import com.slukhayka.audiobooks.ui.icons.PaperInkIconSizes
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The «Папір і голос» icon family, rendered in real Compose on both materials.
 *
 * This is the gallery a designer reviews: every glyph at its chrome size on
 * paper and on ink. It is a golden, not a catalogue — a redrawn icon or an
 * icon that stops reading at 24 dp fails here, and the pair of images shows
 * that one monochrome vector reads on both materials (the set bakes no tint).
 *
 * The order is [PaperInkIcons.All]; `PaperInkIconsTest` owns the invariants
 * (grid, stroke scale, RTL mirroring) so this test can stay about pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PaperInkIconGallerySnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val columns = 8
    private val cell = 42.dp

    @Composable
    private fun Gallery(darkTheme: Boolean) {
        PaperInkTheme(darkTheme = darkTheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PaperInkIcons.All.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { icon ->
                                Box(
                                    modifier = Modifier.size(cell),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PaperInkIcon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        size = PaperInkIconSizes.Header,
                                        tint = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun paperink_icon_gallery_paper() {
        composeTestRule.setContent { Gallery(darkTheme = false) }
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/paperink_icon_gallery_paper.png")
    }

    @Test
    fun paperink_icon_gallery_ink() {
        composeTestRule.setContent { Gallery(darkTheme = true) }
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/paperink_icon_gallery_ink.png")
    }

    /**
     * The geometry contract the gallery alone cannot name: ink inside the 24 dp
     * grid's safe zone, and an optical centre that is actually the centre.
     *
     * This is the check that would have caught two real regressions: `Library`
     * drew its slanted spine out to x=23.9 (audit §5.4.1 asks for 2..22), and
     * `BookmarkAdd`/`BookmarkRemove` rendered their bbox at 10.6 instead of 12.
     * Both read fine at a glance and only a bounds assertion sees them.
     */
    @Test
    fun icons_stay_within_the_safe_zone_and_are_optically_centred() {
        // The audit allows a filled glyph to reach 1.5 dp (§5.4.1); the family's
        // stroked ink is inflated by half the stroke before it is measured.
        val chart = Rect(1.45f, 1.45f, 22.55f, 22.55f)
        val safeZone = Rect(1.95f, 1.95f, 22.05f, 22.05f)
        // Deliberately asymmetric glyphs: a play triangle's ink sits right of the
        // 12 dp axis (Material's own play glyph centres at 13.5), and the
        // prototype's five-bar wave is filled and reaches 22.2.
        val asymmetric = setOf("PaperInk.Play")
        val filledPast22 = setOf("PaperInk.Wave")

        PaperInkIcons.All.forEach { icon ->
            val ink = inkBounds(icon)
            assertTrue(
                "${icon.name} ink $ink leaves the 24 dp grid",
                ink.left >= chart.left && ink.top >= chart.top &&
                    ink.right <= chart.right && ink.bottom <= chart.bottom,
            )
            if (icon.name !in filledPast22) {
                assertTrue(
                    "${icon.name} ink $ink leaves the 2..22 safe zone",
                    ink.left >= safeZone.left && ink.top >= safeZone.top &&
                        ink.right <= safeZone.right && ink.bottom <= safeZone.bottom,
                )
            } else {
                assertTrue(
                    "${icon.name} is filled and may reach 22.5 only: $ink",
                    ink.right <= 22.5f && ink.left >= safeZone.left &&
                        ink.top >= safeZone.top && ink.bottom <= safeZone.bottom,
                )
            }
            if (icon.name !in asymmetric) {
                assertEquals(
                    "${icon.name} is off-centre horizontally: $ink",
                    12f, (ink.left + ink.right) / 2f, 1.3f,
                )
                assertEquals(
                    "${icon.name} is off-centre vertically: $ink",
                    12f, (ink.top + ink.bottom) / 2f, 1.3f,
                )
            }
        }
    }

    /** Stroke ink is the path's bounds inflated by half the stroke width. */
    private fun inkBounds(icon: androidx.compose.ui.graphics.vector.ImageVector): Rect {
        var bounds: Rect? = null
        icon.root.toList().filterIsInstance<VectorPath>().forEach { path ->
            val pathBounds = path.pathData.toPath().getBounds()
            val half = if (path.stroke != null) path.strokeLineWidth / 2f else 0f
            val ink = Rect(
                pathBounds.left - half,
                pathBounds.top - half,
                pathBounds.right + half,
                pathBounds.bottom + half,
            )
            bounds = bounds?.let {
                Rect(
                    minOf(it.left, ink.left),
                    minOf(it.top, ink.top),
                    maxOf(it.right, ink.right),
                    maxOf(it.bottom, ink.bottom),
                )
            } ?: ink
        }
        return requireNotNull(bounds) { "${icon.name} has no paths" }
    }
}
