// @vitest-environment jsdom
/**
 * spec-51 (#697, T9, AC6) — «Без Firebase — чесна відсутність усіх
 * ком'юніті-поверхонь».
 *
 * The config reader already pins «no keys → null» (`firebase/config.test.ts`);
 * this test pins the other half of the chain at the App boundary: with a null
 * Firestore the book page has no «Добірки з цією книгою», no «Відгуки» and no
 * collection screen, and Слухати has no «Добірки слухачів». Not an empty
 * headline and not a dead control — the surfaces are absent.
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import { api } from '../api/client'
import { setUiLocale } from '../i18n/locale'
import type { BookDetail, UnifiedWorkPage } from '../worker/types'

// The real `getFirestoreForEnv` returns null for a config without keys; pin the
// App's WIRING by making that null explicit, so the test cannot turn green by
// a VITE_FIREBASE_* variable that happens to be exported on the build machine.
vi.mock('../firebase/firestore', () => ({ getFirestoreForEnv: () => null }))

const BOOK_URL = 'https://4read.org/7611-neostannij-bij.html'

const feed: UnifiedWorkPage = {
  works: [
    {
      id: 'w-kosmos',
      mergeKey: 'неостанній бій|костянтин шелест',
      title: 'Неостанній бій',
      author: 'Костянтин Шелест',
      editions: [{ id: 'e1', sources: [{ sourceId: 'fourread', url: BOOK_URL }] }],
    },
  ],
}

const detail: BookDetail = {
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

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  window.localStorage.clear()
  vi.spyOn(api, 'workFeed').mockResolvedValue(feed)
  vi.spyOn(api, 'book').mockResolvedValue(detail)
  vi.spyOn(api, 'catalog').mockResolvedValue({ sections: [] })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('App without Firebase (AC6)', () => {
  it('renders no community surface on the book page or Слухати', async () => {
    const user = userEvent.setup()
    render(<App profile={{ uid: 'uid-1', nickname: 'Слухач-0001' }} />)

    const openings = await screen.findAllByRole('button', { name: 'Відкрити книгу: Неостанній бій' })
    await user.click(openings[0]!)
    await screen.findByRole('heading', { level: 1, name: 'Неостанній бій' })

    // No collections block, no reviews/ratings block, and no sheet to open.
    expect(screen.queryByRole('heading', { name: 'Добірки з цією книгою' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Відгуки' })).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()

    // Back to Огляд, then Слухати: the rail is absent too, not empty-but-there.
    await user.click(screen.getByRole('button', { name: /Назад/ }))
    await user.click(screen.getByRole('tab', { name: 'Слухати' }))
    await screen.findByRole('heading', { level: 1, name: 'Слухати' })

    expect(screen.queryByRole('heading', { name: 'Добірки слухачів' })).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
