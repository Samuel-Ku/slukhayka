// @vitest-environment jsdom
/**
 * #582 W0.4 — the three person-bookmark surfaces, honestly: the «Ваші
 * виконавці/автори» block in Медіатека renders bookmarked people on
 * canonical BookRows with a working remove toggle (absent when empty);
 * the BookPage byline carries Android's one-toggle-per-person control;
 * the Виконавці/Автори index rows bookmark through the same controller —
 * the web's in-app person surface (person pages themselves are external
 * site pages). Without the bookmark seam there are no buttons at all —
 * never dead UI.
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DomainStore } from '../local/domain'
import { personIdentityOf } from '../local/personIdentity'
import { setUiLocale } from '../i18n/locale'
import { api } from '../api/client'
import { PeopleIndexPanel } from './catalogIndexes'
import { Library } from './Library'
import { BookPage } from './BookPage'
import type { BookDetail, ParsedCatalog } from '../worker/types'
import { PersonBookmarkSyncController, LocalPendingPersonBookmarkDeletes } from '../sync/personBookmarkController'
import type { PersonBookmarkSyncController as PersonBookmarkSyncControllerType } from '../sync/personBookmarkController'
import { EditionLinkStore } from '../local/editionLinks'
import type { LocalListeningStateSnapshot } from '../player/localState'
import type { ListenerDatabase } from '../local/listeningState'

class MemoryListeningStore implements Pick<ListenerDatabase, 'allSnapshots' | 'clearSnapshot'> {
  private rows = new Map<string, LocalListeningStateSnapshot>()
  async allSnapshots(): Promise<LocalListeningStateSnapshot[]> {
    return [...this.rows.values()]
  }
  async clearSnapshot(_editionId: string): Promise<void> {
    this.rows.delete(_editionId)
  }
}

function makeController(domain: DomainStore): PersonBookmarkSyncControllerType {
  return new PersonBookmarkSyncController(
    () => null,
    domain,
    null,
    () => true,
    new LocalPendingPersonBookmarkDeletes({
      getItem: () => null,
      setItem: () => undefined,
    }),
  )
}

function detailOf(overrides: Partial<BookDetail> = {}): BookDetail {
  return {
    url: 'https://x/book',
    title: 'Кобзар',
    author: 'Тарас Шевченко',
    narrator: 'Читець Оксана',
    genres: [],
    chapters: [{ title: 'Розділ 1', streamUrl: 'https://x/1.mp3', durationSeconds: 60 }],
    otherNarrations: [],
    relatedBooks: [],
    ...overrides,
  }
}

const peopleCatalog: ParsedCatalog = {
  sections: [
    {
      id: 'people',
      title: 'Виконавці',
      cards: [
        { url: 'https://x/readers.html#1', title: 'Читець Оксана', author: '', count: 12 },
        { url: 'https://x/readers.html#2', title: 'Інший Читець', author: '', count: 3 },
      ],
    },
  ],
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('Медіатека — the «Ваші виконавці/автори» block', () => {
  it('renders bookmarked people on canonical BookRows and removes on ★', async () => {
    const domain = new DomainStore()
    const controller = makeController(domain)
    const id = personIdentityOf('narrator', 'Читець Оксана').id
    await domain.addPersonBookmark({ role: 'narrator', personId: id, displayName: 'Читець Оксана' })
    render(
      <Library
        domainStore={domain}
        linkStore={new EditionLinkStore()}
        listening={new MemoryListeningStore()}
        pushAfterChange={vi.fn()}
        personBookmarks={controller}
      />,
    )
    await screen.findByRole('heading', { name: /Ваші виконавці\/автори/ })
    expect(screen.getByText('Читець Оксана')).toBeTruthy()
    expect(screen.getByText('Виконавець')).toBeTruthy()
    // ★ removes the bookmark — the block disappears with its last person.
    await userEvent.click(screen.getByRole('button', { name: 'Прибрати з закладок: Читець Оксана' }))
    await waitFor(() => expect(screen.queryByText('Читець Оксана')).toBeNull())
    expect(await domain.personBookmarks()).toHaveLength(0)
  })

  it('renders no block when nothing is bookmarked', async () => {
    render(
      <Library
        domainStore={new DomainStore()}
        linkStore={new EditionLinkStore()}
        listening={new MemoryListeningStore()}
        pushAfterChange={vi.fn()}
        personBookmarks={makeController(new DomainStore())}
      />,
    )
    await screen.findByText('Медіатека порожня')
    expect(screen.queryByRole('heading', { name: /Ваші виконавці\/автори/ })).toBeNull()
  })
})

describe('BookPage — the byline bookmark controls', () => {
  it('toggles author and narrator bookmarks from the byline', async () => {
    const domain = new DomainStore()
    const controller = makeController(domain)
    vi.spyOn(api, 'book').mockResolvedValue(detailOf())
    render(
      <BookPage
        url="https://x/book"
        source="sluhayua"
        onOpenBook={vi.fn()}
        profile={null}
        reviewsStore={null}
        narrationRatingsStore={null}
        domainStore={domain}
        personBookmarks={controller}
      />,
    )
    const authorButton = await screen.findByRole('button', { name: 'Закласти Тарас Шевченко' })
    const narratorButton = screen.getByRole('button', { name: 'Закласти Читець Оксана' })
    expect(authorButton).toBeTruthy()
    expect(narratorButton).toBeTruthy()
    await userEvent.click(authorButton)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Прибрати з закладок: Тарас Шевченко' })).toBeTruthy())
    expect(await domain.personBookmarks('author')).toHaveLength(1)
    // The narrator button stays untouched.
    expect(screen.getByRole('button', { name: 'Закласти Читець Оксана' })).toBeTruthy()
  })

  it('renders no bookmark buttons without the seam', async () => {
    vi.spyOn(api, 'book').mockResolvedValue(detailOf())
    render(
      <BookPage
        url="https://x/book"
        source="sluhayua"
        onOpenBook={vi.fn()}
        profile={null}
        reviewsStore={null}
        narrationRatingsStore={null}
      />,
    )
    await screen.findByText('Кобзар')
    expect(screen.queryByRole('button', { name: /Закласти/ })).toBeNull()
  })
})

describe('Виконавці index — the row bookmark toggle', () => {
  it('bookmarks a person from the row and reflects the state', async () => {
    const domain = new DomainStore()
    const controller = makeController(domain)
    vi.spyOn(api, 'catalog').mockImplementation(async (_source, url) => (url === undefined ? null : peopleCatalog))
    render(
      <PeopleIndexPanel
        kind="performers"
        onBack={vi.fn()}
        onOpenPerson={vi.fn()}
        domainStore={domain}
        personBookmarks={controller}
      />,
    )
    const addButton = await screen.findByRole('button', { name: 'Закласти Читець Оксана' })
    await userEvent.click(addButton)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Прибрати з закладок: Читець Оксана' })).toBeTruthy())
    expect(await domain.personBookmarks('narrator')).toHaveLength(1)
    // The other row is unaffected.
    expect(screen.getByRole('button', { name: 'Закласти Інший Читець' })).toBeTruthy()
  })
})