// @vitest-environment jsdom
/**
 * W4.1 — the book page's full build-out: the «У серії» and «Можливо, Тебе
 * зацікавить» rows from real data, and the «Відгуки» block — honest
 * average (ADR-0014: no addends → no stars), cards with own edit/delete,
 * the write/edit form (one form, both modes), the exact-scope delete
 * confirmation, and the narration-rating row (ADR-0023 #348). The finish
 * prompt opens the SAME form (the AC's single entry).
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { setUiLocale } from '../i18n/locale'
import { InMemoryNarrationRatingsStore, InMemoryReviewsStore } from '../reviews/store'
import type { ListenerReview } from '../reviews/reviewModel'
import type { NarrationRating } from '../reviews/narrationRatingModel'
import { BookPage } from './BookPage'
import { reviewDateLabel, reviewWorkIdFor, ListenerReviewFormSheet } from './bookReviews'
import { combinedAverage } from '../reviews/combinedAverage'
import { editionIdFor, mergeKeyFor } from '../sync/edition'

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
  relatedBooks: [
    { url: 'https://4read.org/7000-inshe.html', title: 'Інша книга', author: 'Інший автор', coverImageUrl: 'https://4read.org/uploads/posts/x.webp' },
  ],
  series: { name: 'Трохи ненависті', url: 'https://4read.org/xfsearch/cikl/xyz/', position: 1 },
  rating: 4.7,
}

const seriesCatalog = {
  sections: [
    {
      id: 'series',
      title: 'Цикл',
      cards: [
        { url: BOOK_URL, title: 'Неостанній бій', author: 'Костянтин Шелест' },
        { url: 'https://4read.org/8000-druga-knyga.html', title: 'Друга книга', author: 'Костянтин Шелест', coverImageUrl: 'https://4read.org/uploads/posts/y.webp' },
      ],
    },
  ],
}

function seededReview(overrides: Partial<ListenerReview> = {}): ListenerReview {
  return {
    workId: 'неостанній бій|костянтин шелест',
    uid: 'uid-1',
    authorName: 'Слухач-0001',
    rating: 5,
    body: 'Чудова книга!',
    editionTag: 'Олександр Волох',
    createdAt: 1_700_000_000_000,
    ...overrides,
  }
}

function pageEditionId(): string {
  return editionIdFor(mergeKeyFor(detail.title, detail.author), BOOK_URL, detail.narrator ?? '', detail.language ?? '')
}

function seededRating(overrides: Partial<NarrationRating> = {}): NarrationRating {
  return {
    workId: 'неостанній бій|костянтин шелест',
    uid: 'uid-1',
    editionId: pageEditionId(),
    rating: 5,
    createdAt: 1_700_000_000_000,
    ...overrides,
  }
}

async function renderPage({
  profile = { uid: 'uid-1', nickname: 'Слухач-0001' },
  reviewsStore,
  ratingsStore,
}: {
  profile?: { uid: string; nickname: string } | null
  reviewsStore?: InMemoryReviewsStore
  ratingsStore?: InMemoryNarrationRatingsStore
} = {}) {
  vi.spyOn(api, 'book').mockResolvedValue(detail)
  vi.spyOn(api, 'catalog').mockResolvedValue(seriesCatalog)
  const store = reviewsStore ?? new InMemoryReviewsStore()
  const ratings = ratingsStore ?? new InMemoryNarrationRatingsStore()
  render(
    <BookPage
      url={BOOK_URL}
      source="fourread"
      onOpenBook={vi.fn()}
      onPlay={vi.fn(async () => true)}
      profile={profile}
      reviewsStore={store}
      narrationRatingsStore={ratings}
    />,
  )
  await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeTruthy())
  return { store, ratings }
}

beforeEach(() => {
  setUiLocale('uk')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the book page rows (W4.1)', () => {
  it('renders «У серії» from the series page minus the book itself', async () => {
    await renderPage()
    await waitFor(() => expect(screen.getByRole('heading', { name: /У серії/ })).toBeTruthy())
    // The book itself is excluded; the other volume opens.
    expect(screen.queryByRole('button', { name: 'Відкрити книгу: Неостанній бій' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Відкрити книгу: Друга книга' })).toBeTruthy()
  })

  it("renders «Можливо, Тебе зацікавить» from the page's own posters", async () => {
    await renderPage()
    expect(screen.getByRole('heading', { name: /Можливо, Тебе зацікавить/ })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Відкрити книгу: Інша книга' })).toBeTruthy()
  })

  it('renders no series/related rows when the data is absent', async () => {
    vi.spyOn(api, 'book').mockResolvedValue({
      ...detail,
      series: undefined,
      relatedBooks: [],
      rating: undefined,
    })
    vi.spyOn(api, 'catalog').mockResolvedValue(null)
    render(
      <BookPage
        url={BOOK_URL}
        source="fourread"
        onOpenBook={vi.fn()}
        onPlay={vi.fn(async () => true)}
        profile={null}
        reviewsStore={null}
        narrationRatingsStore={null}
      />,
    )
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeTruthy())
    expect(screen.queryByRole('heading', { name: /У серії/ })).toBeNull()
    expect(screen.queryByRole('heading', { name: /Можливо, Тебе зацікавить/ })).toBeNull()
  })
})

describe('the reviews block (W4.1)', () => {
  it('is absent without a store — the honest no-Firebase state', async () => {
    vi.spyOn(api, 'book').mockResolvedValue(detail)
    render(
      <BookPage
        url={BOOK_URL}
        source="fourread"
        onOpenBook={vi.fn()}
        onPlay={vi.fn(async () => true)}
        profile={null}
        reviewsStore={null}
        narrationRatingsStore={null}
      />,
    )
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeTruthy())
    expect(screen.queryByRole('heading', { name: /Відгуки/ })).toBeNull()
  })

  it('renders the count, the cards and an honest combined average', async () => {
    const store = new InMemoryReviewsStore()
    store.seed(seededReview({ uid: 'uid-a', authorName: 'Інший слухач', rating: 4, body: 'Гарно', editionTag: undefined }))
    await renderPage({ reviewsStore: store })
    await waitFor(() => expect(screen.getByText('Інший слухач')).toBeTruthy())
    const heading = screen.getByRole('heading', { name: /Відгуки/ })
    expect(heading.textContent).toContain('1')
    expect(screen.getByText('Гарно')).toBeTruthy()
    // 4.7 source vote + 4 listener = 4.35 → one rounded value, real count.
    const average = combinedAverage([4.7], [4])
    expect(average).toEqual({ value: 4.35, count: 2 })
    expect(screen.getByLabelText('4.3 із 5, 2 оцінок, джерела і слухачі')).toBeTruthy()
    // The reviewer named no narration → no «Начитка:» chip.
    expect(screen.queryByText(/Начитка:/)).toBeNull()
  })

  it('renders the canonical empty state and NO average row when nobody rated', async () => {
    await renderPage()
    await waitFor(() => expect(screen.getByText('Ще немає відгуків — станьте першим')).toBeTruthy())
    expect(screen.queryByText('джерела і слухачі')).toBeNull()
  })

  it('writes a review through the store (document identity: one per listener per Work)', async () => {
    const { store } = await renderPage()
    await userEvent.click(screen.getByRole('button', { name: 'Написати відгук' }))
    const form = within(screen.getByRole('dialog'))
    await userEvent.click(form.getByRole('radio', { name: '5 із 5' }))
    await userEvent.type(form.getByLabelText('Текст відгуку (необов’язково)'), 'Супер!')
    await userEvent.click(form.getByRole('button', { name: 'Опублікувати' }))
    await waitFor(() => expect(screen.getByText('Супер!')).toBeTruthy())
    const stored = await store.getForWork('неостанній бій|костянтин шелест')
    expect(stored).toHaveLength(1)
    expect(stored[0]).toMatchObject({ uid: 'uid-1', rating: 5, body: 'Супер!', authorName: 'Слухач-0001' })
  })

  it('edits an own review under the SAME document key (no duplicate)', async () => {
    const store = new InMemoryReviewsStore()
    store.seed(seededReview())
    await renderPage({ reviewsStore: store })
    await userEvent.click(screen.getByRole('button', { name: 'Змінити ваш відгук про «Неостанній бій», оцінка 5 із 5' }))
    const form = within(screen.getByRole('dialog'))
    await userEvent.click(form.getByRole('radio', { name: '4 із 5' }))
    await userEvent.clear(form.getByLabelText('Текст відгуку (необов’язково)'))
    await userEvent.type(form.getByLabelText('Текст відгуку (необов’язково)'), 'Оновлено')
    await userEvent.click(form.getByRole('button', { name: 'Зберегти' }))
    await waitFor(() => expect(screen.getByText('Оновлено')).toBeTruthy())
    const stored = await store.getForWork('неостанній бій|костянтин шелест')
    expect(stored).toHaveLength(1)
    expect(stored[0]).toMatchObject({ rating: 4, body: 'Оновлено', editedAt: expect.any(Number) })
  })

  it('deletes an own review behind the exact-scope confirmation', async () => {
    const store = new InMemoryReviewsStore()
    store.seed(seededReview())
    const { store: used } = await renderPage({ reviewsStore: store })
    await userEvent.click(screen.getByRole('button', { name: 'Видалити ваш відгук про «Неостанній бій», оцінка 5 із 5' }))
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    expect(screen.getByText(/Буде видалено ваш відгук про «Неостанній бій»: оцінка 5 із 5 і текст відгуку/)).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Видалити' }))
    await waitFor(async () => expect((await used.getForWork('неостанній бій|костянтин шелест')).length).toBe(0))
  })

  it('disables publish until a star is chosen (stars are required)', async () => {
    await renderPage()
    await userEvent.click(screen.getByRole('button', { name: 'Написати відгук' }))
    const form = within(screen.getByRole('dialog'))
    const publish = form.getByRole('button', { name: 'Опублікувати' })
    expect((publish as HTMLButtonElement).disabled).toBe(true)
    await userEvent.click(form.getByRole('radio', { name: '3 із 5' }))
    expect((publish as HTMLButtonElement).disabled).toBe(false)
  })

  it('offers the narration tag dropdown with «Не вказувати»', async () => {
    await renderPage()
    await userEvent.click(screen.getByRole('button', { name: 'Написати відгук' }))
    const form = within(screen.getByRole('dialog'))
    // The trigger is prefilled with the current narrator; the menu offers
    // the explicit «Не вказувати» choice (Android's EDITION_TAG_NONE).
    await userEvent.click(form.getByRole('button', { name: 'Олександр Волох' }))
    await waitFor(() => expect(form.getByRole('button', { name: 'Не вказувати' })).toBeTruthy())
    await userEvent.click(form.getByRole('button', { name: 'Не вказувати' }))
    // The explicit no-tag choice is now the trigger's value.
    await waitFor(() => expect(form.getByRole('button', { name: 'Не вказувати' })).toBeTruthy())
  })
})

describe('the narration-rating row (ADR-0023 #348)', () => {
  it('shows the crowd average only when votes exist', async () => {
    const ratings = new InMemoryNarrationRatingsStore()
    ratings.seed(seededRating({ uid: 'uid-b', rating: 4 }))
    await renderPage({ ratingsStore: ratings })
    await waitFor(() => expect(screen.getByText('Начитка:')).toBeTruthy())
    expect(screen.getByText('4.0 · 1')).toBeTruthy()
  })

  it('asks for a rating instead of faking a zero when nobody rated yet', async () => {
    await renderPage()
    expect(screen.getByText('Оцінити начитку')).toBeTruthy()
    expect(screen.queryByText('Начитка:')).toBeNull()
  })

  it('rates the narration through the store and can delete the own rating', async () => {
    const { ratings } = await renderPage()
    const ask = screen.getByText('Оцінити начитку').closest('p') as HTMLElement
    await userEvent.click(within(ask).getByRole('radio', { name: '5 із 5' }))
    await waitFor(async () => expect((await ratings.getForWork('неостанній бій|костянтин шелест')).length).toBe(1))
    expect(screen.getByText('5.0 · 1')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Видалити оцінку начитки' }))
    await waitFor(async () => expect((await ratings.getForWork('неостанній бій|костянтин шелест')).length).toBe(0))
  })
})

describe('pure helpers', () => {
  it('reviewWorkIdFor mirrors Android: the mergeKey when present, else the edition id', () => {
    expect(reviewWorkIdFor('неостанній бій|костянтин шелест', 'edition-1')).toBe('неостанній бій|костянтин шелест')
    expect(reviewWorkIdFor('', 'edition-1')).toBe('edition-1')
  })

  it('reviewDateLabel formats a MEDIUM uk date', () => {
    expect(reviewDateLabel(1_700_000_000_000, 'uk')).toBe('14 лист. 2023 р.')
  })

  it('combinedAverage is honest: no addends → null', () => {
    expect(combinedAverage([], [])).toBeNull()
    expect(combinedAverage([null], [])).toBeNull()
    expect(combinedAverage([4.5], [5, 4])).toEqual({ value: 4.5, count: 3 })
  })
})

describe('the finish prompt opens the same form', () => {
  it('the ListenerReviewFormSheet is one shared component with both modes', async () => {
    setUiLocale('en')
    render(
      <ListenerReviewFormSheet
        bookTitle="Неостанній бій"
        editing={null}
        editionOptions={['Олександр Волох']}
        defaultEditionTag="Олександр Волох"
        isSaving={false}
        errorMessage={null}
        onSave={vi.fn()}
        onDismiss={vi.fn()}
      />,
    )
    expect(screen.getByRole('dialog', { name: 'Review of “Неостанній бій”' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Your review' })).toBeTruthy()
  })
})