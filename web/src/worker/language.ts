/**
 * spec-45 (#405) T12 — the ONE normalizer of Edition content languages on
 * the web, mirroring the Android `LanguageCode` rule (same inputs, same
 * outputs, same maps) so both platforms claim identical BCP-47 tags.
 *
 * An Edition's `language` is a BCP-47 primary tag (`uk`, `en`, `de`, …).
 * Adapters never invent one: they hand their raw claim (a source's own label
 * like `English`, a code like `en-US`, or an ISO-639-3 code like `eng`) to
 * [normalizeLanguage], and only a KNOWN mapping yields a value. Unknown →
 * null → the Edition stores no language (unknown), which the language filter
 * NEVER hides — a source that does not declare a language must not make its
 * books vanish under a monoglot's filter.
 */

const PRIMARY_TAG = /^[a-z]{2,8}$/

/** Two-letter canonical tags we know directly (verbatim pass-through). */
const CANONICAL_TAGS = new Set([
  'uk', 'en', 'de', 'fr', 'es', 'ru', 'pl', 'it', 'pt', 'nl',
  'sv', 'da', 'no', 'fi', 'cs', 'sk', 'hu', 'ro', 'bg', 'el',
  'hr', 'sr', 'sl', 'et', 'lv', 'lt', 'tr', 'ar', 'he', 'zh', 'ja',
])

/** Full-name (and primary-tag alias) → canonical tag. */
const BY_NAME: Record<string, string> = {
  ukrainian: 'uk', uk: 'uk',
  english: 'en', en: 'en',
  german: 'de', de: 'de',
  french: 'fr', fr: 'fr',
  spanish: 'es', es: 'es',
  russian: 'ru', ru: 'ru',
  polish: 'pl', pl: 'pl',
  italian: 'it', it: 'it',
  portuguese: 'pt', pt: 'pt',
  dutch: 'nl', nl: 'nl',
  swedish: 'sv', sv: 'sv',
  danish: 'da', da: 'da',
  norwegian: 'no', no: 'no',
  finnish: 'fi', fi: 'fi',
  czech: 'cs', cs: 'cs',
  slovak: 'sk', sk: 'sk',
  hungarian: 'hu', hu: 'hu',
  romanian: 'ro', ro: 'ro',
  bulgarian: 'bg', bg: 'bg',
  greek: 'el', el: 'el',
  croatian: 'hr', hr: 'hr',
  serbian: 'sr', sr: 'sr',
  slovenian: 'sl', sl: 'sl',
  estonian: 'et', et: 'et',
  latvian: 'lv', lv: 'lv',
  lithuanian: 'lt', lt: 'lt',
  turkish: 'tr', tr: 'tr',
  arabic: 'ar', ar: 'ar',
  hebrew: 'he', he: 'he',
  chinese: 'zh', zh: 'zh',
  japanese: 'ja', ja: 'ja',
}

/** ISO-639-3 / ISO-639-2 → canonical tag (the `eng`-style claims). */
const ISO_639_3: Record<string, string> = {
  ukr: 'uk', eng: 'en', deu: 'de', ger: 'de', fra: 'fr', fre: 'fr',
  spa: 'es', rus: 'ru', pol: 'pl', ita: 'it', por: 'pt', nld: 'nl',
  dut: 'nl', swe: 'sv', dan: 'da', nor: 'no', fin: 'fi', ces: 'cs',
  cze: 'cs', slk: 'sk', slo: 'sk', hun: 'hu', ron: 'ro', rum: 'ro',
  bul: 'bg', ell: 'el', gre: 'el', hrv: 'hr', srp: 'sr', slv: 'sl',
  est: 'et', lav: 'lv', lit: 'lt', tur: 'tr', ara: 'ar', heb: 'he',
  zho: 'zh', chi: 'zh', jpn: 'ja',
}

/**
 * Normalizes a raw language claim to its canonical BCP-47 primary tag.
 *
 * Accepted inputs (case-insensitive):
 *  - canonical primary tags: `uk`, `en`, `de`, … (kept verbatim);
 *  - full primary-tag strings: `en-US`, `uk_UA` (the primary tag wins);
 *  - full English names: `English`, `Ukrainian`, `German`, …;
 *  - ISO-639-3 / ISO-639-2 codes for the same languages: `eng`, `ukr`.
 *
 * Anything else — an unknown name, garbage, blank — is null. The caller
 * stores undefined for null: unknown, never guessed.
 */
export function normalizeLanguage(raw: string | null | undefined): string | null {
  if (raw === null || raw === undefined) return null
  const cleaned = raw.trim().toLowerCase()
  if (cleaned === '') return null

  // Full primary-tag strings / locale variants: the primary tag wins.
  const primary = cleaned.split(/[-_]/)[0] ?? ''
  if (PRIMARY_TAG.test(primary)) {
    if (BY_NAME[primary] !== undefined) return BY_NAME[primary]
    if (CANONICAL_TAGS.has(primary)) return primary
    if (ISO_639_3[primary] !== undefined) return ISO_639_3[primary]
  }

  // A full language name that is not already a code (e.g. "english").
  if (BY_NAME[cleaned] !== undefined) return BY_NAME[cleaned]
  if (ISO_639_3[cleaned] !== undefined) return ISO_639_3[cleaned]
  if (PRIMARY_TAG.test(cleaned) && CANONICAL_TAGS.has(cleaned)) return cleaned
  return null
}