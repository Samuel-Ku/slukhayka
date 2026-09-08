// @vitest-environment jsdom
/**
 * #584 W1.3 — the deletion loop's other half: a tombstoned Work never
 * re-enters Огляд — not in the feed, not in search, whatever the catalog
 * refresh brings back.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { api } from '../api/client'
import { DomainStore } from '../local/domain'
import { setUiLocale } from '../i18n/locale'
import type { UnifiedWorkPage } from '../worker/types'
import { Catalog } from './Catalog'

const page: UnifiedWorkPage = {
  works: [
    {
      id: 'w1',
      mergeKey: 'заяць|михайло коцюбинський',
      title: 'Заяць',
      author: 'Михайло Коцюбинський',
      editions: [{ id: 'e1', sources: [{ sourceId: 'sound-books', url: 'https://s/zayats' }] }],
    },
    {
      id: 'w2',
      mergeKey: 'лісова пісня|леся українка',
      title: 'Лісова пісня',
      author: 'Леся Українка',
      editions: [{ id: 'e2', sources: [{ sourceId: 'sound-books', url: 'https://s/lisova' }] }],
    },
  ],
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  vi.spyOn(api, 'workFeed').mockResolvedValue(page)
  vi.spyOn(api, 'workSearch').mockResolvedValue(page)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('Catalog tombstone filter', () => {
  it('hides a tombstoned Work from the feed and keeps the counter honest', async () => {
    const domain = new DomainStore()
    await domain.tombstoneWork('заяць|михайло коцюбинський')
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} domainStore={domain} />)

    await waitFor(() => expect(screen.getByText('Лісова пісня')).toBeTruthy())
    await waitFor(() => expect(screen.queryByText('Заяць')).toBeNull())
    // The counter is the truth after filtering (ADR-0014): 1, not 2.
    expect(document.querySelector('.sec-head-count')?.textContent?.replace('·', '').trim()).toBe('1')
  })

  it('hides a tombstoned Work from search results too', async () => {
    const domain = new DomainStore()
    await domain.tombstoneWork('заяць|михайло коцюбинський')
    const user = (await import('@testing-library/user-event')).default.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} domainStore={domain} />)
    await waitFor(() => expect(screen.queryByText('Лісова пісня')).toBeTruthy())

    await user.click(screen.getByRole('button', { name: 'Пошук' }))
    await user.type(screen.getByRole('searchbox'), 'Заяць')
    // The search debounce must settle before the results (and their filter) exist.
    await waitFor(() => expect(screen.getByText('Лісова пісня')).toBeTruthy(), { timeout: 3000 })
    expect(screen.queryByText('Заяць')).toBeNull()
  })

  it('without a domain store the catalog renders everything (back-compat)', async () => {
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByText('Заяць')).toBeTruthy())
    expect(screen.getByText('Лісова пісня')).toBeTruthy()
  })
})
