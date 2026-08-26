import { initializeAppCheck, ReCaptchaV3Provider, type AppCheck } from 'firebase/app-check'
import type { FirebaseApp } from 'firebase/app'

/**
 * spec-43/T2 — App Check for web (reCAPTCHA v3), the web sibling of
 * Android's installAppCheckIfConfigured: installed only when a site key is
 * configured, silent degrade otherwise. Firestore rules already refuse
 * token-less writes, so a missing key means read-only public crowd data —
 * never a crash.
 */
export function installAppCheckIfConfigured(
  app: FirebaseApp,
  siteKey: string | undefined,
): AppCheck | null {
  if (!siteKey) return null
  try {
    return initializeAppCheck(app, {
      provider: new ReCaptchaV3Provider(siteKey),
      isTokenAutoRefreshEnabled: true,
    })
  } catch {
    return null
  }
}
