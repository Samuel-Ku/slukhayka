import { initializeApp, getApps, type FirebaseApp } from 'firebase/app'
import type { AuthGateway } from '../identity/listenerIdentity'
import { FirebaseAuthGateway } from './authGateway'
import { installAppCheckIfConfigured } from './appCheck'
import { readFirebaseConfig } from './config'

/**
 * spec-43/T2 — deployment wiring: build the auth gateway from env config,
 * installing App Check on the way. No config → null gateway → the identity
 * core degrades to a `local-…` profile.
 */
export async function createAuthGateway(env: ImportMetaEnv): Promise<AuthGateway | null> {
  const config = readFirebaseConfig(env)
  if (config === null) return null
  const app: FirebaseApp = getApps().at(0) ?? initializeApp(config)
  installAppCheckIfConfigured(app, env.VITE_RECAPTCHA_SITE_KEY)
  return FirebaseAuthGateway.create(config)
}
