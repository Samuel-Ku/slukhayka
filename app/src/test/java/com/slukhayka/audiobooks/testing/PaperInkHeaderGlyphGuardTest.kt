package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1623 — a surface that already speaks the «Папір і голос» direction draws its
 * chrome from ONE glyph family.
 *
 * The device audit found the migrated library header still carrying M3
 * `MoreVert`/`Add` and the search fields still carrying M3 `Search`, while the
 * family has had `More`/`Plus`/`Search` since #1534. Replacing them once is a
 * diff; keeping them replaced is a rule — a mixed weight is most visible exactly
 * on the chrome the direction's first screen is judged by.
 *
 * The guard is a disk read on the JVM: no device, no Android runtime. It names
 * the surfaces fixed in #1623 on purpose — the rest of `ui/` is a follow-up
 * (see the issue), and a blanket rule would either fail on legacy screens or
 * force a rename of the whole tree in one PR.
 */
class PaperInkHeaderGlyphGuardTest {

    private val repoRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/; the IDE may use the repo root.
        val cwd = File(System.getProperty("user.dir"))
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "app/build.gradle.kts").isFile }
            ?: error("repo root not found from $cwd")
    }

    /** `Icons.Default.MoreVert` / `Icons.Filled.Add` / `...Search` — the M3
     *  glyphs whose values the family already owns. */
    private val banned: Regex by lazy {
        Regex("""Icons\.(?:Default|Filled)\.(?:MoreVert|Add|Search)\b""")
    }

    /**
     * The migrated surfaces this PR closed. Value = the family glyphs each must
     * draw; the key = its path from the repo root.
     */
    private val surfaces: Map<String, List<String>> = mapOf(
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryScreen.kt" to
            listOf("More", "Plus", "Search"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/HomeScreen.kt" to
            listOf("More", "Search"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/BookDetailScreen.kt" to
            listOf("More"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/BookDetailReviews.kt" to
            listOf("More"),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/WebSourceBrowserScreen.kt" to
            listOf("Plus"),
    )

    @Test
    fun noMaterialGlyphInMigratedPaperHeadersAndSearch() {
        val offenders = surfaces.keys.mapNotNull { path ->
            val text = File(repoRoot, path).readText()
            val matches = banned.findAll(text).map { it.value }.toList()
            if (matches.isEmpty()) null else "$path -> $matches"
        }
        assertTrue(
            "migrated paper chrome must not fall back to M3 MoreVert/Add/Search:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The browser screen's own M3 leftovers: Warning/Close/Link/Refresh/Home
     *  and the AutoMirrored OpenInNew. The family owns Warning→Alert, Close,
     *  Link and Refresh; Home/OpenInNew have no family glyph, so they move to
     *  the Outlined weight instead of staying Filled (§5.4.7). */
    private val browserBanned: Regex by lazy {
        Regex("""Icons\.(?:Default|Filled)\.(?:Warning|Close|Link|Refresh|Home)\b|Icons\.AutoMirrored\.Filled\.OpenInNew\b""")
    }

    private val browserRequired: List<String> = listOf(
        "PaperInkIcons.Alert",
        "PaperInkIcons.Close",
        "PaperInkIcons.Link",
        "PaperInkIcons.Refresh",
        "Icons.Outlined.Home",
        "Icons.AutoMirrored.Outlined.OpenInNew",
    )

    /** The library's own M3 leftovers: the section back arrow, the saved/imported
     *  empty states, the reading-year cards and the bookmark/person rows. The
     *  family owns the arrow (`ChevronLeft`, auto-mirrored), `Friends`,
     *  `Bookmark`, `Flame`, `Trash` and `Person`; `Today`/`DateRange`/
     *  `CalendarMonth`/`Inbox` have no family glyph, so they move to the
     *  Outlined weight (§5.4.7), not to a Filled stand-in. */
    private val libraryBanned: Regex by lazy {
        Regex("""Icons\.(?:AutoMirrored\.)?(?:Default|Filled)\.(?:ArrowBack|Group|Bookmark|Inbox|Today|DateRange|Whatshot|Delete|Person|CalendarMonth)\b""")
    }

    private val libraryRequired: List<String> = listOf(
        "PaperInkIcons.ChevronLeft",
        "PaperInkIcons.Friends",
        "PaperInkIcons.Bookmark",
        "PaperInkIcons.Flame",
        "PaperInkIcons.Trash",
        "PaperInkIcons.Person",
        "Icons.Outlined.Inbox",
        "Icons.Outlined.Today",
        "Icons.Outlined.DateRange",
        "Icons.Outlined.CalendarMonth",
    )

    @Test
    fun libraryScreenUsesFamilyOrOutlinedGlyphs() {
        val path = "app/src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryScreen.kt"
        val text = File(repoRoot, path).readText()
        val offenders = libraryBanned.findAll(text).map { it.value }.toList()
        assertTrue(
            "$path must not keep M3 Filled glyphs the family or Outlined weight owns: $offenders",
            offenders.isEmpty()
        )
        val missing = libraryRequired.filterNot { text.contains(it) }
        assertTrue(
            "$path is missing the migrated glyphs: " + missing.joinToString(),
            missing.isEmpty()
        )
    }

    /** The book page's own M3 leftovers: the header back arrow, the ⋮ menu
     *  entries (mark listened, edit order/metadata, update published, save to
     *  library, delete) and the «open on Sluhay» door. The family owns the
     *  arrow (`ChevronLeft`), `Check`, `Edit`, `Upload`, `BookmarkAdd` and
     *  `Trash`; `OpenInNew` has no family glyph, so it moves to the Outlined
     *  weight (§5.4.7). */
    private val bookDetailBanned: Regex by lazy {
        Regex("""Icons\.(?:AutoMirrored\.)?(?:Default|Filled)\.(?:ArrowBack|OpenInNew|CheckCircle|Edit|CloudUpload|BookmarkAdd|Delete)\b""")
    }

    private val bookDetailRequired: List<String> = listOf(
        "PaperInkIcons.ChevronLeft",
        "PaperInkIcons.Check",
        "PaperInkIcons.Edit",
        "PaperInkIcons.Upload",
        "PaperInkIcons.BookmarkAdd",
        "PaperInkIcons.Trash",
        "Icons.AutoMirrored.Outlined.OpenInNew",
    )

    @Test
    fun bookDetailScreenUsesFamilyOrOutlinedGlyphs() {
        val path = "app/src/main/java/com/slukhayka/audiobooks/ui/screens/BookDetailScreen.kt"
        val text = File(repoRoot, path).readText()
        val offenders = bookDetailBanned.findAll(text).map { it.value }.toList()
        assertTrue(
            "$path must not keep M3 Filled glyphs the family or Outlined weight owns: $offenders",
            offenders.isEmpty()
        )
        val missing = bookDetailRequired.filterNot { text.contains(it) }
        assertTrue(
            "$path is missing the migrated glyphs: " + missing.joinToString(),
            missing.isEmpty()
        )
    }

    /** The Listen tab's own M3 leftovers: the «all shelves hidden» empty state,
     *  the shelf-management sheet (close, restore-all, show/hide toggles) and the
     *  fresh-install door (headphones + browse/import actions). The family owns
     *  `Close`, `Headphones`, `Compass` and `Upload`; `Visibility`,
     *  `VisibilityOff` and `SettingsBackupRestore` have no family glyph, so they
     *  move to the Outlined weight (§5.4.7). */
    private val listenBanned: Regex by lazy {
        Regex("""Icons\.(?:Default|Filled)\.(?:VisibilityOff|Visibility|Close|SettingsBackupRestore|Headphones|Explore|FileUpload)\b""")
    }

    private val listenRequired: List<String> = listOf(
        "PaperInkIcons.Close",
        "PaperInkIcons.Headphones",
        "PaperInkIcons.Compass",
        "PaperInkIcons.Upload",
        "Icons.Outlined.Visibility",
        "Icons.Outlined.VisibilityOff",
        "Icons.Outlined.SettingsBackupRestore",
    )

    @Test
    fun listenScreenUsesFamilyOrOutlinedGlyphs() {
        val path = "app/src/main/java/com/slukhayka/audiobooks/ui/screens/ListenScreen.kt"
        val text = File(repoRoot, path).readText()
        val offenders = listenBanned.findAll(text).map { it.value }.toList()
        assertTrue(
            "$path must not keep M3 Filled glyphs the family or Outlined weight owns: $offenders",
            offenders.isEmpty()
        )
        val missing = listenRequired.filterNot { text.contains(it) }
        assertTrue(
            "$path is missing the migrated glyphs: " + missing.joinToString(),
            missing.isEmpty()
        )
    }

    /** The last migrated-surface leftovers of the F11 sweep: the update banner,
     *  the catalogue cards' dismiss, the curator rail's empty fact, the book
     *  page's chapter/bookmark/edition rows and the review/highlight/abandon
     *  actions. Per file: the M3 glyphs its chrome may no longer draw, and the
     *  family/Outlined token that must be there instead. */
    private val migratedSurfaceRules: Map<String, Pair<Regex, List<String>>> = mapOf(
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/UpdateBanner.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:Close|SystemUpdateAlt)\b""") to
                listOf("PaperInkIcons.Close", "Icons.Outlined.SystemUpdateAlt")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/HomeFeedContent.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:Close|Refresh)\b""") to
                listOf("PaperInkIcons.Close", "PaperInkIcons.Refresh")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/PosterCard.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Close\b""") to
                listOf("PaperInkIcons.Close")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/collections/PublicCollectionsRail.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Bookmark\b""") to
                listOf("PaperInkIcons.Bookmark")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/bookdetail/BookDetailRows.kt" to
            (Regex("""Icons\.(?:AutoMirrored\.Filled|Default|Filled)\.(?:VolumeUp|Delete|Bookmark)\b""") to
                listOf("Icons.AutoMirrored.Outlined.VolumeUp", "PaperInkIcons.Trash", "PaperInkIcons.Bookmark")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/bookdetail/BookDetailActions.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.BookmarkAdd\b""") to
                listOf("PaperInkIcons.BookmarkAdd")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/bookdetail/BookDetailSections.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:Public|RecordVoiceOver)\b""") to
                listOf("Icons.Outlined.Public", "Icons.Outlined.RecordVoiceOver")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/BookDetailReviews.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:CloudUpload|Edit|Delete)\b""") to
                listOf("PaperInkIcons.Upload", "PaperInkIcons.Edit", "PaperInkIcons.Trash")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/bookdetail/BookDetailHighlights.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:Favorite|FavoriteBorder)\b""") to
                listOf("PaperInkIcons.HeartFilled", "PaperInkIcons.Heart")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/bookdetail/BookAbandonAction.kt" to
            (Regex("""Icons\.(?:AutoMirrored\.Filled|Default|Filled)\.(?:Undo|BookmarkRemove)\b""") to
                listOf("Icons.AutoMirrored.Outlined.Undo", "PaperInkIcons.BookmarkRemove")),
    )

    @Test
    fun remainingMigratedSurfacesUseFamilyOrOutlinedGlyphs() {
        // A deleted map entry would silently shrink coverage; pin the count.
        assertEquals(10, migratedSurfaceRules.size)
        val offenders = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((path, rule) in migratedSurfaceRules) {
            val text = File(repoRoot, path).readText()
            rule.first.findAll(text).forEach { offenders += "$path -> ${it.value}" }
            rule.second.filterNot { text.contains(it) }.forEach { missing += "$path -> $it" }
        }
        assertTrue(
            "migrated paper surfaces must not fall back to M3 Filled glyphs:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
        assertTrue(
            "these migrated surfaces must draw the family/Outlined glyph:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    @Test
    fun listenScreenCarriesNoDeadFilledWildcardImport() {
        val path = "app/src/main/java/com/slukhayka/audiobooks/ui/screens/ListenScreen.kt"
        val text = File(repoRoot, path).readText()
        assertFalse(
            "$path must not keep the dead `filled.*` wildcard import (#1654 nit)",
            text.contains("import androidx.compose.material.icons.filled.*")
        )
    }

    /** The stragglers the F11 sweep found last: the Home feed's sort/seen/retry
     *  actions, the import sheet, the people/author empty states, the player's
     *  sleep/speed sheets, the shared gear, the cover placeholder, the chapter
     *  drag handle, the index/collection back arrows and the settings
     *  sub-screens. Same contract: no M3 Filled where the family or the
     *  Outlined weight owns the value (§5.4.7). */
    private val stragglerRules: Map<String, Pair<Regex, List<String>>> = mapOf(
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/HomeScreen.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:ArrowDropDown|Done|FileUpload|Refresh)\b""") to
                listOf("PaperInkIcons.Refresh", "PaperInkIcons.Check", "PaperInkIcons.Upload", "PaperInkIcons.ChevronDown")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryImportSheet.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:Close|FileUpload|CreateNewFolder|Link)\b""") to
                listOf("PaperInkIcons.Close", "PaperInkIcons.Upload", "PaperInkIcons.Link", "Icons.Outlined.CreateNewFolder")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/PeopleScreen.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:Info|Warning|Person)\b""") to
                listOf("PaperInkIcons.Info", "PaperInkIcons.Alert", "PaperInkIcons.Person")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/AuthorDiscovery.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.(?:Person|Warning)\b""") to
                listOf("PaperInkIcons.Person", "PaperInkIcons.Alert")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/OpenWebSourceRow.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Language\b""") to
                listOf("PaperInkIcons.Globe")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/SleepTimerSheet.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Close\b""") to listOf("PaperInkIcons.Close")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/SpeedSheet.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Close\b""") to listOf("PaperInkIcons.Close")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/AppSettingsGear.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Settings\b""") to listOf("PaperInkIcons.Settings")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/CatalogCoverImage.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Headphones\b""") to listOf("PaperInkIcons.Headphones")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/ChapterDragHandle.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.DragHandle\b""") to listOf("PaperInkIcons.DragHandle")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/components/IndexScreenScaffold.kt" to
            (Regex("""Icons\.(?:AutoMirrored\.Filled|Default|Filled)\.ArrowBack\b""") to listOf("PaperInkIcons.ChevronLeft")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/collections/CollectionPage.kt" to
            (Regex("""Icons\.(?:AutoMirrored\.Filled|Default|Filled)\.ArrowBack\b""") to listOf("PaperInkIcons.ChevronLeft")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/AppLocaleScreen.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Language\b""") to listOf("PaperInkIcons.Globe")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/ContentLanguageScreen.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Language\b""") to listOf("PaperInkIcons.Globe")),
        "app/src/main/java/com/slukhayka/audiobooks/ui/screens/NetworkPrivacyScreen.kt" to
            (Regex("""Icons\.(?:Default|Filled)\.Shield\b""") to listOf("PaperInkIcons.Shield")),
    )

    @Test
    fun lastMigratedSurfaceStragglersUseFamilyOrOutlinedGlyphs() {
        // Same non-vacuity contract as the #1656 map: deleting an entry must fail.
        assertEquals(15, stragglerRules.size)
        val offenders = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((path, rule) in stragglerRules) {
            val text = File(repoRoot, path).readText()
            rule.first.findAll(text).forEach { offenders += "$path -> ${it.value}" }
            rule.second.filterNot { text.contains(it) }.forEach { missing += "$path -> $it" }
        }
        assertTrue(
            "migrated paper surfaces must not fall back to M3 Filled glyphs:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
        assertTrue(
            "these migrated surfaces must draw the family/Outlined glyph:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    @Test
    fun webSourceBrowserUsesFamilyOrOutlinedGlyphs() {
        val path = "app/src/main/java/com/slukhayka/audiobooks/ui/screens/WebSourceBrowserScreen.kt"
        val text = File(repoRoot, path).readText()
        val offenders = browserBanned.findAll(text).map { it.value }.toList()
        assertTrue(
            "$path must not keep M3 Filled glyphs the family or Outlined weight owns: $offenders",
            offenders.isEmpty()
        )
        val missing = browserRequired.filterNot { text.contains(it) }
        assertTrue(
            "$path is missing the migrated glyphs: " + missing.joinToString(),
            missing.isEmpty()
        )
    }

    @Test
    fun everyMigratedSurfaceDrawsItsFamilyGlyph() {
        val missing = surfaces.flatMap { (path, glyphs) ->
            val text = File(repoRoot, path).readText()
            glyphs.filterNot { text.contains("PaperInkIcons.$it") }.map { "$path -> $it" }
        }
        assertTrue(
            "these migrated surfaces must draw the family glyph, not a text or M3 stand-in:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
        assertFalse(surfaces.isEmpty())
    }
}
