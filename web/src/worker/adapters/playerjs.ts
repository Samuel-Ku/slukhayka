/**
 * spec-43/T4 — спільний розбір playerjs-плейлистів (`{"file":…,"title":…}`),
 * який досі дублювався у sluhay та audiobook-mp3; обидва реекспортують його
 * зі своїх модулів за сумісністю. Objects are walked PER OBJECT (title and
 * file of the same object stay aligned) — the Kotlin adapters' semantics;
 * a track without a title gets «Глава N» by its own position.
 */
import type { Chapter } from '../types'

const FILE = /"file"\s*:\s*"([^"]+)"/
const TITLE = /"title"\s*:\s*"([^"]*)"/

function beforeLast(input: string, separator: string): string {
  const index = input.lastIndexOf(separator)
  return index > 0 ? input.substring(0, index) : input
}

export function parsePlayerjsPlaylist(json: string): Chapter[] {
  if (!json.trim().startsWith('[{')) return []
  const chapters: Chapter[] = []
  let pos = 0
  for (;;) {
    const objStart = json.indexOf('{', pos)
    if (objStart < 0) break
    // Playlists are flat {title, file} objects — the object ends at the next
    // '}' outside a string. A '}' inside a title value would need a real JSON
    // walk; the playerjs sources emit flat objects, so the scan stays simple.
    const objEnd = json.indexOf('}', objStart)
    if (objEnd < 0) break
    const obj = json.slice(objStart, objEnd + 1)
    const file = FILE.exec(obj)?.[1]
    if (file !== undefined) {
      const raw = TITLE.exec(obj)?.[1]
      const name = raw === undefined ? '' : beforeLast(raw, '.').trim()
      chapters.push({ title: name === '' ? `Глава ${chapters.length + 1}` : name, streamUrl: file })
    }
    pos = objEnd + 1
  }
  return chapters
}
