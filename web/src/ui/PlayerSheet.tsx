import { useEffect, useState } from 'react'
import type { AudioEngine } from '../player/audioEngine'
import type { EngineState } from '../player/engine'
import { useTranslate } from '../i18n/locale'

export function PlayerSheet({ engine, onClose }: { engine: AudioEngine; onClose: () => void }) {
  const t = useTranslate()
  const [state, setState] = useState<EngineState>(engine.getState())
  useEffect(() => engine.subscribe(setState), [engine])

  const isPlaying = state.status === 'playing'
  return (
    <div style={{ position: 'fixed', inset: 0, background: 'var(--bg)', zIndex: 50, display: 'flex', flexDirection: 'column', padding: '16px' }}>
      <button onClick={onClose} style={{ alignSelf: 'flex-start', background: 'none', border: 'none', color: 'var(--accent)', fontSize: 16 }}>{t('close')}</button>
      <h2 style={{ marginTop: 16 }}>{state.isCompleted ? t('completed') : t('chapter', { n: state.chapterIndex + 1 })}</h2>
      <p style={{ color: 'var(--fg-dim)', fontSize: 14 }}>{t('position', { time: `${Math.floor(state.positionSeconds / 60)}:${String(Math.floor(state.positionSeconds % 60)).padStart(2, '0')}` })}</p>
      <input
        type="range"
        min={0}
        max={100}
        value={state.positionSeconds}
        onChange={(e) => engine.seek(Number(e.target.value))}
        style={{ width: '100%', margin: '16px 0' }}
      />
      <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
        <button onClick={() => engine.skip(-15)}>⏪ 15s</button>
        <button onClick={() => (isPlaying ? engine.pause() : engine.play())} style={{ fontSize: 24, padding: '8px 24px', borderRadius: 999, background: 'var(--accent)', color: '#000', border: 'none' }}>
          {isPlaying ? '⏸' : '▶'}
        </button>
        <button onClick={() => engine.skip(15)}>15s ⏩</button>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button onClick={() => engine.prevChapter()}>{t('prev')}</button>
        <button onClick={() => engine.nextChapter()}>{t('next')}</button>
      </div>
      <label style={{ marginTop: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
        {t('speed')}
        <select value={state.speed} onChange={(e) => engine.setSpeed(Number(e.target.value))}>
          {[0.75, 1, 1.25, 1.5, 1.75, 2].map((s) => (
            <option key={s} value={s}>
              {s}×
            </option>
          ))}
        </select>
      </label>
      {state.status === 'unavailable' && <p style={{ color: 'var(--bad)', marginTop: 12 }}>{t('bookUnavailable')}</p>}
    </div>
  )
}
