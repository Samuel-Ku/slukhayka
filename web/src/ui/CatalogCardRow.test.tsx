// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { BookDetail, CatalogCard, UnifiedSource } from '../worker/types'
import { CatalogCardRow } from './Catalog'

const card: CatalogCard = {
  url: 'https://sound-books.net/book',
  title: 'Книга',
  author: 'Автор',
}
const sources: UnifiedSource[] = [{ sourceId: 'sound-books', url: card.url }]
const detail: BookDetail = {
  ...card,
  genres: [],
  chapters: [{ title: 'Розділ', streamUrl: 'https://audio.example/1.mp3' }],
  otherNarrations: [],
}

class PlayingAudio extends EventTarget {
  muted = false
  preload = ''
  src = ''
  play = vi.fn(async () => { queueMicrotask(() => this.dispatchEvent(new Event('playing'))) })
  pause = vi.fn()
  load = vi.fn()
  removeAttribute = vi.fn()
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('CatalogCardRow accessibility and actions', () => {
  it('keeps Open and Play as separate focusable actions', async () => {
    const open = vi.fn()
    const play = vi.fn(async () => true)
    vi.spyOn(api, 'book').mockResolvedValue(detail)
    vi.stubGlobal('Audio', PlayingAudio)
    render(<CatalogCardRow card={card} editionId="edition-a" sources={sources} onOpenBook={open} onPlay={play} />)

    const openButton = screen.getByRole('button', { name: 'Відкрити книгу: Книга' })
    const playButton = screen.getByRole('button', { name: 'Слухати: Книга' })
    expect(openButton.compareDocumentPosition(playButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await userEvent.click(openButton)
    expect(open).toHaveBeenCalledWith(card.url, 'sound-books')
    expect(play).not.toHaveBeenCalled()

    await userEvent.click(playButton)
    await waitFor(() => expect(play).toHaveBeenCalledOnce())
  })

  it('announces checking and cancellation blocks a late result', async () => {
    let resolveBook: ((value: BookDetail) => void) | undefined
    vi.spyOn(api, 'book').mockImplementation(() => new Promise((resolve) => { resolveBook = resolve }))
    const play = vi.fn(async () => true)
    render(<CatalogCardRow card={card} editionId="edition-a" sources={sources} onOpenBook={vi.fn()} onPlay={play} />)

    fireEvent.click(screen.getByRole('button', { name: 'Слухати: Книга' }))
    expect(screen.getByText('Перевіряємо доступність…').getAttribute('aria-live')).toBe('polite')
    fireEvent.click(screen.getByRole('button', { name: 'Скасувати перевірку: Книга' }))
    resolveBook?.(detail)

    await Promise.resolve()
    expect(play).not.toHaveBeenCalled()
    expect(screen.queryByText('Перевіряємо доступність…')).toBeNull()
  })

  it('shows a listener-controlled browser door for a session Source', async () => {
    const book = vi.spyOn(api, 'book')
    render(<CatalogCardRow
      card={{ ...card, url: 'https://4read.org/book' }}
      editionId="edition-a"
      sources={[{ sourceId: 'fourread', url: 'https://4read.org/book' }]}
      onOpenBook={vi.fn()}
      onPlay={vi.fn(async () => true)}
    />)

    await userEvent.click(screen.getByRole('button', { name: 'Слухати: Книга' }))
    expect(await screen.findByText(/потребує сесії/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Відкрити 4read' }).getAttribute('href')).toBe('https://4read.org')
    expect(book).not.toHaveBeenCalled()
  })

  it('badges a known edition language and stays silent for unknown', () => {
    const { rerender } = render(<CatalogCardRow
      card={{ ...card, language: 'en' }}
      editionId="edition-a"
      sources={sources}
      onOpenBook={vi.fn()}
      onPlay={vi.fn(async () => true)}
    />)
    const badge = screen.getByText('EN')
    expect(badge.getAttribute('aria-label')).toBe('English')

    rerender(<CatalogCardRow
      card={card}
      editionId="edition-a"
      sources={sources}
      onOpenBook={vi.fn()}
      onPlay={vi.fn(async () => true)}
    />)
    expect(screen.queryByText('EN')).toBeNull()
    expect(screen.queryByText('UA')).toBeNull()
  })

  it('links to the session-gated fallback Source that actually needs recovery', async () => {
    vi.spyOn(api, 'book').mockResolvedValue(null)
    render(<CatalogCardRow
      card={card}
      editionId="edition-a"
      sources={[
        { sourceId: 'sound-books', url: card.url },
        { sourceId: 'fourread', url: 'https://4read.org/book' },
      ]}
      onOpenBook={vi.fn()}
      onPlay={vi.fn(async () => true)}
    />)

    await userEvent.click(screen.getByRole('button', { name: 'Слухати: Книга' }))
    expect((await screen.findByRole('link', { name: 'Відкрити 4read' })).getAttribute('href')).toBe('https://4read.org')
  })
})
