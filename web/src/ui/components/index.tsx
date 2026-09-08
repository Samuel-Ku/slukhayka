/**
 * #579 W0.1 — the canonical component vocabulary of v1.4 (ADR-0033/0034)
 * as the web's ONLY card/chip/header/state components. New screens build
 * on these; a divergent web variant is a defect exactly like Android's
 * duplicate implementations were. PosterCard/CycleCard join with their
 * first consumers (the Огляд shelves, W3.1) — never as test-only parts.
 *
 * Contracts ported from the Android baseline:
 * - BookRow: a flat list row (64px cover, progress hairline, divider not
 *   border); the body opens the Work; trailing actions are separate
 *   focusable targets; a hairline renders only from a real number (ADR-0014).
 * - MetadataChip: non-interactive (never a button), muted tone; language
 *   chips reuse the T7 badge contract (unknown language = absent, never
 *   guessed — spec-45).
 * - SectionHeader: two levels (group/section) with an optional action slot
 *   and an optional counter INSIDE the heading — no free-standing counter
 *   rows; absent numbers render nothing (ADR-0014).
 * - EmptyState / EmptyStateRow: the two canonical states; the compact row
 *   is a polite live region.
 * - TabHeader: one header model (title + optional action) with the
 *   collapsible search pattern (🔍 expands, ✕/blur-empty clears and
 *   collapses).
 */
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { badgeLabel } from '../contentLanguagePrefs'
import { useTranslate } from '../../i18n/locale'
import type { StringKey } from '../../i18n/strings'

// --- SectionHeader ---------------------------------------------------------

export function SectionHeader({ level, title, count, action, hint }: {
  level: 'group' | 'section'
  title: string
  /** Real counts only; `undefined` renders nothing — never a fabricated 0 (ADR-0014). */
  count?: number
  action?: ReactNode
  hint?: string
}) {
  const Tag = level === 'group' ? ('h2' as const) : ('h3' as const)
  return (
    <div className={`sec-head sec-head-${level}`}>
      <Tag className="sec-head-title">
        {title}
        {count !== undefined && <span className="sec-head-count"> · {count}</span>}
      </Tag>
      {hint && <span className="sec-head-hint">{hint}</span>}
      {action && <span className="sec-head-action">{action}</span>}
    </div>
  )
}

// --- MetadataChip ----------------------------------------------------------

export function MetadataChip({ kind, code, children }: {
  kind: 'language' | 'source' | 'plain'
  /** BCP-47 content language for kind="language" (the T7 badge contract). */
  code?: string
  children?: ReactNode
}) {
  if (kind === 'language') {
    const badge = badgeLabel(code)
    // Unknown language renders nothing — the honest absence (spec-45 T7).
    if (!badge) return null
    return (
      <span className="meta-chip meta-chip-lang" aria-label={badge.name}>{badge.label}</span>
    )
  }
  return <span className="meta-chip">{children}</span>
}

// --- BookRow ---------------------------------------------------------------

/** Initials fallback so a missing cover never renders a broken image. */
export function coverInitials(title: string): string {
  const words = title.trim().split(/\s+/).slice(0, 2)
  return words.map((word) => word.charAt(0).toUpperCase()).join('') || '·'
}

export function BookRow({ coverUrl, title, subtitle, progress, badges, actions, onOpen, openAriaLabel, trailing, innerRef }: {
  coverUrl?: string
  title: string
  subtitle?: string
  /** 0..1 from a REAL known value; undefined renders no hairline (ADR-0014). */
  progress?: number
  badges?: ReactNode
  actions?: ReactNode
  /** Opening the Work (row body); action buttons stay separate targets. */
  onOpen?: () => void
  /** The open action's own name (e.g. «Відкрити книгу: …») — never the raw text soup. */
  openAriaLabel?: string
  /** Extra content of the row (e.g. recovery notices under the row body). */
  trailing?: ReactNode
  /** Observer hook: the row's own li node (availability preflight). */
  innerRef?: (node: HTMLLIElement | null) => void
}) {
  const body = (
    <>
      {coverUrl
        ? <img className="bookrow-cover" src={coverUrl} alt="" loading="lazy" />
        : <span className="bookrow-cover bookrow-cover-fallback" aria-hidden="true">{coverInitials(title)}</span>}
      <span className="bookrow-main">
        <span className="bookrow-title">{title}</span>
        {subtitle && <span className="bookrow-subtitle">{subtitle}</span>}
        {badges && <span className="bookrow-badges">{badges}</span>}
        {progress !== undefined && (
          <span className="bookrow-progress" data-progress={progress.toFixed(3)} aria-hidden="true">
            <span className="bookrow-progress-fill" style={{ width: `${Math.min(Math.max(progress, 0), 1) * 100}%` }} />
          </span>
        )}
      </span>
    </>
  )
  return (
    <li className="bookrow" ref={innerRef}>
      {onOpen !== undefined ? (
        <button type="button" className="bookrow-body" onClick={onOpen} aria-label={openAriaLabel}>{body}</button>
      ) : (
        <span className="bookrow-body">{body}</span>
      )}
      {actions && <span className="bookrow-actions">{actions}</span>}
      {trailing}
    </li>
  )
}

// --- Canonical states --------------------------------------------------------

export function EmptyState({ icon, message, hint, actions }: {
  icon?: string
  message: string
  hint?: string
  actions?: ReactNode
}) {
  return (
    <div className="empty-state" role="status">
      {icon && <span className="empty-state-icon" aria-hidden="true">{icon}</span>}
      <p className="empty-state-message">{message}</p>
      {hint && <p className="empty-state-hint">{hint}</p>}
      {actions && <div className="empty-state-actions">{actions}</div>}
    </div>
  )
}

export function EmptyStateRow({ message, children }: { message: string; children?: ReactNode }) {
  return <p className="empty-state-row" role="status" aria-live="polite">{message}{children}</p>
}

// --- TabHeader ---------------------------------------------------------------

/**
 * The one tab-header model: title + optional action + the collapsible search
 * gesture (🔍 expands; ✕ clears+collapses; blur while empty collapses).
 */
export function TabHeader({ title, subtitle, action, search, searchPlaceholderKey = 'searchPlaceholder', searchQuery = '', onSearchQueryChange }: {
  title: string
  subtitle?: string
  action?: ReactNode
  search?: boolean
  searchPlaceholderKey?: StringKey
  /** Controlled query: the owner (screen) holds the search state. */
  searchQuery?: string
  onSearchQueryChange?: (query: string) => void
}) {
  const t = useTranslate()
  const [open, setOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement | null>(null)
  // The field is expanded while the user opened it OR a query exists (so a
  // return to the tab with an active search lands back in the field).
  const expanded = open || searchQuery.trim() !== ''

  useEffect(() => {
    if (expanded) inputRef.current?.focus()
  }, [expanded])

  const collapse = (): void => {
    setOpen(false)
    // ✕/Back collapses AND clears — the shared gesture (ADR-0033).
    if (searchQuery !== '') onSearchQueryChange?.('')
  }

  const headerStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
  }

  if (expanded) {
    return (
      <div className="tabheader tabheader-searching" style={headerStyle}>
        <input
          ref={inputRef}
          type="search"
          role="searchbox"
          className="tabheader-search-field"
          placeholder={t(searchPlaceholderKey)}
          value={searchQuery}
          onChange={(event) => onSearchQueryChange?.(event.target.value)}
          onBlur={() => { if (searchQuery === '') setOpen(false) }}
          onKeyDown={(event) => { if (event.key === 'Escape') collapse() }}
        />
        <button type="button" className="tabheader-btn" onClick={collapse} aria-label={t('closeSearchAria')}>✕</button>
      </div>
    )
  }

  return (
    <div className="tabheader" style={headerStyle}>
      <span>
        <h1 className="tabheader-title">{title}</h1>
        {subtitle && <p className="tabheader-subtitle">{subtitle}</p>}
      </span>
      <span className="tabheader-actions" style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
        {action}
        {search && (
          <button
            type="button"
            className="tabheader-btn"
            onClick={() => setOpen(true)}
            aria-label={t('searchAria')}
          >
            🔍
          </button>
        )}
      </span>
    </div>
  )
}
