package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1673 — Ink theme made the two «Збережене» headings almost invisible.
 *
 * The defect was not a palette value: both `Text`s set only a typography style
 * and NO colour, so they inherited `LocalContentColor`. On the ink path nothing
 * sets it, so it stayed the default black on a near-black page (the Paper theme
 * looked fine, which is why the bug shipped). Semantics cannot see a colour and
 * a device screenshot is not a gate, so the cheap fast witness is the source:
 * both headings must route through the composable that NAMES the role.
 *
 * [com.slukhayka.audiobooks.ui.snapshots.LibrarySavedHeaderInkSnapshotTest]
 * pins the pixels on top of this decision.
 */
class LibrarySavedHeaderInkSourceTest {

    private val sourceRoot: File =
        listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        ).first { it.isDirectory }

    private fun libraryScreen(): String =
        File(sourceRoot, "ui/screens/LibraryScreen.kt").readText()

    @Test
    fun `the shared saved heading names its colour role explicitly`() {
        val body = libraryScreen()
            .substringAfter("internal fun LibrarySavedSectionHeader(")
            .substringBefore("\n}\n")

        assertTrue(
            "the heading must not rely on the inherited LocalContentColor (#1673)",
            body.contains("color = MaterialTheme.colorScheme.onSurface"),
        )
    }

    @Test
    fun `both saved headings use the shared composable`() {
        val library = libraryScreen()
        val beforeDefinition = library.substringBefore("internal fun LibrarySavedSectionHeader(")

        assertTrue(
            "«Люди» heading must route through LibrarySavedSectionHeader",
            beforeDefinition.contains("testTag = \"library_saved_people_header\""),
        )
        assertTrue(
            "«Закладки» heading must route through LibrarySavedSectionHeader",
            beforeDefinition.contains("testTag = \"library_saved_bookmarks_header\""),
        )
        // And the old inline shape (a bare Text with only a style) must be gone.
        assertTrue(
            "no inline saved heading may keep the colour-less shape",
            !beforeDefinition.contains(
                "text = stringResource(R.string.lib_section_people),\n" +
                    "                                style = MaterialTheme.typography.titleSmall,"
            ),
        )
    }
}
