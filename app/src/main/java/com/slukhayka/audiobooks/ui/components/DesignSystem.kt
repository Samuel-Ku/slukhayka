package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.ui.icons.PaperInkIconSizes
import com.slukhayka.audiobooks.ui.theme.AppDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/**
 * Design-system primitives (wayfinder #23): one set of section headers and
 * empty states for the whole app. Implements the empty-states house standard
 * from the audit — icon + title + short explanation + one or two next actions.
 * Content is separated by spacing and typography, never by nesting cards.
 */

// The canonical section header moved to SectionHeaders.kt (v1.4 C1,
// ADR-0033): two levels (group/section) with optional counter and action
// slots. This file keeps the canonical empty states.

/**
 * Full-size empty state: a 35 dp muted icon (the prototype's `.sl-empty`
 * glyph, #885), title, explanation and (per the house standard) one or two
 * next actions. Pass an [actions] block to render the
 * CTA column; without one the column is omitted entirely.
 *
 * v1.4 C4 (ADR-0033): [stateDescription] lets a transient state announce its
 * nature («Помилка») on the same title node that carries the polite
 * live-region; [iconContent] replaces the static icon with a live indicator
 * (a spinner) for the loading facade — one empty-state shape app-wide.
 */
@Composable
fun EmptyState(
    // spec §4 of the 2026 direction: an empty state is Literata 20 + a 12.5
    // description + ONE action, with NO placeholder glyph. Legacy surfaces
    // still pass their glyph; a migrated screen simply omits it — an absence is
    // stated in type, not illustrated with an icon that stands for something
    // that is not there.
    icon: ImageVector? = null,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    // [iconContent] precedes [actions] deliberately: `actions` must stay the
    // LAST parameter so trailing-lambda call sites keep binding to it.
    iconContent: (@Composable () -> Unit)? = null,
    stateDescription: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null
) {
    val paperInk = com.slukhayka.audiobooks.ui.theme.LocalPaperInkDesign.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (paperInk) com.slukhayka.audiobooks.ui.theme.PaperInkDimens.SpaceXl else AppDimens.SpaceXl,
                vertical = if (paperInk) com.slukhayka.audiobooks.ui.theme.PaperInkDimens.SpaceSection else AppDimens.SpaceSection,
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // #885 (wave 3) — the prototype's empty state is a bare muted glyph:
        // `.sl-empty svg { width:35px; height:35px; color:var(--sl-muted) }`
        // (`:1215-1217`). The app wrapped it in a 56 dp tonal circle, which
        // made an absence look like a decorated feature. Removed rather than
        // restyled: the circle was a shape the prototype never had.
        //
        // `iconContent` still replaces the glyph (the loading facade) — the
        // spinner needs no circle to read as "working".
        if (iconContent != null) {
            iconContent()
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                // Unslop audit 2026-11-01 (U3): the direction gives the lamp role
                // to sound only — playback state and listening progress. An empty
                // state is an absence, not a sound, so the glyph stays muted in the
                // paper scheme too. Before this, `tertiary` opened every empty
                // screen with a warm amber icon that competed with the one real
                // moment on the screen.
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(EmptyStateIconSize)
                    // A stable seam for the «no placeholder glyph» rule: a
                    // migrated empty state asserts this node does not exist.
                    .testTag("empty_state_icon")
            )
        }
        if (iconContent != null || icon != null) {
            Spacer(modifier = Modifier.height(AppDimens.SpaceMd))
        }
        // v1.4 C4 (ADR-0033): an empty state is transient — it announces
        // itself politely (screen readers) the moment it appears.
        Text(
            text = title,
            style = if (paperInk) {
                com.slukhayka.audiobooks.ui.theme.PaperInkType.EmptyTitle
            } else {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                if (stateDescription != null) this.stateDescription = stateDescription
            }
        )
        if (body.isNotBlank()) {
            Spacer(modifier = Modifier.height(AppDimens.SpaceXs))
            Text(
                text = body,
                style = if (paperInk) {
                    com.slukhayka.audiobooks.ui.theme.PaperInkType.Meta
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actions != null) {
            Spacer(modifier = Modifier.height(AppDimens.SpaceXl))
            actions()
        }
    }
}

/**
 * #1766 (audit U9/U13) — the 2026 direction's empty state: Literata title, ONE
 * short line and ONE primary action, optionally followed by a quiet text link
 * for the second path. There is NO placeholder glyph: an absence is stated in
 * type, not illustrated with an icon that stands for something that is not
 * there (spec §4 `EmptyState`, §2 rule 2).
 *
 * The component is PAPER-ONLY: it is rendered by the six paper surfaces this
 * ticket migrated and nobody else. Its typography is [PaperInkType]
 * unconditionally, so a host that has not yet switched its own theme still gets
 * the paper empty state rather than a silent fallback to legacy M3 type. It does
 * NOT replace [EmptyState] on the legacy call sites — those keep their component
 * until their own migration.
 *
 * Anchoring is the caller's half of the contract and the point of the ticket —
 * the panel fills the width it is given and centres its column inside the
 * height it is given (the `Box` below). A host that can hand it a viewport does
 * (`Modifier.fillParentMaxSize()` from a `LazyItemScope`, `Modifier.weight(1f)`
 * in a `Column`, or a full-size `Box`); a host that cannot gives it none and
 * the panel simply wraps — it never invents a fixed dp of dead space.
 *
 * [stateDescription] survives from [EmptyState] so a genuinely failed state can
 * announce «Помилка» on the same polite live-region title. None of the six
 * migrated surfaces passes it today (a plain absence stays silent about an
 * error that did not happen); it is kept for the future error branches that
 * will adopt this panel, and [com.slukhayka.audiobooks.ui.EmptyStatePanelTest]
 * pins the behaviour.
 */
@Composable
fun EmptyStatePanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    action: (@Composable () -> Unit)? = null,
    quietAction: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PaperInkDimens.SpaceXl,
                    vertical = PaperInkDimens.SpaceSection,
                )
                // The seam a migrated surface asserts and the tests compare
                // against its host: this node is the PANEL COLUMN, not the box
                // around it, so a centring check measures the content.
                .testTag("empty_state_panel"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // An empty state is transient: the title is a polite live-region,
            // exactly like [EmptyState] (v1.4 C4, ADR-0033).
            Text(
                text = title,
                style = PaperInkType.EmptyTitle,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (stateDescription != null) this.stateDescription = stateDescription
                }
            )
            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(PaperInkDimens.SpaceSm))
                Text(
                    text = body,
                    style = PaperInkType.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (action != null) {
                Spacer(modifier = Modifier.height(PaperInkDimens.SpaceXl))
                action()
            }
            if (quietAction != null) {
                Spacer(modifier = Modifier.height(PaperInkDimens.SpaceSm))
                quietAction()
            }
        }
    }
}

/**
 * #1775 (unslop audit U1) — THE primary action of a paper surface.
 *
 * Before this primitive the same invitation («почати/переглянути») was drawn in
 * four shapes: a full pill hand-rolled out of a clickable `Row` + `CircleShape`
 * in the «Слухати» empty state, a 12 dp `Button` in «Медіатека», a 14 dp
 * `Button` in «Огляд», and 24 dp on the book page and in the player. The audit's
 * rule is one role, one form (§4 rule 1): a primary CTA is 24 dp, a chip is a
 * pill, and a pill is never an action button.
 *
 * This composable is the ONE place that form is named. It renders a filled M3
 * `Button` — [PaperInkDimens.PrimaryButtonHeight] tall, shaped by
 * [PaperInkDimens.RadiusLarge], filled with the paper roles
 * `colorScheme.primary` / `onPrimary` — so a surface that needs the CTA names
 * no radius, no height and no colour of its own. Callers pass the label, the
 * click and optionally one leading [PaperInkIconSizes.Inline] glyph (the
 * catalogue compass, the refresh arrow); the width follows the label, and the
 * panel centres it.
 *
 * [heightIn] rather than a fixed `height`: at a large font scale the label must
 * grow the button instead of being clipped out of it. At the default scale the
 * measured height is exactly 52 dp.
 *
 * [testTag] follows the house convention for optional seams — the node exists
 * only when a caller asks for one, so a migrated surface keeps the tag its
 * tests already know (`library_empty_import`, `catalog_empty_refresh`).
 */
@Composable
fun EmptyStatePrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    testTag: String? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = PaperInkDimens.PrimaryButtonHeight)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = RoundedCornerShape(PaperInkDimens.RadiusLarge),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                // Decorative: the label is the action's name.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(PaperInkIconSizes.Inline),
            )
            Spacer(modifier = Modifier.width(PaperInkDimens.SpaceSm))
        }
        Text(
            text = text,
            style = PaperInkType.BodyStrong,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * #1775 (unslop audit U1) — the quiet second path of a paper surface.
 *
 * The same role was a `TextButton` on three surfaces and a bare clickable
 * `Text` before #1766; its shape came from whichever theme was ambient. It is
 * defined here so the secondary role has ONE radius too —
 * [PaperInkDimens.RadiusMedium] — and the project's 48 dp target floor
 * ([PaperInkDimens.TouchTarget]), rather than each call site re-deciding what a
 * "quiet link" is.
 *
 * The container stays transparent (that is what makes it quiet); the shape is
 * still named, because the press ripple and any future state layer are bounded
 * by it, and because a token at the call site would be a second place to
 * change the form.
 */
@Composable
fun EmptyStateQuietAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = PaperInkDimens.TouchTarget)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = RoundedCornerShape(PaperInkDimens.RadiusMedium),
    ) {
        Text(text = text, style = PaperInkType.BodyStrong)
    }
}

/** Compact canonical empty state for list sub-sections: 40 dp icon, title, one-line body, optional trailing action. Announces itself politely like [EmptyState]. */
@Composable
fun EmptyStateRow(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
        Column(modifier = Modifier.weight(1f)) {
            // v1.4 C4 (ADR-0033): the compact state announces itself exactly
            // like the full one — an empty state is transient, so it is a
            // polite live-region wherever it appears.
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (action != null) action()
    }
}

/**
 * #885 (wave 3) — the empty-state glyph size, from the prototype's
 * `.sl-empty svg` (`:1215-1217`). One constant, because there is one empty
 * state app-wide (ADR-0033) and a second size would be a second shape.
 */
private val EmptyStateIconSize = 35.dp
