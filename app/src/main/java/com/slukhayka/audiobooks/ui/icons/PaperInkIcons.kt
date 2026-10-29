package com.slukhayka.audiobooks.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The «Папір і голос» icon family: ONE hand-drawn set on a 24 dp grid, round
 * caps and joins, a single 1.8 dp-equivalent stroke that thickens only where a
 * glyph is naturally thin: 1.9 for chevrons/search/close, 2.0 for plus/check,
 * 1.7 for the circular seek arc, 1.5 for the numerals inside [Rew15]/[Fwd30].
 *
 * The source of truth is the direction's own SVG family in
 * `docs/prototypes/2026-ui-refresh/index.html` (lines 12-52); this object is a
 * direct translation of it, not Material glyphs with a tint. That is the point:
 * «Слухайка» must not read as a stock Material app on a warm background.
 *
 * Filled variants exist only where fill means a STATE: play/pause, the skip
 * triangles, the sound wave, a saved bookmark/heart, a chosen star, the compass
 * needle. `Downloaded`/`Offline` are state marks too, but they are stroked (a
 * filled tray would read as a solid blob at 16 dp). Navigation tabs keep ONE
 * outline glyph and change their tonal pill, exactly as the prototype's
 * `.nav-item.active` does.
 *
 * Every icon is a monochrome `Color.Black` vector on purpose: [Icon] tints it
 * with the caller's role (`onSurface`, `primary`, `tertiary`…), so one glyph
 * reads on paper and on ink. Sizes live in [PaperInkIconSizes]; the 48 dp touch
 * target is the caller's job and never shrinks to the glyph.
 */
object PaperInkIcons {

    val Play: ImageVector = buildIcon("PaperInk.Play") {
        fill("M8.5 5.2v13.6L19 12z")
    }

    val Pause: ImageVector = buildIcon("PaperInk.Pause") {
        fill("M8.2 5h1a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1 -1.2 1.2h-1a1.2 1.2 0 0 1 -1.2 -1.2v-11.6a1.2 1.2 0 0 1 1.2 -1.2Z")
        fill("M14.8 5h1a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1 -1.2 1.2h-1a1.2 1.2 0 0 1 -1.2 -1.2v-11.6a1.2 1.2 0 0 1 1.2 -1.2Z")
    }

    val Headphones: ImageVector = buildIcon("PaperInk.Headphones") {
        stroke("M4 15v-3a8 8 0 0 1 16 0v3")
        stroke("M4 14.5h2.2a1.3 1.3 0 0 1 1.3 1.3v3.4a1.3 1.3 0 0 1-1.3 1.3H5.4A1.4 1.4 0 0 1 4 19.1z")
        stroke("M20 14.5h-2.2a1.3 1.3 0 0 0-1.3 1.3v3.4a1.3 1.3 0 0 0 1.3 1.3h.8a1.4 1.4 0 0 0 1.4-1.4z")
    }

    val Compass: ImageVector = buildIcon("PaperInk.Compass") {
        stroke("M3.8 12a8.2 8.2 0 1 0 16.4 0a8.2 8.2 0 1 0 -16.4 0Z")
        fill("M15.2 8.8l-1.9 5-4.5 1.4 1.9-5z")
    }

    val Friends: ImageVector = buildIcon("PaperInk.Friends") {
        stroke("M5.9 8.4a3.3 3.3 0 1 0 6.6 0a3.3 3.3 0 1 0 -6.6 0Z")
        stroke("M3.6 19.4a5.8 5.8 0 0 1 11.2 0")
        stroke("M16.2 5.6a3.1 3.1 0 0 1 0 5.9")
        stroke("M17.6 14.2a5.4 5.4 0 0 1 3.1 4.9")
    }

    val Search: ImageVector = buildIcon("PaperInk.Search") {
        stroke("M4.2 10.5a6.3 6.3 0 1 0 12.6 0a6.3 6.3 0 1 0 -12.6 0Z", strokeWidth = 1.9f)
        stroke("M15.4 15.4L20 20", strokeWidth = 1.9f)
    }

    val Filter: ImageVector = buildIcon("PaperInk.Filter") {
        stroke("M4 7h16M7 12h10M10 17h4")
    }

    val ChevronDown: ImageVector = buildIcon("PaperInk.ChevronDown") {
        stroke("M6.5 9.5L12 15l5.5-5.5", strokeWidth = 1.9f)
    }

    val ChevronLeft: ImageVector = buildIcon("PaperInk.ChevronLeft", autoMirror = true) {
        stroke("M14.5 6.5L9 12l5.5 5.5", strokeWidth = 1.9f)
    }

    val ChevronRight: ImageVector = buildIcon("PaperInk.ChevronRight", autoMirror = true) {
        stroke("M9.5 6.5L15 12l-5.5 5.5", strokeWidth = 1.9f)
    }

    val More: ImageVector = buildIcon("PaperInk.More") {
        fill("M10.3 5.6a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z")
        fill("M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z")
        fill("M10.3 18.4a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z")
    }

    val Plus: ImageVector = buildIcon("PaperInk.Plus") {
        stroke("M12 5.5v13M5.5 12h13", strokeWidth = 2.0f)
    }

    val ArrowRight: ImageVector = buildIcon("PaperInk.ArrowRight", autoMirror = true) {
        stroke("M4.5 12h14", strokeWidth = 1.9f)
        stroke("M13 6.5L19 12l-6 5.5", strokeWidth = 1.9f)
    }

    val SkipBack: ImageVector = buildIcon("PaperInk.SkipBack") {
        stroke("M6.4 5.6v12.8", strokeWidth = 1.9f)
        fill("M18.6 6.1v11.8L8.9 12z")
    }

    val SkipFwd: ImageVector = buildIcon("PaperInk.SkipFwd") {
        stroke("M17.6 5.6v12.8", strokeWidth = 1.9f)
        fill("M5.4 6.1v11.8L15.1 12z")
    }

    val Moon: ImageVector = buildIcon("PaperInk.Moon") {
        stroke("M20 14.3A8 8 0 1 1 9.7 4a6.4 6.4 0 0 0 10.3 10.3z")
    }

    val Bookmark: ImageVector = buildIcon("PaperInk.Bookmark") {
        stroke("M7 4.5h10V20l-5-3.8L7 20z")
    }

    val List: ImageVector = buildIcon("PaperInk.List") {
        stroke("M9 6.5h11M9 12h11M9 17.5h11")
        fill("M3.35 6.5a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z")
        fill("M3.35 12a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z")
        fill("M3.35 17.5a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z")
    }

    val Speed: ImageVector = buildIcon("PaperInk.Speed") {
        stroke("M4.4 17.5a8 8 0 1 1 15.2 0")
        stroke("M12 13.6l3.4-3.4")
        fill("M10.75 14a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z")
    }

    val Download: ImageVector = buildIcon("PaperInk.Download") {
        stroke("M12 4.5v10")
        stroke("M8 11l4 4 4-4")
        stroke("M5 16.5v2a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-2")
    }

    val Star: ImageVector = buildIcon("PaperInk.Star") {
        fill("M12 3.9l2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-3.9 5.6-.8z")
    }

    val StarOutline: ImageVector = buildIcon("PaperInk.StarOutline") {
        stroke("M12 3.9l2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-3.9 5.6-.8z", strokeWidth = 1.7f)
    }

    val StarHalf: ImageVector = buildIcon("PaperInk.StarHalf") {
        stroke("M12 3.9l2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-3.9 5.6-.8z", strokeWidth = 1.7f)
        fill("M12 3.9v13.6l-5.1 2.7 1-5.6-4-3.9 5.6-.8z")
    }

    val Check: ImageVector = buildIcon("PaperInk.Check") {
        stroke("M4.8 12.4l4.6 4.6L19.4 7", strokeWidth = 2.0f)
    }

    val Lock: ImageVector = buildIcon("PaperInk.Lock") {
        stroke("M7.7 10.5h8.6a2.2 2.2 0 0 1 2.2 2.2v4.6a2.2 2.2 0 0 1 -2.2 2.2h-8.6a2.2 2.2 0 0 1 -2.2 -2.2v-4.6a2.2 2.2 0 0 1 2.2 -2.2Z")
        stroke("M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")
    }

    val Timer: ImageVector = buildIcon("PaperInk.Timer") {
        stroke("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z")
        stroke("M12 7.6V12l2.9 1.9")
    }

    val Wave: ImageVector = buildIcon("PaperInk.Wave") {
        fill("M4.2 10h0a1.2 1.2 0 0 1 1.2 1.2v1.6a1.2 1.2 0 0 1 -1.2 1.2h-0a1.2 1.2 0 0 1 -1.2 -1.2v-1.6a1.2 1.2 0 0 1 1.2 -1.2Z")
        fill("M8.4 7h0a1.2 1.2 0 0 1 1.2 1.2v7.6a1.2 1.2 0 0 1 -1.2 1.2h-0a1.2 1.2 0 0 1 -1.2 -1.2v-7.6a1.2 1.2 0 0 1 1.2 -1.2Z")
        fill("M12.6 4.5h0a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1 -1.2 1.2h-0a1.2 1.2 0 0 1 -1.2 -1.2v-12.6a1.2 1.2 0 0 1 1.2 -1.2Z")
        fill("M16.8 8h0a1.2 1.2 0 0 1 1.2 1.2v5.6a1.2 1.2 0 0 1 -1.2 1.2h-0a1.2 1.2 0 0 1 -1.2 -1.2v-5.6a1.2 1.2 0 0 1 1.2 -1.2Z")
        fill("M21 10.5h0a1.2 1.2 0 0 1 1.2 1.2v0.6a1.2 1.2 0 0 1 -1.2 1.2h-0a1.2 1.2 0 0 1 -1.2 -1.2v-0.6a1.2 1.2 0 0 1 1.2 -1.2Z")
    }

    val Heart: ImageVector = buildIcon("PaperInk.Heart") {
        stroke("M12 20.2c-5.3-3.2-8-6.4-8-9.6a4.4 4.4 0 0 1 8-2.5 4.4 4.4 0 0 1 8 2.5c0 3.2-2.7 6.4-8 9.6z")
    }

    val Comment: ImageVector = buildIcon("PaperInk.Comment") {
        stroke("M4.6 5.2h14.8v10.4H12l-4.4 3.2v-3.2H4.6z")
    }

    val HeartFilled: ImageVector = buildIcon("PaperInk.HeartFilled") {
        fill("M12 20.2c-5.3-3.2-8-6.4-8-9.6a4.4 4.4 0 0 1 8-2.5 4.4 4.4 0 0 1 8 2.5c0 3.2-2.7 6.4-8 9.6z")
    }

    val BookmarkFilled: ImageVector = buildIcon("PaperInk.BookmarkFilled") {
        fill("M7 4.5h10V20l-5-3.8L7 20z")
    }

    val Downloaded: ImageVector = buildIcon("PaperInk.Downloaded") {
        stroke("M5 15.4v3a1.4 1.4 0 0 0 1.4 1.4h11.2a1.4 1.4 0 0 0 1.4-1.4v-3")
        stroke("M7.6 9.4l3.3 3.3 6.5-6.5")
    }

    val Offline: ImageVector = buildIcon("PaperInk.Offline") {
        stroke("M5 15.4v3a1.4 1.4 0 0 0 1.4 1.4h11.2a1.4 1.4 0 0 0 1.4-1.4v-3")
        stroke("M12 4.8v7.6")
        stroke("M9 9.6l3 3 3-3")
        stroke("M5.2 18.8L18.8 5.2")
    }

    val BookmarkAdd: ImageVector = buildIcon("PaperInk.BookmarkAdd") {
        stroke("M7 4.5h10V20l-5-3.8L7 20z")
        stroke("M12 6.8v4")
        stroke("M10 8.8h4")
    }

    val BookmarkRemove: ImageVector = buildIcon("PaperInk.BookmarkRemove") {
        stroke("M7 4.5h10V20l-5-3.8L7 20z")
        stroke("M10 8.8h4")
    }

    val ChevronUp: ImageVector = buildIcon("PaperInk.ChevronUp") {
        stroke("M6.5 14.5L12 9l5.5 5.5", strokeWidth = 1.9f)
    }

    val ArrowUp: ImageVector = buildIcon("PaperInk.ArrowUp") {
        stroke("M12 19.4V4.8", strokeWidth = 1.9f)
        stroke("M6.9 9.9L12 4.8l5.1 5.1", strokeWidth = 1.9f)
    }

    val ArrowDown: ImageVector = buildIcon("PaperInk.ArrowDown") {
        stroke("M12 4.6v14.6", strokeWidth = 1.9f)
        stroke("M6.9 14.1L12 19.2l5.1-5.1", strokeWidth = 1.9f)
    }

    val Refresh: ImageVector = buildIcon("PaperInk.Refresh") {
        stroke("M19.4 10.2A7.6 7.6 0 1 0 17 17.6")
        stroke("M19.8 5.6v4.8h-4.8")
    }

    val Replay: ImageVector = buildIcon("PaperInk.Replay") {
        stroke("M4.6 10.2A7.6 7.6 0 1 1 7 17.6", strokeWidth = 1.7f)
        stroke("M4.2 5.6v4.8h4.8", strokeWidth = 1.7f)
    }

    val Person: ImageVector = buildIcon("PaperInk.Person") {
        stroke("M12 4.8a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0-6.8z")
        stroke("M4.9 19.7c0-3.9 3.2-6.3 7.1-6.3s7.1 2.4 7.1 6.3")
    }

    val Upload: ImageVector = buildIcon("PaperInk.Upload") {
        stroke("M12 16V4.8")
        stroke("M7.9 8.7L12 4.6l4.1 4.1")
        stroke("M5 15.4v3a1.4 1.4 0 0 0 1.4 1.4h11.2a1.4 1.4 0 0 0 1.4-1.4v-3")
    }

    val Trash: ImageVector = buildIcon("PaperInk.Trash") {
        stroke("M4.8 7.2h14.4")
        stroke("M9.6 7.2V5.7a1.2 1.2 0 0 1 1.2-1.2h2.4a1.2 1.2 0 0 1 1.2 1.2v1.5")
        stroke("M6.9 7.2l0.8 11.1a2 2 0 0 0 2 1.9h4.6a2 2 0 0 0 2-1.9l0.8-11.1")
        stroke("M10.3 11v5.2")
        stroke("M13.7 11v5.2")
    }

    val Edit: ImageVector = buildIcon("PaperInk.Edit") {
        stroke("M4.6 19.4l0.9-4.1L15.7 5.1a2.2 2.2 0 0 1 3.2 3.2L8.7 18.5z")
        stroke("M13.9 7.2l2.9 2.9")
    }

    val Info: ImageVector = buildIcon("PaperInk.Info") {
        stroke("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z")
        stroke("M12 11v5.2")
        fill("M11.05 8.1a0.95 0.95 0 1 0 1.9 0a0.95 0.95 0 1 0 -1.9 0Z")
    }

    val Alert: ImageVector = buildIcon("PaperInk.Alert") {
        stroke("M12 4.2l8.1 13.9a1.4 1.4 0 0 1-1.2 2.1H5.1a1.4 1.4 0 0 1-1.2-2.1z")
        stroke("M12 9.7v4.3")
        fill("M11.05 16.7a0.95 0.95 0 1 0 1.9 0a0.95 0.95 0 1 0 -1.9 0Z")
    }

    val Medal: ImageVector = buildIcon("PaperInk.Medal") {
        stroke("M7.1 8.6a4.9 4.9 0 1 0 9.8 0a4.9 4.9 0 1 0 -9.8 0Z")
        stroke("M8.8 12.2L7.3 20.3 12 17.9 16.7 20.3 15.2 12.2")
    }

    val Globe: ImageVector = buildIcon("PaperInk.Globe") {
        stroke("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z")
        stroke("M4.4 9.4h15.2")
        stroke("M4.4 14.6h15.2")
        stroke("M12 4c-2.6 2.2-4 4.9-4 8s1.4 5.8 4 8c2.6-2.2 4-4.9 4-8s-1.4-5.8-4-8z")
    }

    val Translate: ImageVector = buildIcon("PaperInk.Translate") {
        stroke("M4 19.2l4.4-12.4 4.4 12.4")
        stroke("M5.7 14.6h5.4")
        stroke("M14.6 19.2l2.7-7.4 2.7 7.4")
        stroke("M15.6 16.6h3.4")
    }

    val Shield: ImageVector = buildIcon("PaperInk.Shield") {
        stroke("M12 3.7l7 2.5v5.3c0 4.2-2.8 7.1-7 8.9-4.2-1.8-7-4.7-7-8.9V6.2z")
    }

    val Storage: ImageVector = buildIcon("PaperInk.Storage") {
        stroke("M4.2 8.2a2 2 0 0 1 2-2h11.6a2 2 0 0 1 2 2v7.6a2 2 0 0 1-2 2H6.2a2 2 0 0 1-2-2z")
        stroke("M4.2 12.6h15.6")
        stroke("M7 15.4h2.6")
    }

    val Sort: ImageVector = buildIcon("PaperInk.Sort") {
        stroke("M4.6 6.4h7.2")
        stroke("M4.6 12h5.2")
        stroke("M4.6 17.6h3.2")
        stroke("M17.4 18.6V6.2")
        stroke("M14.4 9.2l3-3 3 3")
    }

    val FilterOff: ImageVector = buildIcon("PaperInk.FilterOff") {
        stroke("M4 7h16")
        stroke("M7 12h10")
        stroke("M10 17h4")
        stroke("M4.6 4.4l14.8 15.2")
    }

    val Close: ImageVector = buildIcon("PaperInk.Close") {
        stroke("M7.5 7.5L16.5 16.5", strokeWidth = 1.9f)
        stroke("M16.5 7.5L7.5 16.5", strokeWidth = 1.9f)
    }

    val DragHandle: ImageVector = buildIcon("PaperInk.DragHandle") {
        fill("M7.95 7.6a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0Z")
        fill("M13.35 7.6a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0Z")
        fill("M7.95 12a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0Z")
        fill("M13.35 12a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0Z")
        fill("M7.95 16.4a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0Z")
        fill("M13.35 16.4a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0Z")
    }

    val Grid: ImageVector = buildIcon("PaperInk.Grid") {
        stroke("M4.8 5.6h5.2v5.2H4.8z")
        stroke("M14 5.6h5.2v5.2H14z")
        stroke("M4.8 13.2h5.2v5.2H4.8z")
        stroke("M14 13.2h5.2v5.2H14z")
    }

    val ListCover: ImageVector = buildIcon("PaperInk.ListCover") {
        stroke("M4.6 6.2h4.2v4.2H4.6z")
        stroke("M11.4 7.2h8")
        stroke("M11.4 9.8h5.8")
        stroke("M4.6 13.6h4.2v4.2H4.6z")
        stroke("M11.4 14.6h8")
        stroke("M11.4 17.2h5.8")
    }

    val Settings: ImageVector = buildIcon("PaperInk.Settings") {
        stroke("M18.81 13.29a6.99 6.99 0 0 0 0 -2.58l1.84 -1.38 -1.84 -3.13 -2.21 0.83a7.08 7.08 0 0 0 -2.21 -1.29L14.02 3.26h-4.05l-0.37 2.48a7.08 7.08 0 0 0 -2.21 1.29l-2.21 -0.83 -1.84 3.13 1.84 1.38a6.99 6.99 0 0 0 0 2.58l-1.84 1.38 1.84 3.13 2.21 -0.83a7.08 7.08 0 0 0 2.21 1.29l0.37 2.48h4.05l0.37 -2.48a7.08 7.08 0 0 0 2.21 -1.29l2.21 0.83 1.84 -3.13z")
        stroke("M9.15 12a2.85 2.85 0 1 0 5.7 0a2.85 2.85 0 1 0 -5.7 0Z")
    }

    val Library: ImageVector = buildIcon("PaperInk.Library") {
        stroke("M3.9 5h3.8v14.4H3.9z")
        stroke("M8.9 5h3.8v14.4H8.9z")
        stroke("M13.6 6.1l2.9-.7 3.6 13.2-2.9.7z")
    }

    val Book: ImageVector = buildIcon("PaperInk.Book") {
        stroke("M12 6.9C10.7 5.8 9.1 5.2 7.3 5.2H4.8v13h2.5c1.8 0 3.4.6 4.7 1.7")
        stroke("M12 6.9c1.3-1.1 2.9-1.7 4.7-1.7h2.5v13h-2.5c-1.8 0-3.4.6-4.7 1.7")
        stroke("M12 6.9v13")
    }

    val Link: ImageVector = buildIcon("PaperInk.Link") {
        stroke("M9.7 14.3l4.6-4.6")
        stroke("M8.3 12.4l-1.7 1.7a3.6 3.6 0 0 0 5.1 5.1l1.7-1.7")
        stroke("M15.7 11.6l1.7-1.7a3.6 3.6 0 0 0-5.1-5.1l-1.7 1.7")
    }

    val Flame: ImageVector = buildIcon("PaperInk.Flame") {
        fill("M12.5 2.9c.4 2.9-.4 4.7-1.8 6.2-1.4 1.5-2.8 2.9-2.8 5.1a4.8 4.8 0 0 0 9.6.3c0-1.7-.8-2.8-1.7-3.9-.9-1.1-1.7-2.1-1.9-3.6-.4.8-.9 1.4-1.5 1.9-.1-1.6.5-3.9.1-6zM12.1 20.3a4.3 4.3 0 0 1-4.2-4.2c0-1.7 1.1-2.7 2.2-3.8.7-.7 1.3-1.5 1.6-2.5.6 1.1 1.4 1.9 2.1 2.8.8 1 1.4 2.1 1.4 3.5a4.3 4.3 0 0 1-3.1 4.2z", evenOdd = true)
    }

    val Rew15: ImageVector = buildIcon("PaperInk.Rew15") {
        stroke("M4.6 10.2A7.6 7.6 0 1 1 7 17.6", strokeWidth = 1.7f)
        stroke("M4.2 5.6v4.8h4.8", strokeWidth = 1.7f)
        stroke("M10.7 11.0v5.4", strokeWidth = 1.5f)
        stroke("M9.9 11.7l0.8-0.7", strokeWidth = 1.5f)
        stroke("M14.6 11.0h-2.6v2.4c0.5-0.4 1.2-0.5 1.8-0.2c0.9 0.5 1.1 1.5 0.6 2.3c-0.4 0.7-1.1 1.1-1.9 1.1", strokeWidth = 1.5f)
    }

    val Fwd30: ImageVector = buildIcon("PaperInk.Fwd30") {
        stroke("M19.4 10.2A7.6 7.6 0 1 0 17 17.6", strokeWidth = 1.7f)
        stroke("M19.8 5.6v4.8h-4.8", strokeWidth = 1.7f)
        stroke("M9.4 11.0h2.3l-1.3 2.3c0.9-0.2 1.7 0.4 1.7 1.2c0 1-0.9 1.9-2.2 1.8", strokeWidth = 1.5f)
        stroke("M14.0 11.0c1.2 0 1.9 1.2 1.9 2.7s-0.7 2.7-1.9 2.7-1.9-1.2-1.9-2.7 0.7-2.7 1.9-2.7z", strokeWidth = 1.5f)
    }

    /** Every icon, for the gallery snapshot and the contract test. */
    val All: List<ImageVector> = listOf(
        Play,
        Pause,
        Headphones,
        Compass,
        Friends,
        Search,
        Filter,
        ChevronDown,
        ChevronLeft,
        ChevronRight,
        More,
        Plus,
        ArrowRight,
        SkipBack,
        SkipFwd,
        Moon,
        Bookmark,
        List,
        Speed,
        Download,
        Star,
        StarOutline,
        StarHalf,
        Check,
        Lock,
        Timer,
        Wave,
        Heart,
        Comment,
        HeartFilled,
        BookmarkFilled,
        Downloaded,
        Offline,
        BookmarkAdd,
        BookmarkRemove,
        ChevronUp,
        ArrowUp,
        ArrowDown,
        Refresh,
        Replay,
        Person,
        Upload,
        Trash,
        Edit,
        Info,
        Alert,
        Medal,
        Globe,
        Translate,
        Shield,
        Storage,
        Sort,
        FilterOff,
        Close,
        DragHandle,
        Grid,
        ListCover,
        Settings,
        Library,
        Book,
        Link,
        Flame,
        Rew15,
        Fwd30,
    )
}

/**
 * Optical sizes measured from the prototype. The glyph is drawn once on the
 * 24 dp grid and reused at every size; a 20 dp icon therefore carries a ~1.5 dp
 * stroke, which is the weight of the 12-13 sp label type beside it.
 */
object PaperInkIconSizes {
    /** Inline with label/body type: chips, metadata rows. */
    val Inline = 20.dp

    /** Chrome actions in headers and rows (the prototype's `.ico`). */
    val Chrome = 22.dp

    /** Bottom navigation / rail. */
    val Navigation = 23.dp

    /** Screen headers and player tool buttons. */
    val Header = 24.dp

    /** Transport controls (chapter skip, replay). */
    val Transport = 28.dp

    /** The one play/pause button. */
    val Play = 30.dp
}

/**
 * One icon at a semantic size, so a call site names the ROLE instead of a dp
 * literal:
 *
 * ```
 * PaperInkIcon(PaperInkIcons.Search, searchDescription, size = PaperInkIconSizes.Chrome)
 * ```
 *
 * The 48 dp touch target belongs to the clickable box around the icon and is
 * never shrunk to this size.
 */
@Composable
fun PaperInkIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = PaperInkIconSizes.Header,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

// ── primitives ────────────────────────────────────────────────────────────────

private fun buildIcon(
    name: String,
    autoMirror: Boolean = false,
    block: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).apply(block).build()

private fun ImageVector.Builder.stroke(pathData: String, strokeWidth: Float = 1.8f) {
    addPath(
        pathData = addPathNodes(pathData),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fill(pathData: String, evenOdd: Boolean = false) {
    addPath(
        pathData = addPathNodes(pathData),
        fill = SolidColor(Color.Black),
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
    )
}
