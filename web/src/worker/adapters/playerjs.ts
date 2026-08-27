/**
 * spec-43/T4 — спільний розбір playerjs-плейлистів (`{"file":…,"title":…}`),
 * який досі дублювався у sluhay та audiobook-mp3; обидва реекспортують його
 * зі своїх модулів за сумісністю.
 */
import type { Chapter } from '../types'

const FILE_G = /"file"\s*:\s*"([^"]+)"/gi
const TITLE_G = /"title"\s*:\s*"([^"]*)"/gi

function beforeLast(input: string, separator: string): string {
  const index = input.lastIndexOf(separator)
  return index > 0 ? input.substring(0, index) : input
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
