package com.slukhayka.audiobooks.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals

/**
 * #1809 — a [com.slukhayka.audiobooks.ui.screens.CheckboxRow] is ONE named
 * checkbox: the row owns the toggleable semantics and the visible label is
 * merged into it, so TalkBack announces «<label>, прапорець, позначено/не
 * позначено» and a tap on the label toggles the same state.
 *
 * The unmerged-tree count is the regression guard: a visual `Checkbox` that
 * still carried its own `onCheckedChange` would show up there as a second
 * toggle node, exactly the defect the issue describes in
 * FirstLanguageChoiceSheet.
 */
fun ComposeTestRule.assertNamedCheckboxRow(tag: String, label: String) {
    onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
    onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    onNodeWithTag(tag).assertTextContains(label)
    assertEquals(
        "expected exactly one toggle node in the row «$tag»",
        1,
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) and
                (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true
        ).fetchSemanticsNodes().size
    )
}
