/**
 * #584 W1.3 — the pure deletion-scope policy, ported from Android's
 * `ui/library/Format.kt` and the `BookDeleteOptionsSheet` /
 * `BookDeleteConfirmationDialog` copy: the Ukrainian plural helper, the
 * human byte size, and the exact-scope confirmation text. Pure — testable
 * without a screen (the ADR-0034 two-runtime rule).
 *
 * Honesty rule (ADR-0014/0022): the confirmation quotes the EXACT scope —
 * and the scope is what the platform actually has. Web has no downloads
 * yet (the platform ledger), so the no-files branch renders «Завантажених
 * файлів немає» instead of Android's file wording; once downloads exist
 * the same model quotes the file count and the byte size («2,3 ГБ»).
 */
import type { StringKey } from '../i18n/strings'

/**
 * The Ukrainian plural helper (Android `Format.kt`):
 * 1 → one; 2–4 → few; 5–20 → many; 21 → one again; 11–14 → many even
 * though they end in 1–4.
 */
export function ukPlural(n: number, one: string, few: string, many: string): string {
  const n100 = n % 100
  const n10 = n % 10
  if (n100 >= 11 && n100 <= 14) return many
  if (n10 === 1) return one
  if (n10 >= 2 && n10 <= 4) return few
  return many
}

/** «2,3 ГБ» / «350 МБ» — a human byte size with the Ukrainian decimal comma. */
export function formatBytes(bytes: number, locale: 'uk' | 'en' = 'uk'): string {
  if (bytes <= 0) return locale === 'en' ? '0 MB' : '0 МБ'
  const gb = bytes / (1024.0 * 1024 * 1024)
  if (gb >= 1.0) {
    return locale === 'en' ? `${gb.toFixed(1)} GB` : `${gb.toFixed(1)} ГБ`.replace('.', ',')
  }
  return locale === 'en' ? `${Math.floor(bytes / (1024 * 1024))} MB` : `${Math.floor(bytes / (1024 * 1024))} МБ`
}

/** What the destructive action actually removes — the platform's real scope. */
export interface DeleteScope {
  title: string
  /** Web deletes no chapter rows yet (W5.1 ports bookmarks; chapters ride the Edition). */
  chapters: boolean
  bookmarks: boolean
  progress: boolean
  /** The Work's downloaded copy when one exists; null = none (web today). */
  downloads: { fileCount: number; bytes: number } | null
}

/** The scope items as i18n keys, in Android's order — the caller translates. */
export function deleteScopeItems(scope: Omit<DeleteScope, 'title'>): StringKey[] {
  const items: StringKey[] = []
  if (scope.chapters) items.push('deleteScopeChapters')
  if (scope.bookmarks) items.push('deleteScopeBookmarks')
  if (scope.progress) items.push('deleteScopeProgress')
  if (scope.downloads !== null) items.push('deleteScopeDownloads')
  return items
}

/**
 * «a, b і c» — the list join. Ukrainian puts no comma before «і»; English
 * keeps Android's Oxford comma («progress, and downloaded files»).
 */
export function joinScopeParts(parts: string[], andWord: string, commaBeforeAnd = false): string {
  if (parts.length === 0) return ''
  if (parts.length === 1) return parts[0]!
  const separator = commaBeforeAnd ? ', ' : ' '
  return `${parts.slice(0, -1).join(', ')}${separator}${andWord} ${parts[parts.length - 1]!}`
}

/**
 * The exact-scope body of the delete-everything confirmation, assembled
 * from locale strings + the platform's real scope. The translated tokens:
 * body template, one noun per scope item, the file sentence, and the
 * file-count plural forms. With nothing else in scope the «along with»
 * segment disappears instead of rendering an empty list.
 */
export function deleteEverythingScopeText(
  scope: DeleteScope,
  t: (key: StringKey, params?: Readonly<Record<string, string | number>>) => string,
  locale: 'uk' | 'en' = 'uk',
): string {
  const items = deleteScopeItems(scope).map((key) => t(key))
  const files = scope.downloads === null
    ? t('deleteScopeFilesNone')
    : t('deleteScopeFilesWithSize', {
        files: `${scope.downloads.fileCount} ${ukPlural(scope.downloads.fileCount, t('deleteScopeFileOne'), t('deleteScopeFileFew'), t('deleteScopeFileMany'))}`,
        size: formatBytes(scope.downloads.bytes, locale),
      })
  if (items.length === 0) return t('deleteConfirmBodyPlain', { title: scope.title, files })
  const list = joinScopeParts(items, t('deleteScopeAnd'), locale === 'en')
  return t('deleteConfirmBody', { title: scope.title, list, files })
}
