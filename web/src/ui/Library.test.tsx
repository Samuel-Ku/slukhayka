// @vitest-environment jsdom
/**
 * #584 W1.2 — the Медіатека screen: honest counters, the status trilogy,
 * canonical empty states, and the three-level deletion whose confirmation
 * quotes the exact scope and whose verdict writes a tombstone.
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DomainStore } from '../local/domain'
import { EditionLinkStore } from '../local/editionLinks'
import type { LocalListeningStateSnapshot } from '../player/localState'
import type { ListenerDatabase } from '../local/listeningState'
import { setUiLocale } from '../i18n/locale'
import { Library } from './Library'

class MemoryListeningStore implements Pick<ListenerDatabase, 'allSnapshots' | 'clearSnapshot'> {
  private rows = new Map<string, LocalListeningStateSnapshot>()
  constructor(initial: LocalListeningStateSnapshot[] = []) {
    for (const snapshot of initial) this.rows.set(snapshot.editionId, snapshot)
  }
  async allSnapshots(): Promise<LocalListeningStateSnapshot[]> {
    return [...this.rows.values()]
  }
  async clearSnapshot(editionId: string): Promise<void> {
    this.rows.delete(editionId)
  }
  has(editionId: string): boolean {
    return this.rows.has(editionId)
  }
}

/** The SectionHeader counter's real number (ADR-0014: or nothing). */
function counterOf(): string | null {
  return document.querySelector('.sec-head-count')?.textContent?.replace('·', '').trim() ?? null
}

beforeEach(() => {
  // A fresh factory per test: the listener DB must never leak rows between tests.
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
})

afterEach(() => {
  cleanup()
})

describe('Library', () => {
  it('renders an empty library with the canonical empty state', async () => {
    render(<Library
      domainStore={new DomainStore()}
      linkStore={new EditionLinkStore()}
      listening={new MemoryListeningStore()}
      pushAfterChange={vi.fn()}
    />)
    expect(await screen.findByText('Медіатека порожня')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: 'Медіатека' })).toBeTruthy()
  })

  it('lists entries with honest counters, hairlines and no fabricated numbers', async () => {
    const domain = new DomainStore()
    const links = new EditionLinkStore()
    await domain.addLibraryEntry({ title: 'Трохи ненависті', author: 'Артем Чех' })
    await domain.addLibraryEntry({ title: 'Кобзар', author: 'Тарас Шевченко' })
    await links.link({
      editionId: 'ed-hate', mergeKey: 'трохи ненависті|артем чех', narrator: 'Диктор',
      language: 'uk', durationSeconds: 200, chapterDurations: [100, 100],
    })
    render(<Library
      domainStore={domain}
      linkStore={links}
      listening={new MemoryListeningStore([
        { editionId: 'ed-hate', chapterIndex: 1, positionSeconds: 50, isCompleted: false, preferredSpeed: null, lastPausedAtEpochMs: 900 },
      ])}
      pushAfterChange={vi.fn()}
    />)

    expect(await screen.findByText('Трохи ненависті')).toBeTruthy()
    expect(screen.getByText('Кобзар')).toBeTruthy()
    // The header counter is the real number of visible rows (ADR-0014).
    expect(counterOf()).toBe('2')
    // A real fraction renders as data; the untouched Work renders no hairline.
    expect(screen.getByText(/Трохи ненависті/).closest('li')!.innerHTML).toContain('bookrow-progress')
    expect(screen.getByText(/Кобзар/).closest('li')!.innerHTML).not.toContain('bookrow-progress')
  })

  it('filters by the status trilogy and keeps the counter live', async () => {
    const user = userEvent.setup()
    const domain = new DomainStore()
    const links = new EditionLinkStore()
    await domain.addLibraryEntry({ title: 'Слухана', author: 'А' })
    await domain.addLibraryEntry({ title: 'Нова', author: 'Б' })
    await links.link({ editionId: 'ed1', mergeKey: 'слухана|а', narrator: '', language: '', durationSeconds: null, chapterDurations: null })
    render(<Library
      domainStore={domain}
      linkStore={links}
      listening={new MemoryListeningStore([
        { editionId: 'ed1', chapterIndex: 0, positionSeconds: 10, isCompleted: false, preferredSpeed: null, lastPausedAtEpochMs: 5 },
      ])}
      pushAfterChange={vi.fn()}
    />)
    expect(await screen.findByText('Слухана')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Нові' }))
    expect(screen.queryByText('Слухана')).toBeNull()
    expect(screen.getByText('Нова')).toBeTruthy()
    expect(counterOf()).toBe('1')

    await user.click(screen.getByRole('button', { name: 'Слухаю' }))
    expect(screen.getByText('Слухана')).toBeTruthy()
    expect(screen.queryByText('Нова')).toBeNull()
  })

  it('an empty filter shows the honest empty row, not the empty-library state', async () => {
    const user = userEvent.setup()
    const domain = new DomainStore()
    await domain.addLibraryEntry({ title: 'Книга', author: 'А' })
    render(<Library
      domainStore={domain}
      linkStore={new EditionLinkStore()}
      listening={new MemoryListeningStore()}
      pushAfterChange={vi.fn()}
    />)
    expect(await screen.findByText('Книга')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Завершені' }))
    expect(screen.getByText('У цьому фільтрі нічого.')).toBeTruthy()
  })

  it('deletes through three levels: ⋮ → red item → scope confirmation → tombstone', async () => {
    const user = userEvent.setup()
    const domain = new DomainStore()
    const links = new EditionLinkStore()
    const listening = new MemoryListeningStore([
      { editionId: 'ed-del', chapterIndex: 0, positionSeconds: 10, isCompleted: false, preferredSpeed: null, lastPausedAtEpochMs: 7 },
    ])
    const push = vi.fn(async () => undefined)
    await domain.addLibraryEntry({ title: 'Заяць', author: 'Михайло Коцюбинський' })
    await links.link({ editionId: 'ed-del', mergeKey: 'заяць|михайло коцюбинський', narrator: '', language: '', durationSeconds: null, chapterDurations: null })

    render(<Library domainStore={domain} linkStore={links} listening={listening} pushAfterChange={push} />)
    expect(await screen.findByText('Заяць')).toBeTruthy()

    // Level 1: the row's ⋮ action (rare actions live in secondary menus).
    await user.click(screen.getByRole('button', { name: 'Дії з книгою: Заяць' }))
    // Level 2: the red, explicitly-labelled menu item.
    const menuItem = screen.getByRole('menuitem', { name: 'Видалити з Медіатеки…' })
    expect(menuItem.className).toContain('danger')
    await user.click(menuItem)
    // Level 3: the confirmation quotes the exact scope (count + what is wiped).
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    expect(screen.getByText(/Точна сфера: 1 книга «Заяць»/)).toBeTruthy()
    expect(screen.getByText(/Позицій прослуховування: 1/)).toBeTruthy()
    expect(screen.getByText(/Завантажених файлів немає/)).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Видалити' }))

    await waitFor(() => expect(screen.queryByText('Заяць')).toBeNull())
    const relationship = await domain.relationshipOf('заяць|михайло коцюбинський')
    expect(relationship?.state).toBe('tombstone')
    expect(listening.has('ed-del')).toBe(false)
    expect(await links.linksFor('заяць|михайло коцюбинський')).toEqual([])
    expect(push).toHaveBeenCalledWith('заяць|михайло коцюбинський')
    expect(await screen.findByText(/«Заяць» видалено з Медіатеки\./)).toBeTruthy()
  })

  it('cancelling the confirmation deletes nothing and returns focus to the row', async () => {
    const user = userEvent.setup()
    const domain = new DomainStore()
    const push = vi.fn(async () => undefined)
    await domain.addLibraryEntry({ title: 'Лісова пісня', author: 'Леся Українка' })
    render(<Library
      domainStore={domain}
      linkStore={new EditionLinkStore()}
      listening={new MemoryListeningStore()}
      pushAfterChange={push}
    />)
    expect(await screen.findByText('Лісова пісня')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Дії з книгою: Лісова пісня' }))
    await user.click(screen.getByRole('menuitem', { name: 'Видалити з Медіатеки…' }))
    // The dialog took focus when it opened.
    expect(document.activeElement?.getAttribute('name') ?? document.activeElement?.textContent).toContain('Видалити')
    await user.click(screen.getByRole('button', { name: 'Скасувати' }))

    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.getByText('Лісова пісня')).toBeTruthy()
    // The focus-return contract: back to the ⋮ row that opened the dialog.
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Дії з книгою: Лісова пісня')
    const relationship = await domain.relationshipOf('лісова пісня|леся українка')
    expect(relationship?.state).toBe('entry')
    expect(push).not.toHaveBeenCalled()
  })
})
