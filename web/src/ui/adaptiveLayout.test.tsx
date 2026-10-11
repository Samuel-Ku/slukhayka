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
 *   expanded block carries the numbers the layout is built from (the list
 *   column, the gutter, and the cap as arithmetic over both), all four list
 *   kinds become auto-fill columns, a BookRow grid draws its closing hairline,
 *   and the rails wrap. The old fixed 640 px column and any media query — the
 *   second spelling of the boundary — cannot come back unnoticed. The visual
 *   delta itself was verified in a real browser against the built bundle,
 *   which cannot run in this job (CI's web job is `npm ci` + `tsc --noEmit` +
 *   `vitest run`).
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
  const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'theme.css'), 'utf8')
  // Rules only: a comment must never be able to satisfy a pin (the seam block
  // above, for one, talks ABOUT `@media` while the sheet has none).
  const css = source.replace(/\/\*[\s\S]*?\*\//g, '')

  /** Every `selectors { declarations }` rule of the sheet, in file order. */
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((match) => ({
    selectors: match[1]!.trim(),
    declarations: match[2]!.trim(),
  }))
  const wide = rules.filter((rule) => rule.selectors.includes(".app[data-layout='expanded']"))
  /** The token block: the only wide rule whose selector is the attribute alone. */
  const numbers = wide.find((rule) => rule.selectors.endsWith(".app[data-layout='expanded']"))?.declarations ?? ''
  /** A BookRow list of real rows that is safe for a grid — the long selector. */
  const bookRowList = ".app[data-layout='expanded'] .surface .card-list:not(:has(> :not(.bookrow))):not(:empty)"

  it('takes the app column width from the token, never from a literal', () => {
    expect(css).toContain('max-width: var(--layout-max-width)')
    // The old fixed column: `#root { … max-width: 640px }` must not return.
    expect(css).not.toMatch(/#root\s*\{[^}]*max-width/)
  })

  it('keeps every number the wide layout reads in the expanded block', () => {
    expect(numbers).toContain('--list-column-min: 320px')
    expect(numbers).toContain('--list-column-gap: 16px')
    expect(numbers).toContain('--layout-gutter: 24px')
    // The cap is three columns plus their gaps and gutters, not a literal that
    // could drift from the grid it holds (the lesson of #1206, one layer up).
    const cap = /--layout-max-width:\s*calc\(([^;]*)\)/.exec(numbers)?.[1] ?? ''
    for (const token of ['--list-column-min', '--list-column-gap', '--layout-gutter']) {
      expect(cap).toContain(`var(${token})`)
    }
  })

  it('turns all four list kinds into auto-fill columns of the column token', () => {
    const grid = wide.find((rule) => rule.declarations.includes('grid-template-columns'))
    expect(grid).toBeDefined()
    for (const selector of [
      bookRowList,
      ".app[data-layout='expanded'] .surface .narrations",
      ".app[data-layout='expanded'] .surface .chapters",
      ".app[data-layout='expanded'] .surface .collection-list",
    ]) {
      expect(grid?.selectors).toContain(selector)
    }
    expect(grid?.declarations).toContain(
      'grid-template-columns: repeat(auto-fill, minmax(var(--list-column-min), 1fr))',
    )
    expect(grid?.declarations).toContain('column-gap: var(--list-column-gap)')
  })

  it('draws the closing hairline of a BookRow grid, and only over rows that exist', () => {
    const hairline = wide.find((rule) => rule.declarations.includes('row-gap: 0'))
    expect(hairline?.selectors).toContain(bookRowList)
    expect(hairline?.declarations).toContain('border-top: 1px solid var(--line)')
    // …and the line `:first-child` draws alone while there is one column gives
    // way to the grid's own top edge.
    const firstRow = wide.find((rule) => rule.selectors.includes('> .bookrow:first-child'))
    expect(firstRow?.declarations).toContain('border-top: none')
  })

  it('lets the rails wrap instead of hiding cards behind a scroll', () => {
    const rails = wide.find((rule) => rule.declarations.includes('flex-wrap: wrap'))
    for (const selector of [
      ".app[data-layout='expanded'] .surface .shelf-rail",
      ".app[data-layout='expanded'] .surface .poster-row",
      ".app[data-layout='expanded'] .surface .collection-rail-row",
    ]) {
      expect(rails?.selectors).toContain(selector)
    }
    expect(rails?.declarations).toContain('overflow: visible')
  })

  it('keeps the boundary out of the stylesheet, where it would exist twice', () => {
    // No media query at all: the file has none, and any width key here would
    // be the second spelling of the 600 px line `adaptiveLayout.ts` owns.
    expect(css).not.toMatch(/@media/)
    expect(css).not.toContain('600px')
  })
})
