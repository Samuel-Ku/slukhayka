/**
 * spec-43/T2 — the web deployment's Firebase knobs, read from Vite env
 * (`VITE_FIREBASE_*`). Pure so tests pin which keys are required; a
 * deployment without them simply runs the degraded local identity instead
 * of crashing at import time.
 */
export interface WebFirebaseConfig {
  readonly apiKey: string
  readonly authDomain: string
  readonly projectId: string
  readonly storageBucket: string
  readonly appId: string
}

export interface EnvLike {
  [key: string]: string | undefined
}

export function readFirebaseConfig(env: EnvLike): WebFirebaseConfig | null {
  const apiKey = env.VITE_FIREBASE_API_KEY
  const projectId = env.VITE_FIREBASE_PROJECT_ID
  const appId = env.VITE_FIREBASE_APP_ID
  if (!apiKey || !projectId || !appId) return null
  return {
    apiKey,
    projectId,
    appId,
    authDomain: env.VITE_FIREBASE_AUTH_DOMAIN ?? `${projectId}.firebaseapp.com`,
    storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET ?? `${projectId}.appspot.com`,
  }
}
