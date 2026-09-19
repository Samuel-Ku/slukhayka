// @vitest-environment jsdom
/**
 * #611 — the React wiring of the two Play intents: the book page's per-Chapter
 * ▶ is an explicit pick (starts that Chapter from zero), so it must reach the
 * playback interface marked `explicitChapter`. Without this wiring the card
 * index 0 and «first Chapter» were indistinguishable and the saved place won.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { setUiLocale } from '../i18n/locale'
import { BookPage } from './BookPage'

const BOOK_URL = 'https://sound-books.example/book-1'

const detail = {
  url: BOOK_URL,
  title: 'Книга',
  author: 'Автор',
  narrator: 'Диктор',
  language: 'uk',
  genres: [],
  chapters: [
    { title: 'Розділ 1', streamUrl: 'https://audio.example/1.mp3', durationSeconds: 600 },
    { title: 'Розділ 2', streamUrl: 'https://audio.example/2.mp3', durationSeconds: 300 },
  ],
  otherNarrations: [],
  relatedBooks: [],
}

function renderPage(): (detail: unknown, chapterIndex: number, intent?: unknown) => Promise<boolean> {
  const onPlay = vi.fn(async () => true)
  vi.spyOn(api, 'book').mockResolvedValue(detail)
  vi.spyOn(api, 'catalog').mockResolvedValue(null)
  render(
    <BookPage
      url={BOOK_URL}
      source="sound-books"
      onOpenBook={vi.fn()}
      onPlay={onPlay}
      profile={null}
      reviewsStore={null}
      narrationRatingsStore={null}
    />,
  )
  return onPlay
}

beforeEach(() => setUiLocale('uk'))
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('#611 — the book page Chapter row is an explicit pick', () => {
  it('forwards explicitChapter with the chosen index', async () => {
    const user = userEvent.setup()
    const onPlay = renderPage()

    await user.click(await screen.findByRole('button', { name: 'Слухати розділ 2' }))

    await waitFor(() => expect(onPlay).toHaveBeenCalledTimes(1))
    expect(onPlay).toHaveBeenCalledWith(detail, 1, { explicitChapter: true })
  })
})
