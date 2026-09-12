/**
 * spec-51 (#742) — the web First Language Choice sheet: the one-time question
 * «якими мовами хочеш книжки», every language of the loaded catalog on by
 * default, with the quick actions «Лише українські» and «Усі». Answers are
 * terminal (the caller persists the preference and the answered marker).
 *
 * The a11y contract mirrors DeleteBookSheet: the heading takes focus on open.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslate } from '../i18n/locale'
import { LANGUAGE_LABELS } from './contentLanguagePrefs'

export function FirstLanguageChoiceSheet({ languages, onAnswer }: {
  languages: readonly string[]
  onAnswer: (selection: string[]) => void
}) {
  const t = useTranslate()
  const headingRef = useRef<HTMLHeadingElement | null>(null)
  const [selected, setSelected] = useState<Set<string>>(() => new Set(languages))

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  const toggle = (code: string): void =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(code)) next.delete(code)
      else next.add(code)
      return next
    })

  // Everything on means «Усі» — carried as the empty set, exactly like the
  // filter's "empty = all".
  const done = (): void => onAnswer(selected.size >= languages.length ? [] : [...selected])

  return (
    <div className="lib-dialog-backdrop">
      <div className="lib-sheet" role="dialog" aria-modal="true" aria-label={t('firstLanguageChoiceTitle')}>
        <div className="lib-sheet-head">
          <h2 ref={headingRef} tabIndex={-1} className="lib-sheet-title">
            {t('firstLanguageChoiceTitle')}
          </h2>
        </div>
        <p className="lib-sheet-hint">{t('firstLanguageChoiceBody')}</p>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, margin: '8px 0' }}>
          {languages.map((code) => (
            <label key={code} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input type="checkbox" checked={selected.has(code)} onChange={() => toggle(code)} />
              <span>{LANGUAGE_LABELS[code] ?? code}</span>
            </label>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
          <button type="button" className="tabheader-btn" onClick={() => onAnswer(['uk'])}>
            {t('firstLanguageChoiceUkOnly')}
          </button>
          <button type="button" className="tabheader-btn" onClick={() => onAnswer([])}>
            {t('firstLanguageChoiceAll')}
          </button>
          <button type="button" className="tabheader-btn" onClick={done}>
            {t('firstLanguageChoiceDone')}
          </button>
        </div>
      </div>
    </div>
  )
}
