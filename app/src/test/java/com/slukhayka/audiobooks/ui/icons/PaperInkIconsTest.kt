package com.slukhayka.audiobooks.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of the «Папір і голос» icon family.
 *
 * A hand-drawn set drifts quietly: one glyph gets redrawn on its own grid, a
 * path is left empty, an RTL-sensitive chevron loses `autoMirror`. This test
 * pins the invariants a screenshot cannot name, so the gallery golden can stay
 * about the look.
 */
class PaperInkIconsTest {

    @Test
    fun `every icon is a non-empty 24 dp vector`() {
        assertTrue(
            "the family shrank below a usable set: ${PaperInkIcons.All.size}",
            PaperInkIcons.All.size >= 60,
        )
        PaperInkIcons.All.forEach { icon ->
            assertEquals("width of ${icon.name}", 24.dp, icon.defaultWidth)
            assertEquals("height of ${icon.name}", 24.dp, icon.defaultHeight)
            assertEquals("viewport width of ${icon.name}", 24f, icon.viewportWidth, 0f)
            assertEquals("viewport height of ${icon.name}", 24f, icon.viewportHeight, 0f)

            val nodes = icon.root.toList()
            assertTrue("${icon.name} has no paths at all", nodes.isNotEmpty())
            nodes.filterIsInstance<VectorPath>().forEach { path ->
                assertTrue("${icon.name} carries an empty path", path.pathData.isNotEmpty())
                assertTrue(
                    "${icon.name} has a path with neither fill nor stroke",
                    path.fill != null || path.stroke != null,
                )
            }
        }
    }

    @Test
    fun `strokes are round-capped and share one weight scale`() {
        // Exactly five weights, each with one job: 1.8 the base; 1.9 the
        // naturally-thin chevrons/search/close; 2.0 plus/check; 1.7 the
        // circular seek arc; 1.5 the numerals inside Rew15/Fwd30, which must
        // stay lighter than the arc they sit in.
        val allowed = setOf(1.5f, 1.7f, 1.8f, 1.9f, 2.0f)
        var strokedPaths = 0
        var strokedIcons = 0
        PaperInkIcons.All.forEach { icon ->
            var iconHasStroke = false
            icon.root.toList().filterIsInstance<VectorPath>().forEach { path ->
                if (path.stroke == null) return@forEach
                strokedPaths++
                iconHasStroke = true
                assertEquals("${icon.name} cap", StrokeCap.Round, path.strokeLineCap)
                assertEquals("${icon.name} join", StrokeJoin.Round, path.strokeLineJoin)
                assertTrue(
                    "${icon.name} stroke width ${path.strokeLineWidth} is outside the family scale",
                    path.strokeLineWidth in allowed,
                )
            }
            if (iconHasStroke) strokedIcons++
        }
        assertTrue("the family lost its stroked icons (found $strokedIcons)", strokedIcons >= 52)
        assertTrue(
            "every path is filled and nothing is stroked ($strokedPaths paths)",
            strokedPaths >= 120,
        )
    }

    @Test
    fun `names are unique, namespaced and stable`() {
        val names = PaperInkIcons.All.map { it.name }
        assertEquals("duplicate icon names: $names", names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue("$name is not in the PaperInk namespace", name.startsWith("PaperInk."))
        }
        // Names are the contract the design audit's icon-map.tsv refers to, so a
        // rename has to be a deliberate change here, not a refactor side effect.
        assertEquals("PaperInk.Play", PaperInkIcons.Play.name)
        assertEquals("PaperInk.Headphones", PaperInkIcons.Headphones.name)
        assertEquals("PaperInk.ChevronLeft", PaperInkIcons.ChevronLeft.name)
    }

    @Test
    fun `the design audit's canonical names are all present`() {
        // `docs/audits/assets/2026-10-28-device/icon-map.tsv` names each target
        // glyph as PaperInkIcons.X. Every core and state name there must exist,
        // so a follow-up replacement PR never has to invent a glyph.
        val canonical = setOf(
            "Alert", "ArrowDown", "ArrowRight", "ArrowUp", "Book", "BookmarkAdd",
            "BookmarkRemove", "Check", "ChevronDown", "ChevronLeft", "ChevronRight",
            "ChevronUp", "Close", "Compass", "Download", "DragHandle", "Edit",
            "Filter", "FilterOff", "Flame", "Friends", "Globe", "Grid", "Headphones",
            "Info", "Library", "Link", "List", "ListCover", "Medal", "Moon", "More",
            "Person", "Plus", "Refresh", "Rew15", "Fwd30", "Search", "Settings",
            "Shield", "SkipBack", "SkipFwd", "Sort", "Speed", "Storage", "Timer",
            "Translate", "Trash", "Upload", "Wave",
            "Bookmark", "Downloaded", "HeartFilled", "Offline", "Pause", "Play", "Star",
        )
        val names = PaperInkIcons.All.map { it.name.removePrefix("PaperInk.") }.toSet()
        assertEquals("the family is missing canonical glyphs", emptySet<String>(), canonical - names)
    }

    @Test
    fun `direction-sensitive glyphs mirror in RTL`() {
        listOf(
            PaperInkIcons.ChevronLeft,
            PaperInkIcons.ChevronRight,
            PaperInkIcons.ArrowRight,
        ).forEach { icon ->
            assertTrue("${icon.name} must auto-mirror", icon.autoMirror)
        }
        listOf(
            PaperInkIcons.Play,
            PaperInkIcons.Pause,
            PaperInkIcons.Headphones,
            PaperInkIcons.Replay,
            PaperInkIcons.Rew15,
            PaperInkIcons.Fwd30,
            PaperInkIcons.Library,
        ).forEach { icon ->
            assertFalse("${icon.name} is not direction-sensitive", icon.autoMirror)
        }
    }

    @Test
    fun `light and dark suites use the same glyph, only the tint changes`() {
        // The set is monochrome on purpose: reusing one vector in both schemes is
        // what keeps paper and ink optically identical. A tint baked into the
        // vector would fork the family.
        PaperInkIcons.All.forEach { icon ->
            assertEquals("${icon.name} bakes a tint", Color.Unspecified, icon.tintColor)
        }
    }
}
