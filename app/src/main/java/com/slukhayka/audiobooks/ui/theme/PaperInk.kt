package com.slukhayka.audiobooks.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.R

/**
 * The «Папір і голос» direction (2026-2027): paper and ink materials, one
 * saturated accent (the lamp) that means sound, and a serif that appears only
 * in big book titles.
 *
 * This file is the **token layer** of the direction. It is additive on purpose:
 * it does not replace [Typography], [AppDimens] or the schemes in [Theme.kt]
 * yet, so shipping it changes no pixel. The migration (primitives → screens)
 * switches to these tokens step by step and updates snapshots per screen.
 *
 * Sources of truth:
 *  - spec: `docs/specs/2026-10-26-ui-refresh-2027.md`
 *  - prototype: `docs/prototypes/2026-ui-refresh/` (412×915 dp, both themes)
 *
 * Contrast of every role pair below is pinned by `PaperInkTokensTest`.
 */
object PaperInkPalette {

    // ── Light theme: «Папір» ────────────────────────────────────────────────
    // Warm, slightly grey paper; never pure white, never cream-with-terracotta.
    val Paper = Color(0xFFF1EEE4)          // screen ground
    val Page = Color(0xFFFAF8F1)           // cards, fields, hero
    val Ink = Color(0xFF1B1E18)            // primary text
    val Dust = Color(0xFF5D6156)           // secondary text
    val Dust2 = Color(0xFF5F6457)          // micro text: sources, days, counters
    val Verdigris = Color(0xFF2E5C4B)      // actions — deliberately muted
    val OnVerdigris = Color(0xFFF7F4E9)
    val VerdigrisSoft = Color(0xFFDCE8DE)
    val OnVerdigrisSoft = Color(0xFF1C3529)
    val Lamp = Color(0xFFA96A12)           // sound + progress (graphics, 3:1 on track)
    val LampLight = Color(0xFFD89A2E)      // the glow only, never text or lines
    val LampInk = Color(0xFF7A4E00)        // amber as text on paper
    val LampSoft = Color(0xFFF3E2C0)
    val OnLampSoft = Color(0xFF4A3200)
    val Line = Color(0xFFDCD6C5)           // decorative separators
    val LineStrong = Color(0xFF8A8270)     // borders that are an affordance
    val Track = Color(0xFFE8E3D4)          // progress track
    val Danger = Color(0xFFA8472C)
    val OnDanger = Color(0xFFFFF7F3)
    val DangerSoft = Color(0xFFF5D6CE)
    val OnDangerSoft = Color(0xFF3D0F05)

    // ── Dark theme: «Чорнило» ───────────────────────────────────────────────
    // A green-cast ink, not graphite: the old #111318 was the "streaming app"
    // tell. Elevation is expressed by light, not by shadow.
    val Night = Color(0xFF0E1411)
    val NightSurface = Color(0xFF161D19)
    val NightHigh = Color(0xFF1E2721)
    val Chalk = Color(0xFFECE8DB)
    val DustNight = Color(0xFFA4A89A)
    val VerdigrisNight = Color(0xFF8FC9AD)
    val OnVerdigrisNight = Color(0xFF0D241B)
    val VerdigrisSoftNight = Color(0xFF24382F)
    val OnVerdigrisSoftNight = Color(0xFFC9E8D8)
    val LampNight = Color(0xFFEFC36C)
    val LampSoftNight = Color(0xFF3A2E1A)
    val OnLampSoftNight = Color(0xFFF7E3BC)
    val LineNight = Color(0xFF2C3630)
    val LineStrongNight = Color(0xFF6A756D)
    val TrackNight = Color(0x29ECE8DB)     // chalk at 16 %
    val DangerNight = Color(0xFFE5876B)
    val OnDangerNight = Color(0xFF2A0F08)
    val DangerSoftNight = Color(0xFF5C2A1E)
    val OnDangerSoftNight = Color(0xFFF7D8CE)
}

/**
 * Two families, two jobs.
 *
 * Onest carries the interface (OFL, variable 100–900 upstream; five static
 * instances are shipped because `minSdk 24` cannot use `FontVariationAxis`).
 * Literata carries big book titles, numbers and quotes only — a real reading
 * face, not a display serif pasted on every card.
 *
 * Fonts are subset to Latin + Cyrillic + Ukrainian punctuation (U+02BC included)
 * without hinting to stay around 296 KB total in the APK.
 */
object PaperInkType {

    val Ui: FontFamily = FontFamily(
        Font(R.font.onest_regular, FontWeight.Normal),
        Font(R.font.onest_medium, FontWeight.Medium),
        Font(R.font.onest_semibold, FontWeight.SemiBold),
        Font(R.font.onest_bold, FontWeight.Bold),
        Font(R.font.onest_extrabold, FontWeight.ExtraBold),
    )

    val Book: FontFamily = FontFamily(
        Font(R.font.literata_semibold, FontWeight.SemiBold),
    )

    /** Root-tab title: «Слухати», «Медіатека». Onest ExtraBold, tight tracking. */
    val ScreenTitle = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-1.1).sp,
    )

    /** Shelf / block heading: «Продовжити серію». */
    val SectionTitle = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.45).sp,
    )

    /** The hero's book name: Literata 24/29. */
    val BookTitleHero = TextStyle(
        fontFamily = Book,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.25).sp,
    )

    /** Book-detail name: Literata 25/31. */
    val BookTitlePage = TextStyle(
        fontFamily = Book,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.3).sp,
    )

    /** Player book name: Literata 28/32. */
    val BookTitlePlayer = TextStyle(
        fontFamily = Book,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    )

    /** The mini-player's one-line book title: 13/17, the bar's own density. */
    val MiniTitle = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    )

    /** A book name inside a dense row or a card. */
    val RowTitle = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    )

    /** Descriptions, sheets, review bodies. */
    val Body = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )

    /** A body line that carries the main action. */
    val BodyStrong = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    )

    /** Author, series, subtitle. */
    val Meta = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    )

    /** Buttons, chips, captions. */
    val Label = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    )

    /** The only sub-12 role: sources, week days, counters — never content. */
    val Micro = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.1.sp,
    )

    /** Library / friends tabs. */
    val Tab = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    )

    /** The wordmark eyebrow above a root title: 11/15, 800, +0.35 tracking. */
    val TrackingLabel = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.35.sp,
    )

    /** All timers: tabular figures so seconds never jitter. */
    val Timer = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )

    /** The player's big elapsed time. */
    val TimerXL = TextStyle(
        fontFamily = Ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum",
    )

    /** «Мій рік» and other big numbers: Literata, not UI sans. */
    val StatXL = TextStyle(
        fontFamily = Book,
        fontWeight = FontWeight.SemiBold,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.8).sp,
        fontFeatureSettings = "tnum",
    )

    /** Empty-state line: quiet Literata instead of an icon placeholder. */
    val EmptyTitle = TextStyle(
        fontFamily = Book,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.4).sp,
    )

    /**
     * M3 slot mapping, used by framework components that read
     * `MaterialTheme.typography` directly (dialogs, sheets, menus). The custom
     * styles above stay the API for our own components.
     */
    val typography = Typography(
        displaySmall = StatXL,
        headlineLarge = ScreenTitle,
        headlineMedium = BookTitlePlayer,
        headlineSmall = BookTitlePage,
        titleLarge = TextStyle(
            fontFamily = Ui,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.45).sp,
        ),
        titleMedium = BodyStrong,
        titleSmall = Tab,
        bodyLarge = Body,
        bodyMedium = Meta,
        bodySmall = TextStyle(
            fontFamily = Ui,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = Ui,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = Label,
        labelSmall = Micro,
    )
}

/**
 * Spacing, radii and the measured sizes of the direction.
 *
 * The 4 dp base is the rule: every value below is a multiple of four except the
 * deliberately named cover ratios and the 30/18 dp optical numbers in the
 * player. Sizes come from the prototype render and are pinned by
 * `PaperInkTokensTest` so a later screen cannot "fix" them by eye.
 */
object PaperInkDimens {

    // Spacing
    val Base = 4.dp
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 20.dp
    val SpaceSection = 32.dp
    val SpacePage = 20.dp

    // Shape scale (M3: 8 / 12 / 16 / 24 / 28 / full)
    val RadiusMicro = 8.dp
    val RadiusSmall = 12.dp
    val RadiusMedium = 16.dp
    val RadiusLarge = 24.dp
    val RadiusXl = 28.dp
    val RadiusFull = 999.dp

    // Covers — one scale for the whole app
    val CoverCompactWidth = 48.dp
    val CoverCompactHeight = 72.dp
    val CoverRowWidth = 58.dp
    val CoverRowHeight = 87.dp
    val CoverCollectionWidth = 84.dp
    val CoverCollectionHeight = 126.dp
    val CoverHeroWidth = 104.dp
    val CoverHeroHeight = 156.dp
    val CoverPageWidth = 118.dp
    val CoverPageHeight = 177.dp
    val CoverShelfWidth = 132.dp
    val CoverShelfHeight = 198.dp
    val CoverPlayerWidth = 272.dp
    val CoverPlayerHeight = 408.dp

    // Components — measured, not guessed
    val TouchTarget = 48.dp
    val ScreenTitleBlock = 104.dp
    val HeroHeight = 193.dp
    val BookRowHeight = 112.dp
    val MiniPlayerHeight = 64.dp
    val MiniPlayerMargin = 16.dp
    val NavigationBarHeight = 86.dp
    val PrimaryButtonHeight = 52.dp
    val PlayerTransportTarget = 52.dp
    val PlayerPlayButton = 72.dp
    // F8 (аудит 2026-10-28): прототип `.sl-play { width:80px; height:72px }` —
    // кнопка ширша за високу, глиф 28–30 замість 40.
    val PlayerPlayButtonWidth = 80.dp
    val PlayerPlayGlyph = 30.dp
    val PlayerTransportGlyph = 30.dp
    val PlayerSeekHeight = 44.dp
    val ChapterRowHeight = 44.dp
    val SeekTrack = 6.dp
    val BookProgressLine = 2.dp
    val ShelfProgressLine = 3.dp
    val AlbumArtRadius = 24.dp
}

/** The shape set handed to `MaterialTheme` once the direction is wired. */
val PaperInkShapes = Shapes(
    extraSmall = RoundedCornerShape(PaperInkDimens.RadiusMicro),
    small = RoundedCornerShape(PaperInkDimens.RadiusSmall),
    medium = RoundedCornerShape(PaperInkDimens.RadiusMedium),
    large = RoundedCornerShape(PaperInkDimens.RadiusLarge),
    extraLarge = RoundedCornerShape(PaperInkDimens.RadiusXl),
)

/** The light «Папір» scheme. Every text pair clears 4.5:1 (see the test). */
internal fun paperInkLightScheme(): ColorScheme = lightColorScheme(
    primary = PaperInkPalette.Verdigris,
    onPrimary = PaperInkPalette.OnVerdigris,
    primaryContainer = PaperInkPalette.VerdigrisSoft,
    onPrimaryContainer = PaperInkPalette.OnVerdigrisSoft,
    secondary = PaperInkPalette.Dust,
    onSecondary = PaperInkPalette.OnVerdigris,
    secondaryContainer = PaperInkPalette.Track,
    onSecondaryContainer = PaperInkPalette.Ink,
    tertiary = PaperInkPalette.Lamp,
    onTertiary = Color(0xFF0A0600),
    tertiaryContainer = PaperInkPalette.LampSoft,
    onTertiaryContainer = PaperInkPalette.OnLampSoft,
    background = PaperInkPalette.Paper,
    onBackground = PaperInkPalette.Ink,
    surface = PaperInkPalette.Page,
    onSurface = PaperInkPalette.Ink,
    surfaceVariant = PaperInkPalette.Track,
    onSurfaceVariant = PaperInkPalette.Dust,
    surfaceDim = Color(0xFFE9E4D6),
    surfaceBright = Color(0xFFFBF9F3),
    surfaceContainerLowest = Color(0xFFFBF9F3),
    surfaceContainerLow = Color(0xFFF7F3E9),
    surfaceContainer = Color(0xFFF2EEE3),
    surfaceContainerHigh = Color(0xFFEAE5D7),
    surfaceContainerHighest = Color(0xFFE3DDCC),
    inverseSurface = PaperInkPalette.Ink,
    inverseOnSurface = PaperInkPalette.Paper,
    inversePrimary = PaperInkPalette.VerdigrisNight,
    surfaceTint = PaperInkPalette.Verdigris,
    outline = PaperInkPalette.LineStrong,
    outlineVariant = PaperInkPalette.Line,
    error = PaperInkPalette.Danger,
    onError = PaperInkPalette.OnDanger,
    errorContainer = PaperInkPalette.DangerSoft,
    onErrorContainer = PaperInkPalette.OnDangerSoft,
    scrim = Color.Black,
)

/** The dark «Чорнило» scheme. Same hierarchy, different material. */
internal fun paperInkDarkScheme(): ColorScheme = darkColorScheme(
    primary = PaperInkPalette.VerdigrisNight,
    onPrimary = PaperInkPalette.OnVerdigrisNight,
    primaryContainer = PaperInkPalette.VerdigrisSoftNight,
    onPrimaryContainer = PaperInkPalette.OnVerdigrisSoftNight,
    secondary = PaperInkPalette.DustNight,
    onSecondary = PaperInkPalette.OnVerdigrisNight,
    secondaryContainer = PaperInkPalette.NightHigh,
    onSecondaryContainer = PaperInkPalette.Chalk,
    tertiary = PaperInkPalette.LampNight,
    onTertiary = Color(0xFF241A00),
    tertiaryContainer = PaperInkPalette.LampSoftNight,
    onTertiaryContainer = PaperInkPalette.OnLampSoftNight,
    background = PaperInkPalette.Night,
    onBackground = PaperInkPalette.Chalk,
    surface = PaperInkPalette.NightSurface,
    onSurface = PaperInkPalette.Chalk,
    surfaceVariant = PaperInkPalette.NightHigh,
    onSurfaceVariant = PaperInkPalette.DustNight,
    surfaceDim = Color(0xFF0A0F0D),
    surfaceBright = Color(0xFF2A342E),
    surfaceContainerLowest = Color(0xFF090D0B),
    surfaceContainerLow = Color(0xFF121814),
    surfaceContainer = PaperInkPalette.NightSurface,
    surfaceContainerHigh = PaperInkPalette.NightHigh,
    surfaceContainerHighest = Color(0xFF26312A),
    inverseSurface = PaperInkPalette.Chalk,
    inverseOnSurface = PaperInkPalette.Night,
    inversePrimary = PaperInkPalette.Verdigris,
    surfaceTint = PaperInkPalette.VerdigrisNight,
    outline = PaperInkPalette.LineStrongNight,
    outlineVariant = PaperInkPalette.LineNight,
    error = PaperInkPalette.DangerNight,
    onError = PaperInkPalette.OnDangerNight,
    errorContainer = PaperInkPalette.DangerSoftNight,
    onErrorContainer = PaperInkPalette.OnDangerSoftNight,
    scrim = Color.Black,
)
