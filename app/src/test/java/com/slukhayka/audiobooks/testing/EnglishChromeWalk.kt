package com.slukhayka.audiobooks.testing

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.assertTrue

/**
 * #980 — the one semantics walk every English-chrome check runs on.
 *
 * The earlier EN slices (#975 index, #978 series, #979 collections) each grew
 * a private `collectTexts` that read only Text, ContentDescription and
 * StateDescription, which silently made every "the EN run is clean" claim
 * weaker than it sounded: `SemanticsProperties.PaneTitle` — the title TalkBack
 * announces first when a sheet or screen opens — was never read, so
 * `AddToCollectionSheet`'s `accessibilityPane("Додати до добірки")` sat in the
 * blind spot and the walk still said "clean".
 *
 * The walk lives in one place on purpose: a fourth surface must reach for it
 * instead of reinventing a narrower one. It reads EVERY root — dialogs and
 * modal sheets compose into their own — and it reads the UNMERGED tree, so a
 * container that carries its own `contentDescription`/`paneTitle` is inspected
 * directly rather than being folded into its descendants and lost.
 */
object EnglishChromeWalk {

    /** Any Cyrillic letter: the app's default locale is Ukrainian. */
    val CYRILLIC: Regex = Regex("[А-Яа-яІіЇїЄєҐґ]")

    /**
     * Every user-visible string the semantics forest carries — Text,
     * ContentDescription, StateDescription and PaneTitle — from every root and
     * every node, parents included.
     */
    fun collectTexts(rule: ComposeTestRule): List<String> =
        rule.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { collectTexts(it) }

    /** [collectTexts] narrowed to what the EN run must never contain. */
    fun leakedCyrillic(rule: ComposeTestRule): List<String> =
        collectTexts(rule).filter { CYRILLIC.containsMatchIn(it) }

    /**
     * Fails unless all rendered chrome is free of Cyrillic. [surface] names the
     * slice in the failure, and the message carries how many strings leaked as
     * well as which ones — the number is the honest measure of the fix's size.
     */
    fun assertNoCyrillic(rule: ComposeTestRule, surface: String) {
        val leaked = leakedCyrillic(rule)
        assertTrue(
            "Ukrainian chrome leaked into the EN $surface run " +
                "(${leaked.size} string(s)): $leaked",
            leaked.isEmpty()
        )
    }

    private fun collectTexts(node: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
        // #980 — the property the three slices above could not see.
        node.config.getOrNull(SemanticsProperties.PaneTitle)?.let { out += it }
        node.children.forEach { out += collectTexts(it) }
        return out
    }
}
