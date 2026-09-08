// @vitest-environment jsdom
/**
 * W4.1 — «Запит після завершення книги відкриває ту саму форму»: the
 * player sheet, on a completed book, offers the SAME ListenerReviewFormSheet
 * the book page uses (the AC's single entry — no second form, no second
 * button shape anywhere). Without Firebase stores the prompt is absent.
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AudioEngine } from '../player/audioEngine'
import { LocalListeningStateStore, type StorageLike } from '../player/localState'
import { setUiLocale } from '../i18n/locale'
import { InMemoryReviewsStore } from '../reviews/store'
import { PlayerSheet } from './PlayerSheet'

class MapStorage implements StorageLike {
  private map = new Map<string, string>()
  getItem(key: string): string | null {
    return this.map.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value)
  }

  removeItem(key: string): void {
    this.map.delete(key)
  }
}

function engineWithCompletedBook(): { engine: AudioEngine; store: LocalListeningStateStore } {
  const store = new LocalListeningStateStore(new MapStorage())
  const engine = new AudioEngine({ relayBase: '/api', store })
  // Drive the inner PlaybackEngine to the last chapter's end — the same
  // parking state the T5 binding publishes on the media element's ended.
  engine.engine.load(
    [{ title: 'Глава 1', streamUrl: 'https://4read.org/uploads/audio/1.mp3', durationSeconds: 60 }],
    { startChapter: 0, startPositionSeconds: 59.9 },
  )
  engine.engine.play()
  engine.engine.tick(500)
  return { engine, store }
}

beforeEach(() => {
  setUiLocale('uk')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the player finish prompt (W4.1)', () => {
  it('offers the same review form after the book completes', async () => {
    const { engine } = engineWithCompletedBook()
    const reviewsStore = new InMemoryReviewsStore()
    render(
      <PlayerSheet
        engine={engine}
        onClose={vi.fn()}
        lastPlayed={{ title: 'Неостанній бій', author: 'Костянтин Шелест', narrator: 'Олександр Волох', language: 'uk', url: 'https://4read.org/7611-neostannij-bij.html' }}
        profile={{ uid: 'uid-1', nickname: 'Слухач-0001' }}
        reviewsStore={reviewsStore}
      />,
    )
    await waitFor(() => expect(screen.getByRole('button', { name: 'Написати відгук' })).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: 'Написати відгук' }))
    // The SAME form sheet: heading «Ваш відгук», stars, body, publish.
    const form = within(screen.getByRole('dialog'))
    expect(form.getByRole('heading', { name: 'Ваш відгук' })).toBeTruthy()
    await userEvent.click(form.getByRole('radio', { name: '4 із 5' }))
    await userEvent.type(form.getByLabelText('Текст відгуку (необов’язково)'), 'Дочитав — сподобалось!')
    await userEvent.click(form.getByRole('button', { name: 'Опублікувати' }))
    await waitFor(async () => {
      const reviews = await reviewsStore.getForWork('неостанній бій|костянтин шелест')
      expect(reviews).toHaveLength(1)
      expect(reviews[0]).toMatchObject({ uid: 'uid-1', rating: 4, body: 'Дочитав — сподобалось!' })
    })
  })

  it('shows no prompt without the reviews store (honest no-Firebase state)', () => {
    const { engine } = engineWithCompletedBook()
    render(
      <PlayerSheet
        engine={engine}
        onClose={vi.fn()}
        lastPlayed={{ title: 'Неостанній бій', author: 'Костянтин Шелест', narrator: 'Олександр Волох', language: 'uk', url: 'https://4read.org/7611-neostannij-bij.html' }}
        profile={null}
        reviewsStore={null}
      />,
    )
    expect(screen.queryByRole('button', { name: 'Написати відгук' })).toBeNull()
  })
})