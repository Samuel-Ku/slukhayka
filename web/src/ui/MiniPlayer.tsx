import { useEffect, useState } from 'react'
import type { AudioEngine } from '../player/audioEngine'
import type { EngineState } from '../player/engine'

export function MiniPlayer({ engine, onExpand }: { engine: AudioEngine; onExpand: () => void }) {
  const [state, setState] = useState<EngineState>(engine.getState())
  useEffect(() => engine.subscribe(setState), [engine])
  if (state.status === 'idle') return null
  const isPlaying = state.status === 'playing'
  return (
    <div
      onClick={onExpand}
      style={{
        position: 'fixed',
        bottom: 'calc(env(safe-area-inset-bottom) + 56px)',
        left: 8,
        right: 8,
        background: 'var(--surface)',
        border: '1px solid var(--line)',
        borderRadius: 12,
        padding: '8px 12px',
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        cursor: 'pointer',
        zIndex: 40,
      }}
    >
      <span style={{ flex: 1, fontSize: 14, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
        Розділ {state.chapterIndex + 1} · {Math.floor(state.positionSeconds / 60)}:{String(Math.floor(state.positionSeconds % 60)).padStart(2, '0')}
      </span>
      <button
        onClick={(e) => {
          e.stopPropagation()
          isPlaying ? engine.pause() : engine.play()
        }}
        style={{ background: 'var(--accent)', color: '#000', border: 'none', borderRadius: 999, padding: '6px 12px' }}
      >
        {isPlaying ? '⏸' : '▶'}
      </button>
      <button
        onClick={(e) => {
          e.stopPropagation()
          engine.skip(15)
        }}
        style={{ background: 'none', border: 'none', color: 'var(--fg-dim)' }}
      >
        +15s
      </button>
    </div>
  )
}
