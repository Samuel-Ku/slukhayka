// @vitest-environment jsdom
/**
 * spec-45 T14 (#502) — under locale = en, exercised flows (catalog feed and
 * book page) render no Ukrainian UI literals. Language self-names
 * («Українська» on the chip/badge) are content, not chrome, and stay.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { Catalog } from '../ui/Catalog'
import { BookPage } from '../ui/BookPage'
import { setUiLocale } from './locale'
import type { UnifiedWorkPage } from '../worker/types'

const page: UnifiedWorkPage = {
  works: [{
    id: 'pride',
    mergeKey: 'pride',
    title: 'Pride and Prejudice',
    author: 'Jane Austen',
    editions: [
      { id: 'pride-uk', language: 'uk', narrator: 'Читець', sources: [{ sourceId: 'sound-books', url: 'https://sound-books.net/pride' }] },
      { id: 'pride-en', language: 'en', narrator: 'Reader', sources: [{ sourceId: 'librivox', url: 'https://archive.org/details/pride_librivox' }] },
    ],
  }],
}

const UK_CHROME = ['Пошук', 'Усі джерела', 'Завантажуємо', 'Показати більше', 'Начитка', 'Слухати', 'Відкрити', 'Мова:', 'Розділи', 'Інші начитки']

beforeEach(() => {
  setUiLocale('en')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  setUiLocale('uk')
})

describe('en locale flows', () => {
  it('renders the catalog feed and language chips without Ukrainian chrome', async () => {
    vi.spyOn(api, 'workFeed').mockResolvedValue(page)
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    expect(screen.getByPlaceholderText('Search…')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('Pride and Prejudice')).toBeTruthy())
    // The heading and the source chip both carry the same label.
    expect(screen.getAllByText('All sources').length).toBeGreaterThan(0)
    expect(screen.getByText('Language:')).toBeTruthy()
    expect(screen.getAllByText('All').length).toBeGreaterThan(0)
    // Language self-names stay visible as content.
    expect(screen.getByText('Українська')).toBeTruthy()

    const body = document.body.textContent ?? ''
    for (const literal of UK_CHROME) {
      expect(body, `en catalog renders uk literal: ${literal}`).not.toContain(literal)
    }
  })

  it('renders the book page without Ukrainian chrome', async () => {
    vi.spyOn(api, 'book').mockResolvedValue({
      url: 'https://archive.org/details/pride_librivox',
      title: 'Pride and Prejudice',
      author: 'Jane Austen',
      narrator: 'Reader',
      language: 'en',
      genres: [],
      chapters: [{ title: 'Chapter 1', streamUrl: 'https://archive.org/download/pride_librivox/01.mp3', durationSeconds: 600 }],
      otherNarrations: [],
    })
    render(<BookPage
      url="https://archive.org/details/pride_librivox"
      source="librivox"
      onOpenBook={vi.fn()}
      onPlay={vi.fn(async () => true)}
    />)

    await waitFor(() => expect(screen.getByText('Pride and Prejudice')).toBeTruthy())
    expect(screen.getByText('Chapters')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Listen to chapter 1' })).toBeTruthy()
    expect(screen.getByText(/read by Reader/)).toBeTruthy()

    const body = document.body.textContent ?? ''
    for (const literal of UK_CHROME) {
      expect(body, `en book page renders uk literal: ${literal}`).not.toContain(literal)
    }
  })
})