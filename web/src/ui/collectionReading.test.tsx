// @vitest-environment jsdom
/**
 * spec-51 (#697, T9) — the web reading surfaces behave like Android's: the
 * book-page block, the Слухати rail and the collection screen read the SAME
 * documents through the store seam, show the shared ranking, draw stars only
 * when real votes exist, and render NOTHING at all without a store (honest
 * absence, never a fake empty community).
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { setUiLocale } from '../i18n/locale'
import { DomainStore } from '../local/domain'
import { EditionLinkStore } from '../local/editionLinks'
import { ListenPrefsStore } from '../local/listenPrefs'
import { RecommendationPrefsStore } from '../local/recommendationPrefs'
import { InMemoryNarrationRatingsStore, InMemoryReviewsStore } from '../reviews/store'
import { decodePublishedCollection, type PublishedCollection } from '../collections/collectionModel'
import { CollectionIdentity, CuratorIdentity } from '../collections/collectionIdentity'
import { InMemoryCollectionsStore, type CollectionsStore } from '../collections/store'
import { editionIdFor, mergeKeyFor } from '../sync/edition'
import { reviewWorkIdFor } from './bookReviews'
import { BookPage } from './BookPage'
import { Listen } from './Listen'

const BOOK_URL = 'https://4read.org/7611-neostannij-bij.html'

const detail = {
  url: BOOK_URL,
  title: 'Неостанній бій',
  author: 'Костянтин Шелест',
  narrator: 'Олександр Волох',
  language: 'uk',
  genres: ['Фентезі'],
  chapters: [{ title: 'Глава 1', streamUrl: 'https://4read.org/uploads/audio/1.mp3', durationSeconds: 600 }],
  otherNarrations: [],
  relatedBooks: [],
  series: undefined,
  rating: 4.7,
}

/** The workId the page computes — the identity a collection must carry. */
function bookWorkId(): string {
  const mergeKey = mergeKeyFor(detail.title, detail.author)
  const editionId = editionIdFor(mergeKey, BOOK_URL, detail.narrator, detail.language)
  return reviewWorkIdFor(mergeKey, editionId)
}

function collection(overrides: Record<string, unknown> = {}): PublishedCollection {
  const decoded = decodePublishedCollection({
    authorId: 'curator-1',
    collectionId: 'c1',
    pseudonym: 'Книголюб',
    title: 'Космос',
    description: 'Про зорі та планети',
    bookIds: [bookWorkId()],
    reasons: ['бо космос'],
    ratingSum: 0,
    ratingCount: 0,
    hidden: false,
    reportCount: 0,
    items: [],
    publishedAt: 1,
    ...overrides,
  })
  if (decoded === null) throw new Error('fixture must decode')
  return decoded
}

const failingStore: CollectionsStore = {
  readContaining: async () => ({ kind: 'failure' }),
  topPublic: async () => [],
  visibleBy: async () => [],
  vote: async () => false,
  myVote: async () => null,
  report: async () => false,
}

/** Reads really, but every write is an honest refusal — the offline seam. */
function refusingWritesStore(inner: InMemoryCollectionsStore): CollectionsStore {
  return {
    readContaining: (bookId) => inner.readContaining(bookId),
    topPublic: (limit) => inner.topPublic(limit),
    visibleBy: (authorId) => inner.visibleBy(authorId),
    vote: async () => false,
    myVote: (voterKey) => inner.myVote(voterKey),
    report: async () => false,
  }
}

async function renderBookPage(collectionsStore?: CollectionsStore | null): Promise<void> {
  vi.spyOn(api, 'book').mockResolvedValue(detail as never)
  vi.spyOn(api, 'catalog').mockResolvedValue({ sections: [] } as never)
  render(
    <BookPage
      url={BOOK_URL}
      source="fourread"
      onOpenBook={vi.fn()}
      onPlay={vi.fn(async () => true)}
      profile={{ uid: 'uid-1', nickname: 'Слухач-0001' }}
      reviewsStore={new InMemoryReviewsStore()}
      narrationRatingsStore={new InMemoryNarrationRatingsStore()}
      collectionsStore={collectionsStore}
    />,
  )
  await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeTruthy())
}

function renderListen(collectionsStore?: CollectionsStore | null): void {
  render(
    <Listen
      domainStore={new DomainStore()}
      linkStore={new EditionLinkStore()}
      listening={{ allSnapshots: async () => [] }}
      prefsStore={new ListenPrefsStore()}
      recommendationPrefs={new RecommendationPrefsStore()}
      collectionsStore={collectionsStore}
    />,
  )
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the book-page «Добірки з цією книгою» block (#697/#692)', () => {
  it('renders the visible collections ranked by the shared rule, hidden ones absent', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'low', title: 'Слабша', ratingSum: 4, ratingCount: 1 }))
    store.seed(collection({ collectionId: 'high', title: 'Найкраща', ratingSum: 5, ratingCount: 1 }))
    store.seed(collection({ collectionId: 'hidden', title: 'Прихована', ratingSum: 5, ratingCount: 9, hidden: true }))

    await renderBookPage(store)

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Добірки з цією книгою' })).toBeTruthy())
    const rows = screen.getAllByRole('button', { name: /^Відкрити добірку:/ })
    expect(rows).toHaveLength(2)
    expect(rows[0].getAttribute('aria-label')).toContain('Найкраща')
    expect(rows[1].getAttribute('aria-label')).toContain('Слабша')
    expect(screen.queryByText('Прихована')).toBeNull()
  })

  it('shows the honest «Ще без оцінок» instead of drawing fabricated stars', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'unvoted', title: 'Нова', ratingSum: 0, ratingCount: 0 }))
    await renderBookPage(store)

    await waitFor(() => expect(screen.getByText('Ще без оцінок')).toBeTruthy())
    const block = screen.getByRole('region', { name: 'Добірки з цією книгою' })
    expect(within(block).queryByText(/★/)).toBeNull()
  })

  it('renders no block at all when the shared layer is absent', async () => {
    await renderBookPage(null)
    expect(screen.queryByRole('heading', { name: 'Добірки з цією книгою' })).toBeNull()
  })

  it('renders no block when the read fails — a failure is not an empty community', async () => {
    await renderBookPage(failingStore)
    expect(screen.queryByRole('heading', { name: 'Добірки з цією книгою' })).toBeNull()
  })

  it('opens the collection screen with the composition, reason and real average', async () => {
    const user = userEvent.setup()
    const store = new InMemoryCollectionsStore()
    store.seed(
      collection({
        collectionId: 'curated',
        title: 'Космос',
        bookIds: [bookWorkId(), 'work-2'],
        ratingSum: 9,
        ratingCount: 2,
        items: [
          { bookId: 'work-1', title: 'Марсіянин', author: 'Енді Вейр', coverUrl: 'https://cdn.example/m.jpg', reason: 'бо космос' },
          { bookId: 'work-2', title: 'Дюна', author: 'Френк Герберт', reason: 'бо пісок' },
        ],
      }),
    )
    await renderBookPage(store)

    const row = await screen.findByRole('button', { name: /^Відкрити добірку: Космос/ })
    await user.click(row)

    const dialog = await screen.findByRole('dialog', { name: /Добірка: Космос/ })
    expect(within(dialog).getByText('добірка слухача Книголюб')).toBeTruthy()
    expect(within(dialog).getByText('Книг у добірці: 2')).toBeTruthy()
    expect(within(dialog).getByText('Марсіянин')).toBeTruthy()
    expect(within(dialog).getByText('Дюна')).toBeTruthy()
    expect(within(dialog).getByText('бо пісок')).toBeTruthy()
    expect(within(dialog).getByText(/★ 4.5/)).toBeTruthy()
  })

  it('falls back to the honest book id for a legacy document without snapshots', async () => {
    const user = userEvent.setup()
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'legacy', title: 'Спадок', bookIds: [bookWorkId()], reasons: ['бо так'], items: [] }))
    await renderBookPage(store)

    await user.click(await screen.findByRole('button', { name: /^Відкрити добірку: Спадок/ }))

    const dialog = await screen.findByRole('dialog', { name: /Добірка: Спадок/ })
    expect(within(dialog).getByText('Книга поза локальною бібліотекою')).toBeTruthy()
    expect(within(dialog).getByText('бо так')).toBeTruthy()
  })
})

describe('the Слухати rail «Добірки слухачів» (#697/#693)', () => {
  it('renders the ranked rail even when the listener has no personal shelves', async () => {
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'low', title: 'Слабша', ratingSum: 3, ratingCount: 1 }))
    store.seed(collection({ collectionId: 'high', title: 'Найкраща', ratingSum: 5, ratingCount: 1 }))

    renderListen(store)

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Добірки слухачів' })).toBeTruthy())
    const rows = screen.getAllByRole('button', { name: /^Відкрити добірку:/ })
    expect(rows[0].getAttribute('aria-label')).toContain('Найкраща')
  })

  it('opens the collection screen from a rail card', async () => {
    const user = userEvent.setup()
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'curated', title: 'Космос' }))
    renderListen(store)

    await user.click(await screen.findByRole('button', { name: /^Відкрити добірку: Космос/ }))
    expect(await screen.findByRole('dialog', { name: /Добірка: Космос/ })).toBeTruthy()
  })

  it('renders no rail without a store (honest absence)', async () => {
    renderListen(null)
    await waitFor(() => expect(screen.getByText('Тут з’являться ваші полиці')).toBeTruthy())
    expect(screen.queryByRole('heading', { name: 'Добірки слухачів' })).toBeNull()
  })
})

describe('the collection screen vote and complaint (#697/#694/#696)', () => {
  async function openCollection(store: CollectionsStore, title = 'Космос'): Promise<HTMLElement> {
    const user = userEvent.setup()
    await renderBookPage(store)
    await user.click(await screen.findByRole('button', { name: new RegExp(`^Відкрити добірку: ${title}`) }))
    return screen.findByRole('dialog', { name: new RegExp(`Добірка: ${title}`) })
  }

  it('votes 1–5, and a re-vote replaces the SAME document instead of adding one', async () => {
    const user = userEvent.setup()
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1', title: 'Космос' }))
    const dialog = await openCollection(store)

    // The key is the cross-platform one: sha256(uid + collectionId).
    const voterKey = CollectionIdentity.voterKey('uid-1', 'c1')

    await user.click(within(dialog).getByRole('radio', { name: '4 із 5' }))
    await waitFor(() => expect(store.votes.get(voterKey)?.stars).toBe(4))
    expect(store.votes.size).toBe(1)

    await user.click(within(dialog).getByRole('radio', { name: '2 із 5' }))
    await waitFor(() => expect(store.votes.get(voterKey)?.stars).toBe(2))
    expect(store.votes.size).toBe(1)

    // The aggregate the surfaces re-read is the server's: one vote, sum 2.
    const result = await store.readContaining(bookWorkId())
    if (result.kind !== 'data') throw new Error('expected data')
    expect(result.collections[0].ratingSum).toBe(2)
    expect(result.collections[0].ratingCount).toBe(1)
    // The screen shows the real refreshed average, never a locally invented one.
    await waitFor(() => expect(within(dialog).getByText(/★ 2\.0/)).toBeTruthy())
  })

  it('a complaint leaves the reporter’s own surfaces at once, long before the hide threshold', async () => {
    const user = userEvent.setup()
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1', title: 'Космос' }))
    const dialog = await openCollection(store)

    await user.click(within(dialog).getByRole('button', { name: 'Поскаржитись' }))

    await waitFor(() => expect(store.reports.size).toBe(1))
    expect(screen.queryByRole('button', { name: /^Відкрити добірку: Космос/ })).toBeNull()
    expect(screen.queryByRole('dialog', { name: /Добірка: Космос/ })).toBeNull()

    // The shared count is honest: ONE complaint, not a fabricated hide.
    const result = await store.readContaining(bookWorkId())
    if (result.kind !== 'data') throw new Error('expected data')
    expect(result.collections[0].reportCount).toBe(1)
    expect(result.collections[0].hidden).toBe(false)
  })

  it('a refused vote shows the error, marks no star and changes nothing', async () => {
    const user = userEvent.setup()
    const inner = new InMemoryCollectionsStore()
    inner.seed(collection({ collectionId: 'c1', title: 'Космос', ratingSum: 4, ratingCount: 1 }))
    const dialog = await openCollection(refusingWritesStore(inner))

    await user.click(within(dialog).getByRole('radio', { name: '5 із 5' }))

    expect(await within(dialog).findByText('Не вдалося зберегти оцінку. Спробуйте ще раз.')).toBeTruthy()
    // No fake success: the star is not the listener's own and the aggregate stands.
    expect(within(dialog).getByRole('radio', { name: '5 із 5' }).getAttribute('aria-checked')).toBe('false')
    expect(within(dialog).getByText(/★ 4\.0/)).toBeTruthy()
    expect(inner.votes.size).toBe(0)
  })

  it('a refused complaint shows the error and keeps the collection visible', async () => {
    const user = userEvent.setup()
    const inner = new InMemoryCollectionsStore()
    inner.seed(collection({ collectionId: 'c1', title: 'Космос' }))
    const dialog = await openCollection(refusingWritesStore(inner))

    await user.click(within(dialog).getByRole('button', { name: 'Поскаржитись' }))

    expect(await within(dialog).findByText('Не вдалося надіслати скаргу. Спробуйте ще раз.')).toBeTruthy()
    // The sheet stays open and nothing was hidden: an honest refusal, not a fake one.
    expect(screen.getByRole('dialog', { name: /Добірка: Космос/ })).toBeTruthy()
    expect(inner.reports.size).toBe(0)
  })

  it('an author never gets the vote or report controls on their own collection', async () => {
    const store = new InMemoryCollectionsStore()
    // The viewer's uid hashes to the collection's authorId: this one is theirs.
    store.seed(collection({ authorId: CuratorIdentity.authorId('uid-1'), collectionId: 'c1', title: 'Космос' }))
    const dialog = await openCollection(store)

    expect(within(dialog).queryByText('Ваша оцінка')).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Поскаржитись' })).toBeNull()
  })

  it('stays honestly read-only without an identity (no dead control)', async () => {
    vi.spyOn(api, 'book').mockResolvedValue(detail as never)
    vi.spyOn(api, 'catalog').mockResolvedValue({ sections: [] } as never)
    const store = new InMemoryCollectionsStore()
    store.seed(collection({ collectionId: 'c1', title: 'Космос' }))
    render(
      <BookPage
        url={BOOK_URL}
        source="fourread"
        onOpenBook={vi.fn()}
        onPlay={vi.fn(async () => true)}
        profile={null}
        reviewsStore={new InMemoryReviewsStore()}
        narrationRatingsStore={new InMemoryNarrationRatingsStore()}
        collectionsStore={store}
      />,
    )
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /^Відкрити добірку: Космос/ }))
    const dialog = await screen.findByRole('dialog', { name: /Добірка: Космос/ })

    expect(within(dialog).queryByText('Ваша оцінка')).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Поскаржитись' })).toBeNull()
  })
})
