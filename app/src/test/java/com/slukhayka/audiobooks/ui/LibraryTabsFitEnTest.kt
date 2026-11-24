package com.slukhayka.audiobooks.ui

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1627 — the same fit contract in EN.
 *
 * A SEPARATE FILE on purpose: `scripts/test_partitions.py` discovers test
 * classes from the FILE STEM, so a second class co-located in
 * `LibraryTabsFitTest.kt` would never run in CI. In EN the widest label is
 * «Shelves», not «Saved» — the exact case a saved-only measurement missed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryTabsFitEnTest : LibraryTabsFitTestBase() {
    override val labels = listOf("Books", "Shelves", "Saved")
}
