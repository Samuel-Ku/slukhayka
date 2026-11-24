package com.slukhayka.audiobooks

import android.content.Context
import androidx.compose.material3.R as Material3R
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1389 — a Polish device must resolve the Material3 bottom-sheet strings in
 * Polish, not in the Ukrainian that the old #371 override used to force.
 *
 * The guard `PolishCoverageGuardTest` derives only APP string keys from Kotlin
 * sources, so it cannot see these five: they are library resource IDs whose
 * values the app overrides in `values-pl/strings.xml`. That file flipped its
 * language together with the language switch, and this test proves the result
 * at the resource-resolution level — for both the language-only (`pl`) and a
 * realistic regional (`pl-rPL`) configuration, because the switch sets an exact
 * locale tag and a real device usually reports a region.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "pl")
class PolishMaterial3OverrideTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun polishDeviceSeesPolishMaterial3BottomSheetStrings() {
        assertEquals(
            "Plansza dolna",
            context.getString(Material3R.string.m3c_bottom_sheet_pane_title),
        )
        assertEquals(
            "Uchwyt do przeciągania",
            context.getString(Material3R.string.m3c_bottom_sheet_drag_handle_description),
        )
        assertEquals(
            "Zamknij planszę dolną",
            context.getString(Material3R.string.m3c_bottom_sheet_dismiss_description),
        )
    }

    @Test
    @Config(qualifiers = "pl-rPL")
    fun polishRegionDeviceAlsoSeesPolishMaterial3BottomSheetStrings() {
        assertEquals(
            "Plansza dolna",
            context.getString(Material3R.string.m3c_bottom_sheet_pane_title),
        )
        assertEquals(
            "Uchwyt do przeciągania",
            context.getString(Material3R.string.m3c_bottom_sheet_drag_handle_description),
        )
    }
}
