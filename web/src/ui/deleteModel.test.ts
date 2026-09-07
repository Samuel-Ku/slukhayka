/**
 * #584 W1.3 — the pure deletion-scope policy: Android's plural helper and
 * byte formatter, plus the exact-scope confirmation in both platform
 * truths (no downloads today; file count + size once downloads exist).
 */
import { describe, expect, it } from 'vitest'
import { translate, STRINGS } from '../i18n/strings'
import {
  deleteEverythingScopeText,
  deleteScopeItems,
  formatBytes,
  joinScopeParts,
  ukPlural,
} from './deleteModel'

/** The translate call the component uses, pinned per test. */
const t = (locale: 'uk' | 'en') => (key: Parameters<typeof translate>[1], params?: Record<string, string | number>) =>
  translate(locale, key, params)

describe('ukPlural (Android Format.kt port)', () => {
  it('follows the Ukrainian plural rules', () => {
    expect(ukPlural(1, 'файл', 'файли', 'файлів')).toBe('файл')
    expect(ukPlural(2, 'файл', 'файли', 'файлів')).toBe('файли')
    expect(ukPlural(4, 'файл', 'файли', 'файлів')).toBe('файли')
    expect(ukPlural(5, 'файл', 'файли', 'файлів')).toBe('файлів')
    expect(ukPlural(11, 'файл', 'файли', 'файлів')).toBe('файлів')
    expect(ukPlural(14, 'файл', 'файли', 'файлів')).toBe('файлів')
    expect(ukPlural(21, 'файл', 'файли', 'файлів')).toBe('файл')
    expect(ukPlural(22, 'файл', 'файли', 'файлів')).toBe('файли')
    expect(ukPlural(100, 'файл', 'файли', 'файлів')).toBe('файлів')
  })
})

describe('formatBytes (Android Format.kt port)', () => {
  it('renders whole MB under a GB and one decimal GB with the uk comma', () => {
    expect(formatBytes(0)).toBe('0 МБ')
    expect(formatBytes(350 * 1024 * 1024)).toBe('350 МБ')
    expect(formatBytes(2.3 * 1024 * 1024 * 1024)).toBe('2,3 ГБ')
  })
})

describe('deleteScopeItems', () => {
  it('lists only what the platform actually deletes, in Android order', () => {
    expect(deleteScopeItems({ chapters: false, bookmarks: false, progress: true, downloads: null })).toEqual(['deleteScopeProgress'])
    expect(deleteScopeItems({ chapters: true, bookmarks: true, progress: true, downloads: { fileCount: 2, bytes: 10 } })).toEqual([
      'deleteScopeChapters',
      'deleteScopeBookmarks',
      'deleteScopeProgress',
      'deleteScopeDownloads',
    ])
  })
})

describe('joinScopeParts', () => {
  it('joins the Ukrainian way: «a», «a і b», «a, b і c»', () => {
    expect(joinScopeParts(['a'], 'і')).toBe('a')
    expect(joinScopeParts(['a', 'b'], 'і')).toBe('a і b')
    expect(joinScopeParts(['a', 'b', 'c'], 'і')).toBe('a, b і c')
  })

  it("keeps Android's Oxford comma in English", () => {
    expect(joinScopeParts(['a', 'b', 'c'], 'and', true)).toBe('a, b, and c')
  })
})

describe('deleteEverythingScopeText', () => {
  it('web today: progress only, and the honest no-files sentence', () => {
    const text = deleteEverythingScopeText(
      { title: 'Книга', chapters: false, bookmarks: false, progress: true, downloads: null },
      t('uk'),
    )
    expect(text).toBe('Буде видалено «Книга» разом із прогресом. Завантажених файлів немає. Дію не можна скасувати.')
  })

  it('once downloads exist: the file count and byte size join the exact scope', () => {
    const text = deleteEverythingScopeText(
      { title: 'Книга', chapters: true, bookmarks: true, progress: true, downloads: { fileCount: 12, bytes: 2.3 * 1024 * 1024 * 1024 } },
      t('uk'),
    )
    expect(text).toBe(
      'Буде видалено «Книга» разом із розділами, закладками, прогресом і завантаженими файлами. ' +
        'Завантажені файли: 12 файлів (2,3 ГБ). Дію не можна скасувати.',
    )
  })

  it('one downloaded file uses the singular plural form', () => {
    const text = deleteEverythingScopeText(
      { title: 'Книга', chapters: false, bookmarks: false, progress: true, downloads: { fileCount: 1, bytes: 350 * 1024 * 1024 } },
      t('uk'),
    )
    expect(text).toContain('Завантажені файли: 1 файл (350 МБ).')
  })

  it('the en locale mirrors the Android values-en wording', () => {
    const text = deleteEverythingScopeText(
      { title: 'Книга', chapters: true, bookmarks: true, progress: true, downloads: { fileCount: 12, bytes: 2.3 * 1024 * 1024 * 1024 } },
      t('en'),
      'en',
    )
    expect(text).toBe(
      '“Книга” will be deleted along with chapters, bookmarks, progress, and downloaded files. ' +
        'Downloaded files: 12 files (2.3 GB). This cannot be undone.',
    )
  })

  it('never renders an empty list segment', () => {
    const text = deleteEverythingScopeText(
      { title: 'Книга', chapters: false, bookmarks: false, progress: false, downloads: null },
      t('uk'),
    )
    expect(text).toBe('Буде видалено «Книга». Завантажених файлів немає. Дію не можна скасувати.')
  })
})

describe('i18n parity of the deletion strings', () => {
  it('en keeps every uk key', () => {
    expect(Object.keys(STRINGS.en).sort()).toEqual(Object.keys(STRINGS.uk).sort())
  })
})
