// @vitest-environment jsdom
/**
 * #592 W6.1 — the «Для вас» group on Огляд: the personal shelf renders
 * with Android's reason chips, the «У медіатеці» marker shows only for
 * saved books, «Не цікаво» feeds the SAME local preference store the
 * Listen shelves use, and the participation split is honest — OFF or an
 * absent server profile render the local adaptation; a real profile
 * renders its own picks.
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ForYouGroup, reasonTextOf } from './forYouGroup'
import { DomainStore } from '../local/domain'
import { EditionLinkStore } from '../local/editionLinks'
import { IdbListeningStateStore } from '../local/listeningState'
import { RecommendationPrefsStore } from '../local/recommendationPrefs'
import { setUiLocale } from '../i18n/locale'
import { mergeKeyFor } from '../sync/edition'
import type { UnifiedWork } from '../worker/types'
import type { PersonalPick } from '../recommend/personalization'

const work = (overrides: Partial<UnifiedWork> & { id: string; mergeKey: string; title: string }): UnifiedWork => ({
  author: 'Автор',
  editions: [{ id: `${overrides.id}-e`, sources: [{ sourceId: 'sluhayua', url: `https://x/${overrides.id}` }] }],
  ...overrides,
})

const works: UnifiedWork[] = [
  work({ id: 'w1', mergeKey: mergeKeyFor('Майстер і Маргарита. Том 2', 'Булгаков'), title: 'Майстер і Маргарита. Том 2', author: 'Булгаков' }),
  work({ id: 'w2', mergeKey: mergeKeyFor('Собаче серце (нове видання)', 'Булгаков'), title: 'Собаче серце (нове видання)', author: 'Булгаков' }),
  work({ id: 'w3', mergeKey: mergeKeyFor('Математика для всіх', 'Петренко'), title: 'Математика для всіх', author: 'Петренко' }),
]

const domain = (): DomainStore => new DomainStore()
const links = (): EditionLinkStore => new EditionLinkStore()
const idb = (): IdbListeningStateStore => new IdbListeningStateStore()
const prefsStore = (): RecommendationPrefsStore => new RecommendationPrefsStore()

/** The «signals» side: one saved, ≥30% listened book («Майстер і Маргарита»). */
async function seedSignals(store: DomainStore, linkStore: EditionLinkStore, db: IdbListeningStateStore): Promise<void> {
  const entry = await store.addLibraryEntry({ title: 'Майстер і Маргарита', author: 'Булгаков' })
  await linkStore.link({ editionId: 's1-e', mergeKey: entry.mergeKey, narrator: 'N', language: 'uk', durationSeconds: 3600, chapterDurations: [1200, 1200, 1200] })
  await db.saveSnapshot({ editionId: 's1-e', chapterIndex: 1, positionSeconds: 600, isCompleted: false, preferredSpeed: null, lastPausedAtEpochMs: Date.now() })
}

function pick(): PersonalPick {
  return {
    candidate: { mergeKey: mergeKeyFor('Майстер і Маргарита. Том 2', 'Булгаков'), title: 'Майстер і Маргарита. Том 2', author: 'Булгаков', inLibrary: false },
    score: 0.5,
    reason: { kind: 'similar', title: 'Майстер і Маргарита' },
    exploration: false,
  }
}

beforeEach(() => {
  // fake-indexeddb is a shared singleton — a fresh DB per test.
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
})

afterEach(() => {
  cleanup()
})

describe('ForYouGroup — the «Для вас» group', () => {
  it('renders the honest empty state when there are no personal signals', async () => {
    render(
      <ForYouGroup
        works={works}
        domainStore={domain()}
        linkStore={links()}
        listening={idb()}
        recommendationPrefs={prefsStore()}
        participation={false}
        profileSource={{ load: async () => null }}
        onOpenWork={() => undefined}
        onDismiss={() => undefined}
      />,
    )
    await screen.findByText('Персональних добірок поки немає.')
    expect(screen.getByRole('heading', { name: 'Для вас' })).toBeTruthy()
  })

  it('renders the personal shelf with Android\'s reason chips and the «У медіатеці» marker', async () => {
    const store = domain()
    const linkStore = links()
    const db = idb()
    await seedSignals(store, linkStore, db)
    // The candidate «Собаче серце» is saved too — its marker must render.
    const saved = await store.addLibraryEntry({ title: 'Собаче серце (нове видання)', author: 'Булгаков' })
    await linkStore.link({ editionId: 'w2-e', mergeKey: saved.mergeKey, narrator: 'N', language: 'uk', durationSeconds: 3600, chapterDurations: null })

    render(
      <ForYouGroup
        works={works}
        domainStore={store}
        linkStore={linkStore}
        listening={db}
        recommendationPrefs={prefsStore()}
        participation={false}
        profileSource={{ load: async () => null }}
        onOpenWork={() => undefined}
        onDismiss={() => undefined}
      />,
    )
    await screen.findByText('Рекомендовано для вас')
    // The reason chip answers «чому це тут?» (Android's «Схоже на X»).
    expect(screen.getByText('Схоже на «Майстер і Маргарита»')).toBeTruthy()
    expect(screen.getByText('У медіатеці')).toBeTruthy()
    // The irrelevant book never appears.
    expect(screen.queryByText('Математика для всіх')).toBeNull()
  })

  it('«Не цікаво» hides the pick immediately, persisting the SAME HIDE_WORK preference', async () => {
    const user = userEvent.setup()
    const store = domain()
    const linkStore = links()
    const db = idb()
    await seedSignals(store, linkStore, db)
    const prefs = prefsStore()

    render(
      <ForYouGroup
        works={works}
        domainStore={store}
        linkStore={linkStore}
        listening={db}
        recommendationPrefs={prefs}
        participation={false}
        profileSource={{ load: async () => null }}
        onOpenWork={() => undefined}
        onDismiss={(mergeKey) => { void prefs.add('HIDE_WORK', mergeKey, mergeKey) }}
      />,
    )
    await screen.findByText('Схоже на «Майстер і Маргарита»')
    const pickKey = mergeKeyFor('Майстер і Маргарита. Том 2', 'Булгаков')
    await user.click(screen.getByRole('button', { name: 'Не цікаво: Майстер і Маргарита. Том 2' }))
    await waitFor(() => expect(screen.queryByText('Майстер і Маргарита. Том 2')).toBeNull())
    const rows = await prefs.all()
    expect(rows.some((row) => row.kind === 'HIDE_WORK' && row.targetKey === pickKey)).toBe(true)
  })

  it('participation ON with a REAL server profile renders the profile picks', async () => {
    const store = domain()
    const linkStore = links()
    const db = idb()
    await seedSignals(store, linkStore, db)

    render(
      <ForYouGroup
        works={works}
        domainStore={store}
        linkStore={linkStore}
        listening={db}
        recommendationPrefs={prefsStore()}
        participation={true}
        profileSource={{ load: async () => [{ mergeKey: mergeKeyFor('Математика для всіх', 'Петренко'), title: 'Математика для всіх', author: 'Петренко', reason: { kind: 'collective', title: 'Майстер і Маргарита' } }] }}
        onOpenWork={() => undefined}
        onDismiss={() => undefined}
      />,
    )
    await screen.findByText('Математика для всіх')
    // The collective reason only ever comes from a REAL graph edge.
    expect(screen.getByText('Подобається слухачам, яким сподобалася «Майстер і Маргарита»')).toBeTruthy()
  })

  it('participation ON with NO server profile falls back to the local adaptation (ADR-0031)', async () => {
    const store = domain()
    const linkStore = links()
    const db = idb()
    await seedSignals(store, linkStore, db)

    render(
      <ForYouGroup
        works={works}
        domainStore={store}
        linkStore={linkStore}
        listening={db}
        recommendationPrefs={prefsStore()}
        participation={true}
        profileSource={{ load: async () => null }}
        onOpenWork={() => undefined}
        onDismiss={() => undefined}
      />,
    )
    // The absent graph never blocks Огляд: the local shelf serves.
    await screen.findByText('Рекомендовано для вас')
    expect(screen.getByText('Схоже на «Майстер і Маргарита»')).toBeTruthy()
  })

  it('a profile pick with no openable candidate is honestly skipped', async () => {
    const store = domain()
    const linkStore = links()
    const db = idb()
    await seedSignals(store, linkStore, db)

    render(
      <ForYouGroup
        works={works}
        domainStore={store}
        linkStore={linkStore}
        listening={db}
        recommendationPrefs={prefsStore()}
        participation={true}
        profileSource={{ load: async () => [{ mergeKey: 'ghost', title: 'Немає в каталозі', author: 'Хтось' }] }}
        onOpenWork={() => undefined}
        onDismiss={() => undefined}
      />,
    )
    // Only the ghost — so the shelf is honestly empty (ADR-0014).
    await screen.findByText('Персональних добірок поки немає.')
    expect(screen.queryByText('Немає в каталозі')).toBeNull()
  })
})

describe('reasonTextOf — Android\'s reason dictionary', () => {
  const t = (key: string): string => key

  it('«Схоже на X» for the similar pick', () => {
    expect(reasonTextOf(pick(), t)).toBe('recommendReasonSimilar')
  })

  it('no chip for exploration picks (Android isExploration)', () => {
    expect(reasonTextOf({ ...pick(), reason: null }, t)).toBeNull()
  })
})