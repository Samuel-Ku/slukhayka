// @vitest-environment jsdom
/**
 * W5.1 (ticket 12) — the player's three panes, pinned:
 * - the chapter list shows every chapter with the current position and
 *   jumps on click (Android's chapter sheet);
 * - the sleep timer renders Android's exact option set, counts down on the
 *   transport clock, and extends by +15;
 * - the bookmarks pane adds/jumps/deletes with Android's exact-scope
 *   confirmation and the honest empty state.
 */
import 'fake-indexeddb/auto'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AudioEngine, AUTO_BOOKMARK_NOTE } from '../player/audioEngine'
import { LocalListeningStateStore, type StorageLike } from '../player/localState'
import { PlayerBookmarksStore } from '../player/bookmarks'
import { openListenerDatabase } from '../local/idb'
import { LISTENER_DB_VERSION, LISTENER_STORES, PLAYER_BOOKMARKS_STORE } from '../local/schema'
import { setUiLocale } from '../i18n/locale'
import { BookmarksPane, ChapterList, SleepTimerPane, type BookmarkContext } from './playerExtras'
import { PlayerSheet } from './PlayerSheet'
import { InMemoryReviewsStore } from '../reviews/store'

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

function freshBookmarksStore(): PlayerBookmarksStore {
  return new PlayerBookmarksStore(() => openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES))
}

function freshEngine(): AudioEngine {
  return new AudioEngine({ relayBase: '/api', store: new LocalListeningStateStore(new MapStorage()) })
}

const CHAPTERS = [
  { title: 'Глава 1', streamUrl: 'https://4read.org/uploads/audio/1.mp3', durationSeconds: 600 },
  { title: 'Глава 2', streamUrl: 'https://4read.org/uploads/audio/2.mp3', durationSeconds: 300 },
]

const CONTEXT: BookmarkContext = {
  workId: 'Книга|Автор',
  editionId: 'ed-1',
  bookTitle: 'Книга',
  chapterIndex: 1,
  chapterTitle: 'Глава 2',
  positionSeconds: 42,
}

beforeEach(async () => {
  setUiLocale('uk')
  // fake-indexeddb is a shared singleton per test file — start clean.
  const db = await openListenerDatabase(LISTENER_DB_VERSION, LISTENER_STORES)
  await db?.clear(PLAYER_BOOKMARKS_STORE)
  db?.close()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('ChapterList', () => {
  it('renders every chapter, marks the current one with the position', () => {
    render(<ChapterList chapters={CHAPTERS} currentIndex={1} currentPosition={42} onJump={vi.fn()} />)
    expect(screen.getByRole('button', { name: /Глава 1/ })).toBeTruthy()
    const current = screen.getByRole('button', { name: /Глава 2/ })
    expect(current).toBeTruthy()
    expect(current.getAttribute('aria-current')).toBe('true')
    expect(screen.getByText('0:42')).toBeTruthy()
    expect(screen.getByText('10:00')).toBeTruthy()
  })

  it('reports the unknown duration honestly instead of fabricating one', () => {
    render(
      <ChapterList
        chapters={[{ title: 'Глава 1', streamUrl: 'https://x/1.mp3' }]}
        currentIndex={0}
        currentPosition={0}
        onJump={vi.fn()}
      />,
    )
    expect(screen.getByText('Тривалість невідома')).toBeTruthy()
  })

  it('jumps on click with the clicked chapter index', () => {
    const onJump = vi.fn()
    render(<ChapterList chapters={CHAPTERS} currentIndex={0} currentPosition={0} onJump={onJump} />)
    fireEvent.click(screen.getByRole('button', { name: /Глава 2/ }))
    expect(onJump).toHaveBeenCalledWith(1)
  })
})

describe('SleepTimerPane', () => {
  it('renders Android`s option set verbatim', () => {
    render(<SleepTimerPane engine={freshEngine()} />)
    expect(screen.getByText('Вимкнено')).toBeTruthy()
    expect(screen.getByText('До кінця розділу')).toBeTruthy()
    for (const minutes of [5, 15, 30, 45, 60, 90]) {
      expect(screen.getByText(`${minutes} хвилин`)).toBeTruthy()
    }
  })

  it('arms a countdown, shows the remainder on the transport clock, and extends +15', async () => {
    vi.useFakeTimers()
    const engine = freshEngine()
    await engine.loadBook({ title: 'Книга', chapters: CHAPTERS, editionId: 'e1', workId: 'w1' }, 0, { forceChapter: true })
    engine.pause() // stop the play ticker; the timer's own clock keeps running
    render(<SleepTimerPane engine={engine} />)
    fireEvent.click(screen.getByText('15 хвилин'))
    expect(screen.getByText('Залишилось: 15:00')).toBeTruthy()
    act(() => vi.advanceTimersByTime(60_000))
    expect(screen.getByText('Залишилось: 14:00')).toBeTruthy()
    fireEvent.click(screen.getByText('Додати 15 хвилин до таймера'))
    expect(screen.getByText('Залишилось: 29:00')).toBeTruthy()
  })

  it('the end-of-chapter option counts down to the chapter boundary', async () => {
    vi.useFakeTimers()
    const engine = freshEngine()
    await engine.loadBook({ title: 'Книга', chapters: CHAPTERS, editionId: 'e1', workId: 'w1' }, 0, { forceChapter: true })
    engine.pause()
    engine.seek(90)
    render(<SleepTimerPane engine={engine} />)
    fireEvent.click(screen.getByText('До кінця розділу'))
    expect(screen.getByText('До кінця розділу: 8:30')).toBeTruthy()
    act(() => vi.advanceTimersByTime(510_000))
    // The timer fired: the pane collapses to off (no countdown, no extend).
    expect(screen.queryByText(/Залишилось/)).toBeNull()
    expect(screen.queryByText('Додати 15 хвилин до таймера')).toBeNull()
  })

  it('extending a countdown preserves the exact remainder (Android`s +15 rule)', async () => {
    vi.useFakeTimers()
    const engine = freshEngine()
    await engine.loadBook({ title: 'Книга', chapters: CHAPTERS, editionId: 'e1', workId: 'w1' }, 0, { forceChapter: true })
    engine.pause()
    render(<SleepTimerPane engine={engine} />)
    fireEvent.click(screen.getByText('5 хвилин'))
    act(() => vi.advanceTimersByTime(18_000))
    fireEvent.click(screen.getByText('Додати 15 хвилин до таймера'))
    // 4:42 + 15:00 = 19:42 — the exact remainder was preserved.
    expect(screen.getByText('Залишилось: 19:42')).toBeTruthy()
  })
})

describe('BookmarksPane', () => {
  it('shows the honest empty state before any bookmark', async () => {
    render(<BookmarksPane store={freshBookmarksStore()} context={CONTEXT} onJump={vi.fn()} />)
    await waitFor(() => expect(screen.getByText('Для цієї книги ще немає закладок.')).toBeTruthy())
  })

  it('adds a bookmark with a note and lists it', async () => {
    const user = userEvent.setup()
    const store = freshBookmarksStore()
    render(<BookmarksPane store={store} context={CONTEXT} onJump={vi.fn()} />)
    await user.click(await screen.findByText('Додати закладку'))
    await user.type(screen.getByLabelText('Нотатка або важлива цитата (необов’язково)'), 'Цитата з розділу')
    await user.click(screen.getByText('Зберегти закладку'))
    await waitFor(() => expect(screen.getByText('0:42')).toBeTruthy())
    expect(screen.getByText('Глава 2')).toBeTruthy()
    expect(screen.getByText('Цитата з розділу')).toBeTruthy()
    expect(await store.forWork('Книга|Автор')).toHaveLength(1)
  })

  it('defaults the note to «Закладка на {time}» when empty', async () => {
    const user = userEvent.setup()
    render(<BookmarksPane store={freshBookmarksStore()} context={CONTEXT} onJump={vi.fn()} />)
    await user.click(await screen.findByText('Додати закладку'))
    await user.click(screen.getByText('Зберегти закладку'))
    await waitFor(() => expect(screen.getByText('Закладка на 0:42')).toBeTruthy())
  })

  it('jumps to the bookmark with its exact chapter and position', async () => {
    const user = userEvent.setup()
    const store = freshBookmarksStore()
    const bookmark = await store.add({ workId: 'Книга|Автор', editionId: 'ed-1', chapterIndex: 3, chapterTitle: 'Глава 4', timestampSeconds: 130, note: '' })
    const onJump = vi.fn()
    render(<BookmarksPane store={store} context={CONTEXT} onJump={onJump} />)
    const row = await screen.findByText('2:10')
    const jump = row.closest('li')!.querySelector('button')!
    await user.click(jump)
    expect(onJump).toHaveBeenCalledWith(expect.objectContaining({ id: bookmark!.id, chapterIndex: 3, timestampSeconds: 130 }))
  })

  it('deletes with the exact-scope confirmation (question + consequence)', async () => {
    const user = userEvent.setup()
    const store = freshBookmarksStore()
    await store.add({ workId: 'Книга|Автор', editionId: 'ed-1', chapterIndex: 1, chapterTitle: 'Глава 2', timestampSeconds: 42, note: 'Цитата' })
    render(<BookmarksPane store={store} context={CONTEXT} onJump={vi.fn()} />)
    const row = await screen.findByText('0:42')
    const buttons = row.closest('li')!.querySelectorAll('button')
    await user.click(buttons[1])
    // Android's exact-scope wording: the question names book, chapter, time.
    const dialog = within(await screen.findByRole('alertdialog'))
    expect(dialog.getByText('Видалити закладку у «Книга», розділ «Глава 2», 0:42?')).toBeTruthy()
    expect(dialog.getByText('Нотатку «Цитата» буде видалено без можливості відновлення.')).toBeTruthy()
    await user.click(dialog.getByText('Видалити'))
    await waitFor(() => expect(screen.getByText('Для цієї книги ще немає закладок.')).toBeTruthy())
    expect(await store.forWork('Книга|Автор')).toHaveLength(0)
  })
})

describe('the sleep timer transport (Android`s onFinish semantics)', () => {
  it('a fired timer pauses playback and writes the auto-bookmark', async () => {
    // shouldAdvanceTime: fake-indexeddb schedules via setImmediate, which a
    // frozen clock would starve — the real event loop keeps draining it.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const store = freshBookmarksStore()
    const engine = new AudioEngine({
      relayBase: '/api',
      store: new LocalListeningStateStore(new MapStorage()),
      bookmarks: store,
    })
    await engine.loadBook({ title: 'Книга', chapters: CHAPTERS, editionId: 'e1', workId: 'Книга|Автор' }, 0, { forceChapter: true })
    engine.setSleepTimer(5)
    act(() => vi.advanceTimersByTime(300_000))
    // Android's onFinish: pause + the «Авто-закладка (Таймер сну)» row.
    expect(engine.getState().status).toBe('paused')
    const list = await store.forWork('Книга|Автор')
    expect(list).toHaveLength(1)
    expect(list[0]).toMatchObject({
      note: AUTO_BOOKMARK_NOTE,
      workId: 'Книга|Автор',
      editionId: 'e1',
      chapterIndex: 0,
    })
  })

  it('an end-of-chapter timer re-arms at the next chapter boundary', async () => {
    vi.useFakeTimers()
    const engine = freshEngine()
    await engine.loadBook({ title: 'Книга', chapters: CHAPTERS, editionId: 'e1', workId: 'w1' }, 0, { forceChapter: true })
    engine.pause()
    engine.seek(590) // 10 s left in chapter 0 (600 s)
    engine.setSleepTimer(-1)
    expect(engine.getSleepTimerState()).toMatchObject({ isEndOfChapter: true, remainingSeconds: 10 })
    engine.jumpTo(1, 0) // chapter change — the timer re-arms at the new boundary
    expect(engine.getSleepTimerState()).toMatchObject({ isEndOfChapter: true, remainingSeconds: 300 })
  })
})

describe('PlayerSheet panes (W5.1)', () => {
  it('hosts the three tabs and reports the honest bookmark count', async () => {
    const user = userEvent.setup()
    const engine = freshEngine()
    await engine.loadBook(
      { title: 'Книга', chapters: CHAPTERS, editionId: 'ed-1', workId: 'Книга|Автор' },
      1,
      { forceChapter: true, startPositionSeconds: 42 },
    )
    engine.pause() // stop the transport ticker for the test
    const store = freshBookmarksStore()
    await store.add({ workId: 'Книга|Автор', editionId: 'ed-1', chapterIndex: 0, chapterTitle: 'Глава 1', timestampSeconds: 10, note: '' })
    render(
      <PlayerSheet
        engine={engine}
        onClose={vi.fn()}
        lastPlayed={{ title: 'Книга', author: 'Автор', url: 'https://4read.org/1.html' }}
        profile={null}
        reviewsStore={new InMemoryReviewsStore()}
        bookmarksStore={store}
      />,
    )
    expect(screen.getByRole('tab', { name: 'Розділи (2)' })).toBeTruthy()
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Закладки (1)' })).toBeTruthy())
    // Chapters tab: current chapter marked with its position.
    expect(screen.getByRole('button', { name: /Глава 2/ })).toBeTruthy()
    // Bookmarks tab: the seeded bookmark appears and jumps via the engine.
    await user.click(screen.getByRole('tab', { name: /Закладки/ }))
    await waitFor(() => expect(screen.getByText('0:10')).toBeTruthy())
    const jump = vi.spyOn(engine, 'jumpTo')
    const row = screen.getByText('0:10').closest('li')!
    await user.click(row.querySelector('button')!)
    expect(jump).toHaveBeenCalledWith(0, 10)
    // The sleep timer tab offers Android's options.
    await user.click(screen.getByRole('tab', { name: 'Таймер сну' }))
    expect(screen.getByText('До кінця розділу')).toBeTruthy()
  })

  it('shows the honest empty bookmarks state without a loaded book', () => {
    render(
      <PlayerSheet
        engine={freshEngine()}
        onClose={vi.fn()}
        lastPlayed={null}
        profile={null}
        reviewsStore={null}
        bookmarksStore={freshBookmarksStore()}
      />,
    )
    fireEvent.click(screen.getByRole('tab', { name: /Закладки/ }))
    expect(screen.getByText('Для цієї книги ще немає закладок.')).toBeTruthy()
  })
})