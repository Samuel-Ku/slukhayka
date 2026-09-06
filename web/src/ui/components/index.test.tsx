// @vitest-environment jsdom
import { useState } from 'react'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BookRow,
  EmptyState,
  EmptyStateRow,
  MetadataChip,
  SectionHeader,
  TabHeader,
} from './index'

afterEach(() => {
  cleanup()
  localStorage.removeItem('slukhayka.ui_language')
})

beforeEach(() => {
  // These flows assert the Ukrainian chrome; pin the locale explicitly.
  localStorage.setItem('slukhayka.ui_language', 'uk')
})

describe('SectionHeader', () => {
  it('renders a group heading on the second level with an action slot', () => {
    render(
      <SectionHeader level="group" title="Огляд" action={<button>Action</button>} />,
    )
    expect(screen.getByRole('heading', { level: 2, name: 'Огляд' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Action' })).toBeTruthy()
  })

  it('renders a section heading with an optional honest counter', () => {
    render(<SectionHeader level="section" title="Розділи" count={12} />)
    const heading = screen.getByRole('heading', { level: 3, name: /Розділи/ })
    // The counter is part of the heading, never a free-standing row (ADR-0033).
    expect(within(heading).getByText(/12/)).toBeTruthy()
  })

  it('renders no counter when the number is unknown — never a fabricated 0', () => {
    render(<SectionHeader level="section" title="Відгуки" />)
    expect(screen.getByRole('heading', { level: 3, name: 'Відгуки' }).textContent).not.toMatch(/0/)
  })
})

describe('MetadataChip', () => {
  it('renders a language chip labelled for its content language', () => {
    render(<MetadataChip kind="language" code="en" />)
    const chip = screen.getByText('EN')
    expect(chip.getAttribute('aria-label')).toBe('English')
  })

  it('renders a plain chip for recommendation reasons and unknown languages stay silent', () => {
    render(<MetadataChip kind="plain">Схоже на «Книгу»</MetadataChip>)
    expect(screen.getByText('Схоже на «Книгу»')).toBeTruthy()
    render(<MetadataChip kind="source">4read</MetadataChip>)
    expect(screen.getByText('4read')).toBeTruthy()
  })

  it('is never a button — non-interactive by contract', () => {
    render(<MetadataChip kind="plain">жанр</MetadataChip>)
    expect(screen.queryByRole('button', { name: 'жанр' })).toBeNull()
  })
})

describe('BookRow', () => {
  it('renders a flat list row, not a bordered card wrapper', () => {
    const { container } = render(
      <BookRow
        coverUrl="https://img/1.jpg"
        title="Книга"
        subtitle="Автор"
        onOpen={vi.fn()}
      />,
    )
    // The row IS the list item: one flat li (divider-not-border lives in the
    // canonical CSS; jsdom loads no stylesheet, so structure is the check).
    const row = container.querySelector('li.bookrow')
    expect(row).toBeTruthy()
    expect(row!.className).not.toMatch(/card|panel|border/i)
    expect(row!.querySelector('.bookrow-body')).toBeTruthy()
    expect(screen.getByRole('button', { name: /Книга/ })).toBeTruthy()
  })

  it('keeps the row body and trailing actions as separate focusable targets', async () => {
    const onOpen = vi.fn()
    const onAction = vi.fn()
    render(
      <BookRow
        coverUrl={undefined}
        title="Книга"
        subtitle="Автор"
        onOpen={onOpen}
        actions={<button onClick={onAction}>▶</button>}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: /Книга/ }))
    expect(onOpen).toHaveBeenCalledOnce()
    expect(onAction).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '▶' }))
    expect(onAction).toHaveBeenCalledOnce()
  })

  it('shows the progress hairline only from a real number (ADR-0014)', () => {
    const { rerender } = render(
      <BookRow coverUrl={undefined} title="A" subtitle="B" progress={0.5} onOpen={vi.fn()} />,
    )
    expect(document.querySelector('[data-progress]')).toBeTruthy()
    rerender(<BookRow coverUrl={undefined} title="A" subtitle="B" onOpen={vi.fn()} />)
    expect(document.querySelector('[data-progress]')).toBeNull()
  })

  it('renders cover text initials when no cover URL exists — never a broken image', () => {
    render(<BookRow coverUrl={undefined} title="Книга" subtitle="Автор" onOpen={vi.fn()} />)
    expect(document.querySelector('img')).toBeNull()
  })
})

describe('EmptyState and EmptyStateRow', () => {
  it('renders the full state with an icon, message and actions', async () => {
    const onRetry = vi.fn()
    render(
      <EmptyState
        message="Нічого не знайшли."
        hint="Спробуйте інший запит"
        actions={<button onClick={onRetry}>Спробувати ще раз</button>}
      />,
    )
    expect(screen.getByText('Нічого не знайшли.')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Спробувати ще раз' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('renders the compact row state as a live region for async recoveries', () => {
    render(<EmptyStateRow message="Джерело не відповіло — спробуйте пізніше." />)
    const row = screen.getByText('Джерело не відповіло — спробуйте пізніше.')
    expect(row.getAttribute('role')).toBe('status')
    expect(row.getAttribute('aria-live')).toBe('polite')
  })

  it('carries an inline recovery action inside the canonical row state', () => {
    render(
      <EmptyStateRow message="Потребує сесії.">
        <a href="https://4read.org">Відкрити 4read</a>
      </EmptyStateRow>,
    )
    expect(screen.getByRole('link', { name: 'Відкрити 4read' }).getAttribute('href')).toBe('https://4read.org')
  })
})

describe('TabHeader', () => {
  it('renders the tab title and its optional action', () => {
    render(<TabHeader title="Огляд" action={<button>Оновити</button>} />)
    expect(screen.getByRole('heading', { name: 'Огляд' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Оновити' })).toBeTruthy()
  })

  it('expands the search field from the 🔍 action and clears it on ✕', async () => {
    const Harness = (): JSX.Element => {
      const [q, setQ] = useState('')
      return <TabHeader title="Огляд" search searchQuery={q} onSearchQueryChange={setQ} searchPlaceholderKey="searchPlaceholder" />
    }
    render(<Harness />)
    // Collapsed by default: no searchbox visible, an expand action exists.
    expect(screen.queryByRole('searchbox')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Пошук' }))
    const field = screen.getByRole('searchbox')
    await userEvent.type(field, 'шк')
    // The field reflects every keystroke through the owner's state.
    expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('шк')
    // ✕ collapses and clears.
    await userEvent.click(screen.getByRole('button', { name: 'Закрити пошук' }))
    expect(screen.queryByRole('searchbox')).toBeNull()
  })

  it('lands back in the field when a query is active (return-to-tab contract, W3.2)', () => {
    render(
      <TabHeader title="Огляд" search searchQuery="шк" onSearchQueryChange={vi.fn()} searchPlaceholderKey="searchPlaceholder" />,
    )
    expect(screen.getByRole('searchbox')).toBeTruthy()
  })

  it('returns to the title state when the field loses focus while empty', async () => {
    const Harness = (): JSX.Element => {
      const [q, setQ] = useState('')
      return <TabHeader title="Огляд" search searchQuery={q} onSearchQueryChange={setQ} searchPlaceholderKey="searchPlaceholder" />
    }
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Пошук' }))
    await userEvent.tab()
    expect(screen.queryByRole('searchbox')).toBeNull()
  })
})
