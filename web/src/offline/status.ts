/**
 * W6.2 (#593) — the honest offline signal: navigator.onLine plus the
 * browser's online/offline events. The UI never fabricates availability —
 * it shows «Офлайн» when the network is down and derives «у кеші» from the
 * real Cache API (see OfflineAudioPrimer.cachedStreamUrls).
 */
import { useEffect, useState } from 'react'

function isOnlineNow(): boolean {
  return typeof navigator === 'undefined' || navigator.onLine !== false
}

/** True while the browser believes it has a network connection. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(isOnlineNow)
  useEffect(() => {
    const on = (): void => setOnline(true)
    const off = (): void => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])
  return online
}