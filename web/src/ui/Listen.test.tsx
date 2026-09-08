// @vitest-environment jsdom
/**
 * #585 W2.1 — the Слухати screen: shelves with reasons, the hero never
 * repeating below itself, and the ONE «Керувати полицями» sheet whose
 * reorder/hide/restore persists in IndexedDB.
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { DomainStore } from '../local/domain'
import { EditionLinkStore } from '../local/editionLinks'
import { ListenPrefsStore } from '../local/listenPrefs'
import { RecommendationPrefsStore } from '../local/recommendationPrefs'
import type { LocalListeningStateSnapshot } from '../player/localState'
import type { ListenerDatabase } from '../local/listeningState'
import { setUiLocale } from '../i18n/locale'
import { Listen } from './Listen'

const DAY = 24 * 3600 * 1000
const NOW = Date.now()

function makeListening(rows: LocalListeningStateSnapshot[]): Pick<ListenerDatabase, 'allSnapshots'> {
  return { allSnapshots: async () => rows }
}

async function seedBook(domain: DomainStore, links: EditionLinkStore, mergeKey: string, title: string): Promise<void> {
  // The author 'А' normalizes into the mergeKey the caller passes.
  await domain.addLibraryEntry({ title, author: 'А' })
  await links.link({
    editionId: `ed-${mergeKey}`,
    mergeKey,
    narrator: '',
    language: '',
    durationSeconds: null,
    chapterDurations: [1000, 1000],
  })
}

const snapshot = (editionId: string, lastPausedAtEpochMs: number, chapterIndex = 0, positionSeconds = 10): LocalListeningStateSnapshot => ({
  editionId,
  chapterIndex,
  positionSeconds,
  isCompleted: false,
  preferredSpeed: null,
  lastPausedAtEpochMs,
})

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
})

afterEach(() => {
  cleanup()
})

describe('Listen', () => {
  it('a cold start renders the honest empty state', async () => {
    render(
      <Listen
        domainStore={new DomainStore()}
        linkStore={new EditionLinkStore()}
        listening={makeListening([])}
        prefsStore={new ListenPrefsStore()}
        recommendationPrefs={new RecommendationPrefsStore()}
      />,
    )
    expect(await screen.findByText('Тут з’являться ваші полиці')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: 'Слухати' })).toBeTruthy()
  })

  it('composes hero, almost-done and return shelves with reasons; the hero never repeats', async () => {
    const domain = new DomainStore()
    const links = new EditionLinkStore()
    await seedBook(domain, links, 'герой|а', 'Герой')
    await seedBook(domain, links, 'майже|а', 'Майже')
    await seedBook(domain, links, 'спляча|а', 'Спляча')
    render(
      <Listen
        domainStore={domain}
        linkStore={links}
        listening={makeListening([
          // The hero: listened yesterday, 25 % through.
          snapshot('ed-герой|а', NOW - DAY, 0, 500),
          // Almost done: 90 % through, listened 2 days ago.
          snapshot('ed-майже|а', NOW - 2 * DAY, 1, 800),
          // Dormant: touched 20 days ago.
          snapshot('ed-спляча|а', NOW - 20 * DAY),
        ])}
        prefsStore={new ListenPrefsStore()}
        recommendationPrefs={new RecommendationPrefsStore()}
      />,
    )

    await waitFor(() => expect(screen.getByText('Герой')).toBeTruthy())
    expect(screen.getByRole('heading', { name: 'Продовжити слухати' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: /Майже дочитали/ })).toBeTruthy()
    expect(screen.getByText(/До кінця 3 хв/)).toBeTruthy()
    expect(screen.getByRole('heading', { name: /Поверніться/ })).toBeTruthy()
    expect(screen.getByText(/Ви слухали 20 днів тому/)).toBeTruthy()
    // Hero dedup: «Герой» renders exactly once on the whole screen.
    expect(screen.getAllByText('Герой')).toHaveLength(1)
  })

  it('«Керувати полицями» hides and restores, and both persist across remounts', async () => {
    const user = userEvent.setup()
    const domain = new DomainStore()
    const links = new EditionLinkStore()
    await seedBook(domain, links, 'книга|а', 'Книга')
    const prefsStore = new ListenPrefsStore()
    const props = {
      domainStore: domain,
      linkStore: links,
      listening: makeListening([snapshot('ed-книга|а', NOW - DAY)]),
      prefsStore,
      recommendationPrefs: new RecommendationPrefsStore(),
    }
    const { unmount } = render(<Listen {...props} />)
    await waitFor(() => expect(screen.getByText('Книга')).toBeTruthy())

    // The one chrome entry opens the sheet; focus lands on its heading.
    await user.click(screen.getByRole('button', { name: 'Керувати полицями' }))
    expect(document.activeElement?.textContent).toBe('Керувати полицями')
    await user.click(screen.getByRole('button', { name: 'Сховати полицію: Продовжити слухати' }))

    // Hidden: the hero block is gone from the screen but the sheet keeps it.
    await user.click(screen.getByRole('button', { name: 'Готово' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(screen.queryByText('Продовжити слухати')).toBeNull())
    expect(screen.getByText('Книга')).toBeTruthy()

    // Persistence: a remount restores the hidden state.
    unmount()
    render(<Listen {...props} />)
    await waitFor(() => expect(screen.queryByText('Продовжити слухати')).toBeNull())
    expect(await screen.findByText('Книга')).toBeTruthy()

    // Restore from the sheet's hidden section.
    await user.click(screen.getByRole('button', { name: 'Керувати полицями' }))
    await user.click(screen.getByRole('button', { name: /Повернути полицію: Продовжити слухати/ }))
    await user.click(screen.getByRole('button', { name: 'Готово' }))
    await waitFor(() => expect(screen.getByText('Продовжити слухати')).toBeTruthy())
  })

  it('«Не цікаво» hides the book from every shelf and persists as a HIDE_WORK preference', async () => {
    const user = userEvent.setup()
    const domain = new DomainStore()
    const links = new EditionLinkStore()
    await seedBook(domain, links, 'герой|а', 'Герой')
    await seedBook(domain, links, 'інша|а', 'Інша')
    const recommendationPrefs = new RecommendationPrefsStore()
    const props = {
      domainStore: domain,
      linkStore: links,
      listening: makeListening([
        snapshot('ed-герой|а', NOW - DAY, 0, 500),
        snapshot('ed-інша|а', NOW - 2 * DAY),
      ]),
      prefsStore: new ListenPrefsStore(),
      recommendationPrefs,
    }
    const { unmount } = render(<Listen {...props} />)
    await waitFor(() => expect(screen.getByText('Герой')).toBeTruthy())

    // «Герой» is the resume CTA — no ✕ on the hero row; shelf rows carry it.
    expect(screen.queryByRole('button', { name: 'Не цікаво: Герой' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Не цікаво: Інша' })).toBeTruthy()

    // Dismiss «Інша» (a recently-added shelf row): the book leaves, the
    // HIDE_WORK preference persists locally.
    await user.click(screen.getByRole('button', { name: 'Не цікаво: Інша' }))
    await waitFor(() => expect(screen.queryByText('Інша')).toBeNull())
    const rows = await recommendationPrefs.all()
    expect(rows.map((row) => row.targetKey)).toEqual(['інша|а'])
    expect(rows[0]!.kind).toBe('HIDE_WORK')

    // Persistence: a remount keeps the book hidden; the hero stays intact.
    unmount()
    render(<Listen {...props} />)
    await waitFor(() => expect(screen.queryByText('Інша')).toBeNull())
    expect(await screen.findByText('Герой')).toBeTruthy()
  })

  it('closing the manage sheet returns focus to its opener', async () => {
    const user = userEvent.setup()
    render(
      <Listen
        domainStore={new DomainStore()}
        linkStore={new EditionLinkStore()}
        listening={makeListening([])}
        prefsStore={new ListenPrefsStore()}
        recommendationPrefs={new RecommendationPrefsStore()}
      />,
    )
    await screen.findByText('Тут з’являться ваші полиці')
    await user.click(screen.getByRole('button', { name: 'Керувати полицями' }))
    await user.click(screen.getByRole('button', { name: 'Готово' }))
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Керувати полицями')
  })
})
