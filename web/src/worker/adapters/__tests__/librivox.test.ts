/**
 * spec-45 T12 (#500) — тести порту LibriVoxAdapter (librivox.org JSON API +
 * archive.org mirror; одна sourceId, мова en).
 */
import { describe, expect, it } from 'vitest'
import { archiveSearchUrl, buildBookDetail, catalogUrlOf, identifierOf, librivoxAdapter } from '../librivox'
import { mayFetch, REGISTRY, sourceEntry } from '../../registry'
import { mergeWorkFeed } from '../../workFeed'
import { sourceContentLanguage } from '../../sourceMetadata'
import apiBooksJson from '../../fixtures/librivox-api-books.json?raw'
import apiMixedJson from '../../fixtures/librivox-api-mixed.json?raw'
import archiveSearchJson from '../../fixtures/librivox-archive-search.json?raw'
import metadataJson from '../../fixtures/librivox-metadata.json?raw'

const FEED_URL = catalogUrlOf()

describe('librivox adapter — catalogue feed (librivox.org JSON API)', () => {
  it('parses the api feed into en cards carrying archive mirror urls', () => {
    const catalog = librivoxAdapter.parseCatalog(apiBooksJson, FEED_URL)
    expect(catalog).not.toBeNull()
    const cards = catalog?.sections[0]?.cards ?? []
    expect(catalog?.sections[0]?.id).toBe('librivox')
    expect(cards).toHaveLength(3)
    expect(cards[0]).toMatchObject({
      title: 'Count of Monte Cristo',
      author: 'Alexandre Dumas',
      language: 'en',
      url: 'https://archive.org/details/count_monte_cristo_0711_librivox',
      durationSeconds: 178995,
    })
    expect(cards[1]).toMatchObject({ title: 'Letters of Two Brides', author: 'Honoré de Balzac' })
    expect(cards[2]).toMatchObject({ title: 'Bleak House', author: 'Charles Dickens' })
    // No date ordering in the API feed — the page does not continue.
    expect(catalog?.nextPageUrl).toBeUndefined()
  })

  it('drops non-English records at parse level (the LibriVox start)', () => {
    const catalog = librivoxAdapter.parseCatalog(apiMixedJson, FEED_URL)
    const cards = catalog?.sections[0]?.cards ?? []
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({ title: 'Treasure Island', language: 'en' })
  })

  it('continues the catalogue only after a full feed page', () => {
    const full = {
      books: Array.from({ length: 30 }, (_, n) => ({
        id: String(n),
        title: `Book ${n}`,
        language: 'English',
        url_zip_file: `https://archive.org/compress/book_${n}_librivox/formats=64KBPS MP3&file=/book_${n}_librivox.zip`,
        totaltimesecs: 100,
        authors: [{ first_name: 'Ann', last_name: 'Author' }],
      })),
    }
    const catalog = librivoxAdapter.parseCatalog(JSON.stringify(full), catalogUrlOf())
    expect(catalog?.nextPageUrl).toBe(catalogUrlOf(30))
  })

  it('blank or malformed payload yields null, never a fabricated card', () => {
    expect(librivoxAdapter.parseCatalog('   ', FEED_URL)).toBeNull()
    expect(librivoxAdapter.parseCatalog('not-json-at-all', FEED_URL)).toBeNull()
    expect(librivoxAdapter.parseCatalog('{"books": 3}', FEED_URL)).toBeNull()
  })
})

describe('librivox adapter — search (archive.org mirror)', () => {
  it('parses mirror docs into en cards', () => {
    const cards = librivoxAdapter.search!(
      archiveSearchJson,
      'https://archive.org/advancedsearch.php?q=collection%3Alibrivoxaudio',
    )
    expect(cards).toHaveLength(3)
    expect(cards[1]).toMatchObject({
      title: 'Jacko and Jumpo Kinkytail',
      author: 'Howard R. Garis',
      language: 'en',
      url: 'https://archive.org/details/jacko_and_jumpo_2007_librivox',
    })
  })

  it('builds a quoted-phrase archive search url inside the librivoxaudio collection', () => {
    const url = new URL(archiveSearchUrl('the count of monte cristo'))
    expect(url.hostname).toBe('archive.org')
    const q = url.searchParams.get('q') ?? ''
    expect(q).toContain('collection:librivoxaudio')
    expect(q).toContain('language:eng')
    expect(q).toContain('"the count of monte cristo"')
  })
})

describe('librivox adapter — book detail (archive.org metadata)', () => {
  const DETAIL_URL = 'https://archive.org/details/jacko_and_jumpo_2007_librivox'

  it('resolves ordered playable chapters from the mirror metadata', () => {
    const detail = buildBookDetail(metadataJson, DETAIL_URL)
    expect(detail).not.toBeNull()
    expect(detail).toMatchObject({
      title: 'Jacko and Jumpo Kinkytail',
      author: 'Howard R. Garis',
      language: 'en',
      coverImageUrl: 'https://archive.org/download/jacko_and_jumpo_2007_librivox/__ia_thumb.jpg',
      genres: [],
      otherNarrations: [],
    })
    expect(detail?.descriptionHtml).toContain('Jacko and Jumpo Kinkytail')
    // Only VBR MP3 files are chapters (64/128 Kbps duplicates, covers,
    // playlists and the m3u are never chapters); track order wins, and a
    // missing track falls back to the name sort (the Android rule).
    const chapters = detail?.chapters ?? []
    expect(chapters.map((chapter) => chapter.title)).toEqual([
      '01 - The Kinkytails Go To School',
      '02 - Jumpo and the Cocoanut',
      '03 - The Kinkytails Make a Pudding',
      '04 - Jacko and the Peanuts',
      '05 - Jumpo and the Ice Cream',
      '06 - Jacko and the Paper Bag',
      'jacko and jumpo 07',
    ])
    // mm:ss, h:mm:ss and decimal-seconds lengths all resolve to seconds.
    expect(chapters.map((chapter) => chapter.durationSeconds)).toEqual([736, 788, 697, 792, 1179, 3753, 600])
    expect(chapters[0].streamUrl).toBe(
      'https://archive.org/download/jacko_and_jumpo_2007_librivox/jackoandjumpo_01_garis.mp3',
    )
    // Spaces in file names are encoded in the download url.
    expect(chapters[6].streamUrl).toBe(
      'https://archive.org/download/jacko_and_jumpo_2007_librivox/jacko%20and%20jumpo%2007.mp3',
    )
  })

  it('a non-archive page url honestly yields no detail', () => {
    expect(buildBookDetail(metadataJson, 'https://librivox.org/the-count-of-monte-cristo-by-alexandre-dumas/')).toBeNull()
    expect(buildBookDetail(metadataJson, 'https://archive.org/')).toBeNull()
    expect(identifierOf(DETAIL_URL)).toBe('jacko_and_jumpo_2007_librivox')
    expect(identifierOf('https://archive.org/details/')).toBeNull()
  })

  it('malformed metadata payload yields null', () => {
    expect(buildBookDetail('not-json', DETAIL_URL)).toBeNull()
  })
})

describe('librivox — one source, two transports, one card per Work', () => {
  it('merges the api and mirror cards of one book by the existing rule', () => {
    const page = mergeWorkFeed([
      {
        sourceId: 'librivox',
        cards: [{ url: 'https://archive.org/details/treasure_island_2212_librivox', title: 'Treasure Island', author: 'Robert Louis Stevenson', language: 'en' }],
      },
      {
        sourceId: 'librivox',
        cards: [{ url: 'https://archive.org/details/treasure_island_0711_librivox', title: 'Treasure Island', author: 'Robert Louis Stevenson', language: 'en' }],
      },
    ])
    expect(page.works).toHaveLength(1)
    expect(page.works[0].editions).toHaveLength(1)
    expect(page.works[0].editions[0].sources).toHaveLength(2)
    expect(page.works[0].editions[0].language).toBe('en')
  })
})

describe('librivox — registry and metadata', () => {
  it('registers the source with both transports allowlisted', () => {
    const entry = sourceEntry('librivox')
    expect(entry).not.toBeNull()
    expect(entry?.adapter.id).toBe('librivox')
    expect(entry?.catalogUrl).toBe(catalogUrlOf())
    expect(entry?.searchUrl).toBeDefined()
    expect(mayFetch(entry!, 'https://librivox.org/api/feed/audiobooks/?format=json&limit=30&offset=0')).toBe(true)
    expect(mayFetch(entry!, 'https://archive.org/metadata/jacko_and_jumpo_2007_librivox')).toBe(true)
    expect(mayFetch(entry!, 'https://archive.org/download/jacko_and_jumpo_2007_librivox/jackoandjumpo_01_garis.mp3')).toBe(true)
    expect(mayFetch(entry!, 'https://evil.example.com/')).toBe(false)
    expect(REGISTRY.librivox.allowedHosts).toEqual(['librivox.org', 'archive.org'])
  })

  it('declares the English content language for the whole source', () => {
    expect(sourceContentLanguage('librivox')).toBe('en')
    expect(librivoxAdapter.displayName).toBe('LibriVox')
  })
})