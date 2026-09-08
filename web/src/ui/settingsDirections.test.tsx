// @vitest-environment jsdom
/**
 * #591 W5.2 — the Сховище and Приватність directions:
 * - storage shows honest usage rows, keeps the destructive actions in a
 *   separated danger zone, and every clear carries Android's exact-scope
 *   confirmation (question + consequence) with a destructive confirm;
 * - privacy is honest TEXT about the real transport (ADR-0024 п.4) — no
 *   dead toggles.
 */
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Settings } from './Settings'
import { IdbListeningStateStore } from '../local/listeningState'
import { HybridListeningStateStorage } from '../local/hybridListeningState'
import { DomainStore } from '../local/domain'
import { RecommendationPrefsStore } from '../local/recommendationPrefs'
import { AUDIO_CACHE_NAME } from '../offline/policy'
import { setUiLocale } from '../i18n/locale'
import type { ManagementCache, ManagementCacheStorage } from '../local/storageManagement'

class FakeCache implements ManagementCache {
  private entries = new Map<string, Response>()

  async matchAll(): Promise<readonly Response[]> {
    return Array.from(this.entries.values())
  }

  async keys(): Promise<readonly (Request | string)[]> {
    return Array.from(this.entries.keys())
  }

  async delete(request: RequestInfo | string): Promise<boolean> {
    return this.entries.delete(String(request))
  }

  put(key: string, response: Response): void {
    this.entries.set(key, response)
  }

  clearAll(): void {
    this.entries.clear()
  }
}

function cacheStorageWith(cache: FakeCache): ManagementCacheStorage {
  return {
    open: vi.fn(async () => cache),
    // The real CacheStorage.delete removes the whole cache — the fake must
    // actually drop the entries, or the post-clear measure would lie.
    delete: vi.fn(async (name: string) => {
      if (name !== AUDIO_CACHE_NAME) return false
      cache.clearAll()
      return true
    }),
  }
}

const localProfile = { uid: 'local-abc123', nickname: 'Слухач-1' }

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  window.localStorage.clear()
})

afterEach(() => {
  cleanup()
})

describe('Сховище direction', () => {
  it('opens from the settings row, shows honest usage rows and the separated danger zone', async () => {
    const user = userEvent.setup()
    const idb = new IdbListeningStateStore()
    await idb.saveSnapshot({ editionId: 'e1', chapterIndex: 0, positionSeconds: 10, isCompleted: false, preferredSpeed: null, lastPausedAtEpochMs: null })
    const cache = new FakeCache()
    cache.put('relay-1', new Response(new Uint8Array(2048), { status: 200 }))
    render(
      <Settings
        profile={localProfile}
        recommendationPrefs={new RecommendationPrefsStore()}
        domainStore={new DomainStore()}
        hybrid={new HybridListeningStateStorage(idb, window.localStorage)}
        idbStore={idb}
        storage={window.localStorage}
        cacheStorage={cacheStorageWith(cache)}
      />,
    )
    await user.click(screen.getByRole('button', { name: /Сховище/ }))
    expect(screen.getByRole('heading', { level: 1, name: 'Сховище' })).toBeTruthy()
    // Neutral usage rows: the snapshot count comes from the real store.
    await waitFor(() => expect(screen.getByText('Позиції прослуховування')).toBeTruthy())
    expect(await screen.findByText('1')).toBeTruthy()
    expect(screen.getByText('2 КБ')).toBeTruthy() // the audio cache's real bytes
    // The danger zone is separated (ADR-0014) and destructive-styled.
    expect(screen.getByRole('heading', { name: 'Небезпечна зона' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Очистити кеш аудіо' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Скинути позиції прослуховування' })).toBeTruthy()
  })

  it('clears the audio cache with the exact-scope confirmation', async () => {
    const user = userEvent.setup()
    const cache = new FakeCache()
    cache.put('relay-1', new Response(new Uint8Array(2048), { status: 200 }))
    const storage = cacheStorageWith(cache)
    const idb = new IdbListeningStateStore()
    render(
      <Settings
        profile={localProfile}
        recommendationPrefs={new RecommendationPrefsStore()}
        domainStore={new DomainStore()}
        hybrid={new HybridListeningStateStorage(idb, window.localStorage)}
        idbStore={idb}
        storage={window.localStorage}
        cacheStorage={storage}
      />,
    )
    await user.click(screen.getByRole('button', { name: /Сховище/ }))
    await user.click(await screen.findByRole('button', { name: 'Очистити кеш аудіо' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog.textContent).toContain('Очистити кеш аудіо?')
    expect(dialog.textContent).toContain('Нещодавно слухані розділи зникнуть з офлайн-доступу')
    await user.click(screen.getByRole('button', { name: 'Очистити' }))

    expect(storage.delete).toHaveBeenCalledWith(AUDIO_CACHE_NAME)
    expect(await screen.findByText('Кеш аудіо очищено')).toBeTruthy()
    expect(await screen.findByText('Кешу аудіо немає')).toBeTruthy()
  })

  it('resets listening positions with the exact-scope confirmation', async () => {
    const user = userEvent.setup()
    const idb = new IdbListeningStateStore()
    await idb.saveSnapshot({ editionId: 'e1', chapterIndex: 3, positionSeconds: 500, isCompleted: false, preferredSpeed: null, lastPausedAtEpochMs: null })
    const hybrid = new HybridListeningStateStorage(idb, window.localStorage)
    await hybrid.whenBooted() // the app boots before render — so does the test
    render(
      <Settings
        profile={localProfile}
        recommendationPrefs={new RecommendationPrefsStore()}
        domainStore={new DomainStore()}
        hybrid={hybrid}
        idbStore={idb}
        storage={window.localStorage}
        cacheStorage={null}
      />,
    )
    await user.click(screen.getByRole('button', { name: /Сховище/ }))
    await waitFor(() => expect(screen.getByText('Позиції прослуховування')).toBeTruthy())

    await user.click(screen.getByRole('button', { name: 'Скинути позиції прослуховування' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog.textContent).toContain('Скинути позиції прослуховування?')
    expect(dialog.textContent).toContain('Кожна книга почнеться з початку')
    expect(dialog.textContent).toContain('Закладки та бібліотека залишаться')
    await user.click(screen.getByRole('button', { name: 'Скинути' }))

    expect(await screen.findByText('Позиції скинуто')).toBeTruthy()
    expect(await idb.allSnapshots()).toHaveLength(0)
    expect(await screen.findByText('0')).toBeTruthy()
  })
})

describe('Приватність direction', () => {
  it('explains the real transport honestly — no dead toggles', async () => {
    const user = userEvent.setup()
    render(
      <Settings
        profile={localProfile}
        recommendationPrefs={new RecommendationPrefsStore()}
        domainStore={new DomainStore()}
        hybrid={new HybridListeningStateStorage(new IdbListeningStateStore(), window.localStorage)}
        idbStore={new IdbListeningStateStore()}
        storage={window.localStorage}
        cacheStorage={null}
      />,
    )
    await user.click(screen.getByRole('button', { name: /Приватність/ }))
    expect(screen.getByRole('heading', { level: 1, name: 'Приватність' })).toBeTruthy()
    expect(screen.getByText('Сторінки джерел')).toBeTruthy()
    expect(screen.getByText(/воркер застосунку/)).toBeTruthy()
    expect(screen.getByText('Аудіо')).toBeTruthy()
    expect(screen.getByText(/напряму з джерела/)).toBeTruthy()
    expect(screen.getByText('Приватний маршрут')).toBeTruthy()
    expect(screen.getByText(/Проксі чи Tor на web немає/)).toBeTruthy()
    // No dead switches anywhere on the screen.
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('switch')).toBeNull()
  })
})