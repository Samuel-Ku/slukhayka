/**
 * spec-43/T4 — порт AudiobookMp3Adapter
 */
import {
  absoluteUrl,
  attr,
  parseHtml,
  qs,
  type BookDetail,
  type CatalogCard,
  type CatalogSection,
  type Chapter,
  type ParsedCatalog,
  type SourceAdapter,
} from '../types'

const BASE = 'https://audiobook-mp3.com'

const PLAYLIST_URL = /(https:\/\/[a-z0-9]+\.redirectto\.cc\/[^"'<> ]+\.pl\.txt)/i
const BOOK_LINK_G = /href="(\/uk-audio-\d+-[^"]+)"[^>]*>([^<]*)/gi
const COVER_TILE_G = /<a\s+class="image-abook"\s+href="([^"]+)"[^>]*>\s*<img[^>]*src="([^"]+)"/gi
const COVER_IMG = /<img\s+class="abook_image"[^>]*src="([^"]+)"/i
const SITE_URL_TAIL = /\s*(?:audiobook-mp3\.com\/uk|audiobook-mp3\.com)\s*$/i
const AUTHOR_LINK = /Автор:(?:\s*<\/span>)?\s*<a[^>]*>([^<]+)<\/a>/i
const JSONLD_AUTHOR = /"author"\s*:\s*"([^"]+)"/i
const NARRATOR_LINK = /Виконавець:<\/span>\s*<a[^>]*>([^<]+)<\/a>/i
const CARD_BLOCK_G = /<article class="abook-item">([\s\S]*?)<\/article>/gi
const CARD_URL = /href="(\/uk-audio-\d+-[^"]+)"[^>]*>\s*<img/i
const CARD_NARRATOR = /fa-microphone[^>]*>[\s\S]*?<a[^>]*>([^<]+)<\/a>/i
const PAGE_GENRE_BLOCK = /Жанр:<\/span>([\s\S]*?)<\/div>/i
const LINK_TEXT_G = /<a[^>]*>([^<]+)<\/a>/gi
const DESC_P = /class="abook-desc"[\s\S]*?<p[^>]*>([\s\S]*?)<\/p>/i
const FILE_G = /"file"\s*:\s*"([^"]+)"/gi
const TITLE_G = /"title"\s*:\s*"([^"]*)"/gi

interface AuthorTitle {
  author: string
  title: string
}

export const audiobookMp3Adapter: SourceAdapter = {
  id: 'audiobook-mp3',
  displayName: 'Audiobook-MP3',
  baseUrl: BASE,
  parseCatalog(html, pageUrl): ParsedCatalog | null {
    const covers = new Map<string, string>()
    for (const m of html.matchAll(COVER_TILE_G)) covers.set(m[1], m[2])
    const narrators = cardNarrators(html)
    const seen = new Set<string>()
    const cards: CatalogCard[] = []
    for (const m of html.matchAll(BOOK_LINK_G)) {
      const path = m[1]
      if (m[2].trim().length < 3) continue
      const url = BASE + path
      if (seen.has(url)) continue
      seen.add(url)
      const anchor = trimQuotes(m[2].trim())
      const split = splitAuthorTitle(anchor)
      cards.push({
        url,
        title: split.title === '' ? slugTitle(url) : split.title,
        author: split.author,
        narrator: narrators.get(path) || undefined,
        coverImageUrl: covers.get(path),
      })
    }
    if (cards.length === 0) return null
    const section: CatalogSection = {
      id: sectionIdFromUrl(pageUrl),
      title: '',
      url: pageUrl,
      cards,
    }
    return { sections: [section] }
  },
  parseBookPage(html, pageUrl): BookDetail {
    const rawTitle = ogMeta(html, 'og:title') ?? slugTitle(pageUrl)
    const tailless = splitAuthorTitle(rawTitle).title.replace(SITE_URL_TAIL, '').trim()
    const title = tailless === '' ? rawTitle.trim() : tailless
    const author = AUTHOR_LINK.exec(html)?.[1]?.trim() ?? JSONLD_AUTHOR.exec(html)?.[1]?.trim() ?? ''
    const coverRaw = COVER_IMG.exec(html)?.[1]
    const coverImageUrl =
      coverRaw === undefined || coverRaw.trim() === ''
        ? undefined
        : coverRaw.startsWith('http')
          ? coverRaw
          : absoluteUrl(coverRaw, BASE)
    const narrator = NARRATOR_LINK.exec(html)?.[1]?.trim() ?? ''
    const descriptionHtml = descriptionFrom(html)
    return {
      url: pageUrl,
      title,
      author,
      narrator: narrator === '' ? undefined : narrator,
      coverImageUrl,
      genres: genresFrom(html),
      descriptionHtml: descriptionHtml === '' ? undefined : descriptionHtml,
      chapters: [],
      otherNarrations: [],
    }
  },
}

export function playlistUrlOf(html: string): string | null {
  return PLAYLIST_URL.exec(html)?.[1] ?? null
}

export function parsePlayerjsPlaylist(json: string): Chapter[] {
  if (!json.trim().startsWith('[{')) return []
  const files = [...json.matchAll(FILE_G)].map((m) => m[1])
  const titles = [...json.matchAll(TITLE_G)].map((m) => m[1])
  return files.map((file, index) => {
    const raw = titles[index]
    const name = raw === undefined ? '' : beforeLast(raw, '.').trim()
    return { title: name === '' ? `Глава ${index + 1}` : name, streamUrl: file }
  })
}

function cardNarrators(html: string): Map<string, string> {
  const out = new Map<string, string>()
  for (const m of html.matchAll(CARD_BLOCK_G)) {
    const path = CARD_URL.exec(m[1])?.[1]
    if (path === undefined) continue
    out.set(path, CARD_NARRATOR.exec(m[1])?.[1]?.trim() ?? '')
  }
  return out
}

function genresFrom(html: string): string[] {
  const block = PAGE_GENRE_BLOCK.exec(html)?.[1]
  if (block === undefined) return []
  return [...block.matchAll(LINK_TEXT_G)].map((m) => decodeEntities(m[1].trim()))
}

function descriptionFrom(html: string): string {
  const raw = DESC_P.exec(html)?.[1]
  return raw === undefined ? '' : decodeEntities(stripTags(raw)).trim()
}

function splitAuthorTitle(anchor: string): AuthorTitle {
  const sep = /\s+[—–-]\s+/.exec(anchor)
  if (sep === null || sep.index === undefined) return { author: anchor.trim(), title: anchor.trim() }
  const author = anchor.slice(0, sep.index).trim()
  const title = anchor.slice(sep.index + sep[0].length).trim()
  return { author, title: title === '' ? anchor.trim() : title }
}

function slugTitle(url: string): string {
  const slug = beforeFirst(afterLast(url, '/'), '?')
  const remainder = after(after(slug, 'uk-audio-', slug), '-', slug)
  const titled = titleFromSlug(remainder)
  return titled === '' ? slug : titled
}

function titleFromSlug(slug: string): string {
  const spaced = slug.replaceAll('-', ' ').trim()
  if (spaced === '') return ''
  const head = spaced.charAt(0)
  return (head === head.toLowerCase() ? head.toUpperCase() : head) + spaced.slice(1)
}

function trimQuotes(input: string): string {
  return input.replace(/^"+/, '').replace(/"+$/, '').trim()
}

function sectionIdFromUrl(pageUrl: string): string {
  try {
    return new URL(pageUrl).pathname.split('/').filter((s) => s !== '').join('-') || 'uk'
  } catch {
    return 'uk'
  }
}

function ogMeta(html: string, property: string): string | null {
  const el = qs(parseHtml(html), `meta[property="${property}"]`)
  if (el === null) return null
  const content = attr(el, 'content')
  return content === '' ? null : content
}

function decodeEntities(input: string): string {
  return input
    .replaceAll('&#039;', "'")
    .replaceAll('&#39;', "'")
    .replaceAll('&quot;', '"')
    .replaceAll('&amp;', '&')
}

function stripTags(input: string): string {
  return input.replace(/<[^>]+>/g, '')
}

function beforeFirst(s: string, delim: string): string {
  const i = s.indexOf(delim)
  return i < 0 ? s : s.slice(0, i)
}

function after(s: string, delim: string, missing: string): string {
  const i = s.indexOf(delim)
  return i < 0 ? missing : s.slice(i + delim.length)
}

function afterLast(s: string, delim: string): string {
  const i = s.lastIndexOf(delim)
  return i < 0 ? s : s.slice(i + delim.length)
}

function beforeLast(s: string, delim: string): string {
  const i = s.lastIndexOf(delim)
  return i < 0 ? s : s.slice(0, i)
}
