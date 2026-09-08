import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { readWarm, WARM_CACHE_TTL_MS, warmKey, writeWarm } from '../api/warmCache'
import type { BookDetail, CatalogCard, SourceId } from '../worker/types'
import { canPlayBookFromDisplayedDetail, sourceNeedsBrowserSession } from './bookPlaybackAvailability'
import { EmptyState, EmptyStateRow, MetadataChip, PosterCard, SectionHeader } from './components'
import { useTranslate } from '../i18n/locale'
import type { ListenerProfile } from '../identity/listenerIdentity'
import type { NarrationRatingsStore, ReviewsStore } from '../reviews/store'
import { reviewWorkIdFor, ReviewsBlock } from './bookReviews'
import { mergeKeyFor, editionIdFor } from '../sync/edition'

/**
 * spec-43/T3+T5 + W4.1 — сторінка книги: метадані, розділи, «Інші
 * начитки» (rendition cards, empty on web until a registry exists), «У
 * серії» і «Можливо, Тебе зацікавить» з реальних даних сторінки, і блок
 * «Відгуки» (spec-40 #277-#282) через store seam — відсутній без Firebase.
 */
export function BookPage({
  url,
  source,
  onOpenBook,
  onPlay,
  profile,
  reviewsStore,
  narrationRatingsStore,
}: {
  url: string
  source: SourceId
  onOpenBook: (next: string, source: SourceId) => void
  onPlay?: (detail: BookDetail, chapterIndex: number) => Promise<boolean>
  profile: ListenerProfile | null
  reviewsStore: ReviewsStore | null
  narrationRatingsStore: NarrationRatingsStore | null
}) {
  const t = useTranslate()
  const [detail, setDetail] = useState<BookDetail | null>(null)
  const [failed, setFailed] = useState(false)
  const [showingCachedBook, setShowingCachedBook] = useState(false)
  const [seriesBooks, setSeriesBooks] = useState<CatalogCard[]>([])

  useEffect(() => {
    let alive = true
    setDetail(null)
    setFailed(false)
    setShowingCachedBook(false)
    api.book(source, url).then(async (result) => {
      if (!alive) return
      if (result === null) {
        const cached = await readWarm<BookDetail>(warmKey('book', source, url), WARM_CACHE_TTL_MS)
        if (!alive) return
        if (cached === null) setFailed(true)
        else {
          setDetail(cached)
          setShowingCachedBook(true)
        }
      } else {
        setDetail(result)
        setShowingCachedBook(false)
        // A browser-session Source may expose a temporary cookie-bound stream
        // URL. Keep its public metadata warm, but never persist that locator
        // as if another browser session could replay it.
        const cacheValue = sourceNeedsBrowserSession(source)
          ? { ...result, chapters: [] }
          : publicBookProjection(result)
        void writeWarm(warmKey('book', source, url), cacheValue)
      }
    })
    return () => {
      alive = false
    }
  }, [url, source])

  // W4.1 — «У серії»: the other volumes of the book's series, fetched once
  // per opened book through the catalogue seam (Android's
  // fetchSeriesBooks minus the book itself). A missing/failing series page
  // degrades to an empty row — the canonical empty state, never an error.
  const seriesUrl = detail?.series?.url
  useEffect(() => {
    let alive = true
    setSeriesBooks([])
    if (!seriesUrl) return
    void api.catalog(source, seriesUrl).then((parsed) => {
      if (!alive || parsed === null) return
      const cards = parsed.sections.flatMap((section) => section.cards)
      setSeriesBooks(cards.filter((card) => card.url !== url))
    })
    return () => {
      alive = false
    }
  }, [source, seriesUrl, url])

  if (failed) return <EmptyState message={t('bookFailed')} />
  if (detail === null) return <EmptyState message={t('loadingBook')} />

  const canPlay = canPlayBookFromDisplayedDetail(source, showingCachedBook)
  const requiresFreshSession = !canPlay && sourceNeedsBrowserSession(source)
  const mergeKey = mergeKeyFor(detail.title, detail.author)
  const editionId = editionIdFor(mergeKey, url, detail.narrator ?? '', detail.language ?? '')
  const workId = reviewWorkIdFor(mergeKey, editionId)

  return (
    <article>
      <h1>{detail.title}</h1>
      {showingCachedBook && <p className="notice" role="status" aria-live="polite">{t('cachedBookNotice')}</p>}
      {requiresFreshSession && (
        <p className="notice" role="status" aria-live="polite">
          {t('sessionCheckHint')}{' '}
          <a href={url} target="_blank" rel="noreferrer">{t('openSourcePage')}</a>
        </p>
      )}
      <p className="byline">
        {detail.author}
        {detail.narrator ? t('readBy', { narrator: detail.narrator }) : ''}
      </p>
      {detail.coverImageUrl && <img className="cover" src={detail.coverImageUrl} alt="" loading="lazy" />}
      {detail.genres.length > 0 && (
        <p className="genres">
          {detail.genres.map((genre) => <MetadataChip key={genre} kind="plain">{genre}</MetadataChip>)}
        </p>
      )}
      {detail.descriptionHtml && (
        <div className="description">
          {detail.descriptionHtml.split('\n').map((paragraph, i) => (
            <p key={i}>{paragraph}</p>
          ))}
        </div>
      )}

      <SectionHeader level="section" title={t('chapters')} count={detail.chapters.length > 0 ? detail.chapters.length : undefined} />
      {detail.chapters.length === 0 ? (
        <EmptyStateRow message={t('noChapters')} />
      ) : (
        <ol className="chapters">
          {detail.chapters.map((chapter, idx) => (
            <li key={chapter.streamUrl} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>{chapter.title}</span>
              {onPlay && canPlay && (
                <button
                  onClick={() => { void onPlay(detail, idx) }}
                  aria-label={t('listenChapterAria', { n: idx + 1 })}
                  style={{ background: 'var(--accent)', color: 'var(--accent-contrast)', border: 'none', borderRadius: 999, padding: '4px 12px' }}
                >
                  ▶
                </button>
              )}
            </li>
          ))}
        </ol>
      )}

      {detail.otherNarrations.length > 0 && (
        <>
          <SectionHeader level="section" title={t('otherNarrations')} count={detail.otherNarrations.length} />
          <ul className="narrations">
            {detail.otherNarrations.map((card) => (
              <li key={card.url}>
                <button onClick={() => onOpenBook(card.url, source)}>{`${card.title} — ${card.author}`}</button>
              </li>
            ))}
          </ul>
        </>
      )}

      {/* W4.1 — «У серії»: the other volumes of this book's series, the book
          itself excluded (Android's row verbatim). Empty = absent. */}
      {seriesBooks.length > 0 && (
        <>
          <SectionHeader level="section" title={t('inSeries')} count={seriesBooks.length} />
          <ul className="poster-row">
            {seriesBooks.map((card) => (
              <li key={card.url}>
                <PosterCard
                  coverUrl={card.coverImageUrl}
                  title={card.title}
                  author={card.author || undefined}
                  onClick={() => onOpenBook(card.url, source)}
                  openAriaLabel={t('openBookPosterAria', { title: card.title })}
                />
              </li>
            ))}
          </ul>
        </>
      )}

      {/* W4.1 — «Можливо, Тебе зацікавить»: the page's own related posters. */}
      {detail.relatedBooks.length > 0 && (
        <>
          <SectionHeader level="section" title={t('maybeInterest')} count={detail.relatedBooks.length} />
          <ul className="poster-row">
            {detail.relatedBooks.map((card) => (
              <li key={card.url}>
                <PosterCard
                  coverUrl={card.coverImageUrl}
                  title={card.title}
                  author={card.author || undefined}
                  onClick={() => onOpenBook(card.url, source)}
                  openAriaLabel={t('openBookPosterAria', { title: card.title })}
                />
              </li>
            ))}
          </ul>
        </>
      )}

      {/* W4.1 — «Відгуки»: the block exists ONLY when the store does (no
          Firebase config → no block, Android's contract). The narration
          rating row sits beside the current rendition. */}
      {reviewsStore && narrationRatingsStore && (
        <ReviewsBlock
          workId={workId}
          bookTitle={detail.title}
          defaultEditionTag={detail.narrator ?? ''}
          editionOptions={[detail.narrator ?? '', ...detail.otherNarrations.map((card) => card.author)]}
          sourceRating={detail.rating}
          profile={profile}
          store={reviewsStore}
          narrationRatingsStore={narrationRatingsStore}
          narrationEditionId={editionId}
        />
      )}
    </article>
  )
}

/** Keeps only direct tracks that another local browser context can safely reuse. */
export function publicBookProjection(detail: BookDetail): BookDetail {
  return {
    ...detail,
    chapters: detail.chapters.filter((chapter) => !hasPrivateStreamParameter(chapter.streamUrl)),
  }
}

function hasPrivateStreamParameter(streamUrl: string): boolean {
  try {
    // A query can be a vendor-specific signed token (for example an AWS
    // signature). There is no safe allowlist for a URL we did not issue.
    return new URL(streamUrl).search.length > 0
  } catch {
    return true
  }
}
