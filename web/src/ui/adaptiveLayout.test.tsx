// @vitest-environment jsdom
/**
 * #1207 — the web's adaptive line is 600 px, and it is a test on both sides.
 *
 * The seam has two halves and they are pinned differently, because vitest can
 * only honestly hold one of them:
 *
 * - WHICH layout is on is decided in `adaptiveLayout.ts`, and that half is
 *   behavioural: the pure rule is read at 599/600, and the app root is rendered
 *   and watched flipping across the line on a real `resize`. jsdom runs no CSS
 *   layout, so nothing here claims to measure a column.
 * - What `expanded` MEANS is `theme.css`, and its half is pinned for what a
 *   text check can hold: the app column takes its width from the token, the
 *   expanded block is spelled once, and neither a literal 640 px column nor a
 *   width-keyed media query — the second copy of the number — can come back
 *   unnoticed. The visual delta itself was verified in a real browser against
 *   the built bundle, which cannot run in this job (CI's web job is `npm ci` +
 *   `tsc --noEmit` + `vitest run`).
 */
import 'fake-indexeddb/auto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { App } from '../App'
import type { ListenerProfile } from '../identity/listenerIdentity'
import { setUiLocale } from '../i18n/locale'
import { EXPANDED_MIN_WIDTH_PX, layoutForWidth } from './adaptiveLayout'

const profile: ListenerProfile = { uid: 'local-abc123', nickname: 'Слухач-1' }

/** The window moves the way a browser moves it: the width, then `resize`. */
function setViewportWidth(widthPx: number): void {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: widthPx })
  act(() => {
    window.dispatchEvent(new Event('resize'))
  })
}

function appLayout(container: HTMLElement): string | null {
  return container.querySelector('.app')?.getAttribute('data-layout') ?? null
}

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  setUiLocale('uk')
})

describe('#1207 the adaptive line', () => {
  it('is Android’s ExpandedMinWidthDp, not a web-only breakpoint', () => {
    // app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt
    // draws the one line both clients share (ADR-0034); moving it here alone
    // would be the divergence the shared map exists to prevent.
    expect(EXPANDED_MIN_WIDTH_PX).toBe(600)
  })

  it('calls 599 px a phone and 600 px not one', () => {
    // The sides Android pins in WindowLayoutTest, at the same numbers.
    for (const phone of [0, 320, 360, 411, 480, 599]) {
      expect(layoutForWidth(phone)).toBe('compact')
    }
    for (const wide of [600, 601, 840, 1024, 1440, 2560]) {
      expect(layoutForWidth(wide)).toBe('expanded')
    }
  })

  it('writes the answer on the app root and flips it live across the line', () => {
    setUiLocale('uk')
    // A quiet tab: this test is about the root's layout attribute, not about
    // what the Огляд fetches while it renders.
    window.localStorage.setItem('slukhayka.selected_tab', 'settings')

    setViewportWidth(EXPANDED_MIN_WIDTH_PX - 1)
    const { container } = render(<App profile={profile} />)
    expect(appLayout(container)).toBe('compact')

    setViewportWidth(EXPANDED_MIN_WIDTH_PX)
    expect(appLayout(container)).toBe('expanded')

    // Back across the line: a window that shrinks again is a phone again.
    setViewportWidth(EXPANDED_MIN_WIDTH_PX - 1)
    expect(appLayout(container)).toBe('compact')
  })
})

describe('#1207 theme.css half of the seam', () => {
  // `fileURLToPath(import.meta.url)`, not `new URL(…, import.meta.url)`: under
  // the jsdom environment the global `URL` resolves a relative path against
  // the document's http://localhost origin instead of the module's file URL.
  const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'theme.css'), 'utf8')

  it('takes the app column width from the token, never from a literal', () => {
    expect(css).toContain('max-width: var(--layout-max-width)')
    // The old fixed column: `#root { … max-width: 640px }` must not return.
    expect(css).not.toMatch(/#root\s*\{[^}]*max-width/)
  })

  it('spells the expanded layout under the one attribute the module writes', () => {
    expect(css).toContain(".app[data-layout='expanded']")
  })

  it('keeps the boundary out of the stylesheet, where it would exist twice', () => {
    expect(css).not.toMatch(/@media[^{]*min-width/)
    expect(css).not.toContain('600px')
  })
})
